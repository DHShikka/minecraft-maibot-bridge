package com.mcai.bridge.nav;

import net.minecraft.core.BlockPos;

/**
 * 一个 {@link Movement} 在某一 tick 想做的一件事。
 *
 * <p>对应 Baritone 的 {@code MovementState}（<b>重新实现，未拷贝代码</b>）。
 * 它是「移动」与「实际按键」之间的中间层：移动只管把意图写进来
 * （往哪走、要不要跳、要挖哪个方块），由 {@link PathExecutor} 统一翻译成
 * 输入覆盖、视角、挖掘与放置。</p>
 *
 * <p>这样拆的好处是：移动逻辑本身不碰 Minecraft 的输入系统，
 * 可以脱离游戏跑测试。</p>
 */
public final class MovementState {

    public enum Status {
        /** 还没准备好（可能要先挖掉/放上一个方块）。 */
        PREPPING,
        /** 准备好了，等待开始。 */
        WAITING,
        /** 正在执行。 */
        RUNNING,
        /** 这一步完成了。 */
        SUCCESS,
        /** 这一步做不到（例如要挖的方块够不着）。 */
        UNREACHABLE,
        /** 计算时就不该选它（代价无穷）。 */
        FAILED;

        public boolean isComplete() {
            return this == SUCCESS;
        }
    }

    private Status status = Status.PREPPING;

    private boolean forward;
    private boolean backward;
    private boolean strafeLeft;
    private boolean strafeRight;
    private boolean jump;
    private boolean sneak;
    private boolean sprint;

    /** 希望朝向的角度；null 表示不改视角。 */
    private Float yaw;
    private Float pitch;

    /** 这一 tick 要挖的方块（由执行器负责真正动手）。 */
    private BlockPos breakTarget;
    /** 这一 tick 要对哪个方块右键放置 / 交互。 */
    private BlockPos placeTarget;

    public Status getStatus() {
        return status;
    }

    public MovementState setStatus(final Status status) {
        this.status = status;
        return this;
    }

    public boolean isForward() {
        return forward;
    }

    public boolean isBackward() {
        return backward;
    }

    public boolean isStrafeLeft() {
        return strafeLeft;
    }

    public boolean isStrafeRight() {
        return strafeRight;
    }

    public boolean isJump() {
        return jump;
    }

    public boolean isSneak() {
        return sneak;
    }

    public boolean isSprint() {
        return sprint;
    }

    public Float getYaw() {
        return yaw;
    }

    public Float getPitch() {
        return pitch;
    }

    public BlockPos getBreakTarget() {
        return breakTarget;
    }

    public BlockPos getPlaceTarget() {
        return placeTarget;
    }

    /** 只在本 tick 有效的意图，由执行器在每 tick 结束后清空。 */
    public void clearIntent() {
        forward = false;
        backward = false;
        strafeLeft = false;
        strafeRight = false;
        jump = false;
        sneak = false;
        sprint = false;
        yaw = null;
        pitch = null;
        breakTarget = null;
        placeTarget = null;
    }

    /** 一次性写入一组移动输入（例如「向前 + 疾跑」）。 */
    public MovementState move(final boolean fwd, final boolean back, final boolean left,
                              final boolean right, final boolean sprinting, final boolean sneaking) {
        this.forward = fwd;
        this.backward = back;
        this.strafeLeft = left;
        this.strafeRight = right;
        this.sprint = sprinting;
        this.sneak = sneaking;
        return this;
    }

    public MovementState setForward(final boolean value) {
        this.forward = value;
        return this;
    }

    public MovementState setJump(final boolean value) {
        this.jump = value;
        return this;
    }

    public MovementState setSneak(final boolean value) {
        this.sneak = value;
        return this;
    }

    public MovementState setSprint(final boolean value) {
        this.sprint = value;
        return this;
    }

    public MovementState setYaw(final Float value) {
        this.yaw = value;
        return this;
    }

    public MovementState setPitch(final Float value) {
        this.pitch = value;
        return this;
    }

    public MovementState setBreakTarget(final BlockPos pos) {
        this.breakTarget = pos;
        return this;
    }

    public MovementState setPlaceTarget(final BlockPos pos) {
        this.placeTarget = pos;
        return this;
    }

    @Override
    public String toString() {
        return "MovementState[" + status
                + (forward ? " 前" : "") + (backward ? " 后" : "")
                + (strafeLeft ? " 左" : "") + (strafeRight ? " 右" : "")
                + (jump ? " 跳" : "") + (sneak ? " 潜" : "") + (sprint ? " 跑" : "")
                + (yaw != null ? " yaw=" + Math.round(yaw) : "")
                + (breakTarget != null ? " 挖=" + breakTarget.toShortString() : "")
                + (placeTarget != null ? " 放=" + placeTarget.toShortString() : "")
                + "]";
    }
}
