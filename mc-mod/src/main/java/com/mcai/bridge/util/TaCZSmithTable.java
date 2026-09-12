package com.mcai.bridge.util;

import com.mcai.bridge.McAiBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 永恒枪械工坊（TaCZ）的**枪械工作台**。
 *
 * <p>为什么单独一个类、而且全靠反射：TaCZ 不是我们的编译期依赖。没装它的时候，
 * 这里所有方法都安静地返回 null / 空表，不会把模组带崩。</p>
 *
 * <h2>为什么不能像普通工作台那样「点槽位」</h2>
 *
 * <p>反编译 {@code GunSmithTableScreen / GunSmithTableMenu} 才看明白：枪械工作台
 * <b>不是</b>把材料摆进 3×3 那种玩法。它的流程是——</p>
 * <ol>
 *   <li>界面列出「这台机器能做哪些东西」（按分类分页的按钮，配方来自数据包）；</li>
 *   <li>玩家选中一个配方，界面自己算「背包里的材料够不够」（{@code playerIngredientCount}）；</li>
 *   <li>点制作按钮 → 客户端发 {@code ClientMessageCraft(recipeId, menuId)}；</li>
 *   <li>服务端 {@code GunSmithTableMenu.doCraft()} 核对配方，<b>直接从玩家背包扣材料</b>，
 *       把成品塞回背包。</li>
 * </ol>
 *
 * <p>也就是说「使用工作台」= <b>报一个配方 id</b>，材料在背包里就行，压根没有槽位可点
 * （{@code GunSmithTableMenu} 的槽位只有玩家自己那 36 格）。所以这里走的是和界面
 * 按钮完全相同的那条路：直接发同一个网络包。</p>
 *
 * <p>已知成员名来自 {@code javap}：</p>
 * <ul>
 *   <li>{@code com.tacz.guns.init.ModRecipe.GUN_SMITH_TABLE_CRAFTING}（{@code RecipeType}）</li>
 *   <li>{@code com.tacz.guns.crafting.GunSmithTableRecipe}：{@code getInputs() / getOutput()
 *       / getTab() / getId()}</li>
 *   <li>{@code com.tacz.guns.crafting.GunSmithTableIngredient}：{@code getIngredient()
 *       / getCount()}</li>
 *   <li>{@code com.tacz.guns.network.NetworkHandler.CHANNEL} +
 *       {@code com.tacz.guns.network.message.ClientMessageCraft(ResourceLocation, int)}</li>
 * </ul>
 */
public final class TaCZSmithTable {

    /** 服务端那个菜单类的名字：用来确认「现在开着的就是枪械工作台」。 */
    private static final String MENU_CLASS = "com.tacz.guns.inventory.GunSmithTableMenu";
    /** 客户端界面类名（报给 AI 看，让它知道界面确实开了）。 */
    public static final String SCREEN_CLASS = "com.tacz.guns.client.gui.GunSmithTableScreen";

    private static boolean typeTried;
    private static RecipeType<?> cachedType;

    private TaCZSmithTable() {
    }

    // ------------------------------------------------------------------ 探测

    /** TaCZ 的枪械工作台配方类型（没装 TaCZ 时为 null）。 */
    private static RecipeType<?> recipeType() {
        if (typeTried) {
            return cachedType;
        }
        typeTried = true;
        try {
            final Class<?> modRecipe = Class.forName("com.tacz.guns.init.ModRecipe");
            final Field field = modRecipe.getField("GUN_SMITH_TABLE_CRAFTING");
            final Object registryObject = field.get(null);
            final Object type = registryObject == null ? null
                    : registryObject.getClass().getMethod("get").invoke(registryObject);
            if (type instanceof final RecipeType<?> rt) {
                cachedType = rt;
            }
        } catch (final Throwable t) {
            McAiBridge.LOGGER.debug("[MaiBot Bridge] 没找到 TaCZ 枪械工作台配方类型: {}", t.toString());
        }
        return cachedType;
    }

    /** 这个整合包有没有枪械工作台可用。 */
    public static boolean available() {
        return recipeType() != null;
    }

    // ------------------------------------------------------------------ 配方

