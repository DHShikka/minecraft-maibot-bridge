package com.mcai.bridge.script;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mcai.bridge.script.ScriptVm.Result;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 脚本引擎的单元测试：解析、条件求值、控制流。
 *
 * <p>为什么值得单独测：脚本是「一次下发、模组本地连续执行」，一旦控制流写错，
 * 表现出来是「AI 说做完了但东西没做出来」或者「卡在某个循环里不出来」——
 * 这两种都很难从游戏里看出来。而控制流和条件求值本身是<b>纯逻辑</b>，
 * 只要把「启动动作」和「读世界」抽象成两个接口，就能在不开游戏的情况下测干净。</p>
 *
 * <p>用法：{@code java -cp <classpath> com.mcai.bridge.script.ScriptTest}</p>
 */
public final class ScriptTest {

    private static int passed;
    private static final List<String> FAILURES = new ArrayList<>();

    public static void main(final String[] args) {
        parseSection();
        conditionSection();
        controlFlowSection();
        safetySection();

        System.out.println("=========================================================");
        System.out.println("通过 " + passed + " 项，失败 " + FAILURES.size() + " 项");
        for (final String failure : FAILURES) {
            System.out.println("  [FAIL] " + failure);
        }
        System.out.println("=========================================================");
        System.exit(FAILURES.isEmpty() ? 0 : 1);
    }

    // ============================================================== 解析

    private static void parseSection() {
        System.out.println("\n-- 脚本解析 --");

        ok("最小脚本", "{\"steps\":[{\"action\":\"chat\",\"params\":{\"message\":\"hi\"}}]}");
        ok("控制流齐全", "{\"steps\":["
                + "{\"repeat\":2,\"steps\":[{\"action\":\"jump\"}]},"
                + "{\"while\":{\"condition\":{\"has\":{\"item\":\"oak_log\",\"count\":1}},\"maxIterations\":5},"
                + "  \"steps\":[{\"action\":\"mine_blocks\"}]},"
                + "{\"if\":{\"condition\":{\"healthBelow\":10}},\"then\":[{\"action\":\"mc_sleep\"}],"
                + " \"else\":[{\"action\":\"wait\"}]},"
                + "{\"waitUntil\":{\"condition\":{\"holding\":\"stone_sword\"},\"timeoutMs\":5000}}"
                + "]}");

        // has 的简写形式
        okHas("has 简写（字符串）", "{\"steps\":[{\"waitUntil\":{\"condition\":{\"has\":\"oak_log\"}}}]}");

        fails("缺少 steps", "{\"name\":\"x\"}", "缺少", "steps");
        fails("steps 为空数组", "{\"steps\":[]}", "不能为空");
        fails("步骤既没 action 也没控制流", "{\"steps\":[{\"foo\":1}]}", "action", "repeat");
        fails("repeat 次数为 0", "{\"steps\":[{\"repeat\":0,\"steps\":[{\"action\":\"jump\"}]}]}", ">= 1");
        fails("repeat 次数过大", "{\"steps\":[{\"repeat\":9999,\"steps\":[{\"action\":\"jump\"}]}]}", "上限");
        fails("while 缺 condition", "{\"steps\":[{\"while\":{},\"steps\":[{\"action\":\"jump\"}]}]}", "condition");
        fails("if 缺 condition", "{\"steps\":[{\"if\":{},\"then\":[{\"action\":\"jump\"}]}]}", "condition");
        fails("if 缺 then", "{\"steps\":[{\"if\":{\"condition\":{\"always\":true}}}]}", "then");
        fails("未知条件名", "{\"steps\":[{\"if\":{\"condition\":{\"hasX\":1}},\"then\":[{\"action\":\"jump\"}]}]}",
                "不认识的条件", "has");
        fails("条件对象有多个键", "{\"steps\":[{\"if\":{\"condition\":{\"always\":true,\"busy\":false}},"
                + "\"then\":[{\"action\":\"jump\"}]}]}", "恰好");
        fails("waitUntil 缺 condition", "{\"steps\":[{\"waitUntil\":{\"timeoutMs\":3000}}]}", "condition");
        fails("atPos 缺坐标", "{\"steps\":[{\"if\":{\"condition\":{\"atPos\":{\"x\":1}}},"
                + "\"then\":[{\"action\":\"jump\"}]}]}", "缺少字段");
        fails("错误信息带位置", "{\"steps\":[{\"action\":\"jump\"},{\"action\":\"wait\"},{\"oops\":1}]}",
                "steps[2]");

        // 嵌套节点数上限
        final StringBuilder deep = new StringBuilder("{\"steps\":[");
        for (int i = 0; i < 450; i++) {
            if (i > 0) {
                deep.append(',');
            }
            deep.append("{\"action\":\"jump\"}");
        }
        deep.append("]}");
        fails("节点数超过上限", deep.toString(), "节点太多");
    }

