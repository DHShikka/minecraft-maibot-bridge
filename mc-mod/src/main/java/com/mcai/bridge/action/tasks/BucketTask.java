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
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code bucket}：装液体 / 倒液体。
 *
 * <h2>为什么单开一个动作</h2>
 *
 * <p>装水和倒水本质就是「对着方块右键」，{@code use_on_block} 其实做得到 —— 但差三件麻烦事，
 * 而这三件事恰恰是「生存模式从零搭地狱门」里最容易出错的地方：</p>
 *
 * <ol>
 *   <li><b>要找到真正的源头</b>。岩浆必须点<b>源方块</b>才装得起来，点到流动的岩浆什么都不会发生。
 *       手动找源头很烦，这里自动扫。</li>
 *   <li><b>倒的位置要对</b>。倒液体时流体落在<b>点击面的相邻格</b>，不是点的那一格本身 ——
 *       想倒在 (x,y,z) 就得点 (x,y-1,z) 的上面。算错一格就前功尽弃。</li>
 *   <li><b>手上得是桶</b>。空桶/水桶/岩浆桶之间还要来回换。</li>
 * </ol>
 *
 * <h2>参数</h2>
 * <pre>
 * {"mode": "fill",  "fluid": "lava", "radius": 24}        装一桶岩浆（自动找源头）
 * {"mode": "empty", "x": 10, "y": 64, "z": -3}            把桶里的液体倒在 (10,64,-3)
 * </pre>
 *
 * <p>倾倒目标位置下面没有方块时会明确报错（否则流体等于倒在虚空里）。</p>
 */
public final class BucketTask extends Task {

    private static final int SEARCH_RADIUS_MAX = 64;

    private enum Stage { FIND, APPROACH, USE, VERIFY, DONE }

    private final String mode;
    private final String fluid;
    private final int radius;
    private final Integer targetX;
    private final Integer targetY;
    private final Integer targetZ;

    private Stage stage = Stage.FIND;
    private int stageTicks;
    private BlockPos fluidPos;      // fill：要找的液体源；empty：要点的那个方块
    private BlockPos pourPos;       // empty：流体最终落在哪
    private Navigator navigator;
    private int usedTicks;
    private final List<String> notes = new ArrayList<>();

    private BucketTask(final String id, final JsonObject params, final long timeoutMs) {
        super(id, "bucket", params, timeoutMs, true, true);
        this.mode = Json.str(params, "mode", "fill").trim().toLowerCase(java.util.Locale.ROOT);
        this.fluid = Json.str(params, "fluid", "water").trim().toLowerCase(java.util.Locale.ROOT);
        this.radius = Math.max(4, Math.min(Json.intVal(params, "radius", 24), SEARCH_RADIUS_MAX));
        final boolean hasPos = Json.has(params, "x") && Json.has(params, "y") && Json.has(params, "z");
        this.targetX = hasPos ? Json.intVal(params, "x", 0) : null;
        this.targetY = hasPos ? Json.intVal(params, "y", 0) : null;
        this.targetZ = hasPos ? Json.intVal(params, "z", 0) : null;
    }

    public static Task create(final String id, final JsonObject params, final long timeoutMs) {
        final String mode = Json.str(params, "mode", "fill").trim().toLowerCase(java.util.Locale.ROOT);
        if (!"fill".equals(mode) && !"empty".equals(mode)) {
            return new MoveToTask.FailingTask(id, "bucket", params,
                    "mode 只能是 fill（装液体）或 empty（倒液体），收到「" + mode + "」。");
        }
        if ("empty".equals(mode) && !(Json.has(params, "x") && Json.has(params, "y") && Json.has(params, "z"))) {
            return new MoveToTask.FailingTask(id, "bucket", params,
                    "mode=empty 需要 x/y/z（液体倒在哪里）。");
        }
        return new BucketTask(id, params, timeoutMs);
    }

    @Override
    protected void onStart(final Minecraft mc) {
        navigator = new Navigator(mc.level);
    }

    // ------------------------------------------------------------ 主循环

