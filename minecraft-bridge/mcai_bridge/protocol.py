"""Minecraft 桥接的线协议定义（mod <-> MaiBot 插件）。

链路
----
::

    Minecraft 客户端 (Forge 1.20.1 模组)
        │  ws://<host>:<port>/mc   （模组是客户端，插件是服务端）
        ▼
    MaiBot 插件 (本包)  ──►  ctx.gateway.route_message()  ──►  MaiBot 思考
                       ◄──  LLM 调用 @Tool                    ◄──

报文格式
--------
所有报文都是一个 JSON 对象，统一外壳：

.. code-block:: json

    {"v": 1, "type": "<类型>", "id": "<可选消息ID>", "ts": 1699999999999, "data": {...}}

方向说明：

* ``mod -> plugin``：``hello`` / ``state`` / ``event`` / ``action_result`` / ``pong``
* ``plugin -> mod``：``hello_ack`` / ``action`` / ``ping`` / ``shutdown`` / ``notice``
"""

from __future__ import annotations

import time
import uuid
from typing import Any, Optional

#: 协议版本。模组与插件都会校验，不一致时拒绝连接并提示升级。
PROTOCOL_VERSION = 1

# ---------------------------------------------------------------- 报文类型

#: mod -> plugin
T_HELLO = "hello"
T_STATE = "state"
T_EVENT = "event"
T_ACTION_RESULT = "action_result"
T_PONG = "pong"
T_LOG = "log"

#: plugin -> mod
T_HELLO_ACK = "hello_ack"
T_ACTION = "action"
T_PING = "ping"
T_SHUTDOWN = "shutdown"
T_NOTICE = "notice"

# ---------------------------------------------------------------- 动作类型
#
# 这些字符串必须与 Java 侧 com.mcai.bridge.action.ActionExecutor 支持的类型一致。

A_CHAT = "chat"
A_COMMAND = "command"
A_LOOK = "look"
A_MOVE_TO = "move_to"
A_MOVE_RELATIVE = "move_relative"
A_FOLLOW = "follow"
A_STOP = "stop"
A_JUMP = "jump"
A_SNEAK = "sneak"
A_SPRINT = "sprint"
A_MINE = "mine"
A_MINE_BLOCKS = "mine_blocks"
A_FORAGE = "forage"
A_LOCK_ON = "lock_on"
A_PLACE = "place"
A_USE_ITEM = "use_item"
A_USE_ON_BLOCK = "use_on_block"
A_ATTACK = "attack"
#: 抵御：自己评估威胁（打 / 吃 / 撤），清掉附近敌对生物
A_DEFEND = "defend"
A_EQUIP = "equip"
A_DROP = "drop"
A_GET_STATE = "get_state"
A_SCAN_BLOCKS = "scan_blocks"
A_SCAN_ENTITIES = "scan_entities"
A_SLEEP = "sleep"
A_WAIT = "wait"
A_CANCEL = "cancel"
A_TASK_STATUS = "task_status"
A_JUMP_ON_BLOCK = "jump_on_block"
#: 合成：让模组找配方、摆材料、取成品
A_CRAFT = "craft"
#: 熔炼：在熔炉里烧（原矿→铁锭、沙子→玻璃…）
A_SMELT = "smelt"
#: 桶：装液体 / 倒液体（做黑曜石必需）
A_BUCKET = "bucket"
#: 向下挖阶梯矿道
A_DIG_SHAFT = "dig_shaft"
#: 查询配方（只读，不改动世界）
A_RECIPES = "recipes"
#: 任务组：一次下发一整段脚本，模组本地连续执行（省掉「每步一轮思考」）
A_SCRIPT = "script"
#: AI 托管：麦麦接手后玩家可以放开鼠标切出去，游戏照常跑（关失焦暂停 + 放开鼠标）
A_TAKEOVER = "takeover"
#: 枪械换弹（永恒枪械工艺这类：按它注册的换弹键）
A_RELOAD = "reload"
#: 开枪（枪械的射击是「按住攻击键」，和原版近战不是一回事）
A_SHOOT = "shoot"
#: 枪械工作台（永恒枪械工坊）：列配方 / 报配方 id 制作
A_GUN_SMITH = "gun_smith"
#: 关掉当前打开的界面（右键开出来的箱子/工作台会一直挡着）
A_CLOSE_SCREEN = "close_screen"

