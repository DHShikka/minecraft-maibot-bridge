package com.mcai.bridge.action.tasks;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mcai.bridge.action.Task;
import com.mcai.bridge.action.TaskResult;
import com.mcai.bridge.protocol.Json;
import com.mcai.bridge.snapshot.StateCollector;
import com.mcai.bridge.util.GameUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 世界扫描类动作：{@code scan_blocks} / {@code scan_entities}。
 *
 * <p>这是 AI 的「眼睛」。MaiBot 想知道附近有没有铁矿、有没有怪，
 * 都通过这两个动作来问，而不是把整个区块塞进状态快照里。</p>
 */
public final class QueryTasks {

    private QueryTasks() {
    }

    // ------------------------------------------------------------ 扫描方块

    /**
     * 参数：
     * <ul>
     *   <li>{@code block}：方块名（{@code iron_ore} / {@code minecraft:iron_ore}）或标签（{@code #minecraft:logs}）</li>
     *   <li>{@code radius}：水平搜索半径，默认 16，上限 64</li>
     *   <li>{@code yRadius}：垂直搜索半径，默认等于 radius</li>
     *   <li>{@code limit}：最多返回多少个，默认 20，上限 200</li>
     *   <li>{@code sortBy}：{@code distance}（默认）或 {@code y}（从高到低）</li>
     *   <li>{@code replaceableOnly}：只找可替换方块（默认 false）</li>
     * </ul>
     */
    public static Task scanBlocks(final String id, final JsonObject params, final boolean instant) {
        return new Task(id, "scan_blocks", params, 10_000L, !instant, false) {
            @Override
            protected TaskResult onTick(final Minecraft mc) {
                final ClientLevel level = mc.level;
                final LocalPlayer player = mc.player;
                if (level == null || player == null) {
                    fail("尚未进入世界");
                }
                final String spec = str(params, "block", str(params, "blockId", str(params, "tag", "")));
                if (spec.isBlank()) {
                    fail("缺少参数 block（方块名，例如 iron_ore，或标签 #minecraft:logs）");
                }
                if (!spec.startsWith("#") && GameUtils.resolveBlock(spec) == null) {
                    fail("无法识别的方块名：" + spec + "。请使用原版 ID，例如 iron_ore、diamond_ore、oak_log、chest。");
                }

                final int radius = clamp(Json.intVal(params, "radius", 16), 1, 64);
                final int yRadius = clamp(Json.intVal(params, "yRadius", radius), 1, 128);
                final int limit = clamp(Json.intVal(params, "limit", 20), 1, 200);
                final boolean sortByY = "y".equalsIgnoreCase(str(params, "sortBy", "distance"));

                final BlockPos origin = player.blockPosition();
                final List<long[]> found = new ArrayList<>(); // x, y, z packed, distance
                final List<BlockPos> positions = new ArrayList<>();

                final int minY = Math.max(level.getMinBuildHeight(), origin.getY() - yRadius);
                final int maxY = Math.min(level.getMaxBuildHeight() - 1, origin.getY() + yRadius);

                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        if (dx * dx + dz * dz > radius * radius) {
                            continue;
                        }
                        final int x = origin.getX() + dx;
                        final int z = origin.getZ() + dz;
                        if (!level.hasChunkAt(new BlockPos(x, origin.getY(), z))) {
                            continue;
                        }
                        for (int y = minY; y <= maxY; y++) {
                            final BlockPos pos = new BlockPos(x, y, z);
                            final BlockState state = level.getBlockState(pos);
                            if (state.isAir()) {
                                continue;
                            }
                            if (!GameUtils.matchesBlock(state, spec)) {
                                continue;
                            }
                            positions.add(pos);
                        }
                    }
                }

                final LocalPlayer self = player;
                if (sortByY) {
                    positions.sort(Comparator.<BlockPos>comparingInt(BlockPos::getY).reversed());
                } else {
                    positions.sort(Comparator.comparingDouble(
                            (BlockPos pos) -> self.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)));
                }

