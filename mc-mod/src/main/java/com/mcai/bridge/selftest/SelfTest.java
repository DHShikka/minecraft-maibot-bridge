package com.mcai.bridge.selftest;

import com.mcai.bridge.McAiBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 自检入口：把客户端自动推进到一个真实世界，好让外部（插件侧）像真实 MaiBot 那样下发动作。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>模组只在<b>玩家进入世界</b>之后才连接插件
 * （{@code ClientBridge.onLoggingIn}）。而没人在电脑前时，客户端会一直停在标题界面 ——
 * 于是动作系统、寻路、任务组、卡死检测这些「只有进世界才跑得到」的东西全都验证不了。</p>
 *
 * <p>本类只做一件事：<b>用代码创建一个单人世界并进去</b>。进去之后不再插手，
 * 后面的一切都由真实的插件链路驱动（下发动作 → 模组执行 → 回传结果）。</p>
 *
 * <h2>安全边界</h2>
 *
 * <p>只有 JVM 属性 {@code -Dmcai.selftest} 存在时才会做任何事。玩家正常启动游戏时，
 * {@code enabled()} 返回 false，这个类在第一个 tick 就什么都不干 —— 对生产零影响。</p>
 *
 * <p>用法见 {@code tools/run-live.ps1} / {@code .research/runclient.mjs}：
 * 它会先删掉上次的自检存档，再用 {@code -Dmcai.selftest=1} 启动。</p>
 */
