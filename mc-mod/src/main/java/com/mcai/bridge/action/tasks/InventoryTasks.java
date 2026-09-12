package com.mcai.bridge.action.tasks;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mcai.bridge.action.Task;
import com.mcai.bridge.action.TaskResult;
import com.mcai.bridge.protocol.Json;
import com.mcai.bridge.snapshot.StateCollector;
import com.mcai.bridge.util.GameUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;

import java.util.Locale;

/**
 * 背包相关动作：{@code use_item} / {@code equip} / {@code drop}。
 *
 * <p>点击操作统一走 {@code MultiPlayerGameMode.handleInventoryMouseClick()}，
 * 也就是「模拟真人点背包」，这样服务端会正常校验并同步，不会出现客户端单方面改物品导致回弹。</p>
 */
public final class InventoryTasks {

    private InventoryTasks() {
    }

    // ------------------------------------------------------ 使用手持物品

    /** 参数：{@code hand} = {@code main}（默认）或 {@code off}；{@code durationMs} 控制长按（如弓、食物）。 */
    public static Task useItem(final String id, final JsonObject params, final boolean instant) {
        final String handName = Json.str(params, "hand", "main").toLowerCase(Locale.ROOT);
        final InteractionHand hand = "off".equals(handName) || "offhand".equals(handName)
                ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        final long holdMs = Json.longVal(params, "durationMs", 0L);

        if (holdMs <= 0) {
            return new Task(id, "use_item", params, 5_000L, !instant, false) {
                @Override
                protected TaskResult onTick(final Minecraft mc) {
                    final LocalPlayer player = mc.player;
                    if (player == null || mc.gameMode == null) {
                        fail("玩家或 gameMode 不存在");
                    }
                    final ItemStack stack = player.getItemInHand(hand);
                    if (stack.isEmpty()) {
                        fail("手上没有物品（" + (hand == InteractionHand.MAIN_HAND ? "主手" : "副手") + "为空）");
                    }
                    final InteractionResult result = mc.gameMode.useItem(player, hand);
                    if (result.shouldSwing()) {
                        player.swing(hand);
                    }
                    final JsonObject out = new JsonObject();
                    out.addProperty("item", GameUtils.itemId(stack));
                    out.addProperty("hand", hand == InteractionHand.MAIN_HAND ? "main" : "off");
                    out.addProperty("result", String.valueOf(result));
                    return TaskResult.success(out);
                }
            };
        }

        // 长按：例如拉弓、举盾、吃东西
        return new Task(id, "use_item", params, Math.min(holdMs + 5_000L, 60_000L), true, false) {
            private final long start = System.currentTimeMillis();
            private boolean released;

            @Override
            protected TaskResult onTick(final Minecraft mc) {
                final LocalPlayer player = mc.player;
                if (player == null || mc.gameMode == null) {
                    fail("玩家或 gameMode 不存在");
                }
                final long elapsed = System.currentTimeMillis() - start;
                if (!released) {
                    final ItemStack stack = player.getItemInHand(hand);
                    if (stack.isEmpty()) {
                        fail("手上没有物品，无法长按使用");
                    }
                    // 关键：**真的把使用键按住**。
                    // 吃东西要持续 1.6 秒、拉弓要 1 秒，而下一 tick 的
                    // Minecraft.handleKeybinds() 一旦发现「使用键没按下」就会替我们松手 ——
                    // 服务端看到的蓄力永远是 0，表现就是「动作报成功，但什么都没发生」。
                    // 真机现象：自动进食的日志刷了好几次「自动进食：牛排」，食物一颗没少；
                    // 弓那边也是一样的病（fired:true 但 ammoUsed:0）。
                    try {
                        mc.options.keyUse.setDown(true);
                    } catch (final Throwable ignored) {
                        // 忽略
                    }
                    if (elapsed == 0L || !player.isUsingItem()) {
                        final InteractionResult result = mc.gameMode.useItem(player, hand);
                        if (result.shouldSwing()) {
                            player.swing(hand);
                        }
                    }
                }
                if (elapsed >= holdMs && !released) {
                    released = true;
                    try {
                        mc.options.keyUse.setDown(false);
                    } catch (final Throwable ignored) {
                        // 忽略
                    }
                    mc.gameMode.releaseUsingItem(player);
                    final JsonObject out = new JsonObject();
                    out.addProperty("item", GameUtils.itemId(player.getItemInHand(hand)));
                    out.addProperty("heldMs", elapsed);
                    out.addProperty("note", "按住使用了 " + elapsed + " 毫秒（吃东西/拉弓这类要持续按住才生效）。");
                    return TaskResult.success(out);
                }
                return null;
            }

            @Override
            protected void onCancel(final Minecraft mc) {
                if (mc.player != null && mc.gameMode != null) {
                    try {
                        mc.options.keyUse.setDown(false);
                    } catch (final Throwable ignored) {
                        // 忽略
                    }
                    mc.gameMode.releaseUsingItem(mc.player);
                }
            }

            @Override
            public double progress() {
                return Math.min(1.0, (double) (System.currentTimeMillis() - start) / Math.max(1L, holdMs));
            }
        };
    }

