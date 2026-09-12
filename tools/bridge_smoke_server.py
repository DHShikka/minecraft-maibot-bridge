"""跨语言冒烟测试的服务端一半。

流程（由 tools/run-smoke.ps1 串起来）：

1. 本脚本用真实插件代码起一个 WebSocket 服务端，把端口写进 ``.research/smoke-port.txt``；
2. 外部启动 Java 冒烟客户端 ``BridgeSmokeTest`` 去连它（那份代码就是模组里用的 WsClient）；
3. 本脚本等待连接、主动下发动作验证往返、把观察到的结果写到 ``.research/smoke-server.json``。

这样验证的是「Java 手写 WebSocket 客户端 ↔ Python asyncio 服务端」的真实互通性，
而不是各自单测。
"""

from __future__ import annotations

import asyncio
import importlib.util
import json
import sys
import time
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parent.parent
PLUGIN_DIR = ROOT / "minecraft-bridge"
STUB_SDK = ROOT / "tools" / "stub_sdk"
PORT_FILE = ROOT / ".research" / "smoke-port.txt"
RESULT_FILE = ROOT / ".research" / "smoke-server.json"

sys.path.insert(0, str(STUB_SDK))
sys.path.insert(0, str(PLUGIN_DIR))
sys.path.insert(0, str(Path(__file__).resolve().parent))

from check_plugin import StubContext  # noqa: E402


def load_plugin() -> Any:
    spec = importlib.util.spec_from_file_location("mcai_bridge_plugin_smoke", PLUGIN_DIR / "plugin.py")
    module = importlib.util.module_from_spec(spec)  # type: ignore[arg-type]
    spec.loader.exec_module(module)  # type: ignore[union-attr]
    return module.create_plugin()


async def main() -> int:
    result: dict[str, Any] = {"success": False}
    plugin = load_plugin()
    ctx = StubContext()
    plugin._set_context(ctx)
    plugin.config.server.host = "127.0.0.1"
    plugin.config.server.port = 0
    plugin.config.chat.force_reply = False  # 冒烟测试只关心链路，不需要唤醒麦麦

    await plugin.on_load()
    if plugin.bridge is None:
        RESULT_FILE.write_text(json.dumps({"success": False, "error": "server failed to start"}), "utf-8")
        return 1

    port = plugin.bridge.bound_port
    PORT_FILE.write_text(str(port), "utf-8")
    print(f"[server] listening on ws://127.0.0.1:{port}/mc", flush=True)

    try:
        # ---- 等待 Java 客户端接入并完成握手
        deadline = time.time() + 40
        while time.time() < deadline:
            session = plugin.bridge.get()
            if session is not None and session.authenticated:
                break
            await asyncio.sleep(0.1)
        else:
            result["error"] = "java client never completed handshake"
            return 1

        session = plugin.bridge.get()
        result["handshake"] = session.describe()
        result["player"] = session.player_name
        result["modVersion"] = session.mod_version
        result["mcVersion"] = session.mc_version
        result["serverName"] = session.server_name
        result["capabilities"] = session.capabilities
        print(f"[server] handshake ok: {session.describe()}", flush=True)

        # ---- 握手后插件会自动拉一次状态；等 Java 侧回传结果
        deadline = time.time() + 15
        while time.time() < deadline:
            if session.state_updated_at > 0:
                break
            await asyncio.sleep(0.05)
        result["receivedState"] = session.state is not None
        result["stateAgeSeconds"] = round(time.time() - session.state_updated_at, 2) if session.state_updated_at else None
        state = session.state or {}
        result["statePlayerPos"] = ((state.get("player") or {}).get("pos"))
        result["stateHealth"] = ((state.get("player") or {}).get("health"))

        # ---- 插件主动下发一个动作，验证「服务端 → Java 客户端 → 回传」的往返
        try:
            echo = await session.request_action("chat", {"message": "来自麦麦的问候"}, timeout=10.0)
            result["actionRoundTrip"] = echo
        except Exception as exc:  # noqa: BLE001
            result["actionRoundTrip"] = {"ok": False, "error": str(exc)}

        # ---- 等 Java 侧上报的游戏事件
        deadline = time.time() + 15
        while time.time() < deadline:
            chats = [e for e in session.events if e.get("kind") == "chat"]
            if chats:
                break
            await asyncio.sleep(0.05)
        chats = [e for e in session.events if e.get("kind") == "chat"]
        result["receivedChatEvent"] = bool(chats)
        if chats:
            result["chatSender"] = chats[0].get("sender")
            result["chatText"] = chats[0].get("text")

        # ---- 通过完整的插件链路再验证一次（工具 → action → 回传）
        tool = await plugin.mc_move_to(x=7, y=64, z=9)
        result["toolCall"] = {"success": tool.get("success"), "content": str(tool.get("content"))[:200]}

        # ---- 伪装成麦麦说话，验证出站网关能落到 Java 客户端
        gateway = getattr(plugin, "minecraft_gateway")
        outbound = await gateway({
            "message_id": "smoke-1",
            "timestamp": str(time.time()),
            "platform": "minecraft",
            "message_info": {"user_info": {"user_id": "mc:bot", "user_nickname": "麦麦"},
                             "group_info": {"group_id": "mc:冒烟测试世界", "group_name": "冒烟测试世界"},
                             "additional_config": {}},
            "raw_message": [{"type": "text", "data": "这是一条出站测试消息"}],
            "processed_plain_text": "这是一条出站测试消息",
        })
        result["outbound"] = {"success": outbound.get("success"), "error": outbound.get("error")}

        result["success"] = True
        return 0
    finally:
        try:
            await plugin.on_unload()
        except Exception:  # noqa: BLE001
            pass
        RESULT_FILE.write_text(json.dumps(result, ensure_ascii=False, indent=2), "utf-8")
        print(f"[server] wrote {RESULT_FILE}", flush=True)


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
