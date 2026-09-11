"""纯 asyncio 实现的 RFC 6455 WebSocket 服务端。

为什么不用 ``websockets`` / ``aiohttp``：MaiBot 的插件 Runner 运行在独立子进程里，
插件依赖需要由 Host 侧的依赖流水线安装（``uv pip install`` / ``pip install``）。
把协议实现内联进来可以让插件做到**零第三方依赖**，装上就能跑，
不会因为环境缺包或版本冲突而加载失败。

支持：

* HTTP Upgrade 握手 + ``Sec-WebSocket-Accept`` 校验
* 文本帧、二进制帧（忽略内容）、分片消息、ping / pong、close 握手
* 掩码校验（客户端发来的帧必须带掩码，否则按协议关闭）
* 单条消息大小上限、握手超时、心跳保活与半开连接探测
* 多连接并发（同一时间可以有多台 Minecraft 客户端接入）
"""

from __future__ import annotations

import asyncio
import base64
import hashlib
import logging
import os
import struct
import time
from typing import Awaitable, Callable, Optional

logger = logging.getLogger("plugin.mcai_bridge.ws")

WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

OP_CONT = 0x0
OP_TEXT = 0x1
OP_BINARY = 0x2
OP_CLOSE = 0x8
OP_PING = 0x9
OP_PONG = 0xA

#: 单条消息最大字节数（默认 4 MiB）。MaiBot 下发的动作报文很小，
#: Minecraft 上报的状态快照也远小于这个值，超限一律断开。
MAX_MESSAGE_BYTES = 4 * 1024 * 1024

#: 握手阶段允许的最大请求头大小
MAX_HANDSHAKE_BYTES = 16 * 1024

CLOSE_NORMAL = 1000
CLOSE_PROTOCOL_ERROR = 1002
CLOSE_UNSUPPORTED_DATA = 1003
CLOSE_POLICY_VIOLATION = 1008
CLOSE_MESSAGE_TOO_BIG = 1009

OnMessage = Callable[["WebSocketConnection", str], Awaitable[None]]
OnConnect = Callable[["WebSocketConnection"], Awaitable[None]]
OnDisconnect = Callable[["WebSocketConnection", int, str], Awaitable[None]]


class WebSocketError(Exception):
    """协议层错误，携带应当回给对端的关闭码。"""

    def __init__(self, message: str, code: int = CLOSE_PROTOCOL_ERROR) -> None:
        super().__init__(message)
        self.code = code


