package com.mcai.bridge.action.tasks;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mcai.bridge.BridgeConfig;
import com.mcai.bridge.action.ActionExecutor;
import com.mcai.bridge.action.InputController;
import com.mcai.bridge.action.Task;
import com.mcai.bridge.action.TaskResult;
import com.mcai.bridge.protocol.Json;
import com.mcai.bridge.snapshot.StateCollector;
import com.mcai.bridge.util.GameUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

/**
 * 通讯、观察与轻量控制类动作。
 *
 * <p>包含：{@code chat} / {@code command} / {@code look} / {@code get_state} /
 * {@code task_status} / {@code jump} / {@code sneak} / {@code sprint} /
 * {@code wait} / {@code stop} / {@code cancel}。</p>
 */
public final class BasicTasks {

    private BasicTasks() {
    }

    // ------------------------------------------------------------ 游戏内发言

    public static Task chat(final String id, final JsonObject params, final boolean instant) {
        return new Task(id, "chat", params, 5_000L, !instant, false) {
            @Override
            protected TaskResult onTick(final Minecraft mc) {
                final String raw = str(params, "message", str(params, "text", ""));
                if (raw.isBlank()) {
                    fail("缺少参数 message（要发送的聊天内容）");
                }
                final LocalPlayer player = mc.player;
                final ClientPacketListener connection = player == null ? null : player.connection;
                if (connection == null) {
                    fail("尚未连接到服务器，无法发言");
                }
                // 聊天框是单行的：换行会变成非法报文，统一替换成空格
                final String text = raw.replace('\n', ' ').replace('\r', ' ').trim();
                if (text.length() > 256) {
                    fail("聊天内容过长（" + text.length() + " 字符，上限 256）");
                }
                connection.sendChat(text);
                // 「#」开头的消息是发给 Baritone 的：记下来，好让任务状态别再报「空闲」。
                // Baritone 不通过我们的动作队列干活，光看队列永远以为没人在做事。
                com.mcai.bridge.util.BaritoneWatcher.onChatSent(text);
                final JsonObject result = new JsonObject();
                result.addProperty("sent", true);
                result.addProperty("message", text);
                if (text.startsWith("#")) {
                    result.addProperty("baritone", true);
                    result.addProperty("note", "这是给 Baritone 的指令。它异步执行，"
                            + "进度看 task_status 里的 baritone 字段（或 mc_state）。");
                }
                return TaskResult.success(result);
            }
        };
    }

    // -------------------------------------------------------------- 执行指令

    public static Task command(final String id, final JsonObject params, final boolean instant) {
        return new Task(id, "command", params, 5_000L, !instant, false) {
            @Override
            protected TaskResult onTick(final Minecraft mc) {
                final String raw = str(params, "command", str(params, "cmd", "")).trim();
                if (raw.isBlank()) {
                    fail("缺少参数 command（要执行的指令，例如 time set day）");
                }
                final String normalized = raw.startsWith("/") ? raw.substring(1) : raw;
                if (!BridgeConfig.isCommandAllowed(normalized)) {
                    fail("该指令被模组配置禁止执行：" + normalized.split(" ")[0]
                            + "（可在 config/mcai_bridge-client.toml 的 commandBlacklist 中调整）");
                }
                final LocalPlayer player = mc.player;
                final ClientPacketListener connection = player == null ? null : player.connection;
                if (connection == null) {
                    fail("尚未连接到服务器，无法执行指令");
                }
                connection.sendCommand(normalized);
                final JsonObject result = new JsonObject();
                result.addProperty("sent", true);
                result.addProperty("command", normalized);
                result.addProperty("note", "指令已发送。注意：并非所有指令都有权限，游戏内聊天栏会显示执行结果。");
                return TaskResult.success(result);
            }
        };
    }

    // ---------------------------------------------------------------- 看状态

