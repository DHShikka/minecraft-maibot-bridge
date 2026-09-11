package com.mcai.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mcai.bridge.action.ActionExecutor;
import com.mcai.bridge.net.WsClient;
import com.mcai.bridge.protocol.Json;
import com.mcai.bridge.snapshot.StateCollector;
import com.mcai.bridge.util.GameUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.MovementInputUpdateEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.net.URI;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 客户端桥接总控。
 *
 * <p>它把三件事串起来：</p>
 * <ol>
 *   <li><b>感知</b>：把聊天、游戏事件、定时状态快照上报给 MaiBot 插件；</li>
 *   <li><b>执行</b>：接收插件的动作指令，转给 {@link ActionExecutor} 在客户端主线程执行；</li>
 *   <li><b>反馈</b>：把动作结果与执行进度回传。</li>
 * </ol>
 *
 * <p>线程模型：Forge 事件都在客户端主线程；WebSocket 的回调在独立读线程，
 * 因此收到动作后统一用 {@code Minecraft.execute()} 切到主线程再处理。</p>
 */
public final class ClientBridge {

    private static final ClientBridge INSTANCE = new ClientBridge();

    /** 重连退避的上限倍数。 */
    private static final int MAX_BACKOFF_MULTIPLIER = 6;

    private volatile WsClient client;
    private volatile Thread connectionThread;
    private volatile boolean running;
    private volatile boolean handshakeDone;
    private volatile int lastCloseCode = -1;
    private volatile String lastCloseReason = "";
    private final AtomicLong sentMessages = new AtomicLong();
    private final AtomicLong receivedActions = new AtomicLong();

    // ---- 状态 diff 用的缓存
    private float lastHealth = -1.0f;
    private int lastFood = -1;
    private int lastXpLevel = -1;
    private ResourceKey<Level> lastDimension;
    private BlockPos lastCrosshairBlock;
    private String lastCrosshairId = "";
    private Map<String, Integer> lastInventorySignature;
    private BlockPos lastPosition;
    private long lastCriticalWarning;
    private long lastKillReport;

    private int snapshotTimer;
    private int crosshairTimer;
    private int inventoryTimer;

    private ClientBridge() {
    }

    public static ClientBridge get() {
        return INSTANCE;
    }

    // ============================================================ Forge 事件

    /** 玩家进入世界（含单机与多人）。 */
    @SubscribeEvent
    public void onLoggingIn(final ClientPlayerNetworkEvent.LoggingIn event) {
        if (!BridgeConfig.enabled) {
            return;
        }
        McAiBridge.LOGGER.info("[MaiBot Bridge] 已进入世界，准备连接 MaiBot：{}", BridgeConfig.serverUrl);
        ActionExecutor.get().setSink(new ExecutorSink());
        if (BridgeConfig.autoConnect) {
            start();
        }
    }

    /** 玩家离开世界。 */
    @SubscribeEvent
    public void onLoggingOut(final ClientPlayerNetworkEvent.LoggingOut event) {
        McAiBridge.LOGGER.info("[MaiBot Bridge] 正在离开世界，断开与 MaiBot 的连接");
        ActionExecutor.get().cancelAll("玩家离开了世界");
        stop();
        resetDiffCache();
    }

    /** 玩家重生：维度、坐标都会变，重置 diff 缓存避免误报。 */
    @SubscribeEvent
    public void onClone(final ClientPlayerNetworkEvent.Clone event) {
        resetDiffCache();
        sendEvent(simpleEvent("respawn", "已重生"));
    }

