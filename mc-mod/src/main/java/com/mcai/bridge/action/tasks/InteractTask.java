package com.mcai.bridge.action.tasks;

import com.google.gson.JsonObject;
import com.mcai.bridge.action.Navigator;
import com.mcai.bridge.action.Task;
import com.mcai.bridge.action.TaskResult;
import com.mcai.bridge.protocol.Json;
import com.mcai.bridge.snapshot.StateCollector;
import com.mcai.bridge.util.GameUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 与方块的高级交互：{@code sleep}（睡觉）/ {@code jump_on_block}（爬上某个方块）。
 *
 * <p>这两个动作都建立在「寻路 + 右键」之上，单独拆出来的原因是它们是很常用的
 * 任务级目标（「去睡觉跳过夜晚」「爬上去」），让 LLM 不必自己拼动作序列。</p>
 */
public final class InteractTask {

    private InteractTask() {
    }

    // ---------------------------------------------------------------- 睡觉

    /** 找附近的床并睡上去。参数：{@code radius}（搜索半径，默认 16）。 */
    public static Task sleep(final String id, final JsonObject params, final long timeoutMs) {
        return new SleepTask(id, params, timeoutMs, Json.intVal(params, "radius", 16));
    }

    private static final class SleepTask extends Task {
        private final int radius;
        private Navigator navigator;
        private BlockPos bed;
        private int clicks;

        SleepTask(final String id, final JsonObject params, final long timeoutMs, final int radius) {
            super(id, "sleep", params, timeoutMs, true, true);
            this.radius = Math.max(2, Math.min(48, radius));
        }

        @Override
        protected void onStart(final Minecraft mc) {
            if (mc.player == null || mc.level == null) {
                fail("尚未进入世界");
            }
            navigator = new Navigator(mc.level);
        }

        @Override
        protected TaskResult onTick(final Minecraft mc) {
            final LocalPlayer player = mc.player;
            final ClientLevel level = mc.level;
            if (player == null || level == null || mc.gameMode == null) {
                fail("玩家或世界不存在");
            }

            if (player.isSleeping()) {
                final JsonObject out = new JsonObject();
                out.addProperty("sleeping", true);
                return TaskResult.success(out);
            }

            if (bed == null) {
                bed = findBed(mc, player, level);
                if (bed == null) {
                    fail("半径 " + radius + " 格内没有找到床。请先放一张床（place 一个 red_bed 之类的方块），"
                            + "或者走到有床的地方再试。");
                }
            }

            final BlockState bedState = level.getBlockState(bed);
            if (!bedState.is(BlockTags.BEDS)) {
                bed = null;
                fail("床不见了（" + GameUtils.format(bed == null ? player.blockPosition() : bed) + "）");
            }

            final Vec3 hitPoint = GameUtils.blockCenter(bed);
            final double distance = player.getEyePosition().distanceTo(hitPoint);
            final double reach = mc.gameMode.getPickRange() - 0.8;

            if (distance > reach) {
                if (navigator.goal() == null || navigator.goal().distSqr(bed) > 2.0) {
                    navigator.setGoal(bed, 1);
                }
                switch (navigator.step(mc, 1.0)) {
                    case ARRIVED, MOVING -> {
                        return null;
                    }
                    case FAILED -> {
                        navigator.releaseControl();
                        fail("无法走到床边：" + navigator.failureReason());
                        return null;
                    }
                    default -> {
                        return null;
                    }
                }
            }

            navigator.releaseControl();
            GameUtils.lookAt(player, hitPoint);

            // 对床的上表面右键
            final BlockHitResult hit = new BlockHitResult(
                    hitPoint.add(0, 0.4, 0), net.minecraft.core.Direction.UP, bed, false);
            final InteractionResult result = mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
            if (result.shouldSwing()) {
                player.swing(InteractionHand.MAIN_HAND);
            }
            clicks++;

            if (player.isSleeping()) {
                final JsonObject out = new JsonObject();
                out.addProperty("sleeping", true);
                out.addProperty("bed", GameUtils.format(bed));
                return TaskResult.success(out);
            }
            if (clicks > 20) {
                final JsonObject out = new JsonObject();
                out.addProperty("sleeping", false);
                out.addProperty("clickedTimes", clicks);
                out.addProperty("note", "已经右键床很多次但还是没睡上。可能现在不是夜晚，"
                        + "或者附近有怪物导致「你现在无法休息」。");
                return TaskResult.success(out);
            }
            return null;
        }

