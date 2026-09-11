package com.mcai.bridge.snapshot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mcai.bridge.BridgeConfig;
import com.mcai.bridge.util.GameUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Team;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 把客户端能看到的游戏世界序列化成 JSON，供 MaiBot「观察」。
 *
 * <p>设计原则：</p>
 * <ul>
 *   <li><b>够用就好</b>：AI 需要的是决策依据（我在哪、我有什么、周围有什么威胁、我看着什么），
 *       而不是完整的区块数据。</li>
 *   <li><b>体积可控</b>：实体与背包都做截断，避免一次状态上报就是几百 KB。</li>
 *   <li><b>容错</b>：任何单个字段取值失败都不能让整次上报炸掉。</li>
 * </ul>
 */
public final class StateCollector {

    /** 附近实体扫描半径（格）。 */
    public static final double ENTITY_RADIUS = 32.0;
    /** 附近实体最多上报多少个。 */
    public static final int ENTITY_LIMIT = 40;
    /** 背包快照是否包含空格子。 */
    private static final boolean INCLUDE_EMPTY_SLOTS = false;

    private StateCollector() {
    }

    /** 生成完整状态快照。{@code reason} 说明本次快照的触发原因。 */
    public static JsonObject collect(final Minecraft mc, final String reason) {
        final JsonObject root = new JsonObject();
        root.addProperty("reason", reason);
        root.addProperty("at", System.currentTimeMillis());

        final LocalPlayer player = mc.player;
        final ClientLevel level = mc.level;

        if (player == null || level == null) {
            root.addProperty("inWorld", false);
            return root;
        }
        root.addProperty("inWorld", true);

        safe(root, "player", () -> playerJson(mc, player, level));
        safe(root, "world", () -> worldJson(level, player));
        safe(root, "look", () -> lookJson(mc, player));
        safe(root, "entities", () -> entitiesJson(level, player));
        safe(root, "players", () -> playersJson(level, player));
        safe(root, "inventory", () -> inventoryJson(player));
        safe(root, "scoreboard", () -> scoreboardJson(player));
        return root;
    }

    // ---------------------------------------------------------------- player

    private static JsonObject playerJson(final Minecraft mc, final LocalPlayer player, final ClientLevel level) {
        final JsonObject o = new JsonObject();
        o.addProperty("name", player.getName().getString());
        o.addProperty("uuid", player.getUUID().toString());
        o.addProperty("dimension", level.dimension().location().toString());

        final Vec3 pos = player.position();
        o.add("pos", vec(pos));
        o.add("blockPos", blockPos(player.blockPosition()));
        o.addProperty("yaw", round(player.getYRot()));
        o.addProperty("pitch", round(player.getXRot()));
        o.addProperty("health", round(player.getHealth()));
        o.addProperty("maxHealth", round(player.getMaxHealth()));
        o.addProperty("absorption", round(player.getAbsorptionAmount()));
        o.addProperty("food", player.getFoodData().getFoodLevel());
        o.addProperty("saturation", round(player.getFoodData().getSaturationLevel()));
        o.addProperty("air", player.getAirSupply());
        o.addProperty("maxAir", player.getMaxAirSupply());
        o.addProperty("xpLevel", player.experienceLevel);
        o.addProperty("xpProgress", round(player.experienceProgress));
        o.addProperty("score", player.getScore());
        o.addProperty("armor", player.getArmorValue());

        o.addProperty("gameMode", gameModeName(mc, player));
        o.addProperty("flying", player.getAbilities().flying);
        o.addProperty("mayFly", player.getAbilities().mayfly);
        o.addProperty("onGround", player.onGround());
        o.addProperty("inWater", player.isInWater());
        o.addProperty("inLava", player.isInLava());
        o.addProperty("inWaterOrRain", player.isInWaterOrRain());
        o.addProperty("onFire", player.isOnFire());
        o.addProperty("sprinting", player.isSprinting());
        o.addProperty("sneaking", player.isShiftKeyDown());
        o.addProperty("swimming", player.isSwimming());
        o.addProperty("fallFlying", player.isFallFlying());
        o.addProperty("usingItem", player.isUsingItem());
        o.addProperty("dead", player.isDeadOrDying());
        o.addProperty("passenger", player.isPassenger());
        o.addProperty("creative", player.isCreative());
        o.addProperty("sleeping", player.isSleeping());

        final Inventory inv = player.getInventory();
        o.addProperty("selectedSlot", inv.selected);
        o.add("heldItem", item(inv.getSelected()));
        o.add("offhandItem", item(player.getOffhandItem()));

        final Team team = player.getTeam();
        o.addProperty("team", team == null ? null : team.getName());

        final JsonArray effects = new JsonArray();
        for (final MobEffectInstance effect : player.getActiveEffects()) {
            final JsonObject e = new JsonObject();
            e.addProperty("id", String.valueOf(BuiltInRegistries.MOB_EFFECT.getKey(effect.getEffect())));
            e.addProperty("name", effect.getEffect().getDisplayName().getString());
            e.addProperty("amplifier", effect.getAmplifier());
            e.addProperty("durationTicks", effect.getDuration());
            effects.add(e);
        }
        o.add("effects", effects);

        // 脚下与头部方块，帮助 AI 判断自己是否卡住
        o.addProperty("blockBelow", GameUtils.blockId(level.getBlockState(player.blockPosition().below())));
        o.addProperty("blockAtFeet", GameUtils.blockId(level.getBlockState(player.blockPosition())));
        return o;
    }

