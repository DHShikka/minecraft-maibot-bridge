package com.mcai.bridge.nav;

import net.minecraft.core.BlockPos;

/**
 * 寻路目标。
 *
 * <p>借鉴 Baritone 的 {@code baritone.api.pathing.goals.Goal} 设计（<b>重新实现，非拷贝代码</b>）：
 * 目标只回答两个问题 —— 「这个位置算不算到了」和「从这里过去大概还要多久」。
 * 把「目标」从「一段路径」里独立出来之后，同一套 A* 就能服务很多种需求：
 * 走到某个方块、走到某片区域、只关心到某个高度、多个目标任选其一。</p>
 */
public interface Goal {

    /** 该坐标是否已经满足目标。 */
    boolean isInGoal(int x, int y, int z);

    /**
     * 从该坐标到目标的代价估计。
     *
     * <p><b>单位必须和 {@link ActionCosts} 的代价一致（tick）</b>，而且必须<b>不高估</b>，
     * 否则 A* 会退化成非最优解。见 {@link Goals#heuristicScale()}。</p>
     */
    double heuristic(int x, int y, int z);

    default boolean isInGoal(final BlockPos pos) {
        return isInGoal(pos.getX(), pos.getY(), pos.getZ());
    }

    default double heuristic(final BlockPos pos) {
        return heuristic(pos.getX(), pos.getY(), pos.getZ());
    }

    /** 给日志与错误信息用的人类可读描述。 */
    default String describe() {
        return getClass().getSimpleName();
    }
}