    public static Task getState(final String id, final JsonObject params, final boolean instant) {
        return new Task(id, "get_state", params, 5_000L, !instant, false) {
            @Override
            protected TaskResult onTick(final Minecraft mc) {
                final JsonObject state = StateCollector.collect(mc, "request");
                final JsonObject task = ActionExecutor.get().statusJson();
                state.add("task", task.get("current"));
                state.addProperty("queueLength", Json.intVal(task, "queueLength", 0));
                return TaskResult.success(state);
            }
        };
    }

    public static Task taskStatus(final String id, final JsonObject params, final boolean instant) {
        return new Task(id, "task_status", params, 5_000L, !instant, false) {
            @Override
            protected TaskResult onTick(final Minecraft mc) {
                return TaskResult.success(ActionExecutor.get().statusJson());
            }
        };
    }

    // ---------------------------------------------------------------- 关界面

    /**
     * 关掉当前打开的界面（{@code close_screen}）。
     *
     * <p>为什么需要它：右键箱子 / 工作台之后界面会一直开着，而「界面开着」这件事
     * 以前在状态里看不出来，AI 也就没有「把它关掉」的概念 —— 于是会出现
     * 「点完箱子接着想走路 / 想吃东西」这种被界面挡住的后续动作。</p>
     *
     * <p>容器界面走 {@code player.closeContainer()}（会给服务端发关闭包，物品栏正常同步），
     * 其它界面（设置、成就这类）直接 {@code setScreen(null)}。</p>
     */
    public static Task closeScreen(final String id, final JsonObject params, final boolean instant) {
        return new Task(id, "close_screen", params, 5_000L, false, false) {
            @Override
            protected TaskResult onTick(final Minecraft mc) {
                final net.minecraft.client.gui.screens.Screen screen = mc.screen;
                final JsonObject result = new JsonObject();
                if (screen == null) {
                    result.addProperty("closed", false);
                    result.addProperty("note", "本来就没有界面开着。");
                    return TaskResult.success(result);
                }
                result.addProperty("closed", true);
                result.addProperty("was", screen.getClass().getSimpleName());
                try {
                    result.addProperty("title", screen.getTitle().getString());
                } catch (final Throwable ignored) {
                    // 少数界面没有标题
                }
                final LocalPlayer player = mc.player;
                if (player != null && screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> cs) {
                    // 容器界面必须走这条路：还要把「关闭容器」告诉服务端
                    final int menuId = cs.getMenu().containerId;
                    player.closeContainer();
                    // 真机踩过的坑：这个调用发的是**客户端当前 containerMenu** 的菜单号。
                    // 万一客户端那边的菜单号已经不在这个界面上（实测出现过），服务端就会一直
                    // 挂着这个菜单不关 —— 而菜单槽位为 0 时服务端不会广播玩家背包，表现为
                    // 「背包里的东西变了但状态里看不见」。所以再按界面自己的菜单号补发一次；
                    // 服务端对不上号的关闭包会直接忽略，补发没有副作用。
                    if (player.connection != null && player.containerMenu != null
                            && player.containerMenu.containerId != menuId) {
                        player.connection.send(
                                new net.minecraft.network.protocol.game.ServerboundContainerClosePacket(menuId));
                    }
                }
                if (mc.screen != null) {
                    mc.setScreen(null);
                }
                if (mc.mouseHandler != null) {
                    mc.mouseHandler.grabMouse();
                }
                return TaskResult.success(result);
            }
        };
    }

    // ------------------------------------------------------------------ 视角

