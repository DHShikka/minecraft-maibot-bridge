# WebSocket 线协议规范

模组（客户端）与 MaiBot 插件（服务端）之间的通信协议。

- **传输**：WebSocket，文本帧，UTF-8 JSON
- **角色**：插件开服务端，模组连过去（`ws://<host>:<port>/mc`）
- **版本**：`v = 1`，两边都会校验，不一致直接拒绝连接
- **路径**：默认 `/mc`，两边配置必须一致

---

## 1. 报文外壳

所有报文都是同一个外壳：

```json
{
  "v": 1,
  "type": "<报文类型>",
  "id": "<可选的消息 ID>",
  "ts": 1699999999999,
  "data": { }
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `v` | int | 协议版本，当前固定 `1` |
| `type` | string | 报文类型，见下表 |
| `id` | string | 可选，消息 ID。`action` / `action_result` 用它配对 |
| `ts` | long | 毫秒时间戳 |
| `data` | object | 载荷。没有载荷时传 `{}`，不要省略 |

### 报文类型总表

| type | 方向 | 作用 |
|---|---|---|
| `hello` | 模组 → 插件 | 握手，带令牌与客户端信息 |
| `hello_ack` | 插件 → 模组 | 握手结果 |
| `state` | 模组 → 插件 | 全量状态快照 |
| `event` | 模组 → 插件 | 游戏事件（聊天、受伤、死亡…） |
| `action` | 插件 → 模组 | 下发一个动作 |
| `action_result` | 模组 → 插件 | 动作执行结果 |
| `ping` | 双向 | 保活（WebSocket 层，`data` 可空） |
| `pong` | 双向 | ping 的应答 |
| `notice` | 插件 → 模组 | 人类可读通知，模组只打日志 |
| `shutdown` | 插件 → 模组 | 要求模组主动断开 |
| `log` | 模组 → 插件 | 模组调试日志 |

---

## 2. 连接生命周期

```
模组                                          插件
 │  ── WebSocket 握手（HTTP Upgrade） ──────►  │  校验路径
 │  ◄──────────── 101 Switching Protocols ──  │
 │                                            │
 │  ── hello {token, protocol, mod, player} ► │  校验令牌与协议版本
 │  ◄──────── hello_ack {ok, sessionId} ───── │
 │                                            │
 │  ◄──────── action {get_state} ──────────── │  插件主动拉一次状态，
 │  ── action_result ───────────────────────► │  让麦麦一开始就知道自己在哪
 │                                            │
 │  ── state / event（持续） ───────────────► │
 │  ◄──────── action（麦麦的决定） ────────── │
 │  ── action_result ───────────────────────► │
 │                                            │
 │  ◄──────── ping ────────────────────────── │  默认 15 秒一次
 │  ── pong ────────────────────────────────► │
