package com.mcai.bridge.nav;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 导航层的单元测试：假世界里跑真实的 A* 与真实的移动实现。
 *
 * <p>为什么值得写这么细：导航是整个模组里最复杂的一块，而且<b>每一类 bug 都真的发生过</b>——
 * {@code PriorityQueue} 少写比较器导致运行时崩溃；「规划说要跳、执行时没跳」导致角色掉进洞里；
 * 规划与执行的容差标准不一致导致无限重规划。这些编译期全都看不出来。</p>
 *
 * <p>能这么测的前提是导航只依赖 {@link BlockWorld} 这个窄接口，不依赖 Minecraft 的
 * {@code BlockState}（真实方块需要注册表 bootstrap，而 Forge 的网络初始化在无头 JVM 里会抛异常）。</p>
 *
 * <p>用法：{@code java -cp <classpath> com.mcai.bridge.nav.PathFinderTest}</p>
 */
public final class PathFinderTest {

    private static int passed;
    private static final List<String> FAILURES = new ArrayList<>();

    public static void main(final String[] args) {
        goalsSection();
        pathfindingSection();
        breakingAndPlacingSection();
        executionContractSection();
        costModelSection();

        System.out.println("=========================================================");
        System.out.println("通过 " + passed + " 项，失败 " + FAILURES.size() + " 项");
        for (final String failure : FAILURES) {
            System.out.println("  [FAIL] " + failure);
        }
        System.out.println("=========================================================");
        System.exit(FAILURES.isEmpty() ? 0 : 1);
    }

    // ============================================================ Goal 抽象

    private static void goalsSection() {
        System.out.println("\n-- Goal 抽象 --");

        final Goal block = Goals.block(new BlockPos(5, 65, 7));
        check(block.isInGoal(new BlockPos(5, 65, 7)), "GoalBlock：目标点本身算到达");
        check(block.isInGoal(new BlockPos(5, 66, 7)), "GoalBlock：站在目标上方也算到达");
        check(!block.isInGoal(new BlockPos(6, 65, 7)), "GoalBlock：隔壁不算到达");
        check(block.heuristic(new BlockPos(5, 65, 7)) == 0, "GoalBlock：在目标上启发式为 0");
        check(block.heuristic(new BlockPos(15, 65, 7)) > 0, "GoalBlock：离远了启发式为正");

        final Goal near = Goals.near(new BlockPos(5, 65, 7), 3);
        check(near.isInGoal(new BlockPos(8, 65, 7)), "GoalNear：半径内算到达");
        check(!near.isInGoal(new BlockPos(10, 65, 7)), "GoalNear：半径外不算到达");

        final Goal xz = Goals.xz(5, 7);
        check(xz.isInGoal(new BlockPos(5, 3, 7)), "GoalXZ：同水平坐标任意高度都算到达");
        check(!xz.isInGoal(new BlockPos(5, 65, 8)), "GoalXZ：水平不同不算到达");

        // 启发式必须不高估，否则 A* 找不到最优解
        final double scale = Goals.heuristicScale();
        check(scale > 0 && scale <= ActionCosts.WALK_ONE_BLOCK_COST,
                "启发式尺度不超过「走一格」的代价（保证不高估）",
                "scale=" + scale + " walk=" + ActionCosts.WALK_ONE_BLOCK_COST);
    }

    // ============================================================ 寻路能力