    private static String gameModeName(final Minecraft mc, final LocalPlayer player) {
        try {
            final GameType type = mc.gameMode != null ? mc.gameMode.getPlayerMode() : null;
            return type != null ? type.getName() : "unknown";
        } catch (final Exception e) {
            return "unknown";
        }
    }

    // ----------------------------------------------------------------- world

    private static JsonObject worldJson(final ClientLevel level, final LocalPlayer player) {
        final JsonObject o = new JsonObject();
        final long dayTime = level.getDayTime();
        o.addProperty("timeOfDay", (int) (dayTime % 24000L));
        o.addProperty("day", (int) (dayTime / 24000L));
        final int time = (int) (dayTime % 24000L);
        o.addProperty("isDay", time < 13000 || time > 23000);
        o.addProperty("isNight", time >= 13000 && time <= 23000);
        o.addProperty("gameTime", level.getGameTime());
        o.addProperty("raining", level.isRaining());
        o.addProperty("thundering", level.isThundering());
        o.addProperty("rainLevel", round(level.getRainLevel(1.0f)));
        o.addProperty("difficulty", level.getDifficulty().getKey());
        o.addProperty("dimension", level.dimension().location().toString());

        final BlockPos pos = player.blockPosition();
        try {
            final String biome = level.getBiome(pos).unwrapKey()
                    .map(key -> key.location().toString())
                    .orElse("unknown");
            o.addProperty("biome", biome);
        } catch (final Exception e) {
            o.addProperty("biome", "unknown");
        }
        try {
            o.addProperty("lightLevel", level.getMaxLocalRawBrightness(pos));
        } catch (final Exception e) {
            o.addProperty("lightLevel", -1);
        }
        o.addProperty("canSeeSky", level.canSeeSky(pos));
        try {
            o.addProperty("minY", level.getMinBuildHeight());
            o.addProperty("maxY", level.getMaxBuildHeight());
        } catch (final Exception ignored) {
            // 老版本可能没有这些方法
        }
        return o;
    }

    // ------------------------------------------------------------------ look

    private static JsonObject lookJson(final Minecraft mc, final LocalPlayer player) {
        final JsonObject o = new JsonObject();
        final HitResult hit = mc.hitResult;
        if (hit instanceof BlockHitResult blockHit) {
            final JsonObject b = new JsonObject();
            b.addProperty("id", GameUtils.blockId(mc.level.getBlockState(blockHit.getBlockPos())));
            b.addProperty("name", GameUtils.blockName(mc.level.getBlockState(blockHit.getBlockPos())));
            b.add("pos", blockPos(blockHit.getBlockPos()));
            b.addProperty("face", blockHit.getDirection().getSerializedName());
            b.addProperty("distance", round(hit.getLocation().distanceTo(player.getEyePosition())));
            o.add("block", b);
        } else {
            o.add("block", null);
        }
        if (hit instanceof EntityHitResult entityHit) {
            o.add("entity", briefEntity(entityHit.getEntity(), player));
        } else {
            o.add("entity", null);
        }
        return o;
    }

