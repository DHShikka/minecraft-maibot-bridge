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
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
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
    /** 够得着的距离（眼睛到方块中心），原版手长 4.5，留点余量。 */
    private static final double REACH = 4.2;
    /** 同一个动作最多重试几次就放弃（独立计数，避免被别的阶段清零）。 */
    private static final int MAX_ATTEMPTS = 6;
    /** 动手之后等几 tick 再判定（客户端有预测，服务端回包也就一两 tick，留点余量）。 */
    private static final int VERIFY_TICKS = 8;

    private enum Stage { FIND, APPROACH, USE, VERIFY, DONE }

    private final String mode;
    private final String fluid;
    private final int radius;
    /** 有没有给倾倒位置。给了就在 onStart 解析（支持 "~" 相对坐标）。 */
    private final boolean hasTarget;
    /** 倾倒目标：液体最后落在这一格。 */
    private BlockPos target;

    private Stage stage = Stage.FIND;
    private int stageTicks;
    private BlockPos fluidPos;      // fill：要找的液体源；empty：要点的那个方块
    private BlockPos pourPos;       // empty：流体最终落在哪
    private BlockPos standSpot;     // 液体旁边能站人的落脚格
    private Navigator navigator;
    private int usedTicks;
    /** 同一个动作试了几次（独立于 usedTicks：后者会被阶段切换清零）。 */
    private int attempts;
    /** 这一轮是否已经先「对准」过了（对准和动手要分成两个 tick）。 */
    private boolean aimed;
    /** 开工前背包里有几桶目标液体（判成功的基准）。 */
    private int filledAtStart;
    /** 开工前背包里有几个空桶（判「液体是不是被装走了」用）。 */
    private int emptyAtStart;
    /** 倒液体时手上那桶实际是什么（"lava" / "water"），动手那一刻记下来。 */
    private String pouredFluid = "";
    private final List<String> notes = new ArrayList<>();

    private BucketTask(final String id, final JsonObject params, final long timeoutMs) {
        super(id, "bucket", params, timeoutMs, true, true);
        this.mode = Json.str(params, "mode", "fill").trim().toLowerCase(java.util.Locale.ROOT);
        this.fluid = Json.str(params, "fluid", "water").trim().toLowerCase(java.util.Locale.ROOT);
        this.radius = Math.max(4, Math.min(Json.intVal(params, "radius", 24), SEARCH_RADIUS_MAX));
        // 坐标不在构造时解析：相对坐标（"~"）要等 onStart 拿到玩家位置才算得出来
        this.hasTarget = BasicTasks.hasBlockPos(params);
    }

    public static Task create(final String id, final JsonObject params, final long timeoutMs) {
        final String mode = Json.str(params, "mode", "fill").trim().toLowerCase(java.util.Locale.ROOT);
        if (!"fill".equals(mode) && !"empty".equals(mode)) {
            return new MoveToTask.FailingTask(id, "bucket", params,
                    "mode 只能是 fill（装液体）或 empty（倒液体），收到「" + mode + "」。");
        }
        if ("empty".equals(mode) && !BasicTasks.hasBlockPos(params)) {
            return new MoveToTask.FailingTask(id, "bucket", params,
                    "mode=empty 需要 x/y/z（液体倒在哪里），支持 \"~\" 相对坐标，"
                            + "例如 {\"x\": \"~\", \"y\": \"~\", \"z\": \"~2\"} 表示倒在面前两格。");
        }
        return new BucketTask(id, params, timeoutMs);
    }

    @Override
    protected void onStart(final Minecraft mc) {
        navigator = new Navigator(mc.level);
        if (hasTarget && mc.player != null) {
            target = BasicTasks.readBlockPos(params, mc.player.blockPosition());
        }
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
            if (target == null) {
                fail("倾倒坐标解析不了（x/y/z 要么是整数，要么是 \"~\" / \"~-1\" 这样的相对坐标）。");
            }
            pourPos = target;
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
            filledAtStart = countFilled(player);
            emptyAtStart = countEmpty(player);
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
        // 记下基准：判断「装到了没有」靠的是背包里液体桶**变多**，不是手里变了样。
        // 手里拿着一叠空桶时，原版会把成品塞到背包别的格子，手上还是空桶。
        filledAtStart = countFilled(player);
        emptyAtStart = countEmpty(player);
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
        // 先定一个「能站人的落脚格」，然后精确走过去。
        //
        // 不能直接拿液体格当目标：那格本身是水/岩浆，站不进去，
        // 「走到它附近 2 格」这种模糊目标会让寻路在它周围打转 ——
        // 真机自检里装水卡了 60 秒、装岩浆甚至跑偏了几百格。
        //
        // 而且「站得住」还不够，还得**看得见**：水/岩浆常常在坑里，
        // 站在三四格外看过去射线会先打在坑沿的地面上（真机自检原话：
        // 「视线探针：打到 (1,-61,-8) 的 up（minecraft:grass_block）」），
        // 这种情况右键多少次都是空的。所以落脚格必须同时满足这两条。
        if (standSpot == null) {
            standSpot = findStandSpot(mc, fluidPos);
            if (standSpot == null) {
                fail("液体 " + GameUtils.format(fluidPos)
                        + " 周围没有**既站得住、又看得见它**的格子。"
                        + "液体在坑里或者被方块挡住时就会这样 —— " + probeText(mc, player)
                        + "。可以先在它旁边挖开一格，或者垫一格站上去再试。");
            }
        }
        final Vec3 hitPoint = GameUtils.blockCenter(fluidPos);
        final double distance = player.getEyePosition().distanceTo(hitPoint);
        if (distance <= REACH) {
            // 站得够近了：先对准，再看一眼射线到底打在哪。
            GameUtils.lookAt(player, hitPoint);
            if (probeHits(mc, player, fluidPos)) {
                navigator.releaseControl();
                aimed = true;   // 已经对准过了，USE 那一步不用再等一个 tick
                moveTo(Stage.USE);
                return null;
            }
        }
        // range 给 1（不是 0）：要求「精确站进那一格」会让寻路执行层在目标周围打转 ——
        // 真机自检里两次都是「朝向对了、往前挪一两格、然后卡满 60 秒」。
        if (navigator.goal() == null || navigator.goal().distSqr(standSpot) > 4.0) {
            navigator.setGoal(standSpot, 1);
        }
        final Navigator.Status status = navigator.step(mc, 1.0);
        if (status == Navigator.Status.ARRIVED) {
            // 「导航器说到地方了」但「我离液体仍然太远」——以前的写法会在
            // 「到达 → 距离不够 → 再设目标 → 又说到地方了」之间空转到超时。
            // 直接说清楚差在哪，比挂 60 秒有用得多。
            navigator.releaseControl();
            if (distance > REACH) {
                fail("走到了落脚点 " + GameUtils.format(standSpot) + "，但离" + fluidName()
                        + " " + GameUtils.format(fluidPos) + " 还有 " + String.format("%.1f", distance)
                        + " 格（够得着要 " + String.format("%.1f", REACH) + " 格以内）。"
                        + "液体可能在坑里或者被方块挡着，先在它旁边垫一格站上去再试。");
            }
            // 距离够了却还是看不见：把射线打到哪儿了说清楚，别让调用方猜
            fail("站到了 " + GameUtils.format(standSpot) + "（离" + fluidName() + " "
                    + String.format("%.1f", distance) + " 格），但视线到不了它 —— "
                    + probeText(mc, player)
                    + "。液体在坑里/被方块挡着时就会这样：先在它旁边挖开一格再试。");
        }
        if (status == Navigator.Status.FAILED) {
            navigator.releaseControl();
            fail("走不到 " + GameUtils.format(standSpot) + "：" + navigator.failureReason()
                    + "。液体周围常常不好站，可以先在岸边垫一格方块。");
        }
        if (stageTicks > 20 * 20) {
            navigator.releaseControl();
            fail("走向 " + GameUtils.format(standSpot) + " 已经 20 秒还在半路（现在在 "
                    + GameUtils.format(player.blockPosition()) + "，路径长度 " + navigator.pathLength()
                    + "，搜过 " + navigator.visitedNodes() + " 个位置）；导航器说法："
                    + navigator.failureReason());
        }
        return null;
    }

    /**
     * 在液体源旁边找一个「站得住 **而且看得见它**」的格子。
     *
     * <p>先看同一层相邻，再看它上方，最后看「相邻格的上方」（水在坑里的典型解就是这一条：
     * 站在坑沿上，从那里斜着看下去正好能看到水面）。</p>
     */
    private BlockPos findStandSpot(final Minecraft mc, final BlockPos fluid) {
        for (final Direction d : Direction.Plane.HORIZONTAL) {
            final BlockPos side = fluid.relative(d);
            if (GameUtils.canStandAt(mc.level, side) && canSeeFrom(mc, side, fluid)) {
                return side;
            }
        }
        final BlockPos above = fluid.above();
        if (GameUtils.canStandAt(mc.level, above) && canSeeFrom(mc, above, fluid)) {
            return above;
        }
        // 站到「液体相邻格的上方」：水在坑里时就是靠这一条
        for (final Direction d : Direction.Plane.HORIZONTAL) {
            final BlockPos side = fluid.relative(d).above();
            if (GameUtils.canStandAt(mc.level, side) && canSeeFrom(mc, side, fluid)) {
                return side;
            }
        }
        return null;
    }

    /** 站在 {@code stand} 这一格上，眼睛能不能真的看到 {@code target} 那一格。 */
    private boolean canSeeFrom(final Minecraft mc, final BlockPos stand, final BlockPos target) {
        final Vec3 eye = new Vec3(stand.getX() + 0.5, stand.getY() + 1.62, stand.getZ() + 0.5);
        final Vec3 aim = GameUtils.blockCenter(target);
        final Vec3 delta = aim.subtract(eye);
        if (delta.length() < 1.0E-4) {
            return true;
        }
        return clipTo(mc, eye, eye.add(delta.normalize().scale(REACH + 0.3)), target);
    }

    /** 当前站姿 + 当前朝向下，射线是不是真的打在 {@code target} 上。 */
    private boolean probeHits(final Minecraft mc, final LocalPlayer player, final BlockPos target) {
        try {
            final Vec3 eye = player.getEyePosition();
            final Vec3 end = eye.add(player.getViewVector(1.0F).scale(REACH + 0.3));
            return clipTo(mc, eye, end, target);
        } catch (final Exception e) {
            return false;
        }
    }

    /** 照原版 {@code BucketItem} 的算法投一次射线，看落点是不是目标方块。 */
    private boolean clipTo(final Minecraft mc, final Vec3 eye, final Vec3 end, final BlockPos target) {
        final ClipContext.Fluid fluidMode = "fill".equals(mode)
                ? ClipContext.Fluid.SOURCE_ONLY : ClipContext.Fluid.NONE;
        final BlockHitResult hit = mc.level.clip(new ClipContext(
                eye, end, ClipContext.Block.OUTLINE, fluidMode, null));
        return hit.getType() == HitResult.Type.BLOCK && target.equals(hit.getBlockPos());
    }

    // ---------------------------------------------------------------- USE

    private TaskResult tickUse(final Minecraft mc, final LocalPlayer player) {
        // 先对准，**隔一 tick** 再动手。
        //
        // 关键时序：lookAt 只改了本地的 yaw/pitch，客户端要等到下一个位置包才把朝向
        // 发给服务端；而 useItem / useItemOn 的服务端处理会**用它那边的玩家朝向做射线检测**。
        // 同一个 tick 里「改朝向 + 立即右键」的话，服务端拿的是旧朝向，射线根本打不到水 ——
        // 表现就是「右键了好几次，桶还是空的」（真机自检里 6 次全空）。
        if (!aimed) {
            aimed = true;
            GameUtils.lookAt(player, GameUtils.blockCenter(fluidPos));
            return null;
        }
        aimed = false;
        attempts++;

        if ("fill".equals(mode)) {
            // 装液体走的是 **useItem（使用物品）**，不是 useItemOn（对方块使用）。
            // 流体方块没有碰撞箱，客户端射线打不到它，所以「对着水右键」在原版里
            // 是「空手使用物品」那条路（ServerboundUseItemPacket）。
            mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
            player.swing(InteractionHand.MAIN_HAND);
            usedTicks = 0;
            moveTo(Stage.VERIFY);
            return null;
        }

        // 倒液体：同样走 **useItem**，不是 useItemOn。
        //
        // 这点很反直觉，但已经在原版字节码里确认过：BucketItem **没有**重写 useOn，
        // 所以「对方块使用」这条路走到 Item.useOn 就返回 PASS，什么都不会发生。
        // 原版右键方块能倒出液体，是因为 Minecraft.startUseItem 在 useItemOn 返回
        // 「没消费」之后**又回退到 useItem**。我们直接调 useItemOn 就丢了这个回退 ——
        // 真机自检里倒水/倒岩浆各试 6 次全空，水桶还原封不动拿在手上。
        //
        // BucketItem.use 对「装好液体的桶」用 Fluid.NONE 投射线（穿液体、只打实心方块），
        // 然后把液体放在命中面的相邻格；所以瞄 target 下面那格的顶面，正好落在 target。
        final Interactive target = resolveClickTarget(mc, player);
        if (target == null) {
            fail("找不到可以点击的面（" + GameUtils.format(fluidPos) + " 周围没有实心方块可点）。");
        }
        // 记住这一刻手上到底是什么桶：判定成功时手已经变成空桶了，
        // 那时候再去读手，只会读到「空桶」然后退回参数名，报出「把水倒在…」这种错话。
        pouredFluid = player.getMainHandItem().is(Items.LAVA_BUCKET) ? "lava" : "water";
        GameUtils.lookAt(player, GameUtils.blockCenter(target.pos).add(0, 0.3, 0));
        mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
        player.swing(InteractionHand.MAIN_HAND);
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
        if (usedTicks < VERIFY_TICKS) {
            return null; // 给服务端几 tick 反应
        }
        final String held = heldId(player);
        if ("fill".equals(mode)) {
            // 判据**不能只看手**。手里拿着一叠空桶时（比如自检里的 "give bucket 3"），
            // 原版 ItemUtils.createFilledResult 会把装好的桶塞进背包**别的格子**，
            // 手上那叠只是少了一个，而且返回的还是同一个 ItemStack 对象 ——
            // MultiPlayerGameMode.useItem 见 `itemstack1 == itemstack` 就完全不碰手持栏。
            //
            // 真机自检里就是这么被骗的：水源确实被装走了、背包里也确实多了一桶水，
            // 但我们只盯手，于是白重试 6 次还报「桶还是空的」。
            // 正确判据 = 手上是装好的桶 **或** 背包里目标液体桶的**总数变多了**。
            final int filledNow = countFilled(player);
            if (isFilledBucket(player.getMainHandItem()) || filledNow > filledAtStart) {
                final JsonObject out = new JsonObject();
                out.addProperty("mode", "fill");
                out.addProperty("fluid", fluid);
                out.addProperty("got", held);
                out.addProperty("buckets", filledNow);
                out.add("source", StateCollector.blockPos(fluidPos));
                out.addProperty("content", "装到了" + fluidName() + "（手上 " + handText(player)
                        + "，背包里一共 " + filledNow + " 桶）");
                return TaskResult.success(out);
            }
            // 重试用**独立的计数器**：以前这里回到 USE，而 USE 会把 usedTicks 清零，
            // 于是「重试 20 次就放弃」永远触发不了，只能干等任务超时（真机自检里卡满 60 秒）。
            if (attempts >= MAX_ATTEMPTS) {
                final int emptyNow = countEmpty(player);
                fail("对着" + fluidName() + " " + GameUtils.format(fluidPos) + " 试了 " + attempts
                        + " 次，还是没装到。现在离它 "
                        + String.format("%.1f", player.getEyePosition()
                                .distanceTo(GameUtils.blockCenter(fluidPos)))
                        + " 格（够得着要 " + String.format("%.1f", REACH) + " 格以内）；手上 "
                        + handText(player) + "，空桶 " + emptyNow + " 个（开工时 " + emptyAtStart
                        + " 个），" + fluidName() + "桶 " + filledNow + " 个（开工时 " + filledAtStart
                        + " 个）；" + probeText(mc, player) + "。"
                        + (emptyNow < emptyAtStart
                                ? "空桶少了 " + (emptyAtStart - emptyNow) + " 个 —— 液体其实装走了，"
                                        + "多半进了背包别的格子（背包满了就直接掉在地上），去背包里找找。"
                                : ""));
            }
            moveTo(Stage.USE);
            return null;
        }

        // 倒液体：手上应该从「装满的桶」变回空桶，同时目标格出现液体
        final BlockState now = mc.level.getBlockState(pourPos);
        final FluidState fluidNow = mc.level.getFluidState(pourPos);
        if (!fluidNow.isEmpty() || !now.isAir()) {
            final JsonObject out = new JsonObject();
            out.addProperty("mode", "empty");
            out.addProperty("fluid", fluid);
            out.add("pouredAt", StateCollector.blockPos(pourPos));
            out.addProperty("blockNow", fluidNow.isEmpty() ? GameUtils.blockId(now) : "fluid");
            out.addProperty("held", handText(player));
            out.addProperty("buckets", countFilledAny(player));
            out.addProperty("content", "把" + pouredFluidName(player) + "倒在了 "
                    + GameUtils.format(pourPos) + "（手上 " + handText(player)
                    + "，背包里还剩 " + countFilledAny(player) + " 桶液体）");
            return TaskResult.success(out);
        }
        if (attempts >= MAX_ATTEMPTS) {
            fail("对着 " + GameUtils.format(fluidPos) + " 试了 " + attempts + " 次，但 "
                    + GameUtils.format(pourPos) + " 还是空的。手上 " + handText(player)
                    + "，背包里装好液体的桶 " + countFilledAny(player) + " 个；"
                    + probeText(mc, player) + "。倒液体要求手上拿着装好液体的桶，"
                    + "并且目标格下面是实心方块。");
        }
        moveTo(Stage.USE);
        return null;
    }

    // ------------------------------------------------------------ 诊断

    /**
     * 纯诊断：照原版 {@code BucketItem.use} 的算法自己投一次射线，看实际会打到哪一格。
     *
     * <p>装液体要求打到「源方块」（Fluid.SOURCE_ONLY），倒液体要求打到目标下面那格的顶面
     * （Fluid.NONE，穿液体只打实心方块）。失败时把它写进消息里，比「试了 6 次还是空的」
     * 有用得多 —— 一眼就能看出是瞄歪了、被挡了，还是液体根本不在射线上。</p>
     */
    private String probeText(final Minecraft mc, final LocalPlayer player) {
        try {
            final Vec3 eye = player.getEyePosition();
            final Vec3 end = eye.add(player.getViewVector(1.0F).scale(REACH + 0.3));
            final ClipContext.Fluid fluidMode = "fill".equals(mode)
                    ? ClipContext.Fluid.SOURCE_ONLY : ClipContext.Fluid.NONE;
            final BlockHitResult hit = mc.level.clip(new ClipContext(
                    eye, end, ClipContext.Block.OUTLINE, fluidMode, player));
            if (hit.getType() == HitResult.Type.MISS) {
                return "视线探针：这条线上什么都没打到（瞄歪了或者被挡了）";
            }
            return "视线探针：打到 " + GameUtils.format(hit.getBlockPos()) + " 的 " + hit.getDirection()
                    + "（" + GameUtils.blockId(mc.level.getBlockState(hit.getBlockPos())) + "）";
        } catch (final Exception e) {
            return "视线探针出错：" + e;
        }
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

    /** 手上那叠东西的「id ×数量」，诊断用。 */
    private static String handText(final LocalPlayer player) {
        final ItemStack held = player.getMainHandItem();
        return held.isEmpty() ? "空手" : GameUtils.itemId(held) + " ×" + held.getCount();
    }

    /** 倒液体时手上那桶到底是什么（动手那一刻记的 pouredFluid，比参数名靠谱）。 */
    private String pouredFluidName(final LocalPlayer player) {
        if ("lava".equals(pouredFluid)) {
            return "岩浆";
        }
        if ("water".equals(pouredFluid)) {
            return "水";
        }
        final ItemStack held = player.getMainHandItem();
        if (held.is(Items.LAVA_BUCKET)) {
            return "岩浆";
        }
        if (held.is(Items.WATER_BUCKET)) {
            return "水";
        }
        return fluidName();
    }

    /** 背包里目标液体的桶一共有几桶（装液体的成功判据）。 */
    private int countFilled(final LocalPlayer player) {
        final net.minecraft.world.item.Item want =
                "lava".equals(fluid) ? Items.LAVA_BUCKET : Items.WATER_BUCKET;
        return countOf(player, want);
    }

    /** 背包里空桶有几个。 */
    private static int countEmpty(final LocalPlayer player) {
        return countOf(player, Items.BUCKET);
    }

    /** 背包里装好液体的桶一共有几个（不管水还是岩浆）。 */
    private static int countFilledAny(final LocalPlayer player) {
        return countOf(player, Items.WATER_BUCKET) + countOf(player, Items.LAVA_BUCKET);
    }

    private static int countOf(final LocalPlayer player, final net.minecraft.world.item.Item item) {
        final Inventory inv = player.getInventory();
        int total = 0;
        for (int slot = 0; slot < inv.getContainerSize(); slot++) {
            final ItemStack stack = inv.getItem(slot);
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
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

    /** 找一桶装好的液体：优先找 fluid 对应的那种，找不到就手上/背包里有哪种算哪种。 */
    private int findFilledBucket(final LocalPlayer player) {
        final net.minecraft.world.item.Item want =
                "lava".equals(fluid) ? Items.LAVA_BUCKET : Items.WATER_BUCKET;
        final Inventory inv = player.getInventory();
        for (int slot = 0; slot < inv.getContainerSize(); slot++) {
            if (inv.getItem(slot).is(want)) {
                return slot;
            }
        }
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
