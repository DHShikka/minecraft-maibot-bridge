package com.mcai.bridge.action.tasks;

import com.google.gson.JsonArray;
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
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 挖掘类动作：{@code mine}（挖一个指定坐标的方块）与 {@code mine_blocks}（挖若干同类方块）。
 *
 * <p>完整流程：</p>
 * <ol>
 *   <li>走到方块旁边（A* 寻路到最近的可站立位置）；</li>
 *   <li>把视线对准方块；</li>
 *   <li>调用 {@code startDestroyBlock} / {@code continueDestroyBlock} 持续挖，直到方块消失；</li>
 *   <li>验证方块确实变了，否则报错而不是假装成功。</li>
 * </ol>
 *
 * <p>{@code mine_blocks} 会先扫描周围匹配的方块，按距离从近到远逐个挖，中途会自动重新寻路。</p>
 */
public final class MineTask extends Task {

    /** 留一点余量，避免在服务器视角里刚好超出交互距离。 */
    private static final double REACH_MARGIN = 0.7;

    private final String blockSpec;
    private final List<BlockPos> queue = new ArrayList<>();
    private final int wantedCount;
    private final int scanRadius;
    private final boolean requireCorrectTool;

    private Navigator navigator;
    private BlockPos currentTarget;
    private BlockPos originalBlockId;
    private BlockState expectedState;
    private boolean mining;
    private int miningTicks;
    private int approachTicks;
    private int minedCount;
    private int missCount;
    private final JsonArray minedList = new JsonArray();
    private final List<BlockPos> failed = new ArrayList<>();

    private MineTask(final String id, final String type, final JsonObject params, final long timeoutMs,
                     final String blockSpec, final int wantedCount, final int scanRadius,
                     final boolean requireCorrectTool) {
        super(id, type, params, timeoutMs, true, true);
        this.blockSpec = blockSpec;
        this.wantedCount = wantedCount;
        this.scanRadius = scanRadius;
        this.requireCorrectTool = requireCorrectTool;
    }

    // ------------------------------------------------------------ 工厂方法

    /** {@code mine}：挖掘坐标 x/y/z 上的方块。 */
    public static Task single(final String id, final JsonObject params, final long timeoutMs) {
        final BlockPos pos = BasicTasks.readBlockPos(params);
        if (pos == null) {
            return new MoveToTask.FailingTask(id, "mine", params,
                    "缺少参数 x/y/z（要挖掉的方块坐标）。如果只知道方块类型，请改用 mine_blocks。");
        }
        final MineTask task = new MineTask(id, "mine", params, timeoutMs, null, 1, 0,
                Json.bool(params, "requireCorrectTool", false));
        task.queue.add(pos);
        return task;
    }

    /** {@code mine_blocks}：扫描并挖掘指定类型的方块。 */
    public static Task multiple(final String id, final JsonObject params, final long timeoutMs) {
        final String spec = Json.str(params, "block", Json.str(params, "blockId", ""));
        if (spec.isBlank()) {
            return new MoveToTask.FailingTask(id, "mine_blocks", params,
                    "缺少参数 block（方块名，例如 iron_ore，或标签 #minecraft:logs）");
        }
        if (!spec.startsWith("#") && GameUtils.resolveBlock(spec) == null) {
            return new MoveToTask.FailingTask(id, "mine_blocks", params,
                    "无法识别的方块名：" + spec + "。请使用原版 ID，例如 iron_ore、diamond_ore、oak_log。");
        }
        return new MineTask(id, "mine_blocks", params, timeoutMs, spec,
                Json.intVal(params, "count", 1),
                clamp(Json.intVal(params, "radius", 32), 2, 64),
                Json.bool(params, "requireCorrectTool", false));
    }

    // ------------------------------------------------------------ 执行流程

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
        if (player == null || level == null) {
            fail("玩家或世界不存在");
        }

        // ------------------------------------------------------ 取下一个目标
        if (currentTarget == null) {
            if (!pickNextTarget(mc, player, level)) {
                return finishResult(true);
            }
            approachTicks = 0;
            missCount = 0;
        }

        final BlockState state = level.getBlockState(currentTarget);
        if (state.isAir()) {
            // 已经被别人挖掉了，算作成功
            recordMined(mc, player, currentTarget, state);
            currentTarget = null;
            return null;
        }
        if (expectedState != null && !state.is(expectedState.getBlock())) {
            recordMined(mc, player, currentTarget, state);
            currentTarget = null;
            return null;
        }

