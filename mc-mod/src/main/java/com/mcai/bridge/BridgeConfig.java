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

    // ============================================================== 战斗

    static {
        BUILDER.comment("战斗：被打之后的自动反应。这些是**模组自己的行为**，不需要麦麦下发动作。")
                .push("combat");
    }

    private static final ForgeConfigSpec.BooleanValue AUTO_FIGHT = BUILDER
            .comment("被生物打了就立刻反击：立刻中断手上的活、转身打回去。",
                     "关掉的话只有麦麦显式下发 attack/defend 才会还手。")
            .define("autoFight", true);

    private static final ForgeConfigSpec.BooleanValue USE_SHIELD = BUILDER
            .comment("有盾牌就自动换到副手，并在敌人靠近时举盾格挡。")
            .define("useShield", true);

    private static final ForgeConfigSpec.BooleanValue USE_BOW = BUILDER
            .comment("目标在天上/会飞（近战够不到）时，自动换弓射它。需要背包里有弓和箭。")
            .define("useBow", true);

    private static final ForgeConfigSpec.IntValue AUTO_FIGHT_COOLDOWN_MS = BUILDER
            .comment("两次自动反击之间至少隔多少毫秒（防止被连续打时反复重开任务）。")
            .defineInRange("autoFightCooldownMs", 1500, 0, 60_000);

    private static final ForgeConfigSpec.BooleanValue AUTO_ATTACK_HOSTILES = BUILDER
            .comment("**主动出击**：托管期间附近出现敌对生物就上去打（不等它先动手）。",
                     "用的是 defend 那套战术：血少先吃、被围就撤、优先打正在打我的。",
                     "只在 AI 托管时生效 —— 你自己玩的时候不会被抢手柄。")
            .define("autoAttackHostiles", true);

    private static final ForgeConfigSpec.BooleanValue AUTO_RELOAD = BUILDER
            .comment("**弹匣清空自动换弹**：手上是枪、弹匣打空了、背包里还有同口径的子弹，",
                     "而且自己正闲着（没有别的动作在跑）时，自动换弹。",
                     "只在 AI 托管时生效 —— 你自己玩的时候不会抢你的 R 键。",
                     "战斗过程中不走这条：那时由战斗逻辑自己决定该射、该换还是该抡。")
            .define("autoReload", true);

    private static final ForgeConfigSpec.IntValue AUTO_ATTACK_RADIUS = BUILDER
            .comment("主动出击的警戒半径（格）。")
            .defineInRange("autoAttackRadius", 12, 3, 48);

    private static final ForgeConfigSpec.IntValue AUTO_ATTACK_COOLDOWN_MS = BUILDER
            .comment("两次主动出击之间至少隔多少毫秒（打完一轮先喘口气，别追着满地图跑）。")
            .defineInRange("autoAttackCooldownMs", 4000, 0, 120_000);

    private static final ForgeConfigSpec.IntValue AUTO_ATTACK_MAX_KILLS = BUILDER
            .comment("一轮主动出击最多清掉几个（防止一路追杀到天亮）。")
            .defineInRange("autoAttackMaxKills", 4, 1, 64);

    private static final ForgeConfigSpec.BooleanValue TARGET_REQUIRE_LOS = BUILDER
            .comment("选目标要不要检查视线（被方块挡住的不算目标）。",
                     "开着更聪明（不会对着墙砍）；想让它隔墙也打（比如隔着栅栏射）可以关掉。",
                     "做法参考 10089YMGC/AutoAim：从眼睛到目标眼睛投射线，被挡住就跳过。")
            .define("targetRequireLineOfSight", true);

    private static final ForgeConfigSpec.BooleanValue TARGET_SKIP_INVISIBLE = BUILDER
            .comment("隐身的目标跳过（看不见，瞄它没意义）。")
            .define("targetSkipInvisible", true);

    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> TARGET_BLACKLIST = BUILDER
            .comment("**永远不主动攻击**的实体（实体 id，忽略大小写）。",
                     "默认把村民、铁傀儡、盔甲架、宠物这类「打了是纯粹搞破坏」的拉黑；",
                     "AI 手里有剑的时候去打村民是很糟糕的行为。想让它打谁就把谁从这里删掉。")
            .defineListAllowEmpty("targetBlacklist",
                    List.of("minecraft:villager", "minecraft:wandering_trader",
                            "minecraft:iron_golem", "minecraft:snow_golem",
                            "minecraft:armor_stand", "minecraft:bat",
                            "minecraft:wolf", "minecraft:cat", "minecraft:parrot",
                            "minecraft:allay", "minecraft:axolotl", "minecraft:item_frame",
                            "minecraft:painting"),
                    BridgeConfig::isNonEmptyString);

    static {
        BUILDER.pop();
    }

    // ============================================================== 画面

    static {
        BUILDER.comment("画面：不影响游戏规则，只改客户端显示。")
                .push("visual");
    }

    private static final ForgeConfigSpec.DoubleValue GAMMA = BUILDER
            .comment("把「亮度」强行拉到这个值，效果等同于夜视 / fullbright（洞穴里全亮）。",
                     "0 = 不动玩家的设置；1.0 = 原版能拉到的最大亮度；10 以上基本全亮（推荐 10）。",
                     "注意：这是客户端本地设置，退出游戏时会写进 options.txt —— 也就是说",
                     "它会留在你的游戏设置里，不会自己还原。")
            .defineInRange("gamma", 0.0, 0.0, 100.0);

    static {
        BUILDER.pop();
    }

    // ============================================================== 托管

    static {
        BUILDER.comment("AI 托管：麦麦接手之后，玩家可以放开鼠标、切出去，游戏照常跑、AI 照常操作。")
                .push("takeover");
    }

    private static final ForgeConfigSpec.BooleanValue TAKEOVER_ON_CONNECT = BUILDER
            .comment("麦麦（插件）一连上就自动进入托管。关掉的话只能用动作/工具手动开。")
            .define("autoOnConnect", true);

    private static final ForgeConfigSpec.BooleanValue TAKEOVER_RELEASE_MOUSE = BUILDER
            .comment("托管时把鼠标从游戏窗口里放开（不然玩家一碰鼠标就和 AI 抢视角）。")
            .define("releaseMouse", true);

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
    public static boolean autoFight = true;
    public static boolean useShield = true;
    public static boolean useBow = true;
    public static int autoFightCooldownMs = 1500;
    public static boolean autoAttackHostiles = true;
    public static boolean autoReload = true;
    public static int autoAttackRadius = 12;
    public static int autoAttackCooldownMs = 4000;
    public static int autoAttackMaxKills = 4;
    /** 选目标时要求视线通畅（不被方块挡住）、跳过隐身、以及永不主动攻击的黑名单。 */
    public static boolean targetRequireLineOfSight = true;
    public static boolean targetSkipInvisible = true;
    public static List<? extends String> targetBlacklist = List.of();
    /** 强行设定的伽马值（0 = 不动）。用来模拟夜视：洞穴里也能看清。 */
    public static double gamma = 0.0;
    /** AI 托管：麦麦一连上就接管（关掉失焦暂停、放开鼠标）。 */
    public static boolean takeoverOnConnect = true;
    public static boolean takeoverReleaseMouse = true;
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
        autoFight = AUTO_FIGHT.get();
        useShield = USE_SHIELD.get();
        useBow = USE_BOW.get();
        autoFightCooldownMs = AUTO_FIGHT_COOLDOWN_MS.get();
        autoAttackHostiles = AUTO_ATTACK_HOSTILES.get();
        autoReload = AUTO_RELOAD.get();
        autoAttackRadius = AUTO_ATTACK_RADIUS.get();
        autoAttackCooldownMs = AUTO_ATTACK_COOLDOWN_MS.get();
        autoAttackMaxKills = AUTO_ATTACK_MAX_KILLS.get();
        targetRequireLineOfSight = TARGET_REQUIRE_LOS.get();
        targetSkipInvisible = TARGET_SKIP_INVISIBLE.get();
        targetBlacklist = TARGET_BLACKLIST.get();
        gamma = GAMMA.get();
        takeoverOnConnect = TAKEOVER_ON_CONNECT.get();
        takeoverReleaseMouse = TAKEOVER_RELEASE_MOUSE.get();
        maxActionsPerSecond = MAX_ACTIONS_PER_SECOND.get();
        actionTimeoutSeconds = ACTION_TIMEOUT_SECONDS.get();
        maxQueuedActions = MAX_QUEUED_ACTIONS.get();
        stuckDetectionSeconds = STUCK_DETECTION_SECONDS.get();
        showHud = SHOW_HUD.get();
        verboseLog = VERBOSE_LOG.get();
    }
}
