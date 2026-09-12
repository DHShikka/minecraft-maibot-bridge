package com.mcai.bridge.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.Locale;

/**
 * 对**其它模组**的联动。全部走「软依赖」：没装那些模组时，这里的每个方法都只是返回空/false，
 * 不会崩、也不需要它们在编译期存在。
 *
 * <h2>为什么不去调它们的 API</h2>
 *
 * <p>JEI / 永恒枪械工艺(TaCZ) / 机械动力(Create) 的 API 都要在编译期依赖它们的 jar，
 * 而这是个「谁装谁用」的客户端模组 —— 加三个 hard dependency 会让没装的人开不了游戏。
 * 所以：</p>
 *
 * <ul>
 *   <li><b>物品/方块的名字解析</b>不经过任何模组：直接翻注册表，按 <b>本地化显示名</b>
 *       （中文！）和 id 一起匹配。模组物品本来就能这样找到，比挂 JEI 更通用。</li>
 *   <li><b>按键</b>（枪械换弹那种）走 {@code KeyMapping} 反射：把别的模组注册的按键找出来按下。</li>
 *   <li><b>配方</b>走原版 {@code RecipeManager} —— 它本来就装着所有模组的配方
 *       （Create 的粉碎/混合也是注册在这儿的自定义类型）。</li>
 * </ul>
 */
public final class ModHooks {

    private ModHooks() {
    }

    // ------------------------------------------------------------ 模组是否装了

    /** 某个模组 id 在不在。用于「装了才启用某功能」，也用于把环境告诉 AI。 */
    public static boolean isLoaded(final String modId) {
        try {
            final Class<?> modList = Class.forName("net.minecraftforge.fml.ModList");
            final Object list = modList.getMethod("get").invoke(null);
            final Object loaded = modList.getMethod("isLoaded", String.class).invoke(list, modId);
            return loaded instanceof final Boolean b && b;
        } catch (final Throwable t) {
            return false;
        }
    }

    /** 把「装了哪些相关模组」报给 AI —— 它得先知道有什么工具，才谈得上用。 */
    public static JsonObject modsJson() {
        final JsonObject o = new JsonObject();
        o.addProperty("jei", isLoaded("jei"));
        o.addProperty("tacz", isLoaded("tacz"));
        o.addProperty("create", isLoaded("create"));
        o.addProperty("baritone", isLoaded("baritone") || isLoaded("baritoe"));
        o.addProperty("forge", true);
        return o;
    }

    // ---------------------------------------------------------------- 枪械

