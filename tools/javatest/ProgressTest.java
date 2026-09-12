package com.mcai.bridge.util;

import java.util.ArrayList;
import java.util.List;

/**
 * 卡死检测的单元测试。
 *
 * <p>这一段逻辑值得单独测，因为它是个<b>权衡</b>：判太松等于没有（用户抱怨的
 * 「执行到一半不动了」还是白等），判太紧会把正常行为误杀 —— 尤其是挖矿，
 * 玩家本来就是站着不动的，只看位移必然误判。这两种错误都只在游戏里才暴露，
 * 而它们其实完全可以在这里定死。</p>
 *
 * <p>时间由测试自己给，所以不用真的等 24 秒。</p>
 */
public final class ProgressTest {

    private static int passed;
    private static final List<String> FAILURES = new ArrayList<>();

    /** 和 Task 里用的一致：3 个 8 秒窗口 = 24 秒。 */
    private static final long WINDOW = 8_000L;
    private static final int STRIKES = 3;

    public static void main(final String[] args) {
        movingSection();
        miningSection();
        idleSection();
        interruptionSection();
        messageSection();

        System.out.println("=========================================================");
        System.out.println("通过 " + passed + " 项，失败 " + FAILURES.size() + " 项");
        for (final String failure : FAILURES) {
            System.out.println("  [FAIL] " + failure);
        }
        System.out.println("=========================================================");
        System.exit(FAILURES.isEmpty() ? 0 : 1);
    }

    // ------------------------------------------------------------ 一直动

    private static void movingSection() {
        System.out.println("\n-- 正常移动：绝不能误判 --");

        final ProgressWatchdog dog = new ProgressWatchdog(WINDOW, STRIKES);
        // 以 0.1 格/秒的速度慢慢挪（比正常走路慢得多，但确实在动）
        for (long t = 0; t <= 60_000; t += 50) {
            dog.feed(t / 1000.0 * 0.1, 64, 0, -1, t);
        }
        check(!dog.stuck(), "慢速移动一分钟：不判卡死", "strikes=" + dog.strikes());

        // 每个窗口内动一下就停 —— 走走停停也算在前进
        final ProgressWatchdog burst = new ProgressWatchdog(WINDOW, STRIKES);
        for (long t = 0; t <= 60_000; t += 50) {
            final double x = (t / WINDOW) * 1.0; // 每个窗口前进 1 格
            burst.feed(x, 64, 0, -1, t);
        }
        check(!burst.stuck(), "每个窗口走一格、其余时间不动：不判卡死",
                "strikes=" + burst.strikes());

        // 正好达到最小位移阈值
        final ProgressWatchdog exact = new ProgressWatchdog(WINDOW, STRIKES, 0.5, 0.001);
        for (long t = 0; t <= 40_000; t += 50) {
            exact.feed((t / WINDOW) * 0.5, 64, 0, -1, t);
        }
        check(!exact.stuck(), "位移刚好等于阈值（0.5 格/窗口）：算有进展",
                "strikes=" + exact.strikes());
    }

    // ------------------------------------------------------------ 挖矿

    private static void miningSection() {
        System.out.println("\n-- 站着挖矿：靠进展计数救回来 --");

        // 玩家一动不动，但每 1.5 秒挖掉一个方块
        final ProgressWatchdog dog = new ProgressWatchdog(WINDOW, STRIKES);
        int mined = 0;
        for (long t = 0; t <= 60_000; t += 50) {
            mined = (int) (t / 1500);
            dog.feed(10, 64, 10, mined, t);
        }
        check(!dog.stuck(), "原地挖矿一分钟（人不动、进展在涨）：不判卡死",
                "strikes=" + dog.strikes());

        // 进展涨得太慢：一个 8 秒窗口里只涨 0.0008，低于最小增幅 0.001 → 该判卡死
        final ProgressWatchdog slow = new ProgressWatchdog(WINDOW, STRIKES, 0.5, 0.001);
        for (long t = 0; t <= 30_000; t += 50) {
            slow.feed(10, 64, 10, t / 10_000_000.0, t);
        }
        check(slow.stuck(), "进展慢到可以忽略（0.0003/窗口）：判卡死",
                "strikes=" + slow.strikes());

        // 没有进展计数的任务（-1）：只能靠位移，站着不动就会被判
        final ProgressWatchdog noCounter = new ProgressWatchdog(WINDOW, STRIKES);
        for (long t = 0; t <= 30_000; t += 50) {
            noCounter.feed(10, 64, 10, -1, t);
        }
        check(noCounter.stuck(), "任务没有进展计数时（-1）：原地不动会判卡死",
                "strikes=" + noCounter.strikes());

        // 慢活儿：每 10 秒才出一次进展（比 8 秒的窗口还长）。
        // 窗口边界必然切出空窗，但这是「在干活」而不是卡死 —— 真机上熔炼铁锭就是这个形状，
        // 当时被误判成卡死（已出 2/3 却报「24 秒没有任何进展」）。
        final ProgressWatchdog slowWork = new ProgressWatchdog(WINDOW, STRIKES);
        for (long t = 0; t <= 120_000; t += 50) {
            slowWork.feed(10, 64, 10, t / 10_000, t); // 每 10000ms 涨 1
        }
        check(!slowWork.stuck(),
                "每 10 秒才出一次的慢活儿（比如熔炼）：不能误判成卡死",
                "strikes=" + slowWork.strikes());

        // 但「连续」真的不动，还是要判
        final ProgressWatchdog slowThenDead = new ProgressWatchdog(WINDOW, STRIKES);
        for (long t = 0; t <= 20_000; t += 50) {
            slowThenDead.feed(10, 64, 10, t / 10_000, t);
        }
        for (long t = 20_050; t <= 80_000; t += 50) {
            slowThenDead.feed(10, 64, 10, 2, t); // 到 2 就不动了
        }
        check(slowThenDead.stuck(), "干活干到一半停住不动：仍然要判出来",
                "strikes=" + slowThenDead.strikes());
    }

