# 安装与排错

## 0. 前置条件

| 需要什么 | 版本 |
|---|---|
| Minecraft | 1.20.1 |
| Forge | 47.x（47.0 – 47.4+ 全系列） |
| Java（跑游戏） | 17 |
| Java（构建模组，可选） | JDK 17 |
| MaiBot | Host ≥ 1.0.0，插件 SDK ≥ 2.8.0 |
| Python（跑 MaiBot） | 3.10+ |

### 关于 Forge 47.x 的构造函数兼容

Forge 在 47.4.0 改过 `@Mod` 主类的构造函数形式：

| Forge 版本 | 支持的写法 |
|---|---|
| 47.0 – 47.3 | 只有无参构造 + `FMLJavaModLoadingContext.get()` |
| 47.4+ | 注入 `FMLJavaModLoadingContext` 参数（无参构造仍然可用，作为回退） |

47.4 的 `FMLModContainer` 会**先尝试带参构造，找不到才回退到无参**；而 47.0–47.3 只会找无参构造。
所以 `McAiBridge` 两个构造函数都提供了，同一个 jar 覆盖整个 47.x ——
这也和 `mods.toml` 里声明的 `loaderVersion="[47,)"` 一致。

如果只保留带参构造，模组在 47.2 / 47.3 的整合包里会直接加载失败（找不到有效构造函数）。

模组**不需要**任何额外的库；插件也**不需要** `pip install` 任何东西。

---

## 1. 安装模组

### 1.1 获得 jar

```powershell
cd mc-mod
$env:JAVA_HOME = "C:\path\to\jdk-17"     # 需要 JDK（不是 JRE）
.\gradlew.bat build
```

产物：`mc-mod\build\libs\mcai_bridge-1.0.0.jar`

首次构建会比较久：Gradle 要下载 ForgeGradle、Minecraft 客户端与服务端、映射文件，并做一次反编译。整个过程大约需要 1–2 GB 磁盘和十几分钟（视网速）。之后增量编译只要几秒。

> 如果构建时网络慢，可以在 `mc-mod/settings.gradle` 与 `mc-mod/build.gradle` 里把镜像仓库换成离你更近的源（默认已经加了 `maven.aliyun.com` 与腾讯的 Gradle 分发镜像）。
>
> `mc-mod/gradle/wrapper/gradle-wrapper.properties` 里的 `distributionUrl` 默认指向腾讯镜像；想用官方源就换成 `https://services.gradle.org/distributions/gradle-8.8-bin.zip`。

### 1.2 放进 mods 目录

把 jar 复制到 Forge 1.20.1 的 `mods` 目录：

- 官方启动器：`%APPDATA%\.minecraft\mods\`
- 第三方启动器（PCL2 / HMCL 等）：在启动器的「版本设置 → mod 管理」里添加，或找到该版本的 `.minecraft\mods\`

模组是**纯客户端**的（`clientSideOnly=true`），装进多人服务器不会被踢，也不需要在服务器上装。

### 1.3 配置

第一次启动游戏后会生成 `config/mcai_bridge-client.toml`。**这就是它的完整默认内容**
（下面的内容是用 Forge 的配置系统真实生成出来的，不是手写的）：

```toml
[connection]
    # 是否启用 AI 桥接。关闭后不会建立任何网络连接，也不会拦截任何事件。
    enabled = true
    # 进入世界/服务器后是否自动连接 MaiBot 插件。
    autoConnect = true
    # MaiBot 插件 WebSocket 服务端地址。
    # 同一台电脑填 ws://127.0.0.1:<插件端口><插件路径>；
    # MaiBot 在另一台机器上就填那台机器的局域网 IP。
    serverUrl = "ws://127.0.0.1:8765/mc"
    # 鉴权令牌，必须与 MaiBot 插件配置里的 auth_token 完全一致。留空表示不鉴权。
    authToken = ""
    # 断线后的重连基础间隔（秒），实际间隔会按指数退避增长，上限为该值的 6 倍。
    reconnectDelaySeconds = 5
    # 心跳间隔（秒）。
    heartbeatSeconds = 15
    # 连接超时（秒）。
    connectTimeoutSeconds = 10

