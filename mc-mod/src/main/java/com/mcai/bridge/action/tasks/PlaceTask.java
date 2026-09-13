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
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

/**
 * 放置与交互类动作：{@code place}（放方块）/ {@code use_on_block}（右键某个方块）。
 *
 * <p>两者都是「走到够得着的地方 → 看向目标面 → 走原版的 {@code useItemOn}」，
 * 这样箱子、按钮、工作台、门之类的方块交互和真人右键完全一致。</p>
 */
public final class PlaceTask extends Task {

    private static final double REACH_MARGIN = 0.8;

    /**
     * {@code use_on_block} 点完之后等界面的最多 tick 数。
     *
     * <p>为什么值得等：右键箱子/工作台/熔炉这类，**界面弹出来才是真的成了**。
     * 只看 {@code useItemOn} 的返回值会把没生效的点击报成成功 ——
     * 真机上就出现过「目标在 6 格外，客户端回 SUCCESS/PASS，服务端其实把交互包丢了」。</p>
     */
    private static final int SCREEN_WAIT_TICKS = 12;

    /**
     * 目标坐标。创建任务时可能还是 null —— 因为 {@code "~"} 这种相对坐标
     * 要等 {@link #onStart} 拿到玩家位置才算得出来。
     */
    private BlockPos target;
    private final boolean placeMode;
    private final String preferredFace;
    private final String equipItem;
    private final int equipSlot;
    private Navigator navigator;
    private boolean equipped;
    private int approachTicks;
    private Direction chosenFace;
    private BlockPos clickBlock;
    /** 「导航说到地方了、但其实还是够不着」连续出现了几次。 */
    private int arrivedNoReach;
    /** 点完之后的回执（等界面时先存着）。 */
    private JsonObject pending;
    private int screenWait;
    /** 点击之前是不是已经有界面开着（有的话就看不出这次点击的效果）。 */
    private boolean screenWasOpen;

    private PlaceTask(final String id, final String type, final JsonObject params, final long timeoutMs,
                      final BlockPos target, final boolean placeMode, final String preferredFace,
                      final String equipItem, final int equipSlot) {
        super(id, type, params, timeoutMs, true, true);
        this.target = target;
        this.placeMode = placeMode;
        this.preferredFace = preferredFace;
        this.equipItem = equipItem;
        this.equipSlot = equipSlot;
    }

    // ------------------------------------------------------------ 工厂方法

    /** {@code place}：把手中的方块放到 x/y/z。坐标支持 {@code "~"} 相对写法。 */
    public static Task place(final String id, final JsonObject params, final long timeoutMs) {
        if (!BasicTasks.hasBlockPos(params)) {
            return new MoveToTask.FailingTask(id, "place", params,
                    "缺少参数 x/y/z（要放置方块的目标坐标）。要放在自己脚下可以写 "
                            + "{\"x\": \"~\", \"y\": \"~-1\", \"z\": \"~\"}。");
        }
        return new PlaceTask(id, "place", params, timeoutMs, null, true,
                Json.str(params, "face", ""),
                Json.str(params, "item", ""),
                Json.intVal(params, "slot", -1));
    }

    /** {@code use_on_block}：右键点击某个方块（开箱子、按按钮、用工作台…）。 */
    public static Task useOnBlock(final String id, final JsonObject params, final long timeoutMs) {
        if (!BasicTasks.hasBlockPos(params)) {
            return new MoveToTask.FailingTask(id, "use_on_block", params,
                    "缺少参数 x/y/z（要交互的方块坐标）。");
        }
        return new PlaceTask(id, "use_on_block", params, timeoutMs, null, false,
                Json.str(params, "face", ""), "", -1);
    }

    // ------------------------------------------------------------ 执行流程

