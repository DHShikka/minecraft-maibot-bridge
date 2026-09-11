package com.mcai.bridge.nav;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;

/**
 * 用真实的 Minecraft 客户端世界实现 {@link BlockWorld}。
 *
 * <p>这是整个寻路包里<b>唯一</b>一处认识 Minecraft 方块的代码。上层（{@link CalculationContext}、
 * {@link Movement}、{@link PathFinder}）只依赖 {@link BlockWorld} 这个接口，
 * 所以它们可以在纯 JVM 里被测试。</p>
 */
public final class VanillaBlockWorld implements BlockWorld {

    /** 危险方块：站上去或走进去会受伤甚至致死。 */
    private static final Set<Block> DANGEROUS = Set.of(
            Blocks.LAVA, Blocks.FIRE, Blocks.SOUL_FIRE, Blocks.MAGMA_BLOCK, Blocks.CACTUS,
            Blocks.SWEET_BERRY_BUSH, Blocks.POWDER_SNOW, Blocks.WITHER_ROSE, Blocks.CAMPFIRE,
            Blocks.SOUL_CAMPFIRE, Blocks.LAVA_CAULDRON, Blocks.COBWEB, Blocks.NETHER_PORTAL,
            Blocks.END_PORTAL, Blocks.SCULK_SHRIEKER, Blocks.POINTED_DRIPSTONE
    );

    /** 可攀爬方块。 */
    private static final Set<Block> CLIMBABLE = Set.of(
            Blocks.LADDER, Blocks.VINE, Blocks.SCAFFOLDING, Blocks.TWISTING_VINES,
            Blocks.WEEPING_VINES, Blocks.CAVE_VINES, Blocks.CAVE_VINES_PLANT
    );

    private final LevelReader level;
    private final LocalPlayer player;

    public VanillaBlockWorld(final LevelReader level, final LocalPlayer player) {
        this.level = level;
        this.player = player;
    }

    // -------------------------------------------------------------- 缓存键

    private static BlockPos pos(final int x, final int y, final int z) {
        return new BlockPos(x, y, z);
    }

    private BlockState state(final int x, final int y, final int z) {
        return level.getBlockState(pos(x, y, z));
    }

    // ------------------------------------------------------------ 方块性质

    @Override
    public boolean isPassable(final int x, final int y, final int z) {
        final BlockState state = state(x, y, z);
        if (state.isAir()) {
            return true;
        }
        if (DANGEROUS.contains(state.getBlock())) {
            return false;
        }
        // 水流可以穿过（游泳）
        if (state.getFluidState().is(FluidTags.WATER)) {
            return true;
        }
        if (state.getFluidState().is(FluidTags.LAVA)) {
            return false;
        }
        return state.getCollisionShape(level, pos(x, y, z)).isEmpty();
    }

    @Override
    public boolean isWalkOn(final int x, final int y, final int z) {
        final BlockState state = state(x, y, z);
        if (state.isAir()) {
            return false;
        }
        if (DANGEROUS.contains(state.getBlock())) {
            return false;
        }
        // 水和岩浆都不能站
        if (!state.getFluidState().isEmpty()) {
            return false;
        }
        return !state.getCollisionShape(level, pos(x, y, z)).isEmpty();
    }

    @Override
    public boolean isDangerous(final int x, final int y, final int z) {
        return DANGEROUS.contains(state(x, y, z).getBlock());
    }

    @Override
    public boolean isWater(final int x, final int y, final int z) {
        return state(x, y, z).getFluidState().is(FluidTags.WATER);
    }

    @Override
    public boolean isClimbable(final int x, final int y, final int z) {
        return CLIMBABLE.contains(state(x, y, z).getBlock());
    }

    @Override
    public boolean isReplaceable(final int x, final int y, final int z) {
        final BlockState state = state(x, y, z);
        if (state.isAir()) {
            return true;
        }
        if (!state.getFluidState().isEmpty()) {
            return true;
        }
        // 1.20 移除了公开的无参 canBeReplaced()，用「没有碰撞体积」近似
        // （覆盖草、花、火把、雪层这些实际会遇到的）
        return state.getCollisionShape(level, pos(x, y, z)).isEmpty();
    }

    @Override
    public double miningCost(final int x, final int y, final int z) {
        final BlockState state = state(x, y, z);
        if (state.isAir() || !state.getFluidState().isEmpty()) {
            return 0;
        }
        final float hardness = state.getDestroySpeed(level, pos(x, y, z));
        if (hardness < 0) {
            return ActionCosts.COST_INF; // 基岩之类
        }
        final boolean correctTool = player != null && player.hasCorrectToolForDrops(state);
        // 「没有正确工具能不能挖掉」在客户端不好精确判断，这里按硬度粗判：
        // 很硬的方块（>= 1.5）没有正确工具基本不值得挖，软的直接徒手挖。
        final boolean canHarvest = hardness < 1.5f;
        return ActionCosts.miningCost(hardness, correctTool, canHarvest);
    }

    @Override
    public boolean isLoaded(final int x, final int z) {
        // BlockGetter 没有区块级查询；LevelReader 有。构造时保证传进来的是 LevelReader。
        return level.hasChunkAt(pos(x, 0, z));
    }

    @Override
    public int minBuildHeight() {
        return level.getMinBuildHeight();
    }

    @Override
    public int maxBuildHeight() {
        return level.getMaxBuildHeight();
    }
}
