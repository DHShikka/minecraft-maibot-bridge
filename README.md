# Minecraft ↔ MaiBot AI 桥接

让麦麦（MaiBot）**住进** Minecraft：游戏里发生的一切它都看得见，它的决定会真的变成游戏里的操作。

```
┌──────────────────────────────┐         ┌───────────────────────────────┐
│  Minecraft 客户端             │         │  MaiBot                       │
│  Forge 1.20.1 模组            │  WS     │  ┌─────────────────────────┐  │
│  mcai_bridge                  │◄───────►│  │ 插件 mcai.minecraft-    │  │
│                              │  服务端  │  │      bridge             │  │
│  · 上报聊天/状态/事件    ─────┼────────►│  └───────────┬─────────────┘  │
│  · 执行动作（寻路/挖矿/   ◄───┼─────────┤              │                │
│    放置/战斗/说话/指令…）      │         │   麦麦的对话流 + LLM 工具调用  │
└──────────────────────────────┘         └───────────────────────────────┘
       玩家本人                                   思考与决策
```

- **模组**装在**你自己电脑**上（客户端模组）。单机存档、多人服务器都能用，**服务端不需要装任何东西**。
- **插件**装在 **MaiBot** 里，负责开 WebSocket 服务端、把游戏消息喂给麦麦、把麦麦的决定发回游戏。

---

## 目录结构

| 路径 | 说明 |
|---|---|
| `mc-mod/` | Minecraft Forge 1.20.1 客户端模组（Java 17，零第三方依赖） |
| `minecraft-bridge/` | MaiBot 插件（Python，零第三方依赖） |
| `docs/PROTOCOL.md` | WebSocket 线协议规范（模组 ↔ 插件） |
| `docs/ACTIONS.md` | 全部动作与工具的参数说明 |
| `docs/SCRIPT.md` | 任务组（`mc_script`）的语法、例子与执行语义 |
| `docs/BARITONE.md` | 寻路/挖矿/放置的设计（Baritone 思路的重实现） |
| `docs/ALTOCLEF.md` | 卡死检测与任务层的设计（Altoclef 思路的重实现） |
| `docs/INSTALL.md` | 详细安装与排错 |
| `tools/` | 自检与集成测试脚本（`check_plugin.py`、`check_smoke.py`、`bridge_smoke_server.py`、`javac-check.ps1`） |

---

## 一、装模组

### 1. 拿 jar

已经构建好的：`mc-mod/build/libs/mcai_bridge-1.0.0.jar`

自己重新构建：

```powershell
# 需要 JDK 17
cd mc-mod
$env:JAVA_HOME = "C:\path\to\jdk-17"
.\gradlew.bat build
# 产物：build\libs\mcai_bridge-1.0.0.jar
```

### 2. 放进游戏

把 jar 丢进 **Forge 1.20.1** 的 `mods` 文件夹（`.minecraft/mods/` 或你用的启动器的对应目录），启动游戏。

模组是纯客户端的，服务器端不用装，也不会因为版本不一致被踢。

> **Forge 47.x 全系列都能用。** Forge 在 47.4.0 改过 `@Mod` 主类的构造函数形式：47.0–47.3 只认无参构造，
> 47.4+ 才支持注入 `FMLJavaModLoadingContext` 参数。很多整合包还停在 47.2 / 47.3，
> 所以模组**两个构造函数都提供了** —— 47.4+ 优先用带参的，旧版本自动回退到无参。
> 同一个 jar 覆盖整个 47.x，与 `mods.toml` 声明的 `loaderVersion="[47,)"` 相符。

### 3. 改配置

第一次进游戏后会在 `config/mcai_bridge-client.toml` 生成配置。文件分成 5 节：
`[connection]` `[reporting]` `[permissions]` `[limits]` `[debug]`。

至少确认这两项：

```toml
[connection]
    # 改成 MaiBot 所在机器的地址；同一台机器就是 127.0.0.1
    serverUrl = "ws://127.0.0.1:8765/mc"
    # 如果插件里设了 auth_token，这里必须填一样的
    authToken = ""
```

