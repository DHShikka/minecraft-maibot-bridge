package com.mcai.bridge.action.tasks;

import com.google.gson.JsonObject;
import com.mcai.bridge.action.Task;
import com.mcai.bridge.action.TaskResult;
import com.mcai.bridge.protocol.Json;
import com.mcai.bridge.util.BaritoneWatcher;
import com.mcai.bridge.util.ModHooks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

import java.util.Locale;

/**
 * 「有 Baritone 就优先用 Baritone」——把寻路 / 挖矿这类活**自动转交给它**。
 *
 * <h2>为什么要有这一层</h2>
 *
 * <p>模组自带那套寻路（{@code nav/} 里 A* + Movement）是 Baritone 思路的重实现：
 * 绕障碍、搭桥、垫脚、挖穿都会，但**没有 Baritone 跑得稳** —— 人家是专门做这个的，
 * 边界情况磨了很多年。以前只有 AI **主动**选 {@code mc_baritone} 才会用它，
 * 而 AI 经常直接调 {@code mc_move_to} / {@code mc_mine_blocks}，于是白白放着 Baritone 不用。</p>
 *
 * <p>现在反过来：装了 Baritone 时，这三个动作默认交给它；想强制用自带那套，
 * 在动作参数里加 {@code "via": "native"} 即可。</p>
 *
 * <h2>为什么用聊天指令而不是它的 API</h2>
 *
 * <p>手上这个 Baritone jar 是混淆过的：{@code baritone.api} 只剩
 * {@code IBaritoneProvider}（方法名是 {@code a()}），{@code IBaritone} 那套接口根本不在。
 * 按名字反射拿不到东西，按签名猜混淆名又太脆。聊天指令这条路对任何版本都成立，而且零依赖 ——
 * 没装 Baritone 时它只是一句普通聊天，不会崩。</p>
 *
 * <h2>怎么知道「干完了」</h2>
 *
 * <p>Baritone 异步干活，不经过我们的动作队列，所以完成判定得自己来：</p>
 * <ul>
 *   <li><b>到位</b>（{@code #goto}）：玩家坐标进到目标附近就算成 —— 这个最可靠，
 *       我们本来就知道目标在哪；</li>
 *   <li><b>挖够</b>（{@code #mine}）：背包里那种方块的数量涨够了就算成；</li>
 *   <li><b>它自己停了</b>：见过它动起来、之后又停了 → 认为这一轮结束（不带「到位 / 挖够」
 *       的成功，结果里会写明是哪种情况，AI 能看出来差多少）。</li>
 * </ul>
 */
public final class BaritoneTasks {

    private BaritoneTasks() {
    }

    /** Baritone 装了没（软依赖：没装时下面每个方法都返回 null，调用方退回自带实现）。 */
    public static boolean available() {
        return ModHooks.isLoaded("baritone") || ModHooks.isLoaded("baritoe");
    }

    /** 这次要不要转交：装了 Baritone + 调用方没要求用自带的（{@code via=native}）。 */
    public static boolean shouldDelegate(final JsonObject params) {
        if (!available()) {
            return false;
        }
        final String via = Json.str(params, "via", "").trim().toLowerCase(Locale.ROOT);
        return !"native".equals(via) && !"self".equals(via) && !"builtin".equals(via);
    }

    /** {@code move_to}：转成 {@code #goto x y z}。参数不全就返回 null（退回自带寻路）。 */
    public static Task gotoTask(final String id, final JsonObject params, final long timeoutMs) {
        if (!shouldDelegate(params)) {
            return null;
        }
        if (!params.has("x") || !params.has("y") || !params.has("z")) {
            return null;
        }
        final int x = Json.intVal(params, "x", 0);
        final int y = Json.intVal(params, "y", 0);
        final int z = Json.intVal(params, "z", 0);
        final double range = Math.max(1, Json.intVal(params, "range", 2));
        final BlockPos target = new BlockPos(x, y, z);
        return new Delegate(id, "move_to", params, timeoutMs,
                "#goto " + x + " " + y + " " + z, target, range, null, 0);
    }