    /** 收到聊天 / 系统消息。这是 AI「听到」游戏的主要途径。 */
    @SubscribeEvent
    public void onChatReceived(final ClientChatReceivedEvent event) {
        if (!BridgeConfig.reportChat || !isConnected()) {
            return;
        }
        final String text = event.getMessage().getString();
        if (text == null || text.isBlank()) {
            return;
        }

        final boolean overlay = event instanceof ClientChatReceivedEvent.System system && system.isOverlay();
        if (overlay && !BridgeConfig.reportActionBar) {
            return;
        }

        final boolean fromPlayer = event instanceof ClientChatReceivedEvent.Player;
        String sender = null;
        if (fromPlayer && event.getSender() != null && mc() != null && mc().level != null) {
            final Entity entity = mc().level.getPlayerByUUID(event.getSender());
            if (entity != null) {
                sender = entity.getName().getString();
            }
        }

        // 过滤条件：配置了 chatTriggerPrefix 时，只有以此开头的玩家发言才上报
        final String prefix = BridgeConfig.chatTriggerPrefix;
        if (!prefix.isEmpty() && fromPlayer) {
            if (!text.startsWith(prefix)) {
                return;
            }
        }

        final JsonObject data = new JsonObject();
        data.addProperty("kind", "chat");
        data.addProperty("channel", overlay ? "actionbar" : (fromPlayer ? "chat" : "system"));
        data.addProperty("text", text);
        data.addProperty("raw", text);
        data.addProperty("player", fromPlayer);
        data.addProperty("sender", sender);
        data.addProperty("isSystem", event.isSystem());
        try {
            data.addProperty("chatTypeName", event.getBoundChatType().name().getString());
        } catch (final Throwable ignored) {
            // 某些版本结构不同，忽略即可
        }
        attachPosition(data);
        sendEvent(data);
    }

    /** 每个 tick：推进动作、做状态 diff、定时上报快照。 */
    @SubscribeEvent
    public void onClientTick(final TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        final Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return;
        }

        // 动作执行器无论是否连上 MaiBot 都要 tick：
        // 否则断线时正在执行的动作会永远卡住，玩家一直被「幽灵按键」控制。
        ActionExecutor.get().tick(mc);

        if (!isConnected() || !handshakeDone) {
            return;
        }
        if (mc.player == null || mc.level == null) {
            return;
        }

        try {
            trackChanges(mc);
        } catch (final Throwable t) {
            McAiBridge.LOGGER.error("[MaiBot Bridge] 状态跟踪异常", t);
        }

