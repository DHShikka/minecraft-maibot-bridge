package com.mcai.bridge.action.tasks;

import com.google.gson.JsonObject;
import com.mcai.bridge.action.Navigator;
import com.mcai.bridge.action.Task;
import com.mcai.bridge.action.TaskResult;
import com.mcai.bridge.protocol.Json;
import com.mcai.bridge.snapshot.StateCollector;
import com.mcai.bridge.util.GameUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

import java.util.Locale;

/**
 * 移动类动作：{@code move_to}（走到绝对坐标）与 {@code move_relative}（走到相对位移后的位置）。
 *
 * <p>内部用 {@link Navigator} 做 A* 寻路 + 路点跟随。到达判定使用水平距离加垂直容差，
 * 避免在台阶边缘反复横跳。</p>
 *
 * <p>可调参数：{@code range}（到达半径，默认 1）、{@code speedFactor}（0.2~1.0）、
 * {@code allowSprint}（默认 true）。</p>
 */
public class MoveToTask extends Task {

    /** 导航器在第一次 tick 时才创建，因为它需要世界对象。 */
    protected Navigator navigator;
    protected final BlockPos goal;
    protected final int range;
    protected final double speedFactor;
    protected final boolean allowSprint;

    protected double initialDistance;
    private int stalledTicks;

    protected MoveToTask(final String id, final String type, final JsonObject params, final long timeoutMs,
                         final BlockPos goal, final int range, final double speedFactor, final boolean allowSprint) {
        super(id, type, params, timeoutMs, true, true);
        this.goal = goal;
        this.range = Math.max(0, range);
        this.speedFactor = Math.max(0.2, Math.min(1.0, speedFactor));
        this.allowSprint = allowSprint;
    }

    // ------------------------------------------------------------ 工厂方法

    /** {@code move_to}：参数 {@code x}/{@code y}/{@code z}。 */
    public static Task toCoordinates(final String id, final JsonObject params, final long timeoutMs) {
        final BlockPos target = BasicTasks.readBlockPos(params);
        if (target == null) {
            return new FailingTask(id, "move_to", params, "缺少参数 x/y/z（目标方块坐标）");
        }
        return new MoveToTask(id, "move_to", params, timeoutMs, target,
                Json.intVal(params, "range", 1),
                Json.num(params, "speedFactor", 1.0),
                Json.bool(params, "allowSprint", true));
    }

    /** {@code move_relative}：参数 {@code dx}/{@code dy}/{@code dz}。 */
    public static Task relative(final String id, final JsonObject params, final long timeoutMs) {
        return new MoveRelativeTask(id, params, timeoutMs);
    }

    // ------------------------------------------------------------ 执行流程

