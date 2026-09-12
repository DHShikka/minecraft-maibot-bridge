package com.mcai.bridge.util;

import com.google.gson.JsonObject;
import com.mcai.bridge.BridgeConfig;
import com.mcai.bridge.McAiBridge;
import net.minecraft.client.Minecraft;

/**
 * AI 托管：麦麦接手之后，玩家可以把鼠标放开、切出去干别的，游戏照常跑、AI 照常操作。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>原版单人游戏有两个「人在电脑前」的假设，托管时全是障碍：</p>
 *
 * <ol>
 *   <li><b>窗口失焦就暂停</b>（{@code pauseOnLostFocus}）。暂停时集成的服务端不 tick ——
 *       表现是「AI 一步都走不动、指令执行了但方块不变」。真机排查这个现象花了很久，
 *       游戏日志里的原话是 {@code Saving and pausing game...}。</li>
 *   <li><b>鼠标被锁在窗口里</b>。玩家一碰鼠标就跟 AI 抢视角。</li>
 * </ol>
 *
 * <p>托管做的事就两件：把暂停关掉、把鼠标放开。之后：</p>
 *
 * <ul>
 *   <li>AI 的动作走 {@code InputController}（直接写 {@code KeyMapping} 状态），
 *       和窗口焦点无关，照常生效；</li>
 *   <li>玩家想干预随时可以点回窗口（鼠标会重新被抓住），或者关掉托管。</li>
 * </ul>
 *
 * <h2>什么时候进托管</h2>
 *
 * <p>默认「麦麦一连上就托管」（{@code takeover.autoOnConnect}）：桥接握手成功 = 有人接管了。
 * 断开时自动还回去（鼠标还给玩家、暂停恢复原样），不会把玩家的游戏设置改坏。</p>
 */
public final class Takeover {

    private static boolean active;
    private static long sinceAt;
    private static boolean savedPauseOnLostFocus = true;
    private static boolean savedMouseGrabbed = true;
    /** 上一次是因为什么进来的（自动/手动），写进状态里方便排查。 */
    private static String reason = "";

    private Takeover() {
    }

    public static boolean isActive() {
        return active;
    }

    /** 麦麦接上了（或玩家手动开）。 */
    public static void enter(final Minecraft mc, final String why) {
        if (active || mc == null) {
            return;
        }
        active = true;
        sinceAt = System.currentTimeMillis();
        reason = why;
        try {
            if (mc.options != null) {
                savedPauseOnLostFocus = mc.options.pauseOnLostFocus;
                // 关键的一条：失焦也不暂停，世界才会继续 tick
                mc.options.pauseOnLostFocus = false;
            }
            if (mc.mouseHandler != null) {
                savedMouseGrabbed = mc.mouseHandler.isMouseGrabbed();
                if (BridgeConfig.takeoverReleaseMouse) {
                    // 把鼠标还给玩家：AI 不需要真鼠标，它直接写按键状态
                    mc.mouseHandler.releaseMouse();
                }
            }
            McAiBridge.LOGGER.info("[MaiBot Bridge] 进入 AI 托管（{}）：关掉失焦暂停{}",
                    why, BridgeConfig.takeoverReleaseMouse ? "、鼠标已放开" : "");
        } catch (final Throwable t) {
            McAiBridge.LOGGER.warn("[MaiBot Bridge] 进入托管时出错（忽略）: {}", t.toString());
        }
    }

    /** 麦麦断开了（或玩家手动关）：把游戏还原成「人在玩」的样子。 */
    public static void exit(final Minecraft mc, final String why) {
        if (!active) {
            return;
        }
        active = false;
        reason = why;
        try {
            if (mc != null) {
                if (mc.options != null) {
                    mc.options.pauseOnLostFocus = savedPauseOnLostFocus;
                }
                if (mc.mouseHandler != null && savedMouseGrabbed && mc.screen == null) {
                    mc.mouseHandler.grabMouse();
                }
            }
            McAiBridge.LOGGER.info("[MaiBot Bridge] 退出 AI 托管（{}）：把鼠标和暂停设置还给玩家", why);
        } catch (final Throwable t) {
            McAiBridge.LOGGER.warn("[MaiBot Bridge] 退出托管时出错（忽略）: {}", t.toString());
        }
    }

