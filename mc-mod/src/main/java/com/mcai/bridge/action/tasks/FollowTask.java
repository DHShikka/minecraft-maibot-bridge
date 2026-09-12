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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

/**
 * {@code follow}：持续跟随某个实体（通常是玩家）。
 *
 * <p>与一次性寻路的 {@code move_to} 不同，跟随是「动态目标」：每个 tick 都会重新看一眼目标在哪，
 * 目标走远了才重新规划，目标在附近就只是转过去看着他。适合「跟着我」「别掉队」这类指令。</p>
 *
 * <p>参数：{@code target}（玩家名 / uuid / 实体类型 / nearest）、{@code range}（跟到多近，默认 3）、
 * {@code durationMs}（跟多久，默认 60 秒；设为 0 表示一直跟到被 stop）、
 * {@code maxDistance}（超过这个距离就判定跟丢，默认 64）。</p>
 */
public final class FollowTask extends Task {

    private final String targetKey;
    private final double range;
    private final long durationMs;
    private final double maxDistance;
    private final long startAt = System.currentTimeMillis();

    private Navigator navigator;
    private String resolvedUuid;
    private BlockPos lastGoal;
    private int lostTicks;
    private int followTicks;
    private double closest;

    private FollowTask(final String id, final JsonObject params, final long timeoutMs,
                       final String targetKey, final double range, final long durationMs, final double maxDistance) {
        super(id, "follow", params, timeoutMs, true, true);
        this.targetKey = targetKey;
        this.range = Math.max(1.0, range);
        this.durationMs = durationMs;
        this.maxDistance = Math.max(range + 4.0, maxDistance);
    }

    public static Task create(final String id, final JsonObject params, final long timeoutMs) {
        final String target = Json.str(params, "target", Json.str(params, "entity", ""));
        if (target.isBlank()) {
            return new MoveToTask.FailingTask(id, "follow", params,
                    "缺少参数 target（要跟随的玩家名 / 实体名 / uuid，也可以填 nearest_player）");
        }
        return new FollowTask(id, params, timeoutMs, target,
                Json.num(params, "range", 3.0),
                Json.longVal(params, "durationMs", 60_000L),
                Json.num(params, "maxDistance", 64.0));
    }

    @Override
    protected void onStart(final Minecraft mc) {
        if (mc.level == null || mc.player == null) {
            fail("尚未进入世界");
        }
        navigator = new Navigator(mc.level);
        closest = Double.MAX_VALUE;
    }

    @Override
    protected TaskResult onTick(final Minecraft mc) {
        final LocalPlayer player = mc.player;
        if (player == null) {
            fail("玩家不存在");
        }

        final long elapsed = System.currentTimeMillis() - startAt;
        if (durationMs > 0 && elapsed >= durationMs) {
            if (navigator != null) {
                navigator.releaseControl();
            }
            final JsonObject out = new JsonObject();
            out.addProperty("followedMs", elapsed);
            out.addProperty("finalDistance", closest == Double.MAX_VALUE ? -1 : closest);
            out.addProperty("endedBecause", "到达指定时长");
            return TaskResult.success(out);
        }

        final Entity target = resolveTarget(mc);
        if (target == null) {
            lostTicks++;
            if (lostTicks > 40) {
                if (navigator != null) {
                    navigator.releaseControl();
                }
                fail("跟丢了目标「" + targetKey + "」。可能对方已经下线、离开视野，或者名字写错了。"
                        + "可以用 scan_entities 或 mc_state 看看现在附近有谁。");
            }
            return null;
        }
        lostTicks = 0;

        final double distance = player.distanceTo(target);
        closest = Math.min(closest, distance);

        if (distance > maxDistance) {
            if (navigator != null) {
                navigator.releaseControl();
            }
            fail(String.format(Locale.ROOT,
                    "跟丢了目标「%s」：距离已经拉开到 %.1f 格（上限 %.0f 格）。",
                    GameUtils.entityName(target), distance, maxDistance));
        }

        // 已经在身边：停下来，转过去看着他
        if (distance <= range) {
            if (navigator != null) {
                navigator.releaseControl();
            }
            GameUtils.lookAt(player, GameUtils.centerOf(target));
            followTicks++;
            return null;
        }

        // 需要靠近：以目标所在位置为导航目标
        if (navigator == null) {
            navigator = new Navigator(mc.level);
        }
        final BlockPos goal = target.blockPosition();
        if (lastGoal == null || goal.distSqr(lastGoal) > 4.0 || navigator.pathLength() == 0) {
            navigator.setGoal(goal, (int) Math.max(1.0, Math.floor(range)));
            lastGoal = goal;
        }

        switch (navigator.step(mc, 1.0)) {
            case ARRIVED, MOVING -> {
                return null;
            }
            case FAILED -> {
                // 跟随过程中寻路失败不算致命：目标可能刚翻过墙，下个 tick 重试即可
                followTicks++;
                if (followTicks > 120) {
                    navigator.releaseControl();
                    fail("跟随「" + GameUtils.entityName(target) + "」时反复寻路失败："
                            + navigator.failureReason());
                }
                navigator.setGoal(target.blockPosition(), (int) Math.max(1.0, Math.floor(range)));
                return null;
            }
            default -> {
                return null;
            }
        }
    }