```

- 插件在 `hello` 校验失败时会回一条 `ok=false` 的 `hello_ack`，然后以关闭码 **1008** 关闭连接。
- 插件超过 `idle_timeout_seconds`（默认 90 秒）收不到任何数据，会判定假死并断开。
- 模组侧断线后按指数退避重连，间隔上限是 `reconnectDelaySeconds × 6`。

---

## 3. 模组 → 插件

### 3.1 `hello`

```json
{
  "v": 1, "type": "hello",
  "data": {
    "token": "共享密钥，插件没配就是空串",
    "protocol": 1,
    "mod": { "version": "1.0.0", "mc": "1.20.1", "forge": "47.4.10", "name": "mcai_bridge" },
    "player": {
      "name": "Steve",
      "uuid": "00000000-0000-0000-0000-000000000001",
      "dimension": "minecraft:overworld"
    },
    "singleplayer": true,
    "serverName": "我的世界",
    "serverAddress": "127.0.0.1:25565",
    "worldKey": "sp:我的世界",
    "capabilities": ["chat", "command", "move", "pathfind", "mine", "place",
                     "attack", "use", "inventory", "scan", "sleep", "follow"]
  }
}
```

| 字段 | 说明 |
|---|---|
| `token` | 与插件 `auth_token` 比对，不一致直接拒绝 |
| `protocol` | 协议版本，与外壳的 `v` 一起校验 |
| `mod` | 模组/MC/Forge 版本，只用于展示与排错 |
| `player` | 当前玩家。还没进世界时可能只有 `name`/`uuid` 为空 |
| `singleplayer` | 是否单机世界 |
| `serverName` / `serverAddress` | 服务器显示名与地址，用来生成「世界标识」，决定游戏聊天进入哪个聊天流 |
| `worldKey` | 世界唯一键，形如 `sp:<存档名>` / `mp:<地址>` |
| `capabilities` | 模组声明支持的能力，插件只用于展示 |

### 3.2 `hello_ack`（插件 → 模组）

```json
{
  "v": 1, "type": "hello_ack",
  "data": {
    "ok": true,
    "error": "",
    "sessionId": "mc-1-1789141129",
    "protocol": 1,
    "heartbeatSeconds": 15,
    "maxActionsPerSecond": 20
  }
}
```

`ok=false` 时 `error` 是人类可读原因（令牌不对、协议版本不匹配等）。

### 3.3 `state`

全量状态快照。触发原因放在 `reason`：`join`（刚进世界）、`interval`（定时，默认 40 tick）、`request`（被 `get_state` 动作要求）、`dimension_change`。

```json
{
  "v": 1, "type": "state",
  "data": {
    "reason": "interval",
    "at": 1699999999999,
    "inWorld": true,

    "player": {
      "name": "Steve", "uuid": "...", "dimension": "minecraft:overworld",
      "pos": {"x": 10.5, "y": 63.0, "z": -4.25},
      "blockPos": {"x": 10, "y": 63, "z": -5},
      "yaw": 90.0, "pitch": 12.5,
      "health": 18.0, "maxHealth": 20.0, "absorption": 0.0,
      "food": 17, "saturation": 5.0,
      "air": 300, "maxAir": 300,
      "xpLevel": 12, "xpProgress": 0.4, "score": 0, "armor": 7,
      "gameMode": "survival",
      "flying": false, "mayFly": false,
      "onGround": true, "inWater": false, "inLava": false, "onFire": false,
      "sprinting": false, "sneaking": false, "swimming": false,
      "fallFlying": false, "usingItem": false, "dead": false,
      "passenger": false, "creative": false, "sleeping": false,
      "selectedSlot": 0,
      "heldItem": {"id": "minecraft:iron_pickaxe", "name": "铁镐", "count": 1,
                   "damage": 10, "maxDamage": 250, "durabilityLeft": 240},
      "offhandItem": null,
      "team": null,
      "effects": [{"id": "minecraft:speed", "name": "速度", "amplifier": 1, "durationTicks": 120}],
      "blockBelow": "minecraft:grass_block",
      "blockAtFeet": "minecraft:air"
    },

    "world": {
      "timeOfDay": 6000, "day": 3, "isDay": true, "isNight": false,
      "gameTime": 72000,
      "raining": false, "thundering": false, "rainLevel": 0.0,
      "difficulty": "normal", "dimension": "minecraft:overworld",
      "biome": "minecraft:plains", "lightLevel": 15, "canSeeSky": true,
      "minY": -64, "maxY": 320
    },

    "look": {
      "block": {"id": "minecraft:grass_block", "name": "草方块",
                "pos": {"x": 10, "y": 62, "z": -6}, "face": "up", "distance": 3.2},
      "entity": null
    },

    "entities": [
      {"uuid": "...", "type": "minecraft:zombie", "name": "僵尸",
       "category": "HOSTILE", "pos": {...}, "blockPos": {...},
       "distance": 5.2, "health": 20.0, "maxHealth": 20.0,
       "alive": true, "onFire": false, "passenger": false, "player": false}
    ],
    "players": [
      {"name": "Alex", "uuid": "...", "pos": {...}, "distance": 12.3,
       "health": 20.0, "maxHealth": 20.0, "creative": false, "sneaking": false, "self": false}
    ],

    "inventory": {
      "slots": [
        {"slot": 0, "hotbar": true, "selected": true,
         "item": {"id": "minecraft:iron_pickaxe", "name": "铁镐", "count": 1,
                  "damage": 10, "maxDamage": 250, "durabilityLeft": 240}}
      ],
      "armor": [{"id": "minecraft:iron_helmet", "name": "铁头盔", "count": 1, "damage": 3, "maxDamage": 165}],
      "usedSlots": 14, "freeSlots": 22, "selectedSlot": 0, "selected": {...}
    },

    "scoreboard": {"score": 0, "xpLevel": 12, "team": null},

    "task": {
      "id": "act-7", "action": "move_to", "elapsedMs": 1200,
      "timeoutMs": 120000, "movement": true, "progress": 0.42,
      "detail": "距离目标还有 12.3 格（当前路径 5 个路点）"
    },
    "queuedTasks": [{"id": "act-8", "action": "mine", "movement": true}],
    "queueLength": 1,

    "bridge": {
      "connected": true, "sentMessages": 120, "receivedActions": 8,
      "modVersion": "1.0.0", "serverName": "我的世界", "worldKey": "sp:我的世界"
    }
  }
}
```

**字段容错**：任何单个字段采集失败都会退化成 `null`，不会导致整条报文发不出去。

### 3.4 `event`

`kind` 决定其它字段。

| `kind` | 附加字段 |
|---|---|
| `chat` | `channel`（`chat`/`system`/`actionbar`）、`text`、`raw`、`player`（bool）、`sender`、`isSystem`、`chatTypeName` |
| `damage` | `amount`、`healthBefore`、`healthAfter`、`source`、`sourceName` |
| `health_critical` | `health`、`maxHealth` |
| `death` | `killer`、`killerType`、`damageSource` |
| `respawn` | `note` |
| `kill` | `entity`、`name` |
| `level_up` | `level` |
| `hungry` | `food` |
| `dimension_change` | `from`、`to` |
| `item_gain` / `item_lost` | `item`、`count`（需开启 `reportItemEvents`） |
| `block_break` / `block_place` | `block`、`pos`（需开启 `reportBlockEvents`） |
| `task_progress` | `actionId`、`action`、`progress`、`detail` |

绝大多数事件还带 `pos`、`dimension`、`playerName`。

示例：

```json
{"v":1,"type":"event","data":{
  "kind":"chat","channel":"chat","text":"麦麦，去帮我挖点铁","player":true,
  "sender":"Alex","isSystem":false,
  "pos":{"x":10.5,"y":63.0,"z":-4.25},"dimension":"minecraft:overworld","playerName":"Steve"
}}
```

```json
{"v":1,"type":"event","data":{
  "kind":"damage","amount":3.0,"healthBefore":20.0,"healthAfter":17.0,
  "source":"minecraft:zombie","sourceName":"僵尸",
  "pos":{...},"dimension":"minecraft:overworld","playerName":"Steve"
}}
```

### 3.5 `action_result`

```json
{
  "v": 1, "type": "action_result",
  "id": "act-7",
  "data": {
    "actionId": "act-7",
    "action": "move_to",
    "ok": true,
    "elapsedMs": 1200,
    "result": {"pos": {"x": 100.0, "y": 64.0, "z": -200.0}, "pathLength": 34},
    "error": null,
    "state": {"pos": {...}, "blockPos": {...}, "health": 18.0, "dimension": "minecraft:overworld"}
  }
}
```

`ok=false` 时 `error` 是一句**给 LLM 看的中文说明**，会指出失败原因和建议做法（例如「连续 80 tick 无法前进，目标可能被完全封死…建议先挖开挡路的方块」）。

---

## 4. 插件 → 模组

### 4.1 `action`

```json
{
  "v": 1, "type": "action",
  "id": "act-7",
  "data": {
    "actionId": "act-7",
    "action": "move_to",
    "params": {"x": 100, "y": 64, "z": -200, "range": 1},
    "timeoutMs": 120000
  }
}
```

模组侧的处理流程：

1. 校验是否被模组配置允许（`permissions.allow*` 开关、指令黑名单）；
2. 全局限速检查（`maxActionsPerSecond`）；
3. **即时动作**当 tick 执行完就回传结果；
4. **长动作**进队列，每 tick 推进一步；
5. 新来的**移动类**动作会顶掉正在执行的移动动作（被顶掉的会收到「已被更新的移动指令取代」的结果）。

`params` 详见 [`ACTIONS.md`](ACTIONS.md)。

### 4.2 其它

```json
{"v":1,"type":"ping","data":{}}
{"v":1,"type":"pong","data":{}}
{"v":1,"type":"notice","data":{"level":"info","message":"..."}}
{"v":1,"type":"shutdown","data":{"reason":"..."}}
```

---

## 5. 动作类型总表

| 动作 | 是否长任务 | 是否占用移动控制 | 需要的模组权限 |
|---|---|---|---|
| `chat` | 否 | 否 | `allowChat` |
| `command` | 否 | 否 | `allowCommand` + 指令黑名单 |
| `get_state` | 否 | 否 | — |
| `task_status` | 否 | 否 | — |
| `scan_blocks` | 否 | 否 | — |
| `scan_entities` | 否 | 否 | — |
| `look` | 否 | 否 | `allowLook` |
| `equip` | 否 | 否 | `allowInventory` |
| `drop` | 否 | 否 | `allowInventory` |
| `use_item` | 否 / 长按 | 否 | `allowUse` |
| `stop` | 否 | 否 | — |
| `cancel` | 否 | 否 | — |
| `move_to` | 是 | 是 | `allowMovement` |
| `move_relative` | 是 | 是 | `allowMovement` |
| `follow` | 是 | 是 | `allowMovement` |
| `jump` | 是 | 是 | `allowMovement` |
| `sneak` / `sprint` | 是 | 是 | `allowMovement` |
| `mine` | 是 | 是 | `allowBreakBlocks` |
| `mine_blocks` | 是 | 是 | `allowBreakBlocks` |
| `place` | 是 | 是 | `allowPlaceBlocks` |
| `use_on_block` | 是 | 是 | `allowUse` |
| `jump_on_block` | 是 | 是 | `allowMovement` |
| `attack` | 是 | 是 | `allowAttack` |
| `defend` | 是 | 是 | `allowAttack`（子动作另有各自开关） |
| `sleep` | 是 | 是 | `allowUse` |
| `craft` | 是 | 是 | `allowInventory` |
| `recipes` | 否 | 否 | — |
| `script` | 是 | 是 | 脚本里每个动作各自的开关 |
| `wait` | 是 | 否 | — |

`script` 是**任务组**：`params` 本身就是一个脚本对象
（`{"name": ..., "steps": [...]}`），模组在本地连续执行整段流程。
它自己不查权限开关，而是**把脚本里每个动作按单独下发时的同一套规则各查一遍** ——
所以不存在「包进脚本就能绕过禁用」的口子。
长任务的默认超时给到 10 分钟（其它动作用 `[limits] actionTimeoutSeconds`），
插件侧还会按脚本自己的 `maxDurationMs` 再放宽。细节见 [SCRIPT.md](SCRIPT.md)。

---

## 6. 安全设计

1. **令牌**：插件配了 `auth_token` 时，`hello.token` 必须一致，否则 1008 断开。
2. **协议版本**：外壳 `v` 与 `hello.protocol` 都要匹配，避免新旧版本用错语义。
3. **掩码校验**：服务端要求客户端帧必须带掩码（RFC 6455 §5.1），否则按协议错误关闭。
4. **消息大小上限**：单条消息 4 MiB，超限断开。
5. **限速**：两侧各有一层每秒动作数限制。脚本内部的子动作走**同一个**闸门 ——
   「包进脚本」不能用来刷动作；区别只是脚本被限速时挂起等下一个时间窗，而不是失败。
6. **权限开关**：动作能不能做，最终由**模组配置**决定 —— 也就是玩家自己电脑上的那个文件，插件改不了。
7. **失败可见**：所有失败都带原因回传，不会「假装成功」。

---

## 7. 兼容性

- 协议版本变更时递增 `v`，两边都会拒绝不匹配的连接并给出明确提示。
- `state` / `event` 的字段是**只增不减**的：插件要容忍缺失字段（模组配置关掉的上报就是不发）。
- 新增动作类型不需要改协议版本，插件侧调用未知动作会收到「不支持的动作类型」的明确错误。
