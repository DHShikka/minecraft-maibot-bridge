package com.mcai.bridge.nav;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 一次「移动」：从 {@code src} 到 {@code dest} 的一个<b>多 tick 动作</b>。
 *
 * <p>这是整个重写里最关键的一点，思路来自 Baritone 的 {@code Movement}
 * （<b>重新实现，未拷贝代码</b>）。</p>
 *
 * <h2>为什么不是「路点」</h2>
 *
 * <p>旧实现把路径表示成一串坐标路点，然后由一个通用的「朝路点走」控制器去跟随，
 * 遇到特殊情况（要跳、要上台阶、前面有洞）再加启发式补救。问题在于
 * <b>规划时知道的信息，执行时丢掉了</b>：规划器决定「这里是一格缺口，跳过去」，
 * 执行器只看到一个两格外的坐标，并不知道为什么要跳 —— 于是要么不跳、
 * 要么在不该跳的地方跳。我们确实踩过这个坑。</p>
 *
 * <p>「移动」把两者绑在一起：<b>它自己知道代价，也自己知道怎么执行</b>。
 * A* 选出的路径就是一串移动，执行器只需要按顺序调用它们的
 * {@link #updateState}，不需要再猜任何东西。</p>
 *
 * <h2>三个能力</h2>
 * <ul>
 *   <li>{@link #calculateCost} —— 这一步要花多少 tick（{@link ActionCosts#COST_INF} 表示做不到）</li>
 *   <li>{@link #getValidPositions} —— 执行期间玩家可能处在哪些格子（用于判断「还在这一步上吗」）</li>
 *   <li>{@link #updateState} —— 这一 tick 该按什么键、朝哪看、要挖/放哪个方块</li>
 * </ul>
 */
public abstract class Movement {

    /** 执行时可读的玩家状态。由 {@link PathExecutor} 用真实客户端实现，测试里用假的。 */
    public interface ExecEnv {
        double x();

        double y();

        double z();

        double eyeY();

        float yaw();

        float pitch();

        boolean onGround();

        boolean inWater();

        /** 玩家是否正对着这个方块。 */
        boolean isLookingAt(BlockPos pos);

        /** 玩家能否够到这个方块（距离 + 视线）。 */
        boolean canReach(BlockPos pos);
    }

    protected final int srcX;
    protected final int srcY;
    protected final int srcZ;
    protected final int destX;
    protected final int destY;
    protected final int destZ;

    /** 执行这一步之前必须先挖掉的方块。 */
    protected final List<BlockPos> positionsToBreak = new ArrayList<>(2);
    /** 执行这一步之前必须先放上一个方块的位置；没有则为 null。 */
    protected BlockPos positionToPlace;

    private Double cost;
    private Set<Long> validPositions;
    private boolean calculatedWhileLoaded;

    protected Movement(final int srcX, final int srcY, final int srcZ,
                       final int destX, final int destY, final int destZ) {
        this.srcX = srcX;
        this.srcY = srcY;
        this.srcZ = srcZ;
        this.destX = destX;
        this.destY = destY;
        this.destZ = destZ;
    }

    // ------------------------------------------------------------------ 代价

    public final double getCost(final CalculationContext context) {
        if (cost == null) {
            cost = calculateCost(context);
        }
        return cost;
    }

    /** 这一步的代价（tick）。{@link ActionCosts#COST_INF} 表示不可能。 */
    public abstract double calculateCost(CalculationContext context);

    // -------------------------------------------------------------- 位置信息

    public final BlockPos getSrc() {
        return new BlockPos(srcX, srcY, srcZ);
    }

    public final BlockPos getDest() {
        return new BlockPos(destX, destY, destZ);
    }

    /** 这一步的方向（dest - src）。 */
    public final BlockPos getDirection() {
        return getDest().subtract(getSrc());
    }

    /**
     * 执行期间玩家「还在这步上」的合法位置集合。
     *
     * <p>{@link PathExecutor} 每 tick 用它判断玩家有没有偏出去：
     * 偏出去就回退一步重试，而不是硬着头皮继续（那是旧实现「卡住检测」
     * 想解决的问题，但那种做法只能事后补救，猜不出该退到哪）。</p>
     */
    public final Set<Long> getValidPositions() {
        if (validPositions == null) {
            validPositions = calculateValidPositions();
        }
        return validPositions;
    }

    protected Set<Long> calculateValidPositions() {
        final Set<Long> set = new HashSet<>(2);
        set.add(getSrc().asLong());
        set.add(getDest().asLong());
        return set;
    }

    public final boolean calculatedWhileLoaded() {
        return calculatedWhileLoaded;
    }

    public final void checkLoadedChunk(final CalculationContext context) {
        calculatedWhileLoaded = context.get(destX, destY, destZ).loaded();
    }

    // ---------------------------------------------------------- 先挖 / 先放

    /** 挡在这步路上、必须先挖掉的方块（空气不算）。 */
    public final List<BlockPos> toBreak(final CalculationContext context) {
        final List<BlockPos> result = new ArrayList<>(2);
        for (final BlockPos pos : positionsToBreak) {
            final CalculationContext.Info info =
                    context.get(pos.getX(), pos.getY(), pos.getZ());
            if (!info.passable() || info.dangerous()) {
                result.add(pos);
            }
        }
        return result;
    }

    /** 需要先放上一个方块的位置（null 表示不需要）。 */
    public final BlockPos toPlace(final CalculationContext context) {
        if (positionToPlace == null) {
            return null;
        }
        final CalculationContext.Info info = context.get(
                positionToPlace.getX(), positionToPlace.getY(), positionToPlace.getZ());
        return info.walkOn() ? null : positionToPlace;
    }

    // -------------------------------------------------------------- 执行

    /**
     * 推进这一 tick。
     *
     * @param state 上一 tick 的状态（调用方复用，本方法会就地修改并返回）
     * @param env   玩家状态读取接口
     * @param context 本次寻路的上下文（判断要不要先挖/先放）
     * @return 新的状态
     */
    public abstract MovementState updateState(MovementState state, ExecEnv env, CalculationContext context);

    /**
     * 「准备阶段」：先把挡路的方块挖掉、缺的方块放上。
     *
     * @return true 表示已经准备好、可以开始正式移动
     */
    protected boolean prepared(final MovementState state, final ExecEnv env,
                               final CalculationContext context) {
        if (state.getStatus() == MovementState.Status.WAITING) {
            return true;
        }
        boolean somethingInTheWay = false;
        for (final BlockPos pos : toBreak(context)) {
            somethingInTheWay = true;
            if (env.canReach(pos)) {
                if (env.isLookingAt(pos)) {
                    state.setBreakTarget(pos);
                    return false;
                }
                // 还没对准：这一 tick 只负责转头
                faceTowards(state, env, pos);
                return false;
            }
            // 够不着 → 这步走不了
            state.setStatus(MovementState.Status.UNREACHABLE);
            return false;
        }
        if (somethingInTheWay) {
            state.setStatus(MovementState.Status.UNREACHABLE);
            return false;
        }

        final BlockPos place = toPlace(context);
        if (place != null) {
            if (!env.canReach(place)) {
                state.setStatus(MovementState.Status.UNREACHABLE);
                return false;
            }
            state.setPlaceTarget(place);
            return false;
        }
        return true;
    }

    /** 让角色朝向某个方块的几何中心。 */
    protected static void faceTowards(final MovementState state, final ExecEnv env, final BlockPos pos) {
        final double dx = pos.getX() + 0.5 - env.x();
        final double dy = pos.getY() + 0.5 - env.eyeY();
        final double dz = pos.getZ() + 0.5 - env.z();
        final double horizontal = Math.sqrt(dx * dx + dz * dz);
        state.setYaw((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0));
        state.setPitch((float) (-Math.toDegrees(Math.atan2(dy, horizontal))));
    }

    /** 平地移动时朝向下一个目标格子的中心。 */
    protected static void moveTowards(final MovementState state, final ExecEnv env,
                                      final int x, final int z) {
        final double dx = x + 0.5 - env.x();
        final double dz = z + 0.5 - env.z();
        state.setYaw((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0));
    }

    /** 玩家是否已经进到这一步的目标格。 */
    protected static boolean arrived(final ExecEnv env, final int x, final int y, final int z) {
        final double dx = env.x() - (x + 0.5);
        final double dz = env.z() - (z + 0.5);
        final double dy = env.y() - y;
        // 水平进格 + 垂直对上（允许站在方块上表面附近）
        return dx * dx + dz * dz < 0.36 && dy > -0.6 && dy < 1.2;
    }

    /** 是否已经走过了目标格（防止在目标格上原地打转）。 */
    protected static boolean overshot(final ExecEnv env, final int srcX, final int srcZ,
                                      final int destX, final int destZ) {
        final int dx = destX - srcX;
        final int dz = destZ - srcZ;
        final double along = (env.x() - (destX + 0.5)) * dx + (env.z() - (destZ + 0.5)) * dz;
        return along > 0.35;
    }

    /** 用于显示的简短名字。 */
    public String name() {
        return getClass().getSimpleName();
    }

    @Override
    public String toString() {
        return name() + " " + getSrc().toShortString() + " -> " + getDest().toShortString();
    }

    // ------------------------------------------------------------ 工具方法

    /** 采集「某方向上一层阶」需要检查的常用方块。 */
    protected static boolean canStandAt(final CalculationContext context, final int x, final int y, final int z) {
        return context.fullyPassable(x, y, z)
                && context.fullyPassable(x, y + 1, z)
                && context.canWalkOn(x, y - 1, z);
    }

    /** 某个方向上的水平偏移。 */
    protected static Direction directionOf(final int dx, final int dz) {
        if (dx > 0) {
            return Direction.EAST;
        }
        if (dx < 0) {
            return Direction.WEST;
        }
        if (dz > 0) {
            return Direction.SOUTH;
        }
        return Direction.NORTH;
    }
}