[reporting]
    # 定时全量状态快照的间隔（tick，20 tick = 1 秒）。0 表示关闭定时快照。
    snapshotIntervalTicks = 40
    # 是否把游戏内聊天/系统消息上报给 AI。这是 AI「听到」玩家的主要途径。
    reportChat = true
    # 动作栏消息（技能冷却、提示等噪音），默认关闭。
    reportActionBar = false
    # 受伤、死亡、重生、升级、切换维度、击杀等事件。
    reportGameEvents = true
    reportDamage = true
    # 下面两个音量较大，默认关闭
    reportBlockEvents = false
    reportItemEvents = false
    # 只有以此前缀开头的玩家发言才会交给 AI（留空 = 所有玩家聊天都交给它）
    chatTriggerPrefix = ""

[permissions]
    # ↓↓↓ AI 能做什么，最终由这一节决定 ↓↓↓
    allowMovement = true
    allowLook = true
    allowBreakBlocks = true
    allowPlaceBlocks = true
    allowAttack = true
    allowUse = true
    allowInventory = true
    allowChat = true
    allowCommand = true
    commandBlacklist = ["op", "deop", "ban", "ban-ip", "pardon", "pardon-ip", "kick", "stop",
                        "whitelist", "save-all", "save-off", "save-on", "reload", "restart"]

[limits]
    maxActionsPerSecond = 20
    actionTimeoutSeconds = 120
    maxQueuedActions = 32

[debug]
    showHud = true
    verboseLog = false
```

> 文件里各节的**顺序**由 Forge 内部决定，和上面不一致是正常的，不影响使用。

**日常真正需要动的通常只有这几个**：

| 想做的事 | 改哪里 |
|---|---|
| 连不上 / 换端口 | `[connection] serverUrl` |
| 跨机器连接要鉴权 | `[connection] authToken` |
| 不想让它对所有聊天搭话 | `[reporting] chatTriggerPrefix = "!ai"` |
| 只准它说话、不准动手 | `[permissions]` 里把除 `allowChat` 外全改 `false` |
| 禁止某些指令 | `[permissions] commandBlacklist` |
| 嫌它操作太快 | `[limits] maxActionsPerSecond` |
| 看不见左上角状态 | `[debug] showHud` |

改完**重启游戏**（Forge 的客户端配置不热重载）。


改完**重启游戏**（或者退出世界再进一次）生效。Forge 的客户端配置不会热重载。

### 1.4 验证连接

进入世界后左上角会出现 HUD：

```
MaiBot: 已连接
任务: 空闲
```

看不到 HUD：

- 确认 `showHud = true`；
- 按 F1 会隐藏全部 HUD，再按一次；

连不上：看 `logs/latest.log`，搜 `[MaiBot Bridge]`。

---

## 2. 安装插件

### 2.1 复制

把 `minecraft-bridge/` 整个目录复制到 MaiBot 的 `plugins/` 下，重命名成任意名字：

```
MaiBot/
└── plugins/
    └── minecraft-bridge/
        ├── _manifest.json
        ├── plugin.py
        ├── mcai_bridge/
        │   ├── __init__.py
        │   ├── bridge.py
        │   ├── protocol.py
        │   └── ws.py
        └── i18n/
            ├── zh-CN.json
            └── en-US.json
