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
        if (mc.level != null) {
            // 已经在世界里：正常情况下撒手不管，只负责「到点退出」
            if (enteredWorldAt == 0L) {
                enteredWorldAt = System.currentTimeMillis();
                McAiBridge.LOGGER.info("[自检] 已进入世界，交给插件链路驱动（存档 {}）", LEVEL_ID);
            }
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
            createWorld(mc);
        } else if (ticks > 20 * 300) {
            McAiBridge.LOGGER.error("[自检] 300 秒内没等到标题界面（当前界面 {}），放弃",
                    mc.screen == null ? "null" : mc.screen.getClass().getSimpleName());
            done = true;
        }
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

    /** 创建并进入一个单人世界。 */
    private static void createWorld(final Minecraft mc) {
        creating = true;
        startedAt = System.currentTimeMillis();
        final boolean flat = useFlat();
        McAiBridge.LOGGER.info("[自检] 正在创建世界「{}」（{}，{}）…", LEVEL_NAME,
                flat ? "超平坦" : "默认地形", allowCommands() ? "开作弊" : "不开作弊");
        try {
            final GameRules rules = new GameRules();
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

    private SelfTest() {
    }
}