    /**
     * 物品的「价值分」：越大越该留。
     *
     * <p>故意用**关键词近似**而不是完整物品表 —— 装了什么模组都能凑合判断，
     * 而且这里只需要「排序」不需要精确值。判断失误的代价不对称：
     * 把值钱的当垃圾丢了很糟，把垃圾当值钱的留着只是占地方，
     * 所以阈值默认定在 60（低于 60 才允许丢），宁可少丢。</p>
     */
    private static int itemValue(final ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0;
        }
        final String path = GameUtils.shortId(GameUtils.itemId(stack));
        if (hits(path, "netherite", "diamond", "emerald", "ancient_debris", "shulker", "totem",
                "elytra", "nether_star", "dragon_egg", "beacon", "enchanted_golden", "ender_eye",
                "ender_pearl", "blaze_rod", "ghast_tear", "wither", "trident", "heart_of_the_sea",
                "nautilus", "echo_shard", "disc_fragment")) {
            return 100;
        }
        if (hits(path, "iron", "gold", "copper", "redstone", "lapis", "quartz", "amethyst",
                "coal", "raw_", "ingot", "nugget", "gunpowder", "glowstone", "obsidian", "crying")) {
            return 60;
        }
        if (hits(path, "sword", "pickaxe", "axe", "shovel", "hoe", "helmet", "chestplate",
                "leggings", "boots", "bow", "crossbow", "arrow", "shield", "fishing_rod", "book",
                "potion", "bucket", "flint_and_steel", "shears", "torch")) {
            return 55;
        }
        if (stack.isEdible()) {
            return 45;   // 食物留着吃
        }
        if (hits(path, "dirt", "gravel", "sand", "cobblestone", "stone", "andesite", "diorite",
                "granite", "deepslate", "tuff", "calcite", "netherrack", "end_stone", "leaves",
                "sapling", "seed", "kelp", "vine", "snow", "ice", "clay", "flint", "stick",
                "rotten_flesh", "poisonous_potato", "spider_eye", "bone", "string", "feather",
                "egg", "leather", "paper", "sugar_cane", "cactus", "grass", "fern", "moss",
                "azalea", "root", "petal", "mushroom", "wheat")) {
            return 5;
        }
        return 25;
    }

    /** 物品 id 里有没有这些关键词（任一命中即可）。 */
    private static boolean hits(final String path, final String... keywords) {
        for (final String k : keywords) {
            if (path.contains(k)) {
                return true;
            }
        }
        return false;
    }

    /** 这件东西是不是盔甲（能穿在身上）。 */
    private static boolean isArmor(final ItemStack stack) {        return stack != null && !stack.isEmpty()
                && stack.getItem() instanceof net.minecraft.world.item.ArmorItem;
    }

    /** 盔甲槽的中文名（按 Inventory 索引）。 */
    private static String armorSlotName(final int invIndex) {
        return switch (invIndex) {
            case 39 -> "头盔";
            case 38 -> "胸甲";
            case 37 -> "护腿";
            case 36 -> "靴子";
            default -> "盔甲槽";
        };
    }

    /** 这件盔甲该穿在哪个槽位。 */
    private static String armorSlotOf(final ItemStack stack) {
        if (stack != null && !stack.isEmpty()
                && stack.getItem() instanceof final net.minecraft.world.item.ArmorItem armor) {
            return switch (armor.getEquipmentSlot()) {
                case HEAD -> "头盔";
                case CHEST -> "胸甲";
                case LEGS -> "护腿";
                case FEET -> "靴子";
                default -> "盔甲槽";
            };
        }
        return "盔甲槽";
    }

    // ------------------------------------------------------------ 切换物品

    /**
     * 参数（三选一）：
     * <ul>
     *   <li>{@code slot}：0-8 快捷栏槽位，直接选中</li>
     *   <li>{@code item}：物品名（支持模糊匹配，如 {@code pickaxe}、{@code iron_sword}）；
     *       在快捷栏里就直接选中，否则与当前槽位交换</li>
     *   <li>{@code offhand} + {@code item}：把物品换到副手</li>
     * </ul>
     */
    public static Task equip(final String id, final JsonObject params, final boolean instant) {
        return new Task(id, "equip", params, 5_000L, !instant, false) {
            @Override
            protected TaskResult onTick(final Minecraft mc) {
                final LocalPlayer player = mc.player;
                if (player == null || mc.gameMode == null) {
                    fail("玩家不存在");
                }
                final Inventory inv = player.getInventory();
                final JsonObject out = new JsonObject();

                // ---- 直接指定快捷栏槽位
                if (Json.has(params, "slot") && !Json.has(params, "item")) {
                    final int slot = Json.intVal(params, "slot", -1);
                    if (slot < 0 || slot > 8) {
                        fail("slot 必须是 0-8（快捷栏槽位），收到 " + slot);
                    }
                    inv.selected = slot;
                    out.addProperty("selectedSlot", slot);
                    out.add("item", StateCollector.item(inv.getSelected()));
                    return TaskResult.success(out);
                }

                final String wanted = Json.str(params, "item", "").trim();
                if (wanted.isEmpty()) {
                    fail("缺少参数：请提供 slot（0-8）或 item（物品名）");
                }

                final int found = findSlot(inv, wanted);
                if (found < 0) {
                    final JsonArray available = new JsonArray();
                    for (int i = 0; i < inv.getContainerSize(); i++) {
                        final ItemStack stack = inv.getItem(i);
                        if (!stack.isEmpty()) {
                            final JsonObject o = new JsonObject();
                            o.addProperty("slot", i);
                            o.addProperty("id", GameUtils.itemId(stack));
                            o.addProperty("count", stack.getCount());
                            available.add(o);
                        }
                    }
                    final JsonObject error = new JsonObject();
                    error.add("inventory", available);
                    fail("背包里找不到匹配「" + wanted + "」的物品。当前背包：" + Json.toCompactJson(available));
                }

                // ---- 换到副手
                if (Json.bool(params, "offhand", false)) {
                    if (found == Inventory.SLOT_OFFHAND) {
                        out.addProperty("alreadyOffhand", true);
                        out.add("item", StateCollector.item(inv.offhand.get(0)));
                        return TaskResult.success(out);
                    }
                    click(mc, player, toMenuSlot(found), 40, ClickType.SWAP);
                    out.addProperty("offhand", true);
                    out.add("item", StateCollector.item(player.getOffhandItem()));
                    return TaskResult.success(out);
                }

                // ---- 穿装备（盔甲）
                //
                // 这一段以前是坏的，真机表现就是**「无法穿戴装备」**：
                //   · 只有传了 armor=true 才进得来，而 AI 根本不知道要传这个参数；
                //   · 判据是 `found >= 36 && found <= 39`，那正是**已经穿在身上**的盔甲槽 ——
                //     也就是说只有「想穿已经穿着的盔甲」才走这里；
                //   · 动作还是 PICKUP（把盔甲抓到鼠标上），不是穿。
                // 现在：**自动识别**背包里那件是不是盔甲，然后一次 QUICK_MOVE（shift 点击），
                // 原版会自己把它放进对应的盔甲槽（头盔/胸甲/护腿/靴子各归各位）。
                final ItemStack maybeArmor = inv.getItem(found);
                if (isArmor(maybeArmor) || Json.bool(params, "armor", false)) {
                    if (found >= 36 && found <= 39) {
                        out.addProperty("alreadyWorn", true);
                        out.addProperty("armorSlot", armorSlotName(found));
                        out.add("item", StateCollector.item(maybeArmor));
                        return TaskResult.success(out);
                    }
                    click(mc, player, toMenuSlot(found), 0, ClickType.QUICK_MOVE);
                    out.addProperty("armorEquipped", true);
                    out.addProperty("armorSlot", armorSlotOf(maybeArmor));
                    out.add("item", StateCollector.item(maybeArmor));
                    out.addProperty("note", "已按 shift 点击交给原版归类到盔甲槽（头盔/胸甲/护腿/靴子）。");
                    return TaskResult.success(out);
                }

                // ---- 快捷栏内：直接选中
                if (found >= 0 && found <= 8) {
                    inv.selected = found;
                    out.addProperty("selectedSlot", found);
                    out.add("item", StateCollector.item(inv.getSelected()));
                    return TaskResult.success(out);
                }

                // ---- 其它位置：与当前快捷栏槽位交换
                final int hotbar = Math.max(0, Math.min(8, inv.selected));
                click(mc, player, toMenuSlot(found), hotbar, ClickType.SWAP);
                out.addProperty("swappedIntoSlot", hotbar);
                out.add("item", StateCollector.item(inv.getItem(hotbar)));
                out.addProperty("note", "已把物品换到快捷栏第 " + hotbar + " 格并选中");
                return TaskResult.success(out);
            }
        };
    }

    // -------------------------------------------------------------- 丢弃

    /** 参数：{@code slot} 或 {@code item}，{@code count}（默认全部）。 */
    public static Task drop(final String id, final JsonObject params, final boolean instant) {
        return new Task(id, "drop", params, 5_000L, !instant, false) {
            @Override
            protected TaskResult onTick(final Minecraft mc) {
                final LocalPlayer player = mc.player;
                if (player == null || mc.gameMode == null) {
                    fail("玩家不存在");
                }
                final Inventory inv = player.getInventory();

                // ---- 腾地方模式：背包满了要捡贵重物品 —— **丢掉最不值钱的**。
                //
                // 判定用一张粗略的价值表（关键词近似，不可靠但够用）：
                //   100 = 钻石/下界合金/末影之眼/潜影盒/图腾… → **绝对不丢**
                //    60 = 铁/金/红石/青金石/黑曜石/粗矿…
                //    55 = 工具/武器/盔甲/弓箭/书/药水/桶…
                //    45 = 食物（留着吃）
                //     5 = 泥土/沙砾/圆石/树叶/树苗/腐肉/骨头…（优先丢这些）
                // 另外**永不丢**：身上穿着的盔甲、副手、当前手持那一格。
                if (Json.bool(params, "junk", false) || Json.has(params, "free")) {
                    final int want = Math.max(1, Math.min(Json.intVal(params, "free", 1), 36));
                    final int minValue = Json.intVal(params, "keepValue", 60);
                    final java.util.List<Integer> order = new java.util.ArrayList<>();
                    for (int i = 0; i < 36; i++) {
                        final ItemStack s = inv.getItem(i);
                        if (s.isEmpty() || i == inv.selected) {
                            continue;
                        }
                        if (itemValue(s) < minValue) {
                            order.add(i);
                        }
                    }
                    order.sort(java.util.Comparator.comparingInt(i -> itemValue(inv.getItem(i))));

                    int freeNow = 0;
                    for (int i = 0; i < 36; i++) {
                        if (inv.getItem(i).isEmpty()) {
                            freeNow++;
                        }
                    }
                    final com.google.gson.JsonArray dropped = new com.google.gson.JsonArray();
                    int need = want - freeNow;
                    for (final int slotIdx : order) {
                        if (need <= 0) {
                            break;
                        }
                        final ItemStack s = inv.getItem(slotIdx);
                        if (s.isEmpty()) {
                            continue;
                        }
                        final JsonObject one = new JsonObject();
                        one.addProperty("slot", slotIdx);
                        one.addProperty("item", GameUtils.itemId(s));
                        one.addProperty("count", s.getCount());
                        one.addProperty("value", itemValue(s));
                        dropped.add(one);
                        click(mc, player, toMenuSlot(slotIdx), 1, ClickType.THROW);
                        need--;
                    }
                    final JsonObject out = new JsonObject();
                    out.add("dropped", dropped);
                    out.addProperty("slotsFreeBefore", freeNow);
                    out.addProperty("droppedStacks", dropped.size());
                    out.addProperty("keepValue", minValue);
                    if (dropped.isEmpty()) {
                        out.addProperty("note", need > 0
                                ? "没东西可丢：背包里剩下的都是值钱的（价值 ≥ " + minValue
                                  + "），或者只有你手上/身上那几件。要么先找个箱子存起来。"
                                : "已经有 " + freeNow + " 个空格，不用丢东西。");
                    } else {
                        out.addProperty("note", "丢掉了 " + dropped.size() + " 摞最不值钱的，"
                                + "腾出位置放贵重物品（价值 ≥ " + minValue + " 的一件没动）。");
                    }
                    return TaskResult.success(out);
                }

                int slot = Json.has(params, "slot") ? Json.intVal(params, "slot", -1) : -1;
                if (slot < 0) {
                    final String wanted = Json.str(params, "item", "").trim();
                    if (wanted.isEmpty()) {
                        fail("缺少参数：请提供 slot（0-8 快捷栏 / 9-35 背包）或 item（物品名）");
                    }
                    slot = findSlot(inv, wanted);
                    if (slot < 0) {
                        fail("背包里找不到匹配「" + wanted + "」的物品");
                    }
                }
                if (slot < 0 || slot >= inv.getContainerSize()) {
                    fail("slot 超出范围（0-" + (inv.getContainerSize() - 1) + "），收到 " + slot);
                }

                final ItemStack stack = inv.getItem(slot);
                if (stack.isEmpty()) {
                    fail("第 " + slot + " 格是空的");
                }
                final int count = Json.intVal(params, "count", 0);
                final boolean wholeStack = count <= 0 || count >= stack.getCount();

                final int before = stack.getCount();
                final String dropped = GameUtils.itemId(stack);
                if (wholeStack) {
                    click(mc, player, toMenuSlot(slot), 1, ClickType.THROW);
                } else {
                    for (int i = 0; i < count; i++) {
                        click(mc, player, toMenuSlot(slot), 0, ClickType.THROW);
                    }
                }

                final JsonObject out = new JsonObject();
                out.addProperty("item", dropped);
                out.addProperty("droppedCount", wholeStack ? before : count);
                out.addProperty("slot", slot);
                return TaskResult.success(out);
            }
        };
    }

    // -------------------------------------------------------------- 内部

    private static void click(final Minecraft mc, final Player player, final int menuSlot,
                             final int button, final ClickType type) {
        mc.gameMode.handleInventoryMouseClick(player.inventoryMenu.containerId, menuSlot, button, type, player);
    }

    /** 背包索引 → 玩家背包菜单的槽位索引。 */
    /**
     * 把背包第 {@code slot} 格的东西换到**当前选中的快捷栏槽位**。
     *
     * <p>给 {@code place} 这类「手上得先拿着」的动作复用：物品在背包里但不在快捷栏时，
     * 与其报错让调用方再补一步，不如自己换过去。换位是发点击包，要下一个 tick 才生效。</p>
     */
    public static void swapIntoHotbar(final Minecraft mc, final LocalPlayer player, final int slot) {
        final int hotbar = Math.max(0, Math.min(8, player.getInventory().selected));
        click(mc, player, toMenuSlot(slot), hotbar, ClickType.SWAP);
    }

    private static int toMenuSlot(final int inventoryIndex) {
        if (inventoryIndex >= 0 && inventoryIndex <= 8) {
            return 36 + inventoryIndex; // 快捷栏
        }
        if (inventoryIndex >= 9 && inventoryIndex <= 35) {
            return inventoryIndex; // 主背包
        }
        if (inventoryIndex >= 36 && inventoryIndex <= 39) {
            return 8 - (inventoryIndex - 36); // 护甲：脚→8, 腿→7, 胸→6, 头→5
        }
        if (inventoryIndex == Inventory.SLOT_OFFHAND) {
            return 45;
        }
        return inventoryIndex;
    }

    /** 按物品名（模糊匹配）找到背包索引；优先返回快捷栏中的那一个。 */
    public static int findSlot(final Inventory inv, final String wanted) {
        final String needle = wanted.trim().toLowerCase(Locale.ROOT);
        final String withNamespace = needle.contains(":") ? needle : "minecraft:" + needle;

        int fallback = -1;
        // 先找快捷栏，后找主背包，最后找护甲/副手
        for (int i = 0; i < inv.getContainerSize(); i++) {
            final ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            final String itemId = GameUtils.itemId(stack).toLowerCase(Locale.ROOT);
            final String shortId = GameUtils.shortId(itemId);
            final String hover;
            try {
                hover = stack.getHoverName().getString().toLowerCase(Locale.ROOT);
            } catch (final Exception e) {
                continue;
            }
            final boolean match = itemId.equals(withNamespace) || shortId.equals(needle)
                    || itemId.contains(needle) || shortId.contains(needle) || hover.contains(needle);
            if (!match) {
                continue;
            }
            if (i <= 8) {
                return i; // 快捷栏最优先
            }
            if (fallback < 0) {
                fallback = i;
            }
        }
        return fallback;
    }
}
