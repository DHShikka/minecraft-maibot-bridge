package com.mcai.bridge.nav;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/**
 * 逐步探测：在纯 JVM 里最少需要做什么，才能拿到可用的 {@link BlockState}。
 *
 * <p>目的见 {@link BootstrapProbe}：只要方块状态可用，就能给 A* 寻路写真正的测试。</p>
 */
public final class BootstrapProbe {

    public static void main(final String[] args) {
        step("0. SharedConstants.tryDetectVersion()", () -> {
            net.minecraft.SharedConstants.tryDetectVersion();
            return "version=" + net.minecraft.SharedConstants.getCurrentVersion().getName();
        });

        step("1. 直接触摸 Blocks.STONE", () -> {
            final BlockState stone = Blocks.STONE.defaultBlockState();
            return String.valueOf(stone) + " id=" + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(stone.getBlock());
        });

        step("2. 碰撞盒 getCollisionShape", () -> {
            final BlockState stone = Blocks.STONE.defaultBlockState();
            return String.valueOf(stone.getCollisionShape(EmptyLevel.INSTANCE, BlockPos.ZERO))
                    + " empty=" + stone.getCollisionShape(EmptyLevel.INSTANCE, BlockPos.ZERO).isEmpty();
        });

        step("3. 空气 isAir / 水的流体", () -> "air.isAir=" + Blocks.AIR.defaultBlockState().isAir()
                + " waterFluid=" + Blocks.WATER.defaultBlockState().getFluidState()
                + " waterIsWater=" + Blocks.WATER.defaultBlockState().getFluidState()
                        .is(net.minecraft.tags.FluidTags.WATER));

        step("4. 岩浆 / 仙人掌 等用到的方块", () -> "lava=" + Blocks.LAVA.defaultBlockState()
                + " cactus=" + Blocks.CACTUS.defaultBlockState()
                + " fire=" + Blocks.FIRE.defaultBlockState());

        step("5. 破坏速度 getDestroySpeed", () -> String.valueOf(
                Blocks.STONE.defaultBlockState().getDestroySpeed(EmptyLevel.INSTANCE, BlockPos.ZERO)));

        System.out.println("---- 探测结束 ----");
    }

    private interface Step {
        String run() throws Exception;
    }

    private static void step(final String name, final Step step) {
        try {
            System.out.println("[OK]   " + name + " -> " + step.run());
        } catch (final Throwable t) {
            System.out.println("[FAIL] " + name + " -> " + t.getClass().getSimpleName() + ": " + t.getMessage());
            final StackTraceElement[] trace = t.getStackTrace();
            if (trace.length > 0) {
                System.out.println("        at " + trace[0]);
            }
            // 把 ExceptionInInitializerError 包着的真实原因挖出来
            Throwable cause = t.getCause();
            for (int depth = 0; cause != null && depth < 4; depth++) {
                System.out.println("        cause: " + cause.getClass().getName() + ": " + cause.getMessage());
                final StackTraceElement[] ct = cause.getStackTrace();
                if (ct.length > 0) {
                    System.out.println("               at " + ct[0]);
                }
                cause = cause.getCause();
            }
        }
    }

    /** 给 getCollisionShape 用的极简 BlockGetter：除了高度什么都不提供。 */
    static final class EmptyLevel implements net.minecraft.world.level.BlockGetter {
        static final EmptyLevel INSTANCE = new EmptyLevel();

        @Override
        public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(final BlockPos pos) {
            return null;
        }

        @Override
        public BlockState getBlockState(final BlockPos pos) {
            return Blocks.AIR.defaultBlockState();
        }

        @Override
        public FluidState getFluidState(final BlockPos pos) {
            return Blocks.AIR.defaultBlockState().getFluidState();
        }

        @Override
        public int getHeight() {
            return 128;
        }

        @Override
        public int getMinBuildHeight() {
            return 0;
        }
    }

    private BootstrapProbe() {
    }
}