    private static void pathfindingSection() {
        System.out.println("\n-- 基础寻路 --");

        // 1) 平地直线
        {
            final FakeWorld world = flatWorld();
            final PathFinder finder = finder(world, Goals.near(new BlockPos(10, 65, 0), 1));
            final PathFinder.Path path = finder.findPath(new BlockPos(0, 65, 0));
            check(!path.isEmpty() && path.reachedGoal(), "平地：找到通往目标的路径",
                    describe(path));
            checkEndsNear(path, new BlockPos(10, 65, 0), 1, "平地：终点在目标范围内");
            check(finder.lastVisitedNodes > 0, "平地：搜索确实展开了节点",
                    "visited=" + finder.lastVisitedNodes);
        }

        // 2) 绕墙
        {
            final FakeWorld world = flatWorld();
            for (int z = -8; z <= 8; z++) {
                world.fill(5, 65, z, 5, 66, z, FakeWorld.Cell.STONE);
            }
            final PathFinder.Path path = finder(world, Goals.near(new BlockPos(10, 65, 0), 1))
                    .findPath(new BlockPos(0, 65, 0));
            check(!path.isEmpty() && path.reachedGoal(), "绕墙：找到路径", describe(path));
            final boolean through = path.positions().stream()
                    .anyMatch(p -> p.getX() == 5 && p.getZ() >= -8 && p.getZ() <= 8 && p.getY() <= 66);
            check(!through, "绕墙：路径没有穿墙", describe(path));
        }

        // 3) 完全封闭的目标（基岩，真的挖不动）
        {
            final FakeWorld world = flatWorld();
            world.fill(4, 65, 4, 6, 68, 6, FakeWorld.Cell.BEDROCK);
            final PathFinder.Path path = finder(world, Goals.block(new BlockPos(5, 65, 5)))
                    .findPath(new BlockPos(0, 65, 0));
            check(!path.reachedGoal(), "封闭目标（基岩）：明确报告「没到达」而不是假装成功", describe(path));
        }

        // 3b) 同样的盒子换成石头：允许挖掘时就应该挖进去
        {
            final FakeWorld world = flatWorld();
            world.fill(4, 65, 4, 6, 68, 6, FakeWorld.Cell.STONE);
            final CalculationContext ctx = context(world, true, false);
            final PathFinder.Path path = new PathFinder(ctx, Goals.block(new BlockPos(5, 65, 5)),
                    40000, 24).findPath(new BlockPos(0, 65, 0));
            check(path.reachedGoal(), "封闭目标（石头）：允许挖掘时能挖进去", describe(path));
        }

        // 4) 上台阶
        {
            final FakeWorld world = new FakeWorld();
            world.fill(-10, 64, -10, 4, 64, 10, FakeWorld.Cell.STONE);
            world.fill(5, 65, -10, 20, 65, 10, FakeWorld.Cell.STONE);
            final PathFinder.Path path = finder(world, Goals.near(new BlockPos(10, 66, 0), 1))
                    .findPath(new BlockPos(0, 65, 0));
            check(!path.isEmpty() && path.reachedGoal(), "上台阶：找到路径", describe(path));
            check(containsMovement(path, Movements.Ascend.class), "上台阶：路径里确实有「上台阶」这一步",
                    describe(path));
        }

        // 5) 下台阶
        {
            final FakeWorld world = new FakeWorld();
            world.fill(-10, 65, -10, 4, 65, 10, FakeWorld.Cell.STONE);
            world.fill(5, 64, -10, 20, 64, 10, FakeWorld.Cell.STONE);
            final PathFinder.Path path = finder(world, Goals.near(new BlockPos(10, 65, 0), 1))
                    .findPath(new BlockPos(0, 66, 0));
            check(!path.isEmpty() && path.reachedGoal(), "下台阶：找到路径", describe(path));
        }

        // 6) 跨一格沟（跑酷）
        {
            final FakeWorld world = flatWorld();
            world.fill(5, 64, -30, 5, 64, 30, FakeWorld.Cell.AIR);
            final PathFinder.Path path = finder(world, Goals.near(new BlockPos(10, 65, 0), 1))
                    .findPath(new BlockPos(0, 65, 0));
            check(!path.isEmpty() && path.reachedGoal(), "跨一格沟：找到路径", describe(path));
            check(containsMovement(path, Movements.Parkour.class),
                    "跨一格沟：用的是「跳过缺口」而不是走进沟里", describe(path));
        }

        // 7) 未加载区块不可通行
        {
            final FakeWorld world = flatWorld();
            // 整列未加载（从这个世界的一头到另一头），绕不过去
            for (int z = -60; z <= 60; z++) {
                world.unloadColumn(5, z);
            }
            final PathFinder.Path blocked = finder(world, Goals.near(new BlockPos(10, 65, 0), 1))
                    .findPath(new BlockPos(0, 65, 0));
            check(!blocked.reachedGoal(), "未加载区块：被当作不可通行（不会凭空穿过去）", describe(blocked));

            for (int z = -60; z <= 60; z++) {
                world.loadColumn(5, z);
            }
            final PathFinder.Path open = finder(world, Goals.near(new BlockPos(10, 65, 0), 1))
                    .findPath(new BlockPos(0, 65, 0));
            check(!open.isEmpty() && open.reachedGoal(), "未加载区块：恢复加载后又能走通", describe(open));
        }

        // 8) 岩浆不可通行
        {
            final FakeWorld world = flatWorld();
            for (int z = -60; z <= 60; z++) {
                world.fill(5, 64, z, 5, 70, z, FakeWorld.Cell.LAVA);
            }
            final PathFinder.Path path = finder(world, Goals.near(new BlockPos(10, 65, 0), 1))
                    .findPath(new BlockPos(0, 65, 0));
            check(!path.reachedGoal(), "岩浆墙：不会尝试穿过去", describe(path));
        }

        // 9) 路径健全性
        {
            final FakeWorld world = flatWorld();
            for (int z = -8; z <= 8; z++) {
                world.fill(5, 65, z, 5, 66, z, FakeWorld.Cell.STONE);
            }
            final CalculationContext ctx = context(world, true, false);
            final PathFinder.Path path = new PathFinder(ctx, Goals.near(new BlockPos(10, 65, 0), 1),
                    20000, 64).findPath(new BlockPos(0, 65, 0));

            final List<String> bad = new ArrayList<>();
            for (final Movement movement : path.movements()) {
                final BlockPos dest = movement.getDest();
                if (!Movement.canStandAt(ctx, dest.getX(), dest.getY(), dest.getZ())) {
                    bad.add(movement.name() + "->" + dest.toShortString());
                }
            }
            check(bad.isEmpty(), "路径健全性：每一步的落点都能站", String.valueOf(bad));
        }
    }

