package com.mcai.bridge.nav;

import com.mcai.bridge.action.InputController;
import com.mcai.bridge.util.GameUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;


/**
 * 执行一条由 {@link Movement} 组成的路径。
 *
 * <p>对应 Baritone 的 {@code PathExecutor}（<b>重新实现，未拷贝代码</b>）。</p>
 *
 * <h2>核心：回溯</h2>
 *
 * <p>旧实现用一个通用的「朝路点走」控制器，卡住了靠「连续 N tick 没位移」来发现，
 * 然后原地跳一下、左右晃一下、重算路径 —— 本质上是在<b>猜</b>发生了什么。</p>
 *
 * <p>现在每一步移动自己声明了「执行期间玩家可能处在哪些格子」
 * （{@link Movement#getValidPositions()}）。执行器每 tick 检查一次：
 * 玩家还在合法格子里就继续；跑出去了就<b>退回到上一步重新执行</b>。
 * 回退次数超过阈值才判定这条路走不通，交给上层重新规划。</p>
 *
 * <p>这样「走偏了」变成一件可以精确检测、且知道该退到哪的事，而不是靠启发式补救。</p>
 */
public final class PathExecutor {

    public enum Status {
        /** 还没走完。 */
        RUNNING,
        /** 整条路径走完了。 */
        DONE,
        /** 这条路走不通了（反复回退，或某一步不可达）。 */
        FAILED
    }

    /** 在同一步上连续待多久就认为卡住了（tick）。 */
    private static final int TICKS_PER_MOVEMENT_LIMIT = 20 * 20;
    /** 允许的回退次数上限（相对路径长度）。 */
    private static final int MIN_BACKTRACK_BUDGET = 6;

    private final CalculationContext context;
    private final PathFinder.Path path;
    /** 是否允许疾跑（speedFactor &lt; 1 时用于「悄悄靠近」）。 */
    private final boolean allowSprint;
    private final PlayerEnv env = new PlayerEnv();

    private int index;
    private Movement current;
    private final MovementState state = new MovementState();
    private int ticksOnCurrent;
    private int offPathTicks;
    private int backtrackBudget;
    private String failure;
    private boolean finished;

    // ---- 挖掘状态 ----
    private BlockPos miningPos;
    private boolean mining;

    public PathExecutor(final CalculationContext context, final PathFinder.Path path) {
        this(context, path, true);
    }

    public PathExecutor(final CalculationContext context, final PathFinder.Path path,
                        final boolean allowSprint) {
        this.context = context;
        this.path = path;
        this.allowSprint = allowSprint;
        this.backtrackBudget = Math.max(MIN_BACKTRACK_BUDGET, path.length() / 2);
    }

    public Status tick(final Minecraft mc) {
        if (finished) {
            return Status.DONE;
        }
        final LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.gameMode == null) {
            failure = "玩家或世界不存在";
            return fail();
        }
        if (path.isEmpty()) {
            finished = true;
            return Status.DONE;
        }

        // 每 tick 重新构建「意图」，避免上一 tick 的按键残留
        state.clearIntent();
        env.bind(mc, player);

        if (current == null) {
            if (index >= path.length()) {
                finished = true;
                stopMining(mc);
                return Status.DONE;
            }
            current = path.movements().get(index);
            current.checkLoadedChunk(context);
            state.setStatus(MovementState.Status.PREPPING);
            ticksOnCurrent = 0;
            offPathTicks = 0;
        }

        ticksOnCurrent++;
        if (ticksOnCurrent > TICKS_PER_MOVEMENT_LIMIT) {
            // 一步走了太久：多半是卡住了。回退而不是硬撑
            if (!backtrack(mc, "这一步花费时间过长")) {
                return fail();
            }
            return Status.RUNNING;
        }

        current.updateState(state, env, context);

        // ---- 把意图翻译成真实输入 ----
        InputController.takeControl();
        InputController.resetIntent();
        if (state.isForward()) {
            InputController.setForward(true);
        }
        if (state.isBackward()) {
            InputController.setBackward(true);
        }
        if (state.isStrafeLeft() || state.isStrafeRight()) {
            InputController.setStrafe(state.isStrafeLeft());
        }
        InputController.setJump(state.isJump());
        InputController.setSneak(state.isSneak());
        InputController.setSprint(state.isSprint() && allowSprint);
        if (state.getYaw() != null) {
            InputController.faceYaw(state.getYaw());
        }
        if (state.getPitch() != null) {
            InputController.facePitch(state.getPitch());
        }

