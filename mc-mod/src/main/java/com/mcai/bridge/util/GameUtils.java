package com.mcai.bridge.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Locale;
import java.util.Set;

/**
 * 方块 / 物品 / 实体 / 坐标的通用工具方法。
 *
 * <p>这里的字符串一律使用原版资源位置形式（如 {@code minecraft:iron_ore}），
 * 方便直接把名字交给 LLM 使用，也方便模组与插件之间对齐语义。</p>
 */
public final class GameUtils {

    /** 视为「危险、不可站立/穿越」的方块。 */
    private static final Set<Block> DANGEROUS = Set.of(
            Blocks.LAVA, Blocks.FIRE, Blocks.SOUL_FIRE, Blocks.MAGMA_BLOCK, Blocks.CACTUS,
            Blocks.SWEET_BERRY_BUSH, Blocks.POWDER_SNOW, Blocks.WITHER_ROSE, Blocks.CAMPFIRE,
            Blocks.SOUL_CAMPFIRE, Blocks.LAVA_CAULDRON, Blocks.COBWEB, Blocks.NETHER_PORTAL,
            Blocks.END_PORTAL, Blocks.SCULK_SHRIEKER
    );

    private GameUtils() {
    }

    // ------------------------------------------------------------ 名称与 ID

    public static String blockId(final BlockState state) {
        return idOf(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
    }

    public static String blockId(final Block block) {
        return idOf(BuiltInRegistries.BLOCK.getKey(block));
    }

    public static String itemId(final ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        return idOf(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }

    /** 物品的本地化显示名（例如「铁镐」）；取值失败时退回资源位置，绝不抛异常。 */
    public static String safeItemName(final ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        try {
            return stack.getHoverName().getString();
        } catch (final Exception e) {
            return itemId(stack);
        }
    }

    public static String entityTypeId(final Entity entity) {
        return idOf(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()));
    }

    private static String idOf(final ResourceLocation loc) {
        return loc == null ? "minecraft:air" : loc.toString();
    }

    /** 方块的中文/本地化名字（例如「铁矿石」）。 */
    public static String blockName(final BlockState state) {
        try {
            return state.getBlock().getName().getString();
        } catch (final Exception e) {
            return blockId(state);
        }
    }

    /** 实体的显示名（自定义名优先，否则用类型名）。 */
    public static String entityName(final Entity entity) {
        try {
            if (entity.getCustomName() != null) {
                return entity.getCustomName().getString();
            }
            return entity.getName().getString();
        } catch (final Exception e) {
            return entityTypeId(entity);
        }
    }

    /** 给 LLM 用的实体分类：PLAYER / HOSTILE / ANIMAL / OTHER。 */
    public static String entityCategory(final Entity entity) {
        if (entity instanceof Player) {
            return "PLAYER";
        }
        if (entity instanceof Monster) {
            return "HOSTILE";
        }
        if (entity instanceof Animal) {
            return "ANIMAL";
        }
        if (entity instanceof Mob) {
            return "MOB";
        }
        return "OTHER";
    }

    // ------------------------------------------------------------ 名字解析

    /**
     * 把用户/LLM 给的名字解析成方块。
     *
     * <p>容错处理：{@code stone}、{@code minecraft:stone}、{@code STONE}、
     * 带空格的 {@code iron_ore} 都能解析；找不到时返回 {@code null}。</p>
     */
    public static Block resolveBlock(final String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String key = name.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        if (key.startsWith("#")) {
            return null; // 标签由 resolveBlockTag 处理
        }
        final boolean hasNamespace = key.contains(":");
        ResourceLocation loc = ResourceLocation.tryParse(hasNamespace ? key : "minecraft:" + key);
        if (loc != null && BuiltInRegistries.BLOCK.containsKey(loc)) {
            return BuiltInRegistries.BLOCK.get(loc);
        }
        // ---- 没写命名空间时，**在所有模组里找**：先按短 id，再按本地化显示名。
        //
        // 为什么必须有这一步：TaCZ 的枪械工作台是 tacz:gun_smith_table，
        // 而玩家/LLM 只会写 gun_smith_table 或「枪械工作台」—— 以前这样就报
        // 「无法识别的方块名」，于是模组方块（TaCZ 工作台、机械动力零件…）全都扫不到、挖不了。
        // 物品名解析早就是这么干的，方块这边一直缺这一步。
        if (hasNamespace) {
            return null;   // 明确写了命名空间却找不到 —— 不猜，免得解析成别的东西
        }
        final String rawLower = name.trim().toLowerCase(Locale.ROOT);
        Block shortMatch = null;
        Block nameMatch = null;
        for (final Block block : BuiltInRegistries.BLOCK) {
            final ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
            if (id == null) {
                continue;
            }
            if (shortMatch == null && id.getPath().equals(key)) {
                shortMatch = block;
                continue;
            }
            if (nameMatch == null) {
                try {
                    final String display = block.getName().getString().toLowerCase(Locale.ROOT);
                    if (display.equals(rawLower) || display.contains(rawLower)) {
                        nameMatch = block;
                    }
                } catch (final Throwable ignored) {
                    // 忽略：某些方块取名字会炸
                }
            }
        }
        return shortMatch != null ? shortMatch : nameMatch;
    }

    /** 解析 {@code #minecraft:logs} 形式的方块标签。 */
    public static TagKey<Block> resolveBlockTag(final String name) {
        if (name == null || !name.startsWith("#")) {
            return null;
        }
        String key = name.substring(1).trim().toLowerCase(Locale.ROOT);
        ResourceLocation loc = ResourceLocation.tryParse(key.contains(":") ? key : "minecraft:" + key);
        if (loc == null) {
            return null;
        }
        return TagKey.create(Registries.BLOCK, loc);
    }

    /** 判断方块状态是否匹配「方块名或方块标签」。 */
    public static boolean matchesBlock(final BlockState state, final String spec) {
        if (spec == null || spec.isBlank()) {
            return false;
        }
        if (spec.startsWith("#")) {
            final TagKey<Block> tag = resolveBlockTag(spec);
            return tag != null && state.is(tag);
        }
        final Block block = resolveBlock(spec);
        return block != null && state.is(block);
    }

    // -------------------------------------------------------------- 通行性

    /** 该位置是否对「身体」完全通畅（空气或可穿过，且不是岩浆/水）。 */
    public static boolean isPassable(final BlockGetter level, final BlockPos pos) {
        final BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return true;
        }
        if (isDangerous(state)) {
            return false;
        }
        return state.getCollisionShape(level, pos).isEmpty();
    }

