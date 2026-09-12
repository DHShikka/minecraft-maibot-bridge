package com.mcai.bridge.action.tasks;

import java.util.ArrayList;
import java.util.List;

/**
 * 防御战术决策的单元测试。
 *
 * <p>「抵御」的代码量不大，但里面全是<b>取舍</b>：血少该吃还是该跑、被围了要不要硬拼、
 * 先打哪个。这些判断在游戏里出错的表现是「AI 站着被打死」或者「明明能打却一直逃」——
 * 两种都很难从日志里看出来。它们是纯函数，所以在这里把每种局面钉死。</p>
 */
public final class DefenseTest {

    private static int passed;
    private static final List<String> FAILURES = new ArrayList<>();

    private static final DefenseTactics.Settings DEFAULTS = DefenseTactics.Settings.defaults();

    public static void main(final String[] args) {
        noThreatSection();
        lowHealthSection();
        overwhelmedSection();
        targetSection();
        retreatSection();

        System.out.println("=========================================================");
        System.out.println("通过 " + passed + " 项，失败 " + FAILURES.size() + " 项");
        for (final String failure : FAILURES) {
            System.out.println("  [FAIL] " + failure);
        }
        System.out.println("=========================================================");
        System.exit(FAILURES.isEmpty() ? 0 : 1);
    }

    // ------------------------------------------------------------ 没威胁

    private static void noThreatSection() {
        System.out.println("\n-- 没有威胁就收工 --");

        final DefenseTactics.SelfState healthy = self(20, true, false);
        check(DefenseTactics.decide(healthy, List.of(), DEFAULTS) == DefenseTactics.Move.DONE,
                "空列表 → DONE", "");
        check(DefenseTactics.decide(healthy, null, DEFAULTS) == DefenseTactics.Move.DONE,
                "null 也当没有威胁（别 NPE）", "");
        check(DefenseTactics.decide(self(3, false, false), List.of(), DEFAULTS) == DefenseTactics.Move.DONE,
                "残血但没威胁 → 也是 DONE（该收工就收工）", "");
    }

    // ------------------------------------------------------------ 血量

    private static void lowHealthSection() {
        System.out.println("\n-- 血少：有吃的先吃，没吃的才跑 --");

        final List<DefenseTactics.Threat> one = List.of(threat("z1", 5, false, true));

        check(DefenseTactics.decide(self(5, true, true), one, DEFAULTS) == DefenseTactics.Move.EAT,
                "血 5（低于 8）+ 有食物 → 先吃", "");
        check(DefenseTactics.decide(self(5, true, false), one, DEFAULTS) == DefenseTactics.Move.RETREAT,
                "血 5 + 没食物 → 撤（留在原地只是等死）", "");
        check(DefenseTactics.decide(self(9, true, false), one, DEFAULTS) == DefenseTactics.Move.FIGHT,
                "血 9（高于 8）→ 打", "");
        check(DefenseTactics.decide(self(8, true, true), one, DEFAULTS) == DefenseTactics.Move.EAT,
                "血正好等于阈值 8 → 也算血少（<= 而不是 <）", "");
        check(DefenseTactics.decide(self(1, false, true), one, DEFAULTS) == DefenseTactics.Move.EAT,
                "只剩 1 滴血、手上没武器但有食物 → 还是先吃（吃完才有得打）", "");
    }

    // ------------------------------------------------------------ 被围

