package com.mcai.bridge;

import com.mojang.logging.LogUtils;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

/**
 * MaiBot AI Bridge —— Forge 1.20.1 客户端模组入口。
 *
 * <p>模组本身不注册任何方块、物品或网络包，它只做三件事：</p>
 * <ol>
 *   <li>把客户端的「感知」——聊天、状态快照、附近实体/方块、游戏事件——上报给 MaiBot 插件；</li>
 *   <li>接收 MaiBot 下发的「动作」指令，在本客户端的玩家实体上执行；</li>
 *   <li>把执行结果回传，形成「思考 → 行动 → 观察」的闭环。</li>
 * </ol>
 *
 * <p>因为完全是客户端行为，所以这个模组可以装在单机存档里，也可以装在自己电脑上连别人的服务器，
 * 服务器侧不需要安装任何东西。</p>
 */
@Mod(McAiBridge.MODID)
public class McAiBridge {

    /** 模组 ID，必须与 META-INF/mods.toml 中的 modId 以及 gradle.properties 中的 mod_id 一致。 */
    public static final String MODID = "mcai_bridge";

    public static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Forge 47.4+ 的写法：FML 会把 {@link FMLJavaModLoadingContext} 注入进来。
     */
    public McAiBridge(final FMLJavaModLoadingContext context) {
        this.init(context);
    }

    /**
     * Forge 47.0–47.3 的写法：那个年代的 FML 只认无参构造函数，上下文要自己取。
     *
     * <p>两个构造函数同时存在是刻意的。47.4 及以后的 {@code FMLModContainer} 会优先尝试带参构造，
     * 找不到才回退到无参；而 47.0–47.3 只会找无参构造。所以「两个都提供」能让同一个 jar
     * 覆盖整个 47.x —— 这与 {@code mods.toml} 里声明的 {@code loaderVersion="[47,)"} 是相符的。
     * 只写带参构造会让模组在 47.2 / 47.3 的整合包里直接加载失败。</p>
     */
    @SuppressWarnings("deprecation")
    public McAiBridge() {
        this(FMLJavaModLoadingContext.get());
    }

    private void init(final FMLJavaModLoadingContext context) {
        final IEventBus modEventBus = context.getModEventBus();

        // 配置文件：config/mcai_bridge-client.toml
        context.registerConfig(ModConfig.Type.CLIENT, BridgeConfig.SPEC);

        // 只在客户端环境初始化桥接逻辑；本模组标记了 clientSideOnly=true，
        // 但保留这段判断可以让它在任何环境下都不会抛异常。
        if (FMLEnvironment.dist == Dist.CLIENT) {
            modEventBus.addListener(this::onClientSetup);
            MinecraftForge.EVENT_BUS.register(ClientBridge.get());
        } else {
            LOGGER.warn("[MaiBot Bridge] 检测到非客户端环境，桥接功能已禁用。");
        }
    }

    private void onClientSetup(final FMLClientSetupEvent event) {
        LOGGER.info("[MaiBot Bridge] 客户端初始化完成，等待进入世界后连接 MaiBot。");
    }
}