    /** 是否可以涉水通过（水不算障碍）。 */
    public static boolean isWater(final BlockGetter level, final BlockPos pos) {
        final FluidState fluid = level.getBlockState(pos).getFluidState();
        return fluid.is(FluidTags.WATER);
    }

    public static boolean isDangerous(final BlockState state) {
        return DANGEROUS.contains(state.getBlock());
    }

    /** 该位置能否成为站立点（脚部与头部通畅，脚下有支撑）。 */
    public static boolean canStandAt(final BlockGetter level, final BlockPos pos) {
        if (!isPassable(level, pos) || !isPassable(level, pos.above())) {
            return false;
        }
        final BlockState below = level.getBlockState(pos.below());
        if (below.isAir() || isDangerous(below)) {
            return false;
        }
        final VoxelShape shape = below.getCollisionShape(level, pos.below());
        return !shape.isEmpty();
    }

    /**
     * 该方块是否属于常见矿物。
     *
     * <p>1.20 移除了 {@code BlockTags.ORES}，所以改为按方块名判断——
     * 这样对原版和绝大多数模组矿物都有效，也不会因为标签变动而失效。</p>
     */
    public static boolean isOre(final BlockState state) {
        final String id = shortId(blockId(state));
        return id.endsWith("_ore") || id.equals("ancient_debris") || id.equals("raw_iron_block");
    }

    public static boolean isLog(final BlockState state) {
        return state.is(BlockTags.LOGS);
    }

    public static boolean isLeaves(final BlockState state) {
        return state.is(BlockTags.LEAVES);
    }