    public static Task look(final String id, final JsonObject params, final boolean instant) {
        return new Task(id, "look", params, 5_000L, !instant, false) {
            @Override
            protected TaskResult onTick(final Minecraft mc) {
                final LocalPlayer player = mc.player;
                if (player == null) {
                    fail("玩家不存在");
                }

                float targetYaw;
                float targetPitch;
                String what;

                if (Json.has(params, "x") && Json.has(params, "y") && Json.has(params, "z")) {
                    final Vec3 target = new Vec3(Json.num(params, "x", 0), Json.num(params, "y", 0), Json.num(params, "z", 0));
                    targetYaw = GameUtils.yawTo(player.getEyePosition(), target);
                    targetPitch = GameUtils.pitchTo(player.getEyePosition(), target);
                    what = "坐标 " + GameUtils.format(target);
                } else if (Json.has(params, "entity") || Json.has(params, "uuid")) {
                    final String key = Json.str(params, "entity", Json.str(params, "uuid", ""));
                    final Entity entity = TargetResolver.findEntity(mc, key);
                    if (entity == null) {
                        fail("找不到实体：" + key);
                    }
                    final Vec3 aim = GameUtils.centerOf(entity);
                    targetYaw = GameUtils.yawTo(player.getEyePosition(), aim);
                    targetPitch = GameUtils.pitchTo(player.getEyePosition(), aim);
                    what = "实体 " + GameUtils.entityName(entity);
                } else if (Json.has(params, "yaw") || Json.has(params, "pitch")) {
                    targetYaw = (float) Json.num(params, "yaw", player.getYRot());
                    targetPitch = (float) Json.num(params, "pitch", player.getXRot());
                    what = "固定角度";
                } else {
                    fail("缺少参数：请提供 x/y/z、entity 或 yaw/pitch 之一");
                    return null;
                }

                targetPitch = net.minecraft.util.Mth.clamp(targetPitch, -90.0f, 90.0f);
                player.setYRot(targetYaw);
                player.setYHeadRot(targetYaw);
                player.yBodyRot = targetYaw;
                player.setXRot(targetPitch);

                final JsonObject result = new JsonObject();
                result.addProperty("yaw", Math.round(targetYaw * 100.0) / 100.0);
                result.addProperty("pitch", Math.round(targetPitch * 100.0) / 100.0);
                result.addProperty("lookingAt", what);
                return TaskResult.success(result);
            }
        };
    }

    // ------------------------------------------------------- 短时移动控制

    /** 按住跳跃键若干 tick（默认 2）。 */
    public static Task jump(final String id, final JsonObject params, final long timeoutMs) {
        final int count = Math.max(1, Json.intVal(params, "count", 1));
        return new Task(id, "jump", params, Math.min(timeoutMs, 10_000L), true, true) {
            private int done;

            @Override
            protected TaskResult onTick(final Minecraft mc) {
                final LocalPlayer player = mc.player;
                if (player == null) {
                    fail("玩家不存在");
                }
                InputController.takeControl();
                InputController.resetIntent();
                if (done < count) {
                    InputController.setJump(true);
                    done++;
                    return null;
                }
                InputController.release();
                final JsonObject result = new JsonObject();
                result.addProperty("jumped", count);
                result.addProperty("onGround", player.onGround());
                return TaskResult.success(result);
            }

            @Override
            protected void onCancel(final Minecraft mc) {
                InputController.release();
            }
        };
    }

    /** 潜行开关。参数 state=true/false；不给 state 时按 durationMs 潜行一段时间。 */
    public static Task sneak(final String id, final JsonObject params, final long timeoutMs) {
        final boolean state = Json.bool(params, "state", true);
        return new Task(id, "sneak", params, Math.min(timeoutMs, 30_000L), true, true) {
            private int ticks;

            @Override
            protected void onStart(final Minecraft mc) {
                InputController.takeControl();
            }

            @Override
            protected TaskResult onTick(final Minecraft mc) {
                final LocalPlayer player = mc.player;
                if (player == null) {
                    fail("玩家不存在");
                }
                InputController.resetIntent();
                InputController.setSneak(state);
                ticks++;
                // 潜行是持续状态，等到时长到或者玩家已经是想要的状态后再多保持 2 tick
                final long holdTicks = Math.max(2L, timeoutMs / 50L);
                if (ticks >= holdTicks) {
                    InputController.release();
                    if (!state) {
                        player.setShiftKeyDown(false);
                    }
                    final JsonObject result = new JsonObject();
                    result.addProperty("sneaking", player.isShiftKeyDown());
                    return TaskResult.success(result);
                }
                return null;
            }

            @Override
            protected void onCancel(final Minecraft mc) {
                InputController.release();
            }
        };
    }

