package com.mcai.bridge.script;

import com.google.gson.JsonObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 脚本虚拟机：tick 驱动地执行一棵 {@link ScriptNode} 树。
 *
 * <h2>它为什么存在</h2>
 *
 * <p>以前的流程是「LLM 想一步 → 下发一个动作 → 等结果 → LLM 再想下一步」。
 * 真正慢的不是网络往返（毫秒级），而是<b>每一步都要 LLM 思考一轮</b>（秒级）。
 * 做一把石剑要 9 步，就是 9 轮思考。</p>
 *
 * <p>有了这个 VM，LLM 可以<b>一次把整段流程说完</b>，模组在本地一口气跑完，
 * 只在结束时回传一次聚合结果。9 轮思考变成 1 轮。</p>
 *
 * <h2>怎么做到可测试</h2>
 *
 * <p>VM 自己不碰 Minecraft：它通过 {@link StepRunner} 启动/推进子动作，
 * 通过 {@link ScriptContext} 求值条件。真实实现接 {@code ActionExecutor} 与客户端玩家，
 * 测试实现只记录调用。于是控制流（循环、分支、等待、错误传播）都能在纯 JVM 里验证。</p>
 *
 * <h2>执行模型</h2>
 *
 * <p>一个显式的帧栈。每帧是「一串待执行节点 + 当前下标」，循环节点会在循环体跑完后
 * 被回头问一次「还要不要再来一轮」。这样循环、分支可以任意嵌套，而且状态全在栈上，
 * 随时可以中断。</p>
 */
public final class ScriptVm {

    /** 子动作的执行结果。 */
    public record StepOutcome(boolean finished, boolean ok, String error) {

        public static StepOutcome running() {
            return new StepOutcome(false, true, null);
        }

        public static StepOutcome done() {
            return new StepOutcome(true, true, null);
        }

        public static StepOutcome failed(final String error) {
            return new StepOutcome(true, false, error);
        }
    }

    /** 脚本要执行动作时用的接口。真实实现接 {@code ActionExecutor}。 */
    public interface StepRunner {

        /**
         * 启动一个子动作。
         *
         * @throws IllegalArgumentException 动作名不认识、参数非法、或被权限开关禁用
         */
        void start(String action, JsonObject params) throws IllegalArgumentException;

        /** 推进当前子动作一 tick。 */
        StepOutcome tick();

        /** 放弃当前子动作（脚本失败或被取消时）。 */
        void abort();
    }

    /** 脚本跑完的结果。 */
    public record Result(boolean ok, String error, int stepsDone, List<String> log) {

        /** 给 LLM 看的紧凑文本。 */
        public String render() {
            final StringBuilder sb = new StringBuilder();
            sb.append(ok ? "脚本执行完成" : "脚本执行失败");
            sb.append("（执行了 ").append(stepsDone).append(" 个动作）");
            if (error != null && !error.isEmpty()) {
                sb.append("\n失败原因：").append(error);
            }
            if (!log.isEmpty()) {
                sb.append("\n执行记录：");
                for (final String line : log) {
                    sb.append("\n  ").append(line);
                }
            }
            return sb.toString();
        }
    }

    /** 执行栈上的一帧。 */
    private static final class Frame {
        final List<ScriptNode> nodes;
        /** 这一帧是哪个循环的循环体；null 表示普通序列。 */
        final ScriptNode loopOwner;
        int index;
        int iterations;
        /**
         * 每个 {@code waitUntil} 节点各自的截止时间，按节点下标存。
         *
         * <p>不能在这一帧上只放一个 {@code waitDeadline}：同一层里写两个等待时，
         * 第二个会捡到第一个的截止时间，于是「刚进来就超时」。</p>
         */
        final long[] waitDeadlines;

        Frame(final List<ScriptNode> nodes, final ScriptNode loopOwner) {
            this.nodes = nodes;
            this.loopOwner = loopOwner;
            this.waitDeadlines = new long[nodes.size()];
        }
    }

    /** 等待类节点每次轮询之间小睡的毫秒数。 */
    private static final long WAIT_POLL_MS = 500L;

    private final ScriptProgram program;
    private final StepRunner runner;
    private final Deque<Frame> stack = new ArrayDeque<>();
    private final List<String> log = new ArrayList<>();

    private final long startedAt = System.currentTimeMillis();
    private int stepsDone;
    private boolean finished;
    private Result finishedResult;

    // 当前正在跑的子动作
    private boolean running;
    private boolean currentOptional;
    private boolean currentRawWait;
    private String currentDescription = "";

    public ScriptVm(final ScriptProgram program, final StepRunner runner) {
        this.program = program;
        this.runner = runner;
        stack.push(new Frame(program.steps(), null));
    }

