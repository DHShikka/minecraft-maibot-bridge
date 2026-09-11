package com.mcai.bridge.action.tasks;

import java.util.List;

/**
 * 防御战术决策：给定「威胁态势 + 自己的状态」，决定这一步该做什么。
 *
 * <h2>为什么要单独抽出来</h2>
 *
 * <p>「抵御」这件事的难点不在动作（走过去、挥剑都有现成的），而在<b>判断</b>：
 * 什么时候该打、什么时候该先吃一口、什么时候该撤、被围了怎么办。
 * 这些判断如果埋在 tick 循环里，就只能靠开游戏试出来；抽成纯函数之后，
 * 每种局面都能在单测里定死 —— 而这类判断恰恰是「一眼看不出对错」的那种。</p>
 *
 * <p>本类不引用任何 Minecraft 类型，输入输出都是普通数值与字符串。</p>
 */
public final class DefenseTactics {

    /** 这一步该干什么。 */
    public enum Move {
        /** 打最近的威胁。 */
        FIGHT,
        /** 先吃口东西回血。 */
        EAT,
        /** 拉开距离。 */
        RETREAT,
        /** 附近没威胁了。 */
        DONE
    }

    /**
     * 一个威胁。
     *
     * @param id          实体 uuid（用来指定打谁）
     * @param name        显示名
     * @param distance    离我多远
     * @param targetingMe 它是不是正冲着我（被打时优先还手）
     * @param canReach    能不能够得着（在地面/同层；够不着的先想办法过去）
     */
    public record Threat(String id, String name, double distance, boolean targetingMe, boolean canReach) {
    }

    /**
     * 自己的状态。
     *
     * @param health         当前血量
     * @param maxHealth      血量上限
     * @param holdingWeapon  主手是不是武器
     * @param hasFood        背包里有没有能吃的东西
     */
    public record SelfState(double health, double maxHealth, boolean holdingWeapon, boolean hasFood) {

        public double healthRatio() {
            return maxHealth <= 0 ? 1.0 : health / maxHealth;
        }
    }

    /**
     * 阈值与开关。
     *
     * @param radius            威胁判定半径
     * @param retreatHealth     血低于这个值就先自保（吃东西 / 撤）
     * @param overwhelmedCount  同时面对这么多敌人就算「被围」
     * @param fleeOnly          只跑不打
     */
    public record Settings(int radius, double retreatHealth, int overwhelmedCount, boolean fleeOnly) {

        public static Settings of(final int radius, final double retreatHealth,
                                  final int overwhelmedCount, final boolean fleeOnly) {
            return new Settings(radius, retreatHealth, overwhelmedCount, fleeOnly);
        }

        public static Settings defaults() {
            return new Settings(16, 8.0, 3, false);
        }
    }

    /**
     * 决策。优先级从高到低：
     *
     * <ol>
     *   <li>附近没威胁 → 收工</li>
     *   <li>只跑不打 → 撤</li>
     *   <li>血太少：有吃的先吃，没吃的就撤</li>
     *   <li>被围了（敌人多）而且血不到一半 → 撤（硬拼基本是送）</li>
     *   <li>否则 → 打</li>
     * </ol>
     *
     * <p>注意「血少但有食物」选吃而不是撤：吃东西是原地回血，比拖着残血跑更快回到能打的状态；
     * 而没有食物时留在原地只是等死。</p>
     */
    public static Move decide(final SelfState self, final List<Threat> threats, final Settings settings) {
        if (threats == null || threats.isEmpty()) {
            return Move.DONE;
        }
        if (settings.fleeOnly()) {
            return Move.RETREAT;
        }
        if (self.health() <= settings.retreatHealth()) {
            return self.hasFood() ? Move.EAT : Move.RETREAT;
        }
        if (threats.size() >= settings.overwhelmedCount() && self.healthRatio() < 0.5) {
            return Move.RETREAT;
        }
        return Move.FIGHT;
    }

    /**
     * 挑一个最该打的目标，按这个顺序比：
     *
     * <ol>
     *   <li><b>正在打我的优先</b> —— 不还手就会一直挨打；</li>
     *   <li><b>够得着的优先</b> —— 先去追一个在墙后/头顶的，只会被别的怪白打；</li>
     *   <li>再比距离，近的优先。</li>
     * </ol>
     *
     * @return 没有威胁时返回 {@code null}
     */
    public static Threat pickTarget(final List<Threat> threats) {
        if (threats == null || threats.isEmpty()) {
            return null;
        }
        Threat best = null;
        for (final Threat threat : threats) {
            if (best == null || isBetterTarget(threat, best)) {
                best = threat;
            }
        }
        return best;
    }

    private static boolean isBetterTarget(final Threat candidate, final Threat current) {
        if (candidate.targetingMe() != current.targetingMe()) {
            return candidate.targetingMe();
        }
        if (candidate.canReach() != current.canReach()) {
            return candidate.canReach();
        }
        return candidate.distance() < current.distance();
    }

    /**
     * 算一个「往反方向跑」的落点：把威胁的加权中心当作「危险源」，
     * 从它沿着「我 → 危险源」的反方向走 {@code fleeDistance} 格。
     *
     * <p>如果威胁正好压在自己身上、或者前后夹击把方向抵消了，就固定往 +X 跑 ——
     * 总比原地不动强。</p>
     *
     * @param threatOffsets 每个威胁相对自己的水平偏移（dx, dz）
     * @return {@code {x, y, z}}，没有威胁时返回自己的位置
     */
    public static double[] retreatTargetFrom(final double selfX, final double selfY, final double selfZ,
                                             final List<double[]> threatOffsets, final double fleeDistance) {
        if (threatOffsets == null || threatOffsets.isEmpty()) {
            return new double[]{selfX, selfY, selfZ};
        }
        double sumX = 0;
        double sumZ = 0;
        for (final double[] offset : threatOffsets) {
            sumX += offset[0];
            sumZ += offset[1];
        }
        final double avgX = sumX / threatOffsets.size();
        final double avgZ = sumZ / threatOffsets.size();
        double awayX = -avgX;
        double awayZ = -avgZ;
        final double length = Math.sqrt(awayX * awayX + awayZ * awayZ);
        if (length < 0.001) {
            awayX = 1;
            awayZ = 0;
        } else {
            awayX /= length;
            awayZ /= length;
        }
        return new double[]{
                selfX + awayX * fleeDistance,
                selfY,
                selfZ + awayZ * fleeDistance,
        };
    }

    private DefenseTactics() {
    }
}
