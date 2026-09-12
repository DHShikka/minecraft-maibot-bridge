"""Minecraft AI 桥接插件 —— 让麦麦「住进」Minecraft。

架构
----
::

    Minecraft 客户端（Forge 1.20.1 模组 mcai_bridge）
        │  ws://<host>:<port>/mc      模组是客户端，本插件是服务端
        ▼
    本插件 ──► ctx.gateway.route_message()  ──► 麦麦的对话流（AI 看见了游戏里发生的事）
           ◄── LLM 调用 @Tool              ◄── 麦麦决定做什么
           ──► WebSocket action 报文        ──► 模组在游戏里真的执行

三条主线
--------
1. **感知**：游戏内聊天通过消息网关注入麦麦的聊天流，麦麦能像看群聊一样看到玩家说了什么；
   受伤、死亡、升级、换维度等事件通过 ``ctx.maisaka.context.append`` 追加为上下文。
2. **行动**：本插件向 LLM 暴露一整套 ``mc_*`` 工具（移动、挖矿、放置、战斗、说话、执行指令……），
   每次调用都会转成一条 WebSocket ``action`` 报文发给模组，并等待模组回传执行结果。
3. **主动**：玩家说话时可以选择让麦麦「主动接话」（``maisaka.proactive.trigger``），
   即使发言频率限制本来会拦下它，也能保证在游戏里及时响应。
"""

from __future__ import annotations

import asyncio
import difflib
import json
import logging
import sys
import time
from pathlib import Path
from typing import Any, ClassVar, Iterable, Optional

from maibot_sdk import (
    CONFIG_RELOAD_SCOPE_SELF,
    API,
    Command,
    Field,
    HomeCard,
    MaiBotPlugin,
    MessageGateway,
    PluginConfigBase,
    Tool,
)
from maibot_sdk.types import ToolParameterInfo, ToolParamType

# ---------------------------------------------------------------------------
#  让插件自己目录出现在 sys.path 上（**这一句不能删**）
# ---------------------------------------------------------------------------
# MaiBot 的 PluginLoader 加载插件时，加进 sys.path 的是「插件目录的父目录」
# 而不是插件目录本身：
#
#     plugin_loader.py:575-579
#         with self._temporary_sys_path_entry(plugin_dir.parent):
#             spec.loader.exec_module(module)
#
# 也就是说 sys.path 上是 plugins/，而 plugins/<插件名>/ 不在上面。
# 因此插件目录里的子包（mcai_bridge/）默认是 import 不到的，
# 会直接报 "No module named 'mcai_bridge'"、插件加载失败。
#
# 解决办法就是在这里把插件自己的目录插进 sys.path。
_PLUGIN_DIR = Path(__file__).resolve().parent
if str(_PLUGIN_DIR) not in sys.path:
    sys.path.insert(0, str(_PLUGIN_DIR))

try:
    from mcai_bridge import protocol as P
    from mcai_bridge.bridge import ActionError, BridgeManager, BridgeSession
except ModuleNotFoundError as _exc:  # pragma: no cover - 只在装错时触发
    _pkg = _PLUGIN_DIR / "mcai_bridge"
    if _pkg.is_dir():
        raise
    # 最可能的原因：复制插件时把子目录漏掉了。给出能直接照做的提示，
    # 而不是让用户对着一句 "No module named" 猜。
    raise ModuleNotFoundError(
        f"插件目录下缺少 mcai_bridge 子目录：{_pkg}\n"
        f"插件目录 {_PLUGIN_DIR} 当前内容："
        f"{sorted(p.name for p in _PLUGIN_DIR.iterdir())}\n"
        "请确认这四项都在：_manifest.json、plugin.py、mcai_bridge/、i18n/\n"
        "（复制插件时最容易漏掉的就是子目录 mcai_bridge/）"
    ) from _exc

logger = logging.getLogger("plugin.mcai_bridge")


# ============================================================================
#  配置
# ============================================================================


class PluginSection(PluginConfigBase):
    """插件基础配置。

    这一节**必须存在、且字段名必须叫 ``plugin``**：MaiBot 的
    ``runner_main._prepare_plugin_config_for_version_update()`` 会调用
    ``extract_plugin_config_version(default_config)``，而它硬性要求
    ``config["plugin"]["config_version"]`` 存在，否则抛 ``PluginConfigVersionError``，
    插件会以「插件初始化失败」告终（UI 上只显示这一句，看不出原因）。

    ``config_version`` 用于配置结构升级：以后改配置结构时把它递增，
    Runner 会用新默认值重建配置并只迁移仍然存在的字段。
    """

    __ui_label__ = "插件"
    __ui_icon__ = "package"
    __ui_order__ = 0

    enabled: bool = Field(default=True, description="是否启用 Minecraft 桥接插件")
    config_version: str = Field(default="1.0.0", description="配置版本，由插件维护；改动配置结构时递增")


class ServerSection(PluginConfigBase):
    """WebSocket 服务端"""

    __ui_label__ = "WebSocket 服务端"
    __ui_icon__ = "dns"
    __ui_order__ = 0

    host: str = Field(
        default="0.0.0.0",
        description="监听地址。模组和 MaiBot 在同一台机器时建议改成 127.0.0.1 更安全。",
        json_schema_extra={"placeholder": "0.0.0.0"},
    )
    port: int = Field(
        default=8765,
        description="监听端口。必须与模组配置里的 serverUrl 端口一致。",
    )
    path: str = Field(
        default="/mc",
        description="WebSocket 路径。必须与模组 serverUrl 的路径一致。",
        json_schema_extra={"placeholder": "/mc"},
    )
    account_id: str = Field(
        default="mc-server-1",
        description="机器人账号标识。用于把「出站消息」精确路由回本插件，"
                    "一般不需要改；同一台机器上跑多个 MaiBot 实例时才需要区分。",
        json_schema_extra={"placeholder": "mc-server-1"},
    )
    auth_token: str = Field(
        default="",
        description="鉴权令牌。留空表示不鉴权；填了的话模组配置里的 authToken 必须一致。",
        json_schema_extra={"placeholder": "留空表示不鉴权"},
    )
    heartbeat_seconds: int = Field(
        default=15,
        description="心跳间隔（秒），用于探测假死连接。",
    )
    idle_timeout_seconds: int = Field(
        default=90,
        description="多久没收到任何数据就判定连接已死并断开（秒）。",
    )


class ChatSection(PluginConfigBase):
    """游戏内聊天接入"""

    __ui_label__ = "游戏聊天"
    __ui_icon__ = "chat"
    __ui_order__ = 1

    enabled: bool = Field(
        default=True,
        description="是否把游戏内玩家聊天注入麦麦的对话流（关掉后麦麦就「听不见」游戏里说话了）。",
    )
    chat_mode: str = Field(
        default="group",
        description='按「群聊」还是「私聊」接入。group：整个服务器是一个聊天流，多个玩家共享上下文（推荐）；'
                    'private：每个玩家各自一个聊天流。',
        json_schema_extra={"placeholder": "group 或 private"},
    )
    server_group_id: str = Field(
        default="",
        description="自定义服务器标识（留空则自动使用世界/服务器地址）。用于区分不同存档的聊天流。",
        json_schema_extra={"placeholder": "留空自动生成"},
    )
    force_reply: bool = Field(
        default=True,
        description="玩家说话时是否强制唤醒麦麦思考一轮。"
                    "开启后即使发言频率控制本来会拦下回复，麦麦也会正常在游戏里回应（推荐开启）。",
    )
    ignore_own_messages: bool = Field(
        default=True,
        description="忽略由麦麦自己发出的游戏内消息，避免自问自答的循环。",
    )
    ignore_players: list[str] = Field(
        default_factory=list,
        description="不接入麦麦的玩家名单（按游戏内名字，忽略大小写）。",
    )
    command_prefix: str = Field(
        default="!ai",
        description="给麦麦下指令的聊天前缀。玩家在游戏里说「!ai 去挖点铁」时，"
                    "会把「去挖点铁」单独作为指令意图交给麦麦；留空则不做特殊处理。",
        json_schema_extra={"placeholder": "!ai"},
    )


class EventSection(PluginConfigBase):
    """游戏事件上报"""

    __ui_label__ = "游戏事件"
    __ui_icon__ = "bolt"
    __ui_order__ = 2

    report_events: bool = Field(
        default=True,
        description="是否把受伤、死亡、升级、换维度等游戏事件追加到麦麦的上下文里（只作观察，不会打断对话）。",
    )
    notify_on: list[str] = Field(
        default_factory=lambda: ["death", "health_critical"],
        description="哪些事件需要主动唤醒麦麦思考一轮。可选值：death、health_critical、kill、"
                    "damage、respawn、dimension_change、hungry、level_up。留空表示从不主动打扰。",
    )
    notify_cooldown_seconds: int = Field(
        default=30,
        description="同一类事件的主动提醒最小间隔（秒），防止被怪物连续攻击时刷屏。",
    )
    event_context_limit: int = Field(
        default=60,
        description="每个连接最多保留多少条最近事件，供 mc_query 回看。",
    )


class SafetySection(PluginConfigBase):
    """安全与限流"""

    __ui_label__ = "安全与限流"
    __ui_icon__ = "shield"
    __ui_order__ = 3

    max_actions_per_second: int = Field(
        default=20,
        description="每秒最多下发给游戏的动作数，防止 AI 疯狂操作把客户端卡死。",
    )
    max_action_timeout_seconds: int = Field(
        default=180,
        description="单个动作在插件侧的等待上限（秒）。超过后工具调用会返回超时错误。",
    )
    allow_actions: list[str] = Field(
        default_factory=list,
        description="动作白名单（留空表示全部允许）。填写动作名后，只有列表里的动作能被 AI 使用，"
                    "例如 [\"chat\", \"move_to\", \"get_state\"]。",
    )
    deny_actions: list[str] = Field(
        default_factory=list,
        description="动作黑名单，优先级低于白名单之外的一切判断，例如 [\"attack\", \"place\"]。",
    )
    greet_on_connect: bool = Field(
        default=True,
        description="客户端连上时，往聊天流里追加一条「某某进入了游戏」的上下文消息。",
    )


class MinecraftBridgeConfig(PluginConfigBase):
    """Minecraft AI 桥接插件配置

    ``plugin`` 必须是第一个字段，且名字固定 —— 见 :class:`PluginSection` 的说明。
    """

    plugin: PluginSection = Field(default_factory=PluginSection)
    server: ServerSection = Field(default_factory=ServerSection)
    chat: ChatSection = Field(default_factory=ChatSection)
    events: EventSection = Field(default_factory=EventSection)
    safety: SafetySection = Field(default_factory=SafetySection)


# ============================================================================
#  任务组脚本的本地校验
# ============================================================================
#
# 模组那边有完整的脚本解析器，报错信息也写得很详细。这里只做三件事，
# 目的是让 LLM 少跑一趟弯路：
#   1. 把「对象」和「JSON 字符串」两种传参形式统一成对象；
#   2. 挡掉一眼就能看出来的结构错误（没 steps、步骤既不是动作也不是控制流）；
#   3. 提前检查脚本里用到的动作名 —— 拼错的当场纠正，被禁用的当场拒绝。
# 真正的权限判定仍然在模组侧（Forge 配置），这里只是第二道闸门。

#: 脚本里代表控制流的键
SCRIPT_CONTROL_KEYS = ("repeat", "while", "if", "waitUntil")


def _parse_script_arg(raw: Any) -> tuple[Optional[dict[str, Any]], str]:
    """把工具参数统一成一个脚本对象。返回 (对象, 错误信息)，错误信息非空表示参数不可用。"""
    if raw is None:
        return None, "缺少参数 script（要执行的脚本对象）。"
    if isinstance(raw, dict):
        return raw, ""
    if isinstance(raw, str):
        text = raw.strip()
        if not text:
            return None, "参数 script 是空字符串。"
        try:
            value = json.loads(text)
        except json.JSONDecodeError as exc:
            return None, f"参数 script 不是合法 JSON：{exc.msg}（第 {exc.lineno} 行第 {exc.colno} 列）。"
        if not isinstance(value, dict):
            return None, '参数 script 必须是一个 JSON 对象，形如 {"steps": [...]}。'
        return value, ""
    return None, f"参数 script 必须是对象，收到 {type(raw).__name__}。"


def _collect_script_actions(raw: Any, out: set[str]) -> None:
    """递归收集脚本里出现的动作名（用于提前做权限与拼写检查）。"""
    if not isinstance(raw, dict):
        return
    action = raw.get("action")
    if isinstance(action, str) and action.strip():
        out.add(action.strip().lower())
    for key in ("steps", "then", "else"):
        items = raw.get(key)
        if isinstance(items, list):
            for item in items:
                _collect_script_actions(item, out)
    for key in SCRIPT_CONTROL_KEYS:
        spec = raw.get(key)
        if isinstance(spec, dict):
            _collect_script_actions(spec, out)


def _validate_script_shape(script: dict[str, Any]) -> str:
    """一眼可见的结构检查。返回空串表示没问题。"""
    steps = script.get("steps")
    if not isinstance(steps, list) or not steps:
        return ('script 里必须有非空的 "steps" 数组，形如 {"steps": [{"action": "chat", '
                '"params": {"message": "你好"}}]}。')
    for index, step in enumerate(steps):
        if not isinstance(step, dict):
            return f"steps[{index}] 必须是对象，收到 {type(step).__name__}。"
        if "action" in step:
            if not isinstance(step.get("action"), str) or not str(step["action"]).strip():
                return f'steps[{index}].action 必须是非空字符串。'
            continue
        if not any(key in step for key in SCRIPT_CONTROL_KEYS):
            return (f"steps[{index}] 既没有 action，也不是控制流"
                    f"（{'/'.join(SCRIPT_CONTROL_KEYS)}）。当前键是 {list(step)}。")
    return ""


def _count_items(state: Any, spec: str) -> int:
    """从状态快照里数某种物品有多少个（背包在 state 的顶层 slots 里）。

    ``spec`` 可以是 ``iron_ore`` 这种短名，也可以是 ``minecraft:iron_ore``；
    挖矿时矿石会变成掉落物（比如 iron_ore 挖出来是 raw_iron），所以调用方
    通常要传一个更宽的名词，这里做包含匹配就够用了。
    """
    if not isinstance(state, dict):
        return 0
    slots = ((state.get("inventory") or {}).get("slots")) or []
    needle = str(spec).strip().lower().replace("minecraft:", "")
    total = 0
    for slot in slots:
        item = slot.get("item") or {}
        item_id = str(item.get("id") or "").lower().replace("minecraft:", "")
        if needle and (needle in item_id or item_id in needle):
            total += int(item.get("count") or 0)
    return total


def _summarize_script(script: dict[str, Any]) -> str:
    """给日志/回执用的一句话描述。"""
    name = script.get("name")
    steps = script.get("steps")
    count = len(steps) if isinstance(steps, list) else 0
    label = f"《{name}》" if isinstance(name, str) and name.strip() else ""
    return f"{label}{count} 个顶层步骤"


def _baritone_command(action: str, *, target: str = "", x: Any = None, y: Any = None,
                      z: Any = None, count: int = 0) -> str:
    """把工具参数拼成 Baritone 的聊天指令。

    返回以 ``!`` 开头表示参数有问题（后面是给 LLM 的原因）。

    Baritone 的指令前缀默认是 ``#``，它拦截**聊天消息**（不是 ``/指令``），
    所以我们走 chat 动作，而不是 command。
    """
    t = str(target).strip()
    if action == "goto":
        if x is not None and z is not None:
            if y is not None:
                return f"#goto {int(x)} {int(y)} {int(z)}"
            return f"#goto {int(x)} {int(z)}"
        if not t:
            return "!action=goto 要么给 x/y/z 坐标，要么给 target（方块名，例如 iron_ore）。"
        return f"#goto {t}"
    if action == "mine":
        if not t:
            return "!action=mine 需要 target（要挖什么，例如 iron_ore、#minecraft:logs）。"
        # Baritone 的 mine 语法是 **数量在前**：`#mine <数量> <方块>`（数量可省略）。
        # 之前写成 `#mine dirt 10` 会报 "Error at argument #2: Expected w" ——
        # 它把 10 当成又一个方块名了。
        if int(count) > 0:
            return f"#mine {int(count)} {t}"
        return f"#mine {t}"
    if action == "explore":
        return "#explore"
    if action == "tunnel":
        height = int(count) if int(count) > 0 else 2
        return f"#tunnel {height}"
    if action == "come":
        return "#come"
    if action == "follow":
        if not t:
            return "!action=follow 需要 target（玩家名）。"
        return f"#follow player {t}"
    if action == "thisway":
        return f"#thisway {int(count) if int(count) > 0 else 100}"
    if action == "build":
        if not t:
            return "!action=build 需要 target（schematic 名字，要放在 Baritone 的 schematics 目录里）。"
        return f"#build {t}"
    if action == "stop":
        return "#stop"
    return f"!不认识的 Baritone 动作：{action}"


# ============================================================================
#  常用预设
# ============================================================================
#
# 预设就是「把一段已经验证过材料账的流程写死」。它解决的是一次性浪费：
# 模型每次现编「做木镐要几步」，就可能漏掉工作台、或者把木板数量算错，
# 而这类错误在游戏里表现为「craft 失败：材料不够」，要再想一轮。
#
# 展开发生在插件里（把步骤内联进一个普通脚本），所以预设里不会出现嵌套的 script 动作。
# 材料账都算过了：注释里的「→」就是推导过程。


