# 任务组（脚本）—— 让 AI 一次说完一整段流程

## 为什么要这个

原来的流程是「AI 想一步 → 下发一个动作 → 等结果 → AI 再想下一步」。

真正慢的不是网络往返（毫秒级），而是**每一步都要 AI 重新思考一轮**（秒级）。
做一把石剑要 9 步，就是 9 轮思考加 9 次往返；中途 AI 还容易忘了自己本来在干什么。

任务组把事情反过来：**AI 一次把整段流程写清楚，模组在游戏里自己连着做完，
只在结束时回传一次聚合结果**。9 轮思考变成 1 轮。

```
以前：  AI ─动1→ 游戏 ─结果→ AI ─动2→ 游戏 ─结果→ AI ... （N 轮思考）
现在：  AI ── 整段脚本 ──→ 游戏本地连续执行 ──── 汇总结果 ──→ AI （1 轮思考）
```

## 怎么用

插件侧是一个工具：`mc_script`。

```json
{
  "name": "做把石剑",
  "steps": [
    {"action": "scan_blocks", "params": {"block": "stone", "radius": 32, "limit": 1}},
    {"action": "mine_blocks", "params": {"block": "stone", "count": 3}},
    {"action": "craft", "params": {"item": "stick", "count": 1}},
    {"action": "craft", "params": {"item": "stone_sword", "count": 1}}
  ]
}
```

线协议上它就是一条普通的 `action` 报文，动作名是 `script`，
`params` 就是脚本本身：

```json
{
  "v": 1, "type": "action", "id": "act-9f3a",
  "data": {
    "actionId": "act-9f3a",
    "action": "script",
    "params": {"name": "做把石剑", "steps": [ ... ]},
    "timeoutMs": 600000
  }
}
```

## 脚本结构

顶层对象：

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `steps` | 是 | 步骤数组，不能为空 |
| `name` | 否 | 人类可读的名字，会出现在日志和回执里 |
| `maxSteps` | 否 | 这个脚本最多执行多少个动作，默认 200，上限 2000 |
| `maxDurationMs` | 否 | 最长执行时间，默认 10 分钟，最小 1000 |

`steps` 里每个元素是下面五种之一。

### 1. 一个动作

```json
{"action": "mine_blocks", "params": {"block": "stone", "count": 3}}
{"action": "place", "params": {"x": "~", "y": "~-1", "z": "~", "item": "crafting_table"}, "optional": true}
```

`action` 就是其它 `mc_*` 工具对应的动作名，`params` 和那些工具的参数完全一样。
加了 `"optional": true` 表示**这一步失败也继续往下做**（适合「顺手做一下，做不成算了」）。

#### 坐标：用 `~` 相对坐标

脚本是**提前写好的**，写的时候不可能知道玩家会站在哪。所以需要坐标的地方
（`place`、`use_on_block`）支持 Minecraft 自己的 `~` 写法：

| 写法 | 含义 |
| --- | --- |
| `"~"` | 自己脚下那一格 |
| `"~-1"` | 往下一格（放在脚边最常用的就是 `y: "~-1"`） |
| `"~2"` / `"~+2"` | 往上两格 |
| `10` 或 `"10"` | 绝对坐标，原样使用 |

三个分量可以混着写，比如 `{"x": 5, "y": "~-1", "z": "~3"}`。
模组在动作**开始执行时**才解析，那时它已经知道玩家在哪了。

所以「把工作台放在脚边」在脚本里就是：

```json
{"action": "place", "params": {"x": "~", "y": "~-1", "z": "~", "item": "crafting_table"}}
```

### 2. 重复固定次数

```json
{"repeat": 3, "steps": [{"action": "use", "params": {"hand": "main"}}]}
```

`repeat` 上限 200。

### 3. 条件循环

```json
{"while": {"condition": {"has": {"item": "cobblestone", "count": 1}}, "maxIterations": 20},
 "steps": [{"action": "mine_blocks", "params": {"block": "stone", "count": 1}}]}
```

`maxIterations` 默认 20，上限 200 —— 这是死循环的兜底：条件一直成立也会在跑满之后停下。

### 4. 条件分支

```json
{"if": {"condition": {"healthBelow": 10}},
 "then": [{"action": "use", "params": {"hand": "main"}}],
 "else": [{"action": "attack", "params": {"target": "nearest_hostile"}}]}
```

`else` 可以省略（省略时条件不成立就直接跳过）。

### 5. 等条件成立

```json
{"waitUntil": {"condition": {"has": {"item": "iron_ingot", "count": 3}}, "timeoutMs": 60000}}
```

`timeoutMs` 默认 60000，最小 500。等不到就报「等待超时」并中止脚本。

## 预设：不用自己写脚本

常见的那几件事，插件已经写好了 —— 也就是 `mc_script` 的 `preset` 参数。
预设解决的是一次性浪费：让模型每次现编「做木镐要几步」，它就可能漏掉工作台、
或者把木板数量算错；而这类错误在游戏里表现为「craft 失败：材料不够」，又要多一轮。

