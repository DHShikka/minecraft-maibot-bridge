package com.mcai.bridge.nav;

import net.minecraft.core.BlockPos;

import java.util.Set;

/**
 * 所有具体移动（movement）的实现。
 *
 * <p>架构与代价模型参照 Baritone 的 {@code MovementTraverse} / {@code MovementAscend} /
 * {@code MovementDescend} / {@code MovementFall} / {@code MovementDiagonal} /
 * {@code MovementParkour} / {@code MovementDownward} / {@code MovementPillar}
 * （<b>重新实现，未拷贝其代码；Baritone 为 LGPL-3.0，本项目为 MIT</b>）。</p>
 *
 * <p>放在同一个文件里是因为它们共享大量校验逻辑，分开写反而要到处 import 同一批辅助方法。
 * 字母顺序排列，每个类开头一句话说明它代表什么动作。</p>
 */
public final class Movements {

    private Movements() {
    }

    // ============================================================== 共用校验

    /** 清理某个方块所在空间的代价：已经是通的返回 0，挡路且可挖返回挖掘代价，否则无穷。 */
    static double clearCost(final CalculationContext ctx, final int x, final int y, final int z) {
        final CalculationContext.Info info = ctx.get(x, y, z);
        if (!info.loaded()) {
            return ActionCosts.COST_INF;
        }
        if (info.dangerous()) {
            return ActionCosts.COST_INF;
        }
        if (info.passable()) {
            return 0;
        }
        if (!ctx.allowBreak) {
            return ActionCosts.COST_INF;
        }
        return info.miningCost();
    }

    /** 身体（脚 + 头）通过某格所需的清理代价。 */
    static double clearBodyCost(final CalculationContext ctx, final int x, final int y, final int z) {
        final double feet = clearCost(ctx, x, y, z);
        if (feet >= ActionCosts.COST_INF) {
            return ActionCosts.COST_INF;
        }
        final double head = clearCost(ctx, x, y + 1, z);
        if (head >= ActionCosts.COST_INF) {
            return ActionCosts.COST_INF;
        }
        return feet + head;
    }

    /** 落脚点是否可用（脚下能站，或允许垫方块）。 */
    static boolean landingOk(final CalculationContext ctx, final int x, final int y, final int z) {
        return ctx.canWalkOn(x, y - 1, z) && ctx.fullyPassable(x, y, z) && ctx.fullyPassable(x, y + 1, z);
    }

    /** 走路一格的基础代价（含疾跑折扣）。 */
    static double walkCost(final CalculationContext ctx) {
        return ctx.allowSprint
                ? ActionCosts.WALK_ONE_BLOCK_COST * ActionCosts.SPRINT_MULTIPLIER
                : ActionCosts.WALK_ONE_BLOCK_COST;
    }

    // ============================================================== Traverse

    /** 同一层走一格（四向或对角）。 */
    public static final class Traverse extends Movement {

        private final boolean diagonal;

        public Traverse(final int srcX, final int srcY, final int srcZ,
                        final int destX, final int destZ) {
            super(srcX, srcY, srcZ, destX, srcY, destZ);
            this.diagonal = destX != srcX && destZ != srcZ;
            positionsToBreak.add(new BlockPos(destX, srcY, destZ));
            positionsToBreak.add(new BlockPos(destX, srcY + 1, destZ));
        }

        @Override
        public double calculateCost(final CalculationContext ctx) {
            final double clear = clearBodyCost(ctx, destX, srcY, destZ);
            if (clear >= ActionCosts.COST_INF) {
                return ActionCosts.COST_INF;
            }
            if (!ctx.canWalkOn(destX, srcY - 1, destZ)) {
                return ActionCosts.COST_INF;
            }
            double cost = walkCost(ctx) * (diagonal ? Math.sqrt(2.0) : 1.0);
            if (ctx.allowSprint && clear > 0) {
                // 要挖东西就没法疾跑，把折扣补回来
                cost = ActionCosts.WALK_ONE_BLOCK_COST * (diagonal ? Math.sqrt(2.0) : 1.0);
            }
            // 水里有额外阻力
            if (ctx.get(destX, srcY, destZ).water() || ctx.get(destX, srcY + 1, destZ).water()) {
                cost += ActionCosts.WALK_ONE_IN_WATER_COST - ActionCosts.WALK_ONE_BLOCK_COST;
            }
            cost += clear;
            return cost;
        }