class WebSocketConnection:
    """一条已建立的 WebSocket 连接。"""

    def __init__(
        self,
        reader: asyncio.StreamReader,
        writer: asyncio.StreamWriter,
        *,
        path: str = "/",
        remote: str = "?",
    ) -> None:
        self._reader = reader
        self._writer = writer
        self.path = path
        self.remote = remote
        self.opened_at = time.time()
        self.closed = False
        self.close_code: Optional[int] = None
        self.close_reason = ""

        #: 由上层填充的会话标识（例如 Minecraft 玩家名）
        self.session_id: str = ""
        self.player_name: str = ""
        self.meta: dict = {}

        self._write_lock = asyncio.Lock()
        self.last_received_at = time.time()
        self.last_pong_at = time.time()
        self.sent_messages = 0
        self.received_messages = 0
        self._fragments = bytearray()
        self._fragment_opcode: Optional[int] = None

    # ------------------------------------------------------------------ 发送

    async def send_text(self, text: str) -> bool:
        """发送一个文本帧。连接已关闭时返回 False。"""
        return await self._send_frame(OP_TEXT, text.encode("utf-8"))

    async def send_ping(self, payload: bytes = b"") -> bool:
        return await self._send_frame(OP_PING, payload)

    async def _send_frame(self, opcode: int, payload: bytes) -> bool:
        if self.closed:
            return False
        if len(payload) > MAX_MESSAGE_BYTES:
            raise WebSocketError("待发送的消息过大", CLOSE_MESSAGE_TOO_BIG)

        header = bytearray()
        header.append(0x80 | opcode)  # FIN + opcode
        length = len(payload)
        if length < 126:
            header.append(length)
        elif length <= 0xFFFF:
            header.append(126)
            header += struct.pack("!H", length)
        else:
            header.append(127)
            header += struct.pack("!Q", length)

        try:
            async with self._write_lock:
                self._writer.write(bytes(header) + payload)
                await self._writer.drain()
        except (ConnectionError, asyncio.IncompleteReadError, RuntimeError, OSError):
            self.closed = True
            return False
        self.sent_messages += 1
        return True

    async def close(self, code: int = CLOSE_NORMAL, reason: str = "") -> None:
        """发送 close 帧并关闭底层连接。"""
        if self.closed:
            return
        payload = struct.pack("!H", code) + reason.encode("utf-8")[:123]
        try:
            await self._send_frame(OP_CLOSE, payload)
        except Exception:  # noqa: BLE001 - 关闭流程不应抛异常
            pass
        await self._shutdown()
        self.close_code = code
        self.close_reason = reason

    async def _shutdown(self) -> None:
        self.closed = True
        try:
            self._writer.close()
            await asyncio.wait_for(self._writer.wait_closed(), timeout=3)
        except Exception:  # noqa: BLE001
            pass

    # ------------------------------------------------------------------ 接收

    async def receive_text(self) -> Optional[str]:
        """读取下一条文本消息。连接结束或收到 close 帧时返回 None。"""
        while True:
            frame = await self._read_frame()
            if frame is None:
                return None
            fin, opcode, payload = frame

            if opcode == OP_CLOSE:
                code = CLOSE_NORMAL
                reason = ""
                if len(payload) >= 2:
                    code = struct.unpack("!H", payload[:2])[0]
                    reason = payload[2:].decode("utf-8", errors="replace")
                self.close_code = code
                self.close_reason = reason
                await self._send_frame(OP_CLOSE, payload[:125] if payload else struct.pack("!H", CLOSE_NORMAL))
                await self._shutdown()
                return None

            if opcode == OP_PING:
                await self._send_frame(OP_PONG, payload)
                continue

            if opcode == OP_PONG:
                self.last_pong_at = time.time()
                continue

            if opcode == OP_BINARY:
                # 本协议只使用文本帧；二进制帧直接忽略内容但保持连接
                if not fin:
                    await self._discard_fragments()
                continue

            if opcode == OP_TEXT:
                if fin:
                    self.received_messages += 1
                    return payload.decode("utf-8", errors="replace")
                self._fragment_opcode = opcode
                self._fragments = bytearray(payload)
                continue

            if opcode == OP_CONT:
                if self._fragment_opcode is None:
                    raise WebSocketError("收到孤立的续帧")
                if len(self._fragments) + len(payload) > MAX_MESSAGE_BYTES:
                    raise WebSocketError("分片消息超过上限", CLOSE_MESSAGE_TOO_BIG)
                self._fragments += payload
                if fin:
                    text = bytes(self._fragments).decode("utf-8", errors="replace")
                    self._fragment_opcode = None
                    self._fragments = bytearray()
                    self.received_messages += 1
                    return text
                continue

            raise WebSocketError(f"未知的 opcode: {opcode}")

    async def _discard_fragments(self) -> None:
        """丢弃一个分片中的二进制消息。"""
        while True:
            frame = await self._read_frame()
            if frame is None:
                return
            fin, opcode, _payload = frame
            if opcode in (OP_CLOSE, OP_PING, OP_PONG):
                continue
            if opcode not in (OP_CONT, OP_BINARY):
                return
            if fin:
                return

    async def _read_frame(self) -> Optional[tuple[bool, int, bytes]]:
        try:
            header = await self._reader.readexactly(2)
        except (asyncio.IncompleteReadError, ConnectionError, OSError):
            return None

        b0, b1 = header[0], header[1]
        fin = bool(b0 & 0x80)
        if b0 & 0x70:
            raise WebSocketError("RSV 位必须为 0（不支持扩展协商）")
        opcode = b0 & 0x0F
        masked = bool(b1 & 0x80)
        length = b1 & 0x7F

        if length == 126:
            length = struct.unpack("!H", await self._read_exact(2))[0]
        elif length == 127:
            length = struct.unpack("!Q", await self._read_exact(8))[0]
            if length >> 63:
                raise WebSocketError("帧长度非法")

        if length > MAX_MESSAGE_BYTES:
            raise WebSocketError("收到的帧过大", CLOSE_MESSAGE_TOO_BIG)

        # RFC 6455 §5.1：客户端发往服务端的帧必须使用掩码
        if not masked:
            raise WebSocketError("客户端帧缺少掩码")

        mask = await self._read_exact(4)
        payload = await self._read_exact(length) if length else b""
        if length:
            payload = bytes(payload[i] ^ mask[i & 3] for i in range(length))

        self.last_received_at = time.time()
        return fin, opcode, payload

    async def _read_exact(self, n: int) -> bytes:
        try:
            return await self._reader.readexactly(n)
        except (asyncio.IncompleteReadError, ConnectionError, OSError) as exc:
            raise WebSocketError("连接在读取帧数据时中断") from exc