```json
mc_script(preset="wooden_pickaxe")
mc_script(preset="mine_until", preset_params={"item": "cobblestone", "count": 64})
```

| 预设 | 做什么 | 参数 |
| --- | --- | --- |
| `crafting_table` | 一根原木 → 工作台，并放到脚边 | — |
| `wooden_pickaxe` | 从原木到木镐（含工作台） | — |
| `stone_pickaxe` | 从原木到石镐（含工作台） | — |
| `stone_sword` | 做一把石剑（含工作台） | — |
| `torch` | 挖煤做 4 个火把（不需要工作台） | — |
| `furnace` | 挖石头做熔炉（含工作台） | — |
| `chest` | 做箱子（含工作台） | — |
| `mine_until` | 一直挖到背包里有够数为止（条件循环） | `item`、`block`、`count` |
| `mine_then_craft` | 挖够材料再合成（挖矿 + 条件循环 + 合成） | `item`、`block`、`count`、`craft`、`craft_count` |
| `defend_area` | 守在这里：有敌对生物就清掉，清干净就收工 | `radius`、`max_rounds` |

> `mine_then_craft` 解决的是「差一点材料」这一类来回：先 `mine_until` 攒够，再 `craft`。
> `defend_area` 用脚本层的 `while` + `attack` 表达「守着」，好处是**每一步都看得见**；
> 想要「会自保的抵御」（残血先吃、被围就撤、挑目标优先级）直接用 `mc_defend`。

**脚本里能用的动作**就是协议动作总表里的那些（见 [PROTOCOL.md](PROTOCOL.md) 第 5 节），
包括 `smelt`（烧矿）、`bucket`（装/倒液体）、`dig_shaft`（往下挖阶梯矿道）——
这三个没有单独的工具，但写进脚本一样跑。例如：

```json
{"name": "备一桶岩浆和两桶水",
 "steps": [
   {"action": "dig_shaft", "params": {"depth": 24}},
   {"action": "mine_blocks", "params": {"block": "iron_ore", "count": 3}},
   {"action": "smelt", "params": {"item": "iron_ingot", "count": 3}},
   {"action": "craft", "params": {"item": "bucket", "count": 3}},
   {"action": "bucket", "params": {"mode": "fill", "fluid": "lava"}}
 ]}
```

写错预设名会给出候选（`nope_pickaxe` → 「是不是想用 `stone_pickaxe`？」），
写错参数名会列出这个预设支持哪些参数。预设展开成的是**普通脚本** ——
所以上面所有的执行语义、权限检查、安全阀，对预设一样生效。

材料账都算过了，注释就在 `plugin.py` 的 `SCRIPT_PRESETS` 里，例如木镐：

```
3 原木 → 12 木板；木棍 2 木板 → 4 根；工作台 4 木板；木镐 3 木板 + 2 木棍
合计 2+4+3 = 9 ≤ 12 木板，2 ≤ 4 木棍
```

`mine_until` / `mine_then_craft` 是参数化的，展开成条件循环而不是写死次数：

```json
{"while": {"condition": {"not": {"has": {"item": "cobblestone", "count": 64}}},
           "maxIterations": 72},
 "steps": [{"action": "mine_blocks", "params": {"block": "stone", "count": 1}}]}
```

## 条件

条件永远是**只有一个键**的 JSON 对象。要表达「并且」请用 `all`，
写两个键会直接报错（这是故意的：多键的语义容易让人误解）。

| 条件 | 含义 |
| --- | --- |
| `{"has": {"item": "oak_log", "count": 3}}` | 背包里有 3 个（简写 `{"has": "oak_log"}` = 至少 1 个） |
| `{"holding": "stone_pickaxe"}` | 主手拿着什么 |
| `{"healthBelow": 10}` | 血量低于 |
| `{"foodBelow": 6}` | 饥饿值低于 |
| `{"atPos": {"x": 1, "y": 65, "z": 0, "radius": 2}}` | 在某个坐标附近 |
| `{"nearby": {"type": "zombie", "radius": 8, "min": 1}}` | 附近有实体（`type` 也支持 `hostile` / `animal` / `player`） |
| `{"busy": false}` | 当前没有别的动作在跑 |
| `{"all": [ ... ]}` / `{"any": [ ... ]}` / `{"not": { ... }}` | 逻辑组合 |
| `{"always": true}` | 恒真（占位用） |

## 能做什么、做不到什么

**能做**：
- 把一串已经想清楚的动作连着做完（挖 3 个石头 → 做木棍 → 做石剑）
- 带条件的重复劳动（一直挖到背包里有 64 个圆石）
- 应急反应（血低了先吃东西，附近有僵尸先打）
- 等某个东西出现（等工作台造好、等掉落的物品进背包）

**做不到**：
- 不能感知脚本执行过程中新出现的信息。脚本一旦下发，路线就定死了。
  中途环境变了（比如挖到一半被苦力怕炸了），脚本只会在下一步失败时报错停下 ——
  它会告诉你做到哪一步、为什么失败，然后由 AI 决定接下来怎么办。