    // ------------------------------------------------------------ 卡死

    private static void idleSection() {
        System.out.println("\n-- 真的卡住：要在预期时间报出来 --");

        final ProgressWatchdog dog = new ProgressWatchdog(WINDOW, STRIKES);
        long stuckAt = -1;
        for (long t = 0; t <= 60_000; t += 50) {
            dog.feed(5, 64, 5, -1, t);
            if (dog.stuck() && stuckAt < 0) {
                stuckAt = t;
            }
        }
        check(dog.stuck(), "完全不动：判卡死", "");
        // 3 个 8 秒窗口 → 第 24 秒左右报出来；给一个 tick 的余量
        check(stuckAt >= 24_000 && stuckAt <= 24_200L,
                "在第 24 秒左右报出来（配置写几秒就是几秒）", "实际 " + stuckAt + "ms");
        check(dog.stuckForMs() == 24_000L, "报告的卡住时长是 24 秒",
                dog.stuckForMs() + "ms");
        check(dog.strikes() >= 3, "累计的「没进展」次数至少 3", String.valueOf(dog.strikes()));

        // 判死之后不会自己恢复
        dog.feed(500, 64, 500, 99, 60_050);
        check(dog.stuck(), "判死之后即使动了也保持卡死状态（由上层收尾）", "");

        // 一个窗口内偶尔动一下但没到阈值（0.4 < 0.5）→ 仍然算没进展
        final ProgressWatchdog tiny = new ProgressWatchdog(WINDOW, STRIKES, 0.5, 0.001);
        for (long t = 0; t <= 30_000; t += 50) {
            tiny.feed((t / WINDOW) * 0.4, 64, 0, -1, t);
        }
        check(tiny.stuck(), "每窗口只挪 0.4 格（不够 0.5）：仍然判卡死",
                "strikes=" + tiny.strikes());
    }

    // ------------------------------------------------------------ 中断

    private static void interruptionSection() {
        System.out.println("\n-- 观测中断（暂停、失焦、卡顿）不能算卡死 --");

        // 前 5 秒不动，然后「暂停」了 60 秒（没有任何 tick），再回来接着不动
        final ProgressWatchdog dog = new ProgressWatchdog(WINDOW, STRIKES);
        for (long t = 0; t <= 5_000; t += 50) {
            dog.feed(5, 64, 5, -1, t);
        }
        dog.feed(5, 64, 5, -1, 65_000); // 暂停 60 秒后的第一次 tick
        check(!dog.stuck(), "切出去一分钟再切回来：不判卡死（观测中断不算）",
                "strikes=" + dog.strikes());
        check(dog.strikes() == 0, "中断的那次不记「没进展」", "strikes=" + dog.strikes());

        // 回来之后如果还是不动，照常判
        for (long t = 65_050; t <= 100_000; t += 50) {
            dog.feed(5, 64, 5, -1, t);
        }
        check(dog.stuck(), "回来之后继续不动：该判还是判", "");
    }

    // ------------------------------------------------------------ 文案

    private static void messageSection() {
        System.out.println("\n-- 报错文案要说清「怎么办」 --");

        final ProgressWatchdog dog = new ProgressWatchdog(WINDOW, STRIKES);
        for (long t = 0; t <= 30_000; t += 50) {
            dog.feed(5, 64, 5, -1, t);
        }
        final String text = dog.describe("mine_blocks(block=stone, count=64)");
        check(text.contains("24 秒"), "文案里写了卡了多久", text);
        check(text.contains("mine_blocks"), "文案里带上了当时在做的事", text);
        check(text.contains("失去焦点") || text.contains("暂停"),
                "文案提到了「窗口失焦/世界暂停」这个最常见的原因", text);
        check(text.contains("scan_blocks"), "文案给了下一步该干嘛", text);
    }

    // ------------------------------------------------------------ 断言

    private static void check(final boolean ok, final String name, final String detail) {
        if (ok) {
            passed++;
            System.out.println("  [PASS] " + name);
        } else {
            FAILURES.add(name + "  " + detail);
            System.out.println("  [FAIL] " + name + "  " + detail);
        }
    }

    private ProgressTest() {
    }
}