    /** 一条枪械工作台配方，只留我们报给 AI 需要的字段。 */
    public static final class RecipeInfo {
        public final String id;
        public final String tab;
        public final ItemStack output;
        public final List<Ing> inputs;

        RecipeInfo(final String id, final String tab, final ItemStack output, final List<Ing> inputs) {
            this.id = id;
            this.tab = tab;
            this.output = output;
            this.inputs = inputs;
        }

        /** 成品显示名（例如「AK47 突击步枪」）。 */
        public String outputName() {
            return output.isEmpty() ? id : plain(output.getHoverName().getString());
        }
    }

    /** 一条配方里的材料要求。 */
    public static final class Ing {
        public final Ingredient ingredient;
        public final int count;

        Ing(final Ingredient ingredient, final int count) {
            this.ingredient = ingredient;
            this.count = count;
        }

        /** 材料显示名（取第一个可用物品的名字）。 */
        public String name() {
            final ItemStack[] items = ingredient.getItems();
            return items.length == 0 ? "未知材料" : plain(items[0].getHoverName().getString());
        }

        /** 材料 id（取第一个可用物品）。 */
        public String itemId() {
            final ItemStack[] items = ingredient.getItems();
            return items.length == 0 ? "" : GameUtils.itemId(items[0]);
        }
    }

    /**
     * 列出所有枪械工作台配方。
     *
     * <p>不需要站在工作台旁边，也不需要界面开着 —— 配方表就在客户端的
     * {@code RecipeManager} 里（数据包同步过来的），这样 AI 可以在出门采材料之前
     * 先问「做这把枪到底要什么」。</p>
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static List<RecipeInfo> recipes(final Level level) {
        final List<RecipeInfo> out = new ArrayList<>();
        final RecipeType<?> type = recipeType();
        if (type == null || level == null) {
            return out;
        }
        List<?> raw;
        try {
            raw = (List<?>) level.getRecipeManager().getAllRecipesFor((RecipeType) type);
        } catch (final Throwable t) {
            McAiBridge.LOGGER.warn("[MaiBot Bridge] 读枪械工作台配方失败: {}", t.toString());
            return out;
        }
        if (raw == null) {
            return out;
        }
        for (final Object recipe : raw) {
            final RecipeInfo info = describe(level, recipe);
            if (info != null) {
                out.add(info);
            }
        }
        return out;
    }

    private static RecipeInfo describe(final Level level, final Object recipe) {
        final String id = asString(firstNonNull(call(recipe, "getId"), call(recipe, "m_6423_")));
        if (id == null || id.isBlank()) {
            return null;
        }
        final String tab = asString(call(recipe, "getTab"));
        final List<Ing> inputs = new ArrayList<>();
        final Object rawInputs = call(recipe, "getInputs");
        if (rawInputs instanceof final List<?> list) {
            for (final Object one : list) {
                final Object ingredient = call(one, "getIngredient");
                if (ingredient instanceof final Ingredient ing) {
                    inputs.add(new Ing(ing, Math.max(1, asInt(call(one, "getCount"), 1))));
                }
            }
        }
        return new RecipeInfo(id, tab, outputOf(level, recipe), inputs);
    }

    /**
     * 取配方成品。
     *
     * <p>优先 {@code getOutput()}（TaCZ 自己加的方法，枪的成品带 NBT）；
     * 万一将来改名了，退回原版那条 {@code getResultItem(RegistryAccess)}。</p>
     */
    private static ItemStack outputOf(final Level level, final Object recipe) {
        final Object direct = call(recipe, "getOutput");
        if (direct instanceof final ItemStack stack && !stack.isEmpty()) {
            return stack;
        }
        final Object viaVanilla = firstNonNull(
                call(recipe, "getResultItem", level.registryAccess()),
                call(recipe, "m_8043_", level.registryAccess()));
        return viaVanilla instanceof final ItemStack stack ? stack : ItemStack.EMPTY;
    }

    // ------------------------------------------------------------ 当前界面

