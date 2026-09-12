package com.mcai.bridge.action;

import com.google.gson.JsonObject;
import com.mcai.bridge.BridgeConfig;
import net.minecraft.client.Minecraft;

/**
 * 一个可执行的游戏动作。
 *
 * <p>任务是「每 tick 推进一步」的状态机：</p>
 * <ul>
 *   <li>即时任务（说话、执行指令、查询状态…）在第一 tick 就返回结果；</li>
 *   <li>长任务（寻路、挖矿、追击…）每 tick 推进一点，直到完成、失败或超时。</li>
 * </ul>
 *
 * <p>所有回调都在客户端主线程上执行（由 {@link ActionExecutor} 保证），
 * 因此任务内部可以放心地直接操作 {@code Minecraft} 与玩家实体。</p>
 */
public abstract class Task {

    /** 任务标识，来自插件的 actionId。回传结果时原样带回。 */
    public final String id;
    /** 任务类型，例如 {@code move_to}。 */
    public final String type;
    /** 原始参数。 */
    public final JsonObject params;
    /** 超时（毫秒）。 */
    public final long timeoutMs;
    /** 是否为长任务（需要排队执行，且同一时间只允许一个移动类任务）。 */
    public final boolean longRunning;
    /** 是否占用「移动控制权」——多个移动任务不能同时存在。 */
    public final boolean movement;

    private final long startedAt = System.currentTimeMillis();
    private long finishedAt;
    private boolean started;
    private boolean cancelled;
    private com.mcai.bridge.util.ProgressWatchdog watchdog;

    /** 卡死检测把时间切成几段：连续这么多个窗口都没进展才判卡死。 */
    private static final int STUCK_STRIKES = 3;

    protected int ticks;

    protected Task(final String id, final String type, final JsonObject params,
                   final long timeoutMs, final boolean longRunning, final boolean movement) {
        this.id = id;
        this.type = type;
        this.params = params == null ? new JsonObject() : params;
        this.timeoutMs = timeoutMs <= 0 ? 60_000L : timeoutMs;
        this.longRunning = longRunning;
        this.movement = movement;
    }