        private BlockPos findBed(final Minecraft mc, final LocalPlayer player, final ClientLevel level) {
            final BlockPos origin = player.blockPosition();
            BlockPos best = null;
            double bestDistance = Double.MAX_VALUE;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -6; dy <= 6; dy++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        if (dx * dx + dz * dz > radius * radius) {
                            continue;
                        }
                        final BlockPos pos = origin.offset(dx, dy, dz);
                        if (!level.hasChunkAt(pos)) {
                            continue;
                        }
                        if (!level.getBlockState(pos).is(BlockTags.BEDS)) {
                            continue;
                        }
                        final double distance = origin.distSqr(pos);
                        if (distance < bestDistance) {
                            bestDistance = distance;
                            best = pos;
                        }
                    }
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
        public String detail() {
            return bed == null ? "正在寻找床" : "正在走向床 " + GameUtils.format(bed);
        }
    }

    // ------------------------------------------------------------ 爬上方块

    /** 走到目标方块的顶部。参数：{@code x}/{@code y}/{@code z} 指定方块。 */
    public static Task jumpOnBlock(final String id, final JsonObject params, final long timeoutMs) {
        final BlockPos target = BasicTasks.readBlockPos(params);
        if (target == null) {
            return new MoveToTask.FailingTask(id, "jump_on_block", params,
                    "缺少参数 x/y/z（要爬上去的方块坐标）");
        }
        return new JumpOnBlockTask(id, params, timeoutMs, target);
    }

    private static final class JumpOnBlockTask extends Task {
        private final BlockPos target;
        private Navigator navigator;
        private int ticks;

        JumpOnBlockTask(final String id, final JsonObject params, final long timeoutMs, final BlockPos target) {
            super(id, "jump_on_block", params, timeoutMs, true, true);
            this.target = target;
        }

        @Override
        protected void onStart(final Minecraft mc) {
            if (mc.player == null || mc.level == null) {
                fail("尚未进入世界");
            }
            navigator = new Navigator(mc.level);
            // 目标是「方块上表面那一格」，A* 的上台阶逻辑会自然地把我们带上去
            navigator.setGoal(target.above(), 0);
        }

        @Override
        protected TaskResult onTick(final Minecraft mc) {
            final LocalPlayer player = mc.player;
            if (player == null || mc.level == null) {
                fail("玩家不存在");
            }
            ticks++;
            if (player.blockPosition().getY() >= target.getY() + 1
                    && player.blockPosition().distSqr(target) <= 4.0) {
                navigator.releaseControl();
                final JsonObject out = new JsonObject();
                out.add("pos", StateCollector.vec(player.position()));
                out.addProperty("standingOn", GameUtils.format(player.blockPosition()));
                return TaskResult.success(out);
            }
            switch (navigator.step(mc, 1.0)) {
                case ARRIVED -> {
                    if (player.blockPosition().getY() >= target.getY()) {
                        navigator.releaseControl();
                        final JsonObject out = new JsonObject();
                        out.add("pos", StateCollector.vec(player.position()));
                        return TaskResult.success(out);
                    }
                    // 到了旁边但没上去：原地起跳试试
                    if (ticks % 10 == 0) {
                        navigator.setGoal(target.above(), 0);
                    }
                    return null;
                }
                case MOVING -> {
                    return null;
                }
                case FAILED -> {
                    navigator.releaseControl();
                    fail("爬不上 " + GameUtils.format(target) + "：" + navigator.failureReason());
                    return null;
                }
                default -> {
                    return null;
                }
            }
        }

        @Override
        protected void onCancel(final Minecraft mc) {
            if (navigator != null) {
                navigator.releaseControl();
            }
        }

        @Override
        public String detail() {
            return "正在爬向 " + GameUtils.format(target.above());
        }
    }
}