```

> **⚠️ 为什么子目录不能漏**
>
> MaiBot 的 `PluginLoader` 加载插件时，加进 `sys.path` 的是**插件目录的父目录**
> （`plugins/`），而**不是插件目录本身**（见 `plugin_loader.py:575-579`）：
>
> ```python
> plugin_parent_dir = plugin_dir.parent
> with self._temporary_sys_path_entry(plugin_parent_dir):
>     spec.loader.exec_module(module)
> ```
>
> 所以插件目录里的子包默认 import 不到。本插件在 `plugin.py` 开头自己把所在目录
> 插进了 `sys.path` 来解决这个问题（那几行注释标了「不能删」）。
>
> 如果你看到插件页报 **`No module named 'mcai_bridge'`**，按顺序查两件事：
> 1. `plugins/<插件名>/mcai_bridge/` 这个子目录在不在（复制时最容易漏）；
> 2. `plugin.py` 开头那段 `_PLUGIN_DIR ... sys.path.insert(...)` 有没有被删掉。
>
> 可以用 `python tools/check_loader.py` 本地复现真实的加载过程。

> **不要**把它放进 `src/plugins/built_in/`，也不要给 manifest 加 `"plugin_type": "adapter"` —— 第三方 `plugins/` 目录下的 adapter 类型会被 MaiBot 静默跳过。

### 2.2 重启 MaiBot

日志里应该出现：

```
Minecraft 桥接 WebSocket 服务端已启动: ws://0.0.0.0:8765/mc
Minecraft AI 桥接已就绪：请在游戏里确认模组配置 serverUrl = ws://<本机IP>:8765/mc
```

### 2.3 配置插件

WebUI → 插件 → Minecraft AI 桥接 → 配置。主要项：

```toml
[plugin]
    # ⚠️ 这一节必须存在，而且 config_version 不能删。
    # Runner 激活插件前会检查它，缺了会直接报「插件初始化失败」。
    enabled = true
    config_version = "1.0.0"

[server]
    host = "0.0.0.0"          # 只连本机的话改成 127.0.0.1 更安全
    port = 8765
    path = "/mc"
    account_id = "mc-server-1"
    auth_token = ""           # 建议跨机器时设置
    heartbeat_seconds = 15
    idle_timeout_seconds = 90

[chat]
    enabled = true
    chat_mode = "group"       # 整个服务器算一个聊天流；private = 每人一个
    server_group_id = ""      # 留空自动用世界名/服务器地址
    force_reply = true        # 玩家说话时强制唤醒麦麦（推荐开）
    ignore_own_messages = true
    ignore_players = []
    command_prefix = "!ai"

[events]
    report_events = true
    notify_on = ["death", "health_critical"]   # 哪些事件要主动打扰麦麦
    notify_cooldown_seconds = 30
    event_context_limit = 60

[safety]
    max_actions_per_second = 20
    max_action_timeout_seconds = 180
    allow_actions = []        # 白名单，空 = 全允许
    deny_actions = []         # 黑名单
    greet_on_connect = true
```

---

## 3. 跨机器部署

MaiBot 和游戏不在同一台电脑时：

1. 插件保持 `host = "0.0.0.0"`，`port = 8765`；
2. **设置 `auth_token`**（例如一串随机字符）—— 否则任何能连到这个端口的人都能操作你的游戏角色；
3. 模组的 `serverUrl` 填 **MaiBot 那台机器的局域网 IP**：`ws://192.168.1.50:8765/mc`；
4. 模组的 `authToken` 填一模一样的值；
5. 放行防火墙：

```powershell
New-NetFirewallRule -DisplayName "MaiBot MC Bridge" -Direction Inbound -LocalPort 8765 -Protocol TCP -Action Allow
```

---

## 4. 排错

### 4.1 连接问题

| 现象 | 排查 |
|---|---|
| HUD 一直「连接中…」 | MaiBot 日志里有没有「WebSocket 服务端已启动」；端口是否被别的程序占用（`netstat -ano \| findstr 8765`）；防火墙 |
| HUD「握手中」后断开 | `authToken` 两边不一致，或协议版本不匹配。日志里会有明确原因 |
| 断开 code=1008 | 鉴权失败或协议版本不匹配 |
| 断开 code=1006 | TCP 层断了：网络问题，或服务端进程没了 |
| 连上了但很快又断 | 检查心跳：`idle_timeout_seconds` 太小，或者模组所在机器时间不对外（不影响协议，但影响日志判读） |
| 日志「协议版本不匹配：模组 vN，插件 v1」 | 模组和插件不是同一版本，更新其中一个 |

### 4.2 麦麦听得见但不回应

