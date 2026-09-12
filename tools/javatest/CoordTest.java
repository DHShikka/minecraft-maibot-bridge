package com.mcai.bridge.action.tasks;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * 方块坐标解析的单元测试。
 *
 * <p>为什么要单独测这几十行：{@code "~"} 相对坐标是脚本能用的前提 ——
 * 脚本是提前写好的，写的时候不可能知道玩家会站在哪。而它错起来是<b>静默</b>的：
 * 少减一格、把 {@code "~-1"} 解析成 0，游戏里表现为「方块放到了奇怪的地方」，
 * 光看日志很难反应过来是坐标解析的问题。</p>
 *
 * <p>这个类只调静态方法，不需要开游戏。</p>
 */
public final class CoordTest {

    private static int passed;
    private static final List<String> FAILURES = new ArrayList<>();

    /** 假装的玩家位置：脚下方块 (100, 64, -20)。 */
    private static final BlockPos ORIGIN = new BlockPos(100, 64, -20);

    public static void main(final String[] args) {
        absoluteSection();
        relativeSection();
        edgeSection();

        System.out.println("=========================================================");
        System.out.println("通过 " + passed + " 项，失败 " + FAILURES.size() + " 项");
        for (final String failure : FAILURES) {
            System.out.println("  [FAIL] " + failure);
        }
        System.out.println("=========================================================");
        System.exit(FAILURES.isEmpty() ? 0 : 1);
    }

    // ------------------------------------------------------------ 绝对坐标

    private static void absoluteSection() {
        System.out.println("\n-- 绝对坐标（老行为不能变） --");

        expect("整数", "{\"x\":10,\"y\":64,\"z\":-3}", new BlockPos(10, 64, -3));
        expect("小数向下取整", "{\"x\":10.7,\"y\":64.2,\"z\":-3.9}", new BlockPos(10, 64, -4));
        expect("数字字符串也认", "{\"x\":\"10\",\"y\":\"64\",\"z\":\"-3\"}", new BlockPos(10, 64, -3));
        expect("pos 对象写法", "{\"pos\":{\"x\":1,\"y\":2,\"z\":3}}", new BlockPos(1, 2, 3));
        check(read("{\"y\":1,\"z\":2}") == null, "缺 x 就是解析不了（不能瞎猜一个坐标）", "");
    }

    // ------------------------------------------------------------ 相对坐标

    private static void relativeSection() {
        System.out.println("\n-- 相对坐标 ~ --");

        expect("三个都是 ~", "{\"x\":\"~\",\"y\":\"~\",\"z\":\"~\"}", ORIGIN);
        expect("~-1 往下一格", "{\"x\":\"~\",\"y\":\"~-1\",\"z\":\"~\"}",
                ORIGIN.below(1));
        expect("~-1 三个方向都减", "{\"x\":\"~-1\",\"y\":\"~-1\",\"z\":\"~-1\"}",
                new BlockPos(99, 63, -21));
        expect("~+2 / ~2 都是加", "{\"x\":\"~2\",\"y\":\"~+2\",\"z\":\"~\"}",
                new BlockPos(102, 66, -20));
        expect("混着写：绝对 + 相对", "{\"x\":5,\"y\":\"~-1\",\"z\":\"~3\"}",
                new BlockPos(5, 63, -17));
        expect("~ 两边有空格也认", "{\"x\":\" ~ \",\"y\":\" ~-1 \",\"z\":\"~\"}",
                ORIGIN.below(1));
    }

    // ------------------------------------------------------------ 边界

    private static void edgeSection() {
        System.out.println("\n-- 边界 --");

        // 没有基准（origin == null）时，"~" 必须明确失败，而不是猜一个 0
        check(readNoOrigin("{\"x\":\"~\",\"y\":64,\"z\":1}") == null,
                "没有玩家位置时 ~ 解析不了，返回 null（让调用方报人话）", "");
        check(readNoOrigin("{\"x\":\"~-1\",\"y\":64,\"z\":1}") == null,
                "没有玩家位置时 ~-1 也解析不了", "");
        // 绝对值在没有基准时照常工作
        expect("没有基准也不影响绝对坐标", "{\"x\":1,\"y\":2,\"z\":3}", new BlockPos(1, 2, 3));

        check(read("{\"x\":\"~~\",\"y\":1,\"z\":1}") == null,
                "畸形的 ~~ 解析不了", "");
        check(read("{\"x\":\"~abc\",\"y\":1,\"z\":1}") == null,
                "~abc 解析不了", "");
        check(read("{\"y\":1,\"z\":1}") == null, "完全没有 x 时返回 null", "");

        // hasBlockPos：区分「没写坐标」和「写了但解析不了」
        check(BasicTasks.hasBlockPos(json("{\"x\":1,\"y\":2,\"z\":3}")),
                "hasBlockPos：绝对坐标", "");
        check(BasicTasks.hasBlockPos(json("{\"x\":\"~\",\"y\":\"~-1\",\"z\":\"~\"}")),
                "hasBlockPos：相对坐标", "");
        check(BasicTasks.hasBlockPos(json("{\"pos\":{\"x\":1,\"y\":2,\"z\":3}}")),
                "hasBlockPos：pos 对象", "");
        check(!BasicTasks.hasBlockPos(json("{\"item\":\"torch\"}")),
                "hasBlockPos：没有坐标", "");

        // 缺分量时保持「当 0」的老行为（绝对语义）
        expect("缺 y/z 时当 0（老行为）", "{\"x\":7}", new BlockPos(7, 0, 0));
    }

    // ------------------------------------------------------------ 断言

    private static void expect(final String name, final String params, final BlockPos want) {
        final BlockPos got = read(params);
        check(want.equals(got), name, "期望 " + want + "，实际 " + got);
    }

    private static BlockPos read(final String params) {
        return BasicTasks.readBlockPos(json(params), ORIGIN);
    }

    /** 没有玩家位置时（例如任务工厂在创建阶段就想解析坐标）。 */
    private static BlockPos readNoOrigin(final String params) {
        return BasicTasks.readBlockPos(json(params), null);
    }

    private static JsonObject json(final String text) {
        return JsonParser.parseString(text).getAsJsonObject();
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

    private CoordTest() {
    }
}