        @Override
        protected Set<Long> calculateValidPositions() {
            final Set<Long> set = super.calculateValidPositions();
            if (diagonal) {
                // 对角移动时玩家也可能短暂处在两侧的正交格附近
                set.add(new BlockPos(destX, srcY, srcZ).asLong());
                set.add(new BlockPos(srcX, srcY, destZ).asLong());
            }
            return set;
        }

        @Override
        public MovementState updateState(final MovementState state, final ExecEnv env,
                                       final CalculationContext context) {
            if (!prepared(state, env, context)) {
                return state;
            }
            if (!prepared(state, env, context)) {
                return state;
            }
            if (state.getStatus() == MovementState.Status.PREPPING) {
                state.setStatus(MovementState.Status.RUNNING);
            }
            if (arrived(env, destX, destY, destZ)) {
                return state.setStatus(MovementState.Status.SUCCESS);
            }
            moveTowards(state, env, destX, destZ);
            state.setForward(true);
            state.setSprint(true);
            return state;
        }

        @Override
        public String name() {
            return diagonal ? "斜走" : "直走";
        }
    }

    // =============================================================== Ascend

    /** 走一格并上一格台阶。 */
    public static final class Ascend extends Movement {

        public Ascend(final int srcX, final int srcY, final int srcZ,
                      final int destX, final int destZ) {
            super(srcX, srcY, srcZ, destX, srcY + 1, destZ);
            // 要跳上去，所以目标格的脚和头都要清空
            positionsToBreak.add(new BlockPos(destX, srcY + 1, destZ));
            positionsToBreak.add(new BlockPos(destX, srcY + 2, destZ));
        }

        @Override
        public double calculateCost(final CalculationContext ctx) {
            if (!landingOk(ctx, destX, srcY + 1, destZ)) {
                return ActionCosts.COST_INF;
            }
            // 起跳需要头顶空间
            if (!ctx.fullyPassable(srcX, srcY + 2, srcZ)) {
                return ActionCosts.COST_INF;
            }
            final double clear = clearBodyCost(ctx, destX, srcY + 1, destZ);
            if (clear >= ActionCosts.COST_INF) {
                return ActionCosts.COST_INF;
            }
            final double jump = Math.max(ActionCosts.JUMP_ONE_BLOCK_COST, ActionCosts.WALK_ONE_BLOCK_COST);
            return jump + clear + ActionCosts.CENTER_AFTER_FALL_COST;
        }

        @Override
        public MovementState updateState(final MovementState state, final ExecEnv env,
                                       final CalculationContext context) {
            if (!prepared(state, env, context)) {
                return state;
            }
            if (state.getStatus() == MovementState.Status.PREPPING) {
                state.setStatus(MovementState.Status.RUNNING);
            }
            if (arrived(env, destX, destY, destZ)) {
                return state.setStatus(MovementState.Status.SUCCESS);
            }
            moveTowards(state, env, destX, destZ);
            state.setForward(true);
            // 脚还在下面那一层时才需要按跳
            if (env.y() < srcY + 0.6) {
                state.setJump(true);
            }
            return state;
        }

        @Override
        public String name() {
            return "上台阶";
        }
    }

    // ============================================================== Descend

    /** 走一格并下 1 格台阶。 */
    public static final class Descend extends Movement {

        public Descend(final int srcX, final int srcY, final int srcZ,
                       final int destX, final int destZ, final int destY) {
            super(srcX, srcY, srcZ, destX, destY, destZ);
            positionsToBreak.add(new BlockPos(destX, destY, destZ));
            positionsToBreak.add(new BlockPos(destX, destY + 1, destZ));
        }