改完重启游戏（或进出一次世界）即可生效。完整默认内容与逐项说明见
[`docs/INSTALL.md`](docs/INSTALL.md#13-配置)。

### 4. 确认连上了

进入世界后看左上角 HUD：

```
MaiBot: 已连接
任务: 空闲
```

没有 HUD 就把 `[debug] showHud` 打开。连不上看 `logs/latest.log` 里 `[MaiBot Bridge]` 开头的日志。

---

## 二、装插件

### 1. 复制目录

把 `minecraft-bridge/` 整个文件夹复制到 MaiBot 的 `plugins/` 目录下，重命名成你喜欢的名字：

```
MaiBot/
└── plugins/
    └── minecraft-bridge/          ← 把 minecraft-bridge 目录整个拷进来
        ├── _manifest.json
        ├── plugin.py
        ├── mcai_bridge/           ← 子目录！漏掉它会报 "No module named 'mcai_bridge'"
        │   ├── __init__.py
        │   ├── bridge.py
        │   ├── protocol.py
        │   └── ws.py
        └── i18n/
            ├── zh-CN.json
            └── en-US.json
```

> **⚠️ 一定要连子目录一起拷。** 只拷 `plugin.py` + `_manifest.json` 会加载失败，
> 插件页会显示 `No module named 'mcai_bridge'`。
> 外层文件夹名可以随便改（插件身份由 `_manifest.json` 里的 `id` 决定），
> 但 `mcai_bridge/` 这个名字、以及它和 `plugin.py` 的相对位置不能动。

### 2. 重启 MaiBot

插件会被自动发现并加载。日志里应该出现：

```
Minecraft 桥接 WebSocket 服务端已启动: ws://0.0.0.0:8765/mc
Minecraft AI 桥接已就绪
```

然后在 WebUI 的插件页里把「Minecraft AI 桥接」的配置按需要调一下（端口、鉴权令牌、开关）。

### 3. 注意

- 插件**不需要**装 `websockets` 之类的第三方包 —— WebSocket 服务端是内置实现的。
- 如果 8765 端口被占用，改插件配置里的 `port`，同时改模组的 `serverUrl`。
- 如果 MaiBot 和游戏不在同一台机器上，把插件配置的 `host` 保持 `0.0.0.0`，模组的 `serverUrl` 填 **MaiBot 那台机器的局域网 IP**；同时建议设置 `authToken`。

---

## 三、怎么用

### 在游戏里跟麦麦说话

直接在聊天栏打字就行：

```
你: 麦麦，帮我去挖点铁
麦麦: 好，我这就去！
      （然后角色会自己走路、找铁矿、开挖）
```

想让它只对特定前缀起反应（避免它在你们聊天时乱插嘴），在模组配置里设：

```toml
chatTriggerPrefix = "!ai"
```

之后只有 `!ai 去挖点铁` 这样的发言才会被交给麦麦。

### 用工具直接指挥

麦麦的 LLM 侧拿到了 **31 个工具**，全部以 `mc_` 开头。你（或者麦麦自己）可以让它：

| 想做的事 | 麦麦会调用 |
|---|---|
| **一口气做一整段流程** | `mc_script`（任务组，见下） |
| 看看现在什么情况 | `mc_state` |
| 回看刚才发生了什么 | `mc_query` |
| 走过去 / 跟着我 | `mc_move_to` / `mc_follow` |
| 挖矿、砍树 | `mc_mine_blocks`（`iron_ore`、`#minecraft:logs`…） |
| 挖掉某个具体方块 | `mc_mine` |
| 放方块、点火把 | `mc_place` |
| 开箱子、按按钮、用工作台 | `mc_use_on_block` |
| **合成物品** | `mc_craft`（先 `mc_recipes` 查配方） |
| **烧矿、烧食物** | `mc_smelt`（生铁→铁锭、沙子→玻璃） |
| **装水/装岩浆、把桶倒空** | `mc_bucket`（做黑曜石、搭地狱门靠它） |
| **交给 Baritone 去跑图/找矿** | `mc_baritone`（寻路、挖矿、探索、挖隧道） |
| 看背包里有什么 | `mc_inventory` |
| 吃东西、拉弓、举盾 | `mc_use` |
| 打怪、打人 | `mc_attack` |
| **用枪 / 弓 / 弩 / 三叉戟开火** | `mc_shoot`（开火前先数弹药；枪会持续开火 `ticks`） |
| **抵御（会自己判断打/吃/撤）** | `mc_defend` |
| **被打就自动还手（举盾 / 用弓打天上的）** | 模组自己的行为，不用下发动作（见下） |
| 换手上的东西 | `mc_equip` |
| 找矿、找箱子、找怪 | `mc_scan_blocks` / `mc_scan_entities` |
| 睡觉跳过夜晚 | `mc_sleep` |
| 在游戏里说话 | `mc_chat` |
| 执行指令 | `mc_command` |
| 紧急刹车 | `mc_stop` |

> **装了 Baritone 的话，寻路和找矿优先用 `mc_baritone`**：它是成熟的寻路机器人，
> 绕障碍、搭桥、挖穿、垫脚都会自己做，比模组自带的那套强。
> 我们是**用它的聊天指令**驱动它的（`#goto`、`#mine 64 dirt`…），零依赖 ——
> 没装 Baritone 时这些消息就只是普通聊天，不会崩。细节见 [docs/BARITONE.md](docs/BARITONE.md)。
>
> **它的活会算进「任务状态」**：Baritone 干活时不经过模组的动作队列，
> 所以模组里有个活动监视，把「在跑哪条指令、还在不在动、有没有报错」并进
> `mc_task_status` / `mc_state` —— 否则这边一直显示「空闲」，麦麦会以为没人在做事。
> `mc_stop` 也会顺手给它发一条 `#stop`。

### 模组自己的三个「保命」行为

这些**不需要麦麦下发动作**，是模组直接做的（配置在 `config/mcai_bridge-client.toml`）：

| 配置 | 默认 | 行为 |
|---|---|---|
| `[combat] autoFight` | `true` | **被生物打了就立刻还手**：中断手上的活（挖矿/放置/走路）、转身打回去 |
| `[combat] autoAttackHostiles` | `true` | **主动出击**：托管时附近有敌对生物就上去打（贴脸 ≤6 格会打断手头的活） |
| `[combat] useShield` | `true` | 有盾牌就自动换到**副手**，敌人靠近就举盾格挡 |
| `[combat] useBow` | `true` | 目标在天上/会飞（近战够不到）时自动换弓，按距离算下坠提前量，拉满一秒再放箭 |
| `[combat] autoReload` | `true` | **弹匣清空自动换弹**：托管 + 闲着 + 背包里有同口径子弹时，自己把弹匣补满 |
| `[takeover] autoOnConnect` | `true` | **AI 托管**：麦麦一连上就接管 —— 关掉失焦暂停、放开鼠标，玩家可以切出去，AI 照常操作 |
| `[visual] gamma` | `0.0` | 把画面亮度强行拉到指定值 → **夜视 / fullbright**（`10` 基本全亮；`0` = 不动你的设置） |

**自动战斗会按手里的武器换手**（不用你操心，也不用下发动作）：

| 距离 | 用什么 |
|---|---|
| **≤ 2 格** | **近战武器**（会自动把背包里的剑/斧/三叉戟换到手上，换不到才退回手里的远程武器） |
| 2 ~ 4 格 | 枪有弹就射；弹匣空了**先抡**（站着换弹会被打死） |
| > 4 格 | 有弹就射、弹匣空就换弹、弓有箭就射 |

拿弓就射箭、拿枪就开枪、弹匣打空自己换弹、子弹彻底没了才退回近战 —— 真机日志长这样：

```
自动反击：史莱姆（打断当前动作，用枪打）
自动反击：史莱姆（打断当前动作，先换弹）
自动换弹：弹匣空了（背包还有 70 发备弹）
主动出击：附近有 僵尸（2 格外，贴脸了，近战）
```

细节（包括为什么反击必须「打断」、为什么贴脸要打断手头的活、为什么倒水要倒在岩浆**旁边**）
见 [`docs/ACTIONS.md`](docs/ACTIONS.md) 的战斗与 `bucket` 两节。

### 和别的模组怎么配合

原则是**软依赖**：没装那些模组时对应功能只是「用不上」，不会崩，也不需要它们在编译期存在。

| 模组 | 怎么配合 |
|---|---|
| **Baritone** | 聊天指令驱动（`mc_baritone`），活动并进任务状态；`mc_stop` 会顺手 `#stop` |
| **JEI / 任意模组** | 物品名解析直接翻**注册表**：内部 ID、**中文显示名**、模糊包含都能匹配（`mc_craft` / `mc_recipes` / `mc_inventory` / `mc_equip` 都吃这套）。`mc_recipes` 找不到合成台配方时会**翻遍所有配方类型**，把「Create 的粉碎/混合、模组机器」这类加工方式也报出来 |
| **永恒枪械工艺等枪械 / 远程武器** | `mc_shoot`：**弓、弩、三叉戟、枪械都能用**（自动选一把）。开火前**先数弹药**，没子弹会拒绝而不是空放；`fired` 以「弹药真的少了」为准。枪的开火/换弹走**模组自己的接口**（反射调，零编译依赖）—— 因为 TaCZ 的开火前有一道 `InputExtraCheck.isInGame()` 要求鼠标锁在窗口里，跟「AI 托管放开鼠标」直接冲突，走按键永远打不响 |
| **机械动力 Create** | 方块按普通方块挖/放；扳手就是 `use_on_block` 右键；它的加工配方走上面那条「翻遍所有配方类型」 |
| 环境自述 | 状态快照里带 `mods`（jei/tacz/create/baritone 装了没）和 `ranged`（手上武器的剩余弹药），AI 随时看得见 |


举个例子，让它「做一把石剑」它会自己拆成：

```
mc_inventory            → 看看有什么
mc_recipes cobblestone  → 不对，石剑要圆石+木棍
mc_craft stick          → 木板不够就先 mc_craft oak_planks
mc_craft stone_sword    → 3x3，需要工作台
   └─ 没工作台 → mc_craft crafting_table → mc_place → 再 mc_craft stone_sword
```

参数和返回值见 [`docs/ACTIONS.md`](docs/ACTIONS.md)。

### 任务组：一次说完一整段流程

上面那些工具一次只做一个动作。一步一步来的瓶颈不在网络（毫秒级），
而在**每一步都要 AI 重新思考一轮**（秒级）—— 做一把石剑 9 步就是 9 轮。

`mc_script` 让麦麦一次把整段流程交过来，模组在游戏里自己连着做完：

```json
{"name": "做把石剑", "steps": [
  {"action": "mine_blocks", "params": {"block": "stone", "count": 3}},
  {"action": "craft", "params": {"item": "stick", "count": 1}},
  {"action": "craft", "params": {"item": "stone_sword", "count": 1}}
]}
```

除了动作，还支持 `repeat`（重复）、`while`（条件循环）、`if`/`else`（分支）、
`waitUntil`（等条件成立），条件是 `has` / `holding` / `healthBelow` / `nearby` / `atPos` /
`busy` 加上 `all` / `any` / `not` 组合。写错了会带位置报错，中途失败会告诉你做到哪一步。

**常见流程不用现编**：`mc_script` 还带了一批预设，材料账都算好了 ——
`preset="wooden_pickaxe"` 就是「砍树 → 木板 → 木棍 → 工作台 → 放到脚边 → 木镐」，
另有 `stone_pickaxe`、`stone_sword`、`crafting_table`、`torch`、`furnace`、`chest`，
以及参数化的 `mine_until`（挖够多少个）和 `mine_then_craft`（挖够再合成）。

坐标可以直接写整数，也可以写 `"~"` 相对坐标（`~` 是脚下那一格）——
脚本是提前写好的，只有相对坐标才能表达「放在我脚边」。

语法、例子和限制见 **[`docs/SCRIPT.md`](docs/SCRIPT.md)**。

### 从 QQ 侧控制

| 指令 | 作用 |
|---|---|
| `/mc` 或 `/mc status` | 看有几台游戏客户端在线、各自在哪、血量多少 |
| `/mc say <内容>` | 让游戏里的角色说一句话 |
| `/mc exec <指令>` | 让角色执行一条指令 |
| `/mc stop` | 立刻让角色停下 |

### 主动行为

麦麦不只是被动回答。以下情况它会**主动**做出反应（可在配置里改）：

- 血量危险（`health_critical`）
- 死亡（`death`）

默认只对这两种情况主动打扰，其它事件（受伤、升级、换维度、击杀…）只作为上下文记录，不会打断正常聊天。

---

## 四、权限与安全

**AI 能做的事情范围由模组配置决定**，这是第一道闸门（改的是游戏客户端的文件，插件改不了）：

```toml
[permissions]
    allowMovement = true      # 走路、跳跃、寻路
    allowLook = true          # 转视角
    allowBreakBlocks = true   # 破坏方块
    allowPlaceBlocks = true   # 放置方块
    allowAttack = true        # 攻击
    allowUse = true           # 使用物品 / 右键
    allowInventory = true     # 整理背包、丢弃物品
    allowChat = true          # 在游戏里发言
    allowCommand = true       # 执行游戏指令（权限最高）
    # 禁止执行的指令（比较时忽略大小写、不算前导斜杠）
    commandBlacklist = ["op", "deop", "ban", "kick", "stop", "whitelist", ...]

[limits]
    maxActionsPerSecond = 20   # 每秒最多几个动作，防刷屏
    actionTimeoutSeconds = 120 # 单个长任务的最长执行时间
```

第二道闸门在插件侧：

```toml
[safety]
    max_actions_per_second = 20
    allow_actions = []           # 白名单，留空表示全部允许
    deny_actions = ["attack"]    # 黑名单，比如只给麦麦探索和建造的权限
```

想最保守，就只留 `chat` + `get_state`：

```toml
allow_actions = ["chat", "get_state", "mc_state", "mc_query", "mc_list_clients", "mc_task_status"]
```

### 已知边界

- 模组只能看见**客户端已经加载的区块**（也就是玩家附近）。远处的方块/生物扫不到，这是原版机制决定的，不是 bug。
- 寻路按 Baritone 的思路重写过（见 [`docs/BARITONE.md`](docs/BARITONE.md)）：会绕障碍、上台阶、下落、**跳缺口**、**挖穿挡路的方块**、**向下挖矿道**、**垫方块翻墙**。跨不过去的情况会说明「最后停在哪、为什么」。
  **装了真正的 Baritone 时，长距离移动和找矿优先交给它**（`mc_baritone`）。
- **还没实现**：沿梯子/藤蔓攀爬、船/矿车/鞘翅、按图纸建筑（schematic）—— 这些 Baritone 有，我们没有。
- AI 的动作受原版规则约束：够不到就够不到、没材料就放不了、服务器没权限的指令就是会失败。所有失败都会带着原因回传给麦麦，它会自己想别的办法。

---

## 五、自检与测试

仓库里带了几套可复现的验证脚本：纯 JVM 单测、插件自检、跨语言冒烟，以及**开真游戏的真机自检**。

### 插件端（语法 + Manifest + 端到端协议）

```powershell
python tools/check_plugin.py
```

它会：语法检查 → 按 MaiBot 的 `ManifestValidator` 规则校验 `_manifest.json` → 用签名一致的假 SDK 加载 `plugin.py` 并模拟 Runner 的组件发现 → **起一个真的 WebSocket 服务端**，扮演模组跑完整链路（握手、聊天注入、工具调用、动作回传、任务组下发、事件上报、断线）。

当前结果：**196 项全部通过**。

### 真机自检（开真的 Minecraft，跑真的动作）

前两类测试用的都是假模组 / 假客户端。**这一类是真的把游戏开起来**，让插件像麦麦一样
下发动作、模组在游戏里真的执行、结果回传到插件侧断言。

```powershell
# 终端 1：起真实插件服务端（默认端口 8765，和模组默认 serverUrl 一致）
python tools/live_server.py

# 终端 2：启动游戏（--selftest 会让模组自动创建一个超平坦世界并进去）
node tools/run-client.mjs --selftest
```

进去之后全自动：模组连上插件 → 插件下发十几个动作 → 结果是
`.research/live-result.json`。它覆盖的东西是别的方式验证不到的：

| 验证项 | 说明 |
|---|---|
| 模组能在真实 Forge 里加载 | 本次实测 Forge **47.4.23**（编译环境是 47.4.10，两边都跑得起来） |
| 进世界后自动连 WS + 握手 | 不在世界里不连接（设计如此），所以必须真进世界 |
| 状态快照 / 指令 / 聊天 | `get_state`、`command`、`chat` 在游戏里真的发生 |
| **合成**：木板 → 木棍 → 工作台 → **木镐** | 木镐的 `needsTable: true`，证明真的走了 3x3 工作台路径 |
| **`~` 相对坐标放置** | `place {"x":"~","y":"~","z":"~1"}` 放到面前一格，回执 `placed: true` |
| **任务组** | 一次下发 7 个动作（含 `wait`、条件分支），4.3 秒跑完，只回传一次聚合结果 |
| **挖矿「挖够就停」** | `mine_blocks(count=3)` 就是 3 个（这个 bug 就是真机自检抓出来的） |
| **抵御** | 召唤 3 只僵尸，`defend` 自己换上铁剑清掉 3 只，血量 20→20，附近威胁归零 |
| 断线重连 | 杀掉服务端后模组自动退避重试，重起服务端就自己连回来了 |

场景可以选（默认那个是「从零做一把木镐」的全流程）：

```powershell
python tools/live_server.py --bucket     # 桶：装水/倒水/装岩浆/倒岩浆
python tools/live_server.py --baritone   # Baritone 联动（mods 里要有 baritone.jar）
python tools/live_server.py --survival   # 默认地形 + 生存 + 不作弊
```

`--bucket` 那一轮的结果（四条路径全绿）：装水 0.8s、倒水 0.6s、装岩浆 1.0s、倒岩浆 0.6s，
世界里能扫到倒出来的水和岩浆，最后背包是**三个空桶** —— 液体装走又倒回去了。
**水桶这件事在真机上曾经完全不通**（对着水点 6 次，桶还是空的），根因写在
[`docs/ACTIONS.md`](docs/ACTIONS.md) 的 `bucket` 一节里：装和倒都得走 `useItem`，
而且判断成功不能只看手上那一格。

自检入口（`selftest/SelfTest.java`）只在 `-Dmcai.selftest` 存在时生效，正常启动游戏时
第一个 tick 就什么都不干。

#### 跑长任务时的几个开关

搭一座地狱门要砍树、挖矿、烧铁、找岩浆，跨越几十分钟甚至几次重启游戏，
所以自检入口还有几个给长任务用的开关（`tools/run-client.mjs` 会从环境变量转成 JVM 参数）：

| 环境变量 | 作用 |
|---|---|
| `MCAI_KEEP_WORLD=1` | **读旧存档接着玩**（默认每次新建，长任务必须开这个） |
| `MCAI_KEEP_INVENTORY=true` | 建世界时写 `keepInventory`：**死亡不掉落**（真机会被怪打死，掉光装备等于重来） |
| `MCAI_AUTO_RESPAWN`（默认开） | 死了自动重生。原版会停在「你死了」界面等人点按钮 —— 无人值守时就是**卡死** |
| `MCAI_TERRAIN=normal` / `MCAI_CHEATS=false` | 默认地形 / 不开作弊 |
| `MCAI_QUIT_AFTER=<秒>` | 进世界后到点自动退出 |

> **别用 `Stop-Process -Force` 杀客户端。** 硬杀会丢掉还没写盘的那部分进度 ——
> 实测丢过一次「2 个桶 + 盾牌」（背包回滚到几分钟前，但人物坐标是新的）。
> 想重启就让它在游戏目录里看到一个 **`mcai-quit.flag`**：自检入口会走 `mc.stop()`
> 正常存档再退。
>
> 另外：单人游戏**窗口失焦会自动暂停**，而暂停时集成的服务端不 tick ——
> 表现是「玩家一步都走不动、指令执行了但方块在客户端上不变」。
> 自检模式会关掉这个（`pauseOnLostFocus`），游戏日志里的原话是 "Saving and pausing game..."。

### 跨语言互通（Java 客户端 ↔ Python 服务端）

```powershell
# 1) 起服务端（会把端口写到 .research/smoke-port.txt）
python tools/bridge_smoke_server.py

# 2) 另开一个终端，用模组里那份 WsClient 去连它
#    先编译一次：
javac -encoding UTF-8 -cp "<模组编译 classpath>" -d tools/javatest/out tools/javatest/BridgeSmokeTest.java
java -cp "<模组编译 classpath>;tools/javatest/out" BridgeSmokeTest ws://127.0.0.1:<端口>/mc .research/smoke-java.json
```

这一步验证的是**手写 WebSocket 客户端的握手、掩码、帧编解码、ping/pong、close 握手**在真实 RFC 6455 服务端面前是否正确。它不是可选项：开发过程中就是它抓出了「`onOpen` 在构造函数里回调导致 hello 永远发不出去」这个致命 bug。

```powershell
# 3) 两边都跑完后，断言结果
python tools/check_smoke.py     # 12 项
```

### 纯 JVM 单测（不用开游戏）

| 测试 | 覆盖 |
|---|---|
| `tools/javatest/PathFinderTest.java` | 寻路与代价模型，**50 项**（见 [`docs/BARITONE.md`](docs/BARITONE.md)） |
| `tools/javatest/ScriptTest.java` | 任务组脚本：解析、条件、控制流、安全阀，**72 项**（见 [`docs/SCRIPT.md`](docs/SCRIPT.md)） |
| `tools/javatest/CoordTest.java` | 坐标解析（含 `~` 相对坐标），**22 项** |
| `tools/javatest/ProgressTest.java` | 卡死检测（误判与漏判两边都测），**19 项**（见 [`docs/ALTOCLEF.md`](docs/ALTOCLEF.md)） |
| `tools/javatest/DefenseTest.java` | 抵御战术（打/吃/撤、选目标、撤退方向），**32 项** |

这些之所以能在纯 JVM 里测，是因为关键的逻辑都被抽成了窄接口：
寻路只问 `BlockWorld`「那一格是什么方块」，脚本只问 `ScriptContext`「背包里有几个」
和 `StepRunner`「把这一步做了」。真实实现接 Minecraft，测试实现读内存里的假数据。

### 模组编译

```powershell
tools\javac-check.ps1        # 直接用 javac 编译，报错信息比 Gradle 可读
cd mc-mod; .\gradlew.bat build
```

---

## 六、排错

| 现象 | 原因与处理 |
|---|---|
| HUD 显示「连接中…」很久 | 检查插件的 WebSocket 是否启动（MaiBot 日志）、端口是否被占用、防火墙是否挡住 |
| HUD 显示「握手中」 | 多半是 `authToken` 两边不一致，或者协议版本不匹配（看 `logs/latest.log`） |
| 麦麦听得见但从不回话 | 插件配置里 `chat.enabled` 和 `chat.force_reply` 打开；游戏里说的话要满足 `chatTriggerPrefix` |
| 麦麦说话，游戏里没反应 | 检查是不是被 `ignore_own_messages` 之外的过滤器拦了；看插件日志有没有「没有已连接的 Minecraft 客户端」 |
| 动作总是「超时」 | 游戏窗口失去焦点/暂停（单机按 Esc 会暂停世界）时动作不会推进；回到游戏窗口再试 |
| **执行到一半不动了** | 分两种情况：**① 卡死**——现在模组会在 `stuckDetectionSeconds`（默认 24 秒）内判出来并报「卡住了：最近约 24 秒既没有移动，也没有任何进展」，附原因和建议，看一眼就知道该绕过还是先挖开；**② 世界暂停**——单机切出去/按 Esc 会让世界暂停，动作是真的不推进（这是原版行为），点回游戏窗口就继续。两种情况在 `logs/latest.log` 里都有记录 |
| 明明卡住了却什么都没报 | 说明暂停时间没到检测窗口，或者 `[limits] stuckDetectionSeconds` 被改成了 0。把它设回 24 再看 |
| 正常操作被误判成「卡住」 | 卡死检测是个启发式。网络卡顿严重、或者你的玩法本来就要长时间原地不动，就把 `stuckDetectionSeconds` 调大（或设 0 关闭） |
| 寻路总是失败 | 目标可能需要挖穿、或者垂直落差超过 24 格。先用 `mc_scan_blocks` 确认目标附近情况 |
| 挖了方块但没掉落 | 手上工具不对。让麦麦 `mc_equip` 一把合适的工具 |

更多细节见 [`docs/INSTALL.md`](docs/INSTALL.md)。

---

## 七、协议一览

一句话版：模组和插件之间传 JSON 文本帧，五类报文——`hello`/`hello_ack` 握手、`state` 状态快照、`event` 游戏事件、`action` 动作下发、`action_result` 结果回传。

完整字段定义（含每个动作的参数表）见 [`docs/PROTOCOL.md`](docs/PROTOCOL.md)。

---

## 许可证

MIT。