@Mod.EventBusSubscriber(modid = McAiBridge.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class SelfTest {

    /** 触发用的系统属性。 */
    private static final String PROP = "mcai.selftest";
    /** 自检世界的存档名（gameDir/saves/ 下的目录名）。 */
    public static final String LEVEL_ID = "mcai-selftest";
    /** 创建世界时的世界名。 */
    private static final String LEVEL_NAME = "McAi 自检世界";

    /** 进入世界后我们就撒手不管了；除非设了 quitAfter，到点自己退出。 */
    private static boolean done;
    private static boolean creating;
    private static long startedAt;
    private static long enteredWorldAt;
    private static int ticks;
    /** 死的那一刻（毫秒）；用来「过两秒自动重生」。 */
    private static long diedAt;

    @SubscribeEvent
    public static void onClientTick(final TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || done) {
            return;
        }
        if (System.getProperty(PROP) == null) {
            done = true; // 正常游玩：立刻退出，零开销
            return;
        }
        ticks++;
        final Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return;
        }
        // 外部要求优雅退出：在游戏目录里放一个 mcai-quit.flag 就行。
        //
        // 为什么需要它：调试时习惯用 Stop-Process -Force 硬杀客户端，而那样会丢掉
        // **还没写盘的那部分进度** —— 实测丢过一次「2 个桶 + 盾牌」（背包回滚到几分钟前）。
        // 走 mc.stop() 会正常存盘再退。
        if (quitRequested(mc)) {
            McAiBridge.LOGGER.info("[自检] 收到 mcai-quit.flag：保存并退出");
            done = true;
            mc.stop();
            return;
        }
        // 单人游戏**窗口失焦会自动暂停**，而暂停时集成的服务端不 tick：
        // 表现是「玩家一步都走不动、指令执行了但方块在客户端上不变」——
        // 无人值守的自检必须关掉它（真机调试时为这个现象排查了很久，
        // 游戏日志里的原话是 "Saving and pausing game..."）。
        if (mc.options != null && mc.options.pauseOnLostFocus) {
            mc.options.pauseOnLostFocus = false;
            McAiBridge.LOGGER.info("[自检] 已关掉「失焦自动暂停」（否则窗口不在前台时世界不 tick）");
        }
        if (mc.level != null) {
            // 已经在世界里：正常情况下撒手不管，只负责「到点退出」和「死了自动重生」
            if (enteredWorldAt == 0L) {
                enteredWorldAt = System.currentTimeMillis();
                McAiBridge.LOGGER.info("[自检] 已进入世界，交给插件链路驱动（存档 {}）", LEVEL_ID);
            }
            autoRespawn(mc);
            final long quitAfter = quitAfterSeconds();
            if (quitAfter > 0 && System.currentTimeMillis() - enteredWorldAt > quitAfter * 1000L) {
                McAiBridge.LOGGER.info("[自检] 到点（{} 秒），退出游戏", quitAfter);
                done = true;
                mc.stop();
            }
            return;
        }
        if (done) {
            return;
        }
        if (creating) {
            // 世界加载中：给足时间（生成地形可能要几十秒）
            if (System.currentTimeMillis() - startedAt > 180_000L) {
                McAiBridge.LOGGER.error("[自检] 等了 180 秒还没进世界，放弃");
                done = true;
            }
            return;
        }
        if (mc.screen instanceof TitleScreen && mc.getConnection() == null) {
            openOrCreateWorld(mc);
        } else if (ticks > 20 * 300) {
            McAiBridge.LOGGER.error("[自检] 300 秒内没等到标题界面（当前界面 {}），放弃",
                    mc.screen == null ? "null" : mc.screen.getClass().getSimpleName());
            done = true;
        }
    }

    /**
     * 死了就自动重生。
     *
     * <p>无人值守跑长任务时必须的：原版死了会停在「你死了！」界面等人点按钮，
     * 而我们这边没人点 —— 表现就是整个任务卡死在死亡界面上。
     * 留两秒是为了让死亡事件先报给插件（麦麦能知道死了一次、死在哪）。</p>
     */
    private static void autoRespawn(final Minecraft mc) {
        if (mc.player == null || !autoRespawn()) {
            return;
        }
        final boolean dead = mc.player.isDeadOrDying() || mc.player.getHealth() <= 0.0F;
        if (!dead) {
            diedAt = 0L;
            return;
        }
        if (diedAt == 0L) {
            diedAt = System.currentTimeMillis();
            McAiBridge.LOGGER.info("[自检] 玩家死亡（{}），2 秒后自动重生",
                    mc.player.blockPosition().toShortString());
            return;
        }
        if (System.currentTimeMillis() - diedAt > 2000L) {
            McAiBridge.LOGGER.info("[自检] 自动重生");
            diedAt = 0L;
            mc.player.respawn();
        }
    }

    /** 检测「请优雅退出」的标记文件（检出即删）。 */
    private static boolean quitRequested(final Minecraft mc) {
        final java.io.File flag = new java.io.File(mc.gameDirectory, "mcai-quit.flag");
        if (!flag.isFile()) {
            return false;
        }
        //noinspection ResultOfMethodCallIgnored
        flag.delete();
        return true;
    }

    /** 进世界后多少秒自动退出（0 = 不退出）。由 -Dmcai.selftest.quitAfter=N 指定。 */
    private static long quitAfterSeconds() {
        final String raw = System.getProperty(PROP + ".quitAfter");
        if (raw == null) {
            return 0L;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (final NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * 进世界：能读旧存档就读旧的，否则新建一个。
     *
     * <p>「保留存档」（{@code -Dmcai.selftest.keepWorld=true}）是给<b>长任务</b>用的：
     * 搭一座地狱门要砍树、挖矿、烧铁、找岩浆，跨越几十分钟甚至几次重启游戏。
     * 每次都新建世界的话，攒的东西全没了。所以长任务用同一个存档接着干。</p>
     */
    private static void openOrCreateWorld(final Minecraft mc) {
        if (keepWorld()) {
            final java.io.File save = new java.io.File(mc.gameDirectory, "saves/" + LEVEL_ID);
            if (new java.io.File(save, "level.dat").isFile()) {
                creating = true;
                startedAt = System.currentTimeMillis();
                McAiBridge.LOGGER.info("[自检] 读旧存档「{}」（保留存档模式，接着上次继续）", LEVEL_ID);
                try {
                    mc.createWorldOpenFlows().loadLevel(mc.screen, LEVEL_ID);
                } catch (final Throwable t) {
                    McAiBridge.LOGGER.error("[自检] 读旧存档失败", t);
                    done = true;
                }
                return;
            }
            McAiBridge.LOGGER.info("[自检] 没有旧存档，新建一个（之后一直用它）");
        }
        createWorld(mc);
    }

    /** 创建并进入一个单人世界。 */
    private static void createWorld(final Minecraft mc) {
        creating = true;
        startedAt = System.currentTimeMillis();
        final boolean flat = useFlat();
        McAiBridge.LOGGER.info("[自检] 正在创建世界「{}」（{}，{}，死亡不掉落={}）…", LEVEL_NAME,
                flat ? "超平坦" : "默认地形", allowCommands() ? "开作弊" : "不开作弊",
                keepInventory());
        try {
            final GameRules rules = new GameRules();
            if (keepInventory()) {
                // 长任务允许开「死亡不掉落」：真机会被怪打死，掉光装备等于从头再来。
                // 这是**建世界时**写进 rules 的，和游戏里敲 /gamerule 不是一回事。
                rules.getRule(GameRules.RULE_KEEPINVENTORY).set(true, null);
            }
            final LevelSettings settings = new LevelSettings(
                    LEVEL_NAME,
                    GameType.SURVIVAL,      // 生存，这样挖矿/合成才有意义
                    false,                  // hardcore
                    Difficulty.NORMAL,
                    allowCommands(),        // 单人开作弊时玩家自动有 OP
                    rules,
                    WorldDataConfiguration.DEFAULT);
            // 第二个参数：是否生成建筑；第三个：奖励箱。都给 false，让世界快点出来。
            final WorldOptions options = new WorldOptions(System.nanoTime(), false, false);
            // 平坦：周围全空、脚下是泥土层，放置和挖掘都可预测，适合无人值守的自动验证。
            // 默认地形：验证「下矿」「找地形」这类必须真地形才成立的能力。
            mc.createWorldOpenFlows().createFreshLevel(LEVEL_ID, settings, options,
                    registryAccess -> registryAccess.registryOrThrow(Registries.WORLD_PRESET)
                            .getOrThrow(flat ? WorldPresets.FLAT : WorldPresets.NORMAL)
                            .createWorldDimensions());
            McAiBridge.LOGGER.info("[自检] createFreshLevel 已调用，等待世界生成…");
        } catch (final Throwable t) {
            McAiBridge.LOGGER.error("[自检] 创建世界失败", t);
            done = true;
        }
    }

    /** 世界类型：默认超平坦（可预测）；{@code -Dmcai.selftest.terrain=normal} 切默认地形。 */
    private static boolean useFlat() {
        return !"normal".equalsIgnoreCase(System.getProperty(PROP + ".terrain", "flat").trim());
    }

    /** 是否开作弊（授权指令）。{@code -Dmcai.selftest.cheats=false} 可关掉，用于验证「不作弊」。 */
    private static boolean allowCommands() {
        return !"false".equalsIgnoreCase(System.getProperty(PROP + ".cheats", "true").trim());
    }

    /** 死亡不掉落。{@code -Dmcai.selftest.keepInventory=true} 打开（长任务用）。 */
    private static boolean keepInventory() {
        return "true".equalsIgnoreCase(System.getProperty(PROP + ".keepInventory", "false").trim());
    }

    /** 读旧存档接着玩。{@code -Dmcai.selftest.keepWorld=true} 打开（长任务用）。 */
    private static boolean keepWorld() {
        return "true".equalsIgnoreCase(System.getProperty(PROP + ".keepWorld", "false").trim());
    }

    /** 死亡后自动重生。默认开：无人值守时死在「你死了」界面上就全卡住了。 */
    private static boolean autoRespawn() {
        return !"false".equalsIgnoreCase(System.getProperty(PROP + ".autoRespawn", "true").trim());
    }

    private SelfTest() {
    }
}
