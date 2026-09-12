# 任务层：向 Altoclef 借鉴了什么

这份文档记录本项目在**「怎么让一串动作别中途死掉」**这件事上参照
[Altoclef](https://github.com/gaucho-matrero/altoclef) 做了什么，
以及**还差什么**。

---

## 0. 先说许可证（和 Baritone 那篇的区别）

| | |
|---|---|
| Altoclef | **MIT** |
| 本项目 | **MIT** |
| Baritone（Altoclef 的依赖） | LGPL-3.0 |

Altoclef 是 MIT，跟本项目一样 —— 也就是说**连代码复用都是许可兼容的**（保留版权声明即可）。
不过这次仍然是**照思路重写**（`ProgressWatchdog` 是本项目自己的实现、自己的 API），
原因不是许可，而是它的检查器跟 Minecraft 的方块破坏进度、玩家实体绑得比较紧，
我们需要一个不碰 Minecraft、能直接单测的版本。

> 对比着看：Baritone 那篇（[BARITONE.md](BARITONE.md)）是 LGPL，所以那边只能借鉴架构与思路，
> 一行代码都不能抄。这两个项目的边界不一样，别混。

---

## 1. Altoclef 是什么，和我们什么关系

Altoclef 的定位是「无人值守把 Minecraft 打通」的机器人。它的结构可以粗略看成两层：

```
任务层   chains / tasks / tasksystem / TaskCatalogue      ← 由 Altoclef 自己实现
寻路层   util/baritone/*（GoalAnd、GoalDodgeProjectiles…）  ← 直接用 Baritone
```

也就是说，**Altoclef 自己的贡献恰好是我们最薄的那一层**：把一个高层次目标
（"搞到一把石镐"）拆成可执行、可失败、可重试的任务序列，并保证它不会中途莫名其妙地卡住。

我们的分工是同一个形状：

| 层 | 我们 | Altoclef |
|---|---|---|
| 寻路 / 挖矿 / 放置 | `mc-mod/.../nav/`（照 Baritone 思路重写） | Baritone |
| 任务编排 | `mc-mod/.../script/` + 插件预设 | `tasks/` + `TaskCatalogue` |
| 卡死检测 | `mc-mod/.../util/ProgressWatchdog.java` | `util/progresscheck/` |

---

## 2. 这次真正落地的：进度检测（卡死检测）

### 问题

原来的表现是**沉默地耗到超时**。一个子动作可以拿着整个预算杵在那里 ——
脚本的子动作甚至能拿到 10 分钟。从玩家角度看就是「执行到一半不动了」，
而且没有任何提示：既难排查，也白等。

### Altoclef 的做法

它有一组 `IProgressChecker`：

| 类 | 作用 |
|---|---|
| `LinearProgressChecker(timeout, minProgress)` | 把时间切成窗口，每个窗口结束时要求「进展值」至少涨了 `minProgress`，否则判失败 |
| `DistanceProgressChecker(timeout, minDistance)` | 进展值 = 离出发点的距离；可以反过来要求「距离必须缩短」 |
| `ProgressCheckerRetry(checker, attempts)` | 给上面的检查器套一层「允许重试 N 次」 |
| `MovementProgressChecker` | 组合上面几个：**在挖方块时检查挖掘进度，其余时候检查位移** |

`MovementProgressChecker` 里有两个细节是它的精华，也正是我们这次照抄的思路：

1. **两个信号**。挖矿的时候玩家本来就是站着不动的，只看「有没有位移」必然误判。
   所以在破坏方块时改用「破坏进度」这个信号。
2. **「挖到了才算数」**。它的注释写得很清楚：距离检查器要**等到真的挖掉一个方块之后**才重置，
   否则「一直在尝试挖、一直没挖掉」这种情况永远判不出失败。

### 我们的实现

`ProgressWatchdog`（纯逻辑，不碰 Minecraft，时间由调用方传入 → 可以直接单测）：

- 固定窗口（配置里的秒数 ÷ 3），窗口结束时检查「位移够不够 **或** 进展计数涨没涨」；
- 连续 3 个空窗口 → 判卡死，报「最近约 24 秒既没有移动，也没有任何进展」；
- **观测中断不算卡死**：如果两次观测之间隔了超过 2 个窗口（切出去、世界暂停、严重卡顿），
  直接把窗口挪到当下重开 —— 否则「切出去一分钟再切回来」会立刻被误杀；
- 报错文案直接给下一步：提窗口失焦、目标够不着、需要先准备工具，并建议先 `scan_blocks`。

接线方式（`Task.advance`）：

```java
protected boolean watchdogApplies() { return movement; }   // 默认只检测会驱动移动的任务
protected double realProgress()     { return -1; }         // 没有进展计数的任务只靠位移
```

**为什么不直接用 `Task.progress()`**：有些任务的 `progress()` 是**按时间**算的
（`AttackTask` 的 durationMs、`FollowTask`、长按使用），站着不动也会涨 ——
拿它当「有进展」的证据等于没检测。所以单独开了 `realProgress()`，只让真正在推进的计数进来：

| 任务 | 报的真实进展 |
|---|---|
| `MineTask` | 已挖到的方块数 |
| `CraftTask` | 已合成的个数 |
| `AttackTask` | 已打中的次数 |
| `ScriptTask` | **退出检测**（见下） |

`ScriptTask` 主动退出检测：它的子动作各自都是独立任务、各自都走 `Task.advance`，
已经被保护了；而脚本这一层「站着不动」是合法的 —— `waitUntil` 就是在等条件成立。

配置在 `config/mcai_bridge-client.toml` 的 `[limits] stuckDetectionSeconds`（默认 24，0 = 关闭）。
判太紧会误杀正常行为，所以留了开关。

---

## 2.5 抵御：把「该不该打」也交给模组

Altoclef 有 `MobDefenseChain` —— 危险时自动接管，反过来打或者跑。我们做的是它的**单次版本**：
`defend` 动作（工具 `mc_defend`）。

为什么值得做：**挨打这件事等不起一轮 LLM 思考**。等 AI 想清楚「要不要还手」，人已经被打死两次了。
所以判断必须下沉到模组里：

| 局面 | 决定 |
|---|---|
| 附近没敌人 | 收工（给了驻守点就先走回去） |
| 血 ≤ 阈值、**背包里有吃的** | 先吃（原地回血比拖着残血跑更快回到能打的状态） |
| 血 ≤ 阈值、**没有吃的** | 撤（留在原地只是等死） |
| 被 3 个以上围住、血不到一半 | 撤（硬拼基本是送） |
| 其他 | 打 |

打谁也是有讲究的，按这个顺序比：**正在打我的** > **够得着的** > 近的。
前两条都是从 Altoclef 那类「无人值守机器人」的实战经验里来的：不还手会一直挨打；
去追一个在墙后/头顶的敌人，只会被别的怪白打。

实现上它**不重写战斗细节** —— 走位、追击、攻击冷却、吃东西、换装备全都复用标准动作
（`attack` / `move_to` / `use_item` / `equip`，走同一个 `ActionExecutor.createTask`，
所以权限开关也一致）。决策本身在 `DefenseTactics` 里，是纯函数，有 32 项单测把每种局面钉死。

真机自检（`tools/live_server.py`）里会召唤 3 只僵尸来验：

```
(20/22) scan_entities → 3 只僵尸（health 17）
(21/22) defend → kills: 3, healthStart 20 → healthEnd 20, remaining []
         log: → 换上 iron_sword / → 打 僵尸 / ✓ 倒下了（累计 1/2/3）
(22/22) scan_entities → 0
```

顺带抓出两个真问题，都在同一轮修掉了：

1. **`scan_entities` 会报「血量 0 的尸体」** —— 客户端在死亡动画期间实体还在，
   不过滤的话 AI 会看到「还有 3 个僵尸」，以为没打干净，重复下指令。
2. **击杀数少报** —— 铁剑有横扫，砍一只常常把旁边两只一起带走；
   「attack 任务返回成功就 +1」会写成「清掉 1 个」，实际三只全倒。
   现在按「**看到它倒下**」统计。

**还没做的**：常驻的自动防御（现在要有人/AI 说一句才启动），以及 Altoclef 那种
「危险时抢占当前任务」的链式抢占。

---

## 3. 明确**没做**的（从仓库结构看出来的方向）

下面这些是 Altoclef 有、我们没有的。列出来是为了说清「我们知道差在哪」，
不是「已经支持」：

| Altoclef 的东西 | 我们现在的状态 |
|---|---|
| `TaskCatalogue` / `CataloguedResourceTask` / `StorageHelper`：从「我要一把石镐」自动推导依赖树（合成 → 缺材料 → 挖矿 → 缺工具 → …） | ❌ 没有。我们靠**手写预设**（`SCRIPT_PRESETS`）+ LLM 现场规划。它的目录是编译进去的静态知识，我们是运行时推理 |
| `tasks/slot/EnsureFreePlayerCraftingGridTask`：合成前先把 2x2 合成格里的残留物挪回背包 | ❌ 没有。如果上一轮合成把东西留在合成格里，我们这边会报「材料不够」，实际上材料在格子里 |
| `trackers/blacklisting/*`：把反复失败的目标（方块位置、实体）记进黑名单，避免反复撞同一面墙 | ⚠️ 部分：`MineTask` 有自己的 `failed` 集合（够不着的目标跳过），但没有跨任务、带时间的黑名单 |
| `tasks/movement/TimeoutWanderTask`：卡住时先随机走两步再重试 | ❌ 没有。我们是直接失败，把原因交给 LLM 重新规划 |
| `chains/MobDefenseChain`、`FoodChain`、`MLGBucketFallChain`：低优先级的常驻链，危险时抢占当前任务 | ⚠️ 部分：**`defend` 动作用了同一套战术判断**（打 / 吃 / 撤 / 换武器，见第 2.5 节），但它是「按指令启动的任务」，不是常驻抢占的链 —— 也就是说要有人（或 AI）说一句「去抵御」它才会开始。真正的常驻保命链还没做 |
| `chains/WorldSurvivalChain` / `BeatMinecraft2Task`（整套速通流程） | ❌ 没有 |

---

## 4. 一个定位上的差异（为什么有些东西我们**不应该**照搬）

Altoclef 是**无人值守**的：没人给它兜底，所以它的默认策略是「想尽办法自己搞定」——
超时了换个方式重试、挖不到就漫游、失败了自动降级。

我们这边是**AI 在环**：卡住这件事本身就是有价值的信息 ——
它意味着「LLM 的计划在这一步不成立」，而 LLM 有能力重新规划（换工具、先挖开、绕路、
干脆放弃）。所以我们这次选的是 **快速失败 + 把原因写清楚**，而不是无限重试：

- Altoclef：卡住 → 自己 wander 一下、重试，尽量不把它当失败；
- 我们：卡住 → 24 秒内报出来，原因和建议一起交给 LLM 决定下一步。

`TimeoutWanderTask` 那类"再试试"的兜底，对我们来说收益没那么大，
真正需要的是**别沉默**。这也是「预设 vs 资源目录」的同一个取舍：
静态目录在无人值守时是刚需，在有 LLM 的情况下，把「怎么拆解」留给模型往往更灵活。

---

## 5. 参考

- 仓库：<https://github.com/gaucho-matrero/altoclef>（MIT）
- 主要阅读的文件：
  - `util/progresscheck/IProgressChecker.java`、`LinearProgressChecker.java`、
    `DistanceProgressChecker.java`、`MovementProgressChecker.java`、`ProgressCheckerRetry.java`
  - `tasksystem/Task.java`、`TaskChain.java`、`TaskRunner.java`
  - 仓库结构（`tasks/`、`chains/`、`trackers/`、`TaskCatalogue.java`）
- 对应的自检：`tools/javatest/ProgressTest.java`（**19 项**，纯 JVM，不用开游戏）