    private static void overwhelmedSection() {
        System.out.println("\n-- 被围：人多且血少就先撤 --");

        final List<DefenseTactics.Threat> three = List.of(
                threat("z1", 3, true, true),
                threat("z2", 4, true, true),
                threat("z3", 5, false, true));
        final List<DefenseTactics.Threat> two = List.of(
                threat("z1", 3, true, true),
                threat("z2", 4, true, true));

        // 注意血量要取在「血少阈值 8」和「半血 10」之间（也就是 9），
        // 否则会先命中「血少」那条规则，测不到「被围」这条。
        check(DefenseTactics.decide(self(9, false, true), three, DEFAULTS) == DefenseTactics.Move.RETREAT,
                "3 个敌人 + 血 9（不到一半但高于血线）→ 撤（硬拼基本是送）", "");
        check(DefenseTactics.decide(self(1, false, false), three, DEFAULTS) == DefenseTactics.Move.RETREAT,
                "3 个敌人 + 残血 + 没食物 → 撤", "");
        check(DefenseTactics.decide(self(20, true, false), three, DEFAULTS) == DefenseTactics.Move.FIGHT,
                "3 个敌人但满血 → 照样打", "");
        check(DefenseTactics.decide(self(12, true, false), three, DEFAULTS) == DefenseTactics.Move.FIGHT,
                "3 个敌人 + 血 12（六成）→ 还是打（被围只是「血也不多」时才撤）", "");
        // 和上面那条只差敌人数量，正好看出阈值在起作用
        check(DefenseTactics.decide(self(9, false, true), two, DEFAULTS) == DefenseTactics.Move.FIGHT,
                "血 9 + 只有 2 个（没到「被围」阈值 3）→ 打（因为没得选）", "");
        // 「有食物」比「被围」优先：先吃一口再看
        check(DefenseTactics.decide(self(4, false, true), three, DEFAULTS) == DefenseTactics.Move.EAT,
                "残血 + 被围 + 有食物 → 先吃（优先级在「被围」之前）", "");

        final DefenseTactics.Settings flee = DefenseTactics.Settings.of(16, 8, 3, true);
        check(DefenseTactics.decide(self(20, true, true), three, flee) == DefenseTactics.Move.RETREAT,
                "flee=true：满血也只跑不打", "");
    }

    // ------------------------------------------------------------ 选目标

    private static void targetSection() {
        System.out.println("\n-- 选哪个打 --");

        check(DefenseTactics.pickTarget(List.of()) == null, "没有威胁 → null", "");
        check(DefenseTactics.pickTarget(null) == null, "null → null", "");

        final DefenseTactics.Threat only = threat("solo", 7, false, true);
        check(DefenseTactics.pickTarget(List.of(only)) == only, "只有一个就打它", "");

        // 正在打我的那个更远，但必须优先
        final DefenseTactics.Threat near = threat("near", 3, false, true);
        final DefenseTactics.Threat far = threat("far", 9, true, true);
        check(DefenseTactics.pickTarget(List.of(near, far)) == far,
                "先打「正在打我」的，哪怕它更远（不还手会一直挨打）", "");

        // 都不打我 → 打最近的
        final DefenseTactics.Threat a = threat("a", 6, false, true);
        final DefenseTactics.Threat b = threat("b", 2, false, true);
        check(DefenseTactics.pickTarget(List.of(a, b)) == b, "都不打我 → 打最近的", "");

        // 都打我 → 打最近的
        final DefenseTactics.Threat c = threat("c", 8, true, true);
        final DefenseTactics.Threat d = threat("d", 4, true, true);
        check(DefenseTactics.pickTarget(List.of(c, d)) == d, "都打我 → 打最近的", "");

        // 够得着的优先于够不着的（哪怕够不着的更近）
        final DefenseTactics.Threat unreachable = threat("up", 2, false, false);
        final DefenseTactics.Threat reachable = threat("flat", 7, false, true);
        check(DefenseTactics.pickTarget(List.of(unreachable, reachable)) == reachable,
                "优先够得着的（去追墙后/头顶的只会被别的怪白打）", "");

        // 优先级：正在打我 > 够得着 > 近
        final DefenseTactics.Threat aggroUnreachable = threat("au", 15, true, false);
        final DefenseTactics.Threat calmNear = threat("cn", 2, false, true);
        check(DefenseTactics.pickTarget(List.of(calmNear, aggroUnreachable)) == aggroUnreachable,
                "「正在打我」压过「够得着」", "");
    }

    // ------------------------------------------------------------ 撤退方向