ALL_ACTIONS = (
    A_CHAT, A_COMMAND, A_LOOK, A_MOVE_TO, A_MOVE_RELATIVE, A_FOLLOW, A_STOP, A_JUMP,
    A_SNEAK, A_SPRINT, A_MINE, A_MINE_BLOCKS, A_PLACE, A_USE_ITEM, A_USE_ON_BLOCK,
    A_ATTACK, A_EQUIP, A_DROP, A_GET_STATE, A_SCAN_BLOCKS, A_SCAN_ENTITIES, A_SLEEP,
    A_WAIT, A_CANCEL, A_TASK_STATUS, A_JUMP_ON_BLOCK, A_CRAFT, A_RECIPES, A_SCRIPT, A_DEFEND,
    A_SMELT, A_BUCKET, A_DIG_SHAFT, A_TAKEOVER, A_RELOAD, A_SHOOT, A_GUN_SMITH, A_CLOSE_SCREEN,
)

# ---------------------------------------------------------------- 事件类型

E_CHAT = "chat"
E_DAMAGE = "damage"
E_DEATH = "death"
E_RESPAWN = "respawn"
E_JOIN = "join"
E_LEAVE = "leave"
E_KILL = "kill"
E_ADVANCEMENT = "advancement"
E_ITEM_PICKUP = "item_pickup"
E_BLOCK_BREAK = "block_break"
E_BLOCK_PLACE = "block_place"
E_DIMENSION_CHANGE = "dimension_change"
E_LEVEL_UP = "level_up"
E_HEALTH_CRITICAL = "health_critical"
E_TASK_DONE = "task_done"
E_TASK_FAILED = "task_failed"
E_TASK_PROGRESS = "task_progress"
E_CONNECTED = "connected"
E_DISCONNECTED = "disconnected"
E_LOG = "log"


def envelope(msg_type: str, data: Optional[dict[str, Any]] = None, msg_id: Optional[str] = None) -> dict[str, Any]:
    """构造一个标准报文外壳。"""
    return {
        "v": PROTOCOL_VERSION,
        "type": msg_type,
        "id": msg_id or uuid.uuid4().hex[:12],
        "ts": int(time.time() * 1000),
        "data": data or {},
    }


def new_action_id(prefix: str = "act") -> str:
    return f"{prefix}-{uuid.uuid4().hex[:10]}"


def action_message(action_id: str, action_type: str, params: dict[str, Any], timeout_ms: int) -> dict[str, Any]:
    """构造一条 ``action`` 报文。"""
    return envelope(
        T_ACTION,
        {
            "actionId": action_id,
            "action": action_type,
            "params": params or {},
            "timeoutMs": int(timeout_ms),
        },
        msg_id=action_id,
    )


def hello_ack(session_id: str, *, ok: bool = True, error: str = "",
              heartbeat_seconds: int = 15, max_actions_per_second: int = 20,
              protocol: int = PROTOCOL_VERSION) -> dict[str, Any]:
    """构造 ``hello_ack`` 报文。"""
    return envelope(
        T_HELLO_ACK,
        {
            "ok": ok,
            "error": error,
            "sessionId": session_id,
            "protocol": protocol,
            "heartbeatSeconds": heartbeat_seconds,
            "maxActionsPerSecond": max_actions_per_second,
        },
    )


def notice(level: str, message: str) -> dict[str, Any]:
    """向模组推送一条人类可读的通知（模组会打到日志里）。"""
    return envelope(T_NOTICE, {"level": level, "message": message})


def shutdown(reason: str = "") -> dict[str, Any]:
    return envelope(T_SHUTDOWN, {"reason": reason})


# ---------------------------------------------------------------- 取值辅助


def get_data(message: dict[str, Any]) -> dict[str, Any]:
    """取出报文的数据段，兼容没有 ``data`` 字段的裸报文。"""
    data = message.get("data")
    return data if isinstance(data, dict) else {}


def msg_type(message: dict[str, Any]) -> str:
    value = message.get("type")
    return value if isinstance(value, str) else ""


def coord_str(pos: Any) -> str:
    """把 ``{"x":1,"y":64,"z":-3}`` 格式化成人类可读坐标。"""
    if not isinstance(pos, dict):
        return "?"
    try:
        return f"({float(pos.get('x', 0)):.1f}, {float(pos.get('y', 0)):.1f}, {float(pos.get('z', 0)):.1f})"
    except (TypeError, ValueError):
        return "?"


def block_coord_str(pos: Any) -> str:
    if not isinstance(pos, dict):
        return "?"
    try:
        return f"({int(pos.get('x', 0))}, {int(pos.get('y', 0))}, {int(pos.get('z', 0))})"
    except (TypeError, ValueError):
        return "?"