    /** 推进一 tick。这是唯一对外入口，内部会处理启动、超时与异常。 */
    public final TaskResult advance(final Minecraft mc) {
        ticks++;
        if (cancelled) {
            return TaskResult.fail("任务已被取消");
        }
        final long now = System.currentTimeMillis();
        if (now - startedAt > timeoutMs) {
            finish();
            try {
                onCancel(mc);
            } catch (final Throwable ignored) {
                // 取消阶段的异常无需上报
            }
            return TaskResult.fail("任务超时（已执行 " + (now - startedAt) / 1000 + " 秒，上限 "
                    + timeoutMs / 1000 + " 秒）。可能目标不可达、被方块挡住，或者游戏窗口没有获得焦点。");
        }
        try {
            if (!started) {
                started = true;
                onStart(mc);
            }
            // 玩家死了就立刻收工。否则任务会继续对着尸体操作：真机自检里出现过
            // 「右键熔炉但界面没打开」「半径内找不到 stone」这种驴唇不对马嘴的报错，
            // 根因其实是玩家已经死了 —— 那种错误信息会把排查带偏。
            if (mc.player != null && (mc.player.isDeadOrDying() || mc.player.getHealth() <= 0)) {
                finish();
                try {
                    onCancel(mc);
                } catch (final Throwable ignored) {
                    // 忽略
                }
                return TaskResult.fail("玩家已经死亡，任务中止。等复活之后再重新下指令吧。");
            }
            final TaskResult stuck = checkProgress(mc, now);
            if (stuck != null) {
                finish();
                try {
                    onCancel(mc);
                } catch (final Throwable ignored) {
                    // 忽略
                }
                return stuck;
            }
            final TaskResult result = onTick(mc);
            if (result == null) {
                return TaskResult.running();
            }
            if (result.isFinished()) {
                finish();
            }
            return result;
        } catch (final TaskFailure failure) {
            finish();
            try {
                onCancel(mc);
            } catch (final Throwable ignored) {
                // 忽略
            }
            return TaskResult.fail(failure.getMessage());
        } catch (final Throwable t) {
            finish();
            try {
                onCancel(mc);
            } catch (final Throwable ignored) {
                // 忽略
            }
            com.mcai.bridge.McAiBridge.LOGGER.error("[MaiBot Bridge] 任务 {} 执行异常", type, t);
            return TaskResult.fail("任务执行内部错误: " + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : " - " + t.getMessage()));
        }
    }

    private void finish() {
        if (finishedAt == 0L) {
            finishedAt = System.currentTimeMillis();
        }
    }

    /**
     * 卡死检测：一段时间内既没移动、也没进展就提前失败，而不是沉默地耗到超时。
     *
     * <p>思路来自 Altoclef 的 {@code MovementProgressChecker}（见
     * {@link com.mcai.bridge.util.ProgressWatchdog} 的说明）。</p>
     *
     * @return 卡死时返回要回传的失败结果；否则返回 null
     */
    private TaskResult checkProgress(final Minecraft mc, final long now) {
        final int seconds = BridgeConfig.stuckDetectionSeconds;
        if (seconds <= 0 || !watchdogApplies() || mc == null || mc.player == null) {
            return null;
        }
        if (watchdog == null) {
            watchdog = new com.mcai.bridge.util.ProgressWatchdog(
                    seconds * 1000L / STUCK_STRIKES, STUCK_STRIKES);
        }
        watchdog.feed(mc.player.getX(), mc.player.getY(), mc.player.getZ(), realProgress(), now);
        if (!watchdog.stuck()) {
            return null;
        }
        final String reason = watchdog.describe(detail().isEmpty() ? type : detail());
        com.mcai.bridge.McAiBridge.LOGGER.warn("[MaiBot Bridge] 判定任务 {} 卡死：{}", type, reason);
        return TaskResult.fail(reason);
    }

    /**
     * 这个任务是否接受卡死检测。默认只检测「会驱动移动」的任务 ——
     * 说话、查状态这类任务本来就不该动。
     */
    protected boolean watchdogApplies() {
        return movement;
    }

    /**
     * 「真实进展」计数：只在任务确实往前推进时才增加的量（挖到几个、合成几个、打中几下）。
     *
     * <p><b>不要</b>把 {@link #progress()} 直接拿来用 —— 有些任务的 progress 是按时间算的
     * （追击、长按使用），站着不动也会涨，拿它当「有进展」等于没检测。</p>
     *
     * @return -1 表示这个任务没有可用的进展计数（那就只靠位移判断）
     */
    protected double realProgress() {
        return -1;
    }

    public final void markCancelled() {
        this.cancelled = true;
    }

    /**
     * 由外部驱动者（例如脚本引擎）调用：取消本任务并释放它占用的资源。
     *
     * <p>{@link #onCancel} 是 protected，脚本引擎持有的是别的 Task 实例，
     * 没法直接调；这里开一个受控入口，保证子动作被取消时能和正常结束一样收尾
     * （松开按键、停止挖掘、关闭容器…）。</p>
     */
    public final void cancelNow(final Minecraft mc) {
        markCancelled();
        try {
            onCancel(mc);
        } catch (final Throwable t) {
            com.mcai.bridge.McAiBridge.LOGGER.debug("[MaiBot Bridge] 取消任务 {} 时出错: {}",
                    type, t.toString());
        }
    }

    public final boolean isCancelled() {
        return cancelled;
    }

    public final long elapsedMs() {
        final long end = finishedAt == 0L ? System.currentTimeMillis() : finishedAt;
        return end - startedAt;
    }

    // ------------------------------------------------------------ 子类实现

    /** 第一 tick 之前调用一次。 */
    protected void onStart(final Minecraft mc) throws Exception {
        // 默认无操作
    }

    /** 每 tick 调用。返回 {@code null} 表示继续，返回结果表示结束。 */
    protected abstract TaskResult onTick(Minecraft mc) throws Exception;

    /** 任务结束或被取消时调用，用于释放按键、停止挖掘等。 */
    protected void onCancel(final Minecraft mc) {
        // 默认无操作
    }

    /** 0.0 ~ 1.0 的进度，未知返回 -1。 */
    public double progress() {
        return -1.0;
    }

    /** 给 LLM 看的人类可读进度描述。 */
    public String detail() {
        return "";
    }

    // ---------------------------------------------------------------- 异常

    /** 任务无法继续时抛出，会被转成带回传的错误信息。 */
    public static final class TaskFailure extends RuntimeException {
        public TaskFailure(final String message) {
            super(message);
        }
    }

    protected static void fail(final String message) {
        throw new TaskFailure(message);
    }

    protected static String str(final JsonObject o, final String key, final String def) {
        return com.mcai.bridge.protocol.Json.str(o, key, def);
    }
}