class WebSocketServer:
    """极简 asyncio WebSocket 服务端。

    参数
    ----
    host / port
        监听地址。
    path
        只接受该路径的握手请求；``"/"`` 表示任意路径。
    on_connect / on_message / on_disconnect
        三个异步回调，分别在建连、收到文本消息、断开时触发。
    """

    def __init__(
        self,
        host: str,
        port: int,
        path: str,
        on_connect: OnConnect,
        on_message: OnMessage,
        on_disconnect: OnDisconnect,
        *,
        handshake_timeout: float = 10.0,
        heartbeat_interval: float = 15.0,
        idle_timeout: float = 60.0,
    ) -> None:
        self.host = host
        self.port = port
        self.path = path or "/"
        self.on_connect = on_connect
        self.on_message = on_message
        self.on_disconnect = on_disconnect
        self.handshake_timeout = handshake_timeout
        self.heartbeat_interval = heartbeat_interval
        self.idle_timeout = idle_timeout

        self._server: Optional[asyncio.AbstractServer] = None
        self._connections: set[WebSocketConnection] = set()
        self._tasks: set[asyncio.Task] = set()
        self.bound_port: int = port

    # ------------------------------------------------------------ 生命周期

    @property
    def connections(self) -> list[WebSocketConnection]:
        return list(self._connections)

    async def start(self) -> int:
        """启动监听，返回实际绑定的端口（port=0 时可用于随机端口）。"""
        self._server = await asyncio.start_server(
            self._handle_client,
            host=self.host,
            port=self.port,
            limit=MAX_HANDSHAKE_BYTES,
        )
        sockets = self._server.sockets or []
        if sockets:
            self.bound_port = sockets[0].getsockname()[1]
        logger.info("Minecraft 桥接 WebSocket 服务端已启动: ws://%s:%s%s", self.host, self.bound_port, self.path)
        return self.bound_port

    async def stop(self) -> None:
        """停止监听并关闭所有连接。"""
        if self._server is not None:
            self._server.close()
            try:
                await self._server.wait_closed()
            except Exception:  # noqa: BLE001
                pass
            self._server = None

        for conn in list(self._connections):
            await conn.close(CLOSE_NORMAL, "服务端关闭")
        self._connections.clear()

        for task in list(self._tasks):
            task.cancel()
        self._tasks.clear()
        logger.info("Minecraft 桥接 WebSocket 服务端已停止")

    # -------------------------------------------------------------- 内部实现

    async def _handle_client(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        peer = writer.get_extra_info("peername")
        remote = f"{peer[0]}:{peer[1]}" if isinstance(peer, tuple) and len(peer) >= 2 else str(peer)
        conn: Optional[WebSocketConnection] = None

        try:
            path = await asyncio.wait_for(self._handshake(reader, writer), timeout=self.handshake_timeout)
        except asyncio.TimeoutError:
            logger.warning("握手超时，断开 %s", remote)
            await self._abort(writer)
            return
        except _HandshakeRejected as exc:
            logger.warning("拒绝来自 %s 的连接: %s", remote, exc.reason)
            await self._reject(writer, exc.status, exc.reason)
            return
        except Exception as exc:  # noqa: BLE001
            logger.warning("握手失败（%s）: %s", remote, exc)
            await self._abort(writer)
            return

        conn = WebSocketConnection(reader, writer, path=path, remote=remote)
        self._connections.add(conn)
        logger.info("Minecraft 客户端已连接: %s%s", remote, path)

        try:
            await self.on_connect(conn)
        except Exception as exc:  # noqa: BLE001
            logger.error("on_connect 回调异常: %s", exc, exc_info=True)

        heartbeat = asyncio.create_task(self._heartbeat_loop(conn), name=f"ws-heartbeat-{remote}")
        self._tasks.add(heartbeat)

        # 每条消息都派发到独立任务里处理，而不是在读取循环里 await。
        # 这一点很重要：插件处理一条消息时经常需要「再问模组一句话并等回复」
        # （例如握手后拉取状态、执行动作等结果），如果读取循环被 await 占住，
        # 回复就永远读不进来 —— 会直接死锁。派发成任务后读取循环始终在收包。
        dispatch_tasks: set[asyncio.Task] = set()

        code = CLOSE_NORMAL
        reason = ""
        try:
            while True:
                text = await conn.receive_text()
                if text is None:
                    code = conn.close_code if conn.close_code is not None else CLOSE_NORMAL
                    reason = conn.close_reason
                    break
                if not text.strip():
                    continue
                task = asyncio.create_task(self._dispatch(conn, text),
                                           name=f"ws-handle-{remote}")
                dispatch_tasks.add(task)
                task.add_done_callback(dispatch_tasks.discard)
        except WebSocketError as exc:
            code = exc.code
            reason = str(exc)
            logger.warning("协议错误（%s）: %s", remote, exc)
            await conn.close(exc.code, str(exc)[:100])
        except asyncio.CancelledError:
            code = CLOSE_NORMAL
            reason = "服务端取消"
            await conn.close(CLOSE_NORMAL, reason)
            raise
        except Exception as exc:  # noqa: BLE001
            code = 1011
            reason = f"内部错误: {exc}"
            logger.error("连接处理异常: %s", exc, exc_info=True)
            await conn.close(1011, "内部错误")
        finally:
            heartbeat.cancel()
            self._tasks.discard(heartbeat)
            if dispatch_tasks:
                # 给正在处理中的消息一点收尾时间，避免「刚收到就断线」时任务被凭空取消
                try:
                    await asyncio.wait(set(dispatch_tasks), timeout=5)
                except Exception:  # noqa: BLE001
                    pass
            self._connections.discard(conn)
            conn.closed = True
            await conn._shutdown()  # noqa: SLF001 - 同一模块内部使用
            logger.info("Minecraft 客户端断开: %s（code=%s, reason=%s）", remote, code, reason)
            try:
                await self.on_disconnect(conn, code, reason)
            except Exception as exc:  # noqa: BLE001
                logger.error("on_disconnect 回调异常: %s", exc, exc_info=True)

    async def _dispatch(self, conn: WebSocketConnection, text: str) -> None:
        try:
            await self.on_message(conn, text)
        except asyncio.CancelledError:
            raise
        except Exception as exc:  # noqa: BLE001
            logger.error("处理消息时发生异常（%s）: %s", conn.remote, exc, exc_info=True)

    async def _heartbeat_loop(self, conn: WebSocketConnection) -> None:
        """定期 ping，并在长时间无任何数据时判定为半开连接。"""
        try:
            while not conn.closed:
                await asyncio.sleep(self.heartbeat_interval)
                if conn.closed:
                    return
                idle = time.time() - conn.last_received_at
                if idle > self.idle_timeout:
                    logger.warning("连接 %s 超过 %.0f 秒无数据，判定为假死并断开", conn.remote, idle)
                    await conn.close(CLOSE_NORMAL, "心跳超时")
                    return
                await conn.send_ping(struct.pack("!d", time.time()))
        except asyncio.CancelledError:
            pass
        except Exception as exc:  # noqa: BLE001
            logger.debug("心跳循环结束: %s", exc)

    async def _handshake(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> str:
        try:
            raw = await reader.readuntil(b"\r\n\r\n")
        except asyncio.LimitOverrunError as exc:
            raise _HandshakeRejected("请求头过大", status=431) from exc
        except asyncio.IncompleteReadError as exc:
            raise _HandshakeRejected("连接在握手完成前关闭", status=400) from exc

        text = raw.decode("latin-1")
        lines = text.split("\r\n")
        if not lines or not lines[0]:
            raise _HandshakeRejected("缺少请求行", status=400)

        parts = lines[0].split(" ")
        if len(parts) < 3 or parts[0].upper() != "GET":
            raise _HandshakeRejected("只支持 GET 升级请求", status=405)

        request_path = parts[1].split("?", 1)[0]
        if self.path not in ("/", "") and request_path.rstrip("/") != self.path.rstrip("/"):
            raise _HandshakeRejected(f"路径不匹配: {request_path}", status=404)

        headers: dict[str, str] = {}
        for line in lines[1:]:
            if not line:
                break
            if ":" not in line:
                continue
            name, _, value = line.partition(":")
            headers[name.strip().lower()] = value.strip()

        upgrade = headers.get("upgrade", "").lower()
        connection = headers.get("connection", "").lower()
        key = headers.get("sec-websocket-key")
        version = headers.get("sec-websocket-version", "")

        if "websocket" not in upgrade:
            raise _HandshakeRejected("缺少 Upgrade: websocket", status=400)
        if "upgrade" not in connection:
            raise _HandshakeRejected("缺少 Connection: Upgrade", status=400)
        if not key:
            raise _HandshakeRejected("缺少 Sec-WebSocket-Key", status=400)
        if version != "13":
            raise _HandshakeRejected(f"不支持的 WebSocket 版本: {version}", status=426)

        accept = base64.b64encode(hashlib.sha1((key + WS_GUID).encode("ascii")).digest()).decode("ascii")
        response = (
            "HTTP/1.1 101 Switching Protocols\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            f"Sec-WebSocket-Accept: {accept}\r\n"
            "Server: McAiBridge-MaiBot-Plugin\r\n"
            "\r\n"
        )
        writer.write(response.encode("latin-1"))
        await writer.drain()
        return request_path

    async def _abort(self, writer: asyncio.StreamWriter) -> None:
        try:
            writer.close()
            await writer.wait_closed()
        except Exception:  # noqa: BLE001
            pass

    async def _reject(self, writer: asyncio.StreamWriter, status: int, reason: str) -> None:
        body = f"{status} {reason}".encode("utf-8")
        phrase = {400: "Bad Request", 404: "Not Found", 405: "Method Not Allowed",
                  426: "Upgrade Required", 431: "Request Header Fields Too Large"}.get(status, "Bad Request")
        head = (
            f"HTTP/1.1 {status} {phrase}\r\n"
            "Content-Type: text/plain; charset=utf-8\r\n"
            f"Content-Length: {len(body)}\r\n"
            "Connection: close\r\n"
            "\r\n"
        ).encode("latin-1")
        try:
            writer.write(head + body)
            await writer.drain()
        except Exception:  # noqa: BLE001
            pass
        await self._abort(writer)


class _HandshakeRejected(Exception):
    def __init__(self, reason: str, status: int = 400) -> None:
        super().__init__(reason)
        self.reason = reason
        self.status = status


def default_port() -> int:
    """从环境变量读取默认端口，方便容器化部署。"""
    try:
        return int(os.environ.get("MCAI_BRIDGE_PORT", "8765"))
    except ValueError:
        return 8765