    /** 当前开着的枪械工作台（没开就是 null）。 */
    public static OpenTable open() {
        final Minecraft mc = Minecraft.getInstance();
        if (mc == null || !(mc.screen instanceof final AbstractContainerScreen<?> cs)) {
            return null;
        }
        final AbstractContainerMenu menu = cs.getMenu();
        if (menu == null || !MENU_CLASS.equals(menu.getClass().getName())) {
            return null;
        }
        final ResourceLocation blockId = call(menu, "getBlockId") instanceof final ResourceLocation rl ? rl : null;
        return new OpenTable(menu.containerId, blockId == null ? null : blockId.toString(),
                mc.screen.getClass().getName());
    }

    /** 「现在开着的枪械工作台」这点信息：菜单号 + 是哪台机器。 */
    public static final class OpenTable {
        public final int menuId;
        public final String blockId;
        public final String screenClass;

        OpenTable(final int menuId, final String blockId, final String screenClass) {
            this.menuId = menuId;
            this.blockId = blockId;
            this.screenClass = screenClass;
        }
    }

    // ---------------------------------------------------------------- 制作

    /**
     * 发一个制作请求（和界面上的制作按钮发的是同一个包）。
     *
     * <p>服务端会核对：{@code menuId} 得是它当前给这个玩家开的那个菜单，而且必须是
     * 枪械工作台 —— 所以界面必须真的开着（先右键工作台）。</p>
     *
     * @return 发出去了返回 null，否则返回人话原因
     */
    public static String craft(final ResourceLocation recipeId, final int menuId) {
        try {
            final Class<?> handler = Class.forName("com.tacz.guns.network.NetworkHandler");
            final Object channel = handler.getField("CHANNEL").get(null);
            if (channel == null) {
                return "TaCZ 的网络通道还没初始化好，稍后再试。";
            }
            final Class<?> packetClass = Class.forName("com.tacz.guns.network.message.ClientMessageCraft");
            final Object packet = packetClass
                    .getConstructor(ResourceLocation.class, int.class)
                    .newInstance(recipeId, menuId);
            channel.getClass().getMethod("sendToServer", Object.class).invoke(channel, packet);
            return null;
        } catch (final Throwable t) {
            McAiBridge.LOGGER.warn("[MaiBot Bridge] 发送枪械工作台制作请求失败: {}", t.toString());
            return "发送制作请求失败：" + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : " - " + t.getMessage());
        }
    }

    // ------------------------------------------------------------ 反射小工具

    private static Object call(final Object target, final String name) {
        return call(target, name, new Object[0]);
    }

    private static Object call(final Object target, final String name, final Object arg) {
        return call(target, name, new Object[]{arg});
    }

    /**
     * 按名字调一个无参/少参方法，失败返回 null。
     *
     * <p>为什么要「多个候选名」：TaCZ 覆盖原版接口的方法在编译产物里是 SRG 名
     * （{@code getId} 变成 {@code m_6423_}），而它自己新加的方法保留源码名。
     * 两边都试一遍，改动版本时不容易碎。</p>
     */
    private static Object call(final Object target, final String name, final Object[] args) {
        if (target == null) {
            return null;
        }
        try {
            for (final Method m : target.getClass().getMethods()) {
                if (!m.getName().equals(name) || m.getParameterCount() != args.length) {
                    continue;
                }
                return m.invoke(target, args);
            }
        } catch (final Throwable ignored) {
            // 换下一个候选名
        }
        return null;
    }

    private static Object firstNonNull(final Object... values) {
        for (final Object v : values) {
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    private static String asString(final Object o) {
        return o instanceof final ResourceLocation rl ? rl.toString() : (o == null ? null : String.valueOf(o));
    }

    /** Minecraft 的旧式颜色标记（{@code §a} 这种）。 */
    private static final java.util.regex.Pattern COLOR = java.util.regex.Pattern.compile("\u00a7.");

    /**
     * 去掉显示名里的颜色代码。
     *
     * <p>枪包的物品名是带格式的（枪械工作台配方列表里真机上就是
     * 「§912 号口径霰弹」）—— 这种字符直接喂给 AI 只会浪费它的注意力。</p>
     */
    private static String plain(final String text) {
        return text == null ? null : COLOR.matcher(text).replaceAll("");
    }

    private static int asInt(final Object o, final int def) {
        return o instanceof final Number n ? n.intValue() : def;
    }
}
