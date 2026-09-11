package com.mcai.bridge.action.tasks;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mcai.bridge.McAiBridge;
import com.mcai.bridge.action.ActionExecutor;
import com.mcai.bridge.action.Task;
import com.mcai.bridge.action.TaskResult;
import com.mcai.bridge.protocol.Json;
import com.mcai.bridge.script.ScriptContext;
import com.mcai.bridge.script.ScriptParseException;
import com.mcai.bridge.script.ScriptProgram;
import com.mcai.bridge.script.ScriptVm;
import com.mcai.bridge.script.VanillaScriptContext;
import net.minecraft.client.Minecraft;

/**
 * {@code script}：把一整段流程一次性下发给模组，本地连续执行。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>之前每一步都要「LLM 思考一轮 → 下发 → 等结果」。真正的瓶颈是<b>思考</b>（秒级），
 * 不是网络往返（毫秒级）。做一把石剑 9 步就是 9 轮思考。</p>
 *
 * <p>现在 LLM 可以一次把整段脚本交过来，模组自己跑完，只在结束时回传一次聚合结果。</p>
 *
 * <h2>与执行器的关系</h2>
 *
 * <p>本任务作为「当前动作」独占执行器，然后<b>直接</b>驱动子动作
 * （{@code step.advance(mc)}），不经过队列 —— 否则脚本的每一步都会去和
 * 「移动类动作互相顶掉」的队列语义打架。</p>
 *
 * <p>子动作全部通过 {@link ActionExecutor#createTask} 创建，
 * 所以<b>权限开关、参数校验和单独下发时完全一致</b>，
 * 不存在「放进脚本里就能绕过禁用开关」的口子。</p>
 */
public final class ScriptTask extends Task implements ScriptVm.StepRunner {

    private final ScriptVm vm;
    private final ScriptInfo info = new ScriptInfo();

    private Task step;
    private int stepCounter;
    private long lastStepElapsedMs;

    /** 已经 start() 但还没真正创建的子动作（等限速闸门放行）。 */
    private String pendingAction;
    private JsonObject pendingParams;
    private boolean rateLimitLogged;

    private ScriptTask(final String id, final JsonObject params, final long timeoutMs,
                       final ScriptProgram program) {
        super(id, "script", params, timeoutMs, true, true);
        this.vm = new ScriptVm(program, this);
        this.info.name = program.name();
        this.info.nodeCount = program.nodeCount();
        this.info.maxSteps = program.maxSteps();
    }

    /**
     * 工厂。脚本解析失败时返回一个「立刻失败」的任务，把可读的报错带回给 LLM，
     * 而不是抛异常炸掉整条执行链。
     */
    public static Task create(final String id, final JsonObject params, final long timeoutMs) {
        try {
            final ScriptProgram program = ScriptProgram.parse(params);
            return new ScriptTask(id, params, timeoutMs, program);
        } catch (final ScriptParseException e) {
            return new MoveToTask.FailingTask(id, "script", params,
                    "脚本格式有问题，没法执行：\n" + e.getMessage());
        }
    }

    // ------------------------------------------------------------ 主循环

    @Override
    protected TaskResult onTick(final Minecraft mc) {
        final ScriptContext context = new VanillaScriptContext(mc);
        final ScriptVm.Result result;
        try {
            result = vm.tick(context);
        } catch (final Throwable t) {
            com.mcai.bridge.McAiBridge.LOGGER.error("[MaiBot Bridge] 脚本执行异常", t);
            abort();
            return TaskResult.fail("脚本执行时内部出错：" + t);
        }
        if (result == null) {
            return null; // 还在跑
        }

        final JsonObject out = new JsonObject();
        out.addProperty("ok", result.ok());
        out.addProperty("stepsDone", result.stepsDone());
        if (info.name != null && !info.name.isEmpty()) {
            out.addProperty("name", info.name);
        }
        out.addProperty("nodeCount", info.nodeCount);
        final JsonArray log = new JsonArray();
        for (final String line : result.log()) {
            log.add(line);
        }
        out.add("log", log);
        out.addProperty("content", result.render());
        if (result.error() != null) {
            out.addProperty("error", result.error());
        }
        // 把「脚本中途停了」这件事说清楚，而不是只报一个 ok=false
        if (!result.ok()) {
            return TaskResult.fail(result.render());
        }
        return TaskResult.success(out);
    }