        @Override
        public double calculateCost(final CalculationContext ctx) {
            if (!landingOk(ctx, destX, destY, destZ)) {
                return ActionCosts.COST_INF;
            }
            final double clear = clearBodyCost(ctx, destX, destY, destZ);
            if (clear >= ActionCosts.COST_INF) {
                return ActionCosts.COST_INF;
            }
            final int drop = srcY - destY;
            if (drop > ctx.maxFallHeight) {
                return ActionCosts.COST_INF;
            }
            return ActionCosts.fallCost(drop) + clear;
        }

        @Override
        public MovementState updateState(final MovementState state, final ExecEnv env,
                                       final CalculationContext context) {
            if (!prepared(state, env, context)) {
                return state;
            }
            if (state.getStatus() == MovementState.Status.PREPPING) {
                state.setStatus(MovementState.Status.RUNNING);
            }
            if (arrived(env, destX, destY, destZ) && env.onGround()) {
                return state.setStatus(MovementState.Status.SUCCESS);
            }
            moveTowards(state, env, destX, destZ);
            state.setForward(true);
            return state;
        }

        @Override
        public String name() {
            return "下台阶";
        }
    }

    // ================================================================= Fall

    /** 走出去并往下掉若干格。 */
    public static final class Fall extends Movement {

        public Fall(final int srcX, final int srcY, final int srcZ,
                    final int destX, final int destY, final int destZ) {
            super(srcX, srcY, srcZ, destX, destY, destZ);
            positionsToBreak.add(new BlockPos(destX, destY, destZ));
            positionsToBreak.add(new BlockPos(destX, destY + 1, destZ));
        }

        @Override
        public double calculateCost(final CalculationContext ctx) {
            final int drop = srcY - destY;
            if (drop <= 0 || drop > ctx.maxFallHeight) {
                return ActionCosts.COST_INF;
            }
            if (!landingOk(ctx, destX, destY, destZ)) {
                return ActionCosts.COST_INF;
            }
            // 下落途中的每一格都必须是空的（否则会卡在半空）
            for (int y = destY + 2; y <= srcY; y++) {
                final CalculationContext.Info info = ctx.get(destX, y, destZ);
                if (!info.loaded() || !info.passable() || info.dangerous()) {
                    return ActionCosts.COST_INF;
                }
            }
            return ActionCosts.fallCost(drop) + clearBodyCost(ctx, destX, destY, destZ);
        }

        @Override
        public MovementState updateState(final MovementState state, final ExecEnv env,
                                       final CalculationContext context) {
            if (!prepared(state, env, context)) {
                return state;
            }
            if (state.getStatus() == MovementState.Status.PREPPING) {
                state.setStatus(MovementState.Status.RUNNING);
            }
            if (env.y() <= destY + 0.1 && env.onGround()) {
                return state.setStatus(MovementState.Status.SUCCESS);
            }
            // 还没离开边缘就继续走，离开后只需要保持不掉队
            moveTowards(state, env, destX, destZ);
            state.setForward(true);
            return state;
        }

        @Override
        public String name() {
            return "下落";
        }
    }

    // ============================================================= Downward

    /**
     * 向下挖穿并掉进下一层。
     *
     * <p>这是「挖矿」在寻路里的体现：旧实现只能绕路，遇到垂直矿道就抓瞎。</p>
     */
    public static final class Downward extends Movement {

        public Downward(final int srcX, final int srcY, final int srcZ, final int drop) {
            super(srcX, srcY, srcZ, srcX, srcY - drop, srcZ);
            // 从脚下一直清到落地那一格
            for (int y = srcY - 1; y >= srcY - drop; y--) {
                positionsToBreak.add(new BlockPos(srcX, y, srcZ));
            }
        }