    @Override
    protected void onStart(final Minecraft mc) {
        if (mc.player == null || mc.level == null) {
            fail("尚未进入世界");
        }
        // 相对坐标（"~"）以玩家脚下那一格为基准，所以只能等到这里才解析
        if (target == null) {
            final BlockPos resolved = BasicTasks.readBlockPos(params, mc.player.blockPosition());
            if (resolved == null) {
                fail("坐标解析不了：" + params + "。x/y/z 要么是整数，要么是 \"~\" / \"~-1\" "
                        + "这样的相对坐标（相对你脚下的方块）。");
            }
            target = resolved;
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
        if (mc.gameMode == null) {
            fail("gameMode 不存在");
        }

        // 已经点过了：等界面（开着就说明真的生效了）
        if (pending != null) {
            return settle(mc);
        }

        // ------------------------------------------------------ 需要的话先换物品
        if (!equipped) {
            equipped = true;
            if (equipSlot >= 0 || !equipItem.isBlank()) {
                final Inventory inv = player.getInventory();
                final int slot = equipSlot >= 0 ? equipSlot : InventoryTasks.findSlot(inv, equipItem);
                if (slot < 0) {
                    fail("背包里没有「" + equipItem + "」，放不了。");
                }
                if (slot > 8) {
                    // 东西在背包里但没在快捷栏：**自己换过去**，别让调用方再补一步。
                    // 真机上就是这么卡住的：熔炉刚合成在 34 号槽，place 直接说
                    // 「不在快捷栏里」—— 明明背包里有，却要 AI 再想一轮。
                    InventoryTasks.swapIntoHotbar(mc, player, slot);
                    return null;   // 换位要一个 tick 才生效
                }
                inv.selected = slot;
            }
        }

        final BlockState targetState = level.getBlockState(target);

        // 放置模式下，目标位置必须是可以被替换的（空气/水/草）
        if (placeMode && !isReplaceable(level, target)) {
            fail("目标位置 " + GameUtils.format(target) + " 已经被 " + GameUtils.blockId(targetState)
                    + " 占住了，不能放东西。请换一个坐标，或者先 mine 把它挖掉。");
        }

        // ---------------------------------------------------------- 找一个可点击的面
        if (chosenFace == null || clickBlock == null) {
            if (!selectFace(mc, player, level)) {
                fail("在 " + GameUtils.format(target) + " 周围找不到可以点击的实心方块面。"
                        + (placeMode
                           ? "放置方块必须挨着一个已有的实体方块，请把目标坐标改到方块旁边。"
                           : "这个位置可能已经被移除或者不可交互。"));
            }
        }

        final Vec3 hitPoint = GameUtils.blockCenter(clickBlock)
                .add(chosenFace.getStepX() * 0.5, chosenFace.getStepY() * 0.5, chosenFace.getStepZ() * 0.5);

        final double distance = player.getEyePosition().distanceTo(hitPoint);
        final double reach = mc.gameMode.getPickRange() - REACH_MARGIN;

        if (distance > reach) {
            if (navigator == null) {
                navigator = new Navigator(level);
            }
            final BlockPos standAt = findStandPosition(mc, player, hitPoint, reach);
            if (standAt == null) {
                fail((placeMode ? "放置" : "交互") + "目标 " + GameUtils.format(target)
                        + " 够不到，而且附近没有合适的落脚点。请先靠近一些。");
            }
            if (navigator.goal() == null || navigator.goal().distSqr(standAt) > 1.0) {
                navigator.setGoal(standAt, 0);
            }
            switch (navigator.step(mc, 1.0)) {
                case ARRIVED -> {
                    navigator.releaseControl();
                    // 「导航说到地方了」不等于「真的够得着」：落脚点是按容差挑出来的，
                    // 有时候到了还是差一点。以前这里直接 return null 让它下一 tick 重算，
                    // 于是「走一点 → 还是够不着 → 再走一点」可以一直循环到超时/看门狗
                    // （真机上就表现成「use_on_block 卡死」）。所以这里数着，连续几次
                    // 到了还是够不着就直接说清楚。
                    if (++arrivedNoReach > 30) {
                        fail("走到落脚点了，但离 " + GameUtils.format(target) + " 还差 "
                                + round(distance) + " 格（够得着的上限约 " + round(reach)
                                + " 格）。多半是那个位置站不住、或者被别的东西挡着。"
                                + "可以先自己 mc_move_to 靠近一点，再回来右键。");
                    }
                    return null;
                }
                case MOVING -> {
                    arrivedNoReach = 0;
                    approachTicks++;
                    if (approachTicks > 600) {
                        navigator.releaseControl();
                        fail("走了很久都靠不近 " + GameUtils.format(target) + "：" + navigator.failureReason());
                    }
                    return null;
                }
                case FAILED -> {
                    navigator.releaseControl();
                    fail("无法走到 " + GameUtils.format(target) + " 旁边：" + navigator.failureReason());
                    return null;
                }
                default -> {
                    return null;
                }
            }
        }

        // -------------------------------------------------------------- 正式点击
        if (navigator != null) {
            navigator.releaseControl();
        }
        GameUtils.lookAt(player, hitPoint);

        screenWasOpen = mc.screen != null;
        final InteractionHand hand = InteractionHand.MAIN_HAND;
        final BlockHitResult hit = new BlockHitResult(hitPoint, chosenFace, clickBlock, false);
        final InteractionResult result = mc.gameMode.useItemOn(player, hand, hit);
        if (result.shouldSwing()) {
            player.swing(hand);
        }

        final JsonObject out = new JsonObject();
        out.add("target", StateCollector.blockPos(target));
        out.addProperty("clickedBlock", GameUtils.blockId(level.getBlockState(clickBlock)));
        out.addProperty("clickedPos", GameUtils.format(clickBlock));
        out.addProperty("face", chosenFace.getSerializedName());
        out.addProperty("result", String.valueOf(result));
        out.addProperty("handItem", GameUtils.itemId(player.getMainHandItem()));
        // 距离报出来：够不够得着是「这次点击算不算数」的关键（服务端对超过 6 格的交互
        // 直接丢包，而客户端这边照样会回一个 SUCCESS），AI 看到数字就能自己判断。
        out.addProperty("distance", round(distance));

        if (placeMode) {
            final BlockState after = level.getBlockState(target);
            final boolean placed = !isReplaceable(level, target);
            out.addProperty("placed", placed);
            out.addProperty("blockNow", GameUtils.blockId(after));
            if (!placed) {
                out.addProperty("hint", "点击已发出，但目标位置仍是空的。可能服务器拒绝了这次放置（距离过远、"
                        + "权限不足、或者目标位置其实不可放置）。可以换一个相邻坐标再试。");
            }
            return TaskResult.success(out);
        }

        // 右键类：**再等几 tick 看界面弹没弹** —— 这是「到底成了没」唯一的硬证据。
        pending = out;
        return null;
    }

    /**
     * 点完之后等界面。
     *
     * <p>{@code useItemOn} 的返回值只代表**客户端**愿不愿意处理这次交互，
     * 服务端那边完全可以把它丢掉（超过 6 格、方块被别人挖了、权限不够…）。
     * 而「界面弹出来了」是客户端能亲眼看到的结果，所以把它当成功判据。</p>
     */
    private TaskResult settle(final Minecraft mc) {
        if (screenWasOpen) {
            pending.addProperty("screenOpened", false);
            pending.addProperty("note", "点之前就已经有界面开着了，所以看不出这次点击的效果。"
                    + "想确认就先 mc_close_screen 关掉、再右键一次。");
            return TaskResult.success(pending);
        }
        if (mc.screen != null) {
            String title = "";
            try {
                title = mc.screen.getTitle().getString();
            } catch (final Throwable ignored) {
                // 少数界面没有标题
            }
            pending.addProperty("screenOpened", true);
            pending.addProperty("screen", mc.screen.getClass().getSimpleName());
            if (!title.isBlank()) {
                pending.addProperty("screenTitle", title);
            }
            pending.addProperty("note", "界面已经弹出来了 —— 这次交互确实生效了。"
                    + "界面开着的时候用 mc_close_screen 关掉再干别的。");
            return TaskResult.success(pending);
        }
        if (++screenWait >= SCREEN_WAIT_TICKS) {
            pending.addProperty("screenOpened", false);
            pending.addProperty("note", "点完之后界面没有弹出来。两种情况都可能有："
                    + "① 正常 —— 按钮、拉杆、门、耕地这些本来就不开界面；"
                    + "② 这次交互服务端根本没理（超过了交互距离、方块已经不在、没权限）。"
                    + "拿不准就 mc_state 看一眼准星那个方块变了没有，别反复重试。");
            return TaskResult.success(pending);
        }
        return null;
    }

    // ---------------------------------------------------------------- 辅助

    /** 保留两位小数（回执里报距离用）。 */
    private static double round(final double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    /**
     * 目标位置是否可以放东西。
     *
     * <p>原版没有公开的无参 {@code canBeReplaced()}，所以这里用「空气 / 流体 / 没有碰撞体积
     * （草、花、火把、雪）」来判断，覆盖了实际会遇到的绝大多数情况。</p>
     */
    private boolean isReplaceable(final ClientLevel level, final BlockPos pos) {
        final BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return true;
        }
        if (!state.getFluidState().isEmpty()) {
            return true;
        }
        return state.getCollisionShape(level, pos).isEmpty();
    }

    /** 遍历目标位置相邻的 6 个方向，挑一个实心面作为点击点。 */
    private boolean selectFace(final Minecraft mc, final LocalPlayer player, final ClientLevel level) {
        final Direction preferred = GameUtils.parseDirection(preferredFace, null);

        // ---- 目标格**自己就有方块**（箱子 / 拉杆 / 工作台 / 熔炉 / 按钮…）：必须点它本身。
        //
        // 真机 bug：以前一律走下面那套「找邻居的实心面」逻辑，而那是给「往空格里放方块」用的 ——
        // 于是右键箱子时射线落到了箱子**下面那格**（草方块顶面），result 直接 FAIL；
        // 右键工作台更糟，会卡死到被看门狗掐。真机证据：
        //   target (7,-60,9) → clickedBlock minecraft:grass_block / clickedPos (7,-61,9)
        if (!isReplaceable(level, target)) {
            chosenFace = preferred != null ? preferred : sideTowardPlayer(player, target);
            clickBlock = target;
            return true;
        }

        Direction best = null;
        BlockPos bestBlock = null;
        double bestScore = Double.MAX_VALUE;

        for (final Direction direction : Direction.values()) {
            final BlockPos neighbor = target.relative(direction);
            if (!level.hasChunkAt(neighbor)) {
                continue;
            }
            final BlockState state = level.getBlockState(neighbor);
            if (state.isAir() || state.getCollisionShape(level, neighbor).isEmpty()) {
                continue;
            }
            final Vec3 hitPoint = GameUtils.blockCenter(neighbor)
                    .add(direction.getStepX() * 0.5, direction.getStepY() * 0.5, direction.getStepZ() * 0.5);
            double score = player.getEyePosition().distanceTo(hitPoint);
            if (preferred != null) {
                // 指定了面就强烈优先 —— 注意**朝向要反过来看**：
                // 「点某个邻居的 X 面」等价于「那个邻居在目标的 X 反方向」。
                // 以前这里写的是 direction == preferred，于是 face=up 会去找**目标上方**的邻居、
                // 点它的底面（等于从天花板往下挂），而调用方想要的显然是
                // 「点下面那块方块的顶面」—— 也就是邻居应该在 DOWN 方向。
                score += direction == preferred.getOpposite() ? -1000.0 : 100.0;
            }
            if (direction == Direction.UP) {
                score -= 0.5; // 顶面最容易点到，稍微倾斜一下选择
            }
            if (score < bestScore) {
                bestScore = score;
                best = direction;
                bestBlock = neighbor;
            }
        }

        if (best == null) {
            return false;
        }
        // 点击面 = 从目标看向邻居的反方向
        chosenFace = best.getOpposite();
        clickBlock = bestBlock;
        return true;
    }

    /** 玩家站在目标方块的哪一侧（决定右键它哪一面）。 */
    private static Direction sideTowardPlayer(final LocalPlayer player, final BlockPos target) {
        final Vec3 eye = player.getEyePosition();
        final Vec3 center = GameUtils.blockCenter(target);
        final double dx = eye.x - center.x;
        final double dy = eye.y - center.y;
        final double dz = eye.z - center.z;
        // 水平方向优先（大多数方块交互都是从侧面点的），水平差不多时再看上下
        if (Math.abs(dx) >= Math.abs(dz)) {
            if (Math.abs(dx) > Math.abs(dy)) {
                return dx > 0 ? Direction.EAST : Direction.WEST;
            }
        } else if (Math.abs(dz) > Math.abs(dy)) {
            return dz > 0 ? Direction.SOUTH : Direction.NORTH;
        }
        return dy > 0 ? Direction.UP : Direction.DOWN;
    }

    private BlockPos findStandPosition(final Minecraft mc, final LocalPlayer player,
                                       final Vec3 hitPoint, final double reach) {        final ClientLevel level = mc.level;
        final BlockPos anchor = BlockPos.containing(hitPoint);
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -3; dz <= 3; dz++) {
                    final BlockPos candidate = anchor.offset(dx, dy, dz);
                    if (!level.hasChunkAt(candidate) || !GameUtils.canStandAt(level, candidate)) {
                        continue;
                    }
                    final double distance = Math.sqrt(
                            Math.pow(candidate.getX() + 0.5 - hitPoint.x, 2)
                                    + Math.pow(candidate.getY() + 1.7 - hitPoint.y, 2)
                                    + Math.pow(candidate.getZ() + 0.5 - hitPoint.z, 2));
                    if (distance > reach) {
                        continue;
                    }
                    final double fromPlayer = player.blockPosition().distSqr(candidate);
                    if (fromPlayer < bestDistance) {
                        bestDistance = fromPlayer;
                        best = candidate;
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
        return (placeMode ? "正在放置到 " : "正在交互 ") + GameUtils.format(target);
    }

    @Override
    public double progress() {
        return chosenFace == null ? 0.0 : 0.5;
    }

    /** 供其它任务复用：寻找附近符合条件的方块（例如床）。 */
    public static BlockPos findNearby(final ClientLevel level, final BlockPos origin,
                                      final String spec, final int radius) {
        for (int r = 1; r <= radius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    for (int dy = -3; dy <= 3; dy++) {
                        final BlockPos pos = origin.offset(dx, dy, dz);
                        if (Math.abs(dx) != r && Math.abs(dz) != r && dy != -3 && dy != 3) {
                            continue;
                        }
                        if (!level.hasChunkAt(pos)) {
                            continue;
                        }
                        if (GameUtils.matchesBlock(level.getBlockState(pos), spec)) {
                            return pos;
                        }
                    }
                }
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "PlaceTask[%s -> %s]", placeMode ? "place" : "use", GameUtils.format(target));
    }
}
