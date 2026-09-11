package com.mcai.bridge.nav;

import java.util.HashMap;
import java.util.Map;

/**
 * 一次寻路计算的上下文：<b>带缓存的世界视图 + 本次允许的能力与代价上限</b>。
 *
 * <p>对应 Baritone 的 {@code CalculationContext}（<b>重新实现，未拷贝代码</b>）。</p>
 *
 * <p>为什么必须缓存：A* 每展开一个节点都要问十几个「这里能不能站」，相邻节点之间大量重复查询。
 * 不缓存的话一次寻路会对同一个方块问几十次，在客户端上直接表现为掉帧。
 * 缓存的生命周期就是一次 {@code findPath} 调用 —— 路径算完就丢掉，
 * 所以世界在计算过程中发生变化也不会读到过期数据。</p>
 */
public final class CalculationContext {

    /** 某个位置的方块性质快照。一个位置只查一次世界，之后全走缓存。 */
    public record Info(
            boolean loaded,
            boolean passable,
            boolean walkOn,
            boolean dangerous,
            boolean water,
            boolean climbable,
            boolean replaceable,
            double miningCost
    ) {
    }

    /** 世界未加载时统一按「实心不可通行」处理，避免在没数据的地方凭空规划。 */
    private static final Info UNLOADED =
            new Info(false, false, false, false, false, false, false, ActionCosts.COST_INF);

    private final BlockWorld world;
    private final Map<Long, Info> cache = new HashMap<>();

    // ---- 本次计算允许做什么 ----
    /** 是否允许挖穿挡路的方块。关闭时寻路只会绕路。 */
    public final boolean allowBreak;
    /** 是否允许垫方块（柱子上行、搭桥）。 */
    public final boolean allowPlace;
    /** 是否允许疾跑（影响走路代价）。 */
    public final boolean allowSprint;
    /** 是否允许向下挖（挖脚下方块下降）。 */
    public final boolean canDigDown;
    /** 最大下落高度。超过就不考虑，避免规划出摔死的路线。 */
    public final int maxFallHeight;
    /** 单次为了「抄近路」最多肯花多少挖掘代价。防止为省 3 格路去挖穿一座山。 */
    public final double maxBreakCostForShortcut;
    /**
     * 背包里是否有可放置的方块。
     *
     * <p>没有的话「垫脚上」「搭桥」这类需要放方块的走法必须直接判为不可能 ——
     * 否则 A* 会规划出一条漂亮的路线，执行时才发现手上什么都没有。
     * Baritone 用 {@code context.hasThrowaway} 表达同一件事。</p>
     */
    public final boolean hasPlaceableBlock;

    public CalculationContext(final BlockWorld world,
                              final boolean allowBreak,
                              final boolean allowPlace,
                              final boolean allowSprint,
                              final boolean canDigDown,
                              final int maxFallHeight,
                              final double maxBreakCostForShortcut,
                              final boolean hasPlaceableBlock) {
        this.world = world;
        this.allowBreak = allowBreak;
        this.allowPlace = allowPlace;
        this.allowSprint = allowSprint;
        this.canDigDown = canDigDown;
        this.maxFallHeight = maxFallHeight;
        this.maxBreakCostForShortcut = maxBreakCostForShortcut;
        this.hasPlaceableBlock = hasPlaceableBlock;
    }

    /** 默认设置：能挖能放能疾跑，最大下落 4 格。 */
    public static CalculationContext standard(final BlockWorld world) {
        return new CalculationContext(world, true, true, true, true,
                PathFinder.MAX_FALL, ActionCosts.WALK_ONE_BLOCK_COST * 8, false);
    }

    // ------------------------------------------------------------ 世界查询

    /** 取某个位置的方块性质（带缓存）。 */
    public Info get(final int x, final int y, final int z) {
        if (y < world.minBuildHeight() || y >= world.maxBuildHeight()) {
            return UNLOADED;
        }
        if (!world.isLoaded(x, z)) {
            return UNLOADED;
        }
        final long key = packKey(x, y, z);
        final Info cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        final Info info = new Info(
                true,
                world.isPassable(x, y, z),
                world.isWalkOn(x, y, z),
                world.isDangerous(x, y, z),
                world.isWater(x, y, z),
                world.isClimbable(x, y, z),
                world.isReplaceable(x, y, z),
                world.miningCost(x, y, z)
        );
        cache.put(key, info);
        return info;
    }

    // ---------------------------------------------- Baritone 式的组合判定

    /**
     * 身体能不能穿过这一格。
     *
     * <p>和 {@link BlockWorld#isPassable} 的区别在于它会<b>考虑本次是否允许挖方块</b>：
     * 不允许挖的时候，挡路的方块就是真的过不去。</p>
     */
    public boolean canWalkThrough(final int x, final int y, final int z) {
        final Info info = get(x, y, z);
        if (!info.loaded() || info.dangerous()) {
            return false;
        }
        if (info.passable()) {
            return true;
        }
        // 挡路但可以挖掉 → 挖穿之后就能过
        return allowBreak && info.miningCost() < ActionCosts.COST_INF;
    }

    /**
     * 能不能站在这一格上面。
     *
     * <p><b>刻意不因为「可以往上垫方块」就返回 true。</b>如果允许，A* 会把「一路搭桥过去」
     * 当成和走路一样便宜，于是动不动就规划出铺一条路的方案。垫方块是
     * {@link Movements.Pillar} 这种专门走法的事，落脚点判定必须严格。</p>
     */
    public boolean canWalkOn(final int x, final int y, final int z) {
        final Info info = get(x, y, z);
        return info.loaded() && !info.dangerous() && info.walkOn();
    }

    /** 能不能往这一格放方块（需要本次允许放置、且手上有方块）。 */
    public boolean canPlaceAt(final int x, final int y, final int z) {
        if (!allowPlace || !hasPlaceableBlock) {
            return false;
        }
        final Info info = get(x, y, z);
        return info.loaded() && info.replaceable() && !info.dangerous();
    }

    /** 完全可以通过（脚和头都不挡）。 */
    public boolean fullyPassable(final int x, final int y, final int z) {
        final Info info = get(x, y, z);
        return info.loaded() && info.passable() && !info.dangerous();
    }

    /** 挖掉这一格的代价；不允许挖时返回 {@link ActionCosts#COST_INF}。 */
    public double breakCost(final int x, final int y, final int z) {
        if (!allowBreak) {
            return ActionCosts.COST_INF;
        }
        final Info info = get(x, y, z);
        if (info.passable() && !info.dangerous()) {
            return 0;
        }
        return info.miningCost();
    }

    /** 是否值得为了这个方块付出挖掘代价（用于「抄近路」的取舍）。 */
    public boolean worthBreaking(final double cost) {
        return allowBreak && cost < maxBreakCostForShortcut;
    }

    public BlockWorld world() {
        return world;
    }

    public int minBuildHeight() {
        return world.minBuildHeight();
    }

    public int maxBuildHeight() {
        return world.maxBuildHeight();
    }

    /** 已查询过的不同位置数量，调试用。 */
    public int cachedPositions() {
        return cache.size();
    }

    private static long packKey(final int x, final int y, final int z) {
        // 和 BlockPos.asLong 同样的打包方式，保证同一坐标只有一把键
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }
}