    @Override
    protected TaskResult onTick(final Minecraft mc) {
        final LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            fail("玩家或世界不存在");
        }
        stageTicks++;
        return switch (stage) {
            case FIND -> tickFind(mc, player);
            case APPROACH -> tickApproach(mc, player);
            case USE -> tickUse(mc, player);
            case VERIFY -> tickVerify(mc, player);
            case DONE -> finish(player);
        };
    }

    // ---------------------------------------------------------------- FIND

    private TaskResult tickFind(final Minecraft mc, final LocalPlayer player) {
        if ("empty".equals(mode)) {
            // 倒液体：要点的方块 = 目标位置下面那一格；流体落在目标位置
            pourPos = new BlockPos(targetX, targetY, targetZ);
            final BlockPos below = pourPos.below();
            final BlockState belowState = mc.level.getBlockState(below);
            if (!mc.level.hasChunkAt(below)) {
                fail("目标位置下面的区块还没加载，没法确认能不能倒。");
            }
            if (belowState.isAir()) {
                fail("(" + pourPos.getX() + "," + pourPos.getY() + "," + pourPos.getZ()
                        + ") 下面没有方块，倒下去会直接流走。先垫个方块，或者换个位置。");
            }
            if (!mc.level.getBlockState(pourPos).isAir()) {
                fail("(" + pourPos.getX() + "," + pourPos.getY() + "," + pourPos.getZ()
                        + ") 已经有 " + GameUtils.blockId(mc.level.getBlockState(pourPos))
                        + " 占着了，倒不进去。");
            }
            fluidPos = below;
            // 手上要有装好的桶
            if (!isFilledBucket(player.getMainHandItem())) {
                final int slot = findFilledBucket(player);
                if (slot < 0) {
                    fail("手上和背包里都没有装好的桶。先用 mode=fill 装一桶水或岩浆。");
                }
                player.getInventory().selected = slot;
                notes.add("换上了 " + heldId(player));
            }
            moveTo(Stage.APPROACH);
            return null;
        }

        // ---- 装液体
        if (!heldIs(player, Items.BUCKET)) {
            final int slot = findItem(player, Items.BUCKET);
            if (slot < 0) {
                fail("没有空桶。空桶要 3 个铁锭合成（mc_craft item=bucket），铁锭要先挖铁矿再 mc_smelt。");
            }
            player.getInventory().selected = slot;
            notes.add("换上空桶");
        }
        fluidPos = findFluidSource(mc, player);
        if (fluidPos == null) {
            fail("半径 " + radius + " 格内没有找到" + fluidName() + "的**源头**。"
                    + "流动的液体装不起来，必须点源头。可以先用 mc_scan_blocks 找 "
                    + fluid + "（找不到就往地下挖，岩浆在深处更常见）。");
        }
        moveTo(Stage.APPROACH);
        return null;
    }

    /** 找最近的液体**源方块**（isSource）。 */
    private BlockPos findFluidSource(final Minecraft mc, final LocalPlayer player) {
        final BlockPos origin = player.blockPosition();
        final boolean wantLava = "lava".equals(fluid);
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -Math.min(radius, 24); dy <= 8; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    final BlockPos pos = origin.offset(dx, dy, dz);
                    if (!mc.level.hasChunkAt(pos)) {
                        continue;
                    }
                    final FluidState state = mc.level.getFluidState(pos);
                    if (state.isEmpty() || !state.isSource()) {
                        continue;
                    }
                    final boolean isLava = state.getType() == net.minecraft.world.level.material.Fluids.LAVA
                            || state.getType() == net.minecraft.world.level.material.Fluids.FLOWING_LAVA;
                    if (isLava != wantLava) {
                        continue;
                    }
                    final double d = origin.distSqr(pos);
                    if (d < bestDist) {
                        bestDist = d;
                        best = pos;
                    }
                }
            }
        }
        return best;
    }

    // ------------------------------------------------------------ APPROACH

    private TaskResult tickApproach(final Minecraft mc, final LocalPlayer player) {
        final Vec3 hitPoint = GameUtils.blockCenter(fluidPos);
        if (player.getEyePosition().distanceTo(hitPoint) <= 4.0) {
            moveTo(Stage.USE);
            return null;
        }
        if (navigator.goal() == null || navigator.goal().distSqr(fluidPos) > 4.0) {
            navigator.setGoal(fluidPos, 2);
        }
        return switch (navigator.step(mc, 1.0)) {
            case ARRIVED -> {
                navigator.releaseControl();
                yield null;
            }
            case MOVING -> {
                if (stageTicks > 20 * 40) {
                    navigator.releaseControl();
                    fail("走到 " + GameUtils.format(fluidPos) + " 旁边超时了："
                            + navigator.failureReason() + "（液体旁边可能不好站）");
                }
                yield null;
            }
            case FAILED -> {
                navigator.releaseControl();
                fail("走不到 " + GameUtils.format(fluidPos) + " 旁边：" + navigator.failureReason()
                        + "。液体周围常常站不住，可以先在岸边搭个落脚点。");
                yield null;
            }
        };
    }

    // ---------------------------------------------------------------- USE

    private TaskResult tickUse(final Minecraft mc, final LocalPlayer player) {
        final Interactive target = resolveClickTarget(mc, player);
        if (target == null) {
            fail("找不到可以点击的面（" + GameUtils.format(fluidPos) + " 周围没有实心方块可点）。");
        }
        GameUtils.lookAt(player, GameUtils.blockCenter(target.pos).add(0, 0.3, 0));
        final BlockHitResult hit = new BlockHitResult(
                GameUtils.blockCenter(target.pos).add(0, 0.3, 0), target.face, target.pos, false);
        final InteractionResult result = mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
        if (result.shouldSwing()) {
            player.swing(InteractionHand.MAIN_HAND);
        }
        usedTicks = 0;
        moveTo(Stage.VERIFY);
        return null;
    }

    /** 计算该点哪个方块、哪个面。 */
    private Interactive resolveClickTarget(final Minecraft mc, final LocalPlayer player) {
        if ("empty".equals(mode)) {
            // 点目标位置下面那格的上面 → 流体落在目标位置
            return new Interactive(fluidPos, Direction.UP);
        }
        // 装液体：直接点液体源方块本身（原版允许对着液体方块右键）
        return new Interactive(fluidPos, Direction.UP);
    }

    private record Interactive(BlockPos pos, Direction face) {
    }

    // ------------------------------------------------------------ VERIFY

    private TaskResult tickVerify(final Minecraft mc, final LocalPlayer player) {
        usedTicks++;
        if (usedTicks < 5) {
            return null; // 给服务端几 tick 反应
        }
        final String held = heldId(player);
        if ("fill".equals(mode)) {
            if (isFilledBucket(player.getMainHandItem())) {
                final JsonObject out = new JsonObject();
                out.addProperty("mode", "fill");
                out.addProperty("fluid", fluid);
                out.addProperty("got", held);
                out.add("source", StateCollector.blockPos(fluidPos));
                out.addProperty("content", "装到了一桶" + fluidName() + "（" + held + "）");
                return TaskResult.success(out);
            }
            if (usedTicks > 20) {
                fail("对着" + fluidName() + "源头右键了，但桶还是空的。"
                        + "可能是点到流动的液体了，或者中间被方块挡住。");
            }
            // 再点一次
            moveTo(Stage.USE);
            return null;
        }

        // 倒液体：手上应该从「装满的桶」变回空桶
        final BlockState now = mc.level.getBlockState(pourPos);
        final FluidState fluidNow = mc.level.getFluidState(pourPos);
        if (!fluidNow.isEmpty() || !now.isAir()) {
            final JsonObject out = new JsonObject();
            out.addProperty("mode", "empty");
            out.addProperty("fluid", fluid);
            out.add("pouredAt", StateCollector.blockPos(pourPos));
            out.addProperty("blockNow", fluidNow.isEmpty() ? GameUtils.blockId(now) : "fluid");
            out.addProperty("content", "把" + fluidName() + "倒在了 "
                    + GameUtils.format(pourPos));
            return TaskResult.success(out);
        }
        if (usedTicks > 20) {
            fail("对着 " + GameUtils.format(fluidPos) + " 右键了，但 " + GameUtils.format(pourPos)
                    + " 还是空的。可能目标位置不合法（太远/被挡），或者手上的桶不是装好液体的。");
        }
        moveTo(Stage.USE);
        return null;
    }

    // ------------------------------------------------------------ 收尾

    private TaskResult finish(final LocalPlayer player) {
        final JsonObject out = new JsonObject();
        out.addProperty("content", "完成");
        return TaskResult.success(out);
    }

    // ------------------------------------------------------------ 小工具

    private void moveTo(final Stage next) {
        stage = next;
        stageTicks = 0;
    }

    private String fluidName() {
        return "lava".equals(fluid) ? "岩浆" : "水";
    }

    private static String heldId(final LocalPlayer player) {
        final ItemStack held = player.getMainHandItem();
        return held.isEmpty() ? "空手" : GameUtils.itemId(held);
    }

    private static boolean heldIs(final LocalPlayer player, final net.minecraft.world.item.Item item) {
        return player.getMainHandItem().is(item);
    }

    private static boolean isFilledBucket(final ItemStack stack) {
        return stack.is(Items.WATER_BUCKET) || stack.is(Items.LAVA_BUCKET);
    }

    private static int findItem(final LocalPlayer player, final net.minecraft.world.item.Item item) {
        final Inventory inv = player.getInventory();
        for (int slot = 0; slot < 9; slot++) {
            if (inv.getItem(slot).is(item)) {
                return slot;
            }
        }
        for (int slot = 9; slot < inv.getContainerSize(); slot++) {
            if (inv.getItem(slot).is(item)) {
                return slot;
            }
        }
        return -1;
    }

    private static int findFilledBucket(final LocalPlayer player) {
        final Inventory inv = player.getInventory();
        for (int slot = 0; slot < inv.getContainerSize(); slot++) {
            if (isFilledBucket(inv.getItem(slot))) {
                return slot;
            }
        }
        return -1;
    }

    @Override
    protected void onCancel(final Minecraft mc) {
        if (navigator != null) {
            navigator.releaseControl();
        }
    }

    @Override
    public String detail() {
        return "桶操作（" + mode + " " + fluid + "）：" + stage.name().toLowerCase();
    }

    @Override
    protected boolean watchdogApplies() {
        return false; // 找液体/走路都可能长时间不动，交给各自的超时判断
    }
}
