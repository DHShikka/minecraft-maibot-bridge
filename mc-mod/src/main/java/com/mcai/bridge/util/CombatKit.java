package com.mcai.bridge.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import com.google.gson.JsonObject;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;
import java.util.Set;

/**
 * 打架要用的家伙事：盾牌、弓、判断目标是不是「在天上」。
 *
 * <h2>为什么单开一个类</h2>
 *
 * <p>这三件事都容易写错，而且错了很难看出来：</p>
 *
 * <ul>
 *   <li><b>盾牌在副手</b>：不是快捷栏。要换上去得用容器点击做 SWAP
 *       （玩家背包菜单的 45 号槽 = 副手，按钮 = 快捷栏索引），照着「换到手上」的写法写会失败。</li>
 *   <li><b>举盾 = 使用副手物品</b>：{@code useItem(OFF_HAND)} 之后玩家进入
 *       {@code isUsingItem()} 状态，服务端才认你在格挡；松开要 {@code releaseUsingItem}。</li>
 *   <li><b>射天上的目标要抬枪口</b>：箭会下坠，直接瞄实体中心必空。得按距离和箭速加一个提前量。</li>
 * </ul>
 */
public final class CombatKit {

    /** 会飞/悬空的生物：近战够不到，只能射。 */
    private static final Set<String> FLYERS = Set.of(
            "minecraft:phantom", "minecraft:ghast", "minecraft:blaze", "minecraft:bee",
            "minecraft:vex", "minecraft:parrot", "minecraft:bat", "minecraft:allay",
            "minecraft:ender_dragon", "minecraft:wither", "minecraft:fireball",
            "minecraft:small_fireball", "minecraft:shulker_bullet");

    /**
     * 目标比玩家高出这么多才算「在天上」。
     *
     * <p>不能设小：真机上山地地形里，站在坡上的僵尸常常就比玩家高两三格 ——
     * 那时候判成「在天上」会让近战直接拒绝出手（实测原话：
     * 「目标「苦力怕」在天上（高差 2.7 格），近战够不到」），
     * 而它明明就在眼前。所以门槛给到 6 格，并且要求水平距离也拉开。</p>
     */
    public static final double AIR_GAP = 6.0;

    private CombatKit() {
    }

    public static boolean isFlyer(final Entity entity) {
        return entity != null && FLYERS.contains(GameUtils.entityTypeId(entity));
    }

    /** 目标是不是「在天上」：会飞，或者明显高出一大截**而且水平也拉得很开**。 */
    public static boolean isAirborne(final LocalPlayer player, final Entity target) {
        if (target == null) {
            return false;
        }
        if (isFlyer(target)) {
            return true;
        }
        final double gap = target.getY() - player.getY();
        if (gap <= AIR_GAP) {
            return false;
        }
        // 水平距离也要够远：不然「站在旁边高台上的怪」会被误判成天上的，
        // 近战明明打得着却改用弓（或者干脆拒绝出手）。
        final double dx = target.getX() - player.getX();
        final double dz = target.getZ() - player.getZ();
        return Math.sqrt(dx * dx + dz * dz) > 4.0;
    }

    // ---------------------------------------------------------------- 盾牌

    /** 副手有没有盾。 */
    public static boolean hasShieldEquipped(final LocalPlayer player) {
        return player.getOffhandItem().is(Items.SHIELD);
    }