    private Entity resolveTarget(final Minecraft mc) {
        if (resolvedUuid != null && mc.level != null) {
            for (final Entity entity : mc.level.entitiesForRendering()) {
                if (entity.getUUID().toString().equals(resolvedUuid)) {
                    return entity;
                }
            }
            resolvedUuid = null;
            return null; // 目标已离开，不再退回按名字找，避免跟错人
        }
        Entity found = BasicTasks.TargetResolver.findEntity(mc, targetKey);
        if (found == null && ("nearest_player".equalsIgnoreCase(targetKey) || "最近的玩家".equals(targetKey))) {
            found = nearestPlayer(mc);
        }
        if (found != null) {
            resolvedUuid = found.getUUID().toString();
        }
        return found;
    }

    private Entity nearestPlayer(final Minecraft mc) {
        if (mc.level == null || mc.player == null) {
            return null;
        }
        Entity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (final net.minecraft.world.entity.player.Player other : mc.level.players()) {
            if (other == mc.player) {
                continue;
            }
            final double distance = mc.player.distanceTo(other);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = other;
            }
        }
        return best;
    }

    @Override
    protected void onCancel(final Minecraft mc) {
        if (navigator != null) {
            navigator.releaseControl();
        }
    }

    @Override
    public double progress() {
        if (durationMs <= 0) {
            return -1;
        }
        return Math.min(1.0, (double) (System.currentTimeMillis() - startAt) / durationMs);
    }

    @Override
    public String detail() {
        final Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return "跟随中";
        }
        final Entity target = resolveTargetForDetail(mc);
        if (target == null) {
            return "正在寻找「" + targetKey + "」";
        }
        return String.format(Locale.ROOT, "跟随「%s」，相距 %.1f 格",
                GameUtils.entityName(target), mc.player.distanceTo(target));
    }

    private Entity resolveTargetForDetail(final Minecraft mc) {
        if (mc.level == null) {
            return null;
        }
        if (resolvedUuid != null) {
            for (final Entity entity : mc.level.entitiesForRendering()) {
                if (entity.getUUID().toString().equals(resolvedUuid)) {
                    return entity;
                }
            }
        }
        return null;
    }

    /** 跟随结束时顺带返回当前位置，方便 AI 继续规划。 */
    static JsonObject positionOf(final LocalPlayer player) {
        final JsonObject o = new JsonObject();
        if (player != null) {
            o.add("pos", StateCollector.vec(player.position()));
        }
        return o;
    }
}
