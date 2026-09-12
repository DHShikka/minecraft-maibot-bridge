"""真实链路驱动：用真实插件代码起服务端，等真实 Minecraft 客户端连上，然后像麦麦一样下发动作。

和 ``bridge_smoke_server.py`` 的区别：那个是「造一个假客户端来测插件」，
这个是「插件去驱动真客户端」—— 客户端是真的在游戏里跑，动作是真的在游戏里发生。

用法（客户端要先起来，见 ``tools/run-client.mjs --selftest``）：
::

    python tools/live_server.py                 # 端口 8765，等客户端进世界后自动跑一遍
    python tools/live_server.py --keep          # 跑完不退出，方便手工继续调

推荐开两个终端：先起本脚本，再起客户端。反过来也行 —— 模组会自动重连（实测退避重试）。

产物：``.research/live-result.json``（每一步的 ok / 耗时 / 错误 / 回执）
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
RESULT_FILE = ROOT / ".research" / "live-result.json"

sys.path.insert(0, str(STUB_SDK))
sys.path.insert(0, str(PLUGIN_DIR))
sys.path.insert(0, str(Path(__file__).resolve().parent))

from check_plugin import StubContext  # noqa: E402


def load_plugin() -> Any:
    spec = importlib.util.spec_from_file_location("mcai_bridge_plugin_live", PLUGIN_DIR / "plugin.py")
    module = importlib.util.module_from_spec(spec)  # type: ignore[arg-type]
    spec.loader.exec_module(module)  # type: ignore[union-attr]
    return module.create_plugin()


#: 自检动作序列。每项是 (说明, 动作, 参数, 超时秒)。
#: 顺序有依赖：先给材料 → **等一个 tick**（指令是发给服务器的，不是立刻生效）→ 再合成；
#: 先放工作台，再合成 3x3 的木镐。
STEPS: list[tuple[str, str, dict[str, Any], float]] = [
    ("看状态（进世界后世界快照能不能拿到）", "get_state", {}, 20),
    ("执行指令（顺便把时间设成白天）", "command", {"command": "time set day"}, 20),
    ("给自己 8 个原木、4 个圆石", "command", {"command": "give @s oak_log 8"}, 20),
    ("等指令生效（指令发给服务器，要等它处理）", "wait", {"ms": 900}, 20),
    ("确认材料到账", "get_state", {}, 20),
    ("合成木板（2x2，不需要工作台；12 个是算好的：木棍 2 + 工作台 4 + 木镐 3）",
     "craft", {"item": "oak_planks", "count": 12}, 60),
    ("合成木棍", "craft", {"item": "stick", "count": 4}, 60),
    ("合成工作台", "craft", {"item": "crafting_table", "count": 1}, 60),
    ("把工作台放到面前一格（验证 ~ 相对坐标）",
     "place", {"x": "~", "y": "~", "z": "~1", "item": "crafting_table"}, 60),
    ("★ 合成木镐（3x3，需要附近有工作台）", "craft", {"item": "wooden_pickaxe", "count": 1}, 90),
    ("挖 3 个泥土（验证「挖够就停」，不能再挖 60 多个）",
     "mine_blocks", {"block": "#minecraft:dirt", "count": 3}, 90),
    ("任务组：一次下发一整段流程", "script", {
        "name": "自检任务组",
        "steps": [
            {"action": "command", "params": {"command": "give @s cobblestone 8"}},
            {"action": "wait", "params": {"ms": 900}},
            {"action": "chat", "params": {"message": "任务组：开始"}},
            {"action": "mine_blocks", "params": {"block": "#minecraft:dirt", "count": 2}},
            {"action": "craft", "params": {"item": "stone_pickaxe", "count": 1}, "optional": True},
            {"if": {"condition": {"has": {"item": "cobblestone", "count": 1}}},
             "then": [{"action": "chat", "params": {"message": "任务组：圆石到手了"}}],
             "else": [{"action": "chat", "params": {"message": "任务组：没拿到圆石"}}]},
            {"action": "chat", "params": {"message": "任务组：结束"}},
        ],
    }, 150),
    ("最终状态", "get_state", {}, 20),

    # ---- 抵御：制造真实威胁，看模组自己怎么处理
    ("给自己一把铁剑（验证防御时会自动换武器）", "command", {"command": "give @s iron_sword 1"}, 20),
    ("把时间设为午夜（白天僵尸会被晒死）", "command", {"command": "time set midnight"}, 20),
    ("召唤僵尸当威胁", "command", {"command": "summon zombie ~ ~ ~3"}, 20),
    ("再召唤两只", "command", {"command": "summon zombie ~2 ~ ~3"}, 20),
    ("再召唤一只", "command", {"command": "summon zombie ~-2 ~ ~3"}, 20),
    ("等它们刷出来", "wait", {"ms": 1500}, 20),
    ("确认附近有敌对生物", "scan_entities", {"radius": 16, "filter": "hostile"}, 20),
    ("★ 抵御：模组自己决定打 / 吃 / 撤", "defend",
     {"radius": 16, "maxKills": 4, "retreatHealth": 6}, 150),
    ("确认威胁被清掉了", "scan_entities", {"radius": 16, "filter": "hostile"}, 20),

    # ---- 抵御的第二条分支：血量低而且有吃的 → 应该先吃再打
    ("给自己几个面包（验证低血自动进食）", "command", {"command": "give @s bread 3"}, 20),
    ("把自己打到只剩 8 点血", "command", {"command": "effect give @s instant_damage 1 1"}, 20),
    ("等效果生效", "wait", {"ms": 900}, 20),
    ("看看现在多少血", "get_state", {}, 20),
    ("再召唤两只僵尸", "command", {"command": "summon zombie ~ ~ ~4"}, 20),
    ("再召唤一只", "command", {"command": "summon zombie ~2 ~ ~4"}, 20),
    ("等它们靠近", "wait", {"ms": 1500}, 20),
    ("★ 抵御（血 8 < 阈值 12 且有面包 → 应该先吃）", "defend",
     {"radius": 16, "maxKills": 4, "retreatHealth": 12}, 150),
    ("确认威胁被清掉了", "scan_entities", {"radius": 16, "filter": "hostile"}, 20),

    # ---- 熔炼：铁矿石 → 铁锭（没有这一步，生存模式做什么都缺铁）
    ("给自己熔炉 + 生铁 + 煤", "command", {"command": "give @s furnace 1"}, 20),
    ("给 3 个生铁", "command", {"command": "give @s raw_iron 3"}, 20),
    ("给煤当燃料", "command", {"command": "give @s coal 2"}, 20),
    ("等指令生效", "wait", {"ms": 900}, 20),
    ("把熔炉放到面前一格（顺便看 place 可靠不可靠）",
     "place", {"x": "~", "y": "~", "z": "~2", "item": "furnace"}, 60),
    # 自检环境里再用 setblock 兜底一次：这一段要验的是「熔炼」，不是「放置」
    # （放置已经在第 9 步验过了）。位置选脚下方块，保证不会和玩家碰撞。
    ("兜底：直接用 setblock 放一个熔炉", "command",
     {"command": "setblock ~2 ~-1 ~2 minecraft:furnace"}, 20),
    ("等方块出现", "wait", {"ms": 600}, 20),
    ("★ 熔炼 3 个铁锭（验证熔炼）", "smelt",
     {"item": "iron_ingot", "count": 3, "radius": 8}, 150),
    ("确认铁锭到账", "get_state", {}, 20),

    # ---- 自动换工具：手上拿着剑，去挖一块需要镐的石头
    ("在脚边放一块石头", "command", {"command": "setblock ~1 ~-1 ~1 minecraft:stone"}, 20),
    ("给一把木镐（不主动装备）", "command", {"command": "give @s wooden_pickaxe 1"}, 20),
    ("等指令生效", "wait", {"ms": 900}, 20),
    ("★ 挖掉那块石头（手上是剑，应该自动换成镐）", "mine_blocks",
     {"block": "stone", "count": 1, "radius": 8}, 60),
]

#: 硬结论：结束时必须在背包里的东西。
EXPECTED_ITEMS = {"minecraft:wooden_pickaxe": 1}

#: 「默认地形 + 生存 + 不作弊」场景：全程零指令，从砍树开始。
#: 用 --survival 启动，并且客户端要带 MCAI_TERRAIN=normal MCAI_CHEATS=false。
SURVIVAL_STEPS: list[tuple[str, str, dict[str, Any], float]] = [
    ("看看出生点在哪、什么地形", "get_state", {}, 20),
    ("先扫一下周围的矿石（看这个世界值不值得挖）", "scan_blocks",
     {"block": "#minecraft:iron_ores", "radius": 24, "yRadius": 24, "limit": 5}, 30),
    ("★★ 向下挖 20 层阶梯矿道（挖穿泥土到石头层）", "dig_shaft",
     {"depth": 20}, 300),
    ("看看挖到哪了、挖了多少层", "get_state", {}, 20),
    ("再往下挖一段，挖到铁矿就停", "dig_shaft",
     {"depth": 30, "stopAt": "iron_ore"}, 300),
    ("最后看一眼背包和位置", "get_state", {}, 20),
]

#: Baritone 联动场景：用聊天发 Baritone 指令（它拦截 # 开头的消息），看角色动不动。
#: 客户端要装 baritone.jar。用超平坦 + 作弊，快速看清角色位置变化。
BARITONE_STEPS: list[tuple[str, str, dict[str, Any], float]] = [
    ("记录起点", "get_state", {}, 20),
    ("先看 Baritone 在不在：发 #help（它会把帮助打进聊天）", "chat", {"message": "#help"}, 20),
    ("等它回话", "wait", {"ms": 1500}, 20),
    ("★ 寻路：让 Baritone 走到 (60, -60, 60)", "chat", {"message": "#goto 60 -60 60"}, 20),
    ("等它走一会儿", "wait", {"ms": 12000}, 20),
    ("看看走到哪了（和起点比应该移动了）", "get_state", {}, 20),
    ("★ 停止：#stop", "chat", {"message": "#stop"}, 20),
    ("等它停稳", "wait", {"ms": 2000}, 20),
    ("★ 挖矿：让它挖泥土（注意：Baritone 的 mine 不接数量）", "chat",
     {"message": "#mine dirt"}, 20),
    ("等它挖一会儿", "wait", {"ms": 15000}, 20),
    ("看背包里有没有泥土", "get_state", {}, 20),
    ("最后停下", "chat", {"message": "#stop"}, 20),
]


async def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--wait", type=float, default=600.0, help="等客户端进世界的最长秒数")
    parser.add_argument("--keep", action="store_true", help="跑完保持服务端运行")
    parser.add_argument("--survival", action="store_true",
                        help="跑「默认地形 + 生存 + 不作弊」场景（客户端要带 "
                             "MCAI_TERRAIN=normal MCAI_CHEATS=false）")
    parser.add_argument("--baritone", action="store_true",
                        help="跑 Baritone 联动场景（客户端 mods 里要有 baritone.jar）")
    args = parser.parse_args()

    global STEPS
    if args.survival:
        STEPS = SURVIVAL_STEPS
        print("[live] 生存模式场景：全程零指令，从砍树开始", flush=True)
    elif args.baritone:
        STEPS = BARITONE_STEPS
        print("[live] Baritone 联动场景：用聊天发 # 指令驱动 Baritone", flush=True)

    result: dict[str, Any] = {"success": False, "steps": []}
    plugin = load_plugin()
    ctx = StubContext()
    plugin._set_context(ctx)
    plugin.config.server.host = "127.0.0.1"
    plugin.config.server.port = args.port
    plugin.config.chat.force_reply = False   # 只测链路，不唤醒 LLM

    await plugin.on_load()
    if plugin.bridge is None:
        print("[live] 服务端起不来（端口被占用？）")
        return 1
    print(f"[live] 服务端已就绪：{plugin.bridge.url}", flush=True)
    print(f"[live] 等客户端进入世界（最多 {args.wait:.0f} 秒）…", flush=True)

    try:
        deadline = time.time() + args.wait
        session = None
        while time.time() < deadline:
            session = plugin.bridge.get()
            if session is not None and session.authenticated:
                break
            await asyncio.sleep(0.2)
        if session is None or not session.authenticated:
            result["error"] = "客户端没有连上（游戏起来了吗？进世界了吗？）"
            print("[live] " + result["error"], flush=True)
            return 1

        print(f"[live] 客户端已连接：{session.describe()}", flush=True)
        result["handshake"] = session.describe()
        result["player"] = session.player_name
        result["modVersion"] = session.mod_version

        # 等第一次状态快照
        deadline = time.time() + 30
        while time.time() < deadline and not session.state_updated_at:
            await asyncio.sleep(0.1)
        state = session.state or {}
        player = state.get("player") or {}
        result["spawn"] = {"pos": player.get("pos"), "dimension": player.get("dimension"),
                           "health": player.get("health"), "gameMode": player.get("gameMode")}
        print(f"[live] 出生点：{result['spawn']}", flush=True)

        failures = 0
        for i, (label, action, params, timeout) in enumerate(STEPS, 1):
            print(f"\n[live] ({i}/{len(STEPS)}) {label} -> {action}", flush=True)
            started = time.time()
            entry: dict[str, Any] = {"index": i, "label": label, "action": action, "params": params}
            try:
                reply = await session.request_action(action, params, timeout=timeout)
                entry["ok"] = True
                entry["result"] = reply.get("result")
                print(f"[live]   OK  ({time.time() - started:.1f}s)", flush=True)
                body = json.dumps(reply.get("result"), ensure_ascii=False)
                print(f"[live]   {body[:400]}", flush=True)
            except Exception as exc:  # noqa: BLE001
                entry["ok"] = False
                entry["error"] = str(exc)
                failures += 1
                print(f"[live]   失败 ({time.time() - started:.1f}s)：{exc}", flush=True)
            entry["elapsed"] = round(time.time() - started, 2)
            result["steps"].append(entry)

        # 最后再抓一次状态，看背包（注意：inventory 在状态快照的**顶层**，不在 player 里）
        final = session.state or {}
        slots = ((final.get("inventory") or {}).get("slots")) or []
        counts: dict[str, int] = {}
        for slot in slots:
            item = slot.get("item") or {}
            if item.get("id"):
                counts[item["id"]] = counts.get(item["id"], 0) + int(item.get("count") or 0)
        result["finalInventory"] = counts
        print(f"\n[live] 最终背包：{counts}", flush=True)

        # 硬结论：木镐真的做出来了吗
        missing = {k: v for k, v in EXPECTED_ITEMS.items() if counts.get(k, 0) < v}

        # 硬结论：抵御真的清掉怪了吗（而且没把自己搭进去）
        defends = [s for s in result["steps"] if s["action"] == "defend"]
        for index, defend_step in enumerate(defends, 1):
            detail = defend_step.get("result") or {}
            kills = detail.get("kills")
            log = detail.get("log") or []
            print(f"[live] 抵御 #{index}：清掉 {kills} 个，血量 {detail.get('healthStart')} → "
                  f"{detail.get('healthEnd')}（最低 {detail.get('healthLowest')}），"
                  f"还剩 {len(detail.get('remaining') or [])} 个威胁", flush=True)
            for line in log[:12]:
                print(f"[live]    {line}", flush=True)
            if not isinstance(kills, int) or kills < 1:
                missing[f"defend{index}_kills"] = f"期望至少清掉 1 个，实际 {kills}"
            if detail.get("remaining"):
                missing[f"defend{index}_remaining"] = f"附近还留着 {len(detail['remaining'])} 个威胁"

        # 第二次抵御是「血低 + 有面包」，必须能看到它先吃
        if len(defends) >= 2:
            log2 = (defends[1].get("result") or {}).get("log") or []
            if not any("吃" in str(line) for line in log2):
                missing["defend2_ate"] = f"血低且有面包时应该先吃，实际过程：{log2}"

        # 硬结论：向下挖真的挖下去了吗（这是「够不到铁矿」那块短板）
        dig_step = next((s for s in result["steps"] if s["action"] == "dig_shaft"), None)
        if dig_step is not None:
            detail = dig_step.get("result") or {}
            print(f"[live] 挖矿道结果：下了 {detail.get('dug')} 层（要求 {detail.get('requestedDepth')}），"
                  f"现在 y={((detail.get('pos') or {}).get('y'))}，"
                  f"途中矿石 {len(detail.get('found') or [])} 处", flush=True)
            for line in (detail.get("log") or [])[-6:]:
                print(f"[live]    {line}", flush=True)
            if detail.get("stoppedBecause"):
                print(f"[live]    停下原因：{detail['stoppedBecause']}", flush=True)
            if int(detail.get("dug") or 0) < 5:
                missing["dig_shaft"] = f"至少要挖下去 5 层，实际 {detail.get('dug')}"

        # 硬结论：熔炼真的把生铁变成铁锭了吗
        smelt_step = next((s for s in result["steps"] if s["action"] == "smelt"), None)
        if smelt_step is not None:
            detail = smelt_step.get("result") or {}
            print(f"[live] 熔炼结果：收到 {detail.get('received')} 个 {detail.get('item')}"
                  f"（原料 {detail.get('input')}，燃料 {detail.get('fuel')}）", flush=True)
            if int(counts.get("minecraft:iron_ingot", 0)) < 3:
                missing["smelt_iron"] = f"期望至少 3 个铁锭，实际背包里有 {counts.get('minecraft:iron_ingot', 0)}"

        # 硬结论：挖需要工具的方块时，有没有自动换工具
        mine_steps = [s for s in result["steps"] if s["action"] == "mine_blocks"]
        if mine_steps:
            last_mine = mine_steps[-1].get("result") or {}
            dropped = [m for m in (last_mine.get("mined") or []) if m.get("dropped")]
            print(f"[live] 自动换工具验证：挖到 {last_mine.get('minedCount')} 个，"
                  f"其中正常掉落 {len(dropped)} 个", flush=True)
            if last_mine.get("minedCount", 0) < 1 or not dropped:
                missing["auto_tool"] = f"挖石头应当自动换镐并正常掉落，实际：{last_mine}"

        result["missingExpected"] = missing
        if missing:
            print(f"[live] !! 未达预期：{missing}", flush=True)
        else:
            print(f"[live] 预期都达成：{EXPECTED_ITEMS} + 抵御有击杀", flush=True)

        result["success"] = failures == 0 and not missing
        result["failures"] = failures
        RESULT_FILE.write_text(json.dumps(result, ensure_ascii=False, indent=1), "utf-8")
        print(f"\n[live] 结果已写入 {RESULT_FILE}（失败 {failures} 项）", flush=True)

        if args.keep:
            print("[live] --keep：保持运行，Ctrl+C 结束", flush=True)
            while True:
                await asyncio.sleep(1)
        return 0 if failures == 0 else 1
    finally:
        if not args.keep:
            try:
                await plugin.on_unload()
            except Exception:  # noqa: BLE001
                pass


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