        if (BridgeConfig.snapshotIntervalTicks > 0) {
            snapshotTimer++;
            if (snapshotTimer >= BridgeConfig.snapshotIntervalTicks) {
                snapshotTimer = 0;
                sendState("interval");
            }
        }
    }

    /** 移动输入覆盖点：让 AI 的移动意图真正作用到玩家身上。 */
    @SubscribeEvent
    public void onMovementInput(final MovementInputUpdateEvent event) {
        if (event.getEntity() != mc().player) {
            return;
        }
        ActionExecutor.get().applyInput(mc(), event.getInput());
    }

    // ====================================================== 状态变化检测

    private void trackChanges(final Minecraft mc) {
        final LocalPlayer player = mc.player;
        final ClientLevel level = mc.level;
        if (player == null || level == null) {
            return;
        }

        // ------------------------------------------------------------ 受伤
        final float health = player.getHealth();
        if (lastHealth >= 0 && BridgeConfig.reportDamage && health < lastHealth - 0.01f) {
            final JsonObject data = new JsonObject();
            data.addProperty("kind", "damage");
            data.addProperty("amount", round(lastHealth - health));
            data.addProperty("healthBefore", round(lastHealth));
            data.addProperty("healthAfter", round(health));
            final LivingEntity attacker = player.getLastHurtByMob();
            if (attacker != null) {
                data.addProperty("source", GameUtils.entityTypeId(attacker));
                data.addProperty("sourceName", GameUtils.entityName(attacker));
            } else {
                data.addProperty("source", "unknown");
            }
            attachPosition(data);
            sendEvent(data);

            // 血量过低时单独提醒一次
            if (health <= 6.0f && System.currentTimeMillis() - lastCriticalWarning > 15_000L) {
                lastCriticalWarning = System.currentTimeMillis();
                final JsonObject critical = new JsonObject();
                critical.addProperty("kind", "health_critical");
                critical.addProperty("health", round(health));
                critical.addProperty("maxHealth", round(player.getMaxHealth()));
                attachPosition(critical);
                sendEvent(critical);
            }
        }
        // ------------------------------------------------------------ 死亡
        if (lastHealth > 0 && health <= 0) {
            final JsonObject data = new JsonObject();
            data.addProperty("kind", "death");
            data.addProperty("health", 0);
            final LivingEntity killer = player.getLastHurtByMob();
            if (killer != null) {
                data.addProperty("killer", GameUtils.entityName(killer));
                data.addProperty("killerType", GameUtils.entityTypeId(killer));
            }
            try {
                if (player.getLastDamageSource() != null) {
                    data.addProperty("damageSource", player.getLastDamageSource().getMsgId());
                }
            } catch (final Throwable ignored) {
                // 忽略
            }
            attachPosition(data);
            sendEvent(data);
        }
        lastHealth = health;

        // -------------------------------------------------------- 击杀计数
        if (BridgeConfig.reportGameEvents && System.currentTimeMillis() - lastKillReport > 500L) {
            final LivingEntity victim = player.getLastHurtMob();
            if (victim != null && !victim.isAlive()) {
                lastKillReport = System.currentTimeMillis();
                final JsonObject data = new JsonObject();
                data.addProperty("kind", "kill");
                data.addProperty("entity", GameUtils.entityTypeId(victim));
                data.addProperty("name", GameUtils.entityName(victim));
                attachPosition(data);
                sendEvent(data);
            }
        }

        if (!BridgeConfig.reportGameEvents) {
            return;
        }

        // -------------------------------------------------------- 升级 / 饥饿
        final int xpLevel = player.experienceLevel;
        if (lastXpLevel >= 0 && xpLevel > lastXpLevel) {
            final JsonObject data = new JsonObject();
            data.addProperty("kind", "level_up");
            data.addProperty("level", xpLevel);
            sendEvent(data);
        }
        lastXpLevel = xpLevel;

        final int food = player.getFoodData().getFoodLevel();
        if (lastFood >= 0 && food < lastFood - 1 && food <= 6) {
            final JsonObject data = new JsonObject();
            data.addProperty("kind", "hungry");
            data.addProperty("food", food);
            attachPosition(data);
            sendEvent(data);
        }
        lastFood = food;

        // ---------------------------------------------------------- 换维度
        final ResourceKey<Level> dimension = level.dimension();
        if (lastDimension != null && !lastDimension.equals(dimension)) {
            final JsonObject data = new JsonObject();
            data.addProperty("kind", "dimension_change");
            data.addProperty("from", lastDimension.location().toString());
            data.addProperty("to", dimension.location().toString());
            attachPosition(data);
            sendEvent(data);
            sendState("dimension_change");
        }
        lastDimension = dimension;

        // -------------------------------------- 可选：准星方块变化 / 背包变化
        if (BridgeConfig.reportBlockEvents) {
            crosshairTimer++;
            if (crosshairTimer >= 10) {
                crosshairTimer = 0;
                trackCrosshairBlock(mc, level, player);
            }
        }
        if (BridgeConfig.reportItemEvents) {
            inventoryTimer++;
            if (inventoryTimer >= 20) {
                inventoryTimer = 0;
                trackInventory(player);
            }
        }
        lastPosition = player.blockPosition();
    }

    /** 通过准星方块的变化推断「挖掉了 / 放下了」——只在你正看着它的时候有效。 */
    private void trackCrosshairBlock(final Minecraft mc, final ClientLevel level, final LocalPlayer player) {
        if (!(mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit)) {
            return;
        }
        final BlockPos pos = hit.getBlockPos();
        final BlockState state = level.getBlockState(pos);
        final String id = GameUtils.blockId(state);

        if (lastCrosshairBlock != null && lastCrosshairBlock.equals(pos)) {
            final boolean wasAir = lastCrosshairId.isEmpty() || "minecraft:air".equals(lastCrosshairId);
            final boolean isAir = state.isAir();
            if (!wasAir && isAir) {
                final JsonObject data = new JsonObject();
                data.addProperty("kind", "block_break");
                data.addProperty("block", lastCrosshairId);
                data.addProperty("pos", GameUtils.format(pos));
                sendEvent(data);
            } else if (wasAir && !isAir) {
                final JsonObject data = new JsonObject();
                data.addProperty("kind", "block_place");
                data.addProperty("block", id);
                data.addProperty("pos", GameUtils.format(pos));
                sendEvent(data);
            }
        }
        lastCrosshairBlock = pos;
        lastCrosshairId = id;
    }

    /** 背包总量签名对比，用于上报拾取/消耗。 */
    private void trackInventory(final LocalPlayer player) {
        final Inventory inv = player.getInventory();
        final Map<String, Integer> signature = new LinkedHashMap<>();
        int total = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            final ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            final String id = GameUtils.itemId(stack);
            signature.merge(id, stack.getCount(), Integer::sum);
            total += stack.getCount();
        }
        if (lastInventorySignature != null) {
            for (final Map.Entry<String, Integer> entry : signature.entrySet()) {
                final int before = lastInventorySignature.getOrDefault(entry.getKey(), 0);
                if (entry.getValue() > before) {
                    final JsonObject data = new JsonObject();
                    data.addProperty("kind", "item_gain");
                    data.addProperty("item", entry.getKey());
                    data.addProperty("count", entry.getValue() - before);
                    sendEvent(data);
                }
            }
            for (final Map.Entry<String, Integer> entry : lastInventorySignature.entrySet()) {
                final int after = signature.getOrDefault(entry.getKey(), 0);
                if (entry.getValue() > after) {
                    final JsonObject data = new JsonObject();
                    data.addProperty("kind", "item_lost");
                    data.addProperty("item", entry.getKey());
                    data.addProperty("count", entry.getValue() - after);
                    sendEvent(data);
                }
            }
        }
        lastInventorySignature = signature;
    }

    private void attachPosition(final JsonObject data) {
        final Minecraft mc = mc();
        if (mc == null || mc.player == null || mc.level == null) {
            return;
        }
        data.add("pos", StateCollector.vec(mc.player.position()));
        data.addProperty("dimension", mc.level.dimension().location().toString());
        data.addProperty("playerName", mc.player.getName().getString());
    }

    private void resetDiffCache() {
        lastHealth = -1.0f;
        lastFood = -1;
        lastXpLevel = -1;
        lastDimension = null;
        lastCrosshairBlock = null;
        lastCrosshairId = "";
        lastInventorySignature = null;
        lastPosition = null;
    }

    // ========================================================= 连接生命周期

    /** 启动连接与自动重连。重复调用安全。 */
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        connectionThread = new Thread(this::connectionLoop, "McAiBridge-Connection");
        connectionThread.setDaemon(true);
        connectionThread.start();
    }

    /** 停止连接（不会自动重连）。 */
    public synchronized void stop() {
        running = false;
        handshakeDone = false;
        final WsClient current = client;
        if (current != null) {
            try {
                current.close(1000, "客户端主动断开");
            } catch (final Throwable ignored) {
                // 忽略
            }
        }
        client = null;
    }

    private void connectionLoop() {
        int attempt = 0;
        while (running && BridgeConfig.enabled) {
            try {
                attempt = connectOnce(attempt);
            } catch (final Throwable t) {
                McAiBridge.LOGGER.warn("[MaiBot Bridge] 连接失败: {}", t.getMessage());
            }
            if (!running) {
                break;
            }
            attempt++;
            final long baseSeconds = Math.max(1, BridgeConfig.reconnectDelaySeconds);
            final long multiplier = Math.min(1L << Math.min(attempt - 1, 4), MAX_BACKOFF_MULTIPLIER);
            final long delayMs = baseSeconds * multiplier * 1000L;
            McAiBridge.LOGGER.info("[MaiBot Bridge] {} 秒后重试连接（第 {} 次）", delayMs / 1000, attempt);
            try {
                Thread.sleep(delayMs);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** 建立一次连接并阻塞到断开。返回 0 表示连接曾经成功过（重置退避）。 */
    private int connectOnce(final int previousAttempts) throws Exception {
        final URI uri = parseUri(BridgeConfig.serverUrl);
        final Map<String, String> headers = new HashMap<>();
        if (!BridgeConfig.authToken.isEmpty()) {
            headers.put("X-McAiBridge-Token", BridgeConfig.authToken);
        }

        final ClientBridge self = this;
        final WsClient ws = new WsClient(uri, new WsClient.Listener() {
            @Override
            public void onOpen() {
                McAiBridge.LOGGER.info("[MaiBot Bridge] 已连接到 MaiBot：{}", uri);
                self.sendHello();
            }

            @Override
            public void onText(final String text) {
                self.handleIncoming(text);
            }

            @Override
            public void onClose(final int statusCode, final String reason, final boolean remote) {
                handshakeDone = false;
                lastCloseCode = statusCode;
                lastCloseReason = reason;
                if (remote) {
                    McAiBridge.LOGGER.warn("[MaiBot Bridge] 与 MaiBot 的连接已断开（code={} reason={}）",
                            statusCode, reason);
                }
                ActionExecutor.get().cancelAll("与 MaiBot 的连接已断开");
            }

            @Override
            public void onError(final Throwable error) {
                McAiBridge.LOGGER.debug("[MaiBot Bridge] 连接错误: {}", error.toString());
            }
        }, headers, Math.max(1000, BridgeConfig.connectTimeoutSeconds * 1000));

        this.client = ws;
        // 先保存引用再 start()：onOpen 回调里要用 this.client 发 hello，
        // 而 WsClient 刻意不在构造函数里回调（否则那时引用还是 null）。
        ws.start();

        final boolean opened = ws.isOpen();
        if (opened && previousAttempts > 0) {
            McAiBridge.LOGGER.info("[MaiBot Bridge] 重连成功");
        }

        // 阻塞直到连接关闭
        while (running && ws.isOpen()) {
            try {
                Thread.sleep(250);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        this.client = null;
        handshakeDone = false;
        return opened ? 0 : previousAttempts;
    }

    private URI parseUri(final String raw) throws Exception {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            value = "ws://127.0.0.1:8765/mc";
        }
        if (!value.startsWith("ws://") && !value.startsWith("wss://")) {
            value = "ws://" + value;
        }
        final URI uri = URI.create(value);
        if (uri.getHost() == null) {
            throw new IllegalArgumentException("serverUrl 不合法: " + raw);
        }
        return uri;
    }

    // ============================================================ 收发报文

    private void sendHello() {
        final Minecraft mc = mc();
        final JsonObject data = new JsonObject();
        data.addProperty("token", BridgeConfig.authToken);
        data.addProperty("protocol", 1);

        final JsonObject mod = new JsonObject();
        mod.addProperty("version", "1.0.0");
        mod.addProperty("mc", "1.20.1");
        mod.addProperty("forge", "47.4.10");
        mod.addProperty("name", "mcai_bridge");
        data.add("mod", mod);

        final JsonObject player = new JsonObject();
        if (mc != null && mc.player != null) {
            player.addProperty("name", mc.player.getName().getString());
            player.addProperty("uuid", mc.player.getUUID().toString());
            if (mc.level != null) {
                player.addProperty("dimension", mc.level.dimension().location().toString());
            }
        }
        data.add("player", player);

        data.addProperty("singleplayer", mc != null && mc.isSingleplayer());
        data.addProperty("serverName", StateCollector.serverDisplayName(mc));
        data.addProperty("serverAddress", mc != null && mc.getCurrentServer() != null
                ? String.valueOf(mc.getCurrentServer().ip) : "");
        data.addProperty("worldKey", StateCollector.worldKey(mc));

        final JsonArray capabilities = new JsonArray();
        for (final String cap : new String[]{"chat", "command", "move", "pathfind", "mine", "place",
                "attack", "use", "inventory", "scan", "sleep", "follow"}) {
            capabilities.add(cap);
        }
        data.add("capabilities", capabilities);

        send("hello", data);
    }

    private void handleIncoming(final String text) {
        final JsonObject message;
        try {
            message = Json.parse(text);
        } catch (final Throwable t) {
            McAiBridge.LOGGER.warn("[MaiBot Bridge] 收到无法解析的报文: {}", Json.abbreviate(text, 200));
            return;
        }

        final String type = Json.getType(message);
        switch (type) {
            case "action" -> {
                final Minecraft mc = mc();
                if (mc == null) {
                    return;
                }
                receivedActions.incrementAndGet();
                // 切回客户端主线程执行
                mc.execute(() -> {
                    try {
                        ActionExecutor.get().handle(message);
                    } catch (final Throwable t) {
                        McAiBridge.LOGGER.error("[MaiBot Bridge] 处理动作失败", t);
                    }
                });
            }
            case "ping" -> send("pong", new JsonObject());
            case "hello_ack" -> {
                final JsonObject data = Json.getData(message);
                if (Json.bool(data, "ok", false)) {
                    handshakeDone = true;
                    McAiBridge.LOGGER.info("[MaiBot Bridge] 握手成功，会话 {}（服务端协议 v{}）",
                            Json.str(data, "sessionId", "?"), Json.intVal(data, "protocol", 1));
                    final Minecraft mc = mc();
                    if (mc != null) {
                        mc.execute(() -> sendState("join"));
                    }
                } else {
                    final String error = Json.str(data, "error", "未知原因");
                    McAiBridge.LOGGER.error("[MaiBot Bridge] 握手被拒绝：{}", error);
                    handshakeDone = false;
                }
            }
            case "shutdown" -> {
                McAiBridge.LOGGER.warn("[MaiBot Bridge] 服务端要求断开：{}",
                        Json.str(Json.getData(message), "reason", ""));
                stop();
            }
            case "notice" -> {
                final JsonObject data = Json.getData(message);
                McAiBridge.LOGGER.info("[MaiBot Bridge] 来自 MaiBot 的通知: {}", Json.str(data, "message", ""));
            }
            default -> McAiBridge.LOGGER.debug("[MaiBot Bridge] 收到未处理的报文类型: {}", type);
        }
    }

    private boolean send(final String type, final JsonObject data) {
        final WsClient ws = client;
        if (ws == null || !ws.isOpen()) {
            return false;
        }
        final JsonObject envelope = Json.envelope(type, data);
        final String json = Json.toJson(envelope);
        final boolean ok = ws.sendText(json);
        if (ok) {
            sentMessages.incrementAndGet();
            if (BridgeConfig.verboseLog) {
                McAiBridge.LOGGER.info("[MaiBot Bridge] -> {}", Json.abbreviate(json, 400));
            }
        }
        return ok;
    }

    private void sendEvent(final JsonObject data) {
        if (!isConnected() || !handshakeDone) {
            return;
        }
        send("event", data);
    }

    /** 主动上报一次状态快照。 */
    public void sendState(final String reason) {
        if (!isConnected() || !handshakeDone) {
            return;
        }
        final Minecraft mc = mc();
        if (mc == null || mc.player == null) {
            return;
        }
        final JsonObject state = StateCollector.collect(mc, reason);
        final JsonObject executor = ActionExecutor.get().statusJson();
        state.add("task", executor.get("current"));
        state.add("queuedTasks", executor.get("queued"));
        state.addProperty("queueLength", Json.intVal(executor, "queueLength", 0));

        final JsonObject bridge = new JsonObject();
        bridge.addProperty("connected", true);
        bridge.addProperty("sentMessages", sentMessages.get());
        bridge.addProperty("receivedActions", receivedActions.get());
        bridge.addProperty("modVersion", "1.0.0");
        bridge.addProperty("serverName", StateCollector.serverDisplayName(mc));
        bridge.addProperty("worldKey", StateCollector.worldKey(mc));
        state.add("bridge", bridge);

        send("state", state);
    }

    private static JsonObject simpleEvent(final String kind, final String note) {
        final JsonObject data = new JsonObject();
        data.addProperty("kind", kind);
        if (note != null) {
            data.addProperty("note", note);
        }
        INSTANCE.attachPosition(data);
        return data;
    }

    // ================================================================ 状态

    public boolean isConnected() {
        final WsClient ws = client;
        return ws != null && ws.isOpen();
    }

    public boolean isHandshakeDone() {
        return handshakeDone;
    }

    public String statusText() {
        if (!BridgeConfig.enabled) {
            return "§7已禁用";
        }
        if (!running) {
            return "§7未连接";
        }
        if (isConnected() && handshakeDone) {
            return "§a已连接";
        }
        if (isConnected()) {
            return "§e握手中";
        }
        return "§c连接中…";
    }

    public String detailText() {
        if (isConnected()) {
            return BridgeConfig.serverUrl;
        }
        if (lastCloseCode > 0) {
            return "上次断开 code=" + lastCloseCode + " " + Json.abbreviate(lastCloseReason, 40);
        }
        return BridgeConfig.serverUrl;
    }

    // ================================================================ 辅助

    private static Minecraft mc() {
        return Minecraft.getInstance();
    }

    private static double round(final double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static double round(final float value) {
        return round((double) value);
    }

    /** 把动作结果通过 WebSocket 回传给插件。 */
    private final class ExecutorSink implements ActionExecutor.ResultSink {

        @Override
        public void onActionResult(final String actionId, final String actionType, final boolean ok,
                                   final JsonObject result, final String error, final long elapsedMs) {
            final JsonObject data = new JsonObject();
            data.addProperty("actionId", actionId);
            data.addProperty("action", actionType);
            data.addProperty("ok", ok);
            data.addProperty("elapsedMs", elapsedMs);
            if (result != null) {
                data.add("result", result);
            }
            if (error != null) {
                data.addProperty("error", error);
            }
            // 附带一份轻量状态，省掉插件再问一次
            final Minecraft mc = mc();
            if (mc != null && mc.player != null) {
                final JsonObject brief = new JsonObject();
                brief.add("pos", StateCollector.vec(mc.player.position()));
                brief.add("blockPos", StateCollector.blockPos(mc.player.blockPosition()));
                brief.addProperty("health", round(mc.player.getHealth()));
                if (mc.level != null) {
                    brief.addProperty("dimension", mc.level.dimension().location().toString());
                }
                data.add("state", brief);
            }
            send("action_result", data);
            if (BridgeConfig.verboseLog) {
                McAiBridge.LOGGER.info("[MaiBot Bridge] <- 动作结果 {} {} ok={} {}ms", actionType, actionId, ok, elapsedMs);
            }
        }

        @Override
        public void onTaskProgress(final String actionId, final String actionType,
                                   final double progress, final String detail) {
            final JsonObject data = new JsonObject();
            data.addProperty("kind", "task_progress");
            data.addProperty("actionId", actionId);
            data.addProperty("action", actionType);
            if (progress >= 0) {
                data.addProperty("progress", round(progress));
            }
            if (detail != null && !detail.isEmpty()) {
                data.addProperty("detail", detail);
            }
            attachPosition(data);
            sendEvent(data);
        }
    }

    /** 未使用的占位：保留以便将来把方块名做成本地化映射表。 */
    static String blockDisplayName(final BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }
}