        @Override
        public double calculateCost(final CalculationContext ctx) {
            if (!ctx.canDigDown) {
                return ActionCosts.COST_INF;
            }
            final int drop = srcY - destY;
            if (drop < 1 || drop > PathFinder.MAX_FALL) {
                return ActionCosts.COST_INF;
            }
            double cost = 0;
            for (int y = srcY - 1; y >= destY; y--) {
                final double c = clearCost(ctx, srcX, y, srcZ);
                if (c >= ActionCosts.COST_INF) {
                    return ActionCosts.COST_INF;
                }
                cost += c;
            }
            // 全是空气就不叫「向下挖」了，那是下落走法的活
            if (cost <= 0) {
                return ActionCosts.COST_INF;
            }
            // 落地格下面必须有支撑，否则会一路掉下去
            if (!ctx.canWalkOn(destX, destY - 1, destZ)) {
                return ActionCosts.COST_INF;
            }
            // 挖穿地板本身要额外加权：它不可逆，而且会改变地形
            return cost + ActionCosts.fallCost(drop) + ActionCosts.PLACE_ONE_BLOCK_COST;
        }

        @Override
        public MovementState updateState(final MovementState state, final ExecEnv env,
                                       final CalculationContext context) {
            if (!prepared(state, env, context)) {
                return state;
            }
            if (state.getStatus() == MovementState.Status.PREPPING) {
                state.setStatus(MovementState.Status.RUNNING);
            }
            if (env.y() <= destY + 0.1 && env.onGround()) {
                return state.setStatus(MovementState.Status.SUCCESS);
            }
            // 整列已经在 prepared() 里挖通了，这里只需低头自由落体
            state.setPitch(90.0f);
            return state;
        }

        @Override
        public String name() {
            return "向下挖";
        }
    }

    // =============================================================== Pillar

    /**
     * 垫一个方块在自己脚下并跳上去。
     *
     * <p>这是「放置」在寻路里的体现：一堵两格高的墙，垫上去比挖穿更划算，
     * 而且旧实现完全没有这个能力。</p>
     */
    public static final class Pillar extends Movement {

        public Pillar(final int srcX, final int srcY, final int srcZ) {
            super(srcX, srcY, srcZ, srcX, srcY + 1, srcZ);
            // 要往自己站的位置放一个方块，然后站上去
            positionToPlace = new BlockPos(srcX, srcY, srcZ);
        }

        @Override
        public double calculateCost(final CalculationContext ctx) {
            // 必须同时满足：允许放置 + 手上真有方块 + 脚下那格可替换。
            // 只判 allowPlace 是不够的 —— 背包里一块方块都没有时，
            // A* 会规划出一条漂亮的「垫上去」路线，执行时才发现放不了。
            if (!ctx.canPlaceAt(srcX, srcY, srcZ)) {
                return ActionCosts.COST_INF;
            }
            // 头顶要有空间站人
            if (!ctx.fullyPassable(srcX, srcY + 1, srcZ) || !ctx.fullyPassable(srcX, srcY + 2, srcZ)) {
                return ActionCosts.COST_INF;
            }
            return ActionCosts.PLACE_ONE_BLOCK_COST + ActionCosts.JUMP_ONE_BLOCK_COST;
        }

        @Override
        protected Set<Long> calculateValidPositions() {
            final Set<Long> set = super.calculateValidPositions();
            set.add(getSrc().asLong());
            return set;
        }

        @Override
        public MovementState updateState(final MovementState state, final ExecEnv env,
                                       final CalculationContext context) {
            // 刻意不走 prepared()：垫脚跳的物理要求是「跳起来 + 在空中往脚下放方块」，
            // 而 prepared() 是「放好了再动」。顺序反了会变成站在自己身上放方块，放不下去。
            if (state.getStatus() == MovementState.Status.PREPPING) {
                state.setStatus(MovementState.Status.RUNNING);
            }
            if (arrived(env, destX, destY, destZ)) {
                return state.setStatus(MovementState.Status.SUCCESS);
            }
            state.setPitch(90.0f);
            state.setPlaceTarget(new BlockPos(srcX, srcY, srcZ));
            state.setJump(true);
            return state;
        }

