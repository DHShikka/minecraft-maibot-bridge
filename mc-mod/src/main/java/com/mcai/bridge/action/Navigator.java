package com.mcai.bridge.action;

import com.mcai.bridge.McAiBridge;
import com.mcai.bridge.nav.CalculationContext;
import com.mcai.bridge.nav.Goal;
import com.mcai.bridge.nav.Goals;
import com.mcai.bridge.nav.PathExecutor;
import com.mcai.bridge.nav.PathFinder;
import com.mcai.bridge.nav.VanillaBlockWorld;
import com.mcai.bridge.util.GameUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

/**
 * 走到某个位置的执行器 —— 所有移动类任务（移动、跟随、挖矿、放置、追击）共用的门面。
 *
 * <p><b>对外 API 保持不变</b>（{@link #setGoal} / {@link #step} / {@link #releaseControl}
 * 等），内部已经换成 Baritone 式的引擎：{@link PathFinder} 用 {@code Movement} 做 A*，
 * {@link PathExecutor} 逐步执行并带回溯。这样上层所有任务不需要改动。</p>
 *
 * <p>每 tick 调用一次 {@link #step}，内部会驱动 {@link InputController} 让玩家真的走过去。</p>
 */
public final class Navigator {

    /** A* 最大搜索节点数。每个节点现在会生成几十个候选移动，所以比旧实现给得少一些。 */
    private static final int MAX_NODES = 4000;
    /** A* 搜索范围：起点外扩多少格。 */
    private static final int MAX_RANGE = 64;
    /** 最多重规划几次就放弃。 */
    private static final int MAX_REPLANS = 10;
    /** 判定到达的水平距离。 */
    private static final double ARRIVE_DISTANCE = 1.0;

    public enum Status {
        /** 已到达目标范围。 */
        ARRIVED,
        /** 正在移动。 */
        MOVING,
        /** 无法到达，{@link #failureReason()} 里有原因。 */
        FAILED
    }

    private final ClientLevel level;

    private BlockPos goalPos;
    private int goalRadius;
    private Goal goal;

    private PathExecutor executor;
    private int replans;
    private int visitedNodes;
    private int generatedMoves;
    private String failure;
    private boolean arrived;

    public Navigator(final ClientLevel level) {
        this.level = level;
    }

    /** 设置目标与允许的到达半径（水平，格）。 */
    public void setGoal(final BlockPos target, final int radius) {
        if (target != null && target.equals(goalPos) && this.goalRadius == Math.max(0, radius)) {
            return; // 目标没变，不要白扔掉已经算好的路径
        }
        this.goalPos = target;
        this.goalRadius = Math.max(0, radius);
        this.goal = target == null ? null : Goals.near(target, this.goalRadius);
        reset();
    }

    private void reset() {
        if (executor != null) {
            executor.releaseControl();
        }
        executor = null;
        replans = 0;
        visitedNodes = 0;
        generatedMoves = 0;
        failure = null;
        arrived = false;
    }

    public BlockPos goal() {
        return goalPos;
    }

    public int pathLength() {
        return executor == null ? 0 : executor.pathLength();
    }

    public int visitedNodes() {
        return visitedNodes;
    }

    public String failureReason() {
        return failure;
    }