    // ================================================== 挖穿 / 垫脚（Baritone 核心能力）

    private static void breakingAndPlacingSection() {
        System.out.println("\n-- 挖穿与垫脚 --");

        // 10) 允许挖穿时能打穿一堵没有缺口的墙
        {
            final FakeWorld world = flatWorld();
            for (int z = -60; z <= 60; z++) {
                world.fill(5, 65, z, 5, 67, z, FakeWorld.Cell.STONE);
            }
            final CalculationContext ctx = context(world, true, false);
            final PathFinder.Path path = new PathFinder(ctx, Goals.near(new BlockPos(10, 65, 0), 2),
                    40000, 40).findPath(new BlockPos(0, 65, 0));

            check(path.reachedGoal(), "挖穿：允许挖掘时能打穿没缺口的墙", describe(path));
            boolean digs = false;
            for (final Movement m : path.movements()) {
                if (!m.toBreak(ctx).isEmpty()) {
                    digs = true;
                    break;
                }
            }
            check(digs, "挖穿：路径里确实包含「要先挖掉方块」的步骤", describe(path));
        }

        // 11) 不允许挖穿时同一堵墙就过不去
        {
            final FakeWorld world = flatWorld();
            for (int z = -60; z <= 60; z++) {
                world.fill(5, 65, z, 5, 67, z, FakeWorld.Cell.STONE);
            }
            final CalculationContext ctx = context(world, false, false);
            final PathFinder.Path path = new PathFinder(ctx, Goals.near(new BlockPos(10, 65, 0), 2),
                    40000, 40).findPath(new BlockPos(0, 65, 0));
            check(!path.reachedGoal(), "挖穿：关掉 allowBreak 后同一堵墙过不去", describe(path));
        }

        // 12) 挖不动的方块（基岩）挖不穿
        {
            final FakeWorld world = flatWorld();
            for (int z = -60; z <= 60; z++) {
                world.fill(5, 65, z, 5, 67, z, FakeWorld.Cell.BEDROCK);
            }
            final CalculationContext ctx = context(world, true, false);
            final PathFinder.Path path = new PathFinder(ctx, Goals.near(new BlockPos(10, 65, 0), 2),
                    40000, 40).findPath(new BlockPos(0, 65, 0));
            check(!path.reachedGoal(), "挖穿：基岩挖不动，绕不过去就如实失败", describe(path));
        }

        // 13) 垫脚上：往上两格
        {
            final FakeWorld world = flatWorld();
            final CalculationContext ctx = context(world, true, true); // 手上有方块
            final PathFinder.Path path = new PathFinder(ctx, Goals.block(new BlockPos(0, 67, 0)),
                    20000, 16).findPath(new BlockPos(0, 65, 0));
            check(path.reachedGoal(), "垫脚上：能往上爬到两格高的位置", describe(path));
            check(containsMovement(path, Movements.Pillar.class), "垫脚上：路径里用了「垫脚上」",
                    describe(path));
        }

        // 14) 手上没方块时不该规划出「垫脚上」
        {
            final FakeWorld world = flatWorld();
            final CalculationContext ctx = context(world, true, false); // hasPlaceableBlock=false
            final PathFinder.Path path = new PathFinder(ctx, Goals.block(new BlockPos(0, 67, 0)),
                    20000, 16).findPath(new BlockPos(0, 65, 0));
            check(!path.reachedGoal(), "垫脚上：手上一块方块都没有时不硬规划", describe(path));
        }

        // 15) 向下挖：从封闭的石头里挖出一条竖井
        {
            final FakeWorld world = new FakeWorld();
            world.fill(-5, 64, -5, 5, 64, 5, FakeWorld.Cell.STONE);  // 地板
            world.fill(-5, 65, -5, 5, 70, 5, FakeWorld.Cell.STONE);  // 把自己埋起来（除了站的地方）
            world.fill(0, 65, 0, 0, 66, 0, FakeWorld.Cell.AIR);
            world.fill(0, 61, 0, 0, 63, 0, FakeWorld.Cell.AIR);      // 下方有个空腔
            world.fill(0, 60, 0, 0, 60, 0, FakeWorld.Cell.STONE);

            final CalculationContext ctx = context(world, true, false);
            final PathFinder.Path path = new PathFinder(ctx, Goals.block(new BlockPos(0, 61, 0)),
                    40000, 24).findPath(new BlockPos(0, 65, 0));
            check(path.reachedGoal(), "向下挖：能挖穿地板往下走", describe(path));
            check(containsMovement(path, Movements.Downward.class), "向下挖：路径里用了「向下挖」",
                    describe(path));
        }
    }

