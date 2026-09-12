package com.mcai.bridge.action;

import com.google.gson.JsonObject;

/** 任务一次 tick 之后的产出：继续跑、成功、失败。 */
public final class TaskResult {

    private static final TaskResult RUNNING = new TaskResult(false, true, null, null);

    public final boolean finished;
    public final boolean ok;
    public final JsonObject result;
    public final String error;

    private TaskResult(final boolean finished, final boolean ok, final JsonObject result, final String error) {
        this.finished = finished;
        this.ok = ok;
        this.result = result;
        this.error = error;
    }

    public static TaskResult running() {
        return RUNNING;
    }

    public static TaskResult success(final JsonObject result) {
        return new TaskResult(true, true, result == null ? new JsonObject() : result, null);
    }

    public static TaskResult fail(final String error) {
        return new TaskResult(true, false, null, error == null ? "未知错误" : error);
    }

    public boolean isFinished() {
        return finished;
    }
}