        // ---- 挖掘 / 放置 ----
        if (state.getBreakTarget() != null) {
            mineBlock(mc, player, state.getBreakTarget());
        } else {
            stopMining(mc);
        }
        if (state.getPlaceTarget() != null) {
            placeBlock(mc, player, state.getPlaceTarget());
        }

        // ---- 状态判定 ----
        switch (state.getStatus()) {
            case SUCCESS -> {
                stopMining(mc);
                index++;
                current = null;
                if (index >= path.length()) {
                    finished = true;
                    InputController.release();
                    return Status.DONE;
                }
                return Status.RUNNING;
            }
            case UNREACHABLE -> {
                if (!backtrack(mc, "某一步做不到（够不着要挖/要放的方块）")) {
                    return fail();
                }
                return Status.RUNNING;
            }
            case FAILED -> {
                if (!backtrack(mc, "某一步的代价被判定为不可能")) {
                    return fail();
                }
                return Status.RUNNING;
            }
            default -> {
                // 继续检查有没有跑出合法位置
            }
        }

        // ---- 跑出合法位置就回退 ----
        final long feet = player.blockPosition().asLong();
        if (!current.getValidPositions().contains(feet)) {
            offPathTicks++;
            if (offPathTicks > 10) {
                if (!backtrack(mc, "角色偏离了这一步的合法位置")) {
                    return fail();
                }
            }
        } else {
            offPathTicks = 0;
        }
        return Status.RUNNING;
    }

    /** 回退一步。返回 false 表示回退预算耗尽，这条路真的走不通了。 */
    private boolean backtrack(final Minecraft mc, final String why) {
        stopMining(mc);
        if (--backtrackBudget <= 0) {
            failure = why + "，且已经回退 " + Math.max(MIN_BACKTRACK_BUDGET, path.length() / 2)
                    + " 次，这条路走不通";
            return false;
        }
        index = Math.max(0, index - 1);
        current = null;
        offPathTicks = 0;
        if (mc.player != null) {
            state.clearIntent();
        }
        return true;
    }

    private Status fail() {
        finished = true;
        InputController.release();
        return Status.FAILED;
    }

    /** 主动取消（例如任务被顶掉）。 */
    public void cancel(final Minecraft mc) {
        finished = true;
        stopMining(mc);
        InputController.release();
    }

    public void releaseControl() {
        InputController.release();
    }

    public boolean isFinished() {
        return finished;
    }

    public String failureReason() {
        return failure;
    }

    public int currentIndex() {
        return index;
    }

    public int pathLength() {
        return path.length();
    }

    /** 已经走完的部分占总代价的比例，用于进度显示。 */
    public double progress() {
        if (path.length() == 0) {
            return 1.0;
        }
        return Math.min(1.0, (double) index / path.length());
    }

    public Movement currentMovement() {
        return current;
    }

    // ============================================================ 挖掘 / 放置

    /** 看向并持续破坏一个方块。 */
    private void mineBlock(final Minecraft mc, final LocalPlayer player, final BlockPos pos) {
        if (context.get(pos.getX(), pos.getY(), pos.getZ()).passable()) {
            stopMining(mc);
            return;
        }
        // 视角对准方块中心，否则服务端会认为在挖别的东西
        GameUtils.lookAt(player, GameUtils.blockCenter(pos));
        final Direction face = Direction.getNearest(
                player.getEyePosition().x - (pos.getX() + 0.5),
                player.getEyePosition().y - (pos.getY() + 0.5),
                player.getEyePosition().z - (pos.getZ() + 0.5));

        if (!mining || !pos.equals(miningPos)) {
            stopMining(mc);
            miningPos = pos;
            mining = true;
            mc.gameMode.startDestroyBlock(pos, face);
            player.swing(InteractionHand.MAIN_HAND);
            return;
        }
        mc.gameMode.continueDestroyBlock(pos, face);
    }

    private void stopMining(final Minecraft mc) {
        if (mining && mc.gameMode != null) {
            mc.gameMode.stopDestroyBlock();
        }
        mining = false;
        miningPos = null;
    }

    /** 往某个位置放一个方块：找一个相邻的可点击面，然后右键。 */
    private void placeBlock(final Minecraft mc, final LocalPlayer player, final BlockPos target) {
        if (!ensurePlaceableInHand(player)) {
            return; // 手上没有可放置的方块，这一步必然失败，交给上层回退
        }
        final BlockHitResult hit = findPlaceFace(mc, player, target);
        if (hit == null) {
            return;
        }
        GameUtils.lookAt(player, hit.getLocation());
        final InteractionResult result = mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
        if (result.shouldSwing()) {
            player.swing(InteractionHand.MAIN_HAND);
        }
    }

    /** 找一个可以放方块的相邻面。优先往下放（垫脚上就是这么用的）。 */
    private BlockHitResult findPlaceFace(final Minecraft mc, final LocalPlayer player, final BlockPos target) {
        final Direction preferred = Direction.UP; // 点击下方方块的顶面 = 往 target 放
        final Direction[] order = {preferred, Direction.NORTH, Direction.SOUTH,
                Direction.WEST, Direction.EAST, Direction.DOWN};
        for (final Direction from : order) {
            // 从 target 看出去的方向 from，意味着要点击 target.relative(from) 朝向 target 的那一面
            final BlockPos neighbor = target.relative(from);
            if (!mc.level.hasChunkAt(neighbor)) {
                continue;
            }
            final var stateAtNeighbor = mc.level.getBlockState(neighbor);
            if (stateAtNeighbor.isAir()
                    || stateAtNeighbor.getCollisionShape(mc.level, neighbor).isEmpty()) {
                continue;
            }
            final Direction face = from.getOpposite();
            final Vec3 hitPoint = GameUtils.blockCenter(neighbor)
                    .add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
            if (player.getEyePosition().distanceTo(hitPoint) > mc.gameMode.getPickRange() - 0.5) {
                continue;
            }
            return new BlockHitResult(hitPoint, face, neighbor, false);
        }
        return null;
    }

    /** 确保手上拿着一个方块类物品；没有就翻快捷栏，翻不到返回 false。 */
    private boolean ensurePlaceableInHand(final LocalPlayer player) {
        final ItemStack held = player.getMainHandItem();
        if (!held.isEmpty() && held.getItem() instanceof BlockItem) {
            return true;
        }
        final var inv = player.getInventory();
        for (int slot = 0; slot < 9; slot++) {
            final ItemStack stack = inv.getItem(slot);
            if (!stack.isEmpty() && stack.getItem() instanceof BlockItem) {
                inv.selected = slot;
                return true;
            }
        }
        return false;
    }

    // ============================================================ 执行环境

    /** 把真实的客户端玩家包装成 {@link Movement.ExecEnv}。 */
    private static final class PlayerEnv implements Movement.ExecEnv {
        private Minecraft mc;
        private LocalPlayer player;

        void bind(final Minecraft mc, final LocalPlayer player) {
            this.mc = mc;
            this.player = player;
        }

        @Override
        public double x() {
            return player.getX();
        }

        @Override
        public double y() {
            return player.getY();
        }

        @Override
        public double z() {
            return player.getZ();
        }

        @Override
        public double eyeY() {
            return player.getEyeY();
        }

        @Override
        public float yaw() {
            return player.getYRot();
        }

        @Override
        public float pitch() {
            return player.getXRot();
        }

        @Override
        public boolean onGround() {
            return player.onGround();
        }

        @Override
        public boolean inWater() {
            return player.isInWater();
        }

        @Override
        public boolean isLookingAt(final BlockPos pos) {
            final HitResult hit = player.pick(mc.gameMode.getPickRange(), 1.0f, false);
            return hit instanceof BlockHitResult blockHit && blockHit.getBlockPos().equals(pos);
        }

        @Override
        public boolean canReach(final BlockPos pos) {
            final double reach = mc.gameMode.getPickRange() - 0.5;
            return player.getEyePosition().distanceTo(GameUtils.blockCenter(pos)) <= reach;
        }
    }
}