def _act(action: str, **params: Any) -> dict[str, Any]:
    """构造一个动作步骤。"""
    return {"action": action, "params": params}


def _optional(step: dict[str, Any]) -> dict[str, Any]:
    """标记成「失败也继续」——比如放工作台，地上被占住了也不该让整段流程停下。"""
    step["optional"] = True
    return step


def _place_table_at_feet() -> dict[str, Any]:
    """把工作台放到自己脚下（"~" 是相对坐标，由模组在开始时按玩家位置解析）。"""
    return _optional(_act("place", x="~", y="~-1", z="~", item="crafting_table"))


def _as_int(params: dict[str, Any], key: str, default: int) -> int:
    value = params.get(key, default)
    try:
        return int(value)
    except (TypeError, ValueError):
        raise ValueError(f"参数 {key} 要是整数，收到 {value!r}") from None


#: 预设表：名字 → {summary, params, build}
#: params 里每项是 (说明, 默认值)，用于报错时告诉模型这个预设认哪些参数。
SCRIPT_PRESETS: dict[str, dict[str, Any]] = {
    "crafting_table": {
        "summary": "用一根原木做出工作台，并放到脚边",
        "params": {},
        # 1 原木 → 4 木板 → 工作台（正好 4 个木板）
        "build": lambda p: [
            _act("mine_blocks", block="#minecraft:logs", count=1),
            _act("craft", item="oak_planks", count=4),
            _act("craft", item="crafting_table", count=1),
            _place_table_at_feet(),
        ],
    },
    "wooden_pickaxe": {
        "summary": "从原木到木镐（含工作台）",
        "params": {},
        # 3 原木 → 12 木板；木棍 2 木板 → 4 根；工作台 4 木板；木镐 3 木板 + 2 木棍
        # 合计 2+4+3 = 9 ≤ 12 木板，2 ≤ 4 木棍
        "build": lambda p: [
            _act("mine_blocks", block="#minecraft:logs", count=3),
            _act("craft", item="oak_planks", count=12),
            _act("craft", item="stick", count=4),
            _act("craft", item="crafting_table", count=1),
            _place_table_at_feet(),
            _act("craft", item="wooden_pickaxe", count=1),
        ],
    },
    "stone_pickaxe": {
        "summary": "从原木到石镐（含工作台）",
        "params": {},
        # 2 原木 → 8 木板：木棍用 2，工作台用 4，剩 2；石镐要 3 圆石 + 2 木棍
        "build": lambda p: [
            _act("mine_blocks", block="#minecraft:logs", count=2),
            _act("craft", item="oak_planks", count=8),
            _act("craft", item="stick", count=4),
            _act("craft", item="crafting_table", count=1),
            _place_table_at_feet(),
            _act("mine_blocks", block="stone", count=3),
            _act("craft", item="stone_pickaxe", count=1),
        ],
    },
    "stone_sword": {
        "summary": "做一把石剑（含工作台）",
        "params": {},
        # 石剑 = 2 圆石 + 1 木棍；2 原木 → 8 木板：木棍 2 + 工作台 4
        "build": lambda p: [
            _act("mine_blocks", block="#minecraft:logs", count=2),
            _act("craft", item="oak_planks", count=8),
            _act("craft", item="stick", count=4),
            _act("craft", item="crafting_table", count=1),
            _place_table_at_feet(),
            _act("mine_blocks", block="stone", count=2),
            _act("craft", item="stone_sword", count=1),
        ],
    },
    "torch": {
        "summary": "挖煤做火把（4 个，不需要工作台）",
        "params": {},
        # 火把 = 1 煤 + 1 木棍，1x2 的配方，背包自带的 2x2 合成格就够
        "build": lambda p: [
            _act("mine_blocks", block="#minecraft:coal_ores", count=1),
            _act("mine_blocks", block="#minecraft:logs", count=1),
            _act("craft", item="oak_planks", count=4),
            _act("craft", item="stick", count=4),
            _act("craft", item="torch", count=4),
        ],
    },
    "furnace": {
        "summary": "挖石头做熔炉（含工作台）",
        "params": {},
        # 熔炉 = 8 圆石（3x3 要工作台）；2 原木 → 8 木板：木棍 2 + 工作台 4
        "build": lambda p: [
            _act("mine_blocks", block="#minecraft:logs", count=2),
            _act("craft", item="oak_planks", count=8),
            _act("craft", item="stick", count=4),
            _act("craft", item="crafting_table", count=1),
            _place_table_at_feet(),
            _act("mine_blocks", block="stone", count=8),
            _act("craft", item="furnace", count=1),
        ],
    },
    "chest": {
        "summary": "做箱子（含工作台）",
        "params": {},
        # 箱子 = 8 木板（3x3 要工作台）；4 原木 → 16 木板：木棍 2 + 工作台 4 + 箱子 8
        "build": lambda p: [
            _act("mine_blocks", block="#minecraft:logs", count=4),
            _act("craft", item="oak_planks", count=16),
            _act("craft", item="stick", count=4),
            _act("craft", item="crafting_table", count=1),
            _place_table_at_feet(),
            _act("craft", item="chest", count=1),
        ],
    },
    "mine_until": {
        "summary": "一直挖到背包里有够数的东西（条件循环）",
        "params": {
            "item": ("背包里数的那个物品名，默认 cobblestone", "cobblestone"),
            "block": ("要挖的方块（支持 #tag），默认 stone", "stone"),
            "count": ("想攒到几个，默认 16", 16),
        },
        # 用「非 has」做循环条件：还没有够就再挖一块。
        # maxIterations 比 count 多一点余量，因为挖到的可能不是预期的掉落物。
        "build": lambda p: [{
            "while": {
                "condition": {"not": {"has": {"item": p["item"], "count": p["count"]}}},
                "maxIterations": min(200, p["count"] + 8),
            },
            "steps": [_act("mine_blocks", block=p["block"], count=1)],
        }],
    },
    "defend_area": {
        "summary": "守在这里：有敌对生物就上去清掉，清干净就收工",
        "params": {
            "radius": ("警戒半径，默认 16", 16),
            "max_rounds": ("最多清几轮，默认 12", 12),
        },
        # 用脚本层的 while + attack 表达「守着」。
        # 它不如 mc_defend 聪明（不会吃东西、不会后撤、不会挑目标优先级），
        # 但好处是**看得见每一步**，而且不需要模组有 defend 动作。
        # 想要「会自保的抵御」就直接用 mc_defend。
        "build": lambda p: [{
            "while": {
                "condition": {"nearby": {"type": "hostile", "radius": p["radius"], "min": 1}},
                "maxIterations": min(200, p["max_rounds"]),
            },
            "steps": [
                {"action": "attack", "params": {
                    "target": "nearest_hostile", "count": 0, "durationMs": 20000,
                }, "optional": True},
            ],
        }],
    },
    "mine_then_craft": {        "summary": "挖够材料再合成（挖矿 + 条件循环 + 合成）",
        "params": {
            "item": ("背包里数的那个物品名，默认 cobblestone", "cobblestone"),
            "block": ("要挖的方块（支持 #tag），默认 stone", "stone"),
            "count": ("想攒到几个，默认 8", 8),
            "craft": ("攒够之后合成什么，默认 furnace", "furnace"),
            "craft_count": ("合成几个，默认 1", 1),
        },
        # 「挖够再合成」：先条件循环挖到够，再合。合成前先备好工作台，
        # 因为熔炉/箱子这类都是 3x3 配方。工作台那几步标了 optional，
        # 万一手上已经有工作台、或者地上放不下，也不会把整段流程弄失败。
        "build": lambda p: [
            _act("mine_blocks", block="#minecraft:logs", count=2),
            _act("craft", item="oak_planks", count=8),
            _act("craft", item="stick", count=4),
            _act("craft", item="crafting_table", count=1),
            _place_table_at_feet(),
            {
                "while": {
                    "condition": {"not": {"has": {"item": p["item"], "count": p["count"]}}},
                    "maxIterations": min(200, p["count"] + 8),
                },
                "steps": [_act("mine_blocks", block=p["block"], count=1)],
            },
            _act("craft", item=p["craft"], count=p["craft_count"]),
        ],
    },
}


def _build_preset(name: str, raw_params: Any) -> tuple[Optional[dict[str, Any]], str]:
    """把一个预设展开成脚本对象。返回 (脚本, 错误信息)。"""
    spec = SCRIPT_PRESETS.get(name)
    if spec is None:
        close = difflib.get_close_matches(name, list(SCRIPT_PRESETS), n=1, cutoff=0.5)
        hint = f"（是不是想用 {close[0]}？）" if close else ""
        return None, (f"没有叫「{name}」的预设{hint}。可用预设："
                      + "、".join(SCRIPT_PRESETS)
                      + "。各预设的说明见 mc_script 的工具描述。")

    if raw_params is None:
        raw_params = {}
    if not isinstance(raw_params, dict):
        return None, f"preset_params 必须是对象，收到 {type(raw_params).__name__}。"

    declared: dict[str, Any] = spec["params"]
    unknown = [k for k in raw_params if k not in declared]
    if unknown:
        detail = "；".join(f"{k}：{v[0]}" for k, v in declared.items()) or "（这个预设没有参数）"
        return None, f"预设「{name}」不认识参数 {unknown}。它支持的参数：{detail}"

    merged = {k: raw_params.get(k, v[1]) for k, v in declared.items()}
    for key in ("count", "craft_count"):
        if key in merged:
            try:
                merged[key] = _as_int(merged, key, merged[key])
            except ValueError as exc:
                return None, f"预设「{name}」：{exc}"
    for key in ("count", "craft_count"):
        if key in merged and not (1 <= merged[key] <= 200):
            return None, f"预设「{name}」：{key} 要在 1~200 之间，收到 {merged[key]}。"

    try:
        steps = spec["build"](merged)
    except ValueError as exc:
        return None, f"预设「{name}」的参数有问题：{exc}"

    return {"name": spec["summary"], "steps": steps}, ""


def _describe_presets() -> str:
    """给工具描述用的一行行预设清单。"""
    return "\n".join(f"  {name:<16} {spec['summary']}" for name, spec in SCRIPT_PRESETS.items())


# ============================================================================
#  插件主体
# ============================================================================