    /** 这个物品是不是枪（永恒枪械工艺的枪，或名字里带 gun/rifle/pistol 的）。 */
    public static boolean isGun(final ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        final ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id == null) {
            return false;
        }
        if ("tacz".equals(id.getNamespace())) {
            return true;
        }
        final String path = id.getPath().toLowerCase(Locale.ROOT);
        return path.contains("gun") || path.contains("rifle") || path.contains("pistol")
                || path.contains("shotgun") || path.contains("smg") || path.contains("sniper");
    }

    /**
     * 从枪的 NBT 里找「弹药」数字。
     *
     * <p>TaCZ 把当前弹匣/备弹存在物品 NBT 里，但标签名是它内部的事 —— 与其写死，
     * 不如把所有像「弹药」的数字标签都列出来（名字里带 ammo/bullet/count/mag 的优先）。
     * 报给 AI 的是「看到的事实」，而不是一个猜出来的语义。</p>
     */
    public static JsonObject gunInfo(final ItemStack stack) {
        final JsonObject o = new JsonObject();
        if (!isGun(stack)) {
            return null;
        }
        o.addProperty("item", GameUtils.itemId(stack));
        o.addProperty("name", GameUtils.safeItemName(stack));
        final CompoundTag tag = stack.getTag();
        if (tag == null || tag.isEmpty()) {
            return o;
        }
        final JsonArray numbers = new JsonArray();
        for (final String key : tag.getAllKeys()) {
            final Tag value = tag.get(key);
            if (value == null) {
                continue;
            }
            final byte type = value.getId();
            if (type == Tag.TAG_INT || type == Tag.TAG_BYTE || type == Tag.TAG_SHORT) {
                final JsonObject e = new JsonObject();
                e.addProperty("key", key);
                e.addProperty("value", tag.getInt(key));
                numbers.add(e);
            } else if (type == Tag.TAG_COMPOUND) {
                // TaCZ 的弹药常常嵌在子标签里（例如 {"Ammo": {"Count": 30}}）
                final CompoundTag sub = tag.getCompound(key);
                for (final String k2 : sub.getAllKeys()) {
                    final Tag subValue = sub.get(k2);
                    if (subValue != null && subValue.getId() == Tag.TAG_INT) {
                        final JsonObject e = new JsonObject();
                        e.addProperty("key", key + "." + k2);
                        e.addProperty("value", sub.getInt(k2));
                        numbers.add(e);
                    }
                }
            }
        }
        o.add("numbers", numbers);
        return o;
    }

    /**
     * 弹匣里那些标签叫什么，按优先级排。
     *
     * <p>这几个名字是从 TaCZ 的字节码里挖出来的（{@code GunItemDataAccessor} 的常量
     * {@code GunCurrentAmmoCount} / {@code AmmoCount} / {@code CurrentAmmoCount}），
     * 真机上给一把 AK 换完弹，NBT 里出现的正是 {@code GunCurrentAmmoCount=29}。</p>
     */
    private static final String[] MAGAZINE_KEYS = {
        "GunCurrentAmmoCount", "CurrentAmmoCount", "GunAmmoCount", "AmmoCount", "CurrentAmmo"
    };

    /** 弹匣里还剩几发（不含膛内那一发）。不是枪、或者没这个标签就返回 0。 */
    public static int gunMagazine(final ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0;
        }
        final CompoundTag tag = stack.getTag();
        if (tag == null || tag.isEmpty()) {
            return 0;
        }
        for (final String key : MAGAZINE_KEYS) {
            if (tag.contains(key, Tag.TAG_ANY_NUMERIC)) {
                return Math.max(0, tag.getInt(key));
            }
        }
        return 0;
    }

    /**
     * 枪里**已经压好的**子弹总数 = 弹匣 + 膛内那一发。
     *
     * <p>这一项以前是靠「把所有名字里带 ammo/count/bullet 的数字标签加起来」猜的 ——
     * 猜出来的数会被 {@code AttachmentLock} 这类无关标签污染，也说不清弹匣到底几发。
     * 现在按确定的名字读。</p>
     */
    public static int gunLoaded(final ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0;
        }
        final CompoundTag tag = stack.getTag();
        if (tag == null || tag.isEmpty()) {
            return 0;
        }
        final int mag = gunMagazine(stack);
        return mag + (tag.getBoolean("HasBulletInBarrel") ? 1 : 0);
    }

    // ------------------------------------------------------------ 别的模组的按键

    /**
     * 当前注册的所有按键映射（原版 + 所有模组的）。
     *
     * <p><b>为什么不反射 {@code KeyMapping.ALL}</b>：运行时游戏类的字段名是 SRG
     * （{@code f_xxxxx_}），而反射用的字符串**不会被 Forge 的重映射器改写** ——
     * 按官方名 {@code ALL} 去找必然找不到。真机症状就是换弹一直报
     * 「没拿到模组的按键映射」，连诊断用的按键表也只会返回「拿不到按键表」。</p>
     *
     * <p>{@code Options.keyMappings} 是**公开字段**，编译期引用会被正常重映射成 SRG，
     * 所以拿到的就是全量列表 —— 不用反射，也就没有名字对不上的问题。</p>
     */
    public static KeyMapping[] allKeyMappings() {
        try {
            final Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.options == null || mc.options.keyMappings == null) {
                return new KeyMapping[0];
            }
            return mc.options.keyMappings;
        } catch (final Throwable t) {
            return new KeyMapping[0];
        }
    }

    /**
     * 按名字找出**所有**匹配的按键。
     *
     * <p>为什么不是一个就够：TaCZ 在按键表里同时注册了 {@code key.tacz.shoot} 和
     * {@code key.tacz.shoot.desc}（真机日志里就是这么显示的）。到底哪一个对象才是它内部
     * 轮询的那个，从外面看不出来 —— 那就**全都按下去**，哪个是真的都会响。</p>
     *
     * <p>关键词按顺序匹配：前一个词找遍了才轮到下一个（这样 "reload" 会先命中换弹键，
     * 而不是被兜底的 "tacz" 抢成开火键）。两轮扫描：先跳过 {@code .desc} 这类「说明用」的
     * 影子按键，找不到才把它们也算上。</p>
     */
    public static java.util.List<KeyMapping> findKeyMappings(final String... keywords) {
        final KeyMapping[] all = allKeyMappings();
        for (final boolean skipDesc : new boolean[] {true, false}) {
            for (final String keyword : keywords) {
                final String needle = keyword.toLowerCase(Locale.ROOT);
                final java.util.List<KeyMapping> hits = new java.util.ArrayList<>();
                for (final KeyMapping mapping : all) {
                    if (mapping == null) {
                        continue;
                    }
                    final String name = (mapping.getName() + " " + mapping.getCategory()).toLowerCase(Locale.ROOT);
                    if (!name.contains(needle)) {
                        continue;
                    }
                    if (skipDesc && name.contains(".desc")) {
                        continue;
                    }
                    hits.add(mapping);
                }
                if (!hits.isEmpty()) {
                    return hits;
                }
            }
        }
        return java.util.List.of();
    }

    /**
     * 按名字找一个别的模组注册的按键（取优先级最高的那个）。要「全都按」就用
     * {@link #findKeyMappings}。
     */
    public static KeyMapping findKeyMapping(final String... keywords) {
        final java.util.List<KeyMapping> hits = findKeyMappings(keywords);
        return hits.isEmpty() ? null : hits.get(0);
    }

    /** 找换弹键：不同版本的叫法不一样，多给几个关键词。 */
    public static KeyMapping reloadKey() {
        return findKeyMapping("key.tacz.reload", "reload", "换弹", "装填", "tacz");
    }

    /** 所有像「换弹」的键。 */
    public static java.util.List<KeyMapping> reloadKeys() {
        return findKeyMappings("key.tacz.reload", "reload", "换弹", "装填", "tacz");
    }

    /**
     * 找**开火**键（可能不止一个，全部返回）。
     *
     * <p>这一条是反编译 TaCZ 才弄明白的（{@code com.tacz.guns.client.input.ShootKey}）：
     * 它的开火**没有任何按键事件监听**，而是在 {@code TickEvent.ClientTickEvent} 里
     * 每 tick 轮询 {@code SHOOT_KEY.isDown()}，看到按下就调
     * {@code IClientPlayerGunOperator.shoot()}。</p>
     *
     * <p>所以「按开火键」对它是无效的 —— {@code SHOOT_KEY} 是模组自己注册的另一个
     * {@code KeyMapping}（默认鼠标左键），必须把**它这个对象**的按下状态改掉。
     * 真机症状：{@code fired:true} 但 {@code ammoUsed:0}，弹匣数字一动不动。</p>
     */
    public static java.util.List<KeyMapping> shootKeys() {
        final java.util.List<KeyMapping> hits =
                findKeyMappings("key.tacz.shoot", "shoot", "开火", "射击", "fire");
        if (!hits.isEmpty()) {
            return hits;
        }
        // 找不到就用原版攻击键：有些枪械模组直接复用左键
        final Minecraft mc = Minecraft.getInstance();
        return mc == null ? java.util.List.of() : java.util.List.of(mc.options.keyAttack);
    }

    /**
     * 按住/松开某个键。
     *
     * <p>先直接改**这个映射对象**的状态（{@code setDown}）—— 这是最确定的：
     * 模组在 tick 里轮询的往往就是它自己那个 {@code KeyMapping} 的 {@code isDown()}，
     * 而 {@code KeyMapping.set(key, down)} 是按「物理键」找映射的，键位冲突时未必落到它头上。</p>
     */
    public static void holdKey(final KeyMapping mapping, final boolean down) {
        if (mapping == null) {
            return;
        }
        try {
            mapping.setDown(down);
        } catch (final Throwable ignored) {
            // 忽略
        }
        try {
            KeyMapping.set(mapping.getKey(), down);
            // 有些模组是监听「点击」而不是「按住」，两个都发一遍
            if (down) {
                KeyMapping.click(mapping.getKey());
            }
        } catch (final Throwable ignored) {
            // 按键被别的模组抢了/已注销：忽略，不让整个动作失败
        }
    }

    /**
     * **发一个真正的按键事件**（Forge 的 {@code InputEvent.Key}），不是只改 KeyMapping 状态。
     *
     * <p>这一条是看 TaCZ 源码才明白的（{@code com.tacz.guns.client.input.ReloadKey}）：
     * 它的换弹是 {@code @SubscribeEvent onReloadPress(InputEvent.Key)} —— 监听的是**原始按键事件**，
     * 判断 {@code event.getAction() == GLFW_PRESS && RELOAD_KEY.matches(key, scanCode)}，
     * 然后直接调 {@code IClientPlayerGunOperator.reload()}。</p>
     *
     * <p>而 {@code KeyMapping.set}/{@code KeyMapping.click} 只改按键的「按下状态」，
     * **不会产生 InputEvent** —— 所以对这类模组完全无效（真机现象：换弹报「找不到换弹键」，
     * 换成按状态也没反应）。自己往事件总线上 post 一个才是有效的。</p>
     */
    public static void pressKeyEvent(final KeyMapping mapping) {
        if (mapping == null) {
            return;
        }
        try {
            postKeyEvent(mapping.getKey().getValue());
        } catch (final Throwable ignored) {
            // 忽略
        }
    }

    /** 往 Forge 事件总线上发一对「按下 + 抬起」（返回是否发出去了）。 */
    public static boolean postKeyEvent(final int keyCode) {
        try {
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(
                    new net.minecraftforge.client.event.InputEvent.Key(
                            keyCode, 0, org.lwjgl.glfw.GLFW.GLFW_PRESS, 0));
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(
                    new net.minecraftforge.client.event.InputEvent.Key(
                            keyCode, 0, org.lwjgl.glfw.GLFW.GLFW_RELEASE, 0));
            return true;
        } catch (final Throwable t) {
            return false;
        }
    }

    /**
     * 触发「换弹」。两条路一起走，因为真机上两条都可能不灵：
     *
     * <ol>
     *   <li>能反射到按键映射（{@code KeyMapping.ALL}）就用它的键值；</li>
     *   <li>反射不到就**直接对 R 键发事件** —— TaCZ 的判断是
     *       {@code RELOAD_KEY.matches(key, scanCode)}，比的是**物理键值**
     *       （默认 R = GLFW_KEY_R = 82），所以即使拿不到它的 KeyMapping 对象，
     *       只要往事件总线上 post 对键值的事件，它照样会响。</li>
     * </ol>
     *
     * @return 用了哪个键值（-1 = 一个都没发出去）
     */
    public static int triggerReload() {
        final KeyMapping mapping = reloadKey();
        if (mapping != null) {
            pressKeyEvent(mapping);
            return mapping.getKey().getValue();
        }
        final int r = org.lwjgl.glfw.GLFW.GLFW_KEY_R;
        return postKeyEvent(r) ? r : -1;
    }

    /** 当前按键表里都有哪些键（找不到某个键时报给 AI，便于排查）。 */
    public static String listKeyNames(final int limit) {
        final KeyMapping[] all = allKeyMappings();
        if (all.length == 0) {
            return "（拿不到按键表）";
        }
        final StringBuilder sb = new StringBuilder();
        int n = 0;
        for (final KeyMapping mapping : all) {
            if (mapping == null) {
                continue;
            }
            if (n++ > 0) {
                sb.append('、');
            }
            sb.append(mapping.getName()).append('=').append(mapping.getKey().getName());
            if (n >= limit) {
                break;
            }
        }
        return n == 0 ? "（按键表是空的）" : sb.toString();
    }

    /** 松掉我们按过的一切键（动作结束/取消时用）。 */
    public static void releaseAll() {
        try {
            KeyMapping.releaseAll();
        } catch (final Throwable ignored) {
            // 忽略
        }
    }

    // ------------------------------------------- 直接调枪械模组的 API（TaCZ）

    /**
     * 为什么绕开按键、直接调 API —— TaCZ 的开火前有一道
     * {@code InputExtraCheck.isInGame()} 闸门，反编译出来是这样：
     *
     * <pre>
     *   if (mc.overlay != null) return false;
     *   if (mc.screen  != null) return false;
     *   if (!mc.mouseHandler.isMouseGrabbed()) return false;   // ← 托管时鼠标是放开的
     *   return mc.isWindowActive();                            // ← 还要窗口在前台
     * </pre>
     *
     * <p>而「AI 托管」这个功能本身就要 {@code releaseMouse()}（玩家才能切出去），
     * 于是我们按下的开火键**永远过不了这道检查** —— 真机现象：弹匣压满 29 发、
     * 键也按对了（{@code key.tacz.shoot.desc}），就是 {@code ammoUsed:0}。</p>
     *
     * <p>所以改走它自己的接口：{@code IClientPlayerGunOperator.fromLocalPlayer(player)}
     * 之后直接 {@code shoot()} / {@code reload()} —— 这正是 TaCZ 开火键按下后调的东西，
     * 只是跳过了那道输入闸门。**用反射拿**，没装 TaCZ 时这些方法一律返回 null/false，
     * 不构成编译期依赖。</p>
     */
    private static Class<?> taczOperatorClass() {
        try {
            return Class.forName("com.tacz.guns.api.client.gameplay.IClientPlayerGunOperator");
        } catch (final Throwable t) {
            return null;
        }
    }

    private static Object taczOperator(final LocalPlayer player) {
        final Class<?> cls = taczOperatorClass();
        if (cls == null || player == null) {
            return null;
        }
        try {
            return cls.getMethod("fromLocalPlayer", LocalPlayer.class).invoke(null, player);
        } catch (final Throwable t) {
            return null;
        }
    }

    /** 枪械模组的开枪接口在不在（拿它判断该走 API 还是退回按键）。 */
    public static boolean taczReady(final LocalPlayer player) {
        return taczOperator(player) != null;
    }

    /** 开一枪。返回 TaCZ 的结果名（{@code SUCCESS} / {@code COOL_DOWN} / {@code NOT_DRAW}
     *  / {@code IS_RELOADING} / {@code NEED_BOLT} …），没法调就返回 null。 */
    public static String taczShoot(final LocalPlayer player) {
        return taczInvoke(player, "shoot");
    }

    /** 换弹。 */
    public static String taczReload(final LocalPlayer player) {
        return taczInvoke(player, "reload");
    }

    /** 拉栓（{@code NEED_BOLT} 时用）。 */
    public static String taczBolt(final LocalPlayer player) {
        return taczInvoke(player, "bolt");
    }

    /**
     * **右键瞄准（ADS）**。
     *
     * <p>就是玩家按住右键的那个举枪瞄准 —— TaCZ 的接口里叫 {@code aim(boolean)}。
     * 和开火一样走 API 而不是按键：它的输入前有 {@code InputExtraCheck.isInGame()}
     * 那道闸门（要求鼠标锁在窗口里），而 AI 托管时鼠标是放开的，按右键根本进不去。</p>
     *
     * @return 调成功没有（没装 TaCZ 就是 false）
     */
    /**
     * 往 Forge 事件总线上发一个**鼠标按键事件**（按下+抬起）。
     *
     * <p>换弹那次是靠发 {@code InputEvent.Key} 解决的，开镜是同一类问题：
     * TaCZ 的 {@code AimKey.onAimPress} 监听的是 {@code InputEvent.MouseButton.Post} ——
     * 就算我们把 {@code AIM_KEY} 按住、也调了 {@code aim(true)}，**没有鼠标事件它这一步就不跑**，
     * 于是 {@code clientAimingProgress} 不涨、画面永远不透镜（真机现象：效率确实变好了、
     * 但画面上没有举镜变焦）。</p>
     *
     * @param button GLFW 的鼠标键号（左键 0、右键 1、中键 2）
     */
    public static boolean postMouseEvent(final int button, final boolean press) {
        try {
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(
                    new net.minecraftforge.client.event.InputEvent.MouseButton.Post(
                            button, press ? org.lwjgl.glfw.GLFW.GLFW_PRESS
                                          : org.lwjgl.glfw.GLFW.GLFW_RELEASE, 0));
            return true;
        } catch (final Throwable t) {
            return false;
        }
    }

    /** 右键的 GLFW 键号。 */
    public static final int MOUSE_RIGHT = 1;

    public static boolean taczAim(final LocalPlayer player, final boolean aiming) {
        // **必须同时把它的瞄准键按住，而且瞄准期间要把鼠标临时抓回窗口。**
        //
        // 反编译 AimKey 才知道它为什么这么难搞：
        //   开镜：onAimPress(InputEvent$MouseButton$Post) —— 要**真正的鼠标按键事件**，
        //         而且第一句就是 InputExtraCheck.isInGame()（要求鼠标锁在窗口里 + 窗口在前台）；
        //   松开：onAimHoldingPreInput(ClientTickEvent) —— 每 tick 看 AIM_KEY 没按住就 aim(false)。
        // 而「AI 托管」的设计就是把鼠标放开，于是这两条路全被闸门挡死 —— 光改状态不产生鼠标
        // 事件，画面上永远不开镜（真机现象：调了 aim(true)、键也按住了，游戏里就是不举镜）。
        //
        // 所以这里做一个**临时抓回鼠标**：AI 要开镜的这段时间鼠标是锁住的，收镜时还给玩家。
        // 代价很小（开镜通常几百毫秒到几秒），换来的是真的能看到/生效的开镜。
        final Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.mouseHandler != null) {
            try {
                if (aiming && !mc.mouseHandler.isMouseGrabbed() && mc.screen == null) {
                    mc.mouseHandler.grabMouse();
                }
            } catch (final Throwable ignored) {
                // 忽略
            }
        }
        holdKey(taczAimKey(), aiming);
        // **再补一个真正的鼠标按键事件** —— TaCZ 的开镜渲染（clientAimingProgress）
        // 只有走 onAimPress 才会推进，光有状态没有事件就不透镜。见 postMouseEvent 的注释。
        postMouseEvent(MOUSE_RIGHT, aiming);
        if (!aiming) {
            // 收镜了：把鼠标还给玩家（托管时本来就该是放开的）
            releaseAimMouse();
        }
        final Class<?> cls = taczOperatorClass();
        final Object op = taczOperator(player);
        if (cls == null || op == null) {
            return false;
        }
        try {
            cls.getMethod("aim", boolean.class).invoke(op, aiming);
            return true;
        } catch (final Throwable t) {
            return false;
        }
    }

    /**
     * 收镜后把鼠标还回去（AI 托管时本来就该是放开的）。
     *
     * <p>{@link Takeover#tick} 每 tick 也会把鼠标放开，所以这里只是让它立刻恢复，
     * 不用等下一 tick。</p>
     */
    public static void releaseAimMouse() {
        final Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.mouseHandler == null) {
            return;
        }
        try {
            if (Takeover.isActive() && mc.mouseHandler.isMouseGrabbed() && mc.screen == null) {
                mc.mouseHandler.releaseMouse();
            }
        } catch (final Throwable ignored) {
            // 忽略
        }
    }

    /** TaCZ 的瞄准键（右键）。 */
    public static KeyMapping taczAimKey() {
        return findKeyMapping("key.tacz.aim.desc", "key.tacz.aim", "瞄准");
    }

    /** 现在是不是在瞄准状态（给结果里报一下，便于确认 ADS 真开了）。 */
    public static String taczAimState(final LocalPlayer player) {
        final String out = taczInvoke(player, "isAim");
        return out == null ? "" : out;
    }

    /** 把枪掏出来（切枪后要先有这个动作才打得出子弹）。 */
    public static boolean taczDraw(final LocalPlayer player, final ItemStack stack) {
        final Class<?> cls = taczOperatorClass();
        final Object op = taczOperator(player);
        if (cls == null || op == null || stack == null) {
            return false;
        }
        try {
            cls.getMethod("draw", ItemStack.class).invoke(op, stack);
            return true;
        } catch (final Throwable t) {
            return false;
        }
    }

    private static String taczInvoke(final LocalPlayer player, final String method) {
        final Class<?> cls = taczOperatorClass();
        final Object op = taczOperator(player);
        if (cls == null || op == null) {
            return null;
        }
        try {
            final Object out = cls.getMethod(method).invoke(op);
            return out == null ? "OK" : String.valueOf(out);
        } catch (final Throwable t) {
            return null;
        }
    }

    /** 现在手上那件东西的「模组信息」，给 AI 看。没有就返回 null。 */
    public static JsonObject heldModInfo(final Minecraft mc) {
        if (mc.player == null) {
            return null;
        }
        final ItemStack held = mc.player.getMainHandItem();
        if (isGun(held)) {
            return gunInfo(held);
        }
        return null;
    }
}