    // ============================================== 规划与执行的接口契约
    //
    // 这一节专门盯「规划说要做什么，执行时到底做没做」。
    // 旧实现就是在这里出的事：规划出「跳过缺口」，执行时却没按跳，
    // 角色径直走进了洞里。

    private static void executionContractSection() {
        System.out.println("\n-- 规划/执行契约 --");

        final FakeWorld world = flatWorld();
        final CalculationContext ctx = context(world, true, true);
        final FakeEnv env = new FakeEnv(0.5, 65.0, 0.5);

        // Traverse：向前走
        {
            final Movement m = new Movements.Traverse(0, 65, 0, 1, 0);
            final MovementState state = new MovementState()
                    .setStatus(MovementState.Status.PREPPING);
            m.updateState(state, env, ctx);
            check(state.isForward(), "Traverse：执行时按了「前进」", state.toString());
            check(state.getYaw() != null, "Traverse：执行时设定了朝向", state.toString());
        }

        // Ascend：向前走且要跳
        {
            final FakeWorld w2 = new FakeWorld();
            w2.fill(-5, 64, -5, 5, 64, 5, FakeWorld.Cell.STONE);
            w2.fill(1, 65, -5, 8, 65, 5, FakeWorld.Cell.STONE);
            final CalculationContext c2 = context(w2, true, false);
            final Movement m = new Movements.Ascend(0, 65, 0, 1, 0);
            final MovementState state = new MovementState().setStatus(MovementState.Status.PREPPING);
            m.updateState(state, new FakeEnv(0.5, 65.0, 0.5), c2);
            check(state.isForward() && state.isJump(),
                    "Ascend：执行时既要前进也要跳", state.toString());
        }

        // Parkour：必须按跳（这正是旧实现踩过的坑）
        {
            final Movement m = new Movements.Parkour(0, 65, 0, 2, 65, 0, 2);
            final MovementState state = new MovementState().setStatus(MovementState.Status.PREPPING);
            m.updateState(state, new FakeEnv(0.5, 65.0, 0.5), ctx);
            check(state.isJump(), "Parkour：执行时按了跳（旧实现就是漏了这个）", state.toString());
            check(state.isForward(), "Parkour：执行时同时保持前进", state.toString());
        }

        // Pillar：要往脚下放方块
        {
            final Movement m = new Movements.Pillar(0, 65, 0);
            final MovementState state = new MovementState().setStatus(MovementState.Status.PREPPING);
            m.updateState(state, new FakeEnv(0.5, 65.0, 0.5), ctx);
            check(state.getPlaceTarget() != null, "Pillar：执行时要求放置方块",
                    state.toString());
            check(state.getPlaceTarget() != null
                            && state.getPlaceTarget().equals(new BlockPos(0, 65, 0)),
                    "Pillar：放置位置正是自己脚下那一格", state.toString());
        }

        // Downward：要挖脚下的方块
        {
            final FakeWorld w3 = new FakeWorld();
            w3.fill(-5, 60, -5, 5, 64, 5, FakeWorld.Cell.STONE);
            w3.fill(0, 65, 0, 0, 66, 0, FakeWorld.Cell.AIR);
            final CalculationContext c3 = context(w3, true, false);
            final Movement m = new Movements.Downward(0, 65, 0, 1);
            final MovementState state = new MovementState().setStatus(MovementState.Status.PREPPING);
            m.updateState(state, new FakeEnv(0.5, 65.0, 0.5), c3);
            check(state.getBreakTarget() != null, "Downward：执行时要求挖掘方块", state.toString());
        }

        // 需要先挖掉挡路方块时，第一步必须是「去挖」而不是「往前走」
        {
            final FakeWorld w4 = new FakeWorld();
            w4.fill(-5, 64, -5, 5, 64, 5, FakeWorld.Cell.STONE);
            w4.fill(1, 65, 0, 1, 66, 0, FakeWorld.Cell.STONE); // 正前方一堵墙
            final CalculationContext c4 = context(w4, true, false);
            final Movement m = new Movements.Traverse(0, 65, 0, 1, 0);
            final MovementState state = new MovementState().setStatus(MovementState.Status.PREPPING);
            m.updateState(state, new FakeEnv(0.5, 65.0, 0.5), c4);
            check(state.getBreakTarget() != null && state.getStatus() == MovementState.Status.PREPPING,
                    "挡路时：先挖，而不是硬往前走", state.toString());
        }
    }