        // -------------------------------------------------------- 距离与视线
        final Vec3 center = GameUtils.blockCenter(currentTarget);
        final double distance = player.getEyePosition().distanceTo(center);
        final double reach = (mc.gameMode != null ? mc.gameMode.getPickRange() : 4.5f) - REACH_MARGIN;

        if (distance > reach) {
            stopMining(mc);
            if (navigator == null) {
                navigator = new Navigator(level);
            }
            final BlockPos standAt = findStandPosition(mc, player, currentTarget, reach);
            if (standAt == null) {
                fail("找不到可以站在 " + GameUtils.format(currentTarget) + " 旁边的落脚点。"
                        + "这个方块可能被完全包围，或者悬在够不到的地方。");
            }
            if (navigator.goal() == null || navigator.goal().distSqr(standAt) > 1.0) {
                navigator.setGoal(standAt, 0);
            }
            switch (navigator.step(mc, 1.0)) {
                case ARRIVED -> {
                    navigator.releaseControl();
                    return null;
                }
                case MOVING -> {
                    approachTicks++;
                    if (approachTicks > 600) {
                        navigator.releaseControl();
                        fail("走了很久都没能靠近 " + GameUtils.format(currentTarget) + "："
                                + navigator.failureReason());
                    }
                    return null;
                }
                case FAILED -> {
                    navigator.releaseControl();
                    // 够不到就换下一个目标，别整个任务失败
                    failed.add(currentTarget);
                    com.mcai.bridge.McAiBridge.LOGGER.debug("[MaiBot Bridge] 放弃 {}：{}",
                            GameUtils.format(currentTarget), navigator.failureReason());
                    currentTarget = null;
                    missCount++;
                    if (missCount > 8) {
                        return finishResult(true);
                    }
                    return null;
                }
                default -> {
                    return null;
                }
            }
        }

        // ------------------------------------------------------------ 开始挖
        navigatorRelease();

        // 视线必须对准方块，否则服务端会认为你在挖别的东西
        GameUtils.lookAt(player, center);

        if (mc.gameMode == null) {
            fail("gameMode 不存在");
        }

        if (state.getDestroySpeed(level, currentTarget) < 0.0f) {
            fail(GameUtils.blockId(state) + " 是挖不掉的方块（例如基岩、传送门框架）。换个目标吧。");
        }

        // 手上工具不对就自动换一把。
        //
        // 为什么必须自动换：铁矿石要石镐、钻石要铁镐、黑曜石要钻石镐 —— 不对的话
        // 原版要么挖得极慢（黑曜石空手要 250 秒），要么挖掉了也不掉落。
        // 「生存模式从零搭地狱门」这种长流程，卡在这里就是硬卡。
        if (!mining && state.requiresCorrectToolForDrops() && !player.hasCorrectToolForDrops(state)) {
            if (!equipBestTool(mc, player, state)) {
                fail(GameUtils.blockId(state) + " 必须有正确的工具才会掉落，而背包里没有能挖它的工具"
                        + "（手上是 " + describeHeld(player) + "）。先 craft 一把合适的镐子吧。");
            }
        }

        final Direction face = faceTowards(player, center);

        if (!mining) {
            expectedState = state;
            originalBlockId = currentTarget;
            final boolean started = mc.gameMode.startDestroyBlock(currentTarget, face);
            miningTicks = 0;
            mining = true;
            if (!started) {
                // 原版返回 false 通常意味着「没抬手成功」，下一 tick 继续尝试即可
                com.mcai.bridge.McAiBridge.LOGGER.debug("[MaiBot Bridge] startDestroyBlock 返回 false：{}",
                        GameUtils.format(currentTarget));
            }
            // 顺手挥一下手，让服务端与客户端动画一致
            player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            return null;
        }

        miningTicks++;
        mc.gameMode.continueDestroyBlock(currentTarget, face);

        // 挖的途中方块变了 → 成功
        final BlockState now = level.getBlockState(currentTarget);
        if (now.isAir() || (expectedState != null && !now.is(expectedState.getBlock()))) {
            recordMined(mc, player, currentTarget, expectedState);
            stopMining(mc);
            currentTarget = null;
            return null;
        }

