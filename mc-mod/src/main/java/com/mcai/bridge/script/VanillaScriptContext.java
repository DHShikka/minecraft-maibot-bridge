package com.mcai.bridge.script;

import com.mcai.bridge.action.ActionExecutor;
import com.mcai.bridge.action.tasks.CraftTask;
import com.mcai.bridge.util.GameUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.Locale;

/**
 * 用真实客户端状态实现 {@link ScriptContext}。
 *
 * <p>这是脚本包里唯一认识 Minecraft 的部分；{@link ScriptVm} 与 {@link Condition}
 * 只依赖接口，所以能在纯 JVM 里测试。</p>
 */
public final class VanillaScriptContext implements ScriptContext {

    private final Minecraft mc;

    public VanillaScriptContext(final Minecraft mc) {
        this.mc = mc;
    }

    @Override
    public boolean inWorld() {
        return mc.player != null && mc.level != null;
    }

    @Override
    public int countItem(final String spec) {
        final LocalPlayer player = mc.player;
        return player == null ? 0 : CraftTask.countItem(player, spec);
    }

    @Override
    public boolean holding(final String spec) {
        final LocalPlayer player = mc.player;
        if (player == null) {
            return false;
        }
        return CraftTask.matchesSpec(player.getMainHandItem(), spec);
    }

    @Override
    public double health() {
        final LocalPlayer player = mc.player;
        return player == null ? 0 : player.getHealth();
    }

    @Override
    public double maxHealth() {
        final LocalPlayer player = mc.player;
        return player == null ? 20 : player.getMaxHealth();
    }

    @Override
    public int food() {
        final LocalPlayer player = mc.player;
        return player == null ? 0 : player.getFoodData().getFoodLevel();
    }

    @Override
    public boolean atPos(final int x, final int y, final int z, final int radius) {
        final LocalPlayer player = mc.player;
        if (player == null) {
            return false;
        }
        final double dx = player.getX() - (x + 0.5);
        final double dz = player.getZ() - (z + 0.5);
        final double dy = player.getY() - y;
        final int r = Math.max(0, radius);
        return dx * dx + dz * dz <= (double) r * r && Math.abs(dy) <= 2;
    }

    @Override
    public int nearbyCount(final String typeSpec, final int radius) {
        final ClientLevel level = mc.level;
        final LocalPlayer player = mc.player;
        if (level == null || player == null) {
            return 0;
        }
        final String needle = typeSpec == null ? "" : typeSpec.trim().toLowerCase(Locale.ROOT);
        final var area = player.getBoundingBox().inflate(Math.max(1, radius));
        int count = 0;
        for (final LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, area)) {
            if (entity == player) {
                continue;
            }
            if (matchesEntity(entity, needle)) {
                count++;
            }
        }
        return count;
    }

    /** 实体匹配：既支持 hostile/animal/player 这类分类，也支持类型名与显示名的模糊匹配。 */
    private static boolean matchesEntity(final Entity entity, final String needle) {
        if (needle.isEmpty() || "any".equals(needle) || "all".equals(needle)) {
            return true;
        }
        if ("hostile".equals(needle) || "monster".equals(needle)) {
            return "HOSTILE".equals(GameUtils.entityCategory(entity));
        }
        if ("animal".equals(needle) || "passive".equals(needle)) {
            return "ANIMAL".equals(GameUtils.entityCategory(entity));
        }
        if ("player".equals(needle)) {
            return entity instanceof Player;
        }
        final String typeId = GameUtils.entityTypeId(entity).toLowerCase(Locale.ROOT);
        final String shortType = GameUtils.shortId(typeId);
        final String name = GameUtils.entityName(entity).toLowerCase(Locale.ROOT);
        return typeId.contains(needle) || shortType.contains(needle) || name.contains(needle);
    }

    @Override
    public boolean busy() {
        // 脚本自己就是当前动作，所以这里问的是「队列里还排着别的动作吗」
        return ActionExecutor.get().queueLength() > 0;
    }
}
