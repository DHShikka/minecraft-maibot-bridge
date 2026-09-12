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
                    if (elapsed == 0L || !player.isUsingItem()) {
                        final InteractionResult result = mc.gameMode.useItem(player, hand);
                        if (result.shouldSwing()) {
                            player.swing(hand);
                        }
                    }
                }
                if (elapsed >= holdMs && !released) {
                    released = true;
                    mc.gameMode.releaseUsingItem(player);
                    final JsonObject out = new JsonObject();
                    out.addProperty("item", GameUtils.itemId(player.getItemInHand(hand)));
                    out.addProperty("heldMs", elapsed);
                    return TaskResult.success(out);
                }
                return null;
            }

            @Override
            protected void onCancel(final Minecraft mc) {
                if (mc.player != null && mc.gameMode != null) {
                    mc.gameMode.releaseUsingItem(mc.player);
                }
            }

            @Override
            public double progress() {
                return Math.min(1.0, (double) (System.currentTimeMillis() - start) / Math.max(1L, holdMs));
            }
        };
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

                // ---- 换到护甲槽
                if (Json.bool(params, "armor", false)) {
                    if (found >= 36 && found <= 39) {
                        click(mc, player, toMenuSlot(found), 0, ClickType.PICKUP);
                        out.addProperty("armorEquipped", true);
                        return TaskResult.success(out);
                    }
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
