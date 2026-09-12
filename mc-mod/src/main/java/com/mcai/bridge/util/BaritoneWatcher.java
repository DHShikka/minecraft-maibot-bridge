package com.mcai.bridge.util;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Baritone 活动监视：让「任务状态」不再只会说「空闲」。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>我们把 {@code #mine} / {@code #goto} 这类指令交给 Baritone 之后，干活的是它 ——
 * 模组自己的动作队列里什么都没有。于是 {@code task_status} 老老实实报「空闲」，
 * 麦麦就以为没人干活，可能又下发一遍、或者在 Baritone 还在挖的时候改主意。</p>
 *
 * <h2>为什么不用 Baritone 的 API</h2>
 *
 * <p>手上这个 jar 是<b>混淆过的</b>：{@code baritone.api} 只剩
 * {@code IBaritoneProvider}（方法叫 {@code a()}，返回 {@code baritone.d}）
 * 和两个工具类，{@code IBaritone} 那套接口根本不在。按名字反射拿不到任何东西，
 * 而按签名猜混淆名又太脆（换一个 Baritone 版本就废）。</p>
 *
 * <p>所以这里改成<b>看行为</b>：玩家在不在动、有没有在挖、离上一条 {@code #} 指令过去多久。
 * 这个办法对任何版本的 Baritone 都成立，而且零依赖 —— 没装 Baritone 时它只会一直空闲。</p>
 *
 * <h2>它报什么</h2>
 *
 * <pre>
 * {"command": "#mine 8 oak_log", "running": true, "elapsedMs": 42000,
 *  "idleMs": 300, "moving": true, "note": "Baritone 在动"}
 * </pre>
 */
public final class BaritoneWatcher {

    /** 认定为「命令已经跑完/卡住」的空闲时长：这么久没有任何动静就认为停下来了。 */
    private static final long IDLE_MS = 5000L;

    /** 玩家连续静止多久算「没在动」（和上一个 tick 比，容差很小）。 */
    private static final double MOVE_EPSILON_SQR = 1.0E-6;

    private static String lastCommand = "";
    private static long commandAt;
    private static long lastActivityAt;
    private static long lastStatusAt;
    private static Vec3 lastPos;
    private static boolean moving;
    private static boolean sawActivity;
    /** Baritone 自己往聊天里说的话（例如参数报错 "Error at argument #2"）。 */
    private static String lastReply = "";
    private static long lastReplyAt;
    /** 上一条 Baritone 指令之后有没有真的动过（用来区分「还没起步」和「干完了」）。 */
    private static boolean everMoved;

    private BaritoneWatcher() {
    }

    /** 收到一条要发给聊天的内容：以 {@code #} 开头的就是在指挥 Baritone。 */
    public static void onChatSent(final String message) {
        if (message == null || !message.startsWith("#")) {
            return;
        }
        final long now = System.currentTimeMillis();
        lastCommand = message.trim();
        commandAt = now;
        lastActivityAt = now;
        lastStatusAt = now;
        lastPos = null;
        moving = false;
        sawActivity = false;
        everMoved = false;
        lastReply = "";
    }

    /**
     * 模组自己往聊天里看到的系统消息：抓 Baritone 的回话。
     *
     * <p>Baritone 出错时会往聊天里发 {@code [Baritone] Error at argument #2: Expected w} 这种，
     * 这正是麦麦最需要看到的东西 —— 比「已下发」有用得多。</p>
     */
    public static void onChatSeen(final String text) {
        if (text == null || !text.contains("Baritone")) {
            return;
        }
        lastReply = text.trim();
        lastReplyAt = System.currentTimeMillis();
        lastActivityAt = lastReplyAt;   // 有回话也算它活着
    }

    /** 每个客户端 tick 调一次：看玩家有没有动静。 */
    public static void tick(final Minecraft mc) {
        if (lastCommand.isEmpty()) {
            return;
        }
        final LocalPlayer player = mc.player;
        if (player == null) {
            return;
        }
        final long now = System.currentTimeMillis();
        final Vec3 pos = player.position();
        if (lastPos == null || pos.distanceToSqr(lastPos) > MOVE_EPSILON_SQR) {
            lastPos = pos;
            moving = true;
            sawActivity = true;
            everMoved = true;
            lastActivityAt = now;
            return;
        }
        moving = false;
        // 站着不动也可能在干活：正在挖方块 / 挥手臂 / 正在用物品，
        // 这些状态下 Baritone 其实很活跃（挖矿就是这样一格一格啃下来的）。
        final boolean busy = mc.gameMode != null && mc.gameMode.isDestroying()
                || player.swinging
                || player.isUsingItem();
        if (busy) {
            sawActivity = true;
            everMoved = true;
            lastActivityAt = now;
        }
    }

    /** 有没有一条「还在跑」的 Baritone 指令。 */
    public static boolean active() {
        return !lastCommand.isEmpty()
                && System.currentTimeMillis() - lastActivityAt < IDLE_MS;
    }

    /** 当前状态（给状态快照和任务状态用）。没有在指挥 Baritone 时返回 null。 */
    public static JsonObject statusJson() {
        if (lastCommand.isEmpty()) {
            return null;
        }
        final long now = System.currentTimeMillis();
        final long idleMs = now - lastActivityAt;
        final boolean running = idleMs < IDLE_MS;
        final JsonObject o = new JsonObject();
        o.addProperty("command", lastCommand);
        o.addProperty("running", running);
        o.addProperty("elapsedMs", now - commandAt);
        o.addProperty("idleMs", idleMs);
        o.addProperty("moving", running && moving);
        o.addProperty("everMoved", everMoved);
        if (!lastReply.isEmpty()) {
            o.addProperty("reply", lastReply);
            o.addProperty("replyAgoMs", now - lastReplyAt);
        }
        o.addProperty("note", note(running));
        return o;
    }

    private static String note(final boolean running) {
        if (running) {
            return moving ? "Baritone 正在移动" : "Baritone 正在干活（可能正在挖）";
        }
        if (!everMoved) {
            return "下了指令但一直没动静 —— 可能没装 Baritone，或者参数它不认（看 reply 字段）";
        }
        return "已经 " + (IDLE_MS / 1000) + " 秒没有任何动静：大概干完了，或者卡住了"
                + "（挖矿类可以用 mc_inventory 看数量确认）";
    }

    /** 给状态摘要用的一句话。 */
    public static String summary() {
        final JsonObject json = statusJson();
        if (json == null) {
            return "";
        }
        final StringBuilder sb = new StringBuilder();
        sb.append(json.get("command").getAsString());
        sb.append(json.get("running").getAsBoolean() ? "（进行中 " : "（已停 ");
        sb.append(json.get("elapsedMs").getAsLong() / 1000).append(" 秒）");
        if (json.has("reply")) {
            sb.append(" 回话：").append(json.get("reply").getAsString());
        }
        return sb.toString();
    }

    /** 还认不认为 Baritone 在跑（给 stop 用：要顺手把它也停了）。 */
    public static boolean shouldStopBaritone() {
        return active();
    }

    /** 主动标记「已经让它停了」。 */
    public static void markStopped() {
        if (lastCommand.isEmpty()) {
            return;
        }
        lastCommand = "";
        moving = false;
        lastPos = null;
    }
}
