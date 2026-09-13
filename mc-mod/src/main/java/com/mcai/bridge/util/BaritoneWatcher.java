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
    /** 被 {@code #pause} 暂停了（这时候「没动静」是正常的，不该报成卡住）。 */
    private static boolean paused;
    /** 下这条指令时玩家在哪 —— 用来算「它已经走了多远」（tunnel/explore 唯一的进度指标）。 */
    private static Vec3 startPos;
    /** 离起点最远走过多少格（中途折返也不会把这个数字变小）。 */
    private static double maxTraveled;

    /**
     * 哪些指令是「让 Baritone 干活」的。
     *
     * <p>为什么要区分：{@code #proc}、{@code #eta}、{@code #wp l}、{@code #saveall}
     * 这些是**查询/维护**指令 —— 它们不产生任何移动。以前一律记成「上一条任务」，
     * 于是问一句 {@code #version}，状态里就会显示「Baritone：#version —— 下了指令但一直没动静
     * —— 可能没装 Baritone」这种假警报。</p>
     */
    private static final java.util.Set<String> TASK_COMMANDS = java.util.Set.of(
            "goto", "mine", "explore", "tunnel", "farm", "follow", "thisway", "come",
            "goal", "path", "axis", "invert", "surface", "build", "home", "sel", "find");

    /**
     * {@code #sel} 里真正**干活**的子指令（会动、会放方块）。
     *
     * <p>其余子指令（{@code 1} / {@code 2} / {@code c} / {@code u} / {@code expand}…）
     * 只是改选区，人不会动 —— 把它们记成任务，状态里就会冒出
     * 「Baritone：#sel 1 —— 下了指令但一直没动静」这种假警报。</p>
     */
    private static final java.util.Set<String> SEL_WORK = java.util.Set.of(
            "f", "fill", "w", "walls", "r", "replace", "ca", "cleararea",
            "shl", "shell", "h", "hollow");

    private BaritoneWatcher() {
    }

    /** 收到一条要发给聊天的内容：以 {@code #} 开头的就是在指挥 Baritone。 */
    public static void onChatSent(final String message) {
        if (message == null || !message.startsWith("#")) {
            return;
        }
        final String body = message.substring(1).trim();
        final String[] parts = body.isEmpty() ? new String[0] : body.split("\\s+");
        final String head = parts.length == 0 ? "" : parts[0].toLowerCase(java.util.Locale.ROOT);

        // 不管哪种 # 指令，先把上一条回话清掉 —— 回话是给「刚发出去的这条」用的，
        // 留着旧回话会让「它到底回了什么」张冠李戴。
        lastReply = "";
        lastReplyAt = 0;

        // 控制类：停 / 暂停 / 继续
        if (head.equals("stop") || head.equals("cancel") || head.equals("forcecancel")) {
            markStopped();
            return;
        }
        if (head.equals("pause")) {
            paused = true;
            lastActivityAt = System.currentTimeMillis();   // 别把「暂停」当成卡死
            return;
        }
        if (head.equals("resume")) {
            paused = false;
            lastActivityAt = System.currentTimeMillis();
            return;
        }

        // 路径点：只有 wp goto / wp g 才是「去某地」这个任务，wp l / i / d / s 都是查询或管理。
        if (head.equals("wp")) {
            final String sub = parts.length > 1 ? parts[1].toLowerCase(java.util.Locale.ROOT) : "";
            if (!sub.equals("g") && !sub.equals("goto")) {
                return;
            }
        } else if (head.equals("sel")) {
            // 选区同样分两种：改选区的那些不算任务（见 SEL_WORK 的说明）
            final String sub = parts.length > 1 ? parts[1].toLowerCase(java.util.Locale.ROOT) : "";
            if (!SEL_WORK.contains(sub)) {
                return;
            }
        } else if (!TASK_COMMANDS.contains(head)) {
            // 查询 / 维护类（proc、eta、version、help、paused、saveall、reloadall、gc…）：
            // 它们不产生移动，记成任务只会在状态里造出假进度。
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
        paused = false;
        // 记下起点：tunnel / explore 这类「一直往前」的活，进度就看它走了多远。
        final net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        startPos = mc != null && mc.player != null ? mc.player.position() : null;
        maxTraveled = 0;
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
        if (startPos != null) {
            final double traveled = Math.sqrt(pos.distanceToSqr(startPos));
            if (traveled > maxTraveled) {
                maxTraveled = traveled;
            }
        }
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
        return !paused && !lastCommand.isEmpty()
                && System.currentTimeMillis() - lastActivityAt < IDLE_MS;
    }

    /** 当前状态（给状态快照和任务状态用）。没有在指挥 Baritone 时返回 null。 */
    public static JsonObject statusJson() {
        if (lastCommand.isEmpty()) {
            return null;
        }
        final long now = System.currentTimeMillis();
        final long idleMs = now - lastActivityAt;
        final boolean running = !paused && idleMs < IDLE_MS;
        final JsonObject o = new JsonObject();
        o.addProperty("command", lastCommand);
        o.addProperty("running", running);
        if (paused) {
            o.addProperty("paused", true);
        }
        o.addProperty("elapsedMs", now - commandAt);
        o.addProperty("idleMs", idleMs);
        o.addProperty("moving", running && moving);
        o.addProperty("everMoved", everMoved);
        // 进度：tunnel / explore 这类「一直往前」的活，看它走了多远就知道有没有在推进。
        // 报两个数：现在离起点多远（traveledBlocks）和走得最远到过多少（maxTraveledBlocks）——
        // 中途折返或绕路时，前者会缩、后者不会，AI 用后者判断「到底干了多少」。
        if (startPos != null) {
            final net.minecraft.client.player.LocalPlayer player = Minecraft.getInstance().player;
            if (player != null) {
                o.addProperty("traveledBlocks", round(Math.sqrt(player.position().distanceToSqr(startPos))));
            }
            o.addProperty("maxTraveledBlocks", round(maxTraveled));
        }
        if (!lastReply.isEmpty()) {
            o.addProperty("reply", lastReply);
            o.addProperty("replyAgoMs", now - lastReplyAt);
        }
        o.addProperty("note", note(running));
        return o;
    }

    private static String note(final boolean running) {
        if (paused) {
            return "已经被 #pause 暂停了（要接着干就 resume，要放弃就 stop）";
        }
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
        final boolean pausedNow = json.has("paused") && json.get("paused").getAsBoolean();
        sb.append(pausedNow ? "（已暂停 " : (json.get("running").getAsBoolean() ? "（进行中 " : "（已停 "));
        sb.append(json.get("elapsedMs").getAsLong() / 1000).append(" 秒）");
        if (json.has("maxTraveledBlocks")) {
            sb.append("，已走 ").append(fmt(json.get("maxTraveledBlocks").getAsDouble())).append(" 格");
        }
        if (json.has("reply")) {
            sb.append(" 回话：").append(json.get("reply").getAsString());
        }
        return sb.toString();
    }

    /** 保留一位小数（报距离用）。 */
    private static double round(final double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static String fmt(final double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    /** 还认不认为 Baritone 在跑（给 stop 用：要顺手把它也停了）。 */
    public static boolean shouldStopBaritone() {
        return active();
    }

    /** 主动标记「已经让它停了」。 */
    public static void markStopped() {
        paused = false;
        if (lastCommand.isEmpty()) {
            return;
        }
        lastCommand = "";
        moving = false;
        lastPos = null;
    }
}