    /** 背包里（含副手）有没有盾。 */
    public static boolean hasShield(final LocalPlayer player) {
        if (hasShieldEquipped(player)) {
            return true;
        }
        final Inventory inv = player.getInventory();
        for (int slot = 0; slot < inv.getContainerSize(); slot++) {
            if (inv.getItem(slot).is(Items.SHIELD)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 把盾换到副手。一次只做一步（换位置要点好几下，分 tick 做才不会和服务器错位）。
     *
     * @return true 表示副手已经有盾了
     */
    public static boolean ensureShield(final Minecraft mc, final LocalPlayer player) {
        if (hasShieldEquipped(player)) {
            return true;
        }
        final MultiPlayerGameMode gameMode = mc.gameMode;
        if (gameMode == null) {
            return false;
        }
        final Inventory inv = player.getInventory();
        // 1) 快捷栏里有 → 直接和副手对调（玩家背包菜单：45 = 副手，按钮 = 快捷栏索引）
        for (int slot = 0; slot < 9; slot++) {
            if (inv.getItem(slot).is(Items.SHIELD)) {
                player.getInventory().selected = slot;   // 顺手选中，观感和真人一致
                gameMode.handleInventoryMouseClick(0, 45, slot, ClickType.SWAP, player);
                return false;
            }
        }
        // 2) 主背包里有 → Shift 点一下挪到快捷栏（下一次 tick 再对调）
        for (int slot = 9; slot < 36; slot++) {
            if (inv.getItem(slot).is(Items.SHIELD)) {
                gameMode.handleInventoryMouseClick(0, slot, 0, ClickType.QUICK_MOVE, player);
                return false;
            }
        }
        return false;
    }

    /** 举盾：使用副手物品。已经在用了就什么都不做。 */
    public static void raiseShield(final Minecraft mc, final LocalPlayer player) {
        if (mc.gameMode == null || !hasShieldEquipped(player) || player.isUsingItem()) {
            return;
        }
        mc.gameMode.useItem(player, InteractionHand.OFF_HAND);
    }

    /** 收盾。 */
    public static void lowerShield(final Minecraft mc, final LocalPlayer player) {
        if (mc.gameMode == null || !player.isUsingItem()) {
            return;
        }
        if (player.getUseItem().is(Items.SHIELD)) {
            mc.gameMode.releaseUsingItem(player);
        }
    }

    /** 盾是不是举着的。 */
    public static boolean isBlocking(final LocalPlayer player) {
        return player.isUsingItem() && player.getUseItem().is(Items.SHIELD);
    }

    // ------------------------------------------------------------ 目标筛选

    /**
     * 这个实体能不能当目标。
     *
     * <p>参考了 {@code 10089YMGC/AutoAim} 的做法（那个模组的自瞄也是先过一遍筛选再瞄准）：</p>
     *
     * <ul>
     *   <li><b>视线检测</b>：从眼睛到目标眼睛射线，被方块挡住就不算目标 ——
     *       不然会「对着墙砍」。我们原来的选目标只看距离，隔墙也照选。</li>
     *   <li><b>黑名单</b>：村民、铁傀儡、盔甲架、宠物这类**不该打**的东西，
     *       按实体 id 拉黑（可配置）。AI 手里有剑的时候打村民是很糟糕的行为。</li>
     *   <li>存活、不是自己、不是隐身的（隐身看不见，瞄它没意义）。</li>
     * </ul>
     */
    public static boolean isValidTarget(final Minecraft mc, final LocalPlayer player,
                                        final Entity entity) {
        if (entity == null || entity == player || !entity.isAlive()) {
            return false;
        }
        if (entity instanceof final LivingEntity living && living.getHealth() <= 0) {
            return false;
        }
        if (entity.isInvisible() && com.mcai.bridge.BridgeConfig.targetSkipInvisible) {
            return false;
        }
        if (entity instanceof final net.minecraft.world.entity.player.Player other
                && (other.isCreative() || other.isSpectator())) {
            return false;
        }
        if (isBlacklisted(entity)) {
            return false;
        }
        return !com.mcai.bridge.BridgeConfig.targetRequireLineOfSight
                || hasLineOfSight(mc, player, entity);
    }

    private static boolean isBlacklisted(final Entity entity) {
        final String id = GameUtils.entityTypeId(entity);
        for (final String blocked : com.mcai.bridge.BridgeConfig.targetBlacklist) {
            if (blocked.equalsIgnoreCase(id) || blocked.equalsIgnoreCase(GameUtils.shortId(id))) {
                return true;
            }
        }
        return false;
    }

    /** 从玩家眼睛到目标眼睛有没有被方块挡住（和 AutoAim 一样用 VISUAL + 忽略液体）。 */
    public static boolean hasLineOfSight(final Minecraft mc, final LocalPlayer player,
                                         final Entity target) {
        if (mc == null || mc.level == null) {
            return true;
        }
        try {
            final Vec3 start = player.getEyePosition();
            final Vec3 end = target.getEyePosition();
            final net.minecraft.world.phys.BlockHitResult hit = mc.level.clip(
                    new net.minecraft.world.level.ClipContext(start, end,
                            net.minecraft.world.level.ClipContext.Block.VISUAL,
                            net.minecraft.world.level.ClipContext.Fluid.NONE, player));
            return hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK;
        } catch (final Throwable t) {
            return true;   // 算不出来就别挡着不让打
        }
    }

    // ------------------------------------------------------------------ 弓

    /** 远程武器的种类。 */
    public enum Ranged { NONE, BOW, CROSSBOW, TRIDENT, GUN }

    /** 手上这件是什么远程武器。 */
    public static Ranged rangedKind(final ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return Ranged.NONE;
        }
        if (stack.is(Items.BOW)) {
            return Ranged.BOW;
        }
        if (stack.is(Items.CROSSBOW)) {
            return Ranged.CROSSBOW;
        }
        if (stack.is(Items.TRIDENT)) {
            return Ranged.TRIDENT;
        }
        if (com.mcai.bridge.util.ModHooks.isGun(stack)) {
            return Ranged.GUN;
        }
        return Ranged.NONE;
    }

    /** 从背包里找一件远程武器（优先手上拿着的）。 */
    public static int findRangedSlot(final LocalPlayer player) {
        if (rangedKind(player.getMainHandItem()) != Ranged.NONE) {
            return player.getInventory().selected;
        }
        final Inventory inv = player.getInventory();
        for (int slot = 0; slot < 9; slot++) {
            if (rangedKind(inv.getItem(slot)) != Ranged.NONE) {
                return slot;
            }
        }
        for (int slot = 9; slot < inv.getContainerSize(); slot++) {
            if (rangedKind(inv.getItem(slot)) != Ranged.NONE) {
                return slot;
            }
        }
        return -1;
    }

    /** 这种远程武器的弹药叫什么（写进给 AI 的说明里）。 */
    public static String ammoName(final Ranged kind) {
        return switch (kind) {
            case BOW, CROSSBOW -> "箭";
            case GUN -> "子弹";
            case TRIDENT -> "（三叉戟本身就是武器，扔出去会自动回来）";
            default -> "弹药";
        };
    }

    /**
     * 还剩多少发。
     *
     * <p><b>「记得带子弹」这件事就在这里</b>：没弹药就别扣扳机 ——
     * 空放一枪在游戏里表现为「什么都没发生」，AI 会以为是自己瞄歪了，然后一直瞄。</p>
     */
    public static int countAmmo(final LocalPlayer player, final Ranged kind) {
        return switch (kind) {
            case BOW -> countArrows(player);
            // 弩还能打烟花火箭
            case CROSSBOW -> countArrows(player) + countOf(player, Items.FIREWORK_ROCKET);
            case TRIDENT -> 1;
            case GUN -> gunAmmo(player);
            default -> 0;
        };
    }

    /**
     * 枪的弹药 = 背包里枪械模组的子弹物品 + 枪自己 NBT 里的余弹。
     *
     * <p>TaCZ 的子弹是它自己的物品（命名空间 tacz，路径里带 ammo），
     * 弹匣余弹存在枪的 NBT 里 —— 两边都数上，才不会出现「明明有子弹却说没弹药」。</p>
     */
    public static int gunAmmo(final LocalPlayer player) {
        final Inventory inv = player.getInventory();
        int total = 0;
        for (int slot = 0; slot < inv.getContainerSize(); slot++) {
            final ItemStack stack = inv.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            final net.minecraft.resources.ResourceLocation id =
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (id == null) {
                continue;
            }
            final String path = id.getPath().toLowerCase(Locale.ROOT);
            if (path.contains("ammo") || path.contains("bullet")
                    || ("tacz".equals(id.getNamespace()) && !ModHooks.isGun(stack))) {
                total += stack.getCount();
            }
        }
        // 枪里已经压好的那几发也算（弹匣 + 膛内一发），按确定的名字读，不猜
        total += ModHooks.gunLoaded(player.getMainHandItem());
        return total;
    }

    /** 手上这把枪的弹匣里还剩几发（0 = 需要 reload）。 */
    public static int magazine(final ItemStack stack) {
        return ModHooks.gunMagazine(stack);
    }

    /**
     * 自动战斗（被打反击 / 主动出击）这一步该用哪一手。
     *
     * <p><b>手里是远程武器就用远程</b>：拿着枪还上去抡拳头，是把「有枪」这件事浪费掉了。
     * 判断只看**手上那把**（不去背包里翻别的武器）—— 玩家/AI 手上拿什么就用什么，
     * 不然会出现「我只想挖矿，它却掏出枪乱打」。</p>
     */
    public enum AutoMode { MELEE, SHOOT, RELOAD }

    /**
     * **敌人贴到这个距离以内，一律改用近战武器。**
     *
     * <p>2 格以内开枪/放箭都不划算：枪在贴脸时容易打空、弓还要蓄力一秒，
     * 而对方的手已经够得着你了。这个距离上最快的解法是抡一下。</p>
     */
    public static final double MELEE_RANGE = 2.0;

    /** 这件东西能不能当近战武器用（剑 / 斧 / 三叉戟）。 */
    public static boolean isMeleeWeapon(final ItemStack stack) {
        return stack != null && !stack.isEmpty()
                && (stack.getItem() instanceof net.minecraft.world.item.SwordItem
                    || stack.getItem() instanceof net.minecraft.world.item.AxeItem
                    || stack.getItem() instanceof net.minecraft.world.item.TridentItem);
    }

    /** 背包里能当近战武器用的东西（优先剑/三叉戟，其次斧头）；没有就返回 null。 */
    public static String findMeleeWeaponId(final LocalPlayer player) {
        if (player == null) {
            return null;
        }
        String axe = null;
        final Inventory inv = player.getInventory();
        for (int slot = 0; slot < inv.getContainerSize(); slot++) {
            final ItemStack stack = inv.getItem(slot);
            if (stack.getItem() instanceof net.minecraft.world.item.SwordItem
                    || stack.getItem() instanceof net.minecraft.world.item.TridentItem) {
                return GameUtils.itemId(stack);
            }
            if (axe == null && stack.getItem() instanceof net.minecraft.world.item.AxeItem) {
                axe = GameUtils.itemId(stack);
            }
        }
        return axe;
    }

    /**
     * 贴脸的距离。
     *
     * <p>这个数是真机撞出来的：日志里 `自动反击：史莱姆（先换弹）` 之后 3 秒，
     * 玩家就 `被史莱姆杀死` 了 —— 因为枪的弹匣空了，它选择**站着换弹 2.5 秒**，
     * 而史莱姆就在 3 格外打它。贴到这个距离，换弹动作的代价比空手抡两下大得多。</p>
     */
    public static final double CLOSE_QUARTER = 4.0;

    /** 自动战斗用哪一手（不知道距离时按「远」处理）。 */
    public static AutoMode autoMode(final LocalPlayer player) {
        return autoMode(player, Double.MAX_VALUE);
    }

    /**
     * 自动战斗用哪一手。
     *
     * @param distance 到目标的距离（格）；贴脸时空弹匣不换弹，直接抡
     */
    public static AutoMode autoMode(final LocalPlayer player, final double distance) {
        if (player == null) {
            return AutoMode.MELEE;
        }
        // 贴脸第一优先：2 格以内不管手上是什么枪什么弓，都改用近战
        if (distance <= MELEE_RANGE) {
            return AutoMode.MELEE;
        }
        final ItemStack held = player.getMainHandItem();
        final Ranged kind = rangedKind(held);
        return switch (kind) {
            // 枪：弹匣有弹就射；弹匣空了但背包里有匹配的子弹 —— 离得远就换弹，
            // **贴脸就先抡**（站着换弹的两秒半足够被打死，真机教训）
            case GUN -> magazine(held) > 0 ? AutoMode.SHOOT
                    : (countAmmo(player, Ranged.GUN) > 0
                        ? (distance <= CLOSE_QUARTER ? AutoMode.MELEE : AutoMode.RELOAD)
                        : AutoMode.MELEE);
            // 弓/弩：有箭就射
            case BOW, CROSSBOW -> countAmmo(player, kind) > 0 ? AutoMode.SHOOT : AutoMode.MELEE;
            case TRIDENT -> AutoMode.SHOOT;
            default -> AutoMode.MELEE;
        };
    }

    /** 自动战斗这一步对应的动作名（交给动作执行器建任务用）。 */
    public static String autoAction(final LocalPlayer player, final double distance) {
        final AutoMode mode = autoMode(player, distance);
        // 贴脸要近战，但**背包里一件近战武器都没有**、手上又有能用的远程武器时别硬抡 ——
        // 拿弓去敲人还不如放一箭。
        if (mode == AutoMode.MELEE && distance <= MELEE_RANGE
                && player != null && !isMeleeWeapon(player.getMainHandItem())
                && findMeleeWeaponId(player) == null) {
            final Ranged kind = rangedKind(player.getMainHandItem());
            if (kind != Ranged.NONE && countAmmo(player, kind) > 0) {
                return "shoot";
            }
        }
        return switch (mode) {
            case SHOOT -> "shoot";
            case RELOAD -> "reload";
            default -> "attack";
        };
    }

    /** 自动开火按住多久（大约半秒多一点，够打出一个点射）。 */
    public static final int AUTO_SHOOT_TICKS = 14;

    /** 自动战斗那一步的参数。 */
    public static JsonObject autoAttackParams(final LocalPlayer player, final String targetId,
                                             final long budgetMs, final double distance) {
        final JsonObject p = new JsonObject();
        switch (autoMode(player, distance)) {
            case SHOOT -> {
                p.addProperty("target", targetId);
                p.addProperty("ticks", AUTO_SHOOT_TICKS);
            }
            case RELOAD -> p.addProperty("ticks", 200);
            default -> {
                p.addProperty("target", targetId);
                p.addProperty("count", 0);
                p.addProperty("durationMs", budgetMs);
            }
        }
        return p;
    }

    /** 给日志/给 AI 看的一句话：这一步打算怎么打。 */
    public static String autoModeText(final LocalPlayer player, final double distance) {
        return switch (autoMode(player, distance)) {
            case SHOOT -> rangedKind(player.getMainHandItem()) == Ranged.GUN
                    ? "用枪打" : "用远程武器打";
            case RELOAD -> "先换弹";
            default -> "近战";
        };
    }

    /**
     * 背包里**最好的**那份食物。
     *
     * <p>「能吃好的尽量吃好的」：按营养值排，同营养再看饱和度 —— 而不是背包顺序里第一个能吃的
     * （以前就是这么挑的，于是炖菜和生鸡肉摆一起时会先啃生鸡肉）。</p>
     *
     * <p>只看能不能吃、值不值，不看稀有度 —— 金苹果在这种排法里确实会排前面，
     * 那正是「能吃好的就吃好的」的意思。</p>
     */
    public static ItemStack findBestFood(final LocalPlayer player) {
        if (player == null) {
            return ItemStack.EMPTY;
        }
        ItemStack best = ItemStack.EMPTY;
        double bestScore = -1;
        final Inventory inv = player.getInventory();
        for (int slot = 0; slot < inv.getContainerSize(); slot++) {
            final ItemStack stack = inv.getItem(slot);
            final double score = foodScore(stack, player);
            if (score > bestScore) {
                bestScore = score;
                best = stack;
            }
        }
        return best;
    }

    /** 食物的评分（不能吃返回 -1）：营养优先，饱和其次。 */
    public static double foodScore(final ItemStack stack, final LocalPlayer player) {
        if (stack == null || stack.isEmpty() || !stack.isEdible()) {
            return -1;
        }
        try {
            final net.minecraft.world.food.FoodProperties food = stack.getFoodProperties(player);
            if (food == null) {
                return -1;
            }
            return food.getNutrition() * 100.0 + food.getSaturationModifier() * 10.0;
        } catch (final Throwable t) {
            return -1;
        }
    }

    /** 这份食物吃了有没有附加效果（金苹果、迷之炖菜这类**饱着吃也有用**的）。 */
    public static boolean foodHasEffects(final ItemStack stack, final LocalPlayer player) {
        if (stack == null || stack.isEmpty() || !stack.isEdible()) {
            return false;
        }
        try {
            final net.minecraft.world.food.FoodProperties food = stack.getFoodProperties(player);
            return food != null && !food.getEffects().isEmpty();
        } catch (final Throwable t) {
            return false;
        }
    }

    /** 食物的可读信息（给结果和日志用）：叫什么、多少营养、多少饱和。 */
    public static JsonObject foodInfo(final ItemStack stack, final LocalPlayer player) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        final JsonObject o = new JsonObject();
        o.addProperty("item", GameUtils.itemId(stack));
        o.addProperty("name", GameUtils.safeItemName(stack));
        o.addProperty("count", stack.getCount());
        try {
            final net.minecraft.world.food.FoodProperties food = stack.getFoodProperties(player);
            if (food != null) {
                o.addProperty("nutrition", food.getNutrition());
                o.addProperty("saturation", food.getSaturationModifier());
                if (!food.getEffects().isEmpty()) {
                    o.addProperty("hasEffects", true);
                }
            }
        } catch (final Throwable ignored) {
            // 忽略
        }
        return o;
    }

    /** 远程武器状态：给状态快照用，让 AI 随时知道「手里这把还有几发」。 */
    public static JsonObject rangedStatus(final LocalPlayer player) {
        final ItemStack held = player.getMainHandItem();
        final Ranged kind = rangedKind(held);
        if (kind == Ranged.NONE) {
            return null;
        }
        final JsonObject o = new JsonObject();
        o.addProperty("weapon", GameUtils.itemId(held));
        o.addProperty("weaponName", GameUtils.safeItemName(held));
        o.addProperty("kind", kind.name().toLowerCase(Locale.ROOT));
        final int ammo = countAmmo(player, kind);
        o.addProperty("ammo", ammo);
        o.addProperty("ammoName", ammoName(kind));
        o.addProperty("canShoot", ammo > 0);
        if (kind == Ranged.GUN) {
            // 弹匣单独报：AI 看到 0 就知道该先 reload，而不是干扣扳机
            final int mag = magazine(held);
            o.addProperty("magazine", mag);
            o.addProperty("reloadNeeded", mag <= 0);
        }
        if (ammo <= 0) {
            o.addProperty("warning", "没有" + ammoName(kind) + "了 —— 先补弹药再用远程武器，"
                    + "或者改用近战。");
        } else if (kind != Ranged.TRIDENT && ammo <= 8) {
            o.addProperty("warning", ammoName(kind) + "只剩 " + ammo + " 发了，记得补。");
        }
        return o;
    }

    public static boolean hasBow(final LocalPlayer player) {
        return findInInventory(player, Items.BOW) >= 0;
    }

    public static int countArrows(final LocalPlayer player) {
        return countOf(player, Items.ARROW) + countOf(player, Items.SPECTRAL_ARROW)
                + countOf(player, Items.TIPPED_ARROW);
    }

    public static boolean canShoot(final LocalPlayer player) {
        return hasBow(player) && countArrows(player) > 0;
    }

    /** 把弓拿到手上。 */
    public static boolean holdBow(final LocalPlayer player, final Minecraft mc) {
        final int slot = findInInventory(player, Items.BOW);
        if (slot < 0) {
            return false;
        }
        if (player.getMainHandItem().is(Items.BOW)) {
            return true;
        }
        if (slot < 9) {
            player.getInventory().selected = slot;
            return false;   // 下一次 tick 生效
        }
        if (mc.gameMode != null) {
            mc.gameMode.handleInventoryMouseClick(0, slot, 0, ClickType.QUICK_MOVE, player);
        }
        return false;
    }

    /**
     * 朝目标瞄准，带**下坠提前量**。
     *
     * <p>箭的初速约 3.0 格/tick（拉满），重力 0.05/tick²。这里按「水平飞行时间」
     * 反推抬高的角度：距离越远抬得越多。不抬的话 10 格外基本全空。</p>
     */
    public static void aimWithLead(final LocalPlayer player, final Entity target, final double speed) {
        final Vec3 eye = player.getEyePosition();
        // 瞄身体中心而不是脚：瞄脚的话稍微下坠就打在地上
        Vec3 aim = new Vec3(target.getX(), target.getY() + target.getBbHeight() * 0.55, target.getZ());
        // 移动目标加一点提前量
        final Vec3 motion = target.getDeltaMovement();
        if (motion.lengthSqr() > 1.0E-4) {
            final double flight = Math.sqrt(eye.distanceToSqr(aim)) / Math.max(1.0, speed);
            aim = aim.add(motion.scale(flight));
        }
        final double dx = aim.x - eye.x;
        final double dy = aim.y - eye.y;
        final double dz = aim.z - eye.z;
        final double horizontal = Math.sqrt(dx * dx + dz * dz);
        // 先按直线算飞行时间，再按重力补偿高度
        final double flight = horizontal / Math.max(1.0, speed);
        final double drop = 0.5 * 0.05 * flight * flight;
        GameUtils.lookAt(player, new Vec3(aim.x, aim.y + drop, aim.z));
    }

    // ------------------------------------------------------------- 小工具

    private static int findInInventory(final LocalPlayer player, final net.minecraft.world.item.Item item) {
        final Inventory inv = player.getInventory();
        for (int slot = 0; slot < inv.getContainerSize(); slot++) {
            if (inv.getItem(slot).is(item)) {
                return slot;
            }
        }
        return -1;
    }

    private static int countOf(final LocalPlayer player, final net.minecraft.world.item.Item item) {
        final Inventory inv = player.getInventory();
        int total = 0;
        for (int slot = 0; slot < inv.getContainerSize(); slot++) {
            final ItemStack stack = inv.getItem(slot);
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** 给日志/回执用：这一身家当能不能打。 */
    public static String loadout(final LocalPlayer player) {
        final StringBuilder sb = new StringBuilder();
        sb.append(hasShieldEquipped(player) ? "有盾" : (hasShield(player) ? "背包里有盾(未装备)" : "没盾"));
        if (hasBow(player)) {
            sb.append("，").append("弓+").append(countArrows(player)).append(" 支箭");
        } else {
            sb.append("，没弓");
        }
        return sb.toString();
    }

    /** 目标描述（报错信息里用）。 */
    public static String describe(final Entity target) {
        if (target == null) {
            return "无目标";
        }
        return String.format(Locale.ROOT, "%s（%.1f 格外，高差 %.1f）",
                GameUtils.entityName(target), 0.0, target.getY());
    }

    /** 箭是不是我们射的（预留给命中统计）。 */
    public static boolean isOurArrow(final Entity entity) {
        return entity instanceof Arrow || entity instanceof AbstractArrow;
    }
}