    // ---------------------------------------------------------------- 视角

    /** 计算从 {@code eye} 看向 {@code target} 的 yaw（度）。 */
    public static float yawTo(final Vec3 eye, final Vec3 target) {
        final double dx = target.x - eye.x;
        final double dz = target.z - eye.z;
        return (float) (Mth.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f;
    }

    /** 计算从 {@code eye} 看向 {@code target} 的 pitch（度）。 */
    public static float pitchTo(final Vec3 eye, final Vec3 target) {
        final double dx = target.x - eye.x;
        final double dy = target.y - eye.y;
        final double dz = target.z - eye.z;
        final double horizontal = Math.sqrt(dx * dx + dz * dz);
        return (float) (-(Mth.atan2(dy, horizontal) * 180.0 / Math.PI));
    }

    /** 把玩家的视线指向一个世界坐标点。 */
    public static void lookAt(final Entity entity, final Vec3 target) {
        final Vec3 eye = entity.getEyePosition();
        final float yaw = yawTo(eye, target);
        final float pitch = pitchTo(eye, target);
        entity.setYRot(yaw);
        entity.setXRot(pitch);
        if (entity instanceof LivingEntity living) {
            living.setYHeadRot(yaw);
            living.yBodyRot = yaw;
        }
    }

    /** 实体身体中心（用于攻击瞄准）。 */
    public static Vec3 centerOf(final Entity entity) {
        return new Vec3(entity.getX(), entity.getY() + entity.getBbHeight() * 0.5, entity.getZ());
    }

    /** 方块的中心坐标。 */
    public static Vec3 blockCenter(final BlockPos pos) {
        return new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }

    /** 是否已经大致朝向目标（用于判断能否挖/攻击）。 */
    public static boolean isLookingAt(final Entity entity, final Vec3 target, final float toleranceDegrees) {
        final Vec3 eye = entity.getEyePosition();
        final float yaw = yawTo(eye, target);
        final float pitch = pitchTo(eye, target);
        return Math.abs(Mth.wrapDegrees(entity.getYRot() - yaw)) <= toleranceDegrees
                && Math.abs(entity.getXRot() - pitch) <= toleranceDegrees;
    }

    // ---------------------------------------------------------------- 朝向

    /** 由两个方块位置推断「从 from 看向 to」的方向（用于放置方块的点击面）。 */
    public static Direction directionBetween(final BlockPos from, final BlockPos to) {
        final int dx = to.getX() - from.getX();
        final int dy = to.getY() - from.getY();
        final int dz = to.getZ() - from.getZ();
        if (Math.abs(dx) > Math.abs(dz)) {
            return dx > 0 ? Direction.EAST : Direction.WEST;
        }
        if (dz != 0) {
            return dz > 0 ? Direction.SOUTH : Direction.NORTH;
        }
        if (dy != 0) {
            return dy > 0 ? Direction.UP : Direction.DOWN;
        }
        return Direction.UP;
    }

    /** 把 ``up``/``down``/… 解析成 {@link Direction}。 */
    public static Direction parseDirection(final String name, final Direction fallback) {
        if (name == null || name.isBlank()) {
            return fallback;
        }
        return switch (name.trim().toLowerCase(Locale.ROOT)) {
            case "up", "top", "y+" -> Direction.UP;
            case "down", "bottom", "y-" -> Direction.DOWN;
            case "north", "z-" -> Direction.NORTH;
            case "south", "z+" -> Direction.SOUTH;
            case "west", "x-" -> Direction.WEST;
            case "east", "x+" -> Direction.EAST;
            default -> fallback;
        };
    }

    /** 格式化坐标为可读字符串。 */
    public static String format(final Vec3 vec) {
        return String.format(Locale.ROOT, "(%.1f, %.1f, %.1f)", vec.x, vec.y, vec.z);
    }

    public static String format(final BlockPos pos) {
        return "(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
    }

    /** 简化物品名：去掉 {@code minecraft:} 前缀，便于 LLM 阅读。 */
    public static String shortId(final String id) {
        if (id == null) {
            return "";
        }
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }
}