    /** 疾跑开关。 */
    public static Task sprint(final String id, final JsonObject params, final long timeoutMs) {
        final boolean state = Json.bool(params, "state", true);
        return new Task(id, "sprint", params, Math.min(timeoutMs, 30_000L), true, true) {
            private int ticks;

            @Override
            protected TaskResult onTick(final Minecraft mc) {
                final LocalPlayer player = mc.player;
                if (player == null) {
                    fail("玩家不存在");
                }
                InputController.takeControl();
                InputController.resetIntent();
                InputController.setSprint(state);
                ticks++;
                final long holdTicks = Math.max(2L, Math.min(timeoutMs, 4000L) / 50L);
                if (ticks >= holdTicks) {
                    InputController.release();
                    player.setSprinting(state);
                    final JsonObject result = new JsonObject();
                    result.addProperty("sprinting", player.isSprinting());
                    return TaskResult.success(result);
                }
                return null;
            }

            @Override
            protected void onCancel(final Minecraft mc) {
                InputController.release();
            }
        };
    }

    /** 原地等待指定毫秒。 */
    public static Task wait(final String id, final JsonObject params, final long timeoutMs) {
        final long ms = Math.max(0L, Json.longVal(params, "ms", Json.longVal(params, "durationMs", 1000L)));
        return new Task(id, "wait", params, Math.min(timeoutMs, ms + 5_000L), true, false) {
            private final long start = System.currentTimeMillis();

            @Override
            protected TaskResult onTick(final Minecraft mc) {
                final long elapsed = System.currentTimeMillis() - start;
                if (elapsed >= ms) {
                    final JsonObject result = new JsonObject();
                    result.addProperty("waitedMs", elapsed);
                    return TaskResult.success(result);
                }
                return null;
            }

            @Override
            public double progress() {
                return ms <= 0 ? 1.0 : Math.min(1.0, (double) (System.currentTimeMillis() - start) / ms);
            }
        };
    }

    // ------------------------------------------------------------ 停止/取消

    /** 停止一切正在执行的动作（清空队列）。 */
    public static Task stop(final String id, final JsonObject params, final boolean instant) {
        return new Task(id, "stop", params, 5_000L, !instant, false) {
            @Override
            protected TaskResult onTick(final Minecraft mc) {
                ActionExecutor.get().cancelAll("动作已被 stop 指令终止");
                InputController.release();
                final JsonObject result = new JsonObject();
                result.addProperty("stopped", true);
                // 「紧急刹车」得把 Baritone 也刹住：它的活不在我们的队列里，
                // 只清自己的队列会出现「麦麦说停了，人还在满地图挖」。
                if (com.mcai.bridge.util.BaritoneWatcher.shouldStopBaritone()) {
                    final LocalPlayer player = mc.player;
                    if (player != null && player.connection != null) {
                        player.connection.sendChat("#stop");
                        result.addProperty("baritoneStopped", true);
                        result.addProperty("note", "顺手给 Baritone 也发了 #stop。");
                    }
                    com.mcai.bridge.util.BaritoneWatcher.markStopped();
                }
                return TaskResult.success(result);
            }
        };
    }

    /** 取消指定 id 的动作；不给 id 时等同于 stop。 */
    public static Task cancel(final String id, final JsonObject params, final boolean instant) {
        return new Task(id, "cancel", params, 5_000L, !instant, false) {
            @Override
            protected TaskResult onTick(final Minecraft mc) {
                final String target = str(params, "targetId", str(params, "target", ""));
                final JsonObject result = new JsonObject();
                if (target.isBlank()) {
                    ActionExecutor.get().cancelAll("动作已全部取消");
                    result.addProperty("cancelledAll", true);
                } else {
                    result.addProperty("cancelled", ActionExecutor.get().cancel(target));
                    result.addProperty("targetId", target);
                }
                return TaskResult.success(result);
            }
        };
    }

