package com.mcai.bridge.nav;

import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * {@link Goal} 的几种常用实现。
 *
 * <p>设计参照 Baritone 的 {@code GoalBlock} / {@code GoalNear} / {@code GoalXZ} / {@code GoalComposite}
 * （<b>重新实现，未拷贝其代码</b>）。放在同一个文件里是因为它们每个都只有几行，
 * 拆成四个文件反而不好读。</p>
 */
public final class Goals {

    private Goals() {
    }

    /**
     * 启发式的尺度：每格最少要花多少 tick。
     *
     * <p>取「疾跑走一格」的代价，因为这是所有移动里单位距离最便宜的一种。
     * 用它乘距离得到的估计量<b>一定不高估</b>真实代价，A* 因此仍然能找到最优路径，
     * 同时比单纯用「格数」当启发式收敛得快得多。</p>
     */
    public static double heuristicScale() {
        return ActionCosts.WALK_ONE_BLOCK_COST * ActionCosts.SPRINT_MULTIPLIER;
    }

    /** 走到某个具体方块（允许站到它上方）。 */
    public static Goal block(final BlockPos pos) {
        return new BlockGoal(pos);
    }

    /** 走到某个位置附近（水平半径 {@code radius}，垂直容差 1）。 */
    public static Goal near(final BlockPos pos, final int radius) {
        return new NearGoal(pos, radius);
    }

    /** 只关心水平坐标，任意高度都算到。 */
    public static Goal xz(final int x, final int z) {
        return new XZGoal(x, z);
    }

    /** 任意一个子目标满足即算到达（例如「走到若干箱子中的任意一个」）。 */
    public static Goal anyOf(final List<Goal> goals) {
        return new CompositeGoal(goals);
    }

    // ------------------------------------------------------------------ 实现

    /** 精确到某一个方块。 */
    private record BlockGoal(BlockPos pos) implements Goal {

        @Override
        public boolean isInGoal(final int x, final int y, final int z) {
            return x == pos.getX() && z == pos.getZ() && Math.abs(y - pos.getY()) <= 1;
        }

        @Override
        public double heuristic(final int x, final int y, final int z) {
            final double dx = x - pos.getX();
            final double dy = y - pos.getY();
            final double dz = z - pos.getZ();
            return Math.sqrt(dx * dx + dy * dy + dz * dz) * heuristicScale();
        }

        @Override
        public String describe() {
            return "到达方块 " + pos.getX() + "," + pos.getY() + "," + pos.getZ();
        }
    }

    /** 到达某个位置附近即可。 */
    private record NearGoal(BlockPos pos, int radius) implements Goal {

        NearGoal {
            radius = Math.max(0, radius);
        }

        @Override
        public boolean isInGoal(final int x, final int y, final int z) {
            if (Math.abs(y - pos.getY()) > 2) {
                return false;
            }
            final int dx = x - pos.getX();
            final int dz = z - pos.getZ();
            final int r = Math.max(1, radius);
            return dx * dx + dz * dz <= r * r;
        }

        @Override
        public double heuristic(final int x, final int y, final int z) {
            final double dx = x - pos.getX();
            final double dz = z - pos.getZ();
            final double horizontal = Math.sqrt(dx * dx + dz * dz);
            // 减去允许的半径，让启发式在接近目标时趋近 0
            final double remaining = Math.max(0, horizontal - Math.max(1, radius));
            final double dy = Math.max(0, Math.abs(y - pos.getY()) - 2);
            return Math.sqrt(remaining * remaining + dy * dy) * heuristicScale();
        }

        @Override
        public String describe() {
            return "到达 " + pos.getX() + "," + pos.getY() + "," + pos.getZ() + " 附近 " + radius + " 格";
        }
    }

    /** 只关心水平位置。 */
    private record XZGoal(int x, int z) implements Goal {

        @Override
        public boolean isInGoal(final int px, final int py, final int pz) {
            return px == x && pz == z;
        }

        @Override
        public double heuristic(final int px, final int py, final int pz) {
            final double dx = px - x;
            final double dz = pz - z;
            return Math.sqrt(dx * dx + dz * dz) * heuristicScale();
        }

        @Override
        public String describe() {
            return "到达水平位置 " + x + "," + z + "（高度不限）";
        }
    }

    /** 任意子目标满足即算到达。 */
    private record CompositeGoal(List<Goal> goals) implements Goal {

        @Override
        public boolean isInGoal(final int x, final int y, final int z) {
            for (final Goal goal : goals) {
                if (goal.isInGoal(x, y, z)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public double heuristic(final int x, final int y, final int z) {
            double best = Double.MAX_VALUE;
            for (final Goal goal : goals) {
                best = Math.min(best, goal.heuristic(x, y, z));
            }
            return best == Double.MAX_VALUE ? 0 : best;
        }

        @Override
        public String describe() {
            return "到达 " + goals.size() + " 个目标中的任意一个";
        }
    }
}