    // ============================================================ 代价模型

    private static void costModelSection() {
        System.out.println("\n-- 代价模型 --");

        final FakeWorld world = flatWorld();
        world.fill(0, 64, 0, 0, 64, 0, FakeWorld.Cell.STONE);
        final CalculationContext ctx = context(world, true, true);

        final double traverse = new Movements.Traverse(0, 65, 0, 1, 0).getCost(ctx);
        final double ascend = new Movements.Ascend(0, 65, 0, 1, 0).getCost(ctx);
        final double parkour = new Movements.Parkour(0, 65, 0, 2, 65, 0, 2).getCost(ctx);
        final double pillar = new Movements.Pillar(0, 65, 0).getCost(ctx);

        check(traverse > 0 && traverse < ActionCosts.COST_INF, "代价：走一格是个有限正值",
                "traverse=" + round(traverse));
        check(traverse < ascend, "代价：上台阶比平走贵", "traverse=" + round(traverse) + " ascend=" + round(ascend));
        check(traverse < parkour, "代价：跳缺口比平走贵", "traverse=" + round(traverse) + " parkour=" + round(parkour));
        check(traverse < pillar, "代价：垫脚上比平走贵", "traverse=" + round(traverse) + " pillar=" + round(pillar));

        // 挖穿一堵硬墙必须明显比绕路贵，否则 A* 会到处乱挖
        final FakeWorld hard = flatWorld();
        hard.fill(1, 65, 0, 1, 66, 0, FakeWorld.Cell.STONE);
        final double digThrough = new Movements.Traverse(0, 65, 0, 1, 0)
                .getCost(context(hard, true, false));
        check(digThrough > traverse * 3, "代价：要挖墙的直走明显比空手走贵",
                "dig=" + round(digThrough) + " plain=" + round(traverse));

        check(!Double.isFinite(ActionCosts.COST_INF) == false && ActionCosts.COST_INF > 100000,
                "代价：COST_INF 足够大，不会被正常代价淹没", "COST_INF=" + ActionCosts.COST_INF);
    }