- 不能嵌套下发新的脚本（脚本里写 `script` 动作会被拒绝 —— 直接展开到外层写就行）。
- 不能绕过权限。脚本里每个动作都走**和单独下发时完全一样**的检查。

## 执行语义

- **顺序执行**：一步做完才做下一步，不存在并发。
- **失败即停**：某一步失败，脚本整体失败并中止，回执里说明是第几个动作、什么原因。
  标了 `optional` 的步骤例外，失败会被跳过并记在日志里。
- **等待不占预算**：`waitUntil` 内部的轮询不计入 `maxSteps`，
  所以「等 5 分钟」不会把动作预算耗光。它只受 `maxDurationMs` 约束。
- **安全阀**：动作数超过 `maxSteps`、或者总时长超过 `maxDurationMs`，都会中止脚本并说明原因。
  这是为了「AI 把循环条件写错了」这种情况不至于把游戏永远占着。
- **可打断**：脚本执行期间收到 `stop`（或新的移动类指令）会中止脚本，
  正在做的子动作会被干净地取消 —— 松开按住的键、停止挖掘、关掉打开的工作台、
  释放正在蓄力的弓，不会留下「按着 W 键不放」这种状态。
  （这需要取消时真的跑一遍任务的收尾逻辑，`ActionExecutor.cancelAll` 因此调的是
  `Task.cancelNow` 而不是只标一个「已取消」。）
- **限速**：脚本的子动作和普通动作走**同一个**每秒动作数闸门，不存在「包进脚本就能刷动作」。
  区别在于被限速时脚本是**挂起等下一个时间窗**，而不是报错 —— 限速的意思是「慢一点」，
  不是「这件事做不了」。
- **独占执行**：脚本作为「当前动作」独占执行器，它的子动作不经过队列 ——
  否则每一步都会和「移动类动作互相顶掉」的队列语义打架。

## 权限：两层

| 层 | 位置 | 作用 |
| --- | --- | --- |
| 第一层（权威） | 模组 Forge 配置 `[permissions]` | 真正决定哪些动作能在游戏里执行 |
| 第二层 | 插件配置「安全与限流」的 `allow_actions` / `deny_actions` | 提前拦掉，省一次往返 |

关于白名单有一点要说明：白名单管的是**脚本实际会做的那些动作**，而不是 `script` 这个壳子。
所以白名单里只写 `["chat"]` 时，「只包含 chat 的脚本」照样能发出去；
而一个包含 `place` 的脚本会被当场拒绝并点名是 `place`。
黑名单则对 `script` 本身也生效 —— 显式拉黑 `script` 的人就是不想用任务组。

## 报错长什么样

AI 能不能自己修好脚本，取决于报错够不够具体。所以两种错都带位置：

插件侧（本地校验，不用往返）：

```
脚本里出现了不认识的动作名：mine_blockz（是不是想写 mine_blocks？）。
可用动作：chat、command、look、move_to、...
```

```
steps[0] 既没有 action，也不是控制流（repeat/while/if/waitUntil）。当前键是 ['foo']。
```

模组侧（解析或执行时）：

```
steps[0].if：缺少 condition，正确写法 {"if": {"condition": {...}}, "then": [...], "else": [...]}
```

```
脚本执行失败（执行了 5 个动作）
失败原因：第 6 个动作失败：craft(item=stone_sword, count=1)
原因：动作 craft 失败（耗时 1204ms）：材料不够：需要 1 个 cobblestone，背包里只有 0 个
执行记录：
  ✓ mine_blocks(block=stone, count=3)
  ✓ craft(item=stick, count=1)
  ...
```

## 实现位置

| 文件 | 作用 |
| --- | --- |
| `mc-mod/.../script/ScriptProgram.java` | 顶层解析、`maxSteps` / `maxDurationMs` |
| `mc-mod/.../script/ScriptNode.java` | 五种节点的解析与描述 |
| `mc-mod/.../script/Condition.java` | 条件的解析与求值 |
| `mc-mod/.../script/ScriptVm.java` | tick 驱动的解释器（帧栈） |
| `mc-mod/.../script/ScriptContext.java` | 条件求值要问世界的那些问题（窄接口，便于测试） |
| `mc-mod/.../script/VanillaScriptContext.java` | 上面那个接口的真实实现（读客户端玩家） |
| `mc-mod/.../action/tasks/ScriptTask.java` | 把 VM 接到动作执行器上（含限速挂起、权限复用） |
| `mc-mod/.../action/tasks/BasicTasks.java` | 坐标解析（绝对坐标 + `~` 相对坐标） |
| `tools/javatest/ScriptTest.java` | 72 项单测（解析 / 条件 / 控制流 / 安全阀） |
| `tools/javatest/CoordTest.java` | 22 项单测（`~` 相对坐标解析） |

VM 不碰 Minecraft：它通过 `StepRunner` 启动子动作、通过 `ScriptContext` 求值条件。
真实实现接执行器和客户端玩家，测试实现只记录调用 —— 所以循环、分支、等待、
错误传播这些**最容易写错**的地方都能在不开游戏的情况下测干净。
