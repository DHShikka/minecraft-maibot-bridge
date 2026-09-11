package com.mcai.bridge.nav;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * 基于<b>移动</b>的 A* 寻路。
 *
 * <p>对应 Baritone 的 {@code AbstractNodeCostSearch} + {@code AStarPathFinder}
 * （<b>重新实现，未拷贝代码</b>）。</p>
 *
 * <h2>与旧实现的区别</h2>
 *
 * <p>旧实现：节点是坐标，边是「单 tick 的一小步」，代价是启发式拍出来的常数；
 * 路径是一串坐标，执行时由另一个模块去猜怎么走。</p>
 *
 * <p>新实现：节点是坐标，<b>边是一个完整的 {@link Movement}</b>（可能持续十几 tick），
 * 代价由动作本身按游戏物理算出来；路径是一串移动，
 * 每个移动自己知道该怎么执行。规划与执行之间不再有信息断层。</p>
 *
 * <h2>目标不可达时的行为</h2>
 *
 * <p>会退而求其次返回「能走到的最接近目标的路径」，并在 {@link Path#reachedGoal} 里标出来。
 * 这样上层可以说「我只能走到某某位置，剩下的过不去」，而不是干巴巴一句「找不到路径」。</p>
 */
public final class PathFinder {

    /** 允许的最大下落格数。 */
    public static final int MAX_FALL = 4;
    /** 允许的最大跑酷跨度（格）。 */
    public static final int MAX_PARKOUR = 4;

    private static final int[][] ORTHO = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int[][] DIAG = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    /**
     * 一条算好的路径：一串移动。
     */
    public record Path(List<Movement> movements, boolean reachedGoal, double cost) {

        public boolean isEmpty() {
            return movements.isEmpty();
        }

        public int length() {
            return movements.size();
        }

        /** 路径经过的坐标序列（每一步的终点），用于显示与日志。 */
        public List<BlockPos> positions() {
            final List<BlockPos> out = new ArrayList<>(movements.size() + 1);
            if (!movements.isEmpty()) {
                out.add(movements.get(0).getSrc());
            }
            for (final Movement movement : movements) {
                out.add(movement.getDest());
            }
            return out;
        }

        public BlockPos end() {
            return movements.isEmpty() ? null : movements.get(movements.size() - 1).getDest();
        }

        public static Path empty() {
            return new Path(List.of(), false, 0);
        }
    }

    private final CalculationContext context;
    private final Goal goal;
    private final int maxNodes;
    private final int maxRange;

    // ---- A* 工作状态（每次查询清空）----
    /** 位置 → 从起点走到这里的已知最小代价。 */
    private final Map<Long, Double> gScore = new HashMap<>();
    /** 位置 → 是哪一步移动走到这里的（回溯路径用）。 */
    private final Map<Long, Movement> cameFrom = new HashMap<>();
    private final Set<Long> closed = new HashSet<>();
    /**
     * 待展开队列，按 f = g + h 排序。
     *
     * <p>元素是<b>不可变</b>快照，比较器显式给定。旧实现这两点都踩过坑：
     * 没给比较器 → 运行时 ClassCastException；入队后又改元素 → 堆序被破坏。</p>
     */
    private final PriorityQueue<PathNode> openQueue =
            new PriorityQueue<>(Comparator.comparingDouble(n -> n.f));

    /** 上次搜索展开的节点数，调试用。 */
    public int lastVisitedNodes;
    /** 上次搜索生成了多少个候选移动，用来观察分支因子。 */
    public int lastGeneratedMoves;

    public PathFinder(final CalculationContext context, final Goal goal,
                      final int maxNodes, final int maxRange) {
        this.context = context;
        this.goal = goal;
        this.maxNodes = maxNodes;
        this.maxRange = maxRange;
    }

    public Goal goal() {
        return goal;
    }

    /**
     * 从 {@code start} 开始搜索。
     *
     * @return 路径；完全找不到任何前进方向时返回空路径
     */
    public Path findPath(final BlockPos start) {
        gScore.clear();
        cameFrom.clear();
        closed.clear();
        openQueue.clear();
        lastVisitedNodes = 0;
        lastGeneratedMoves = 0;

        final BlockPos startNode = normalizeStart(start);
        if (startNode == null) {
            return Path.empty();
        }
        if (goal.isInGoal(startNode)) {
            return new Path(List.of(), true, 0);
        }

        // 目标可能只是一个 XZ 约束，没有具体坐标；用启发式搜索会自然收敛，所以只按起点外扩一圈
        final int boundX1 = startNode.getX() - maxRange;
        final int boundX2 = startNode.getX() + maxRange;
        final int boundZ1 = startNode.getZ() - maxRange;
        final int boundZ2 = startNode.getZ() + maxRange;
        final int minY = Math.max(context.minBuildHeight(), startNode.getY() - 48);
        final int maxY = Math.min(context.maxBuildHeight() - 2, startNode.getY() + 48);

        final long startKey = startNode.asLong();
        gScore.put(startKey, 0.0);
        openQueue.add(new PathNode(startNode, 0.0, goal.heuristic(startNode)));

        int visited = 0;
        long bestKey = startKey;
        double bestHeuristic = goal.heuristic(startNode);
        boolean reached = false;

        final List<Movement> candidates = new ArrayList<>(64);

        while (!openQueue.isEmpty() && visited < maxNodes) {
            final PathNode current = openQueue.poll();
            final long currentKey = current.pos.asLong();
            if (!closed.add(currentKey)) {
                continue; // 过期条目
            }
            visited++;

            if (goal.isInGoal(current.pos)) {
                reached = true;
                bestKey = currentKey;
                break;
            }
            final double h = goal.heuristic(current.pos);
            if (h < bestHeuristic) {
                bestHeuristic = h;
                bestKey = currentKey;
            }

            final double currentG = gScore.getOrDefault(currentKey, Double.MAX_VALUE);

            candidates.clear();
            generateMoves(current.pos.getX(), current.pos.getY(), current.pos.getZ(),
                    boundX1, boundX2, boundZ1, boundZ2, minY, maxY, candidates);
            lastGeneratedMoves += candidates.size();

            for (final Movement movement : candidates) {
                final double stepCost = movement.getCost(context);
                if (stepCost >= ActionCosts.COST_INF) {
                    continue;
                }
                final BlockPos dest = movement.getDest();
                final long destKey = dest.asLong();
                if (closed.contains(destKey)) {
                    continue;
                }
                final double tentative = currentG + stepCost;
                final Double known = gScore.get(destKey);
                if (known != null && known <= tentative) {
                    continue;
                }
                gScore.put(destKey, tentative);
                cameFrom.put(destKey, movement);
                openQueue.add(new PathNode(dest, tentative, goal.heuristic(dest)));
            }
        }

        lastVisitedNodes = visited;

        if (!reached && bestKey == startKey) {
            return Path.empty();
        }
        final List<Movement> path = reconstruct(startKey, bestKey);
        return new Path(path, reached, gScore.getOrDefault(bestKey, 0.0));
    }

    /** 起点可能不合法（悬空、卡在方块里），就近落到可站立的格子。 */
    private BlockPos normalizeStart(final BlockPos start) {
        if (Movement.canStandAt(context, start.getX(), start.getY(), start.getZ())) {
            return start;
        }
        for (int dy = 0; dy <= 3; dy++) {
            final int y = start.getY() - dy;
            if (Movement.canStandAt(context, start.getX(), y, start.getZ())) {
                return new BlockPos(start.getX(), y, start.getZ());
            }
        }
        for (int dy = 1; dy <= 3; dy++) {
            final int y = start.getY() + dy;
            if (Movement.canStandAt(context, start.getX(), y, start.getZ())) {
                return new BlockPos(start.getX(), y, start.getZ());
            }
        }
        // 实在找不到也返回原点：邻居生成会自然给出很少的候选，搜索会很快结束
        return start;
    }

    /** 回溯出一条移动序列。 */
    private List<Movement> reconstruct(final long startKey, final long endKey) {
        final List<Movement> reversed = new ArrayList<>();
        long cursor = endKey;
        int guard = 0;
        while (cursor != startKey && guard++ < 100_000) {
            final Movement movement = cameFrom.get(cursor);
            if (movement == null) {
                break;
            }
            reversed.add(movement);
            cursor = movement.getSrc().asLong();
        }
        Collections.reverse(reversed);
        return reversed;
    }

    // ==================================================== 候选移动的生成
    //
    // 这里刻意「宽松」：只要结构上说得通就生成候选，具体合不合法交给每个移动自己的
    // calculateCost 去判（返回 COST_INF 就被跳过）。把校验只写在一个地方，
    // 避免「生成器认为可以、移动认为不可以」这种两边判断不一致的经典 bug。

    private void generateMoves(final int x, final int y, final int z,
                               final int bx1, final int bx2, final int bz1, final int bz2,
                               final int minY, final int maxY, final List<Movement> out) {

        // ---- 四向：平移 / 上台阶 / 下台阶 / 下落 ----
        for (final int[] dir : ORTHO) {
            final int nx = x + dir[0];
            final int nz = z + dir[1];
            if (nx < bx1 || nx > bx2 || nz < bz1 || nz > bz2) {
                continue;
            }
            out.add(new Movements.Traverse(x, y, z, nx, nz));
            out.add(new Movements.Ascend(x, y, z, nx, nz));

            // 往下找落点：一路到第一块实心为止
            for (int drop = 1; drop <= MAX_FALL; drop++) {
                final int ny = y - drop;
                if (ny < minY) {
                    break;
                }
                final CalculationContext.Info mid = context.get(nx, ny + 1, nz);
                if (!mid.loaded() || !mid.passable() || mid.dangerous()) {
                    break; // 掉不过去
                }
                out.add(drop == 1
                        ? new Movements.Descend(x, y, z, nx, nz, ny)
                        : new Movements.Fall(x, y, z, nx, ny, nz));
                if (context.canWalkOn(nx, ny - 1, nz)) {
                    break; // 找到落脚点了，再往下没意义
                }
            }

            // 跑酷
            for (int dist = 2; dist <= MAX_PARKOUR; dist++) {
                final int px = x + dir[0] * dist;
                final int pz = z + dir[1] * dist;
                if (px < bx1 || px > bx2 || pz < bz1 || pz > bz2) {
                    continue;
                }
                for (int dy = 0; dy <= 1; dy++) {
                    final int py = y + dy;
                    if (py > maxY) {
                        continue;
                    }
                    out.add(new Movements.Parkour(x, y, z, px, py, pz, dist));
                }
            }
        }

        // ---- 四向对角 ----
        for (final int[] dir : DIAG) {
            final int nx = x + dir[0];
            final int nz = z + dir[1];
            if (nx < bx1 || nx > bx2 || nz < bz1 || nz > bz2) {
                continue;
            }
            // 不切角：两侧正交格都必须是通的
            if (!context.canWalkThrough(x + dir[0], y, z) || !context.canWalkThrough(x, y, z + dir[1])) {
                continue;
            }
            if (!context.canWalkThrough(x + dir[0], y + 1, z) || !context.canWalkThrough(x, y + 1, z + dir[1])) {
                continue;
            }
            out.add(new Movements.Traverse(x, y, z, nx, nz));
            out.add(new Movements.Ascend(x, y, z, nx, nz));
        }

        // ---- 向下挖 ----
        if (context.canDigDown) {
            for (int drop = 1; drop <= MAX_FALL; drop++) {
                final int ny = y - drop;
                if (ny - 1 < minY) {
                    break;
                }
                out.add(new Movements.Downward(x, y, z, drop));
            }
        }

        // ---- 垫脚上 ----
        // 必须先确认「允许放置 + 手上有方块」，否则会规划出做不到的垫脚路线
        if (context.canPlaceAt(x, y, z)) {
            out.add(new Movements.Pillar(x, y, z));
        }
    }

    /**
     * 优先队列里的节点。**不可变**，见 {@link #openQueue} 的说明。
     */
    private static final class PathNode {
        final BlockPos pos;
        final double g;
        final double h;
        final double f;

        PathNode(final BlockPos pos, final double g, final double h) {
            this.pos = pos;
            this.g = g;
            this.h = h;
            this.f = g + h;
        }
    }
}