    @Override
    protected void onStart(final Minecraft mc) {
        final LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            fail("尚未进入世界，无法移动");
        }
        navigator = new Navigator(mc.level);
        navigator.setGoal(goal, range);
        initialDistance = Math.max(1.0, navigator.remainingDistance(player));
    }

    @Override
    protected TaskResult onTick(final Minecraft mc) {
        final LocalPlayer player = mc.player;
        if (player == null) {
            fail("玩家不存在");
        }
        if (navigator == null) {
            fail("导航器未初始化");
        }

        final double speed = allowSprint ? speedFactor : Math.min(speedFactor, 0.85);
        switch (navigator.step(mc, speed)) {
            case ARRIVED -> {
                navigator.releaseControl();
                final JsonObject out = new JsonObject();
                out.add("pos", StateCollector.vec(player.position()));
                out.add("blockPos", StateCollector.blockPos(player.blockPosition()));
                out.addProperty("targetBlock", GameUtils.format(goal));
                out.addProperty("pathLength", navigator.pathLength());
                return TaskResult.success(out);
            }
            case MOVING -> {
                // 一直规划不出路径（visitedNodes 恒为 0）说明目标不可达，早点给明确失败而不是拖到超时
                if (navigator.pathLength() == 0) {
                    stalledTicks++;
                    if (stalledTicks > 60) {
                        navigator.releaseControl();
                        fail(stalledFailureMessage(player));
                    }
                } else {
                    stalledTicks = 0;
                }
                return null;
            }
            case FAILED -> {
                navigator.releaseControl();
                fail(navigator.failureReason());
                return null;
            }
            default -> {
                return null;
            }
        }
    }

    private String stalledFailureMessage(final LocalPlayer player) {
        return "无法从 " + GameUtils.format(player.blockPosition()) + " 规划出通往 "
                + GameUtils.format(goal) + " 的路径（已搜索 " + navigator.visitedNodes() + " 个位置）。"
                + "常见原因：目标在封闭空间内、需要挖穿方块、或者目标的垂直高度差超过 24 格。"
                + "建议：先用 scan_blocks 确认目标附近的实际情况，或者先 mine 挖开通道。";
    }

    @Override
    protected void onCancel(final Minecraft mc) {
        if (navigator != null) {
            navigator.releaseControl();
        }
    }

    @Override
    public double progress() {
        if (navigator == null) {
            return -1;
        }
        final double remaining = navigator.remainingDistance(Minecraft.getInstance().player);
        if (remaining < 0) {
            return -1;
        }
        return Math.max(0.0, Math.min(1.0, 1.0 - remaining / initialDistance));
    }

    @Override
    public String detail() {
        if (navigator == null) {
            return "正在规划路径";
        }
        final double remaining = navigator.remainingDistance(Minecraft.getInstance().player);
        return String.format(Locale.ROOT, "距离目标还有 %.1f 格（当前路径 %d 个路点）",
                Math.max(0.0, remaining), navigator.pathLength());
    }

    // -------------------------------------------------------- 相对位移版本

    /** {@code move_relative}：把相对位移换算成绝对坐标后复用同一套寻路逻辑。 */
    static final class MoveRelativeTask extends MoveToTask {

        private final JsonObject raw;

        MoveRelativeTask(final String id, final JsonObject params, final long timeoutMs) {
            super(id, "move_relative", params, timeoutMs, BlockPos.ZERO,
                    Json.intVal(params, "range", 1),
                    Json.num(params, "speedFactor", 1.0),
                    Json.bool(params, "allowSprint", true));
            this.raw = params;
        }

        @Override
        protected void onStart(final Minecraft mc) {
            final LocalPlayer player = mc.player;
            if (player == null || mc.level == null) {
                fail("尚未进入世界，无法移动");
            }
            final BlockPos from = player.blockPosition();
            final int dx = (int) Math.round(Json.num(raw, "dx", Json.num(raw, "x", 0)));
            final int dy = (int) Math.round(Json.num(raw, "dy", Json.num(raw, "y", 0)));
            final int dz = (int) Math.round(Json.num(raw, "dz", Json.num(raw, "z", 0)));
            if (dx == 0 && dy == 0 && dz == 0) {
                fail("相对位移 dx/dy/dz 不能全为 0");
            }
            navigator = new Navigator(mc.level);
            navigator.setGoal(from.offset(dx, dy, dz), range);
            initialDistance = Math.max(1.0, navigator.remainingDistance(player));
        }

        @Override
        protected TaskResult onTick(final Minecraft mc) {
            final TaskResult result = super.onTick(mc);
            if (result.isFinished() && result.ok) {
                result.result.addProperty("relativeTo", GameUtils.format(
                        mc.player == null ? BlockPos.ZERO : mc.player.blockPosition()));
            }
            return result;
        }
    }

    /** 参数错误时用来立即回传失败的占位任务。 */
    static final class FailingTask extends Task {
        private final String message;

        FailingTask(final String id, final String type, final JsonObject params, final String message) {
            super(id, type, params, 1_000L, true, false);
            this.message = message;
        }

        @Override
        protected TaskResult onTick(final Minecraft mc) {
            fail(message);
            return null;
        }
    }
}