    /**
     * 推进一 tick。
     *
     * @return {@code null} 表示还在跑；否则返回最终结果
     */
    public Result tick(final ScriptContext ctx) {
        if (finished) {
            return new Result(false, "脚本已经结束", stepsDone, log);
        }
        while (true) {
            // ---- 超时 / 步骤数安全阀 ----
            if (stepsDone >= program.maxSteps()) {
                return finish(false, "脚本执行的动作数超过上限 " + program.maxSteps()
                        + "。请拆成多段，或者检查循环条件是不是写成了死循环。");
            }
            if (System.currentTimeMillis() - startedAt > program.maxDurationMs()) {
                return finish(false, "脚本执行超过 " + (program.maxDurationMs() / 1000) + " 秒仍未完成，已中止。");
            }

            // ---- 有在跑的子动作：先推进它 ----
            if (running) {
                final StepOutcome outcome = runner.tick();
                if (!outcome.finished()) {
                    return null; // 还没做完，下 tick 继续
                }
                running = false;
                if (currentRawWait) {
                    // 等待轮询是引擎内部的小睡，不占用脚本的动作预算
                    currentRawWait = false;
                    continue;
                }
                stepsDone++;
                if (outcome.ok()) {
                    log.add("✓ " + currentDescription);
                } else if (currentOptional) {
                    log.add("⚠ 跳过（optional）：" + currentDescription
                            + " —— " + outcome.error());
                } else {
                    return finish(false, "第 " + stepsDone + " 个动作失败：" + currentDescription
                            + "\n原因：" + outcome.error());
                }
                continue;
            }

            // ---- 取下一个要执行的节点 ----
            final Frame frame = stack.peek();
            if (frame == null) {
                return finish(true, null);
            }
            if (frame.index >= frame.nodes.size()) {
                stack.pop();
                if (frame.loopOwner != null) {
                    frame.iterations++;
                    if (frame.loopOwner.loopShouldRepeat(frame.iterations, ctx)) {
                        frame.index = 0;
                        stack.push(frame);
                        continue;
                    }
                    log.add("↻ " + frame.loopOwner.describe() + " 结束（跑了 "
                            + frame.iterations + " 轮）");
                }
                continue;
            }

            final ScriptNode node = frame.nodes.get(frame.index++);

            if (node instanceof ScriptNode.Action action) {
                startAction(action);
                if (finished) {
                    return finishedResult;
                }
                if (running) {
                    return null; // 已启动，下一 tick 开始推进
                }
                continue; // 被 optional 跳过了
            }

            if (node instanceof ScriptNode.If branch) {
                final List<ScriptNode> chosen = branch.condition().test(ctx)
                        ? branch.then() : branch.otherwise();
                log.add("? " + branch.describe() + " → "
                        + (chosen == branch.then() ? "then" : "else")
                        + "（" + chosen.size() + " 步）");
                if (!chosen.isEmpty()) {
                    stack.push(new Frame(chosen, null));
                }
                continue;
            }

            if (node instanceof ScriptNode.Repeat || node instanceof ScriptNode.While) {
                final List<ScriptNode> body = node instanceof ScriptNode.Repeat repeat
                        ? repeat.body() : ((ScriptNode.While) node).body();
                // while 第一次进来要先判条件
                if (node instanceof ScriptNode.While loop && !loop.condition().test(ctx)) {
                    log.add("↻ " + loop.describe() + " 条件不成立，跳过");
                    continue;
                }
                log.add("↻ " + node.describe());
                stack.push(new Frame(body, node));
                continue;
            }

            if (node instanceof ScriptNode.WaitUntil wait) {
                if (wait.condition().test(ctx)) {
                    log.add("✓ 条件已成立：" + wait.condition().describe());
                    continue;
                }
                // 这次取出的节点下标（上面已经 ++，所以减回来）
                final int waitIndex = frame.index - 1;
                final long now = System.currentTimeMillis();
                if (frame.waitDeadlines[waitIndex] == 0L) {
                    frame.waitDeadlines[waitIndex] = now + wait.timeoutMs();
                }
                if (now > frame.waitDeadlines[waitIndex]) {
                    return finish(false, "等待超时：" + wait.condition().describe()
                            + "（等了 " + (wait.timeoutMs() / 1000) + " 秒）");
                }
                // 不推进下标：下 tick 再检查一次
                frame.index--;
                startRawWait();
                return null;
            }

            throw new ScriptParseException("脚本里有无法识别的节点：" + node.getClass().getSimpleName());
        }
    }

    /** 启动一个动作节点。启动失败时按 optional 决定跳过还是整体失败。 */
    private void startAction(final ScriptNode.Action action) {
        currentDescription = action.describe();
        currentOptional = action.optional();
        currentRawWait = false;
        try {
            runner.start(action.action(), action.params());
            running = true;
        } catch (final IllegalArgumentException | ScriptParseException e) {
            stepsDone++;
            if (action.optional()) {
                log.add("⚠ 跳过（optional）：" + currentDescription + " —— " + e.getMessage());
                return;
            }
            finish(false, "第 " + stepsDone + " 个动作无法启动：" + currentDescription
                    + "\n原因：" + e.getMessage());
        }
    }

    /** 等待节点的小睡：用一个很短的 wait 动作占位，避免空转。 */
    private void startRawWait() {
        final JsonObject params = new JsonObject();
        params.addProperty("ms", WAIT_POLL_MS);
        try {
            runner.start("wait", params);
            running = true;
            currentDescription = "等待";
            currentOptional = true; // 等待本身失败不该让整个脚本挂掉
            currentRawWait = true;
        } catch (final RuntimeException e) {
            // 连 wait 都启动不了（理论上不会），退化成直接空转
            running = false;
            currentRawWait = false;
        }
    }

    private Result finish(final boolean ok, final String error) {
        finished = true;
        if (!ok) {
            try {
                runner.abort();
            } catch (final RuntimeException ignored) {
                // 收尾阶段的异常无所谓
            }
        }
        finishedResult = new Result(ok, error, stepsDone, List.copyOf(log));
        return finishedResult;
    }

    // ---------------------------------------------------------------- 状态

    public boolean isFinished() {
        return finished;
    }

    public int stepsDone() {
        return stepsDone;
    }

    public String currentDescription() {
        return currentDescription;
    }

    /** 进度：已完成动作数 / 上限，仅用于显示。 */
    public double progress() {
        return Math.min(1.0, (double) stepsDone / Math.max(1, program.maxSteps()));
    }

    public List<String> log() {
        return List.copyOf(log);
    }

    /** 主动中止（被 stop 或新指令顶掉时）。 */
    public void cancel() {
        if (!finished) {
            finish(false, "脚本已被中止");
        }
    }
}