    // -------------------------------------------------------------- entities

    private static JsonArray entitiesJson(final ClientLevel level, final LocalPlayer player) {
        final AABB area = player.getBoundingBox().inflate(ENTITY_RADIUS);
        final List<LivingEntity> found = level.getEntitiesOfClass(LivingEntity.class, area);
        final List<LivingEntity> sorted = new ArrayList<>(found);
        sorted.sort(Comparator.comparingDouble(player::distanceToSqr));

        final JsonArray array = new JsonArray();
        int count = 0;
        for (final LivingEntity entity : sorted) {
            if (entity == player) {
                continue;
            }
            // 死亡动画期间实体还在，但已经不是「附近的威胁」了 —— 不要报给 AI
            if (!entity.isAlive() || entity.getHealth() <= 0) {
                continue;
            }
            if (count++ >= ENTITY_LIMIT) {
                break;
            }
            array.add(briefEntity(entity, player));
        }
        return array;
    }

    private static JsonObject briefEntity(final Entity entity, final LocalPlayer player) {
        final JsonObject e = new JsonObject();
        e.addProperty("uuid", entity.getUUID().toString());
        e.addProperty("type", GameUtils.entityTypeId(entity));
        e.addProperty("name", GameUtils.entityName(entity));
        e.addProperty("category", GameUtils.entityCategory(entity));
        e.add("pos", vec(entity.position()));
        e.add("blockPos", blockPos(entity.blockPosition()));
        e.addProperty("distance", round(player.distanceTo(entity)));
        if (entity instanceof LivingEntity living) {
            e.addProperty("health", round(living.getHealth()));
            e.addProperty("maxHealth", round(living.getMaxHealth()));
        }
        e.addProperty("alive", entity.isAlive());
        e.addProperty("onFire", entity.isOnFire());
        e.addProperty("passenger", entity.isPassenger());
        if (entity instanceof Player p) {
            e.addProperty("player", true);
            e.addProperty("name", p.getName().getString());
            e.addProperty("sneaking", p.isShiftKeyDown());
            e.addProperty("gameMode", p.isCreative() ? "creative" : (p.isSpectator() ? "spectator" : "survival"));
        } else {
            e.addProperty("player", false);
        }
        return e;
    }

    private static JsonArray playersJson(final ClientLevel level, final LocalPlayer self) {
        final JsonArray array = new JsonArray();
        for (final Player player : level.players()) {
            final JsonObject p = new JsonObject();
            p.addProperty("name", player.getName().getString());
            p.addProperty("uuid", player.getUUID().toString());
            p.add("pos", vec(player.position()));
            p.addProperty("distance", round(self.distanceTo(player)));
            p.addProperty("health", round(player.getHealth()));
            p.addProperty("maxHealth", round(player.getMaxHealth()));
            p.addProperty("creative", player.isCreative());
            p.addProperty("sneaking", player.isShiftKeyDown());
            p.addProperty("self", player == self);
            array.add(p);
        }
        return array;
    }

    // ------------------------------------------------------------- inventory

    private static JsonObject inventoryJson(final LocalPlayer player) {
        final Inventory inv = player.getInventory();
        final JsonObject o = new JsonObject();
        final JsonArray slots = new JsonArray();
        int used = 0;

        // 快捷栏 0-8 放在最前面，方便 AI 判断 equip 的 slot 参数
        for (int i = 0; i < inv.getContainerSize(); i++) {
            final ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) {
                if (INCLUDE_EMPTY_SLOTS) {
                    slots.add(com.google.gson.JsonNull.INSTANCE);
                }
                continue;
            }
            used++;
            final JsonObject s = new JsonObject();
            s.addProperty("slot", i);
            s.addProperty("hotbar", i < 9);
            s.addProperty("selected", i == inv.selected);
            s.add("item", item(stack));
            slots.add(s);
        }

        final JsonArray armor = new JsonArray();
        for (int i = 0; i < inv.armor.size(); i++) {
            final ItemStack stack = inv.armor.get(i);
            if (!stack.isEmpty()) {
                armor.add(item(stack));
            }
        }