    // ------------------------------------------------------------------ 辅助

    /** 让实体名解析逻辑集中在一处，{@code look} 与 {@code attack} 共用。 */
    public static final class TargetResolver {

        private TargetResolver() {
        }

        /**
         * 按 uuid、玩家名、实体类型名或 {@code nearest} 查找实体。
         *
         * @return 找不到时返回 {@code null}
         */
        public static Entity findEntity(final Minecraft mc, final String key) {
            if (mc.level == null || mc.player == null || key == null || key.isBlank()) {
                return null;
            }
            final String needle = key.trim();
            final LocalPlayer self = mc.player;

            if ("nearest".equalsIgnoreCase(needle) || "最近的".equals(needle)) {
                return findNearest(mc, null, false);
            }
            if ("nearest_hostile".equalsIgnoreCase(needle) || "最近的怪物".equals(needle)) {
                return findNearest(mc, null, true);
            }

            final java.util.List<Entity> candidates = new java.util.ArrayList<>();
            for (final Entity entity : mc.level.entitiesForRendering()) {
                if (entity == self) {
                    continue;
                }
                if (entity.getUUID().toString().equalsIgnoreCase(needle)) {
                    return entity;
                }
                candidates.add(entity);
            }

            final String lower = needle.toLowerCase(Locale.ROOT);
            Entity best = null;
            double bestDistance = Double.MAX_VALUE;
            for (final Entity entity : candidates) {
                final String name = GameUtils.entityName(entity).toLowerCase(Locale.ROOT);
                final String typeId = GameUtils.entityTypeId(entity).toLowerCase(Locale.ROOT);
                final String shortType = GameUtils.shortId(typeId);
                final boolean match = name.equals(lower) || typeId.equals(lower) || shortType.equals(lower)
                        || name.contains(lower) || shortType.contains(lower);
                if (!match) {
                    continue;
                }
                final double distance = self.distanceTo(entity);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = entity;
                }
            }
            return best;
        }