        @Override
        public String name() {
            return "垫脚上";
        }
    }

    // ============================================================== Parkour

    /**
     * 跳过缺口（跑酷）。
     *
     * <p>对应 Baritone 的 {@code MovementParkour}。只允许四向、跨度 2–4 格、落点同高或高一格。</p>
     */
    public static final class Parkour extends Movement {

        private final int dx;
        private final int dz;
        private final int distance;

        public Parkour(final int srcX, final int srcY, final int srcZ,
                       final int destX, final int destY, final int destZ, final int distance) {
            super(srcX, srcY, srcZ, destX, destY, destZ);
            this.dx = Integer.signum(destX - srcX);
            this.dz = Integer.signum(destZ - srcZ);
            this.distance = distance;
        }

        @Override
        public double calculateCost(final CalculationContext ctx) {
            // 落点必须能站
            if (!ctx.canWalkOn(destX, destY - 1, destZ)
                    || !ctx.fullyPassable(destX, destY, destZ)
                    || !ctx.fullyPassable(destX, destY + 1, destZ)) {
                return ActionCosts.COST_INF;
            }
            // 起跳点到落点之间的中间格必须是空的（否则会撞墙）
            for (int step = 1; step < distance; step++) {
                final int x = srcX + dx * step;
                final int z = srcZ + dz * step;
                if (!ctx.fullyPassable(x, srcY, z) || !ctx.fullyPassable(x, srcY + 1, z)) {
                    return ActionCosts.COST_INF;
                }
                if (!ctx.fullyPassable(x, srcY + 2, z)) {
                    return ActionCosts.COST_INF;
                }
            }
            // 落点比起点高的话还要能跳上去
            if (destY > srcY) {
                if (!ctx.fullyPassable(srcX, srcY + 2, srcZ)) {
                    return ActionCosts.COST_INF;
                }
            }
            // 跨度越大越贵；4 格必须疾跑
            double cost = ActionCosts.JUMP_ONE_BLOCK_COST
                    + ActionCosts.WALK_ONE_BLOCK_COST * distance;
            if (distance >= 4) {
                if (!ctx.allowSprint) {
                    return ActionCosts.COST_INF;
                }
                cost += ActionCosts.WALK_ONE_BLOCK_COST;
            }
            if (destY > srcY) {
                cost += ActionCosts.JUMP_ONE_BLOCK_COST;
            }
            return cost;
        }

        @Override
        protected Set<Long> calculateValidPositions() {
            final Set<Long> set = super.calculateValidPositions();
            // 空中会经过中间格
            for (int step = 1; step < distance; step++) {
                set.add(new BlockPos(srcX + dx * step, srcY, srcZ + dz * step).asLong());
            }
            return set;
        }

        @Override
        public MovementState updateState(final MovementState state, final ExecEnv env,
                                       final CalculationContext context) {
            if (!prepared(state, env, context)) {
                return state;
            }
            if (state.getStatus() == MovementState.Status.PREPPING) {
                state.setStatus(MovementState.Status.RUNNING);
            }
            if (arrived(env, destX, destY, destZ) && env.onGround()) {
                return state.setStatus(MovementState.Status.SUCCESS);
            }
            state.setYaw(yawTowards(env));
            state.setForward(true);
            state.setSprint(true);
            // 站在起跳格上、或已经腾空且还没到落点 → 保持按跳
            if (env.onGround() || env.y() > srcY + 0.1) {
                state.setJump(true);
            }
            return state;
        }

        private float yawTowards(final ExecEnv env) {
            final double tx = destX + 0.5 - env.x();
            final double tz = destZ + 0.5 - env.z();
            return (float) (Math.toDegrees(Math.atan2(tz, tx)) - 90.0);
        }

        @Override
        public String name() {
            return "跳过缺口";
        }
    }
}