    // ---------------------------------------------------------------- 辅助

    private static CalculationContext context(final FakeWorld world, final boolean allowBreak,
                                              final boolean hasBlocks) {
        return new CalculationContext(world, allowBreak, true, true, true,
                PathFinder.MAX_FALL, ActionCosts.WALK_ONE_BLOCK_COST * 8, hasBlocks);
    }

    private static PathFinder finder(final FakeWorld world, final Goal goal) {
        return new PathFinder(context(world, true, false), goal, 40000, 64);
    }

    private static FakeWorld flatWorld() {
        final FakeWorld world = new FakeWorld();
        world.fill(-60, 64, -60, 60, 64, 60, FakeWorld.Cell.STONE);
        return world;
    }

    private static boolean containsMovement(final PathFinder.Path path,
                                            final Class<? extends Movement> type) {
        for (final Movement movement : path.movements()) {
            if (type.isInstance(movement)) {
                return true;
            }
        }
        return false;
    }

    private static void checkEndsNear(final PathFinder.Path path, final BlockPos goal,
                                      final int radius, final String name) {
        if (path.isEmpty()) {
            fail(name, "路径为空");
            return;
        }
        final BlockPos end = path.end();
        check(Goals.near(goal, radius).isInGoal(end), name, "end=" + end + " goal=" + goal);
    }

    private static String describe(final PathFinder.Path path) {
        if (path.isEmpty()) {
            return "空路径";
        }
        final StringBuilder sb = new StringBuilder();
        sb.append(path.length()).append(" 步[");
        for (int i = 0; i < Math.min(path.length(), 8); i++) {
            if (i > 0) {
                sb.append(" → ");
            }
            sb.append(path.movements().get(i).name());
        }
        if (path.length() > 8) {
            sb.append(" …");
        }
        sb.append("] 到达=").append(path.reachedGoal());
        return sb.toString();
    }