        // 太久没挖掉：可能是工具不对（速度极慢）或者服务端拒绝
        if (miningTicks > 20 * 60) {
            stopMining(mc);
            fail("挖 " + GameUtils.format(currentTarget) + "（" + GameUtils.blockId(state) + "）超过 60 秒仍未破坏。"
                    + (player.hasCorrectToolForDrops(state) ? "" : "手上没有正确的工具，挖掘速度会非常慢；")
                    + "建议先 equip 一把合适的工具，或者换一个目标。");
        }
        return null;
    }

    // ---------------------------------------------------------------- 辅助

    private void navigatorRelease() {
        if (navigator != null) {
            navigator.releaseControl();
        }
    }

    /**
     * 把背包里能正确挖这个方块的工具换到手上。
     *
     * <p>先看快捷栏（直接选中，无副作用）；不在快捷栏就和当前槽位**交换**
     * （原版的数字键交换，{@code ClickType.SWAP}）。挖矿时玩家背包界面通常是开着的
     * （{@code InventoryMenu} 常驻），所以不需要额外开界面。</p>
     *
     * @return 现在手上是不是已经有正确的工具了
     */
    private boolean equipBestTool(final Minecraft mc, final LocalPlayer player, final BlockState state) {
        for (int slot = 0; slot < 9; slot++) {
            if (player.getInventory().getItem(slot).isCorrectToolForDrops(state)) {
                player.getInventory().selected = slot;
                com.mcai.bridge.McAiBridge.LOGGER.debug("[MaiBot Bridge] 自动切换到快捷栏第 {} 格来挖 {}",
                        slot, GameUtils.blockId(state));
                return true;
            }
        }
        if (mc.gameMode == null
                || !(player.containerMenu instanceof net.minecraft.world.inventory.InventoryMenu menu)) {
            return false;
        }
        final int selected = player.getInventory().selected;
        for (int slot = 9; slot < player.getInventory().getContainerSize(); slot++) {
            if (!player.getInventory().getItem(slot).isCorrectToolForDrops(state)) {
                continue;
            }
            final int menuSlot = BasicTasks.findMenuSlot(menu, player, slot);
            if (menuSlot < 0) {
                continue;
            }
            mc.gameMode.handleInventoryMouseClick(menu.containerId, menuSlot, selected,
                    net.minecraft.world.inventory.ClickType.SWAP, player);
            com.mcai.bridge.McAiBridge.LOGGER.debug("[MaiBot Bridge] 自动把背包第 {} 格的工具换到手上挖 {}",
                    slot, GameUtils.blockId(state));
            return true;
        }
        return false;
    }

    private static String describeHeld(final LocalPlayer player) {
        final var held = player.getMainHandItem();
        return held.isEmpty() ? "空手" : GameUtils.itemId(held);
    }

    /**
     * 卡死检测用的真实进展：挖到的方块数。
     *
     * <p>挖矿时玩家本来就是站着不动的（挖一个方块要 1~2 秒），
     * 只靠位移判断会把正常挖矿误判成卡死，所以必须报这个计数。</p>
     */
    @Override
    protected double realProgress() {
        return minedCount;
    }

    private void stopMining(final Minecraft mc) {
        if (mining && mc.gameMode != null) {
            mc.gameMode.stopDestroyBlock();
        }
        mining = false;
        expectedState = null;
    }

    /** 挑出下一个要挖的方块；返回 false 表示没有更多目标了。 */
    private boolean pickNextTarget(final Minecraft mc, final LocalPlayer player, final ClientLevel level) {
        stopMining(mc);
        // 「挖够了没有」必须在取队列之前判断。
        //
        // 之前是先取队列、后判断，而扫一次会把半径内所有匹配方块（最多 64 个）都排进队列 ——
        // 结果 mine_blocks(count=3) 会把队里剩下的全挖完才停。真机自检里
        // 「挖 3 个泥土」实际挖了 64 个，就是这个原因（在游戏里表现为「一直挖个不停」）。
        if (blockSpec != null && wantedCount > 0 && minedCount >= wantedCount) {
            queue.clear();
            return false;
        }
        if (!queue.isEmpty()) {
            currentTarget = queue.remove(0);
            return true;
        }
        if (blockSpec == null) {
            return false;
        }
        final List<BlockPos> found = QueryTasks.collectBlocks(level, player.blockPosition(), blockSpec,
                scanRadius, Math.max(16, scanRadius), 64);
        found.removeAll(failed);
        if (found.isEmpty()) {
            if (minedCount == 0) {
                fail("半径 " + scanRadius + " 格内没有找到 " + blockSpec + "。"
                        + "可以先用 scan_blocks 确认附近到底有什么，或者扩大 radius 后重试。");
            }
            return false;
        }
        currentTarget = found.get(0);
        for (int i = 1; i < found.size(); i++) {
            queue.add(found.get(i));
        }
        return true;
    }

    private void recordMined(final Minecraft mc, final LocalPlayer player, final BlockPos pos, final BlockState state) {
        minedCount++;
        final JsonObject o = new JsonObject();
        o.add("pos", StateCollector.blockPos(pos));
        o.addProperty("block", state == null ? "unknown" : GameUtils.blockId(state));
        o.addProperty("name", state == null ? "unknown" : GameUtils.blockName(state));
        if (state != null) {
            o.addProperty("dropped", player.hasCorrectToolForDrops(state));
        }
        minedList.add(o);
        com.mcai.bridge.McAiBridge.LOGGER.debug("[MaiBot Bridge] 已挖掉 {}", GameUtils.format(pos));
    }

    private TaskResult finishResult(final boolean ignoreNothingFound) {
        final JsonObject out = new JsonObject();
        out.addProperty("minedCount", minedCount);
        out.add("mined", minedList);
        out.addProperty("skipped", failed.size());
        if (minedCount == 0 && !ignoreNothingFound) {
            return TaskResult.fail("一个方块都没挖掉");
        }
        if (failed.isEmpty() && (blockSpec == null || minedCount >= wantedCount)) {
            return TaskResult.success(out);
        }
        out.addProperty("note", "已挖到 " + minedCount + " 个"
                + (failed.isEmpty() ? "" : "，" + failed.size() + " 个因为够不到被跳过")
                + "。如果还需要更多，请再调用一次。");
        return TaskResult.success(out);
    }

    /** 找到能站、且能打到目标方块的位置。 */
    private BlockPos findStandPosition(final Minecraft mc, final LocalPlayer player,
                                       final BlockPos target, final double reach) {
        final ClientLevel level = mc.level;
        final List<BlockPos> candidates = new ArrayList<>();
        for (int dx = -3; dx <= 3; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -3; dz <= 3; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    final BlockPos candidate = target.offset(dx, dy, dz);
                    if (!level.hasChunkAt(candidate)) {
                        continue;
                    }
                    final Vec3 center = GameUtils.blockCenter(target);
                    final double distance = Math.sqrt(
                            Math.pow(candidate.getX() + 0.5 - center.x, 2)
                                    + Math.pow(candidate.getY() + 1.7 - center.y, 2)
                                    + Math.pow(candidate.getZ() + 0.5 - center.z, 2));
                    if (distance > reach) {
                        continue;
                    }
                    if (!GameUtils.canStandAt(level, candidate)) {
                        continue;
                    }
                    candidates.add(candidate);
                }
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        candidates.sort(Comparator.comparingDouble(pos -> player.blockPosition().distSqr(pos)));
        return candidates.get(0);
    }

    /** 从玩家看向方块的方向，决定要「点」哪一面。 */
    private static Direction faceTowards(final LocalPlayer player, final Vec3 blockCenter) {
        final Vec3 delta = player.getEyePosition().subtract(blockCenter);
        return Direction.getNearest(delta.x, delta.y, delta.z);
    }

    @Override
    protected void onCancel(final Minecraft mc) {
        stopMining(mc);
        navigatorRelease();
    }

    @Override
    public double progress() {
        if (blockSpec == null) {
            return minedCount > 0 ? 1.0 : -1.0;
        }
        if (wantedCount <= 0) {
            return -1;
        }
        return Math.min(1.0, (double) minedCount / wantedCount);
    }

    @Override
    public String detail() {
        if (currentTarget == null) {
            return String.format(Locale.ROOT, "已挖 %d/%d 个，正在寻找下一个目标", minedCount, wantedCount);
        }
        return String.format(Locale.ROOT, "已挖 %d/%d 个，正在处理 %s%s",
                minedCount, wantedCount, GameUtils.format(currentTarget),
                mining ? "（破坏中 " + (miningTicks / 20) + "s）" : "（正在靠近）");
    }

    private static int clamp(final int value, final int min, final int max) {
        return Math.max(min, Math.min(max, value));
    }
}
