package com.mcai.bridge.nav;

/**
 * 动作代价表。
 *
 * <p>思路取自 Baritone 的 {@code ActionCosts}（<b>重新实现，未拷贝代码</b>）：
 * <b>代价的单位是「tick」</b>，也就是「做这件事大概要花几帧」。
 * 所有移动、挖方块、放方块的代价都换算成 tick 之后，A* 才能在同一把尺子上比较
 * 「绕路 10 格」和「挖穿 1 格石头」哪个更划算。</p>
 *
 * <p>数值来自原版移动速度的换算：玩家走路约 4.317 格/秒，而 Minecraft 一秒 20 tick，
 * 所以走一格 ≈ 20 / 4.317 ≈ 4.63 tick。其余速度同理。这些是从游戏物理直接算出来的量，
 * 不是随手拍的数字。</p>
 */
public final class ActionCosts {

    /** 表示「不可能」的代价。任何路径只要包含一段这个代价就会被 A* 丢掉。 */
    public static final double COST_INF = 1_000_000.0;

    /** 走路一格。原版约 4.317 格/秒。 */
    public static final double WALK_ONE_BLOCK_COST = 20.0 / 4.317;

    /** 疾跑的代价乘数。疾跑约 5.612 格/秒，即 20/5.612 / (20/4.317) ≈ 0.769，这里取更保守的 0.6。 */
    public static final double SPRINT_MULTIPLIER = 0.6;

    /** 在水里前进一格。水中速度约 2.2 格/秒。 */
    public static final double WALK_ONE_IN_WATER_COST = 20.0 / 2.2;

    /** 在灵魂沙上走一格（会被拖慢）。 */
    public static final double WALK_ONE_OVER_SOUL_SAND_COST = WALK_ONE_BLOCK_COST * 2.0;

    /** 潜行前进一格。潜行约 1.3 格/秒。 */
    public static final double SNEAK_ONE_BLOCK_COST = 20.0 / 1.3;

    /** 爬梯子上升一格。 */
    public static final double LADDER_UP_ONE_COST = 20.0 / 2.35;

    /** 顺梯子下降一格。 */
    public static final double LADDER_DOWN_ONE_COST = 20.0 / 3.0;

    /**
     * 原地跳一下接住上一格台阶的代价。
     *
     * <p>比走一格贵：起跳本身要花时间，而且落地后要重新加速。取走路代价的 1.5 倍，
     * 这样 A* 会优先选择平路绕行而不是一路跳着走。</p>
     */
    public static final double JUMP_ONE_BLOCK_COST = WALK_ONE_BLOCK_COST * 1.5;

    /** 从方块边缘走出去的代价（不含下落本身）。 */
    public static final double WALK_OFF_BLOCK_COST = WALK_ONE_BLOCK_COST * 0.8;

    /** 落地后重新走到方块中心的代价。 */
    public static final double CENTER_AFTER_FALL_COST = WALK_ONE_BLOCK_COST;

    /** 放一个方块（要切物品、瞄准、右键）。 */
    public static final double PLACE_ONE_BLOCK_COST = 20.0;

    /** 空手挖一个「瞬间可破坏」方块（草、火把）的代价。 */
    public static final double BREAK_INSTANT_COST = 1.0;

    /** 摔落伤害的阈值：掉这么多格以内不掉血。 */
    public static final int SAFE_FALL_DISTANCE = 3;

    /** 每多掉一格增加的下落 tick（重力换算，约 0.5 秒落地，之后每格更快）。 */
    private static final double FALL_TICKS_PER_BLOCK = 2.0;

    private ActionCosts() {
    }

    /**
     * 从 {@code n} 格高处掉下来的代价（含落地后走到方块中心）。
     *
     * <p>不用数组缓存而是直接算：n 的取值很小（最多 {@link PathFinder#MAX_FALL} 格），
     * 现算比维护缓存更简单也更不容易出错。</p>
     */
    public static double fallCost(final int n) {
        if (n <= 0) {
            return 0;
        }
        // 下落时间大致按 sqrt(2h/g) 增长，但为了保持「代价随高度单调增」这个直觉，
        // 这里用线性近似并叠加落地后的走位成本。
        return WALK_OFF_BLOCK_COST + n * FALL_TICKS_PER_BLOCK + CENTER_AFTER_FALL_COST;
    }

    /**
     * 挖掉一个方块的预计 tick 数。
     *
     * @param hardness       方块的破坏硬度（负值表示挖不动）
     * @param correctTool    手上工具是否匹配
     * @param canHarvest     没有正确工具时是否还能挖掉（比如徒手挖泥土可以，挖石头挖不掉掉落物但能挖掉）
     */
    public static double miningCost(final float hardness, final boolean correctTool, final boolean canHarvest) {
        if (hardness < 0) {
            return COST_INF;
        }
        if (hardness == 0) {
            return BREAK_INSTANT_COST;
        }
        // 原版公式大致是 hardness * 1.5 / 工具速度（秒）。我们不知道手上工具的具体速度，
        // 所以按「有一把称手工具」估成每点硬度 10 tick（约 0.5 秒）：
        // 石头 1.5 → 15 tick（0.75 秒，与石镐接近）；黑曜石 50 → 500 tick（25 秒，会自然被劝退）。
        if (correctTool) {
            return hardness * 10.0;
        }
        if (!canHarvest) {
            // 徒手挖需要工具的方块：能挖，但慢得多
            return hardness * 50.0;
        }
        return hardness * 33.0;
    }
}