    private static void ok(final String name, final String json) {
        try {
            final ScriptProgram program = ScriptProgram.parse(parse(json));
            check(true, name, "");
            if (program.nodeCount() <= 0) {
                fail(name + "（nodeCount 应 > 0）", "");
            }
        } catch (final Exception e) {
            fail(name, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static void okHas(final String name, final String json) {
        try {
            ScriptProgram.parse(parse(json));
            check(true, name, "");
        } catch (final Exception e) {
            fail(name, e.getMessage());
        }
    }

    private static void fails(final String name, final String json, final String... mustContain) {
        try {
            ScriptProgram.parse(parse(json));
            fail(name, "本该解析失败，却成功了");
        } catch (final ScriptParseException e) {
            final String message = e.getMessage() == null ? "" : e.getMessage();
            final List<String> missing = new ArrayList<>();
            for (final String needle : mustContain) {
                if (!message.contains(needle)) {
                    missing.add(needle);
                }
            }
            check(missing.isEmpty(), name,
                    missing.isEmpty() ? "" : "报错里缺少关键词 " + missing + "；实际：" + message);
        } catch (final Exception e) {
            fail(name, "抛出了非预期异常 " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    // ============================================================== 条件

    private static void conditionSection() {
        System.out.println("\n-- 条件求值 --");

        final FakeContext ctx = new FakeContext();
        ctx.items.put("oak_log", 5);
        ctx.holdingSpec = "iron_pickaxe";
        ctx.health = 8;
        ctx.food = 4;
        ctx.px = 1.4;
        ctx.py = 65;
        ctx.pz = 0.2;
        ctx.nearby.put("zombie", 2);
        ctx.nearby.put("hostile", 2);
        ctx.busy = false;

        checkCond("has 够", ctx, "{\"has\":{\"item\":\"oak_log\",\"count\":3}}", true);
        checkCond("has 不够", ctx, "{\"has\":{\"item\":\"oak_log\",\"count\":9}}", false);
        checkCond("has 简写默认 1 个", ctx, "{\"has\":\"oak_log\"}", true);
        checkCond("holding 命中", ctx, "{\"holding\":\"iron_pickaxe\"}", true);
        checkCond("holding 未命中", ctx, "{\"holding\":\"diamond\"}", false);
        checkCond("healthBelow 命中", ctx, "{\"healthBelow\":10}", true);
        checkCond("healthBelow 未命中", ctx, "{\"healthBelow\":5}", false);
        checkCond("foodBelow 命中", ctx, "{\"foodBelow\":6}", true);
        checkCond("atPos 命中", ctx, "{\"atPos\":{\"x\":1,\"y\":65,\"z\":0,\"radius\":2}}", true);
        checkCond("atPos 未命中", ctx, "{\"atPos\":{\"x\":50,\"y\":65,\"z\":0,\"radius\":2}}", false);
        checkCond("nearby 命中", ctx, "{\"nearby\":{\"type\":\"zombie\",\"radius\":8,\"min\":1}}", true);
        checkCond("nearby 数量不够", ctx, "{\"nearby\":{\"type\":\"zombie\",\"radius\":8,\"min\":5}}", false);
        checkCond("busy=false 命中", ctx, "{\"busy\":false}", true);
        checkCond("busy=true 未命中", ctx, "{\"busy\":true}", false);
        checkCond("all 全真", ctx,
                "{\"all\":[{\"has\":\"oak_log\"},{\"healthBelow\":10}]}", true);
        checkCond("all 有一假", ctx,
                "{\"all\":[{\"has\":\"oak_log\"},{\"healthBelow\":5}]}", false);
        checkCond("any 有一真", ctx,
                "{\"any\":[{\"has\":\"diamond\"},{\"holding\":\"iron_pickaxe\"}]}", true);
        checkCond("any 全假", ctx,
                "{\"any\":[{\"has\":\"diamond\"},{\"foodBelow\":1}]}", false);
        checkCond("not 取反", ctx, "{\"not\":{\"has\":\"diamond\"}}", true);
        checkCond("嵌套组合", ctx,
                "{\"all\":[{\"any\":[{\"has\":\"diamond\"},{\"has\":\"oak_log\"}]},{\"not\":{\"busy\":true}}]}", true);
        checkCond("always", ctx, "{\"always\":true}", true);
    }

    private static void checkCond(final String name, final FakeContext ctx,
                                  final String json, final boolean expected) {
        try {
            final Condition condition = Condition.parse(parse(json), "test");
            final boolean actual = condition.test(ctx);
            check(actual == expected, name,
                    actual == expected ? "" : "期望 " + expected + "，实际 " + actual
                            + "（" + condition.describe() + "）");
        } catch (final Exception e) {
            fail(name, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    // ============================================================== 控制流

    private static void controlFlowSection() {
        System.out.println("\n-- 控制流 --");

        // 顺序执行
        {
            final FakeRunner runner = new FakeRunner();
            final Result r = run("{\"steps\":[{\"action\":\"a\"},{\"action\":\"b\"},{\"action\":\"c\"}]}",
                    runner, new FakeContext());
            check(r != null && r.ok(), "顺序执行：三个动作依次跑完", r == null ? "没跑完" : r.render());
            check(r != null && runner.started.equals(List.of("a", "b", "c")),
                    "顺序执行：顺序正确（不是并发也不是乱序）",
                    String.valueOf(runner.started));
            check(r != null && r.stepsDone() == 3, "顺序执行：步骤计数为 3",
                    r == null ? "" : String.valueOf(r.stepsDone()));
        }

        // 失败中止
        {
            final FakeRunner runner = new FakeRunner();
            runner.failing.add("b");
            final Result r = run("{\"steps\":[{\"action\":\"a\"},{\"action\":\"b\"},{\"action\":\"c\"}]}",
                    runner, new FakeContext());
            check(r != null && !r.ok(), "失败中止：脚本整体失败", r == null ? "没跑完" : r.render());
            check(r != null && r.error() != null && r.error().contains("b"),
                    "失败中止：错误里点名了是哪个动作", r == null ? "" : String.valueOf(r.error()));
            check(runner.started.equals(List.of("a", "b")),
                    "失败中止：后面的动作没有被执行", String.valueOf(runner.started));
            check(runner.aborted, "失败中止：调用了 abort 收尾", "");
        }

        // optional 跳过
        {
            final FakeRunner runner = new FakeRunner();
            runner.failing.add("b");
            final Result r = run("{\"steps\":[{\"action\":\"a\"},"
                    + "{\"action\":\"b\",\"optional\":true},{\"action\":\"c\"}]}",
                    runner, new FakeContext());
            check(r != null && r.ok(), "optional：失败被跳过后脚本仍然成功",
                    r == null ? "没跑完" : r.render());
            check(runner.started.equals(List.of("a", "b", "c")),
                    "optional：后续动作照常执行", String.valueOf(runner.started));
            check(r != null && r.log().stream().anyMatch(l -> l.contains("跳过")),
                    "optional：日志里明确写了跳过", r == null ? "" : String.valueOf(r.log()));
        }

        // 动作不存在 → 报错带位置
        {
            final FakeRunner runner = new FakeRunner();
            runner.unknown.add("nope");
            final Result r = run("{\"steps\":[{\"action\":\"a\"},{\"action\":\"nope\"}]}",
                    runner, new FakeContext());
            check(r != null && !r.ok() && r.error() != null && r.error().contains("nope"),
                    "未知动作：脚本失败并说明是哪个动作",
                    r == null ? "没跑完" : String.valueOf(r.error()));
        }

        // repeat
        {
            final FakeRunner runner = new FakeRunner();
            final Result r = run("{\"steps\":[{\"repeat\":3,\"steps\":[{\"action\":\"hit\"}]}]}",
                    runner, new FakeContext());
            check(r != null && r.ok(), "repeat：跑完", r == null ? "没跑完" : r.render());
            check(runner.started.equals(List.of("hit", "hit", "hit")),
                    "repeat 3：动作正好执行 3 次", String.valueOf(runner.started));
        }

        // while 条件由真变假
        {
            final FakeRunner runner = new FakeRunner();
            final FakeContext ctx = new FakeContext();
            ctx.items.put("cobblestone", 3);
            // 每次 mine 动作都会让 countItem 减少，模拟「挖够了」
            runner.onStart = action -> {
                if ("mine".equals(action)) {
                    ctx.items.merge("cobblestone", -1, Integer::sum);
                }
            };
            final Result r = run("{\"steps\":[{\"while\":{\"condition\":"
                    + "{\"has\":{\"item\":\"cobblestone\",\"count\":1}},\"maxIterations\":10},"
                    + "\"steps\":[{\"action\":\"mine\"}]}]}", runner, ctx);
            check(r != null && r.ok(), "while：条件变假后正常退出", r == null ? "没跑完" : r.render());
            check(runner.started.size() == 3,
                    "while：跑了 3 轮（条件一开始有 3 个，挖完就停）",
                    String.valueOf(runner.started));
        }

        // while 上限兜底（死循环保护）
        {
            final FakeRunner runner = new FakeRunner();
            final FakeContext ctx = new FakeContext();
            ctx.items.put("cobblestone", 999);
            final Result r = run("{\"steps\":[{\"while\":{\"condition\":"
                    + "{\"has\":{\"item\":\"cobblestone\",\"count\":1}},\"maxIterations\":4},"
                    + "\"steps\":[{\"action\":\"mine\"}]}]}", runner, ctx);
            check(r != null && r.ok(), "while：达到 maxIterations 后停下来（不会死循环）",
                    r == null ? "没跑完" : r.render());
            check(runner.started.size() == 4, "while：正好跑满 4 轮上限",
                    String.valueOf(runner.started.size()));
        }

        // if / else
        {
            final FakeRunner thenRunner = new FakeRunner();
            final FakeContext ctx = new FakeContext();
            ctx.health = 5;
            run("{\"steps\":[{\"if\":{\"condition\":{\"healthBelow\":10}},"
                    + "\"then\":[{\"action\":\"eat\"}],\"else\":[{\"action\":\"fight\"}]}]}",
                    thenRunner, ctx);
            check(thenRunner.started.equals(List.of("eat")), "if：条件为真走 then",
                    String.valueOf(thenRunner.started));

            final FakeRunner elseRunner = new FakeRunner();
            final FakeContext ctx2 = new FakeContext();
            ctx2.health = 20;
            run("{\"steps\":[{\"if\":{\"condition\":{\"healthBelow\":10}},"
                    + "\"then\":[{\"action\":\"eat\"}],\"else\":[{\"action\":\"fight\"}]}]}",
                    elseRunner, ctx2);
            check(elseRunner.started.equals(List.of("fight")), "if：条件为假走 else",
                    String.valueOf(elseRunner.started));
        }

        // if 没有 else 且条件为假 → 直接跳过
        {
            final FakeRunner runner = new FakeRunner();
            final FakeContext ctx = new FakeContext();
            ctx.health = 20;
            final Result r = run("{\"steps\":[{\"if\":{\"condition\":{\"healthBelow\":10}},"
                    + "\"then\":[{\"action\":\"eat\"}]},{\"action\":\"after\"}]}", runner, ctx);
            check(r != null && r.ok() && runner.started.equals(List.of("after")),
                    "if：没有 else 时条件为假就跳过", String.valueOf(runner.started));
        }

        // 嵌套：if 里面套 repeat
        {
            final FakeRunner runner = new FakeRunner();
            final FakeContext ctx = new FakeContext();
            ctx.health = 5;
            final Result r = run("{\"steps\":[{\"if\":{\"condition\":{\"healthBelow\":10}},"
                    + "\"then\":[{\"repeat\":2,\"steps\":[{\"action\":\"eat\"}]},{\"action\":\"heal\"}]}]}",
                    runner, ctx);
            check(r != null && r.ok(), "嵌套：if 里套 repeat 能跑完", r == null ? "没跑完" : r.render());
            check(runner.started.equals(List.of("eat", "eat", "heal")),
                    "嵌套：执行顺序是 eat, eat, heal", String.valueOf(runner.started));
        }

        // waitUntil 条件已成立 → 立即跳过
        {
            final FakeRunner runner = new FakeRunner();
            final FakeContext ctx = new FakeContext();
            ctx.items.put("oak_log", 1);
            final Result r = run("{\"steps\":["
                    + "{\"waitUntil\":{\"condition\":{\"has\":\"oak_log\"},\"timeoutMs\":3000}},"
                    + "{\"action\":\"after\"}]}", runner, ctx);
            check(r != null && r.ok(), "waitUntil：条件已成立时正常继续", r == null ? "没跑完" : r.render());
            check(runner.started.equals(List.of("after")),
                    "waitUntil：条件已成立就不空耗", String.valueOf(runner.started));
        }

        // waitUntil 条件稍后成立
        {
            final FakeRunner runner = new FakeRunner();
            final FakeContext ctx = new FakeContext();
            runner.onStart = action -> {
                if ("wait".equals(action)) {
                    ctx.items.put("oak_log", 1); // 第一次轮询后条件成立
                }
            };
            final Result r = run("{\"steps\":["
                    + "{\"waitUntil\":{\"condition\":{\"has\":\"oak_log\"},\"timeoutMs\":3000}},"
                    + "{\"action\":\"after\"}]}", runner, ctx);
            check(r != null && r.ok(), "waitUntil：条件稍后成立后继续", r == null ? "没跑完" : r.render());
            check(runner.started.contains("after"), "waitUntil：等到之后执行了后续动作",
                    String.valueOf(runner.started));
        }

        // waitUntil 超时
        {
            final FakeRunner runner = new FakeRunner();
            final Result r = run("{\"steps\":["
                    + "{\"waitUntil\":{\"condition\":{\"has\":\"never\"},\"timeoutMs\":500}}]}",
                    runner, new FakeContext());
            check(r != null && !r.ok(), "waitUntil：条件一直不成立会超时失败",
                    r == null ? "没跑完（超时没触发）" : r.render());
            check(r != null && r.error() != null && r.error().contains("等待超时"),
                    "waitUntil：报错明确说是等待超时", r == null ? "" : String.valueOf(r.error()));
        }
    }

    // ============================================================== 安全阀

    private static void safetySection() {
        System.out.println("\n-- 安全阀 --");

        // maxSteps：脚本自己声明上限
        {
            final FakeRunner runner = new FakeRunner();
            final Result r = run("{\"maxSteps\":3,\"steps\":[{\"repeat\":10,\"steps\":[{\"action\":\"hit\"}]}]}",
                    runner, new FakeContext());
            check(r != null && !r.ok(), "maxSteps：超过上限时中止", r == null ? "没跑完" : r.render());
            check(r != null && r.error() != null && r.error().contains("上限"),
                    "maxSteps：报错说明了是动作数超限", r == null ? "" : String.valueOf(r.error()));
            check(runner.started.size() <= 3, "maxSteps：实际执行数没有超过上限",
                    String.valueOf(runner.started.size()));
        }

        // maxDurationMs：脚本自己声明时长上限
        {
            final FakeRunner runner = new FakeRunner();
            final Result r = run("{\"maxDurationMs\":1000,\"steps\":["
                    + "{\"waitUntil\":{\"condition\":{\"has\":\"never\"},\"timeoutMs\":100000}}]}",
                    runner, new FakeContext());
            check(r != null && !r.ok(), "maxDurationMs：整体超时也会中止",
                    r == null ? "没跑完" : r.render());
        }

        // cancel 能停
        {
            final FakeRunner runner = new FakeRunner();
            final ScriptProgram program = ScriptProgram.parse(parse(
                    "{\"steps\":[{\"repeat\":100,\"steps\":[{\"action\":\"hit\"}]}]}"));
            final ScriptVm vm = new ScriptVm(program, runner);
            final FakeContext ctx = new FakeContext();
            vm.tick(ctx);
            vm.cancel();
            check(vm.isFinished(), "cancel：能主动中止脚本", "");
            check(runner.aborted, "cancel：中止时顺手停掉了当前子动作", "");
        }
    }

    // ---------------------------------------------------------------- 驱动

    private static Result run(final String json, final FakeRunner runner, final FakeContext ctx) {
        final ScriptProgram program;
        try {
            program = ScriptProgram.parse(parse(json));
        } catch (final ScriptParseException e) {
            return new Result(false, "解析失败：" + e.getMessage(), 0, List.of());
        }
        final ScriptVm vm = new ScriptVm(program, runner);
        final long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            final Result r = vm.tick(ctx);
            if (r != null) {
                return r;
            }
        }
        return null;
    }

    private static JsonObject parse(final String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    // ---------------------------------------------------------------- 假件

    /** 假的世界状态。 */
    static final class FakeContext implements ScriptContext {
        final Map<String, Integer> items = new HashMap<>();
        final Map<String, Integer> nearby = new HashMap<>();
        String holdingSpec = "";
        double health = 20;
        int food = 20;
        double px;
        double py;
        double pz;
        boolean busy;

        @Override
        public boolean inWorld() {
            return true;
        }

        @Override
        public int countItem(final String spec) {
            return items.getOrDefault(spec, 0);
        }

        @Override
        public boolean holding(final String spec) {
            return !holdingSpec.isEmpty() && holdingSpec.contains(spec);
        }

        @Override
        public double health() {
            return health;
        }

        @Override
        public double maxHealth() {
            return 20;
        }

        @Override
        public int food() {
            return food;
        }

        @Override
        public boolean atPos(final int x, final int y, final int z, final int radius) {
            final double dx = px - (x + 0.5);
            final double dz = pz - (z + 0.5);
            final double dy = py - y;
            return dx * dx + dz * dz <= (double) radius * radius && Math.abs(dy) <= 2;
        }

        @Override
        public int nearbyCount(final String typeSpec, final int radius) {
            return nearby.getOrDefault(typeSpec, 0);
        }

        @Override
        public boolean busy() {
            return busy;
        }
    }

    /** 假的动作执行器：记录被启动了哪些动作，并可以按名字让某些动作失败。 */
    static final class FakeRunner implements ScriptVm.StepRunner {
        final List<String> started = new ArrayList<>();
        final Set<String> failing = new HashSet<>();
        final Set<String> unknown = new HashSet<>();
        java.util.function.Consumer<String> onStart;
        boolean aborted;

        private String current;
        private int ticksLeft;

        @Override
        public void start(final String action, final JsonObject params) {
            if (unknown.contains(action)) {
                throw new IllegalArgumentException("不支持的动作类型: " + action);
            }
            started.add(action);
            current = action;
            ticksLeft = 1; // 每个动作花 2 次 tick 完成（一次 RUNNING、一次完成）
            if (onStart != null) {
                onStart.accept(action);
            }
        }

        @Override
        public ScriptVm.StepOutcome tick() {
            if (ticksLeft > 0) {
                ticksLeft--;
                return ScriptVm.StepOutcome.running();
            }
            if (failing.contains(current)) {
                return ScriptVm.StepOutcome.failed("测试用失败");
            }
            return ScriptVm.StepOutcome.done();
        }

        @Override
        public void abort() {
            aborted = true;
        }
    }

    // ---------------------------------------------------------------- 断言

    private static void check(final boolean condition, final String name, final String detail) {
        if (condition) {
            passed++;
            System.out.println("  [PASS] " + name);
        } else {
            fail(name, detail);
        }
    }

    private static void fail(final String name, final String detail) {
        FAILURES.add(name + "  " + detail);
        System.out.println("  [FAIL] " + name + "  " + detail);
    }

    private ScriptTest() {
    }
}