        o.add("slots", slots);
        o.add("armor", armor);
        o.addProperty("usedSlots", used);
        o.addProperty("freeSlots", inv.getContainerSize() - used);
        o.addProperty("selectedSlot", inv.selected);
        o.add("selected", item(inv.getSelected()));
        return o;
    }

    private static JsonObject scoreboardJson(final LocalPlayer player) {
        final JsonObject o = new JsonObject();
        o.addProperty("score", player.getScore());
        o.addProperty("xpLevel", player.experienceLevel);
        final Team team = player.getTeam();
        if (team != null) {
            o.addProperty("team", team.getName());
            try {
                o.addProperty("teamColor", String.valueOf(team.getColor()));
            } catch (final Exception ignored) {
                // 忽略
            }
        }
        return o;
    }

    // -------------------------------------------------------------- 小工具

    /** 把一个物品序列化成 JSON。 */
    public static JsonObject item(final ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        final JsonObject o = new JsonObject();
        o.addProperty("id", GameUtils.itemId(stack));
        o.addProperty("name", safeName(stack));
        o.addProperty("count", stack.getCount());
        if (stack.isDamageableItem()) {
            o.addProperty("damage", stack.getDamageValue());
            o.addProperty("maxDamage", stack.getMaxDamage());
            final int max = stack.getMaxDamage();
            if (max > 0) {
                o.addProperty("durabilityLeft", max - stack.getDamageValue());
            }
        }
        if (stack.isEnchanted()) {
            o.addProperty("enchanted", true);
        }
        return o;
    }

    private static String safeName(final ItemStack stack) {
        try {
            return stack.getHoverName().getString();
        } catch (final Exception e) {
            return GameUtils.itemId(stack);
        }
    }

    public static JsonObject vec(final Vec3 vec) {
        final JsonObject o = new JsonObject();
        o.addProperty("x", round(vec.x));
        o.addProperty("y", round(vec.y));
        o.addProperty("z", round(vec.z));
        return o;
    }

    public static JsonObject blockPos(final BlockPos pos) {
        final JsonObject o = new JsonObject();
        o.addProperty("x", pos.getX());
        o.addProperty("y", pos.getY());
        o.addProperty("z", pos.getZ());
        return o;
    }

    private static double round(final double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static double round(final float value) {
        return round((double) value);
    }

    // ------------------------------------------------------------ 安全包装

    private interface Supplier {
        com.google.gson.JsonElement get() throws Exception;
    }

    /** 任何字段取值失败都退化成 null，绝不让整次上报失败。 */
    private static void safe(final JsonObject root, final String key, final Supplier supplier) {
        try {
            root.add(key, supplier.get());
        } catch (final Throwable t) {
            root.add(key, null);
            com.mcai.bridge.McAiBridge.LOGGER.debug("[MaiBot Bridge] 采集状态字段 {} 失败: {}", key, t.toString());
        }
    }

    // ------------------------------------------------------- 会话元信息

    /** 世界/服务器标识，用于帮插件区分不同存档。 */
    public static String worldKey(final Minecraft mc) {
        if (mc == null) {
            return "unknown";
        }
        if (mc.isSingleplayer()) {
            if (mc.getSingleplayerServer() != null) {
                return "sp:" + mc.getSingleplayerServer().getWorldData().getLevelName();
            }
            return "sp:unknown";
        }
        if (mc.getCurrentServer() != null && mc.getCurrentServer().ip != null) {
            return "mp:" + mc.getCurrentServer().ip.toLowerCase(Locale.ROOT);
        }
        return "mp:unknown";
    }

    /** 服务器/存档的显示名。 */
    public static String serverDisplayName(final Minecraft mc) {
        if (mc == null) {
            return "";
        }
        if (mc.isSingleplayer() && mc.getSingleplayerServer() != null) {
            try {
                return mc.getSingleplayerServer().getWorldData().getLevelName();
            } catch (final Exception e) {
                return "单机世界";
            }
        }
        if (mc.getCurrentServer() != null) {
            final String name = mc.getCurrentServer().name;
            return name != null && !name.isBlank() ? name : String.valueOf(mc.getCurrentServer().ip);
        }
        return "";
    }

    /** 供 HUD 使用的简短状态。 */
    public static String hudLine(final Minecraft mc) {
        final LocalPlayer player = mc.player;
        if (player == null) {
            return "未进入世界";
        }
        final Component name = player.getName();
        return name.getString() + " @ " + GameUtils.format(player.position());
    }
}
