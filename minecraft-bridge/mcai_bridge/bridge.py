"""连接与会话管理：把「一台 Minecraft 客户端」抽象成一个 :class:`BridgeSession`。

职责：

* 校验 ``hello`` 握手（协议版本、鉴权令牌）
* 维护每个会话的最新状态快照与最近事件环形缓冲（供 LLM 用 ``mc_query`` 回看）
* 把 LLM 的动作请求编码成 ``action`` 报文发出去，并等待 ``action_result``
* 处理断线与重连，失败所有挂起中的动作请求
* 全局限速，避免 AI 疯狂刷动作把自己的客户端卡死
"""

from __future__ import annotations

import asyncio
import json
import logging
import time
from collections import deque
from typing import Any, Callable, Optional

from . import protocol as P
from .ws import WebSocketConnection, WebSocketServer

logger = logging.getLogger("plugin.mcai_bridge.bridge")


class ActionError(Exception):
    """动作执行失败（模组回传了错误，或超时，或没有可用连接）。"""


class BridgeSession:
    """一台 Minecraft 客户端对应一个会话。"""

    def __init__(self, conn: WebSocketConnection) -> None:
        self.conn = conn
        self.session_id: str = ""
        self.player_name: str = ""
        self.player_uuid: str = ""
        self.mod_version: str = ""
        self.mc_version: str = ""
        self.forge_version: str = ""
        self.server_name: str = ""
        self.singleplayer: bool = False
        self.capabilities: list[str] = []

        self.authenticated = False
        self.state: Optional[dict[str, Any]] = None
        self.state_updated_at: float = 0.0
        self.events: deque[dict[str, Any]] = deque(maxlen=120)
        self.connected_at: float = time.time()

        self._pending: dict[str, asyncio.Future] = {}
        self._action_times: deque[float] = deque(maxlen=200)
        self.actions_sent = 0
        self.last_action_at = 0.0

    # -------------------------------------------------------------- 元信息

    @property
    def label(self) -> str:
        return self.player_name or self.conn.remote

    @property
    def alive(self) -> bool:
        return not self.conn.closed

    def describe(self) -> dict[str, Any]:
        """给 LLM 看的会话摘要。"""
        return {
            "session_id": self.session_id,
            "player": self.player_name,
            "server": self.server_name or ("单机世界" if self.singleplayer else "未知"),
            "singleplayer": self.singleplayer,
            "mc_version": self.mc_version,
            "mod_version": self.mod_version,
            "connected": self.alive,
            "capabilities": self.capabilities,
            "state_age_seconds": round(time.time() - self.state_updated_at, 1) if self.state_updated_at else None,
        }

    def compact_state(self) -> dict[str, Any]:
        """给 LLM 看的压缩状态（不含体量大的实体/背包明细）。"""
        state = self.state or {}
        player = state.get("player") or {}
        world = state.get("world") or {}
        look = state.get("look") or {}
        task = state.get("task")
        entities = state.get("entities") or []
        players = state.get("players") or []

        hostile = [e for e in entities if str(e.get("category", "")).upper() in ("HOSTILE", "MONSTER")]
        return {
            "session": self.describe(),
            "player": {
                "name": player.get("name"),
                "dimension": player.get("dimension"),
                "pos": player.get("pos"),
                "block_pos": player.get("blockPos"),
                "yaw": player.get("yaw"),
                "pitch": player.get("pitch"),
                "health": player.get("health"),
                "max_health": player.get("maxHealth"),
                "food": player.get("food"),
                "air": player.get("air"),
                "xp_level": player.get("xpLevel"),
                "game_mode": player.get("gameMode"),
                "on_ground": player.get("onGround"),
                "in_water": player.get("inWater"),
                "held_item": player.get("heldItem"),
                "offhand_item": player.get("offhandItem"),
                "selected_slot": player.get("selectedSlot"),
                "effects": player.get("effects"),
            },
            "world": world,
            "looking_at": look,
            "nearby_hostile_count": len(hostile),
            "nearby_hostiles": [self._brief_entity(e) for e in hostile[:8]],
            "nearby_entity_count": len(entities),
            "nearby_entities_sample": [self._brief_entity(e) for e in entities[:12]],
            "players_online": [
                {
                    "name": p.get("name"),
                    "distance": p.get("distance"),
                    "pos": p.get("pos"),
                    "health": p.get("health"),
                }
                for p in players
            ],
            "current_task": task,
            "queue_length": state.get("queueLength"),
            "bridge": state.get("bridge"),
            "state_age_seconds": round(time.time() - self.state_updated_at, 1) if self.state_updated_at else None,
        }

    @staticmethod
    def _brief_entity(entity: dict[str, Any]) -> dict[str, Any]:
        return {
            "type": entity.get("type"),
            "name": entity.get("name"),
            "distance": entity.get("distance"),
            "pos": entity.get("pos"),
            "health": entity.get("health"),
            "hostile": str(entity.get("category", "")).upper() in ("HOSTILE", "MONSTER"),
            "uuid": entity.get("uuid"),
        }

    def recent_events(self, limit: int = 20, kind: Optional[str] = None) -> list[dict[str, Any]]:
        items = list(self.events)
        if kind:
            items = [e for e in items if e.get("kind") == kind]
        return items[-limit:]

    # ---------------------------------------------------------------- 发送

    async def send(self, message: dict[str, Any]) -> bool:
        text = json.dumps(message, ensure_ascii=False, separators=(",", ":"))
        return await self.conn.send_text(text)

    def _rate_limit_ok(self, max_per_second: int) -> bool:
        now = time.time()
        while self._action_times and now - self._action_times[0] > 1.0:
            self._action_times.popleft()
        if len(self._action_times) >= max_per_second:
            return False
        self._action_times.append(now)
        return True

    async def request_action(
        self,
        action: str,
        params: Optional[dict[str, Any]] = None,
        *,
        timeout: float = 60.0,
        max_per_second: int = 20,
    ) -> dict[str, Any]:
        """下发一个动作并等待模组回传结果。

        :raises ActionError: 连接不可用、触发限速、动作失败或超时。
        """
        if not self.alive:
            raise ActionError(f"与 Minecraft 客户端（{self.label}）的连接已断开")
        if not self.authenticated:
            raise ActionError("会话尚未完成握手")
        if not self._rate_limit_ok(max_per_second):
            raise ActionError(f"动作触发限速（每秒最多 {max_per_second} 个），请稍后再试")

        action_id = P.new_action_id()
        loop = asyncio.get_running_loop()
        future: asyncio.Future = loop.create_future()
        self._pending[action_id] = future
        self.actions_sent += 1
        self.last_action_at = time.time()

        payload = P.action_message(action_id, action, params or {}, int(timeout * 1000))
        if not await self.send(payload):
            self._pending.pop(action_id, None)
            raise ActionError("动作下发失败：连接已关闭")

        try:
            result = await asyncio.wait_for(future, timeout=timeout + 5.0)
        except asyncio.TimeoutError as exc:
            self._pending.pop(action_id, None)
            raise ActionError(
                f"动作 {action} 超时（{timeout:.0f} 秒内没有收到结果）。"
                "这种情况多半不是「动作做得慢」，而是游戏那边根本没在推进 —— 最常见的原因是"
                "游戏窗口失去焦点（单机时世界会暂停，点回游戏窗口即可），其次是客户端已经卡死或断线。"
                "可以用 mc_task_status 看模组最后在做什么：如果连任务状态都停在很久以前，基本就是前者。"
            ) from exc
        finally:
            self._pending.pop(action_id, None)

        if not result.get("ok", False):
            raise ActionError(str(result.get("error") or f"动作 {action} 执行失败"))
        return result

    def resolve_action(self, action_id: str, result: dict[str, Any]) -> None:
        future = self._pending.pop(action_id, None)
        if future is not None and not future.done():
            future.set_result(result)

    def fail_all_pending(self, reason: str) -> None:
        for action_id, future in list(self._pending.items()):
            if not future.done():
                future.set_result({"ok": False, "error": reason, "actionId": action_id})
        self._pending.clear()