class MinecraftBridgePlugin(MaiBotPlugin):
    """把 Minecraft 接入麦麦：游戏聊天变成对话，麦麦的决策变成游戏操作。"""

    config_model = MinecraftBridgeConfig
    config_reload_subscriptions: ClassVar[Iterable[str]] = ()

    #: 消息网关组件名，必须与 @MessageGateway(name=...) 完全一致
    GATEWAY_NAME = "minecraft_gateway"
    #: 平台名，全部小写（Host 会做小写化处理）
    PLATFORM = "minecraft"

    def __init__(self) -> None:
        super().__init__()
        self.bridge: Optional[BridgeManager] = None
        #: stream_id 缓存：key 是「世界标识」，value 是麦麦侧的聊天流 ID
        self._streams: dict[str, str] = {}
        self._last_notify: dict[str, float] = {}
        #: 每条连接一把锁，保证同一玩家的连续发言按顺序进入麦麦，不会被并发打乱
        self._chat_locks: dict[str, asyncio.Lock] = {}
        #: on_load 失败时的真实原因（刻意不抛异常，见 on_load 的说明）
        self._load_error: str = ""

    # ------------------------------------------------------------ 生命周期

    async def on_load(self) -> None:
        """插件加载。

        **刻意不把异常抛出去。** 一旦 ``on_load`` 抛异常，``_activate_plugin`` 会返回
        FAILED，整个插件被卸载，而 MaiBot 插件页只会显示一句「插件初始化失败」——
        看不到任何真实原因（这个坑我们在配置版本问题上亲自踩过一次）。
        所以这里改成「加载成功但可能未就绪」：真实错误记进 ``self._load_error``，
        日志里有完整 traceback，QQ 发 ``/mc`` 或在游戏里问也能看到。
        """
        self._load_error = ""
        try:
            await self._startup()
        except Exception as exc:  # noqa: BLE001 - 见上面的说明，绝不能往外抛
            self._load_error = f"{type(exc).__name__}: {exc}"
            logger.error(
                "Minecraft 桥接初始化失败（插件仍会加载，但不会监听端口）：%s", exc, exc_info=True
            )
            try:
                await self.ctx.gateway.update_state(
                    gateway_name=self.GATEWAY_NAME,
                    ready=False,
                    platform=self.PLATFORM,
                )
            except Exception:  # noqa: BLE001
                pass

    async def _startup(self) -> None:
        cfg = self.config
        self.bridge = BridgeManager(
            host=cfg.server.host,
            port=int(cfg.server.port),
            path=cfg.server.path,
            auth_token=cfg.server.auth_token,
            max_actions_per_second=int(cfg.safety.max_actions_per_second),
            heartbeat_seconds=int(cfg.server.heartbeat_seconds),
            idle_timeout=float(cfg.server.idle_timeout_seconds),
            on_event=self._on_game_event,
            on_state=self._on_state,
            on_ready=self._on_client_ready,
            on_lost=self._on_client_lost,
        )
        try:
            port = await self.bridge.start()
            logger.info("Minecraft 桥接 WebSocket 服务端已启动: ws://%s:%s%s", cfg.server.host, port, cfg.server.path)
        except OSError as exc:
            logger.error("WebSocket 服务端启动失败（端口 %s 可能被占用）: %s", cfg.server.port, exc, exc_info=True)
            self.bridge = None
            self._load_error = (
                f"WebSocket 端口 {cfg.server.port} 无法监听（多半被占用）：{exc}。"
                "请在插件配置里换一个端口，并同步修改模组的 serverUrl。"
            )
            return

        # 上报网关就绪，否则 Host 不会把入站消息路由给我们，也不会选中我们发出站消息
        await self.ctx.gateway.update_state(
            gateway_name=self.GATEWAY_NAME,
            ready=True,
            platform=self.PLATFORM,
            account_id=self._account_id(),
            scope=self._scope(),
            metadata={"protocol": "mcai-bridge", "version": P.PROTOCOL_VERSION},
        )
        logger.info(
            "Minecraft AI 桥接已就绪：请在游戏里确认模组配置 serverUrl = ws://<本机IP>:%s%s",
            port, cfg.server.path,
        )

    async def on_unload(self) -> None:
        # Runner 不会替插件清理后台任务/连接，必须自己收干净，否则热重载后会留下
        # 一个还占着端口的幽灵服务端。
        if self.bridge is not None:
            try:
                await self.bridge.stop()
            except Exception as exc:  # noqa: BLE001
                logger.warning("关闭 WebSocket 服务端时出错: %s", exc)
            self.bridge = None
        self._chat_locks.clear()
        try:
            await self.ctx.gateway.update_state(
                gateway_name=self.GATEWAY_NAME,
                ready=False,
                platform=self.PLATFORM,
            )
        except Exception as exc:  # noqa: BLE001
            logger.debug("上报网关离线时出错（通常可以忽略）: %s", exc)
        logger.info("Minecraft AI 桥接已卸载")

    async def on_config_update(self, scope: str, config_data: dict[str, Any], version: str) -> None:
        if scope != CONFIG_RELOAD_SCOPE_SELF:
            return
        logger.info("Minecraft 桥接配置已更新（version=%s），重启插件后生效", version)

    # ------------------------------------------------------------ 标识工具

    def _account_id(self) -> str:
        """机器人账号 ID。出站路由要靠它精确匹配到本插件的驱动，两边必须一致。"""
        try:
            value = str(self.config.server.account_id or "").strip()
        except Exception:  # noqa: BLE001 - 配置尚未注入时退回到默认值
            value = ""
        return value or "mc-server-1"

    def _scope(self) -> str:
        return "main"

    def _group_id(self, session: BridgeSession) -> str:
        """把一个 Minecraft 连接映射到一个稳定的「群」标识。"""
        custom = self.config.chat.server_group_id.strip()
        if custom:
            return f"mc:{custom}"
        key = session.server_name or session.conn.path or "unknown"
        return f"mc:{key}"

    # -------------------------------------------------------------- 回调

    async def _on_client_ready(self, session: BridgeSession) -> None:
        """模组握手成功。"""
        logger.info("Minecraft 客户端接入：%s（会话 %s）", session.label, session.session_id)
        group_id = self._group_id(session)
        await self._ensure_stream(session)
        if self.config.safety.greet_on_connect and self.config.events.report_events:
            await self._append_context(
                session,
                f"玩家 {session.player_name or '未知玩家'} 已经进入游戏"
                f"（世界：{session.server_name or '未知'}，Minecraft {session.mc_version or '?'}）。"
                f"现在可以通过 mc_* 工具操作这个角色了。",
                source_kind="mc:presence",
            )
        # 让麦麦一开始就知道自己在哪、手里有什么
        try:
            state = await session.request_action(
                P.A_GET_STATE, {}, timeout=10.0,
                max_per_second=self.config.safety.max_actions_per_second,
            )
            summary = self._summarize_state(state.get("result") or {})
            await self._append_context(session, f"当前游戏状态：{summary}", source_kind="mc:state")
        except ActionError as exc:
            logger.debug("获取初始状态失败（不影响使用）: %s", exc)
        logger.debug("世界标识 %s -> 聊天流 %s", group_id, self._streams.get(group_id))

    async def _on_client_lost(self, session: BridgeSession, code: int, reason: str) -> None:
        logger.info("Minecraft 客户端断开：%s（code=%s %s）", session.label, code, reason)
        self._chat_locks.pop(session.session_id, None)
        if self.config.chat.enabled and self.config.events.report_events:
            try:
                stream_id = self._streams.get(self._group_id(session))
                if stream_id:
                    await self.ctx.maisaka.context.append(
                        stream_id=stream_id,
                        segments=[{"type": "text", "data":
                                   f"玩家 {session.player_name or '?'} 已经离开游戏（连接断开）。"}],
                        visible_text=f"{session.player_name or '?'} 离开了游戏",
                        source_kind="mc:presence",
                    )
            except Exception as exc:  # noqa: BLE001
                logger.debug("追加离线上下文失败: %s", exc)

    async def _on_state(self, session: BridgeSession, state: dict[str, Any]) -> None:
        """收到状态快照。默认只缓存，不做任何 LLM 调用（避免刷爆 token）。"""
        return None

    async def _on_game_event(self, session: BridgeSession, event: dict[str, Any]) -> None:
        """收到游戏事件。"""
        kind = str(event.get("kind") or "")

        if kind == "chat":
            await self._handle_chat_event(session, event)
            return

        if not self.config.events.report_events:
            return

        description = self._describe_event(event)
        if not description:
            return

        await self._append_context(session, description, source_kind=f"mc:event:{kind}")

        # 需要主动打扰的事件
        notify_on = {str(x).strip() for x in self.config.events.notify_on if str(x).strip()}
        if kind not in notify_on:
            return
        cooldown = max(0, int(self.config.events.notify_cooldown_seconds))
        now = time.time()
        if now - self._last_notify.get(kind, 0.0) < cooldown:
            return
        self._last_notify[kind] = now
        await self._trigger_proactive(session, description, reason=f"mc_{kind}")

    # ------------------------------------------------------ 游戏聊天 → 麦麦

    async def _handle_chat_event(self, session: BridgeSession, event: dict[str, Any]) -> None:
        # 服务端把每条消息派发到独立任务里处理（否则会死锁），因此这里用锁把
        # 「同一个玩家的连续发言」串起来，保证进入麦麦的顺序与玩家说话顺序一致。
        lock = self._chat_locks.setdefault(session.session_id, asyncio.Lock())
        async with lock:
            await self._handle_chat_event_locked(session, event)

    async def _handle_chat_event_locked(self, session: BridgeSession, event: dict[str, Any]) -> None:
        if not self.config.chat.enabled:
            return

        text = str(event.get("text") or "").strip()
        if not text:
            return

        from_player = bool(event.get("player"))
        sender = str(event.get("sender") or event.get("playerName") or "").strip()

        # 过滤自己的发言，避免自问自答
        if self.config.chat.ignore_own_messages:
            own = (session.player_name or "").strip().lower()
            if from_player and own and sender.strip().lower() == own:
                return

        ignore_players = {str(x).strip().lower() for x in self.config.chat.ignore_players if str(x).strip()}
        if from_player and sender.lower() in ignore_players:
            return

        # 只有玩家发言才注入对话；系统消息（死亡提示、进度播报）走上下文，避免打断正常聊天
        if not from_player:
            if self.config.events.report_events:
                await self._append_context(session, f"游戏系统消息：{text}",
                                           source_kind="mc:event:system_chat")
            return

        prefix = self.config.chat.command_prefix.strip()
        intent = ""
        if prefix and text.startswith(prefix):
            intent = text[len(prefix):].strip()
            text = intent or text

        group_id = self._group_id(session)
        user_id = f"mc:{sender or 'player'}"
        message_id = f"mc-{session.session_id}-{int(time.time() * 1000)}"

        try:
            accepted = await self.ctx.gateway.route_message(
                gateway_name=self.GATEWAY_NAME,
                message={
                    "message_id": message_id,
                    "timestamp": str(time.time()),
                    "platform": self.PLATFORM,
                    "message_info": self._message_info(session, sender, user_id, group_id),
                    "raw_message": [{"type": "text", "data": text}],
                    "processed_plain_text": text,
                    "is_mentioned": bool(intent),
                },
                route_metadata={
                    "self_id": self._account_id(),
                    "connection_id": self._scope(),
                    "world": group_id,
                },
                external_message_id=message_id,
                dedupe_key=message_id,
            )
        except Exception as exc:  # noqa: BLE001
            logger.error("注入游戏聊天失败: %s", exc, exc_info=True)
            return

        if not accepted:
            logger.warning("Host 没有接受这条游戏聊天（玩家=%s）：%s", sender, text[:60])
            return

        logger.debug("游戏聊天已注入麦麦：[%s] %s", sender, text[:80])

        # 首次注入后才能确认聊天流真正建立，顺手把 stream_id 校正一遍
        await self._refresh_stream_id(session, group_id)

        if self.config.chat.force_reply:
            await self._trigger_proactive(
                session,
                f"游戏里的玩家「{sender}」刚刚说：{text}。请像和队友说话一样回应他，"
                f"必要时调用 mc_* 工具（例如 mc_chat 说话、mc_move_to 走过去、mc_mine 挖矿）。",
                reason="mc_chat",
                stream_id_override=self._streams.get(group_id),
            )

    def _message_info(self, session: BridgeSession, sender: str, user_id: str, group_id: str) -> dict[str, Any]:
        info: dict[str, Any] = {
            "user_info": {
                "user_id": user_id,
                "user_nickname": sender or "玩家",
                "user_cardname": None,
            },
            "additional_config": {},
        }
        if self.config.chat.chat_mode.strip().lower() != "private":
            info["group_info"] = {
                "group_id": group_id,
                "group_name": session.server_name or "Minecraft",
            }
        return info

    # ------------------------------------------------------ 上下文 / 主动

    async def _append_context(self, session: BridgeSession, text: str, source_kind: str = "mc:event") -> None:
        """把一条信息追加到麦麦的上下文里（不会唤醒思考）。"""
        stream_id = await self._ensure_stream(session)
        if not stream_id:
            return
        try:
            await self.ctx.maisaka.context.append(
                stream_id=stream_id,
                segments=[{"type": "text", "data": text}],
                visible_text=text,
                source_kind=source_kind,
            )
        except Exception as exc:  # noqa: BLE001
            logger.debug("追加游戏上下文失败: %s", exc)

    async def _trigger_proactive(self, session: BridgeSession, intent: str, reason: str,
                                 stream_id_override: str = "") -> None:
        """唤醒麦麦主动思考一轮。需要聊天流已经存在。"""
        stream_id = stream_id_override or await self._ensure_stream(session)
        if not stream_id:
            return
        try:
            await self.ctx.maisaka.proactive.trigger(
                stream_id=stream_id,
                intent=intent,
                reason=reason,
                priority="normal",
                metadata={"plugin": "mcai.minecraft-bridge", "player": session.player_name},
            )
        except Exception as exc:  # noqa: BLE001
            logger.debug("唤醒麦麦主动思考失败（stream=%s）: %s", stream_id, exc)

    async def _ensure_stream(self, session: BridgeSession) -> str:
        """取得（必要时创建）这个 Minecraft 连接对应的聊天流 ID。"""
        group_id = self._group_id(session)
        cached = self._streams.get(group_id)
        if cached:
            return cached
        try:
            if self.config.chat.chat_mode.strip().lower() == "private":
                result = await self.ctx.chat.open_session(
                    platform=self.PLATFORM,
                    chat_type="private",
                    user_id=f"mc:{session.player_name or 'player'}",
                    account_id=self._account_id(),
                    scope=self._scope(),
                )
            else:
                result = await self.ctx.chat.open_session(
                    platform=self.PLATFORM,
                    chat_type="group",
                    group_id=group_id,
                    account_id=self._account_id(),
                    scope=self._scope(),
                )
            stream_id = self._extract_stream_id(result)
            if stream_id:
                self._streams[group_id] = stream_id
                logger.debug("已创建/获取聊天流 %s -> %s", group_id, stream_id)
                return stream_id
        except Exception as exc:  # noqa: BLE001
            logger.warning("创建聊天流失败（%s）: %s", group_id, exc)
        return ""

    async def _refresh_stream_id(self, session: BridgeSession, group_id: str) -> None:
        """首条消息注入后，用 Host 侧的查询接口校正 stream_id。"""
        try:
            if self.config.chat.chat_mode.strip().lower() == "private":
                result = await self.ctx.chat.get_stream_by_user_id(
                    user_id=f"mc:{session.player_name or 'player'}", platform=self.PLATFORM)
            else:
                result = await self.ctx.chat.get_stream_by_group_id(group_id=group_id, platform=self.PLATFORM)
            stream_id = self._extract_stream_id(result)
            if stream_id and self._streams.get(group_id) != stream_id:
                self._streams[group_id] = stream_id
                logger.debug("校正聊天流 %s -> %s", group_id, stream_id)
        except Exception as exc:  # noqa: BLE001
            logger.debug("校正聊天流失败: %s", exc)

    @staticmethod
    def _extract_stream_id(result: Any) -> str:
        if isinstance(result, str):
            return result
        if isinstance(result, dict):
            for key in ("stream_id", "session_id", "id"):
                value = result.get(key)
                if isinstance(value, str) and value:
                    return value
            stream = result.get("stream")
            if isinstance(stream, dict):
                for key in ("stream_id", "session_id", "id"):
                    value = stream.get(key)
                    if isinstance(value, str) and value:
                        return value
        return ""

    # ---------------------------------------------------------- 出站：说话

    @MessageGateway(
        "duplex",
        name="minecraft_gateway",
        description="Minecraft 客户端网关：接收游戏内聊天，并把麦麦的回复发进游戏",
        platform="minecraft",
        protocol="mcai-bridge",
        # account_id / scope 与 update_state()、route_metadata 里用的值保持一致，
        # 这样出站路由的「驱动绑定」和「消息路由键」才能精确对上（否则只能退化到 platform 级匹配）。
        account_id="mc-server-1",
        scope="main",
    )
    async def minecraft_gateway(
        self,
        message: dict[str, Any],
        route: Optional[dict[str, Any]] = None,
        metadata: Optional[dict[str, Any]] = None,
        **kwargs: Any,
    ) -> dict[str, Any]:
        """出站方向：麦麦要说话 → 转成 action 报文交给模组。"""
        if self.bridge is None:
            return {"success": False, "error": "桥接未启动"}

        text = self._extract_text(message)
        if not text:
            return {"success": True, "external_message_id": "", "note": "空消息，已忽略"}

        # 找出应该由哪个游戏客户端来发言
        target_key = self._target_world_key(message)
        session = self._select_session(target_key)
        if session is None:
            logger.warning("麦麦想说话，但当前没有 Minecraft 客户端在线：%s", text[:60])
            return {"success": False, "error": "没有已连接的 Minecraft 客户端"}

        try:
            result = await session.request_action(
                P.A_CHAT,
                {"message": text},
                timeout=15.0,
                max_per_second=self.config.safety.max_actions_per_second,
            )
        except ActionError as exc:
            logger.warning("发言到游戏失败: %s", exc)
            return {"success": False, "error": str(exc)}

        payload = result.get("result") or {}
        return {
            "success": True,
            "external_message_id": f"mc-{session.session_id}-{int(time.time() * 1000)}",
            "delivered_to": session.player_name,
            "message": payload.get("message", text),
        }

    @staticmethod
    def _extract_text(message: dict[str, Any]) -> str:
        """从 Host 传来的 MessageDict 里取出纯文本。"""
        if not isinstance(message, dict):
            return ""
        plain = message.get("processed_plain_text")
        if isinstance(plain, str) and plain.strip():
            return plain.strip()
        segments = message.get("raw_message")
        if isinstance(segments, list):
            parts: list[str] = []
            for segment in segments:
                if not isinstance(segment, dict):
                    continue
                if str(segment.get("type", "")).lower() != "text":
                    continue
                data = segment.get("data")
                if isinstance(data, str) and data:
                    parts.append(data)
            if parts:
                return "".join(parts).strip()
        return ""

    def _target_world_key(self, message: dict[str, Any]) -> str:
        info = message.get("message_info") if isinstance(message, dict) else None
        if isinstance(info, dict):
            group = info.get("group_info")
            if isinstance(group, dict):
                group_id = group.get("group_id")
                if isinstance(group_id, str) and group_id:
                    return group_id
        return ""

    def _select_session(self, world_key: str = "") -> Optional[BridgeSession]:
        if self.bridge is None:
            return None
        if world_key and world_key.startswith("mc:"):
            wanted = world_key[3:]
            for session in self.bridge.sessions:
                if not session.alive:
                    continue
                if (session.server_name or "") == wanted:
                    return session
        try:
            return self.bridge.require()
        except ActionError:
            return None

    # ------------------------------------------------------------ 事件描述

    def _describe_event(self, event: dict[str, Any]) -> str:
        kind = str(event.get("kind") or "")
        pos = P.coord_str(event.get("pos"))
        player = str(event.get("playerName") or "玩家")

        if kind == "damage":
            source = str(event.get("sourceName") or event.get("source") or "未知来源")
            return (f"{player} 受到 {event.get('amount')} 点伤害（来自 {source}），"
                    f"血量从 {event.get('healthBefore')} 降到 {event.get('healthAfter')}，位置 {pos}")
        if kind == "health_critical":
            return f"{player} 血量危险！只剩 {event.get('health')}/{event.get('maxHealth')}，位置 {pos}"
        if kind == "death":
            killer = event.get("killerName") or event.get("killer") or "未知"
            return f"{player} 死了（凶手：{killer}），死亡位置 {pos}"
        if kind == "respawn":
            return f"{player} 已经重生，位置 {pos}"
        if kind == "kill":
            return f"{player} 击杀了 {event.get('name') or event.get('entity')}，位置 {pos}"
        if kind == "level_up":
            return f"{player} 升到了 {event.get('level')} 级"
        if kind == "hungry":
            return f"{player} 快饿死了（饥饿值 {event.get('food')}），位置 {pos}"
        if kind == "dimension_change":
            return f"{player} 从 {event.get('from')} 传送到了 {event.get('to')}，位置 {pos}"
        if kind == "item_gain":
            return f"{player} 获得了 {event.get('count')} 个 {event.get('item')}"
        if kind == "item_lost":
            return f"{player} 失去了 {event.get('count')} 个 {event.get('item')}"
        if kind == "block_break":
            return f"{player} 挖掉了 {event.get('block')} @ {event.get('pos')}"
        if kind == "block_place":
            return f"{player} 放置了 {event.get('block')} @ {event.get('pos')}"
        if kind == "task_progress":
            return f"正在执行 {event.get('action')}：{event.get('detail') or ''}".strip()
        if kind == "log":
            return f"游戏日志：{event.get('message')}"
        return ""

    def _summarize_state(self, state: dict[str, Any]) -> str:
        player = state.get("player") or {}
        world = state.get("world") or {}
        inv = state.get("inventory") or {}
        held = player.get("heldItem") or {}
        held_text = f"{held.get('name')}×{held.get('count')}" if held else "空手"
        return (
            f"玩家 {player.get('name')} 在 {player.get('dimension')} 的 {P.coord_str(player.get('pos'))}"
            f"（方块坐标 {P.block_coord_str(player.get('blockPos'))}），"
            f"血量 {player.get('health')}/{player.get('maxHealth')}，饥饿 {player.get('food')}，"
            f"手持 {held_text}，游戏模式 {player.get('gameMode')}；"
            f"世界时间 {world.get('timeOfDay')}（{'白天' if world.get('isDay') else '夜晚'}），"
            f"生物群系 {world.get('biome')}，背包已用 {inv.get('usedSlots')} 格"
        )

    # ============================================================== 工具层

    # 单次工具调用最多让插件等多久（秒）。
    #
    # 为什么必须有这个上限：MaiBot 框架给 `plugin.invoke_tool` 的 RPC 超时是 **60 秒**，
    # 插件等得比它久没有任何意义 —— 到点框架先抛 E_TIMEOUT，连「动作其实还在跑」都传不回去
    # （真机报错：运行时工具 mcai.minecraft-bridge.mc_follow 执行失败: [E_TIMEOUT] 请求
    #   plugin.invoke_tool 超时 (60000ms)，而 mc_follow 默认 duration_ms=60000 时会等 80 秒）。
    # 留 10 秒余量：50 秒还没结束就返回「还在跑」，让 AI 用 mc_task_status 去看进度。
    _MAX_TOOL_WAIT_SECONDS = 50.0

    async def _call(self, action: str, params: dict[str, Any], session_key: str = "",
                    player: str = "", timeout: Optional[float] = None,
                    skip_allowlist: bool = False, hard_cap: bool = True) -> dict[str, Any]:
        """统一的动作调用入口：找到客户端 → 下发 → 返回给 LLM 可读的结果。"""
        if self.bridge is None:
            return {"success": False, "content": "Minecraft 桥接插件没有启动（WebSocket 服务端启动失败，请检查端口占用）。"}

        if not self._action_allowed(action, skip_allowlist=skip_allowlist):
            return {"success": False, "content": f"动作 {action} 已被插件配置禁用（见「安全与限流」里的动作白名单/黑名单）。"}

        try:
            session = self.bridge.require(session_id=session_key, player=player)
        except ActionError as exc:
            return {"success": False, "content": str(exc)}

        if timeout is None:
            timeout = float(self.config.safety.max_action_timeout_seconds)
        # 压到框架的 RPC 超时以内（见 _MAX_TOOL_WAIT_SECONDS 的说明）。
        # 脚本例外：它的长短由脚本自己的 maxDurationMs 决定，插件侧不该替它截断
        # （那种情况下请把框架的工具超时也一起调大，否则框架仍会先报 E_TIMEOUT）。
        if hard_cap:
            timeout = min(float(timeout), self._MAX_TOOL_WAIT_SECONDS)

        try:
            result = await session.request_action(
                action, params, timeout=timeout,
                max_per_second=self.config.safety.max_actions_per_second,
            )
        except ActionError as exc:
            text = str(exc)
            # 「等超时」不等于失败：动作在游戏里还在继续跑，只是这一轮工具调用等不到它结束。
            # 报成错误会让 AI 以为没做成、然后重来一遍（于是排两个跟随）。
            if "超时" in text or "timeout" in text.lower():
                return {
                    "success": True,
                    "stillRunning": True,
                    "content": f"{action} 还在跑：等了 {int(timeout)} 秒还没结束，"
                               f"所以我先把它交回给你。**它没有失败**，游戏里还在继续做；"
                               f"用 mc_task_status 看进度，想让它停就 mc_stop。"
                               f"（这类长动作本来就该这么用：下发 → 过一会儿查进度，别一直等。）",
                    "action": action,
                    "params": params,
                }
            return {"success": False, "content": f"动作 {action} 失败：{exc}", "action": action, "params": params}

        return {
            "success": True,
            "content": f"{action} 执行成功：{json.dumps(result.get('result') or {}, ensure_ascii=False)}",
            "action": action,
            "params": params,
            "result": result.get("result") or {},
            "elapsed_ms": result.get("elapsedMs"),
            "state": result.get("state") or {},
        }

    def _action_allowed(self, action: str, *, skip_allowlist: bool = False) -> bool:
        """动作是否被插件配置允许。

        ``skip_allowlist`` 给任务组用：白名单该管的是「脚本实际会做的那些动作」，
        而不是 ``script`` 这个壳子 —— 否则白名单里只写 chat 的人会发现连
        「只包含 chat 的脚本」都发不出去。黑名单对 ``script`` 仍然生效。
        """
        allow = {str(x).strip() for x in self.config.safety.allow_actions if str(x).strip()}
        deny = {str(x).strip() for x in self.config.safety.deny_actions if str(x).strip()}
        if action in deny:
            return False
        if not skip_allowlist and allow and action not in allow:
            return False
        return True

    # ------------------------------------------------------------- 观察类

    @Tool(
        "mc_state",
        brief_description="查看 Minecraft 角色的完整当前状态（位置、血量、背包、附近实体、正在做什么）",
        detailed_description=(
            "获取游戏内角色的最新状态快照。这是行动前最应该先调用的工具——先看清楚自己在哪、有什么、周围有什么。\n"
            "返回内容包括：坐标与朝向、血量/饥饿/经验、手持与副手物品、背包列表、当前正在执行的任务、"
            "附近实体（含是否敌对）与附近玩家、世界时间与天气、准星正对着的方块或生物。\n"
            "参数说明：\n"
            "- player：string，可选。指定要查询哪个游戏客户端（按游戏内玩家名）。只有一个客户端时可以省略。"
        ),
        parameters=[
            ToolParameterInfo(name="player", param_type=ToolParamType.STRING,
                              description="要查询的游戏内玩家名（多客户端时使用）", required=False, default=""),
        ],
    )
    async def mc_state(self, player: str = "", **kwargs: Any):
        response = await self._call(P.A_GET_STATE, {}, player=player, timeout=15.0)
        if not response.get("success"):
            return response
        return {"success": True, "content": self._render_state(response.get("result") or {}),
                "state": response.get("result") or {}}

    @Tool(
        "mc_query",
        brief_description="回看游戏里最近发生过什么（聊天、受伤、死亡、击杀、升级等事件）",
        detailed_description=(
            "查询最近一段时间内游戏上报的事件记录，用于回答「刚才发生了什么」「谁打我了」这类问题。\n"
            "参数说明：\n"
            "- limit：integer，可选。返回多少条，默认 20，上限 60。\n"
            "- kind：string，可选。只筛选某一类事件，例如 chat、damage、death、kill、level_up、dimension_change。\n"
            "- player：string，可选。指定哪个游戏客户端。"
        ),
        parameters=[
            ToolParameterInfo(name="limit", param_type=ToolParamType.INTEGER,
                              description="返回条数，默认 20", required=False, default=20),
            ToolParameterInfo(name="kind", param_type=ToolParamType.STRING,
                              description="事件类型筛选，留空表示全部", required=False, default=""),
            ToolParameterInfo(name="player", param_type=ToolParamType.STRING,
                              description="游戏内玩家名", required=False, default=""),
        ],
    )
    async def mc_query(self, limit: int = 20, kind: str = "", player: str = "", **kwargs: Any):
        if self.bridge is None:
            return {"success": False, "content": "桥接未启动"}
        session = self._select_session_for(player)
        if session is None:
            return {"success": False, "content": "当前没有 Minecraft 客户端连接。"}
        events = session.recent_events(limit=max(1, min(int(limit), 60)), kind=kind.strip() or None)
        if not events:
            return {"success": True, "content": "最近没有记录到事件。", "events": []}
        lines = []
        for event in events:
            stamp = time.strftime("%H:%M:%S", time.localtime(float(event.get("at") or time.time())))
            description = self._describe_event(event) or json.dumps(event, ensure_ascii=False)
            lines.append(f"[{stamp}] {description}")
        return {"success": True, "content": "\n".join(lines), "events": events}

    @Tool(
        "mc_scan_blocks",
        brief_description="在角色附近搜索指定方块，返回坐标和距离（找矿、找树、找箱子都用它）",
        detailed_description=(
            "以角色为中心扫描已加载的区块，找出所有匹配的方块并按距离排序。\n"
            "参数说明：\n"
            "- block：string，必填。方块名（如 iron_ore、diamond_ore、oak_log、chest），"
            "或者方块标签（如 #minecraft:logs、#minecraft:flowers）。\n"
            "- radius：integer，可选。水平搜索半径，默认 24，最大 64。\n"
            "- limit：integer，可选。最多返回多少个，默认 20。\n"
            "- player：string，可选。指定游戏客户端。\n"
            "注意：只能扫到玩家附近已经加载的区块，远处的方块扫不到。"
        ),
        parameters=[
            ToolParameterInfo(name="block", param_type=ToolParamType.STRING,
                              description="方块名或 #标签，例如 iron_ore、#minecraft:logs", required=True),
            ToolParameterInfo(name="radius", param_type=ToolParamType.INTEGER,
                              description="搜索半径，默认 24", required=False, default=24),
            ToolParameterInfo(name="limit", param_type=ToolParamType.INTEGER,
                              description="最多返回数量，默认 20", required=False, default=20),
            ToolParameterInfo(name="player", param_type=ToolParamType.STRING,
                              description="游戏内玩家名", required=False, default=""),
        ],
    )
    async def mc_scan_blocks(self, block: str, radius: int = 24, limit: int = 20,
                             player: str = "", **kwargs: Any):
        response = await self._call(P.A_SCAN_BLOCKS,
                                    {"block": block, "radius": radius, "limit": limit},
                                    player=player, timeout=30.0)
        if not response.get("success"):
            return response
        data = response.get("result") or {}
        blocks = data.get("blocks") or []
        if not blocks:
            return {"success": True, "content": f"半径 {radius} 格内没有找到 {block}。", "blocks": []}
        lines = [f"{item.get('name')}（{item.get('block')}）@ {P.block_coord_str(item.get('pos'))} "
                 f"距离 {item.get('distance')} 格" for item in blocks]
        return {"success": True,
                "content": f"找到 {data.get('totalFound')} 个，最近 {len(blocks)} 个：\n" + "\n".join(lines),
                "blocks": blocks}

    @Tool(
        "mc_scan_entities",
        brief_description="查看角色附近的生物与玩家（谁在附近、多远、是不是敌对）",
        detailed_description=(
            "扫描附近的活体实体，按距离排序。\n"
            "参数说明：\n"
            "- radius：integer，可选。搜索半径，默认 32，最大 64。\n"
            "- filter：string，可选。all（默认）/ hostile（只找敌对生物）/ animal（只找动物）/ player（只找玩家）。\n"
            "- type：string，可选。按实体类型名模糊过滤，例如 zombie、cow、skeleton。\n"
            "- limit：integer，可选。最多返回多少个，默认 20。"
        ),
        parameters=[
            ToolParameterInfo(name="radius", param_type=ToolParamType.INTEGER,
                              description="搜索半径，默认 32", required=False, default=32),
            ToolParameterInfo(name="filter", param_type=ToolParamType.STRING,
                              description="all / hostile / animal / player", required=False, default="all"),
            ToolParameterInfo(name="type", param_type=ToolParamType.STRING,
                              description="实体类型名模糊过滤", required=False, default=""),
            ToolParameterInfo(name="limit", param_type=ToolParamType.INTEGER,
                              description="最多返回数量，默认 20", required=False, default=20),
        ],
    )
    async def mc_scan_entities(self, radius: int = 32, filter: str = "all", type: str = "",
                               limit: int = 20, **kwargs: Any):
        response = await self._call(P.A_SCAN_ENTITIES,
                                    {"radius": radius, "filter": filter, "type": type, "limit": limit},
                                    timeout=30.0)
        if not response.get("success"):
            return response
        data = response.get("result") or {}
        entities = data.get("entities") or []
        if not entities:
            return {"success": True, "content": f"半径 {radius} 格内没有符合条件的实体。", "entities": []}
        lines = []
        for item in entities:
            tag = "【敌对】" if str(item.get("category")) == "HOSTILE" else (
                "【玩家】" if str(item.get("category")) == "PLAYER" else "")
            lines.append(f"{tag}{item.get('name')}（{item.get('type')}）距离 {item.get('distance')} 格，"
                         f"血量 {item.get('health')}，坐标 {P.coord_str(item.get('pos'))}")
        return {"success": True, "content": f"共 {data.get('totalFound')} 个：\n" + "\n".join(lines),
                "entities": entities}

    @Tool(
        "mc_inventory",
        brief_description="查看角色的物品栏与装备",
        detailed_description="列出快捷栏与背包里的物品、装备、以及当前选中的格子。",
        parameters=[],
    )
    async def mc_inventory(self, **kwargs: Any):
        response = await self._call(P.A_GET_STATE, {}, timeout=15.0)
        if not response.get("success"):
            return response
        state = response.get("result") or {}
        inv = state.get("inventory") or {}
        held = (state.get("player") or {}).get("heldItem") or {}
        slots = inv.get("slots") or []
        lines = []
        for entry in slots:
            item = entry.get("item") or {}
            mark = "←当前" if entry.get("selected") else ("[快捷]" if entry.get("hotbar") else "")
            lines.append(f"槽位 {entry.get('slot')}: {item.get('name')}×{item.get('count')} "
                         f"({item.get('id')}) 耐久 {item.get('durabilityLeft', '-')} {mark}")
        content = f"手持：{held.get('name') or '空手'}；已用 {inv.get('usedSlots')} 格，"
        content += f"剩余 {inv.get('freeSlots')} 格。\n" + ("\n".join(lines) if lines else "背包是空的。")
        return {"success": True, "content": content, "inventory": inv}

    # ------------------------------------------------------------ 行动类

    @Tool(
        "mc_chat",
        brief_description="让角色在游戏聊天栏里说一句话",
        detailed_description=(
            "把一句话发送到游戏内聊天栏（所有玩家都能看到）。\n"
            "参数说明：\n"
            "- message：string，必填。要说的内容，建议简短自然，像真人玩家聊天。\n"
            "- player：string，可选。指定由哪个游戏客户端发言。"
        ),
        parameters=[
            ToolParameterInfo(name="message", param_type=ToolParamType.STRING,
                              description="要发送的聊天内容", required=True),
            ToolParameterInfo(name="player", param_type=ToolParamType.STRING,
                              description="游戏内玩家名", required=False, default=""),
        ],
    )
    async def mc_chat(self, message: str, player: str = "", **kwargs: Any):
        return await self._call(P.A_CHAT, {"message": message}, player=player, timeout=15.0)

    @Tool(
        "mc_command",
        brief_description="让角色执行一条 Minecraft 指令（以 / 开头的那种）",
        detailed_description=(
            "在游戏里执行一条指令，例如 time set day、weather clear、give @s diamond 1。\n"
            "参数说明：\n"
            "- command：string，必填。指令内容，带不带前导斜杠都行。\n"
            "注意：以角色自身权限为准，没有 OP 权限的指令会失败（游戏聊天栏会提示）。"
        ),
        parameters=[
            ToolParameterInfo(name="command", param_type=ToolParamType.STRING,
                              description="要执行的指令，例如 time set day", required=True),
        ],
    )
    async def mc_command(self, command: str, **kwargs: Any):
        return await self._call(P.A_COMMAND, {"command": command}, timeout=15.0)

    @Tool(
        "mc_move_to",
        brief_description="让角色自动寻路走到指定坐标（装了 Baritone 就优先交给它）",
        detailed_description=(
            "让角色寻路走到指定位置。会自动绕开障碍、上台阶、游泳，被完全封死时会失败并说明原因。\n"
            "**装了 Baritone 时默认交给它**（它的寻路更稳：搭桥、绕岩浆、挖穿都会自己处理），"
            "结果里会带 via=baritone 和实际发出的指令（例如 #goto 48 -60 3）。\n"
            "参数说明：\n"
            "- x、y、z：integer，必填。目标方块坐标。可以先从 mc_state 或 mc_scan_blocks 里拿到坐标。\n"
            "- range：integer，可选。到达判定半径，默认 1（走到目标旁边 1 格内就算到）。\n"
            "- via：string，可选。默认交给 Baritone；填 native 就强制用模组自带的 A* 寻路"
            "（Baritone 不在、或者想走一条它不认的路时用）。\n"
            "- player：string，可选。指定游戏客户端。\n"
            "提示：这是长动作，会一直执行到走完为止；期间可以用 mc_stop 打断，用 mc_task_status 看进度。"
        ),
        parameters=[
            ToolParameterInfo(name="x", param_type=ToolParamType.INTEGER, description="目标 X 坐标", required=True),
            ToolParameterInfo(name="y", param_type=ToolParamType.INTEGER, description="目标 Y 坐标", required=True),
            ToolParameterInfo(name="z", param_type=ToolParamType.INTEGER, description="目标 Z 坐标", required=True),
            ToolParameterInfo(name="range", param_type=ToolParamType.INTEGER,
                              description="到达判定半径，默认 1", required=False, default=1),
            ToolParameterInfo(name="via", param_type=ToolParamType.STRING,
                              description="留空=优先用 Baritone；填 native=强制用自带寻路",
                              required=False, default=""),
            ToolParameterInfo(name="player", param_type=ToolParamType.STRING,
                              description="游戏内玩家名", required=False, default=""),
        ],
    )
    async def mc_move_to(self, x: int, y: int, z: int, range: int = 1, via: str = "",
                         player: str = "", **kwargs: Any):
        params: dict[str, Any] = {"x": x, "y": y, "z": z, "range": range}
        if str(via).strip():
            # 模组按 via 决定「交给 Baritone」还是「用自带 A*」
            params["via"] = str(via).strip()
        return await self._call(P.A_MOVE_TO, params,
                                player=player,
                                timeout=float(self.config.safety.max_action_timeout_seconds))

    @Tool(
        "mc_follow",
        brief_description="让角色跟着某个玩家/生物走",
        detailed_description=(
            "持续跟随一个目标。目标走远了会重新规划路径，跟丢了会报告。\n"
            "参数说明：\n"
            "- target：string，必填。玩家名、实体名、实体 uuid，或 nearest_player（最近的其他玩家）。\n"
            "- range：integer，可选。跟到多近就停下，默认 3 格。\n"
            "- duration_ms：integer，可选。跟随多久（毫秒），默认 60000；填 0 表示一直跟到被 mc_stop 打断。"
        ),
        parameters=[
            ToolParameterInfo(name="target", param_type=ToolParamType.STRING,
                              description="玩家名 / 实体名 / nearest_player", required=True),
            ToolParameterInfo(name="range", param_type=ToolParamType.INTEGER,
                              description="跟随距离，默认 3", required=False, default=3),
            ToolParameterInfo(name="duration_ms", param_type=ToolParamType.INTEGER,
                              description="跟随时长（毫秒），默认 60000，0 表示直到被停止", required=False,
                              default=60000),
        ],
    )
    async def mc_follow(self, target: str, range: int = 3, duration_ms: int = 20000, **kwargs: Any):
        # 默认跟随时长从 60 秒降到 20 秒：跟随是「长动作」，但一次工具调用不该霸占太久
        # （框架单次 RPC 只有 60 秒）。想跟更久就隔一会儿再调一次，或者显式传 duration_ms ——
        # 超过 _MAX_TOOL_WAIT_SECONDS 的那部分会被截断成「还在跑，去看进度」。
        timeout = float(self.config.safety.max_action_timeout_seconds)
        if duration_ms and duration_ms > 0:
            timeout = min(timeout, duration_ms / 1000.0 + 10.0)
        return await self._call(P.A_FOLLOW,
                                {"target": target, "range": range, "durationMs": duration_ms},
                                timeout=timeout)

    @Tool(
        "mc_stop",
        brief_description="立刻停止角色当前的一切动作（移动、挖矿、追击…）",
        detailed_description="紧急刹车。清空动作队列并立即结束当前动作，角色会停在原地。",
        parameters=[],
    )
    async def mc_stop(self, **kwargs: Any):
        return await self._call(P.A_STOP, {}, timeout=10.0)

    @Tool(
        "mc_task_status",
        brief_description="查看角色当前正在做什么、队列里还排着哪些动作",
        detailed_description="返回当前动作、进度、队列长度，以及历史执行统计。用于确认上一条指令是否还在跑。",
        parameters=[],
    )
    async def mc_task_status(self, **kwargs: Any):
        """任务状态：把**自己的动作**和 **Baritone 的活**一起报出来。

        以前这里只说「空闲」—— 因为 Baritone 干活时不经过模组的动作队列。
        麦麦看到「空闲」就会以为没人做事，可能重复下发，或者在 Baritone
        还在挖的时候改主意。现在两边的状态合并在一句话里。
        """
        response = await self._call(P.A_TASK_STATUS, {}, timeout=10.0)
        if not response.get("success"):
            return response
        task = response.get("result") or {}

        lines: list[str] = []
        current = task.get("current") or None
        queued = task.get("queued") or []
        if current:
            lines.append(f"正在做：{current.get('action')}"
                         f"（{int(current.get('elapsedMs') or 0) // 1000} 秒"
                         + (f"，进度 {current.get('progress')}" if current.get("progress") is not None else "")
                         + (f"，{current.get('detail')}" if current.get("detail") else "") + "）")
        else:
            lines.append("自己的动作队列：空闲")

        if queued:
            lines.append("排队中：" + "、".join(str(q.get("action")) for q in queued))

        baritone = task.get("baritone") or None
        if baritone:
            state = "进行中" if baritone.get("running") else "已停"
            lines.append(f"Baritone：{baritone.get('command')} —— {state}"
                         f"（{int(baritone.get('elapsedMs') or 0) // 1000} 秒，"
                         f"{'在动' if baritone.get('moving') else '暂时没动'}）")
            if baritone.get("reply"):
                lines.append(f"  Baritone 回话：{baritone['reply']}")
            if baritone.get("note"):
                lines.append(f"  提示：{baritone['note']}")
            response["baritone"] = baritone
        else:
            lines.append("Baritone：没在指挥它")
        lines.append(f"累计执行 {task.get('executedTotal', 0)} 个动作，失败 {task.get('failedTotal', 0)} 个")

        response["content"] = "\n".join(lines)
        response["task"] = task
        return response

    @Tool(
        "mc_mine",
        brief_description="挖掉指定坐标的一个方块（会自动走过去、对准、挖穿）",
        detailed_description=(
            "挖掉一个指定坐标上的方块。角色会自动寻路走到够得着的位置，对准方块并持续挖掘直到它被破坏。\n"
            "参数说明：\n"
            "- x、y、z：integer，必填。要挖掉的方块坐标。\n"
            "- player：string，可选。指定游戏客户端。\n"
            "如果只知道方块类型不知道坐标，请先用 mc_scan_blocks 查坐标，或者直接用 mc_mine_blocks。"
        ),
        parameters=[
            ToolParameterInfo(name="x", param_type=ToolParamType.INTEGER, description="方块 X 坐标", required=True),
            ToolParameterInfo(name="y", param_type=ToolParamType.INTEGER, description="方块 Y 坐标", required=True),
            ToolParameterInfo(name="z", param_type=ToolParamType.INTEGER, description="方块 Z 坐标", required=True),
            ToolParameterInfo(name="player", param_type=ToolParamType.STRING,
                              description="游戏内玩家名", required=False, default=""),
        ],
    )
    async def mc_mine(self, x: int, y: int, z: int, player: str = "", **kwargs: Any):
        return await self._call(P.A_MINE, {"x": x, "y": y, "z": z}, player=player,
                                timeout=float(self.config.safety.max_action_timeout_seconds))

    @Tool(
        "mc_mine_blocks",
        brief_description="自动寻找并挖掉若干指定类型的方块（挖矿、砍树的主力工具）",
        detailed_description=(
            "扫描附近匹配的方块，按距离从近到远逐个挖掉，每个都会自动走过去。\n"
            "参数说明：\n"
            "- block：string，必填。方块名（iron_ore、oak_log…）或标签（#minecraft:logs）。\n"
            "- count：integer，可选。要挖几个，默认 1。\n"
            "- radius：integer，可选。搜索半径，默认 32，最大 64。\n"
            "示例：block=iron_ore, count=8 表示「挖 8 个铁矿石」。"
        ),
        parameters=[
            ToolParameterInfo(name="block", param_type=ToolParamType.STRING,
                              description="方块名或 #标签，例如 iron_ore、#minecraft:logs", required=True),
            ToolParameterInfo(name="count", param_type=ToolParamType.INTEGER,
                              description="要挖的数量，默认 1", required=False, default=1),
            ToolParameterInfo(name="radius", param_type=ToolParamType.INTEGER,
                              description="搜索半径，默认 32", required=False, default=32),
        ],
    )
    async def mc_mine_blocks(self, block: str, count: int = 1, radius: int = 32,
                             via: str = "", **kwargs: Any):
        params: dict[str, Any] = {"block": block, "count": count, "radius": radius}
        if str(via).strip():
            # 默认（留空）装了 Baritone 就交给它的 #mine；填 native 用自带实现
            params["via"] = str(via).strip()
        return await self._call(P.A_MINE_BLOCKS, params,
                                timeout=float(self.config.safety.max_action_timeout_seconds))

    @Tool(
        "mc_forage",
        brief_description="自己去找吃的：干草块做面包 / 打动物拿肉 / 翻宝箱 —— 一条条试到有吃的为止",
        detailed_description=(
            "**饿了又没食物时用它**，模组会自己按「最快能吃到嘴」的顺序试三条路：\n"
            "① **干草块 → 面包**（首选）：找到干草块 → 走过去挖掉 → 合成小麦 → 合成面包；\n"
            "② **打动物**：附近有牛/猪/鸡/羊就杀掉，拿生肉（生肉能吃，烤熟回得更多）；\n"
            "③ **翻宝箱**：走到箱子旁打开它，把里面的东西报给你看。\n"
            "每条路走不通会自动退到下一条（附近没干草块 / 够不着 / 打不到），"
            "全都不行才失败，并说明每条为什么没成。\n"
            "结果里的 steps 是它一路干了什么，breadNow / meat 是最后手上有多少吃的。\n"
            "拿到吃的之后不用你管：饿了模组会自己吃（[combat] autoEat），而且挑营养最高的。\n"
            "参数说明：\n"
            "- bread：integer，可选。想做几个面包，默认 6。\n"
            "- player：string，可选。指定游戏客户端。"
        ),
        parameters=[
            ToolParameterInfo(name="bread", param_type=ToolParamType.INTEGER,
                              description="想做几个面包，默认 6", required=False, default=6),
            ToolParameterInfo(name="player", param_type=ToolParamType.STRING,
                              description="游戏内玩家名", required=False, default=""),
        ],
    )
    async def mc_forage(self, bread: int = 6, player: str = "", **kwargs: Any):
        return await self._call(P.A_FORAGE, {"bread": max(1, int(bread))},
                                player=player,
                                timeout=float(self.config.safety.max_action_timeout_seconds))

    @Tool(
        "mc_place",
        brief_description="在指定坐标放置手上的方块（支持 \"~\" 相对坐标）",
        detailed_description=(
            "把当前手持的方块放到目标坐标。角色会先走到够得着的位置，再对着相邻方块的表面右键。\n"
            "参数说明：\n"
            "- x、y、z：必填。要放置方块的目标坐标（那个位置得是空的）。\n"
            "  可以直接给整数，也可以给相对坐标：\"~\" 表示自己脚下那一格，\"~-1\" 表示再往下一格。\n"
            "  想放在自己脚边就写 x=\"~\"、y=\"~-1\"、z=\"~\"，不用先去查自己的坐标。\n"
            "- item：string，可选。放置前先把手持物品切换成这个（例如 torch、oak_planks）。\n"
            "- player：string，可选。指定游戏客户端。\n"
            "提示：放置必须挨着一个已有的方块，不能凭空放在空中。"
        ),
        parameters=[
            ToolParameterInfo(name="x", param_type=ToolParamType.STRING,
                              description='目标 X 坐标，整数或 "~" 相对坐标', required=True),
            ToolParameterInfo(name="y", param_type=ToolParamType.STRING,
                              description='目标 Y 坐标，整数或 "~-1" 这样的相对坐标', required=True),
            ToolParameterInfo(name="z", param_type=ToolParamType.STRING,
                              description='目标 Z 坐标，整数或 "~" 相对坐标', required=True),
            ToolParameterInfo(name="item", param_type=ToolParamType.STRING,
                              description="放置前切换到的手持物品名", required=False, default=""),
        ],
    )
    async def mc_place(self, x: Any = None, y: Any = None, z: Any = None,
                       item: str = "", **kwargs: Any):
        return await self._call(P.A_PLACE, {"x": x, "y": y, "z": z, "item": item},
                                timeout=float(self.config.safety.max_action_timeout_seconds))

    @Tool(
        "mc_use_on_block",
        brief_description="右键点击某个方块（开箱子、按按钮、用工作台、开门…）",
        detailed_description=(
            "对着指定方块右键交互，和真人右键完全一致。\n"
            "参数说明：\n"
            "- x、y、z：必填。要交互的方块坐标。整数或 \"~\" 相对坐标都行（\"~\" 是自己脚下那一格）。\n"
            "- face：string，可选。从哪一面点，可选 up/down/north/south/east/west。"
        ),
        parameters=[
            ToolParameterInfo(name="x", param_type=ToolParamType.STRING,
                              description='方块 X 坐标，整数或 "~" 相对坐标', required=True),
            ToolParameterInfo(name="y", param_type=ToolParamType.STRING,
                              description='方块 Y 坐标，整数或 "~-1" 这样的相对坐标', required=True),
            ToolParameterInfo(name="z", param_type=ToolParamType.STRING,
                              description='方块 Z 坐标，整数或 "~" 相对坐标', required=True),
            ToolParameterInfo(name="face", param_type=ToolParamType.STRING,
                              description="点击方向 up/down/north/south/east/west", required=False, default=""),
        ],
    )
    async def mc_use_on_block(self, x: Any = None, y: Any = None, z: Any = None,
                              face: str = "", **kwargs: Any):
        return await self._call(P.A_USE_ON_BLOCK, {"x": x, "y": y, "z": z, "face": face},
                                timeout=float(self.config.safety.max_action_timeout_seconds))

    @Tool(
        "mc_use",
        brief_description="使用当前手持的物品（吃东西、拉弓、举盾、放水桶…）",
        detailed_description=(
            "使用手上的物品。\n"
            "参数说明：\n"
            "- hand：string，可选。main（默认，主手）或 off（副手）。\n"
            "- duration_ms：integer，可选。长按多久（毫秒）。拉弓、举盾这类需要蓄力的物品要填，默认 0 表示点一下。"
        ),
        parameters=[
            ToolParameterInfo(name="hand", param_type=ToolParamType.STRING,
                              description="main 或 off", required=False, default="main"),
            ToolParameterInfo(name="duration_ms", param_type=ToolParamType.INTEGER,
                              description="长按毫秒数，默认 0", required=False, default=0),
        ],
    )
    async def mc_use(self, hand: str = "main", duration_ms: int = 0, **kwargs: Any):
        return await self._call(P.A_USE_ITEM, {"hand": hand, "durationMs": duration_ms}, timeout=30.0)

    @Tool(
        "mc_attack",
        brief_description="攻击指定的生物或玩家，直到它死亡或达到次数上限",
        detailed_description=(
            "追击并攻击一个目标。会自动走过去、对准、等待攻击冷却后出手。\n"
            "参数说明：\n"
            "- target：string，必填。玩家名、实体名（zombie）、uuid，或 nearest_hostile（最近的敌对生物）。\n"
            "- count：integer，可选。最多打几下，默认 0 表示一直打到目标死亡。\n"
            "- duration_ms：integer，可选。最长打多久（毫秒），默认 30000。\n"
            "示例：target=nearest_hostile 表示「把最近的怪打死」。"
        ),
        parameters=[
            ToolParameterInfo(name="target", param_type=ToolParamType.STRING,
                              description="目标名 / uuid / nearest_hostile", required=True),
            ToolParameterInfo(name="count", param_type=ToolParamType.INTEGER,
                              description="最多攻击次数，0 表示打到死", required=False, default=0),
            ToolParameterInfo(name="duration_ms", param_type=ToolParamType.INTEGER,
                              description="最长时长（毫秒），默认 30000", required=False, default=30000),
        ],
    )
    async def mc_attack(self, target: str, count: int = 0, duration_ms: int = 30000, **kwargs: Any):
        return await self._call(P.A_ATTACK,
                                {"target": target, "count": count, "durationMs": duration_ms},
                                timeout=float(self.config.safety.max_action_timeout_seconds))

    @Tool(
        "mc_defend",
        brief_description="抵御：自己判断局势，清掉附近的敌对生物（血少会先吃或后撤）",
        detailed_description=(
            "让角色自己守着这一片：模组会盯着附近的敌对生物，逐个清掉，"
            "并且会根据自身情况临时改主意——\n"
            "  · 血少而且背包里有吃的 → 先吃一口再打；\n"
            "  · 血少又没有吃的 → 先拉开距离；\n"
            "  · 被三个以上围住而且血不到一半 → 也先撤；\n"
            "  · 否则 → 打「正在打我」的那个（其次挑够得着的、近的）。\n"
            "这些判断都在模组里做，不需要一步一步指挥——这正是它比反复调用 mc_attack 强的地方：\n"
            "等 AI 想一轮再出手，往往已经挨了好几下。\n"
            "参数说明：\n"
            "- radius：integer，可选。威胁判定半径，默认 16。\n"
            "- max_kills：integer，可选。最多清掉几个，默认 8。\n"
            "- retreat_health：number，可选。血低于这个值就先自保，默认 8。\n"
            "- flee：boolean，可选。只跑不打（默认 false）。角色快死了、或者你只是让它躲开时用。\n"
            "- x、y、z：可选。给了就当作驻守点，清完威胁会走回去。\n"
            "- player：string，可选。指定游戏客户端。\n"
            "返回里有 kills（清掉几个）、healthStart/healthEnd/healthLowest（血量变化）、"
            "remaining（还剩哪些威胁）、log（每步做了什么）。"
        ),
        parameters=[
            ToolParameterInfo(name="radius", param_type=ToolParamType.INTEGER,
                              description="威胁判定半径，默认 16", required=False, default=16),
            ToolParameterInfo(name="max_kills", param_type=ToolParamType.INTEGER,
                              description="最多清掉几个，默认 8", required=False, default=8),
            ToolParameterInfo(name="retreat_health", param_type=ToolParamType.NUMBER,
                              description="血低于这个值就先吃或后撤，默认 8", required=False,
                              default=8.0),
            ToolParameterInfo(name="flee", param_type=ToolParamType.BOOLEAN,
                              description="true = 只跑不打", required=False, default=False),
            ToolParameterInfo(name="x", param_type=ToolParamType.INTEGER,
                              description="驻守点 X（可选，给了就清完走回去）", required=False),
            ToolParameterInfo(name="y", param_type=ToolParamType.INTEGER,
                              description="驻守点 Y（可选）", required=False),
            ToolParameterInfo(name="z", param_type=ToolParamType.INTEGER,
                              description="驻守点 Z（可选）", required=False),
            ToolParameterInfo(name="player", param_type=ToolParamType.STRING,
                              description="指定游戏客户端", required=False, default=""),
        ],
    )
    async def mc_defend(self, radius: int = 16, max_kills: int = 8, retreat_health: float = 8.0,
                        flee: bool = False, x: Any = None, y: Any = None, z: Any = None,
                        player: str = "", **kwargs: Any):
        params: dict[str, Any] = {
            "radius": max(3, min(int(radius), 64)),
            "maxKills": max(1, int(max_kills)),
            "retreatHealth": float(retreat_health),
            "flee": bool(flee),
        }
        # 驻守点：x/z 必须成对给，缺 y 就让模组用玩家当前高度
        if x is not None and z is not None:
            params["x"] = int(x)
            params["z"] = int(z)
            if y is not None:
                params["y"] = int(y)
        # 防御可能持续一阵子，给足预算（由 durationMs 兜底）
        return await self._call(P.A_DEFEND, params, player=player,
                                timeout=float(self.config.safety.max_action_timeout_seconds))

    @Tool(
        "mc_look_at",
        brief_description="让角色转头看向某个坐标、某个实体，或者设定固定视角",
        detailed_description=(
            "控制角色视角。以下三种参数任选一种：\n"
            "- 看向坐标：给 x、y、z。\n"
            "- 看向实体：给 entity（玩家名/实体名/uuid）。\n"
            "- 固定角度：给 yaw 和/或 pitch。"
        ),
        parameters=[
            ToolParameterInfo(name="x", param_type=ToolParamType.NUMBER, description="目标 X", required=False),
            ToolParameterInfo(name="y", param_type=ToolParamType.NUMBER, description="目标 Y", required=False),
            ToolParameterInfo(name="z", param_type=ToolParamType.NUMBER, description="目标 Z", required=False),
            ToolParameterInfo(name="entity", param_type=ToolParamType.STRING,
                              description="实体名 / 玩家名 / uuid", required=False, default=""),
            ToolParameterInfo(name="yaw", param_type=ToolParamType.NUMBER, description="水平角（度）", required=False),
            ToolParameterInfo(name="pitch", param_type=ToolParamType.NUMBER, description="俯仰角（度）", required=False),
        ],
    )
    async def mc_look_at(self, x: Any = None, y: Any = None, z: Any = None, entity: str = "",
                         yaw: Any = None, pitch: Any = None, **kwargs: Any):
        params: dict[str, Any] = {}
        if x is not None and y is not None and z is not None:
            params.update({"x": x, "y": y, "z": z})
        elif entity:
            params["entity"] = entity
        elif yaw is not None or pitch is not None:
            if yaw is not None:
                params["yaw"] = yaw
            if pitch is not None:
                params["pitch"] = pitch
        else:
            return {"success": False, "content": "请提供 x/y/z、entity 或 yaw/pitch 中的至少一组参数。"}
        return await self._call(P.A_LOOK, params, timeout=10.0)

    @Tool(
        "mc_equip",
        brief_description="切换手持物品（选中快捷栏某个格子，或按名字把物品换到手上/副手）",
        detailed_description=(
            "参数三选一：\n"
            "- slot：integer，0-8，直接选中快捷栏对应格子。\n"
            "- item：string，按名字找物品（支持模糊匹配，如 pickaxe、iron_sword）。"
            "在快捷栏里就直接选中，在背包里就与当前格子交换。\n"
            "- item + offhand=true：把物品换到副手（例如把盾牌换到副手）。\n"
            "示例：想换成镐子就说 item=iron_pickaxe 或 item=pickaxe。"
        ),
        parameters=[
            ToolParameterInfo(name="item", param_type=ToolParamType.STRING,
                              description="物品名（模糊匹配），例如 iron_pickaxe", required=False, default=""),
            ToolParameterInfo(name="slot", param_type=ToolParamType.INTEGER,
                              description="快捷栏槽位 0-8", required=False),
            ToolParameterInfo(name="offhand", param_type=ToolParamType.BOOLEAN,
                              description="是否换到副手", required=False, default=False),
            ToolParameterInfo(name="armor", param_type=ToolParamType.BOOLEAN,
                              description="是否装备到护甲槽", required=False, default=False),
        ],
    )
    async def mc_equip(self, item: str = "", slot: Any = None, offhand: bool = False,
                       armor: bool = False, **kwargs: Any):
        params: dict[str, Any] = {}
        if item:
            params["item"] = item
        if slot is not None:
            params["slot"] = int(slot)
        if offhand:
            params["offhand"] = True
        if armor:
            params["armor"] = True
        if not params:
            return {"success": False, "content": "请提供 item（物品名）或 slot（快捷栏槽位 0-8）。"}
        return await self._call(P.A_EQUIP, params, timeout=15.0)

    @Tool(
        "mc_drop",
        brief_description="丢弃物品（丢掉手里的东西、清理背包）",
        detailed_description=(
            "参数：\n"
            "- item：string，可选。物品名，模糊匹配。\n"
            "- slot：integer，可选。直接指定槽位（0-8 快捷栏，9-35 背包）。\n"
            "- count：integer，可选。丢几个，默认 0 表示整叠丢掉。"
        ),
        parameters=[
            ToolParameterInfo(name="item", param_type=ToolParamType.STRING,
                              description="物品名", required=False, default=""),
            ToolParameterInfo(name="slot", param_type=ToolParamType.INTEGER,
                              description="槽位 0-35", required=False),
            ToolParameterInfo(name="count", param_type=ToolParamType.INTEGER,
                              description="丢弃数量，0 表示整叠", required=False, default=0),
        ],
    )
    async def mc_drop(self, item: str = "", slot: Any = None, count: int = 0, **kwargs: Any):
        params: dict[str, Any] = {"count": count}
        if item:
            params["item"] = item
        if slot is not None:
            params["slot"] = int(slot)
        if not item and slot is None:
            return {"success": False, "content": "请提供 item（物品名）或 slot（槽位）。"}
        return await self._call(P.A_DROP, params, timeout=15.0)

    @Tool(
        "mc_sleep",
        brief_description="找附近的床并睡上去（跳过夜晚）",
        detailed_description=(
            "搜索附近的床，走过去睡下。如果附近没床、或者不是夜晚/有怪物，会说明原因。\n"
            "参数说明：\n"
            "- radius：integer，可选。搜索半径，默认 16。"
        ),
        parameters=[
            ToolParameterInfo(name="radius", param_type=ToolParamType.INTEGER,
                              description="搜索半径，默认 16", required=False, default=16),
        ],
    )
    async def mc_sleep(self, radius: int = 16, **kwargs: Any):
        return await self._call(P.A_SLEEP, {"radius": radius},
                                timeout=float(self.config.safety.max_action_timeout_seconds))

    # ---------------------------------------------------------------- 合成

    @Tool(
        "mc_craft",
        brief_description="合成物品：自动找配方、检查材料、摆进合成格、把成品收进背包",
        detailed_description=(
            "让角色合成指定物品。模组会自己完成整条链路：\n"
            "  1. 找出能产出该物品的配方（有多个时挑材料够用的那个）；\n"
            "  2. 检查背包里的材料够不够，不够会明确告诉你缺什么；\n"
            "  3. 2x2 的配方直接用背包自带的合成格；3x3 的配方会走到附近的工作台并打开它；\n"
            "  4. 反复合成，直到做出你要的数量或材料耗尽。\n"
            "参数说明：\n"
            "- item：string，必填。要合成的物品名，原版 ID 或中文名都行，支持模糊匹配。\n"
            "  例如 oak_planks、stick、crafting_table、torch、iron_pickaxe、stone_sword。\n"
            "- count：integer，可选。想要几个成品，默认 1。\n"
            "建议流程：先 mc_inventory 看手上有什么 → mc_recipes 查配方和材料 → mc_craft。\n"
            "注意：需要 3x3 的配方必须在工作台附近。附近没有的话，先 "
            "mc_craft crafting_table（4 个木板，2x2 就能做），再 mc_place 放到脚边，然后再合成。"
        ),
        parameters=[
            ToolParameterInfo(name="item", param_type=ToolParamType.STRING,
                              description="要合成的物品名，例如 oak_planks、stick、iron_pickaxe",
                              required=True),
            ToolParameterInfo(name="count", param_type=ToolParamType.INTEGER,
                              description="想要几个成品，默认 1", required=False, default=1),
        ],
    )
    async def mc_craft(self, item: str, count: int = 1, **kwargs: Any):
        if not str(item).strip():
            return {"success": False, "content": "缺少参数 item（要合成的物品名）。"}
        return await self._call(P.A_CRAFT, {"item": str(item).strip(), "count": int(count)},
                                timeout=float(self.config.safety.max_action_timeout_seconds))

    @Tool(
        "mc_smelt",
        brief_description="在熔炉里烧东西：原矿变锭、沙子变玻璃、生肉变熟肉",
        detailed_description=(
            "把东西放进附近的熔炉烧。**没有它，生存流程走不下去** —— 挖出来的是原矿，"
            "而做桶、做打火石、做铁镐全都要铁锭。\n"
            "参数说明：\n"
            "- item：string，必填。**你想要的东西**，不是原料。"
            "例如 iron_ingot（会自动找生铁/铁矿石）、glass（找沙子）、cooked_beef（找生牛肉）。"
            "支持模糊匹配。\n"
            "- count：integer，可选。要几个成品，默认 1。\n"
            "- radius：integer，可选。找熔炉的半径，默认 8。\n"
            "要求：**附近要有熔炉**。没有的话先 mc_craft item=furnace（8 个圆石，需要工作台）"
            "再 mc_place 放到脚边。燃料（煤/木炭/木板/原木）和原料都会从背包里自动挑，不用你指定。\n"
            "烧一个东西大约 10 秒，所以 count 大的时候这个工具会等一会儿。\n"
            "返回里有 input（实际用了什么原料）、fuel（用了什么燃料）、received（实际拿到几个）。"
        ),
        parameters=[
            ToolParameterInfo(name="item", param_type=ToolParamType.STRING,
                              description="想要的成品，例如 iron_ingot、glass、cooked_beef",
                              required=True),
            ToolParameterInfo(name="count", param_type=ToolParamType.INTEGER,
                              description="要几个成品，默认 1", required=False, default=1),
            ToolParameterInfo(name="radius", param_type=ToolParamType.INTEGER,
                              description="找熔炉的半径，默认 8", required=False, default=8),
        ],
    )
    async def mc_smelt(self, item: str, count: int = 1, radius: int = 8, **kwargs: Any):
        if not str(item).strip():
            return {"success": False, "content": "缺少参数 item（要烧出什么，例如 iron_ingot）。"}
        count = max(1, int(count))
        # 原版烧一个约 10 秒；给它「烧的时间 + 走路开界面」的余量
        timeout = max(60.0, count * 12.0 + 30.0)
        timeout = min(timeout, float(self.config.safety.max_action_timeout_seconds) * 3)
        return await self._call(P.A_SMELT,
                                {"item": str(item).strip(), "count": count, "radius": int(radius)},
                                timeout=timeout)

    # ------------------------------------------------------------ Baritone

    #: Baritone 的子命令 → 对应它的聊天指令。用它自己的指令而不是 API：
    #: Baritone 的 jar 是混淆的（api 包只剩几个类），而聊天指令是**稳定接口**；
    #: 而且这样做到零依赖 —— 没装 Baritone 时这些消息只是普通聊天，不会崩。
    BARITONE_ACTIONS: ClassVar[dict[str, str]] = {
        "goto": "走到某个坐标或某种方块（会自己绕障碍、搭桥、挖穿）",
        "mine": "找并挖某种方块，直到挖够数量（按方块类型找，不是挖眼前）",
        "explore": "自己往外探索找新地形（找岩浆、找结构都靠它）",
        "tunnel": "往前挖一条隧道（指定高度）",
        "come": "走到我（调用者）身边",
        "follow": "跟着某个玩家/实体",
        "thisway": "朝当前朝向走 N 格",
        "build": "按 schematic 建造（需要 Baritone 的 schematics 目录里有文件）",
        "stop": "停下 Baritone 的一切动作",
    }

    @Tool(
        "mc_baritone",
        brief_description="调用 Baritone：寻路/挖矿/探索/挖隧道/建造（比模组自带的动作强得多，装了 Baritone 就该用它）",
        detailed_description=(
            "把任务交给 **Baritone** 执行。Baritone 是成熟的寻路机器人，"
            "寻路、找矿、探图、挖隧道这些它都比模组自带的那套强——绕障碍、搭桥、挖穿、垫脚都会自己做。\n"
            "\n"
            "前提：游戏 mods 目录里装了 baritone.jar。没装的话这些指令不会生效（也不会报错）。\n"
            "\n"
            "参数说明：\n"
            "- action：必填，下面这些之一：\n"
            + "\n".join(f"    · {k}：{v}" for k, v in {
                "goto": "走到坐标或方块。给了 x/y/z 就走坐标；只给 target 就走过去挖那种方块",
                "mine": "找并挖 target 指定的方块（例如 iron_ore、#minecraft:logs），count 给挖几个",
                "explore": "自己往外探图找新地形（找岩浆湖、找结构用它）",
                "tunnel": "往前挖隧道，height 给隧道高度（默认 2）",
                "come": "走到调用者（玩家）身边",
                "follow": "跟着 target 指定的玩家",
                "thisway": "朝当前朝向走 count 格",
                "build": "按 target 指定的 schematic 建造",
                "stop": "停下 Baritone 的一切动作",
            }.items()) + "\n"
            "- target：string，可选。方块名（iron_ore、#minecraft:logs）、玩家名、或 schematic 名。\n"
            "- x、y、z：整数，可选。goto 的目标坐标。\n"
            "- count：integer，可选。mine 挖几个 / thisway 走几格。\n"
            "\n"
            "例子：\n"
            "  action=goto, x=100, y=64, z=-200    走到那个坐标\n"
            "  action=goto, target=iron_ore        自己找铁矿石并走过去\n"
            "  action=mine, target=iron_ore, count=8   挖 8 个铁矿石\n"
            "  action=explore                      出去探图（找岩浆/找结构）\n"
            "  action=stop                         立刻停下\n"
            "\n"
            "注意：Baritone 是**异步**的——指令发出去它就自己开始干了，不会等做完才返回。"
            "发完可以用 mc_state 看它在做什么，或者用 mc_task_status。要停就 action=stop。"
        ),
        parameters=[
            ToolParameterInfo(name="action", param_type=ToolParamType.STRING,
                              description="goto / mine / explore / tunnel / come / follow / thisway / build / stop",
                              required=True, enum_values=list(BARITONE_ACTIONS)),
            ToolParameterInfo(name="target", param_type=ToolParamType.STRING,
                              description="方块名 / 玩家名 / schematic 名", required=False, default=""),
            ToolParameterInfo(name="x", param_type=ToolParamType.INTEGER,
                              description="goto 的目标 X", required=False),
            ToolParameterInfo(name="y", param_type=ToolParamType.INTEGER,
                              description="goto 的目标 Y", required=False),
            ToolParameterInfo(name="z", param_type=ToolParamType.INTEGER,
                              description="goto 的目标 Z", required=False),
            ToolParameterInfo(name="count", param_type=ToolParamType.INTEGER,
                              description="mine 挖几个 / thisway 走几格", required=False, default=0),
            ToolParameterInfo(name="player", param_type=ToolParamType.STRING,
                              description="指定游戏客户端", required=False, default=""),
        ],
    )
    async def mc_baritone(self, action: str, target: str = "", x: Any = None, y: Any = None,
                          z: Any = None, count: int = 0, player: str = "", **kwargs: Any):
        name = str(action).strip().lower()
        if name not in self.BARITONE_ACTIONS:
            return {"success": False,
                    "content": f"不认识的 Baritone 动作「{action}」。可用：" +
                               "、".join(self.BARITONE_ACTIONS)}

        text = _baritone_command(name, target=target, x=x, y=y, z=z, count=count)
        if isinstance(text, str) and text.startswith("!"):
            return {"success": False, "content": text[1:]}

        result = await self._call(P.A_CHAT, {"message": text}, player=player, timeout=20.0)
        if not result.get("success"):
            return result

        # ---- 挖矿：Baritone 自己知道挖几个（数量在指令里），但我们仍然盯两件事：
        #      背包里的数量（够了没）+ 它还在不在动（是在干活还是停了/卡了）。
        if name == "mine" and int(count) > 0:
            target_count = int(count)
            deadline = time.time() + min(300.0, 30.0 + target_count * 20.0)
            have = 0
            stalled = 0
            while time.time() < deadline:
                await asyncio.sleep(3.0)
                state = await self._call(P.A_GET_STATE, {}, player=player, timeout=20.0)
                snapshot = state.get("result") or {}
                have = _count_items(snapshot, target)
                if have >= target_count:
                    break
                # 模组侧的「Baritone 活动监视」：连续两次快照都没动静就当它停了，
                # 不用傻等满 300 秒（真机上「附近没这种方块」时它就是这样停下来的）。
                baritone = snapshot.get("baritone") or {}
                if baritone and not baritone.get("running"):
                    stalled += 1
                    if stalled >= 2:
                        break
                else:
                    stalled = 0
            done = have >= target_count
            if done:
                await self._call(P.A_CHAT, {"message": "#stop"}, player=player, timeout=20.0)
            result["content"] = (
                f"让 Baritone 挖 {target}，现在背包里有 {have} 个（目标 {target_count}）"
                + ("，已经够了，已让它停下。" if done else
                   "，还没挖够 —— 可能附近没有这种方块，或者它已经停下来了。"
                   "可以先用 mc_scan_blocks 确认附近有没有；要停就 action=stop。")
            )
            result["mined"] = have
            result["baritone"] = (snapshot.get("baritone") if not done else None)
            result["success"] = done
            return result

        result["content"] = (
            f"已交给 Baritone：{text}\n"
            "Baritone 是异步执行的，它会自己开始干；想停下就 mc_baritone(action=\"stop\")，"
            "想看进展用 mc_state。"
        )
        result["baritone_command"] = text
        return result

    @Tool(
        "mc_bucket",
        brief_description="装液体 / 倒液体：装一桶水或岩浆，或者把桶里的液体倒出来",
        detailed_description=(
            "用桶装液体或倒液体。**做黑曜石（进而搭地狱门）必须用它**：\n"
            "  黑曜石 = 岩浆源 + 水。所以要「装一桶岩浆倒到位置 A，再装一桶水倒上去」。\n"
            "\n"
            "参数说明：\n"
            "- mode：string，必填。\n"
            "    · fill  —— 装液体：自动找附近**源头**（流动的液体装不起来），走过去右键装满。\n"
            "    · empty —— 倒液体：把手上的水桶/岩浆桶倒在 x/y/z 那一格。\n"
            "- fluid：string，可选，默认 water。fill 时装哪种液体：water 或 lava。\n"
            "- x、y、z：mode=empty 时必填，液体倒在哪一格。支持 \"~\" 相对坐标\n"
            "  （\"~\" 是自己脚下那一格，\"~2\" 是前方两格），例如 {\"x\":\"~\",\"y\":\"~\",\"z\":\"~2\"}。\n"
            "- radius：integer，可选，默认 24。fill 时找液体的搜索半径。\n"
            "\n"
            "前提：手上或背包里要有**桶**（空桶 = 3 个铁锭合成）。\n"
            "要点：倒液体时流体落在**点击面的相邻格**，模组已经帮你算好了 —— 你只给最终落点就行。\n"
            "返回里 source（从哪装的）/ pouredAt（倒在哪了）。\n"
            "\n"
            "搭地狱门的典型用法：\n"
            "  1. mc_baritone(action=\"mine\", target=\"iron_ore\", count=4)  挖铁\n"
            "  2. mc_smelt(item=\"iron_ingot\", count=3) → mc_craft(item=\"bucket\", count=2)\n"
            "  3. mc_bucket(mode=\"fill\", fluid=\"lava\")  装一桶岩浆\n"
            "  4. mc_bucket(mode=\"empty\", x=.., y=.., z=..)  倒在框架位置\n"
            "  5. mc_bucket(mode=\"fill\", fluid=\"water\") → mc_bucket(mode=\"empty\", ...) 浇水成黑曜石"
        ),
        parameters=[
            ToolParameterInfo(name="mode", param_type=ToolParamType.STRING,
                              description="fill（装液体）或 empty（倒液体）",
                              required=True, enum_values=["fill", "empty"]),
            ToolParameterInfo(name="fluid", param_type=ToolParamType.STRING,
                              description="fill 时装什么：water 或 lava，默认 water",
                              required=False, default="water", enum_values=["water", "lava"]),
            ToolParameterInfo(name="x", param_type=ToolParamType.STRING,
                              description='empty 时必填：倒在哪一格的 X（支持 "~"）', required=False),
            ToolParameterInfo(name="y", param_type=ToolParamType.STRING,
                              description='empty 时必填：Y（支持 "~" / "~-1"）', required=False),
            ToolParameterInfo(name="z", param_type=ToolParamType.STRING,
                              description='empty 时必填：Z（支持 "~"）', required=False),
            ToolParameterInfo(name="radius", param_type=ToolParamType.INTEGER,
                              description="fill 时找液体的半径，默认 24", required=False, default=24),
        ],
    )
    async def mc_bucket(self, mode: str, fluid: str = "water", x: Any = None, y: Any = None,
                        z: Any = None, radius: int = 24, **kwargs: Any):
        m = str(mode).strip().lower()
        if m not in ("fill", "empty"):
            return {"success": False,
                    "content": f"mode 只能是 fill（装）或 empty（倒），收到「{mode}」。"}
        fl = str(fluid).strip().lower() or "water"
        if fl not in ("water", "lava"):
            return {"success": False, "content": f"fluid 只能是 water 或 lava，收到「{fluid}」。"}

        params: dict[str, Any] = {"mode": m, "fluid": fl, "radius": max(4, int(radius))}
        if m == "empty":
            if x is None or y is None or z is None:
                return {"success": False,
                        "content": "mode=empty 要给 x/y/z（液体倒在哪一格）。"
                                   '想倒在面前两格就传 {"x": "~", "y": "~", "z": "~2"}。'}
            # 原样透传：模组侧自己解析 "~" 相对坐标（和 place 一样）
            params.update({"x": x, "y": y, "z": z})
        return await self._call(P.A_BUCKET, params,
                                timeout=float(self.config.safety.max_action_timeout_seconds))

    # ------------------------------------------------------------ 托管 / 枪械

    @Tool(
        "mc_takeover",
        brief_description="AI 托管：接手游戏后玩家可以放开鼠标切出去，游戏照常跑、我照常操作",
        detailed_description=(
            "控制「AI 托管」。开着的时候：\n"
            "  · 游戏窗口**失焦也不会暂停**（原版单人游戏一切出去就暂停，那样我一步都走不动）；\n"
            "  · 鼠标从游戏窗口里**放开**，玩家可以切出去干别的，不会和我抢视角。\n"
            "默认「麦麦一连上就自动托管」，断开时自动还原。\n"
            "\n"
            "参数说明：\n"
            "- enabled：boolean，可选。true 进托管、false 交还控制权；不填则切换当前状态。\n"
            "\n"
            "什么时候用：玩家说「你自己玩吧/我切出去了」时不用做任何事（默认就托管着）；"
            "玩家说「我要自己玩」就用 enabled=false 把控制权还回去。"
        ),
        parameters=[
            ToolParameterInfo(name="enabled", param_type=ToolParamType.BOOLEAN,
                              description="true 进托管，false 交还控制权，不填则切换", required=False),
            ToolParameterInfo(name="player", param_type=ToolParamType.STRING,
                              description="游戏内玩家名", required=False, default=""),
        ],
    )
    async def mc_takeover(self, enabled: Optional[bool] = None, player: str = "", **kwargs: Any):
        params: dict[str, Any] = {}
        if enabled is not None:
            params["enabled"] = bool(enabled)
        return await self._call(P.A_TAKEOVER, params, player=player, timeout=15.0)

    @Tool(
        "mc_shoot",
        brief_description="用远程武器开火：弓 / 弩 / 三叉戟 / 枪械都行 —— 开火前会自动数弹药，没子弹会拒绝",
        detailed_description=(
            "用远程武器开火。**任何远程武器都能用**：弓、弩、三叉戟、枪械（永恒枪械工艺这类），"
            "模组会看手上（或背包里）有什么，自动选一把。\n"
            "\n"
            "**开火之前一定先数弹药**：没箭/没子弹时不会空放一枪，而是明确告诉你缺什么、"
            "去哪儿补（箭 = 燧石 + 木棍 + 羽毛）。每次开火的结果里也带剩余弹药，"
            "剩 8 发以下会提醒你补。状态快照里的 ranged 字段随时能看到「手里这把还有几发」。\n"
            "\n"
            "**「响没响」以弹药真的少了为准**：结果是 fired 字段（算出来的），不是「我扣了扳机」。"
            "枪没响时结果里会带 magazine（弹匣还剩几发）、via（这次走的是哪条路）、"
            "taczResult（模组回的状态，例如 IS_RELOADING 正在换弹 / NO_AMMO 打空了）"
            "和一句人话解释，照着它判断下一步。\n"
            "\n"
            "**弹匣清空会自动换弹**：托管期间只要你闲着、背包里还有同口径的子弹，"
            "模组会自己补上（不用你调 reload）。所以打完一梭子看到 magazine=0 不用慌，"
            "下一拍它自己就满了；真正要你操心的是「背包里也没子弹了」。\n"
            "\n"
            "参数说明：\n"
            "- action：string，必填。\n"
            "    · shoot  —— 开火。可以先用 target 瞄准某个实体（名字/uuid/nearest_hostile）。\n"
            "    · reload —— 换弹，**只对枪有意义**（弓/弩/三叉戟不需要，直接 shoot）。\n"
            "- target：string，可选。shoot 时先瞄准它再开火。\n"
            "- ticks：integer，可选，默认 3。枪**持续开火**多少 tick（连发/打空弹匣给大一点，"
            "例如 20 ≈ 一秒、100 能打空一整个弹匣）；弓/弩/三叉戟会自动按各自的蓄力时间拉满再放。\n"
            "\n"
            "什么时候该用远程：目标在天上/会飞（近战够不到）、或者你想在远处先消耗它。\n"
            "**注意**：敌人贴到 2 格以内时，模组的自动战斗会改用近战武器（那时别再调 shoot，"
            "用 mc_attack 更合适）。"
        ),
        parameters=[
            ToolParameterInfo(name="action", param_type=ToolParamType.STRING,
                              description="shoot（开火）或 reload（换弹，只对枪）",
                              required=True, enum_values=["shoot", "reload"]),
            ToolParameterInfo(name="target", param_type=ToolParamType.STRING,
                              description="shoot 时先瞄准的目标（名字/uuid/nearest_hostile）",
                              required=False, default=""),
            ToolParameterInfo(name="ticks", param_type=ToolParamType.INTEGER,
                              description="枪持续开火多少 tick，默认 3（20≈一秒，100≈打空一个弹匣）",
                              required=False, default=3),
            ToolParameterInfo(name="player", param_type=ToolParamType.STRING,
                              description="游戏内玩家名", required=False, default=""),
        ],
    )
    async def mc_shoot(self, action: str, target: str = "", ticks: int = 3,
                       player: str = "", **kwargs: Any):
        name = str(action).strip().lower()
        if name not in ("reload", "shoot"):
            return {"success": False,
                    "content": f"action 只能是 shoot（开火）或 reload（换弹），收到「{action}」。"}
        params: dict[str, Any] = {"ticks": max(1, int(ticks))}
        if name == "reload":
            # 换弹的「等多久」和开火的「按多久」不是一回事：TaCZ 要播完换弹动画才进弹
            # （AK 大约 2.5 秒）。调用方常常沿用默认的 ticks=3，那会让模组只等 1 秒就
            # 误报「没换成」。这里给一个够用的下限。
            params["ticks"] = max(160, int(ticks))
        if name == "shoot" and str(target).strip():
            params["target"] = str(target).strip()
        return await self._call(P.A_SHOOT if name == "shoot" else P.A_RELOAD, params,
                                player=player,
                                timeout=float(self.config.safety.max_action_timeout_seconds))

    @Tool(
        "mc_recipes",
        brief_description="查询某个物品的合成配方、需要什么材料、材料是否够（只查不改）",
        detailed_description=(
            "查询能产出指定物品的合成配方清单。每条会给出：产物数量、需要的材料、"
            "是否需要工作台（3x3）、配方有没有解锁、以及当前背包里的材料够不够。\n"
            "这是「动手之前先规划」用的工具——想合成什么东西之前先查一下，能避免白跑一趟。\n"
            "参数说明：\n"
            "- item：string，必填。要查询的物品名（原版 ID 或中文名，支持模糊匹配）。\n"
            "- limit：integer，可选。最多返回多少条配方，默认 10。"
        ),
        parameters=[
            ToolParameterInfo(name="item", param_type=ToolParamType.STRING,
                              description="要查询的物品名，例如 iron_pickaxe、torch", required=True),
            ToolParameterInfo(name="limit", param_type=ToolParamType.INTEGER,
                              description="最多返回几条配方，默认 10", required=False, default=10),
        ],
    )
    async def mc_recipes(self, item: str, limit: int = 10, **kwargs: Any):
        if not str(item).strip():
            return {"success": False, "content": "缺少参数 item（要查询的物品名）。"}
        return await self._call(P.A_RECIPES, {"item": str(item).strip(), "limit": int(limit)},
                                timeout=20.0)

    # -------------------------------------------------------------- 任务组

    @Tool(
        "mc_script",
        brief_description="任务组：一次把一整段流程交给游戏连着做完（比一步步调用快很多，能提前想清楚就该用它）",
        detailed_description=(
            "把「先做什么、再做什么」写成一段脚本一次下发，模组在游戏里自己连着执行，只在结束时回传一次结果。\n"
            "\n"
            "为什么推荐：一步一步调用时，每一步都要等你重新想一轮（几秒）。做一把石剑要 9 步就是 9 轮。\n"
            "用 mc_script 一次说完，就只有 1 轮。凡是现在就能想清楚的流程，都应该写成脚本。\n"
            "\n"
            "script 的结构：\n"
            "{\n"
            '  "name": "做把石剑",\n'
            '  "maxSteps": 200,\n'
            '  "steps": [ ... ]\n'
            "}\n"
            "\n"
            "steps 里每个元素是下面几种之一：\n"
            '1. 一个动作：{"action": "mine_blocks", "params": {"block": "stone", "count": 3}}\n'
            "   action 就是其它 mc_* 工具对应的动作名，params 和那些工具的参数完全一样。\n"
            '   加上 "optional": true 表示这一步失败也继续往下做。\n'
            '2. 重复：{"repeat": 3, "steps": [ ... ]}\n'
            '3. 条件循环：{"while": {"condition": {...}, "maxIterations": 20}, "steps": [ ... ]}\n'
            '4. 分支：{"if": {"condition": {...}}, "then": [ ... ], "else": [ ... ]}\n'
            '5. 等条件成立：{"waitUntil": {"condition": {...}, "timeoutMs": 30000}}\n'
            "\n"
            "condition 是一个只有单个键的对象：\n"
            '  {"has": {"item": "cobblestone", "count": 3}}          背包里有 3 个（可简写成 {"has": "cobblestone"}）\n'
            '  {"holding": "stone_pickaxe"}                          主手拿着什么\n'
            '  {"healthBelow": 10} / {"foodBelow": 6}                血量 / 饥饿值低于\n'
            '  {"atPos": {"x": 1, "y": 65, "z": 0, "radius": 2}}     在某个坐标附近\n'
            '  {"nearby": {"type": "zombie", "radius": 8, "min": 1}} 附近有实体\n'
            '  {"busy": false}                                       当前没有别的动作在跑\n'
            '  {"all": [...]} {"any": [...]} {"not": {...}}           逻辑组合\n'
            "\n"
            "坐标可以直接写整数，也可以写 \"~\" 相对坐标：\n"
            '  {"action": "place", "params": {"x": "~", "y": "~-1", "z": "~", "item": "crafting_table"}}\n'
            "  ~ 是「我脚下那一格」，~-1 是往下一格。脚本是提前写好的，写的时候还不知道自己会站在哪，"
            "要放在身边就用相对坐标。\n"
            "\n"
            "例子——砍树、做工作台、再做一把木镐：\n"
            '{"name": "木头三件套", "steps": [\n'
            '  {"action": "mine_blocks", "params": {"block": "#minecraft:logs", "count": 3}},\n'
            '  {"action": "craft", "params": {"item": "oak_planks", "count": 12}},\n'
            '  {"action": "craft", "params": {"item": "stick", "count": 4}},\n'
            '  {"action": "craft", "params": {"item": "crafting_table", "count": 1}},\n'
            '  {"action": "place", "params": {"x": "~", "y": "~-1", "z": "~", "item": "crafting_table"}, "optional": true},\n'
            '  {"action": "craft", "params": {"item": "wooden_pickaxe", "count": 1}}\n'
            "]}\n"
            "\n"
            "注意事项：\n"
            "- 拿不准动作名或参数时，先单独调用一次那个工具确认，再写进脚本。\n"
            "- 写之前如果不确定背包里有什么、周围有什么，先 mc_state / mc_inventory / mc_scan_blocks。\n"
            "- 脚本里每个动作都走和单独调用时一样的权限检查，被禁用的动作放进脚本也一样不生效。\n"
            "- 中间某一步失败时脚本会停在那里，并回传已经做到哪一步、失败原因是什么。\n"
            "\n"
            "如果要做的事正好是常见的那几件，别自己写脚本 —— 直接用 preset 参数，"
            "那些流程的材料账已经算过了（几步挖几个、要不要工作台）：\n"
        ) + _describe_presets() + (
            "\n"
            '用预设的写法：mc_script(preset="wooden_pickaxe")，'
            '要带参数的预设再加 preset_params，例如 mc_script(preset="mine_until", '
            'preset_params={"item": "cobblestone", "count": 64})。\n'
            "script 和 preset 二选一，不要同时给。"
        ),
        parameters=[
            ToolParameterInfo(
                name="script",
                param_type=ToolParamType.OBJECT,
                description='脚本对象，形如 {"name": "做把石剑", "steps": [{"action": "mine_blocks", '
                            '"params": {"block": "stone", "count": 3}}]}。与 preset 二选一。',
                required=False,
                additional_properties=True,
            ),
            ToolParameterInfo(
                name="preset",
                param_type=ToolParamType.STRING,
                description="用现成的预设代替自己写脚本，例如 wooden_pickaxe、stone_pickaxe、"
                            "crafting_table、torch、furnace、chest、mine_until、mine_then_craft。"
                            "与 script 二选一。",
                required=False,
                default="",
            ),
            ToolParameterInfo(
                name="preset_params",
                param_type=ToolParamType.OBJECT,
                description='预设的参数，例如 {"item": "cobblestone", "count": 64}。'
                            "只有 mine_until / mine_then_craft 这类预设需要。",
                required=False,
                additional_properties=True,
            ),
            ToolParameterInfo(name="player", param_type=ToolParamType.STRING,
                              description="指定游戏客户端（按玩家名）。只有一个客户端时可省略。",
                              required=False, default=""),
        ],
    )
    async def mc_script(self, script: Any = None, preset: str = "",
                        preset_params: Any = None, player: str = "", **kwargs: Any):
        if str(preset).strip():
            if script is not None:
                return {"success": False,
                        "content": "script 和 preset 只能给一个：要么自己写脚本，要么用现成的预设。"}
            built, error = _build_preset(str(preset).strip(), preset_params)
            if error:
                return {"success": False, "content": error}
            script = built

        parsed, error = _parse_script_arg(script)
        if error:
            return {"success": False, "content": error}

        shape_error = _validate_script_shape(parsed)
        if shape_error:
            return {"success": False, "content": shape_error}

        # 提前检查动作名：拼错当场纠正，被禁用当场拒绝，不用等游戏里跑到那一步
        used: set[str] = set()
        _collect_script_actions(parsed, used)

        unknown = sorted(a for a in used if a not in P.ALL_ACTIONS)
        if unknown:
            hints = []
            for name in unknown[:3]:
                close = difflib.get_close_matches(name, P.ALL_ACTIONS, n=1, cutoff=0.6)
                hints.append(f"{name}（是不是想写 {close[0]}？）" if close else name)
            return {"success": False, "content": "脚本里出现了不认识的动作名：" + "、".join(hints)
                    + "。可用动作：" + "、".join(P.ALL_ACTIONS)}

        blocked = sorted(a for a in used if not self._action_allowed(a))
        if blocked:
            return {"success": False, "content": "脚本里用到了被插件配置禁用的动作：" + "、".join(blocked)
                    + "。请去掉这些步骤，或者去「安全与限流」里放开。"}

        # script 这个壳子本身不受白名单约束（上面已经逐个检查过脚本里的动作了），
        # 但显式拉黑 script 的人应该被拦下
        if not self._action_allowed(P.A_SCRIPT, skip_allowlist=True):
            return {"success": False, "content": "任务组（script）已被插件配置禁用"
                    "（见「安全与限流」的动作黑名单）。"}

        # 不允许嵌套：内层脚本会按「外层的一个步骤」计时，超时预算算不清
        if P.A_SCRIPT in used:
            return {"success": False, "content": "脚本里不能再套一层 script ——"
                    "把内层的步骤直接展开到外层就行。"}

        # 脚本的长短由它自己的 maxDurationMs 决定，插件侧的超时必须不小于它
        timeout = float(self.config.safety.max_action_timeout_seconds)
        budget_ms = parsed.get("maxDurationMs")
        if isinstance(budget_ms, (int, float)) and budget_ms > 0:
            timeout = max(timeout, float(budget_ms) / 1000.0 + 30.0)

        logger.info("下发任务组 %s：用到动作 %s",
                    _summarize_script(parsed), sorted(used) or "（无）")

        outcome = await self._call(P.A_SCRIPT, parsed, player=player, timeout=timeout,
                                   skip_allowlist=True, hard_cap=False)
        if not outcome.get("success"):
            return outcome

        result = outcome.get("result") or {}
        # 模组已经把「做到哪一步、哪一步失败、失败原因」渲染成给人看的文本了，直接用
        rendered = result.get("content")
        if not isinstance(rendered, str) or not rendered.strip():
            rendered = f"脚本执行完成，共执行 {result.get('stepsDone', 0)} 个动作。"
        outcome["content"] = rendered
        outcome["steps_done"] = result.get("stepsDone")
        return outcome

    @Tool(
        "mc_list_clients",
        brief_description="列出当前所有已连接到麦麦的 Minecraft 客户端",
        detailed_description="查看有几台游戏客户端在线、各自是谁、在哪个世界、模组版本是多少。",
        parameters=[],
    )
    async def mc_list_clients(self, **kwargs: Any):
        if self.bridge is None:
            reason = self._load_error or "WebSocket 服务端未启动（请检查端口是否被占用）"
            return {"success": False,
                    "content": f"Minecraft 桥接未就绪：{reason}", "clients": []}
        clients = self.bridge.all_describe()
        if not clients:
            return {"success": True, "content": "当前没有 Minecraft 客户端连接。请确认游戏已启动、"
                                                "模组已加载，并且 serverUrl 指向本插件的 WebSocket 地址。",
                    "clients": []}
        lines = [
            f"{item.get('player')}@{item.get('server') or '未知'}"
            f"（会话 {item.get('session_id')}，MC {item.get('mc_version')}，模组 {item.get('mod_version')}，"
            f"{'在线' if item.get('connected') else '离线'}）"
            for item in clients
        ]
        return {"success": True, "content": "\n".join(lines), "clients": clients}

    def _select_session_for(self, player: str = "") -> Optional[BridgeSession]:
        if self.bridge is None:
            return None
        try:
            return self.bridge.require(player=player)
        except ActionError:
            return None

    # 常见的食物物品 id（判断「身上有没有吃的」用；不求全，够用就行）
    _FOOD_IDS = frozenset({
        "minecraft:bread", "minecraft:cooked_beef", "minecraft:cooked_porkchop",
        "minecraft:cooked_chicken", "minecraft:cooked_mutton", "minecraft:cooked_rabbit",
        "minecraft:cooked_cod", "minecraft:cooked_salmon", "minecraft:beef",
        "minecraft:porkchop", "minecraft:chicken", "minecraft:mutton", "minecraft:rabbit",
        "minecraft:cod", "minecraft:salmon", "minecraft:apple", "minecraft:golden_apple",
        "minecraft:enchanted_golden_apple", "minecraft:carrot", "minecraft:golden_carrot",
        "minecraft:potato", "minecraft:baked_potato", "minecraft:beetroot",
        "minecraft:beetroot_soup", "minecraft:mushroom_stew", "minecraft:rabbit_stew",
        "minecraft:suspicious_stew", "minecraft:melon_slice", "minecraft:sweet_berries",
        "minecraft:glow_berries", "minecraft:dried_kelp", "minecraft:cookie",
        "minecraft:pumpkin_pie", "minecraft:honey_bottle", "minecraft:tropical_fish",
        "minecraft:pufferfish", "minecraft:rotten_flesh", "minecraft:spider_eye",
        "minecraft:poisonous_potato", "minecraft:chorus_fruit", "minecraft:steak",
        "minecraft:cooked_rabbit", "minecraft:kelp",
    })

    def _render_state(self, state: dict[str, Any]) -> str:
        """把状态快照渲染成给 LLM 看的紧凑文本。"""
        player = state.get("player") or {}
        world = state.get("world") or {}
        look = state.get("look") or {}
        task = state.get("task") or {}
        entities = state.get("entities") or []
        players = state.get("players") or []
        inv = state.get("inventory") or {}
        held = player.get("heldItem") or {}

        lines = [self._summarize_state(state)]

        if task:
            lines.append(f"正在执行：{task.get('action')}（{task.get('id')}），"
                         f"已进行 {int((task.get('elapsedMs') or 0) / 1000)} 秒"
                         + (f"，进度 {task.get('progress')}" if task.get("progress") is not None else "")
                         + (f"，{task.get('detail')}" if task.get("detail") else ""))
        else:
            lines.append("当前空闲，没有正在执行的动作。")

        # Baritone 的活不经过模组的动作队列：不单列出来的话，「当前空闲」就是句假话。
        baritone = state.get("baritone") or {}
        if baritone:
            lines.append(f"Baritone：{baritone.get('command')} —— "
                         f"{'进行中' if baritone.get('running') else '已停'}"
                         f"（{int((baritone.get('elapsedMs') or 0) / 1000)} 秒，"
                         f"{'在动' if baritone.get('moving') else '暂时没动'}）")
            if baritone.get("reply"):
                lines.append(f"  Baritone 回话：{baritone['reply']}")

        # AI 托管状态：玩家能不能放开鼠标、失焦会不会暂停
        takeover = state.get("takeover") or {}
        if takeover.get("active"):
            lines.append("AI 托管中：窗口失焦不会暂停，鼠标已放开（玩家可以切出去）。")

        # 装了哪些相关模组 —— 决定了有哪些玩法可用
        mods = state.get("mods") or {}
        installed = [name for name, on in mods.items() if on and name != "forge"]
        if installed:
            lines.append("已装相关模组：" + "、".join(installed))

        # 手上那件东西的模组信息（例如枪的弹药数字）
        held_mod = state.get("heldModInfo") or {}
        if held_mod.get("item"):
            nums = held_mod.get("numbers") or []
            detail = "，".join(f"{n.get('key')}={n.get('value')}" for n in nums[:6])
            lines.append(f"手上（模组物品）：{held_mod.get('name')}"
                         + (f"（{detail}）" if detail else ""))

        # 远程武器状态：手里这把还有几发、要不要先换弹
        ranged = state.get("ranged") or {}
        if ranged.get("kind"):
            ammo_line = (f"手上远程武器：{ranged.get('weaponName')}（{ranged.get('kind')}），"
                         f"{ranged.get('ammoName')} {ranged.get('ammo')}")
            if str(ranged.get("kind")) == "gun" and ranged.get("magazine") is not None:
                ammo_line += f"，弹匣 {ranged.get('magazine')}"
                if ranged.get("reloadNeeded"):
                    ammo_line += "（弹匣空了 —— 托管时会自动换弹，背包里得有同口径子弹）"
            if ranged.get("warning"):
                ammo_line += f" —— {ranged['warning']}"
            lines.append(ammo_line)

        block = look.get("block")
        if block:
            lines.append(f"准星对着：{block.get('name')}（{block.get('id')}）@ "
                         f"{P.block_coord_str(block.get('pos'))}，距离 {block.get('distance')} 格")
        target_entity = look.get("entity")
        if target_entity:
            lines.append(f"准星对着生物：{target_entity.get('name')}（{target_entity.get('type')}）"
                         f"距离 {target_entity.get('distance')} 格，血量 {target_entity.get('health')}")

        if entities:
            hostile = [e for e in entities if str(e.get("category")) == "HOSTILE"]
            if hostile:
                nearest = hostile[0]
                lines.append(f"⚠ 附近有 {len(hostile)} 个敌对生物，最近的是 {nearest.get('name')}"
                             f"（距离 {nearest.get('distance')} 格，血量 {nearest.get('health')}）")
            else:
                lines.append(f"附近有 {len(entities)} 个生物，没有敌对生物。")

        others = [p for p in players if not p.get("self")]
        if others:
            lines.append("附近玩家：" + "、".join(
                f"{p.get('name')}（距离 {p.get('distance')} 格）" for p in others))

        slots = inv.get("slots") or []
        if slots:
            summary = "、".join(f"{((s.get('item') or {}).get('name'))}×{((s.get('item') or {}).get('count'))}"
                                for s in slots[:12])
            lines.append(f"背包：{summary}" + ("…" if len(slots) > 12 else ""))
            # 有没有吃的？没有的话直接给出**获取办法** —— 饿着肚子干活是最容易翻车的一种状态，
            # 与其等 AI 自己想，不如每次状态里就把它该去哪儿弄吃的说清楚。
            food_hits = [s for s in slots
                         if str((s.get("item") or {}).get("id", "")) in self._FOOD_IDS]
            if food_hits:
                names = "、".join(str((s.get("item") or {}).get("name")) for s in food_hits[:4])
                lines.append(f"身上有吃的：{names}")
            else:
                lines.append("⚠ 背包里没有一点吃的 —— 按顺序试下面三条（都是现成工具能直接做的）：\n"
                             "  ① **干草块→面包**（最快，村庄/平原常见）：\n"
                             "     mc_scan_blocks block=hay_block radius=32 → mc_mine_blocks block=hay_block count=2\n"
                             "     → mc_craft item=wheat（1 个干草块 = 9 小麦）→ mc_craft item=bread count=6\n"
                             "     （3 小麦 = 1 面包，6 个面包够吃很久）\n"
                             "  ② **翻宝箱**（村庄房屋/地牢/废弃矿井里常有面包、熟肉、苹果）：\n"
                             "     mc_scan_blocks block=chest radius=48 → mc_move_to 到箱子旁\n"
                             "     → mc_use_on_block 打开它（读结果里的容器内容，有吃的就拿）\n"
                             "  ③ **打动物**（牛/猪/鸡/羊，会掉生肉）：\n"
                             "     mc_scan_entities type=cow filter=passive radius=32 → mc_attack 杀掉\n"
                             "     → mc_inventory 看掉落 → **mc_smelt 烤熟**（生鸡肉会食物中毒，生牛肉回得少）\n"
                             "  ④ 长期方案：小麦种子 + 锄头种地；或者养几头牛。\n"
                             "  饿着肚子别硬扛：饥饿值低了先去做饭，挖矿/赶路都更慢也更危险。")
        else:
            lines.append("背包是空的。")

        lines.append(f"手持：{held.get('name') or '空手'}；"
                     f"世界时间 {world.get('timeOfDay')}（{'白天' if world.get('isDay') else '夜晚'}）；"
                     f"{'下雨' if world.get('raining') else '天气晴朗'}")
        return "\n".join(lines)

    # ============================================================ 指令（QQ 侧）

    @Command("mc_status", pattern=r"^/mc\s+status$", aliases=["/mc"])
    async def command_status(self, **kwargs: Any):
        """查看 Minecraft 桥接状态。"""
        stream_id = kwargs.get("stream_id", "")
        if self.bridge is None:
            reason = self._load_error or "请到 WebUI 插件配置里检查端口是否被占用。"
            text = f"❌ Minecraft 桥接未就绪：{reason}"
        else:
            clients = self.bridge.all_describe()
            if not clients:
                text = (f"⚠ 服务端正在监听 {self.bridge.url}，但目前没有游戏客户端连接。\n"
                        f"请在游戏的 config/mcai_bridge-client.toml 里把 serverUrl 改成这个地址。")
            else:
                lines = [f"✅ 已连接 {len(clients)} 个客户端（监听 {self.bridge.url}）："]
                for item in clients:
                    state = ""
                    session = self._select_session_for(str(item.get("player") or ""))
                    if session is not None:
                        compact = session.compact_state()
                        pos = ((compact.get("player") or {}).get("pos")) or {}
                        state = (f"，坐标 ({pos.get('x')}, {pos.get('y')}, {pos.get('z')})"
                                 f"，血量 {(compact.get('player') or {}).get('health')}")
                    lines.append(f"  · {item.get('player')}@{item.get('server') or '未知'}{state}")
                text = "\n".join(lines)
        if stream_id:
            await self.ctx.send.text(text, stream_id)
        return True, text, False

    @Command("mc_say", pattern=r"^/mc\s+say\s+(?P<text>.+)$")
    async def command_say(self, **kwargs: Any):
        """让游戏里的角色说一句话。"""
        matched = kwargs.get("matched_groups") or {}
        text = str(matched.get("text") or "").strip()
        stream_id = kwargs.get("stream_id", "")
        if not text:
            return False, "用法：/mc say <要说的内容>", False
        response = await self._call(P.A_CHAT, {"message": text}, timeout=15.0)
        reply = f"✅ 已经在游戏里说：{text}" if response.get("success") else f"❌ 失败：{response.get('content')}"
        if stream_id:
            await self.ctx.send.text(reply, stream_id)
        return bool(response.get("success")), reply, False

    @Command("mc_exec", pattern=r"^/mc\s+exec\s+(?P<cmd>.+)$")
    async def command_exec(self, **kwargs: Any):
        """让游戏里的角色执行一条指令。"""
        matched = kwargs.get("matched_groups") or {}
        cmd = str(matched.get("cmd") or "").strip()
        stream_id = kwargs.get("stream_id", "")
        if not cmd:
            return False, "用法：/mc exec <指令>，例如 /mc exec time set day", False
        response = await self._call(P.A_COMMAND, {"command": cmd}, timeout=15.0)
        reply = f"✅ 已执行指令：{cmd}" if response.get("success") else f"❌ 失败：{response.get('content')}"
        if stream_id:
            await self.ctx.send.text(reply, stream_id)
        return bool(response.get("success")), reply, False

    @Command("mc_stop", pattern=r"^/mc\s+stop$")
    async def command_stop(self, **kwargs: Any):
        """停止游戏角色的一切动作。"""
        stream_id = kwargs.get("stream_id", "")
        response = await self._call(P.A_STOP, {}, timeout=10.0)
        reply = "✅ 已让游戏角色停下。" if response.get("success") else f"❌ 失败：{response.get('content')}"
        if stream_id:
            await self.ctx.send.text(reply, stream_id)
        return bool(response.get("success")), reply, False

    # ============================================================== 首页卡片

    @HomeCard(
        "mc_status_card",
        "Minecraft 桥接",
        [
            {"type": "markdown", "content": "把 Minecraft（Forge 1.20.1 模组）接入麦麦："
                                            "游戏聊天会被麦麦听到，麦麦的动作会真的发生在游戏里。"},
            {"type": "list", "items": [
                "**感知**：聊天、位置、血量、背包、附近生物与方块",
                "**行动**：寻路移动、挖矿、放置、战斗、使用物品、说话、执行指令",
                "**主动**：受伤、濒死、死亡等事件会主动唤醒麦麦",
            ]},
            {"type": "markdown", "content": "详细状态请用 `/mc status`，或让麦麦调用 `mc_state` 工具查看。"},
        ],
        description="Minecraft ↔ MaiBot 双向桥接",
        icon="gamepad-2",
        width="medium",
        order=200,
    )
    async def home_card_mc_status(self) -> None:
        return None

    # ================================================================== API

    @API("get_clients", description="列出已连接的 Minecraft 客户端", version="1", public=True)
    async def api_get_clients(self, **kwargs: Any) -> dict[str, Any]:
        if self.bridge is None:
            return {"success": False, "error": "桥接未启动", "clients": []}
        return {"success": True, "clients": self.bridge.all_describe(), "url": self.bridge.url}

    @API("get_state", description="获取指定 Minecraft 客户端的状态快照", version="1", public=True)
    async def api_get_state(self, player: str = "", **kwargs: Any) -> dict[str, Any]:
        response = await self._call(P.A_GET_STATE, {}, player=player, timeout=15.0)
        if not response.get("success"):
            return {"success": False, "error": response.get("content")}
        return {"success": True, "state": response.get("result") or {}}

    @API("run_action", description="向 Minecraft 客户端下发一个动作", version="1", public=True)
    async def api_run_action(self, action: str = "", params: Optional[dict[str, Any]] = None,
                             player: str = "", **kwargs: Any) -> dict[str, Any]:
        if not action:
            return {"success": False, "error": "缺少 action 参数"}
        return await self._call(action, params or {}, player=player)


def create_plugin() -> MinecraftBridgePlugin:
    """Runner 通过这个工厂函数拿到插件实例。"""
    return MinecraftBridgePlugin()