    /** 剩余距离（水平，格）。目标为空时返回 -1。 */
    public double remainingDistance(final LocalPlayer player) {
        if (goalPos == null || player == null) {
            return -1;
        }
        final double dx = player.getX() - (goalPos.getX() + 0.5);
        final double dz = player.getZ() - (goalPos.getZ() + 0.5);
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * 推进一步。
     *
     * @param speedFactor 1.0 正常；小于 1 表示不疾跑（用于「悄悄靠近」）
     */
    public Status step(final Minecraft mc, final double speedFactor) {
        final LocalPlayer player = mc.player;
        if (player == null || level == null) {
            failure = "玩家或世界不存在";
            return Status.FAILED;
        }
        if (goal == null || goalPos == null) {
            failure = "没有设置目标";
            return Status.FAILED;
        }
        if (player.isPassenger()) {
            failure = "玩家正乘坐载具，无法自主行走（请先下船/下车）";
            return Status.FAILED;
        }

        if (goal.isInGoal(player.blockPosition())) {
            releaseControl();
            arrived = true;
            return Status.ARRIVED;
        }

        if (executor == null) {
            if (!plan(mc, player, speedFactor)) {
                return Status.FAILED;
            }
            if (executor == null) {
                // plan() 发现已经在目标里了
                releaseControl();
                arrived = true;
                return Status.ARRIVED;
            }
        }

        final PathExecutor.Status status = executor.tick(mc);
        switch (status) {
            case RUNNING -> {
                return Status.MOVING;
            }
            case DONE -> {
                executor.releaseControl();
                executor = null;
                if (goal.isInGoal(player.blockPosition())) {
                    arrived = true;
                    return Status.ARRIVED;
                }
                // 路径只走了一段就结束（部分路径），或者中途世界变了 → 重规划
                if (++replans >= MAX_REPLANS) {
                    failure = "已经重规划 " + replans + " 次仍没能到达 " + goal.describe()
                            + "，最后停在 " + GameUtils.format(player.blockPosition()) + "。"
                            + "目标可能被完全封死（需要挖穿或搭桥），也可能需要先准备方块。";
                    return Status.FAILED;
                }
                return Status.MOVING;
            }
            default -> {
                // FAILED
                final String reason = executor.failureReason();
                executor.releaseControl();
                executor = null;
                if (++replans >= MAX_REPLANS) {
                    failure = (reason == null ? "路径执行失败" : reason)
                            + "（已重规划 " + replans + " 次）";
                    return Status.FAILED;
                }
                McAiBridge.LOGGER.debug("[MaiBot Bridge] 路径执行失败，准备重规划：{}", reason);
                return Status.MOVING;
            }
        }
    }

    /** 重新规划一条路径。返回 false 表示完全找不到任何可行方向。 */
    private boolean plan(final Minecraft mc, final LocalPlayer player, final double speedFactor) {
        if (goal.isInGoal(player.blockPosition())) {
            return true; // executor 保持为 null，step() 会识别为已到达
        }

        final boolean sprint = speedFactor >= 1.0;
        final CalculationContext context = new CalculationContext(
                new VanillaBlockWorld(level, player),
                true,                      // allowBreak：允许挖穿挡路的方块
                true,                      // allowPlace：允许垫方块
                sprint,                    // allowSprint
                true,                      // canDigDown：允许向下挖
                PathFinder.MAX_FALL,
                ActionCostsShortcutLimit(), // 为了抄近路最多肯花多少挖掘代价
                hasPlaceableBlock(player)  // 手上有方块才考虑「垫脚上」
        );

        final PathFinder finder = new PathFinder(context, goal, MAX_NODES, MAX_RANGE);
        final PathFinder.Path path = finder.findPath(player.blockPosition());
        visitedNodes = finder.lastVisitedNodes;
        generatedMoves = finder.lastGeneratedMoves;

        if (path.isEmpty()) {
            failure = "从 " + GameUtils.format(player.blockPosition()) + " 找不到通往 "
                    + goal.describe() + " 的路（搜索了 " + visitedNodes + " 个位置、"
                    + generatedMoves + " 个候选动作）。"
                    + "常见原因：目标在完全封闭的空间里且中间隔着挖不动的方块，"
                    + "或者垂直落差超过 " + PathFinder.MAX_FALL + " 格。"
                    + "可以先用 mc_scan_blocks 看看目标附近到底是什么情况。";
            return false;
        }

        executor = new PathExecutor(context, path, sprint);
        McAiBridge.LOGGER.debug("[MaiBot Bridge] 规划出 {} 步路径（访问 {} 节点，生成 {} 个候选，{}到达目标）",
                path.length(), visitedNodes, generatedMoves, path.reachedGoal() ? "" : "未");
        return true;
    }

    /** 为了抄近路最多肯花多少挖掘代价：走路 8 格的代价。 */
    private static double ActionCostsShortcutLimit() {
        return com.mcai.bridge.nav.ActionCosts.WALK_ONE_BLOCK_COST * 8;
    }

    /** 背包（含快捷栏）里有没有方块类物品。没有的话「垫脚上」这种走法不该被规划出来。 */
    private static boolean hasPlaceableBlock(final Player player) {
        final Inventory inv = player.getInventory();
        for (int slot = 0; slot < inv.getContainerSize(); slot++) {
            final ItemStack stack = inv.getItem(slot);
            if (!stack.isEmpty() && stack.getItem() instanceof BlockItem) {
                return true;
            }
        }
        return false;
    }

    /** 释放输入控制权（任务结束时调用）。 */
    public void releaseControl() {
        InputController.release();
        if (executor != null) {
            executor.releaseControl();
        }
    }

    /** 供上层显示用的简短描述。 */
    public String describe() {
        if (goal == null) {
            return "无目标";
        }
        final double remaining = remainingDistance(Minecraft.getInstance().player);
        return String.format(Locale.ROOT, "%s，剩余约 %.1f 格（路径 %d 步）",
                goal.describe(), Math.max(0, remaining), pathLength());
    }

    public boolean hasArrived() {
        return arrived;
    }
}