class BridgeManager:
    """管理所有 Minecraft 连接，对上层（MaiBot 插件）暴露统一接口。"""

    def __init__(
        self,
        *,
        host: str,
        port: int,
        path: str,
        auth_token: str,
        max_actions_per_second: int = 20,
        heartbeat_seconds: int = 15,
        idle_timeout: float = 90.0,
        on_event: Optional[Callable[[BridgeSession, dict[str, Any]], Any]] = None,
        on_state: Optional[Callable[[BridgeSession, dict[str, Any]], Any]] = None,
        on_ready: Optional[Callable[[BridgeSession], Any]] = None,
        on_lost: Optional[Callable[[BridgeSession, int, str], Any]] = None,
    ) -> None:
        self.host = host
        self.port = port
        self.path = path or "/mc"
        self.auth_token = auth_token or ""
        self.max_actions_per_second = max_actions_per_second
        self.heartbeat_seconds = heartbeat_seconds
        self.idle_timeout = idle_timeout

        self.on_event = on_event
        self.on_state = on_state
        self.on_ready = on_ready
        self.on_lost = on_lost

        self.sessions: list[BridgeSession] = []
        self._by_conn: dict[int, BridgeSession] = {}
        self._server: Optional[WebSocketServer] = None
        self._counter = 0

    # ------------------------------------------------------------ 生命周期

    @property
    def running(self) -> bool:
        return self._server is not None

    @property
    def bound_port(self) -> int:
        return self._server.bound_port if self._server else self.port

    @property
    def url(self) -> str:
        return f"ws://{self.host}:{self.bound_port}{self.path}"

    async def start(self) -> int:
        if self._server is not None:
            return self._server.bound_port
        self._server = WebSocketServer(
            host=self.host,
            port=self.port,
            path=self.path,
            on_connect=self._handle_connect,
            on_message=self._handle_message,
            on_disconnect=self._handle_disconnect,
            heartbeat_interval=float(self.heartbeat_seconds),
            idle_timeout=self.idle_timeout,
        )
        port = await self._server.start()
        return port

    async def stop(self) -> None:
        if self._server is None:
            return
        server = self._server
        self._server = None
        for session in list(self.sessions):
            session.fail_all_pending("桥接已关闭")
        await server.stop()
        self.sessions.clear()
        self._by_conn.clear()

    # ---------------------------------------------------------------- 会话

    def get(self, session_id: str = "", player: str = "") -> Optional[BridgeSession]:
        """按会话 ID 或玩家名查找会话；都不给时返回最近接入的在线会话。"""
        if session_id:
            for s in self.sessions:
                if s.session_id == session_id and s.alive:
                    return s
        if player:
            needle = player.strip().lower()
            for s in self.sessions:
                if s.player_name.lower() == needle and s.alive:
                    return s
            for s in self.sessions:
                if needle in s.player_name.lower() and s.alive:
                    return s
        alive = [s for s in self.sessions if s.alive and s.authenticated]
        return alive[-1] if alive else None

    def require(self, session_id: str = "", player: str = "") -> BridgeSession:
        session = self.get(session_id, player)
        if session is None:
            if not self.sessions:
                raise ActionError(
                    "当前没有任何 Minecraft 客户端连接到 MaiBot。"
                    "请确认游戏已启动、模组已加载，并且 config/mcai_bridge-client.toml 里的 serverUrl "
                    f"指向 {self.url}"
                )
            names = ", ".join(s.label for s in self.sessions if s.alive) or "（无在线会话）"
            raise ActionError(f"找不到指定的 Minecraft 客户端。当前在线：{names}")
        return session

    def all_describe(self) -> list[dict[str, Any]]:
        return [s.describe() for s in self.sessions]

    # -------------------------------------------------------------- 回调实现

    async def _handle_connect(self, conn: WebSocketConnection) -> None:
        self._counter += 1
        session = BridgeSession(conn)
        session.session_id = f"mc-{self._counter}-{int(time.time())}"
        conn.session_id = session.session_id
        self.sessions.append(session)
        self._by_conn[id(conn)] = session
        logger.info("Minecraft 客户端建立 TCP/WS 连接，等待 hello：%s（会话 %s）", conn.remote, session.session_id)

    async def _handle_message(self, conn: WebSocketConnection, text: str) -> None:
        session = self._by_conn.get(id(conn))
        if session is None:
            return
        try:
            message = json.loads(text)
        except json.JSONDecodeError as exc:
            logger.warning("收到无法解析的报文（%s）: %s", conn.remote, exc)
            return
        if not isinstance(message, dict):
            return

        version = message.get("v")
        if isinstance(version, int) and version != P.PROTOCOL_VERSION:
            logger.warning("协议版本不匹配：模组 %s，插件 %s", version, P.PROTOCOL_VERSION)
            await session.send(P.hello_ack(session.session_id, ok=False,
                                           error=f"协议版本不匹配：模组 v{version}，插件 v{P.PROTOCOL_VERSION}，请更新后重试"))
            await conn.close(1008, "协议版本不匹配")
            return

        msg_type = P.msg_type(message)
        data = P.get_data(message)

        if msg_type == P.T_HELLO:
            await self._handle_hello(session, data)
        elif msg_type == P.T_STATE:
            session.state = data
            session.state_updated_at = time.time()
            await self._maybe_callback(self.on_state, session, data)
        elif msg_type == P.T_EVENT:
            event = dict(data)
            event.setdefault("at", time.time())
            session.events.append(event)
            await self._maybe_callback(self.on_event, session, event)
        elif msg_type == P.T_ACTION_RESULT:
            session.resolve_action(str(data.get("actionId") or message.get("id") or ""), data)
        elif msg_type == P.T_PONG:
            pass
        elif msg_type == P.T_LOG:
            logger.debug("[mod %s] %s", session.label, data.get("message"))
        else:
            logger.debug("收到未处理的报文类型：%s", msg_type)

    async def _handle_hello(self, session: BridgeSession, data: dict[str, Any]) -> None:
        token = str(data.get("token") or "")
        if self.auth_token and token != self.auth_token:
            logger.warning("鉴权失败：来自 %s 的令牌不正确", session.conn.remote)
            await session.send(P.hello_ack(session.session_id, ok=False, error="鉴权令牌不正确"))
            await session.conn.close(1008, "鉴权失败")
            return

        mod_info = data.get("mod") if isinstance(data.get("mod"), dict) else {}
        player_info = data.get("player") if isinstance(data.get("player"), dict) else {}

        session.player_name = str(player_info.get("name") or "")
        session.player_uuid = str(player_info.get("uuid") or "")
        session.mod_version = str(mod_info.get("version") or "")
        session.mc_version = str(mod_info.get("mc") or "")
        session.forge_version = str(mod_info.get("forge") or "")
        session.singleplayer = bool(data.get("singleplayer"))
        session.server_name = str(data.get("serverAddress") or data.get("serverName") or "")
        caps = data.get("capabilities")
        session.capabilities = [str(c) for c in caps] if isinstance(caps, list) else []
        session.authenticated = True
        session.conn.player_name = session.player_name

        await session.send(P.hello_ack(
            session.session_id,
            heartbeat_seconds=self.heartbeat_seconds,
            max_actions_per_second=self.max_actions_per_second,
            protocol=P.PROTOCOL_VERSION,
        ))
        logger.info(
            "Minecraft 客户端握手完成：玩家=%s 世界=%s 单机=%s 模组=%s MC=%s 会话=%s",
            session.player_name or "?", session.server_name or "?", session.singleplayer,
            session.mod_version or "?", session.mc_version or "?", session.session_id,
        )
        await self._maybe_callback(self.on_ready, session)

    async def _handle_disconnect(self, conn: WebSocketConnection, code: int, reason: str) -> None:
        session = self._by_conn.pop(id(conn), None)
        if session is None:
            return
        session.authenticated = False
        session.fail_all_pending(f"与 Minecraft 客户端的连接已断开（code={code} {reason}）")
        if session in self.sessions:
            self.sessions.remove(session)
        await self._maybe_callback(self.on_lost, session, code, reason)

    @staticmethod
    async def _maybe_callback(callback: Optional[Callable], *args: Any) -> None:
        if callback is None:
            return
        try:
            result = callback(*args)
            if asyncio.iscoroutine(result):
                await result
        except Exception as exc:  # noqa: BLE001
            logger.error("桥接回调异常: %s", exc, exc_info=True)