1. 插件配置 `chat.enabled = true`；
2. `chat.force_reply = true`（MaiBot 的发言频率控制有时会拦下回复，这个开关会让游戏场景强制响应）；
3. 模组的 `chatTriggerPrefix` —— 设了 `!ai` 就必须以 `!ai` 开头说话；
4. 玩家名在 `ignore_players` 里；
5. 说的是**系统消息**（死亡提示、进度播报），这类只进上下文不进对话；
6. 看 MaiBot 日志里有没有「Host 没有接受这条游戏聊天」。

### 4.3 麦麦的动作没反应

| 现象 | 原因 |
|---|---|
| 工具返回「当前没有任何 Minecraft 客户端连接」 | 模组没连上，或玩家已经离开世界 |
| 工具返回「该动作已被模组配置禁用」 | 模组的 `permissions.allow*` 开关关着 |
| 工具返回「该动作已被插件配置禁用」 | 插件的 `allow_actions` / `deny_actions` |
| 动作超时 | **游戏窗口失去焦点时单机世界会暂停**，动作不会推进。点回游戏窗口再试 |
| 工具返回「卡住了：最近约 24 秒既没有移动，也没有任何进展」 | 模组的卡死检测生效了。看后面的原因提示：够不着 / 被挡死 / 需要先准备工具。调 `[limits] stuckDetectionSeconds` 可以放宽或关掉 |
| 动作执行到一半就没动静，但也没报错 | 多半是世界暂停（同上）。`logs/latest.log` 里能看到模组最后一条日志停在哪；如果连「卡住」都没报，检查 `stuckDetectionSeconds` 是不是被设成 0 了 |
| 寻路失败 | 目标被完全封死 / 垂直落差 > 24 格。让麦麦先 `mc_mine` 挖开，或换个目标 |
| 挖了方块但没掉落 | 手上工具不对。让麦麦 `mc_equip` 一把合适的工具 |
| 放置失败 | 目标位置被占住；或者必须挨着一个已有方块的表面 |
| 指令失败 | 角色没有 OP 权限。这类失败游戏聊天栏会提示，但插件侧看不到 |

### 4.4 开调试日志

模组 `config/mcai_bridge-client.toml`：

```toml
[safety]
    verboseLog = true
```

会打印每条收发的报文（截断到 400 字符）。

MaiBot 侧如果想看更细的插件日志，调 MaiBot 的日志级别即可，插件用的是标准 `logging`，名字是 `plugin.mcai_bridge*`。

---

## 5. 自检脚本

### 5.1 插件自检（不需要 MaiBot）

```powershell
python tools/check_plugin.py
```

112 项检查，覆盖语法、Manifest 规则、组件发现、以及一次真实的端到端协议往返。

### 5.2 跨语言互通测试

```powershell
# 终端 1
python tools/bridge_smoke_server.py

# 终端 2（等 .research/smoke-port.txt 出现后）
javac -encoding UTF-8 -cp "<模组编译 classpath>" -d tools/javatest/out tools/javatest/BridgeSmokeTest.java
java -cp "<模组编译 classpath>;tools/javatest/out" BridgeSmokeTest ws://127.0.0.1:<端口>/mc .research/smoke-java.json
```

`<模组编译 classpath>` 可以通过 `cd mc-mod; .\gradlew.bat printCompileClasspath` 生成到 `mc-mod/build/compileClasspath.txt`。

### 5.3 只用 javac 快速编译模组

```powershell
cd mc-mod
.\gradlew.bat printCompileClasspath
cd ..
node .research/genargs.mjs        # 或者手动拼 javac 参数
.toolchain\jdk\jdk-17.0.2\bin\javac.exe "@mc-mod\build\javac-args.txt"
```

好处是报错信息是 UTF-8，比 Gradle 转发出来的可读。

---

## 6. 卸载

- 模组：删掉 `mods/mcai_bridge-1.0.0.jar` 和 `config/mcai_bridge-client.toml`
- 插件：删掉 `plugins/minecraft-bridge/` 目录（它的 `config.toml` 和数据都在这个目录里）
