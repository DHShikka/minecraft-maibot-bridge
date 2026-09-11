package com.mcai.bridge.nav;

/**
 * 寻路需要的「方块世界」抽象。
 *
 * <p>对应 Baritone 里 {@code BlockStateInterface} + {@code MovementHelper} 两层的职责合并
 * （<b>重新实现，未拷贝代码</b>）：把「这个世界长什么样」压缩成寻路真正会问的几个问题。</p>
 *
 * <p>之所以要有这层抽象，是因为 A* 每个节点要问几十次「这里能不能站」，
 * 直接调 Minecraft 的 {@code getBlockState} 既慢又和游戏类型绑死 ——
 * 绑死之后就<b>没法在不开游戏的情况下测试寻路</b>，而寻路恰恰是模组里最复杂、
 * 也最容易出错的一块（我们已经因为它崩过一次）。</p>
 */
public interface BlockWorld {

    // ------------------------------------------------------------ 方块性质

    /** 身体能否穿过（无碰撞体积、且不是危险方块）。空气与水流都算可穿过。 */
    boolean isPassable(int x, int y, int z);

    /** 能否站在它上面。 */
    boolean isWalkOn(int x, int y, int z);

    /** 是否危险（岩浆、火、仙人掌、甜浆果丛等），不该走进去。 */
    boolean isDangerous(int x, int y, int z);

    /** 是否是水。 */
    boolean isWater(int x, int y, int z);

    /** 是否可攀爬（梯子、藤蔓、脚手架）。 */
    boolean isClimbable(int x, int y, int z);

    /** 这个位置是否可以被替换掉（空气、草、水…），也就是能不能往这里放方块。 */
    boolean isReplaceable(int x, int y, int z);

    /**
     * 挖掉它的预计代价（tick）。挖不动的方块返回 {@link ActionCosts#COST_INF}。
     * 已经是空气时返回 0。
     */
    double miningCost(int x, int y, int z);

    // -------------------------------------------------------------- 世界

    /** 该水平位置所属的区块是否已加载。 */
    boolean isLoaded(int x, int z);

    int minBuildHeight();

    int maxBuildHeight();
}