    private static void retreatSection() {
        System.out.println("\n-- 往哪撤 --");

        // 威胁在 +X 方向 → 往 -X 跑
        final double[] awayFromPlusX = DefenseTactics.retreatTargetFrom(0, 64, 0, List.of(new double[]{5, 0}), 10);
        check(near(awayFromPlusX[0], -10) && near(awayFromPlusX[2], 0),
                "威胁在 +X → 往 -X 撤 10 格", fmt(awayFromPlusX));

        final double[] awayFromPlusZ = DefenseTactics.retreatTargetFrom(0, 64, 0, List.of(new double[]{0, 5}), 10);
        check(near(awayFromPlusZ[0], 0) && near(awayFromPlusZ[2], -10),
                "威胁在 +Z → 往 -Z 撤", fmt(awayFromPlusZ));

        // 斜向：距离必须正好等于 fleeDistance（方向要归一化）
        final double[] diagonal = DefenseTactics.retreatTargetFrom(3, 64, -2, List.of(new double[]{4, 4}), 12);
        final double dx = diagonal[0] - 3;
        final double dz = diagonal[2] - (-2);
        check(near(Math.sqrt(dx * dx + dz * dz), 12),
                "斜向撤退距离正好是 12 格（方向做过归一化）", fmt(diagonal));
        check(dx < 0 && dz < 0, "斜向也是往反方向（-X -Z）", fmt(diagonal));

        // 多个威胁：按平均方向跑
        final double[] multi = DefenseTactics.retreatTargetFrom(0, 64, 0,
                List.of(new double[]{8, 0}, new double[]{6, 0}), 10);
        check(near(multi[0], -10), "两个威胁都在 +X → 还是往 -X", fmt(multi));

        // 威胁压在身上：方向退化，必须挑一个固定方向而不是原地不动
        final double[] onTop = DefenseTactics.retreatTargetFrom(0, 64, 0, List.of(new double[]{0, 0}), 10);
        check(near(Math.abs(onTop[0]) + Math.abs(onTop[2]), 10),
                "威胁正好在自己身上 → 仍然要跑开 10 格，而不是原地不动", fmt(onTop));

        // 前后夹击抵消：同样必须挑固定方向
        final double[] pinched = DefenseTactics.retreatTargetFrom(0, 64, 0,
                List.of(new double[]{5, 0}, new double[]{-5, 0}), 10);
        check(near(Math.abs(pinched[0]) + Math.abs(pinched[2]), 10),
                "前后夹击方向抵消 → 也要跑开 10 格", fmt(pinched));

        // 没有威胁：返回原位置
        final double[] none = DefenseTactics.retreatTargetFrom(7, 64, 9, List.of(), 10);
        check(near(none[0], 7) && near(none[1], 64) && near(none[2], 9),
                "没有威胁时返回原地（不会乱跑）", fmt(none));

        // Y 不该被改（水平撤退，不要往坑里/天上窜）
        check(near(diagonal[1], 64), "撤退只改水平方向，Y 保持不变", fmt(diagonal));
    }

    // ------------------------------------------------------------ 辅助

    private static DefenseTactics.Threat threat(final String id, final double distance,
                                                final boolean targetingMe, final boolean canReach) {
        return new DefenseTactics.Threat(id, "怪-" + id, distance, targetingMe, canReach);
    }

    private static DefenseTactics.SelfState self(final double health, final boolean weapon, final boolean food) {
        return new DefenseTactics.SelfState(health, 20, weapon, food);
    }

    private static boolean near(final double a, final double b) {
        return Math.abs(a - b) < 0.01;
    }

    private static String fmt(final double[] v) {
        return String.format("(%.2f, %.2f, %.2f)", v[0], v[1], v[2]);
    }

    private static void check(final boolean ok, final String name, final String detail) {
        if (ok) {
            passed++;
            System.out.println("  [PASS] " + name);
        } else {
            FAILURES.add(name + "  " + detail);
            System.out.println("  [FAIL] " + name + "  " + detail);
        }
    }

    private DefenseTest() {
    }
}