        /** 找最近的实体；{@code hostileOnly} 为真时只考虑敌对生物。 */
        public static Entity findNearest(final Minecraft mc, final Class<? extends Entity> filter, final boolean hostileOnly) {
            if (mc.level == null || mc.player == null) {
                return null;
            }
            final LocalPlayer self = mc.player;
            final net.minecraft.world.phys.AABB area =
                    self.getBoundingBox().inflate(StateCollector.ENTITY_RADIUS);
            final java.util.List<net.minecraft.world.entity.LivingEntity> list =
                    mc.level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, area);
            net.minecraft.world.entity.LivingEntity best = null;
            double bestDistance = Double.MAX_VALUE;
            for (final net.minecraft.world.entity.LivingEntity entity : list) {
                if (entity == self || !entity.isAlive()) {
                    continue;
                }
                if (filter != null && !filter.isInstance(entity)) {
                    continue;
                }
                if (hostileOnly && !"HOSTILE".equals(GameUtils.entityCategory(entity))) {
                    continue;
                }
                final double distance = self.distanceTo(entity);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = entity;
                }
            }
            return best;
        }
    }

    /** 生成「我看到了什么」的聊天反馈，方便调试。 */
    public static void echoToChat(final Minecraft mc, final String text) {
        if (mc.gui != null && mc.gui.getChat() != null) {
            mc.gui.getChat().addMessage(Component.literal("[MaiBot] " + text));
        }
    }

    /** 方块坐标解析：优先读 x/y/z，其次读 pos 对象。 */
    public static BlockPos readBlockPos(final JsonObject params) {
        return readBlockPos(params, null);
    }

    /**
     * 反查「玩家背包第 N 格」在某个容器菜单里是第几个槽。
     *
     * <p>不靠布局公式猜：原版 {@code addStandardInventorySlots} 把主背包排在快捷栏**前面**，
     * 和玩家背包下标顺序并不一致；猜错就会点到别的物品。遍历菜单按
     * {@code container + containerSlot} 反查最稳。</p>
     *
     * @return 菜单里的槽位下标；找不到返回 -1
     */
    public static int findMenuSlot(final net.minecraft.world.inventory.AbstractContainerMenu menu,
                                   final net.minecraft.client.player.LocalPlayer player,
                                   final int inventorySlot) {
        for (int i = 0; i < menu.slots.size(); i++) {
            final net.minecraft.world.inventory.Slot slot = menu.slots.get(i);
            if (slot.container instanceof net.minecraft.world.entity.player.Inventory
                    && slot.getContainerSlot() == inventorySlot) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 方块坐标解析，支持 Minecraft 风格的相对坐标。
     *
     * <pre>
     * {"x": 10, "y": 64, "z": -3}        绝对坐标
     * {"x": "~", "y": "~-1", "z": "~"}   相对坐标：脚下那一格，往下一格
     * </pre>
     *
     * <p><b>为什么需要相对坐标</b>：任务组脚本是一段提前写好的流程，
     * 写的时候还不知道玩家会站在哪，只有 {@code ~} 能表达「放在我脚下」。
     * Minecraft 指令本来就是这么写的，模型也天然会用。</p>
     *
     * @param origin 相对坐标的基准（一般是玩家脚下的方块）；为 null 时相对坐标解析不了，
     *               返回 null 让调用方报一句人话
     * @return 解析结果；缺少 x、或相对坐标没有基准时返回 null
     */
    public static BlockPos readBlockPos(final JsonObject params, final BlockPos origin) {
        JsonObject source = params;
        if (!Json.has(params, "x") && Json.has(params, "pos")) {
            final JsonObject pos = com.mcai.bridge.protocol.Json.objOrNull(params, "pos");
            if (pos != null) {
                source = pos;
            }
        }
        if (!Json.has(source, "x")) {
            return null;
        }
        // 缺分量时仍按老规矩当 0（绝对坐标语义），只有 "~" 才需要基准
        final Integer x = readCoord(source, "x", origin == null ? null : origin.getX(), 0);
        final Integer y = readCoord(source, "y", origin == null ? null : origin.getY(), 0);
        final Integer z = readCoord(source, "z", origin == null ? null : origin.getZ(), 0);
        if (x == null || y == null || z == null) {
            return null;
        }
        return new BlockPos(x, y, z);
    }

    /** 判断这个参数里到底有没有坐标（用来区分「没写」和「写了但解析不了」）。 */
    public static boolean hasBlockPos(final JsonObject params) {
        if (Json.has(params, "x")) {
            return true;
        }
        final JsonObject pos = Json.objOrNull(params, "pos");
        return pos != null && Json.has(pos, "x");
    }

    /**
     * 解析单个坐标分量。
     *
     * @param base     {@code ~} 的基准分量；null 表示没有基准
     * @param fallback 分量缺失时用的值（保持「缺省为 0」的老行为）
     * @return null 表示「写了相对坐标但没有基准」，需要调用方报错
     */
    private static Integer readCoord(final JsonObject source, final String key,
                                     final Integer base, final int fallback) {
        final JsonElement element = source.get(key);
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
            return (int) Math.floor(element.getAsDouble());
        }
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            final String text = element.getAsString().trim();
            if (text.startsWith("~")) {
                if (base == null) {
                    return null;
                }
                final String rest = text.substring(1).trim();
                if (rest.isEmpty()) {
                    return base;
                }
                try {
                    return base + (int) Math.floor(Double.parseDouble(rest));
                } catch (final NumberFormatException e) {
                    return null;
                }
            }
            try {
                return (int) Math.floor(Double.parseDouble(text));
            } catch (final NumberFormatException e) {
                return fallback;
            }
        }
        return fallback;
    }
}
