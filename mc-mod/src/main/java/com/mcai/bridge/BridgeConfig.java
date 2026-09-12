package com.mcai.bridge;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;

import java.util.List;

/**
 * 模组配置（Forge CLIENT 类型，生成于 config/mcai_bridge-client.toml）。
 *
 * <p>所有开关的默认值都以「可用优先」为目标：开箱即可连接本地 MaiBot 插件，
 * 同时把可能造成不可逆后果的动作（破坏方块 / 放置 / 攻击 / 执行服务器指令）
 * 暴露为独立开关，方便使用者按需收紧权限。</p>
 */
@Mod.EventBusSubscriber(modid = McAiBridge.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class BridgeConfig {

    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    // ================================================================ 连接
    //
    // 每个 push/pop 对应生成 TOML 里的一个 [section]。
    // 不加 push 的话所有键会平铺在文件顶层（原来的写法就是这样），
    // 31 个键混在一起非常难读，所以这里显式分节。

    static {
        BUILDER.comment("连接设置：模组作为客户端去连 MaiBot 插件的 WebSocket 服务端。").push("connection");
    }

    private static final ForgeConfigSpec.BooleanValue ENABLED = BUILDER
            .comment("是否启用 AI 桥接。关闭后不会建立任何网络连接，也不会拦截任何事件。")
            .define("enabled", true);

    private static final ForgeConfigSpec.BooleanValue AUTO_CONNECT = BUILDER
            .comment("进入世界/服务器后是否自动连接 MaiBot 插件。")
            .define("autoConnect", true);

    public static final ForgeConfigSpec.ConfigValue<String> SERVER_URL = BUILDER
            .comment("MaiBot 插件 WebSocket 服务端地址。",
                     "同一台电脑填 ws://127.0.0.1:<插件端口><插件路径>；",
                     "MaiBot 在另一台机器上就填那台机器的局域网 IP。")
            .define("serverUrl", "ws://127.0.0.1:8765/mc");

    public static final ForgeConfigSpec.ConfigValue<String> AUTH_TOKEN = BUILDER
            .comment("鉴权令牌，必须与 MaiBot 插件配置里的 auth_token 完全一致。留空表示不鉴权。",
                     "跨机器连接时强烈建议设置，否则任何能访问该端口的人都能操作你的角色。")
            .define("authToken", "");

    private static final ForgeConfigSpec.IntValue RECONNECT_DELAY_SECONDS = BUILDER
            .comment("断线后的重连基础间隔（秒），实际间隔会按指数退避增长，上限为该值的 6 倍。")
            .defineInRange("reconnectDelaySeconds", 5, 1, 300);

    private static final ForgeConfigSpec.IntValue HEARTBEAT_SECONDS = BUILDER
            .comment("心跳间隔（秒），同时用于检测对端假死。")
            .defineInRange("heartbeatSeconds", 15, 5, 600);

    private static final ForgeConfigSpec.IntValue CONNECT_TIMEOUT_SECONDS = BUILDER
            .comment("连接超时（秒）。")
            .defineInRange("connectTimeoutSeconds", 10, 1, 120);

    static {
        BUILDER.pop();
    }

    // ================================================================ 上报

    static {
        BUILDER.comment("上报设置：决定 AI 能「感知」到什么。").push("reporting");
    }

    private static final ForgeConfigSpec.IntValue SNAPSHOT_INTERVAL_TICKS = BUILDER
            .comment("定时全量状态快照的间隔（tick，20 tick = 1 秒）。0 表示关闭定时快照，只在事件发生时上报。")
            .defineInRange("snapshotIntervalTicks", 40, 0, 12000);

    private static final ForgeConfigSpec.BooleanValue REPORT_CHAT = BUILDER
            .comment("是否把游戏内聊天/系统消息上报给 AI。这是 AI「听到」玩家的主要途径。")
            .define("reportChat", true);

    private static final ForgeConfigSpec.BooleanValue REPORT_ACTION_BAR = BUILDER
            .comment("是否上报动作栏（action bar）消息。通常是技能冷却、提示等噪音，默认关闭。")
            .define("reportActionBar", false);

    private static final ForgeConfigSpec.BooleanValue REPORT_GAME_EVENTS = BUILDER
            .comment("是否上报受伤、死亡、重生、升级、切换维度、击杀等游戏事件。")
            .define("reportGameEvents", true);

    private static final ForgeConfigSpec.BooleanValue REPORT_DAMAGE = BUILDER
            .comment("是否上报受到伤害（含血量变化）。")
            .define("reportDamage", true);

    private static final ForgeConfigSpec.BooleanValue REPORT_BLOCK_EVENTS = BUILDER
            .comment("是否上报破坏/放置方块（只对「你正看着的那个方块」有效）。音量较大，默认关闭。")
            .define("reportBlockEvents", false);

    private static final ForgeConfigSpec.BooleanValue REPORT_ITEM_EVENTS = BUILDER
            .comment("是否上报拾取/失去物品（按背包总量差分推算）。音量较大，默认关闭。")
            .define("reportItemEvents", false);

    public static final ForgeConfigSpec.ConfigValue<String> CHAT_TRIGGER_PREFIX = BUILDER
            .comment("只有以此前缀开头的玩家发言才会交给 AI（留空表示所有玩家聊天都交给它）。",
                     "例如设成 \"!ai\" 之后，只有「!ai 去挖点铁」这样的发言会触发麦麦。")
            .define("chatTriggerPrefix", "");

    static {
        BUILDER.pop();
    }

    // ================================================================ 权限
    //
    // 这是最重要的一节：AI 到底能做什么，最终由这里决定。
    // 这些开关在游戏客户端本地，插件改不了它们。

    static {
        BUILDER.comment("权限开关：AI 能做什么。想收紧就把对应项改成 false。").push("permissions");
    }

    private static final ForgeConfigSpec.BooleanValue ALLOW_MOVEMENT = BUILDER
            .comment("允许 AI 控制移动（行走、跳跃、疾跑、游泳、跟随、寻路）。")
            .define("allowMovement", true);

    private static final ForgeConfigSpec.BooleanValue ALLOW_LOOK = BUILDER
            .comment("允许 AI 控制视角（转头、看向目标）。")
            .define("allowLook", true);

    private static final ForgeConfigSpec.BooleanValue ALLOW_BREAK = BUILDER
            .comment("允许 AI 破坏方块。")
            .define("allowBreakBlocks", true);

    private static final ForgeConfigSpec.BooleanValue ALLOW_PLACE = BUILDER
            .comment("允许 AI 放置方块。")
            .define("allowPlaceBlocks", true);

    private static final ForgeConfigSpec.BooleanValue ALLOW_ATTACK = BUILDER
            .comment("允许 AI 攻击实体。")
            .define("allowAttack", true);

    private static final ForgeConfigSpec.BooleanValue ALLOW_USE = BUILDER
            .comment("允许 AI 使用物品 / 与方块交互（右键）。")
            .define("allowUse", true);

    private static final ForgeConfigSpec.BooleanValue ALLOW_INVENTORY = BUILDER
            .comment("允许 AI 整理背包、切换手持物品、丢弃物品。")
            .define("allowInventory", true);

    private static final ForgeConfigSpec.BooleanValue ALLOW_CHAT = BUILDER
            .comment("允许 AI 在游戏内发言。")
            .define("allowChat", true);

    private static final ForgeConfigSpec.BooleanValue ALLOW_COMMAND = BUILDER
            .comment("允许 AI 执行游戏内指令（以 / 开头）。这是权限最高的一项，可配合 commandBlacklist 收紧。")
            .define("allowCommand", true);

    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> COMMAND_BLACKLIST = BUILDER
            .comment("禁止 AI 执行的指令（不含前导斜杠，忽略大小写）。",
                     "默认禁止会破坏他人游戏体验或不可逆的服务器管理指令。")
            .defineListAllowEmpty("commandBlacklist",
                    List.of("op", "deop", "ban", "ban-ip", "pardon", "pardon-ip", "kick", "stop",
                            "whitelist", "save-all", "save-off", "save-on", "reload", "restart"),
                    BridgeConfig::isNonEmptyString);

    static {
        BUILDER.pop();
    }

    // ============================================================== 安全阀

    static {
        BUILDER.comment("安全阀：防止 AI 或网络异常导致刷屏式操作、或角色被卡住太久。").push("limits");
    }

    private static final ForgeConfigSpec.IntValue MAX_ACTIONS_PER_SECOND = BUILDER
            .comment("每秒最多执行多少个动作。")
            .defineInRange("maxActionsPerSecond", 20, 1, 200);

    private static final ForgeConfigSpec.IntValue ACTION_TIMEOUT_SECONDS = BUILDER
            .comment("单个长任务（寻路、挖矿、追击等）的最长执行时间（秒）。超时后自动放弃并回报原因。")
            .defineInRange("actionTimeoutSeconds", 120, 5, 3600);

    private static final ForgeConfigSpec.IntValue MAX_QUEUED_ACTIONS = BUILDER
            .comment("动作队列上限，超出后拒绝新动作。")
            .defineInRange("maxQueuedActions", 32, 1, 512);

    private static final ForgeConfigSpec.IntValue STUCK_DETECTION_SECONDS = BUILDER
            .comment("卡死检测：角色连续这么多秒既没移动、也没有任何进展（没挖到方块、没合成出东西）",
                    "就判定卡住并提前报错，而不是沉默地耗到超时。改成 0 关闭。",
                    "调大的情况：网络卡顿严重、或者你在用会长时间原地不动的玩法。")
            .defineInRange("stuckDetectionSeconds", 24, 0, 600);

    static {
        BUILDER.pop();
    }

    // ================================================================ 调试

    static {
        BUILDER.comment("调试选项。").push("debug");
    }

    private static final ForgeConfigSpec.BooleanValue SHOW_HUD = BUILDER
            .comment("在游戏画面左上角显示桥接状态与当前任务。")
            .define("showHud", true);

    private static final ForgeConfigSpec.BooleanValue VERBOSE_LOG = BUILDER
            .comment("输出详细的调试日志（每条收发报文）。排查问题时临时打开。")
            .define("verboseLog", false);

    static {
        BUILDER.pop();
    }

    static final ForgeConfigSpec SPEC = BUILDER.build();

    // ------------------------------------------------------- 运行时快照字段

    public static boolean enabled = true;
    public static boolean autoConnect = true;
    public static String serverUrl = "ws://127.0.0.1:8765/mc";
    public static String authToken = "";
    public static int reconnectDelaySeconds = 5;
    public static int heartbeatSeconds = 15;
    public static int connectTimeoutSeconds = 10;
    public static int snapshotIntervalTicks = 40;
    public static boolean reportChat = true;
    public static boolean reportActionBar = false;
    public static boolean reportGameEvents = true;
    public static boolean reportDamage = true;
    public static boolean reportBlockEvents = false;
    public static boolean reportItemEvents = false;
    public static String chatTriggerPrefix = "";
    public static boolean allowMovement = true;
    public static boolean allowLook = true;
    public static boolean allowBreak = true;
    public static boolean allowPlace = true;
    public static boolean allowAttack = true;
    public static boolean allowUse = true;
    public static boolean allowInventory = true;
    public static boolean allowChat = true;
    public static boolean allowCommand = true;
    public static List<? extends String> commandBlacklist = List.of();
    public static int maxActionsPerSecond = 20;
    public static int actionTimeoutSeconds = 120;
    public static int maxQueuedActions = 32;
    /** 卡死检测：连续这么多秒没移动也没进展就判卡住。0 = 关闭。 */
    public static int stuckDetectionSeconds = 24;
    public static boolean showHud = true;
    public static boolean verboseLog = false;

    private BridgeConfig() {
    }

    private static boolean isNonEmptyString(final Object obj) {
        return obj instanceof final String s && !s.isBlank();
    }

    /** 校验某条指令是否被允许执行。 */
    public static boolean isCommandAllowed(final String command) {
        if (!allowCommand) {
            return false;
        }
        String name = command.startsWith("/") ? command.substring(1) : command;
        int space = name.indexOf(' ');
        if (space >= 0) {
            name = name.substring(0, space);
        }
        if (name.startsWith("/")) {
            // 形如 //set 之类的插件指令，取第一段
            name = name.substring(1);
        }
        for (final String blocked : commandBlacklist) {
            if (blocked.equalsIgnoreCase(name)) {
                return false;
            }
        }
        return true;
    }

    @SubscribeEvent
    static void onLoad(final ModConfigEvent event) {
        if (!McAiBridge.MODID.equals(event.getConfig().getModId())) {
            return;
        }
        if (event.getConfig().getType() != ModConfig.Type.CLIENT) {
            return;
        }
        enabled = ENABLED.get();
        autoConnect = AUTO_CONNECT.get();
        serverUrl = SERVER_URL.get();
        authToken = AUTH_TOKEN.get();
        reconnectDelaySeconds = RECONNECT_DELAY_SECONDS.get();
        heartbeatSeconds = HEARTBEAT_SECONDS.get();
        connectTimeoutSeconds = CONNECT_TIMEOUT_SECONDS.get();
        snapshotIntervalTicks = SNAPSHOT_INTERVAL_TICKS.get();
        reportChat = REPORT_CHAT.get();
        reportActionBar = REPORT_ACTION_BAR.get();
        reportGameEvents = REPORT_GAME_EVENTS.get();
        reportDamage = REPORT_DAMAGE.get();
        reportBlockEvents = REPORT_BLOCK_EVENTS.get();
        reportItemEvents = REPORT_ITEM_EVENTS.get();
        chatTriggerPrefix = CHAT_TRIGGER_PREFIX.get();
        allowMovement = ALLOW_MOVEMENT.get();
        allowLook = ALLOW_LOOK.get();
        allowBreak = ALLOW_BREAK.get();
        allowPlace = ALLOW_PLACE.get();
        allowAttack = ALLOW_ATTACK.get();
        allowUse = ALLOW_USE.get();
        allowInventory = ALLOW_INVENTORY.get();
        allowChat = ALLOW_CHAT.get();
        allowCommand = ALLOW_COMMAND.get();
        commandBlacklist = COMMAND_BLACKLIST.get();
        maxActionsPerSecond = MAX_ACTIONS_PER_SECOND.get();
        actionTimeoutSeconds = ACTION_TIMEOUT_SECONDS.get();
        maxQueuedActions = MAX_QUEUED_ACTIONS.get();
        stuckDetectionSeconds = STUCK_DETECTION_SECONDS.get();
        showHud = SHOW_HUD.get();
        verboseLog = VERBOSE_LOG.get();
    }
}
