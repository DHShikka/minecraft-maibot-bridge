# 导航层：向 Baritone 借鉴了什么

这份文档记录本项目导航层参照 [Baritone](https://github.com/cabaletta/baritone)
所做的重新设计，以及**边界在哪里**。

---

## 0. 先说许可证

| | |
|---|---|
| Baritone | **LGPL-3.0** |
| 本项目（模组） | **MIT** |

**本项目没有拷贝 Baritone 的任何代码。** 借鉴的是它的**架构与算法思路** ——
「用多 tick 的移动作为 A\* 的边」「代价统一换算成 tick」「执行期用合法位置集合做回溯」
这类设计属于方法与思想。所有实现都是照着 Minecraft 1.20.1 的官方映射从零写的，
变量命名、类结构、接口划分都是按本项目的需要重新定的。

阅读 Baritone 源码只是为了**确认设计意图**（例如「为什么代价的单位必须是 tick」
「MovementParkour 的跨度上限为什么是 4」），而不是抄写实现。

---

## 0.5 联动真 Baritone：走聊天指令，不走它的 API

上面说的都是「借鉴思路自己实现」。但玩家 mods 目录里真的放了 `baritone.jar` 时，
**能直接用它** —— 工具 `mc_baritone` 就是这么做的。

**为什么不调它的 API**：手上那个 jar 是**混淆过的**（`baritone/api/` 只剩 4 个类，
没有 `IBaritone`、没有 `Settings`、没有 `process/*`），拿不到稳定入口。
而 Baritone 拦截**聊天消息**（前缀 `#`）这个行为是长期稳定的接口。

于是整条链路是：

```
mc_baritone(action="goto", target="iron_ore")
   → 插件拼指令 "#goto iron_ore"
   → 走 chat 动作（不是 command！Baritone 只拦聊天，/goto 不会生效）
   → 游戏里发出去 → Baritone 自己开始干活
```

这样做的好处是**零依赖**：没装 Baritone 时这些消息就只是普通聊天，不会崩、
不会报 ClassNotFound；装了就能用。代价是**拿不到它的进度**（它不回话），
所以「挖够了没有」只能靠我们盯背包（`mine` 就是每 3 秒数一次背包里的量）。

### 两个真机踩出来的坑

1. **`#mine` 的数量在方块名前面**：`#mine 64 dirt`，不是 `#mine dirt 64`。
   写反了 Baritone 会报 `Error at argument #2: Expected w` —— 它把 `64` 当成又一个方块名了。
2. **`#goto` 只给 x/z 时不要硬塞 y**：`#goto 100 -200` 是合法的（它自己找高度），
   `#goto 100 64 -200` 也行。但别把 `y` 填成 0 冒充。

| action | 拼出来的指令 | 说明 |
|---|---|---|
| `goto` | `#goto <x> <y> <z>` / `#goto <x> <z>` / `#goto <方块名>` | 坐标或「走到某种方块」 |
| `mine` | `#mine <数量> <方块>` / `#mine <方块>` | 数量在前；支持 `#minecraft:logs` 这种标签 |
| `explore` | `#explore` | 自己往外探图（找岩浆湖、找结构） |
| `tunnel` | `#tunnel <高度>` | 往前挖隧道 |
| `come` / `follow` | `#come` / `#follow player <名字>` | 过来 / 跟着 |
| `thisway` | `#thisway <格数>` | 朝当前朝向走 N 格 |
| `build` | `#build <schematic>` | 按图纸建造（图纸要放进 Baritone 的 schematics 目录） |
| `stop` | `#stop` | 停下它的一切动作 |

### 任务状态要和它同步（`BaritoneWatcher`）

交给 Baritone 之后，**干活的是它，我们的动作队列里什么都没有** ——
于是 `task_status` 老老实实报「空闲」，麦麦就以为没人在做事：可能重复下发一遍，
或者在它还在挖的时候改主意。所以模组里有个 `BaritoneWatcher` 把它的活动并进状态：

```json
"baritone": {
  "command": "#mine 8 iron_ore", "running": false, "elapsedMs": 63277,
  "idleMs": 9900, "moving": false, "everMoved": true,
  "note": "已经 5 秒没有任何动静：大概干完了，或者卡住了（挖矿类可以用 mc_inventory 看数量确认）"
}
```

它是**看行为**推出来的，不是查 Baritone 的内部状态（那个 jar 混淆得只剩
`IBaritoneProvider.a()`，反射拿不到东西）：

- 记下每一条发出去的 `#` 指令；
- 之后盯玩家的位移、是不是在挖方块（`isDestroying`）、有没有挥手臂；
- **5 秒**没有任何动静就认为它停了 —— 这个数字是照着真机调的：
  `#mine 8 iron_ore` 全程 63 秒，中间一直有位移或挖掘动作。

顺手还做了两件事：

- **`stop` 会把 Baritone 一起刹住**：只清自己的队列会出现「麦麦说停了，人还在满地图挖」。
- **Baritone 的错误回话进状态**：它报 `[Baritone] Error at argument #2: Expected w`
  这种话会被 `reply` 字段带上来 —— 这正是麦麦最需要看到的东西。

---

## 1. 旧实现的问题

原来的导航是「路点 + 通用跟随器」：

```
A*  →  一串 BlockPos 路点  →  Navigator 朝路点走，遇到特殊情况加启发式补救
```

规划出来的信息在交给执行时**丢掉了**。执行器只看到一个两格外的坐标，
不知道「为什么是这个坐标」。于是只能猜：

- 前方站不住 → 跳一下试试
- 连续 N tick 没位移 → 判定卡住 → 原地跳、左右晃、重算路径
- 规划说「这里是一格缺口要跳过去」→ 执行器不知道要跳 → **角色直接走进洞里**

最后一条是真实发生过的 bug：规划的跳跃逻辑和执行逻辑各自为政，
测试的时候才发现「规划器认为该跳、执行器根本没按跳」。

---

## 2. 新架构

```
Goal  ──┐
        ├─→  PathFinder ──→ Path(List<Movement>) ──→ PathExecutor
BlockWorld ─→ CalculationContext ─┘                        │
   (真实 MC / 测试假世界)                          InputController + 挖掘 + 放置
```

| 组件 | 职责 | 对应 Baritone |
|---|---|---|
| `Goal` / `Goals` | 目标抽象：算不算到了 + 还要多久 | `baritone.api.pathing.goals.Goal` 系列 |
| `BlockWorld` | 方块世界的最小接口（可穿过 / 可站立 / 危险 / 可挖…） | `BlockStateInterface` + `MovementHelper` 的合并 |
| `CalculationContext` | 带缓存的世界视图 + 本次允许的能力 | `CalculationContext` |
| `ActionCosts` | 代价常量，单位是 **tick** | `ActionCosts` |
| `Movement` | 一次多 tick 动作：自己知道代价，也知道怎么执行 | `Movement` / `IMovement` |
| `Movements.*` | 具体走法 | `MovementTraverse` / `Ascend` / `Descend` / `Fall` / `Diagonal` / `Parkour` / `Downward` / `Pillar` |
| `PathFinder` | A\* over movements | `AbstractNodeCostSearch` + `AStarPathFinder` |
| `PathExecutor` | 逐步执行 + **回溯** | `PathExecutor` |
| `Navigator` | 门面，对外 API 不变 | `PathingBehavior` |

---

## 3. 关键设计：为什么「边」是移动而不是坐标

A\* 的每个节点仍然是一个方块坐标，但**边是一个完整的 `Movement`**：

```java
public abstract class Movement {
    public abstract double calculateCost(CalculationContext ctx);  // 单位：tick
    public final Set<Long> getValidPositions();                    // 执行期间"还在这步上"的格子
    public abstract MovementState updateState(state, env, ctx);    // 这一 tick 按什么键
}
```

好处是**规划与执行不再有信息断层**：A\* 选出来的路径就是一串移动，
执行器按顺序调用它们，不需要再猜任何东西。`getValidPositions()` 则让
「走偏了」变成可以精确检测的事件 —— 玩家跑出这个集合就回退一步重试，
而不是靠「连续没位移」去猜。

## 4. 代价模型

代价单位统一是 **tick**（「做这件事要花几帧」）。这样 A\* 才能在同一把尺子上比较
「绕路 10 格」和「挖穿 1 格石头」。

| 动作 | 代价 | 依据 |
|---|---|---|
| 走一格 | `20/4.317 ≈ 4.63` | 走路速度 4.317 格/秒 |
| 疾跑走一格 | 上面的 × 0.6 | 疾跑约 5.612 格/秒 |
| 上台阶 | `max(跳, 走) + 落地走位` | 起跳要额外时间 |
| 下落 N 格 | `走离边缘 + N×2 + 落地走位` | 重力换算 |
| 跳过缺口 | `跳 + 走 × 跨度` | 跨度 4 格必须疾跑 |
| 挖 1 格石头 | 硬度 × 10 ≈ 15 | 原版公式 `hardness×1.5/工具速度` |
| 放一个方块 | 20 | 切物品 + 瞄准 + 右键 |

这些都是**从游戏物理直接算出来的量**，不是随手拍的数字 —— 好处是
A\* 的取舍会自动贴近真人直觉：挖穿一格石头（15 tick）相当于走 5 格路，
所以绕路 5 格以内它就不挖；要绕 20 格它就会直接挖过去。

## 5. 新增的走法（旧实现完全没有）

| 走法 | 作用 | 旧实现 |
|---|---|---|
| `Downward` | 向下挖穿地板，落到实地上（挖矿道、下矿洞） | ❌ 只能绕路 |
| `Pillar` | 往脚下垫方块跳上去（翻 1~2 格高的墙） | ❌ 完全没有 |
| 带挖掘的 `Traverse` / `Ascend` | 打穿挡路的方块继续走 | ❌ 报「找不到路径」 |
| `Parkour` | 跳过 1~2 格宽的缺口 | ⚠️ 有规划没执行（会掉洞里） |

`Pillar` 有一个细节值得记一笔：垫脚跳的**物理顺序**是「跳起来 + 在空中往脚下放方块」，
而不是「先放好再跳」。这一点和 Baritone 的 `prepared()` 流程相反，所以 `Pillar`
刻意绕过了通用的「先挖/先放」准备阶段，自己控制顺序。

## 6. 执行期的回溯

```java
// 每 tick 检查玩家还在不在这一步的合法位置上
if (!current.getValidPositions().contains(player.blockPosition())) {
    offPathTicks++;
    if (offPathTicks > 10) {
        backtrack();   // index--，重跑上一步
    }
}
```

回退预算 = `max(6, 路径长度/2)`。预算耗尽才判定这条路走不通，交给上层重规划。

## 7. 可达性判定

- **未加载区块一律视为不可通行。** 客户端在未加载区块上读到的永远是空气，
  不拦住的话 A\* 会「规划」出一条穿过未知地形的漂亮路线，实走时莫名其妙卡住。
- **危险方块**（岩浆、火、仙人掌、甜浆果、粉雪…）既不能走也不能站。
- **挖不动的方块**（基岩等硬度为负）代价为 `COST_INF`，等价于墙。
- **手上有方块才允许「垫脚上」**。这个约束是测试抓出来的：原来只判了
  `allowPlace`，结果背包里一块方块都没有时，A\* 照样规划出垫脚路线，执行时才发现放不了。

## 8. 目标不可达时的行为

不再是一句干巴巴的「找不到路径」，而是**退而求其次返回「能走到的最接近目标的路径」**，
并在 `Path.reachedGoal` 里标出来。上层因此可以说：

> 已经重规划 10 次仍没能到达 (100, 40, -300)，最后停在 (95, 40, -298)。
> 目标可能被完全封死（需要挖穿或搭桥），也可能需要先准备方块。

## 9. 测试

`tools/javatest/PathFinderTest.java` —— **50 项**，全部在纯 JVM 里跑（不需要开游戏）：

| 分组 | 覆盖 |
|---|---|
| Goal 抽象 | 三种目标、启发式不高估 |
| 基础寻路 | 平地 / 绕墙 / 封闭 / 上下台阶 / 跨沟 / 未加载 / 岩浆 / 路径健全性 |
| 挖穿与垫脚 | 打穿无缺口墙 / 关掉 allowBreak 就过不去 / 基岩挖不动 / 垫脚上 / 手上没方块不硬规划 / 向下挖 |
| **规划-执行契约** | 每种走法执行时是否真的按了对的键（**旧实现就是这里出的 bug**） |
| 代价模型 | 平走 < 上台阶 < 跳缺口 < 垫脚；挖墙明显比空手贵 |

能这么测的前提是导航只依赖 `BlockWorld` 这个窄接口。真实方块状态需要注册表完成
bootstrap，而 Forge 的网络初始化在无头 JVM 里会抛异常 —— 所以「拿真方块做单测」这条路走不通，
必须做这层抽象。

## 10. 没有照搬的部分

Baritone 有 ~5 万行，本项目只取了最核心的骨架。**明确没做**的：

| Baritone 的能力 | 本项目 |
|---|---|
| 沿梯子/藤蔓攀爬 | ❌ 未实现（`isClimbable` 接口留了，但没有对应走法） |
| 船 / 矿车 / 鞘翅 / 马匹 | ❌ 未实现 |
| 水桶落地（MLG） | ❌ 未实现 |
| `BuilderProcess`（按 schematic 建筑） | ❌ 未实现 |
| `MineProcess`（按 ore 列表批量采矿） | ⚠️ 部分：`MineTask` 是一层层扫方块，不是 Baritone 的 ore 定位 |
| 分区并行搜索、`SplicedPath` | ❌ 未实现（单线程 A\* + 节点上限） |
| 45° 斜向优化、`Favoring` | ❌ 未实现 |

这些是明确的后续方向，不是「已经支持」。

不过要注意上面这张表说的是**我们自己写的那套导航层**。装了真 Baritone 的时候，
`build`（按 schematic 建造）、批量找矿这些能力**可以直接用它**（`mc_baritone`），
我们不需要自己实现 —— 见 [0.5 节](#05-联动真-baritone走聊天指令不走它的-api)。

---

## 11. 参考

- Baritone 仓库：<https://github.com/cabaletta/baritone>
- 阅读的分支：`1.20.1`（与本项目的 MC 版本一致）
- 主要参考的文件：`Movement.java`、`MovementTraverse/Ascend/Descend/Fall/Diagonal/Parkour.java`、
  `MovementHelper.java`、`CalculationContext.java`、`AbstractNodeCostSearch.java`、
  `PathExecutor.java`、`BlockStateInterface.java`、`ActionCosts.java`、
  `baritone/api/pathing/goals/Goal*.java`

> 任务层（脚本、卡死检测）参考的是另一个项目 **Altoclef**，见 [ALTOCLEF.md](ALTOCLEF.md)。
> 它是 MIT（本项目也是 MIT），所以那篇的边界和这篇不一样，别混着看。