                final JsonArray array = new JsonArray();
                for (int i = 0; i < positions.size() && i < limit; i++) {
                    final BlockPos pos = positions.get(i);
                    final BlockState state = level.getBlockState(pos);
                    final JsonObject o = new JsonObject();
                    o.add("pos", StateCollector.blockPos(pos));
                    o.addProperty("block", GameUtils.blockId(state));
                    o.addProperty("name", GameUtils.blockName(state));
                    o.addProperty("distance",
                            round(self.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)));
                    o.addProperty("destroySpeed", round(state.getDestroySpeed(level, pos)));
                    array.add(o);
                }

                final JsonObject result = new JsonObject();
                result.addProperty("query", spec);
                result.addProperty("radius", radius);
                result.addProperty("totalFound", positions.size());
                result.addProperty("returned", array.size());
                result.add("blocks", array);
                if (positions.isEmpty()) {
                    result.addProperty("hint", "半径 " + radius + " 格内没有找到 " + spec
                            + "。可以扩大 radius，或者先移动到别的位置再试。注意只有已加载的区块才能被扫描到。");
                }
                return TaskResult.success(result);
            }
        };
    }

    // ------------------------------------------------------------ 扫描实体

    /**
     * 参数：
     * <ul>
     *   <li>{@code radius}：搜索半径，默认 32，上限 64</li>
     *   <li>{@code limit}：最多返回多少个，默认 20</li>
     *   <li>{@code filter}：{@code all}（默认）/ {@code hostile} / {@code animal} / {@code player}</li>
     *   <li>{@code type}：按实体类型名过滤，例如 {@code zombie}</li>
     * </ul>
     */
    public static Task scanEntities(final String id, final JsonObject params, final boolean instant) {
        return new Task(id, "scan_entities", params, 10_000L, !instant, false) {
            @Override
            protected TaskResult onTick(final Minecraft mc) {
                final ClientLevel level = mc.level;
                final LocalPlayer player = mc.player;
                if (level == null || player == null) {
                    fail("尚未进入世界");
                }
                final int radius = clamp(Json.intVal(params, "radius", 32), 1, 64);
                final int limit = clamp(Json.intVal(params, "limit", 20), 1, 128);
                final String filter = str(params, "filter", "all").toLowerCase(java.util.Locale.ROOT);
                final String typeFilter = str(params, "type", "").toLowerCase(java.util.Locale.ROOT);

                final AABB area = player.getBoundingBox().inflate(radius);
                final List<LivingEntity> list = level.getEntitiesOfClass(LivingEntity.class, area);
                list.sort(Comparator.comparingDouble(player::distanceToSqr));

                final JsonArray array = new JsonArray();
                int total = 0;
                for (final LivingEntity entity : list) {
                    if (entity == player) {
                        continue;
                    }
                    // 已经死掉/正在死亡的不要报。客户端在死亡动画期间实体还在，
                    // 不过滤的话真机上会看到「血 0 的僵尸」还列在附近实体里，
                    // AI 会以为威胁没清掉，重复下指令。
                    if (!entity.isAlive() || entity.getHealth() <= 0) {
                        continue;
                    }
                    final String category = GameUtils.entityCategory(entity);
                    final String typeId = GameUtils.entityTypeId(entity);
                    if (!"all".equals(filter)) {
                        if ("hostile".equals(filter) && !"HOSTILE".equals(category)) {
                            continue;
                        }
                        if ("animal".equals(filter) && !"ANIMAL".equals(category)) {
                            continue;
                        }
                        if ("player".equals(filter) && !"PLAYER".equals(category)) {
                            continue;
                        }
                    }
                    if (!typeFilter.isEmpty() && !typeId.toLowerCase(java.util.Locale.ROOT).contains(typeFilter)) {
                        continue;
                    }
                    total++;
                    if (array.size() >= limit) {
                        continue;
                    }
                    final JsonObject o = new JsonObject();
                    o.addProperty("uuid", entity.getUUID().toString());
                    o.addProperty("type", typeId);
                    o.addProperty("name", GameUtils.entityName(entity));
                    o.addProperty("category", category);
                    o.add("pos", StateCollector.vec(entity.position()));
                    o.addProperty("distance", round(player.distanceTo(entity)));
                    o.addProperty("health", round(entity.getHealth()));
                    o.addProperty("maxHealth", round(entity.getMaxHealth()));
                    o.addProperty("onFire", entity.isOnFire());
                    o.addProperty("visible", player.hasLineOfSight(entity));
                    if (entity instanceof net.minecraft.world.entity.player.Player p) {
                        o.addProperty("player", true);
                        o.addProperty("creative", p.isCreative());
                    }
                    array.add(o);
                }

                final JsonObject result = new JsonObject();
                result.addProperty("radius", radius);
                result.addProperty("filter", filter);
                result.addProperty("totalFound", total);
                result.add("entities", array);
                return TaskResult.success(result);
            }
        };
    }

    /** 供其它任务复用：按半径收集匹配方块，已按距离排序。 */
    public static List<BlockPos> collectBlocks(final ClientLevel level, final BlockPos origin,
                                               final String spec, final int radius, final int yRadius,
                                               final int limit) {
        final List<BlockPos> positions = new ArrayList<>();
        final int minY = Math.max(level.getMinBuildHeight(), origin.getY() - yRadius);
        final int maxY = Math.min(level.getMaxBuildHeight() - 1, origin.getY() + yRadius);
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) {
                    continue;
                }
                final int x = origin.getX() + dx;
                final int z = origin.getZ() + dz;
                if (!level.hasChunkAt(new BlockPos(x, origin.getY(), z))) {
                    continue;
                }
                for (int y = minY; y <= maxY; y++) {
                    final BlockPos pos = new BlockPos(x, y, z);
                    final BlockState state = level.getBlockState(pos);
                    if (state.isAir()) {
                        continue;
                    }
                    if (GameUtils.matchesBlock(state, spec)) {
                        positions.add(pos);
                    }
                }
            }
        }
        positions.sort(Comparator.comparingDouble(pos -> origin.distSqr(pos)));
        return positions.size() > limit ? new ArrayList<>(positions.subList(0, limit)) : positions;
    }

    private static int clamp(final int value, final int min, final int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double round(final double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
