package com.mcai.bridge.action;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;

/**
 * 移动输入控制器。
 *
 * <p>Forge 的 {@code MovementInputUpdateEvent} 在 {@code LocalPlayer.aiStep()} 里被调用，
 * 位置正好在 {@code Input.tick()}（读取真实按键）之后、所有移动与跳跃计算之前
 * （已通过反编译字节码确认：{@code Input.tick} → {@code ForgeHooksClient.onMovementInputUpdate}
 * → 跳跃/移动逻辑）。因此在这里覆盖 {@link Input} 的字段，等价于「替玩家按键」，
 * 而且完全走原版的移动代码，能正确处理水、梯子、台阶、潜行防掉落等细节。</p>
 *
 * <p>当没有任何任务占用控制权时（{@link #release()}），本类不做任何修改，
 * 真人玩家可以正常操作。</p>
 */
public final class InputController {

    private static boolean active;

    private static boolean forward;
    private static boolean backward;
    private static boolean strafeLeft;
    private static boolean strafeRight;
    private static boolean jump;
    private static boolean sneak;
    private static boolean sprint;

    private static float yaw = Float.NaN;
    private static float pitch = Float.NaN;

    private InputController() {
    }

    /** 标记「AI 正在驾驶」，之后的每 tick 都会覆盖输入。 */
    public static void takeControl() {
        active = true;
    }

    /** 交还控制权，并把所有虚拟按键复位。 */
    public static void release() {
        active = false;
        forward = false;
        backward = false;
        strafeLeft = false;
        strafeRight = false;
        jump = false;
        sneak = false;
        sprint = false;
        yaw = Float.NaN;
        pitch = Float.NaN;
    }

    public static boolean isActive() {
        return active;
    }

    /** 重置本 tick 的意图（任务在 step 里重新设置）。 */
    public static void resetIntent() {
        forward = false;
        backward = false;
        strafeLeft = false;
        strafeRight = false;
        jump = false;
        sneak = false;
        sprint = false;
    }

    public static void setForward(final boolean value) {
        forward = value;
    }

    public static void setBackward(final boolean value) {
        backward = value;
    }

    public static void setStrafe(final boolean left) {
        strafeLeft = left;
        strafeRight = !left;
    }

    public static void clearStrafe() {
        strafeLeft = false;
        strafeRight = false;
    }

    public static void setJump(final boolean value) {
        jump = value;
    }

    public static void setSneak(final boolean value) {
        sneak = value;
    }

    public static void setSprint(final boolean value) {
        sprint = value;
    }

    /** 让玩家朝向指定 yaw。 */
    public static void faceYaw(final float value) {
        yaw = value;
    }

    /** 让玩家看向指定 pitch。 */
    public static void facePitch(final float value) {
        pitch = value;
    }

    public static boolean hasYaw() {
        return !Float.isNaN(yaw);
    }

    /**
     * 在每个 tick 的 {@code MovementInputUpdateEvent} 里调用，把「意图」写进原版输入对象。
     */
    public static void apply(final Minecraft mc, final Input input) {
        if (!active || input == null) {
            return;
        }
        final LocalPlayer player = mc.player;
        if (player == null) {
            return;
        }

        input.forwardImpulse = (forward ? 1.0f : 0.0f) + (backward ? -1.0f : 0.0f);
        input.leftImpulse = (strafeLeft ? 1.0f : 0.0f) + (strafeRight ? -1.0f : 0.0f);
        input.jumping = jump;
        input.shiftKeyDown = sneak;
        // 保持与原版 KeyboardInput 一致的布尔字段，避免其它模组读到矛盾的输入状态
        input.up = forward;
        input.down = backward;
        input.left = strafeLeft;
        input.right = strafeRight;

        if (!Float.isNaN(yaw)) {
            player.setYRot(yaw);
            player.setYHeadRot(yaw);
            player.yBodyRot = yaw;
        }
        if (!Float.isNaN(pitch)) {
            player.setXRot(pitch);
        }
        if (sprint && !sneak) {
            player.setSprinting(true);
        } else if (player.isSprinting() && !forward) {
            player.setSprinting(false);
        }
    }
}