    private static double round(final double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static void check(final boolean condition, final String name) {
        check(condition, name, "");
    }

    private static void check(final boolean condition, final String name, final String detail) {
        if (condition) {
            passed++;
            System.out.println("  [PASS] " + name);
        } else {
            fail(name, detail);
        }
    }

    private static void fail(final String name, final String detail) {
        FAILURES.add(name + "  " + detail);
        System.out.println("  [FAIL] " + name + "  " + detail);
    }

    // ------------------------------------------------------------ 假世界

    /** 用内存网格拼出来的假方块世界，完全不依赖 Minecraft 运行时。 */
    static final class FakeWorld implements BlockWorld {

        enum Cell {
            AIR, STONE, BEDROCK, WATER, LAVA, UNLOADED
        }

        private final Map<Long, Cell> cells = new HashMap<>();
        /** 被标记为未加载的整列（x,z）。真实客户端里未加载区块读出来永远是空气，必须整体拦住。 */
        private final java.util.Set<Long> unloadedColumns = new java.util.HashSet<>();

        void set(final int x, final int y, final int z, final Cell cell) {
            cells.put(new BlockPos(x, y, z).asLong(), cell);
        }

        void unloadColumn(final int x, final int z) {
            unloadedColumns.add((((long) x) << 32) ^ (z & 0xFFFFFFFFL));
        }

        void loadColumn(final int x, final int z) {
            unloadedColumns.remove((((long) x) << 32) ^ (z & 0xFFFFFFFFL));
        }

        void fill(final int x1, final int y1, final int z1,
                  final int x2, final int y2, final int z2, final Cell cell) {
            for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
                for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
                    for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
                        set(x, y, z, cell);
                    }
                }
            }
        }

        private Cell cellAt(final int x, final int y, final int z) {
            return cells.getOrDefault(new BlockPos(x, y, z).asLong(), Cell.AIR);
        }

        @Override
        public boolean isPassable(final int x, final int y, final int z) {
            return switch (cellAt(x, y, z)) {
                case AIR, WATER -> true;
                case STONE, BEDROCK, LAVA, UNLOADED -> false;
            };
        }

        @Override
        public boolean isWalkOn(final int x, final int y, final int z) {
            return switch (cellAt(x, y, z)) {
                case STONE, BEDROCK -> true;
                default -> false;
            };
        }

        @Override
        public boolean isDangerous(final int x, final int y, final int z) {
            return cellAt(x, y, z) == Cell.LAVA;
        }

        @Override
        public boolean isWater(final int x, final int y, final int z) {
            return cellAt(x, y, z) == Cell.WATER;
        }

        @Override
        public boolean isClimbable(final int x, final int y, final int z) {
            return false;
        }

        @Override
        public boolean isReplaceable(final int x, final int y, final int z) {
            final Cell cell = cellAt(x, y, z);
            return cell == Cell.AIR || cell == Cell.WATER;
        }

        @Override
        public double miningCost(final int x, final int y, final int z) {
            return switch (cellAt(x, y, z)) {
                case AIR, WATER -> 0;
                case STONE -> 30.0;
                case BEDROCK, UNLOADED, LAVA -> ActionCosts.COST_INF;
            };
        }

        @Override
        public boolean isLoaded(final int x, final int z) {
            return !unloadedColumns.contains((((long) x) << 32) ^ (z & 0xFFFFFFFFL));
        }

        @Override
        public int minBuildHeight() {
            return 0;
        }

        @Override
        public int maxBuildHeight() {
            return 128;
        }
    }

    /** 假的玩家状态，用来验证「移动会要求执行器做什么」。 */
    static final class FakeEnv implements Movement.ExecEnv {
        private final double x;
        private final double y;
        private final double z;

        FakeEnv(final double x, final double y, final double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public double x() {
            return x;
        }

        @Override
        public double y() {
            return y;
        }

        @Override
        public double z() {
            return z;
        }

        @Override
        public double eyeY() {
            return y + 1.62;
        }

        @Override
        public float yaw() {
            return 0;
        }

        @Override
        public float pitch() {
            return 0;
        }

        @Override
        public boolean onGround() {
            return true;
        }

        @Override
        public boolean inWater() {
            return false;
        }

        @Override
        public boolean isLookingAt(final BlockPos pos) {
            return true; // 测试里当作已经对准，好让它进入正式移动阶段
        }

        @Override
        public boolean canReach(final BlockPos pos) {
            return true;
        }
    }

    private PathFinderTest() {
    }
}