    @Override
    protected void onCancel(final Minecraft mc) {
        abort();
    }

    @Override
    public double progress() {
        return vm.progress();
    }

    /**
     * 脚本本身不参加卡死检测。
     *
     * <p>它的子动作各自都会检测（子动作是独立任务，走的是同一个 {@code Task.advance}），
     * 而脚本层次上「站着不动」是合法的 —— {@code waitUntil} 就是在等条件成立，
     * 玩家本来就可能一动不动。拿脚本整体去判卡死会把正常的等待误杀。</p>
     */
    @Override
    protected boolean watchdogApplies() {
        return false;
    }

    @Override
    public String detail() {
        if (vm.isFinished()) {
            return "脚本已结束（执行了 " + vm.stepsDone() + " 个动作）";
        }
        return "脚本进行中：" + vm.currentDescription()
                + "（已完成 " + vm.stepsDone() + " 个动作）";
    }

    // ====================================================== StepRunner 实现

    @Override
    public void start(final String action, final JsonObject params) throws IllegalArgumentException {
        // 嵌套脚本没法算清超时：内层会按「外层的一个步骤」计时。直接拒绝，让 LLM 展开写。
        if ("script".equalsIgnoreCase(action)) {
            throw new IllegalArgumentException(
                    "脚本里不能再套一层 script —— 把内层的步骤直接展开到外层就行。");
        }
        stepCounter++;
        // 这里只记下「要做什么」。真正创建子任务放到 tick 里 —— 因为创建前要先过全局限速闸门，
        // 而「被限速」应该表现为等一会儿，不该让整个脚本失败。
        pendingAction = action;
        pendingParams = params;
        step = null;
        rateLimitLogged = false;
    }

    @Override
    public ScriptVm.StepOutcome tick() {
        final Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            step = null;
            return ScriptVm.StepOutcome.failed("玩家已经离开世界");
        }

        if (step == null) {
            // 还没创建子任务：先看限速闸门放不放行，没放行就下一个 tick 再问
            // （只是「看一眼」，不占配额；真发动作时下面才扣）
            final ActionExecutor executor = ActionExecutor.get();
            if (!executor.rateLimitAllows()) {
                if (!rateLimitLogged) {
                    rateLimitLogged = true;
                    McAiBridge.LOGGER.info("[MaiBot Bridge] 脚本被限速，挂起等下一个时间窗");
                }
                return ScriptVm.StepOutcome.running();
            }
            executor.checkRateLimit(); // 真的要用一个动作了，扣掉配额
            try {
                // 复用执行器的任务工厂：权限校验、参数解析、动作是否存在，全部同一套规则
                step = executor.createTask(id + "-s" + stepCounter,
                        pendingAction, pendingParams, remainingBudgetMs());
            } catch (final IllegalArgumentException | ScriptParseException e) {
                pendingAction = null;
                pendingParams = null;
                return ScriptVm.StepOutcome.failed(e.getMessage());
            }
            lastStepElapsedMs = System.currentTimeMillis();
        }

        final TaskResult result;
        try {
            result = step.advance(mc);
        } catch (final Throwable t) {
            McAiBridge.LOGGER.error("[MaiBot Bridge] 脚本子动作异常", t);
            step = null;
            return ScriptVm.StepOutcome.failed("子动作内部出错：" + t);
        }
        if (!result.isFinished()) {
            return ScriptVm.StepOutcome.running();
        }
        final String type = step.type;
        final long elapsed = System.currentTimeMillis() - lastStepElapsedMs;
        step = null;
        if (result.ok) {
            return ScriptVm.StepOutcome.done();
        }
        return ScriptVm.StepOutcome.failed("动作 " + type + " 失败（耗时 " + elapsed + "ms）：" + result.error);
    }

    @Override
    public void abort() {
        if (step != null) {
            final Minecraft mc = Minecraft.getInstance();
            try {
                step.cancelNow(mc);
            } catch (final Throwable ignored) {
                // 收尾异常忽略
            }
            step = null;
        }
        vm.cancel();
    }

    /** 单个子动作最多能跑多久：剩下的脚本总预算，至少给 10 秒。 */
    private long remainingBudgetMs() {
        final long remaining = timeoutMs - elapsedMs();
        return Math.max(10_000L, remaining);
    }

    /** 只用于日志/调试的脚本元信息。 */
    private static final class ScriptInfo {
        String name;
        int nodeCount;
        int maxSteps;
    }
}
