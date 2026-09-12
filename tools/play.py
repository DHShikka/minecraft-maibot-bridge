"""交互式驱动：一步一步下指令，按玩家的**真实位置**算绝对坐标。

和 ``live_server.py`` 的区别：那个跑写死的场景（适合回归），这个跑「临时想出来的下一步」。

为什么需要它：浇筑黑曜石这类活儿要求「倒在哪一格」是**精确的世界坐标**，
而模组的 ``~`` 相对坐标是「相对玩家当前位置」—— 玩家为了装岩浆走了一圈之后，
``~`` 指向哪里就说不清了。所以驱动的正确姿势是：

    先 get_state 拿到玩家站在哪 → 自己算目标坐标 → 用绝对坐标下发

用法：
::

    python tools/play.py                 # 端口 8765，等客户端，然后开始轮询命令文件
    python tools/play.py --port 8765

然后往 ``.research/play-cmd.json`` 里写一步（``seq`` 每次递增）：

.. code-block:: json

    {"seq": 1, "steps": [
        {"action": "get_state", "params": {}},
        {"action": "move_to", "params": {"x": 10, "y": 64, "z": -3}, "timeout": 120}
    ]}

结果写到 ``.research/play-out.json``：``{"seq": 1, "results": [...]}``。

``seq`` 比上次大才会执行，所以重复写同一个文件不会重复执行。
"""

from __future__ import annotations

import argparse
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
CMD_FILE = ROOT / ".research" / "play-cmd.json"
OUT_FILE = ROOT / ".research" / "play-out.json"
LOG_FILE = ROOT / ".research" / "play.log"

sys.path.insert(0, str(STUB_SDK))
sys.path.insert(0, str(PLUGIN_DIR))
sys.path.insert(0, str(Path(__file__).resolve().parent))

from check_plugin import StubContext  # noqa: E402


def load_plugin() -> Any:
    spec = importlib.util.spec_from_file_location("mcai_bridge_plugin_play", PLUGIN_DIR / "plugin.py")
    module = importlib.util.module_from_spec(spec)  # type: ignore[arg-type]
    spec.loader.exec_module(module)  # type: ignore[union-attr]
    return module.create_plugin()


def log(message: str) -> None:
    line = f"[play] {message}"
    print(line, flush=True)
    try:
        with LOG_FILE.open("a", encoding="utf-8") as handle:
            handle.write(line + "\n")
    except OSError:
        pass


def read_command() -> dict[str, Any] | None:
    """读命令文件。写一半/写坏了就返回 None，下一轮再读。"""
    try:
        raw = CMD_FILE.read_text(encoding="utf-8")
    except (OSError, UnicodeDecodeError):
        return None
    if not raw.strip():
        return None
    try:
        data = json.loads(raw)
    except json.JSONDecodeError:
        return None
    if not isinstance(data, dict):
        return None
    seq = data.get("seq")
    if not isinstance(seq, int):
        return None
    steps = data.get("steps")
    if not isinstance(steps, list) or not steps:
        return None
    return data


async def run_steps(session: Any, data: dict[str, Any], plugin: Any = None) -> dict[str, Any]:
    results: list[dict[str, Any]] = []
    for step in data["steps"]:
        action = str(step.get("action") or "")
        params = step.get("params") or {}
        timeout = float(step.get("timeout") or 60.0)
        label = str(step.get("label") or action)
        started = time.time()
        entry: dict[str, Any] = {"label": label, "action": action, "params": params}
        try:
            # mc_* 走**插件工具**（能拿到 mc_query / mc_state 这类加工过的结果），
            # 其余走原始游戏动作。
            tool = getattr(plugin, action, None) if (plugin is not None and action.startswith("mc_")) else None
            if callable(tool):
                outcome = await tool(**params)
                entry["ok"] = bool(outcome.get("success"))
                entry["result"] = outcome
            else:
                reply = await session.request_action(action, params, timeout=timeout)
                entry["ok"] = True
                entry["result"] = reply.get("result")
        except Exception as exc:  # noqa: BLE001
            entry["ok"] = False
            entry["error"] = str(exc)
        entry["elapsed"] = round(time.time() - started, 2)
        results.append(entry)
        verdict = "OK " if entry["ok"] else "FAIL"
        # 失败的**工具**调用没有 error 字段（原因在 result 里）——以前这里会打成 "null"，
        # 白白丢掉最关键的那句「为什么失败」。
        body = json.dumps(entry.get("result") if entry["ok"] else entry.get("error") or entry.get("result"),
                          ensure_ascii=False)
        log(f"{verdict} ({entry['elapsed']}s) {label}: {body[:600]}")
    return {"seq": data["seq"], "at": time.time(), "results": results}


async def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--wait", type=float, default=900.0, help="等客户端进世界的秒数")
    args = parser.parse_args()

    LOG_FILE.write_text("", encoding="utf-8")
    OUT_FILE.write_text("{}", encoding="utf-8")

    plugin = load_plugin()
    ctx = StubContext()
    plugin._set_context(ctx)
    plugin.config.server.host = "127.0.0.1"
    plugin.config.server.port = args.port
    plugin.config.chat.force_reply = False

    await plugin.on_load()
    if plugin.bridge is None:
        log(f"服务端起不来（端口 {args.port} 被占用？）")
        return 1
    log(f"服务端已就绪：{plugin.bridge.url}，等客户端进世界…")

    deadline = time.time() + args.wait
    session = None
    while time.time() < deadline:
        session = plugin.bridge.get()
        if session is not None and session.authenticated:
            break
        await asyncio.sleep(0.3)
    if session is None or not session.authenticated:
        log("客户端没连上，退出")
        await plugin.on_unload()
        return 1

    log(f"客户端已连接：{session.describe()}")
    while time.time() < deadline and not session.state_updated_at:
        await asyncio.sleep(0.1)
    state = session.state or {}
    player = state.get("player") or {}
    log(f"出生点：{player.get('pos')} 维度 {player.get('dimension')} "
        f"血量 {player.get('health')} 模式 {player.get('gameMode')}")

    last_seq = 0
    idle = 0
    try:
        while True:
            data = read_command()
            if data is None or int(data["seq"]) <= last_seq:
                idle += 1
                if idle % 600 == 0:  # 每 5 分钟报一次「我还活着」
                    log(f"待命中（已执行到 seq={last_seq}），当前玩家 "
                        f"{((session.state or {}).get('player') or {}).get('pos')}")
                await asyncio.sleep(0.5)
                continue
            idle = 0
            # 客户端没连上就先别执行：游戏还在加载时写进去的指令不该被「用掉」。
            fresh = plugin.bridge.get() if plugin.bridge else None
            if fresh is not None and fresh.authenticated and fresh is not session:
                session = fresh
                log(f"客户端（重新）接入：{session.describe()}")
            if session is None or not session.authenticated:
                if idle == 0:
                    log(f"seq={int(data['seq'])} 先挂着：客户端还没连上")
                idle = 1
                await asyncio.sleep(0.5)
                continue
            last_seq = int(data["seq"])
            log(f"=== seq={last_seq}：{len(data['steps'])} 步 ===")
            outcome = await run_steps(session, data, plugin)
            OUT_FILE.write_text(json.dumps(outcome, ensure_ascii=False, indent=1), encoding="utf-8")
            log(f"=== seq={last_seq} 结束 ===")
    except asyncio.CancelledError:
        raise
    finally:
        try:
            await plugin.on_unload()
        except Exception:  # noqa: BLE001
            pass


if __name__ == "__main__":
    try:
        sys.exit(asyncio.run(main()))
    except KeyboardInterrupt:
        sys.exit(0)