    /** {@code move_relative}：算成绝对坐标再交给 {@code #goto}。 */
    public static Task relativeTask(final String id, final JsonObject params, final long timeoutMs) {
        if (!shouldDelegate(params)) {
            return null;
        }
        final Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null) {
            return null;
        }
        final LocalPlayer player = mc.player;
        final int x = (int) Math.floor(player.getX()) + Json.intVal(params, "dx", 0);
        final int y = (int) Math.floor(player.getY()) + Json.intVal(params, "dy", 0);
        final int z = (int) Math.floor(player.getZ()) + Json.intVal(params, "dz", 0);
        final double range = Math.max(1, Json.intVal(params, "range", 2));
        final BlockPos target = new BlockPos(x, y, z);
        return new Delegate(id, "move_relative", params, timeoutMs,
                "#goto " + x + " " + y + " " + z, target, range, null, 0);
    }

    /**
     * {@code mine_blocks}：转成 {@code #mine <数量> <方块>}。
     *
     * <p><b>数量在前面</b> —— 反过来 Baritone 会报 {@code Error at argument #2: Expected w}。</p>
     */
    public static Task mineTask(final String id, final JsonObject params, final long timeoutMs) {
        if (!shouldDelegate(params)) {
            return null;
        }
        final String block = Json.str(params, "block",
                Json.str(params, "blocks", Json.str(params, "item", ""))).trim();
        if (block.isBlank()) {
            return null;   // 连挖什么都不知道，别去指挥 Baritone
        }
        final int count = Math.max(1, Json.intVal(params, "count", 1));
        return new Delegate(id, "mine_blocks", params, timeoutMs,
                "#mine " + count + " " + block, null, 0, block, count);
    }

    // ------------------------------------------------------------------ 实现

    /** 转交给 Baritone 的动作：发一条指令，然后盯着「到位 / 挖够 / 它自己停了」。 */
    private static final class Delegate extends Task {

        private final String command;
        private final BlockPos target;
        private final double range;
        private final String mineQuery;
        private final int mineCount;

        private boolean sent;
        private boolean sawRunning;
        private int countBefore;
        private int startupTicks;

        Delegate(final String id, final String action, final JsonObject params, final long timeoutMs,
                 final String command, final BlockPos target, final double range,
                 final String mineQuery, final int mineCount) {
            super(id, action, params, timeoutMs, true, false);
            this.command = command;
            this.target = target;
            this.range = range;
            this.mineQuery = mineQuery;
            this.mineCount = mineCount;
        }

        @Override
        protected TaskResult onTick(final Minecraft mc) {
            final LocalPlayer player = mc.player;
            if (player == null || player.connection == null) {
                fail("玩家或连接不存在");
            }

            // ---- 第一步：把指令发出去
            if (!sent) {
                if (mineQuery != null) {
                    countBefore = CraftTask.countItem(player, mineQuery);
                }
                player.connection.sendChat(command);
                // 告诉监视器「我们自己发了一条 Baritone 指令」，任务状态才不会报「空闲」
                BaritoneWatcher.onChatSent(command);
                sent = true;
                startupTicks = 10;   // 给它一点起步时间（Baritone 要几 tick 才会动起来）
                return null;
            }

            // 起步等待：这几 tick 里它可能还没开始跑，别急着判「它停了」
            if (startupTicks > 0) {
                startupTicks--;
                return null;
            }

            // ---- 到位了吗（#goto 的主要判据）
            if (target != null) {
                final double dist = Math.sqrt(player.distanceToSqr(
                        target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5));
                if (dist <= range + 0.6) {
                    final JsonObject out = base();
                    out.addProperty("reached", true);
                    out.addProperty("distance", Math.round(dist * 10) / 10.0);
                    out.addProperty("note", "Baritone 已经把玩家带到目标附近了。");
                    return TaskResult.success(out);
                }
            }

            // ---- 挖够了吗（#mine 的主要判据）
            if (mineQuery != null) {
                final int now = CraftTask.countItem(player, mineQuery);
                if (now - countBefore >= mineCount) {
                    final JsonObject out = base();
                    out.addProperty("mined", now - countBefore);
                    out.addProperty("countBefore", countBefore);
                    out.addProperty("countAfter", now);
                    out.addProperty("note", "Baritone 挖够了（按背包里该方块的数量算）。");
                    return TaskResult.success(out);
                }
            }

            // ---- 它自己停了：见过它动、现在不动了 → 这一轮结束
            final JsonObject status = BaritoneWatcher.statusJson();
            if (status != null && status.has("running") && status.get("running").getAsBoolean()) {
                sawRunning = true;
                return null;
            }
            if (sawRunning) {
                final JsonObject out = base();
                out.addProperty("reached", false);
                out.addProperty("finishedEarly", true);
                if (mineQuery != null) {
                    out.addProperty("mined", CraftTask.countItem(player, mineQuery) - countBefore);
                }
                out.addProperty("note", "Baritone 停下来了，但没到「到位 / 挖够」的程度 —— "
                        + "可能路被挡死、目标够不着，或者它报错了（看 mc_state 里的 baritone.reply）。"
                        + "可以 scan_blocks / mc_state 看看实际情况，再决定换个目标还是用自带寻路"
                        + "（动作参数加 \"via\": \"native\"）。");
                return TaskResult.success(out);
            }
            return null;
        }

        private JsonObject base() {
            final JsonObject out = new JsonObject();
            out.addProperty("via", "baritone");
            out.addProperty("command", command);
            return out;
        }

        @Override
        public String detail() {
            return "交给 Baritone：" + command;
        }
    }
}