    /**
     * 每 tick 兜底：托管期间保证「失焦不暂停」和「鼠标放开」始终成立。
     *
     * <p>玩家可能自己把它们改回来：在设置界面里重新打开「失焦暂停」，或者**点回游戏窗口
     * 把鼠标重新抓进去** —— 那 AI 就又会被冻住/和玩家抢鼠标，所以托管期间每 tick 拉回来。</p>
     */
    public static void tick(final Minecraft mc) {
        // 死亡自动重生：**不依赖托管**，任何时候都生效。
        // 为什么必须自己点：托管时鼠标是放开的，没人去按「重生」按钮，死亡界面会一直挂着 ——
        // AI 也就一直卡在死亡界面上什么都干不了（这是真机上「死了以后就不动了」的原因）。
        autoRespawn(mc);
        if (!active || mc == null || mc.options == null) {
            return;
        }
        try {
            if (mc.options.pauseOnLostFocus) {
                mc.options.pauseOnLostFocus = false;
            }
        } catch (final Throwable ignored) {
            // 忽略
        }
        // 鼠标也一样：麦麦连着的时候，鼠标就该待在窗口外（玩家可以随时切走）。
        // 只松不抓：这里不会去 setScreen，所以不会弹出暂停菜单。
        try {
            if (BridgeConfig.takeoverReleaseMouse && mc.mouseHandler != null
                    && mc.mouseHandler.isMouseGrabbed() && mc.screen == null) {
                mc.mouseHandler.releaseMouse();
            }
        } catch (final Throwable ignored) {
            // 忽略
        }
    }

    /** 死亡时刻（用于「死后再等 2 秒」再重生）。 */
    private static long diedAt;

    /**
     * 死了就自己重生，不用点「重生」按钮。
     *
     * <p>要点有两个，少一个都会卡住：</p>
     * <ol>
     *   <li>{@code mc.player.respawn()} —— 这才是真的重生（发指令给服务端）；</li>
     *   <li>把 {@code DeathScreen} 关掉（{@code setScreen(null)})—— 只调 respawn 而不关界面的话，
     *       死亡界面会继续挂在屏幕上，玩家看着「已经重生了但界面还在」，AI 的操作也会被界面挡住。</li>
     * </ol>
     *
     * <p>延迟 2 秒是为了让死亡界面露一下脸（能看清是被什么打死的），而不是一死就闪过去。</p>
     */
    private static void autoRespawn(final Minecraft mc) {
        if (mc == null || mc.player == null) {
            return;
        }
        final boolean dead = mc.player.isDeadOrDying() || mc.player.getHealth() <= 0.0F
                || mc.screen instanceof net.minecraft.client.gui.screens.DeathScreen;
        if (!dead) {
            diedAt = 0L;
            return;
        }
        if (diedAt == 0L) {
            diedAt = System.currentTimeMillis();
            McAiBridge.LOGGER.info("[MaiBot Bridge] 玩家死亡（{}），2 秒后自动重生（不用点）",
                    mc.player.blockPosition().toShortString());
            return;
        }
        if (System.currentTimeMillis() - diedAt > 2000L) {
            diedAt = 0L;
            try {
                mc.player.respawn();
            } catch (final Throwable t) {
                McAiBridge.LOGGER.warn("[MaiBot Bridge] 自动重生失败（忽略）: {}", t.toString());
            }
            if (mc.screen instanceof net.minecraft.client.gui.screens.DeathScreen) {
                mc.setScreen(null);
            }
            McAiBridge.LOGGER.info("[MaiBot Bridge] 已自动重生");
        }
    }

    /** 给状态快照/任务状态用。 */
    public static JsonObject statusJson() {
        final JsonObject o = new JsonObject();
        o.addProperty("active", active);
        if (active) {
            o.addProperty("sinceMs", sinceAt);
            o.addProperty("elapsedMs", System.currentTimeMillis() - sinceAt);
            o.addProperty("reason", reason);
            o.addProperty("mouseReleased", BridgeConfig.takeoverReleaseMouse);
            o.addProperty("pauseDisabled", true);
            o.addProperty("note", "AI 托管中：窗口失焦也不会暂停，鼠标已放开 —— "
                    + "玩家可以切出去，AI 继续操作；点回游戏窗口可以随时接管。");
        }
        return o;
    }
}
