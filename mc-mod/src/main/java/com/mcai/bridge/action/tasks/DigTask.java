package com.mcai.bridge.action.tasks;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mcai.bridge.action.ActionExecutor;
import com.mcai.bridge.action.Task;
import com.mcai.bridge.action.TaskResult;
import com.mcai.bridge.protocol.Json;
import com.mcai.bridge.snapshot.StateCollector;
import com.mcai.bridge.util.GameUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code dig_shaft}：往下挖一条**阶梯矿道**。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>铁在 y≈15~60、钻石在 y&lt;16，光靠 {@code mine_blocks}（只在身边水平扫一圈）
 * 是够不到的。没有向下挖的能力，「生存模式从零搭地狱门」这条链在第二道关卡就断了。</p>
 *
 * <h2>为什么是阶梯而不是竖井</h2>
 *
 * <p>竖井（原地往下挖）下去容易上来难 —— 得一路垫方块跳回来。
 * 阶梯每层只下降一格、同时前进一格，**原路走回去就行**，而且不会把自己困在井底。
 * 代价是每层要挖两个方块（脚下那格 + 同一层前方那格），换来的是可逆性，值。</p>
 *
 * <h2>安全第一</h2>
 *
 * <p>往下挖最怕挖穿到岩浆。每一步开挖前都会先看落点：</p>
 * <ul>
 *   <li>落点那一层的方块是**岩浆**（或别的危险流体）→ 立刻停，不挖；</li>
 *   <li>落点下面没有可站立的支撑（会是深坑/悬空）→ 停；</li>
 *   <li>区块没加载 → 停（不能在未知地形上乱挖）。</li>
 * </ul>
 *
 * <p>停下来的原因会原样回传，LLM 可以据此决定绕开还是换个方向。</p>
 *
 * <h2>参数</h2>
 * <pre>
 * {"depth": 30,              往下挖多少格（1~120）
 *  "direction": "east",      往哪个方向下（north/south/east/west），不给就按当前朝向
 *  "stopAt": "iron_ore"}     可选：挖到这种东西就停（挖到矿就回头）
 * </pre>
 */
public final class DigTask extends Task {

    /** 单个子动作（挖一格 / 走一步）的预算。 */
    private static final long STEP_BUDGET_MS = 15_000L;
    /** 一个子动作最多连续失败几次就放弃。 */
    private static final int MAX_STEP_FAILURES = 3;
    /** 允许白白下坠几格（山坡/小坎是正常的），超过就当成悬崖停下。 */
    private static final int MAX_FREE_FALL = 3;

    private final int depth;
    private final String directionSpec;
    private final String stopAt;

    private Direction direction;
    private BlockPos lastPos = BlockPos.ZERO;
    /** 上一层预期的落脚高度；实际比它低太多说明前方是悬崖。 */
    private int expectedY = Integer.MIN_VALUE;
    /** 正在走向的下一层高度；到位了才计入 dug。 */
    private int pendingLandingY = Integer.MIN_VALUE;
    private int dug;
    private int stepFailures;
    private int subCounter;
    private boolean minedBelow;
    private boolean minedFront;

    private Task step;
    private String stepKind = "";
    private final List<String> log = new ArrayList<>();
    private final List<JsonObject> found = new ArrayList<>();
    private String stopReason = "";

    private DigTask(final String id, final JsonObject params, final long timeoutMs) {
        super(id, "dig_shaft", params, timeoutMs, true, true);
        this.depth = Math.max(1, Math.min(Json.intVal(params, "depth", 20), 120));
        this.directionSpec = Json.str(params, "direction", "").trim();
        this.stopAt = Json.str(params, "stopAt", "").trim();
    }

    public static Task create(final String id, final JsonObject params, final long timeoutMs) {
        final int depth = Json.intVal(params, "depth", 20);
        if (depth <= 0) {
            return new MoveToTask.FailingTask(id, "dig_shaft", params,
                    "depth 必须大于 0（往下挖多少格）。");
        }
        return new DigTask(id, params, timeoutMs);
    }

    // ------------------------------------------------------------ 主循环

    @Override
    protected TaskResult onTick(final Minecraft mc) {
        final LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            fail("玩家或世界不存在");
        }
        if (direction == null) {
            direction = pickDirection(player);
            log.add("往下挖 " + depth + " 格，方向 " + direction.getName());
        }
        if (dug >= depth) {
            return finish(player);
        }

        if (step != null) {
            final TaskResult running = tickStep(mc);
            if (running == null) {
                return null; // 还在跑
            }
            if (!running.ok) {
                stepFailures++;
                log.add("✗ " + stepKind + "：" + shorten(running.error));
                if (stepFailures >= MAX_STEP_FAILURES) {
                    stopReason = "连续 " + stepFailures + " 个子步骤失败，停下";
                    return finish(player);
                }
            } else {
                stepFailures = 0;
            }
        }

        // ---- 上一步是「往下走一层」：**确认高度真的降了才算挖了一层**。
        // 这里以前是「启动移动就 dug++」，结果在出生点这种山脊地形上，
        // 前方本来就是空气、move_to 瞬间返回，于是 0.9 秒「挖了 20 层」而玩家一动没动。
        if (pendingLandingY != Integer.MIN_VALUE) {
            final int y = player.blockPosition().getY();
            if (y <= pendingLandingY) {
                dug++;
                expectedY = y;
                pendingLandingY = Integer.MIN_VALUE;
                minedBelow = false;
                minedFront = false;
            } else {
                pendingLandingY = Integer.MIN_VALUE;
                stepFailures++;
                if (stepFailures >= MAX_STEP_FAILURES) {
                    stopReason = "走不到下一层（目标高度 y=" + pendingLandingY
                            + "，玩家还在 y=" + y + "），可能被挡住或够不着";
                    return finish(player);
                }
                minedBelow = false;
                minedFront = false;
                return null;
            }
        }

        // ---- 规划下一步
        final BlockPos feet = player.blockPosition();
        if (!feet.equals(lastPos) && lastPos != BlockPos.ZERO) {
            // 玩家位置变了（掉下去/被推开），重新对齐这一层的进度
            minedBelow = false;
            minedFront = false;
        }
        lastPos = feet;

        final BlockPos front = feet.offset(direction.getStepX(), 0, direction.getStepZ());
        final BlockPos landing = front.below();
        final BlockPos support = landing.below();

        if (!mc.level.hasChunkAt(support)) {
            stopReason = "前方区块还没加载，停下（不在未知地形上乱挖）";
            return finish(player);
        }

        // 落点下方是危险流体 → 停（挖穿到岩浆是最要命的失败）
        final BlockState supportState = mc.level.getBlockState(support);
        if (GameUtils.isDangerous(supportState)) {
            stopReason = "落点下面是 " + GameUtils.blockId(supportState) + "（危险），停下";
            return finish(player);
        }

        // 落点这一层有岩浆/火 → 停
        final BlockState landingState = mc.level.getBlockState(landing);
        if (GameUtils.isDangerous(landingState)) {
            stopReason = "落点是 " + GameUtils.blockId(landingState) + "（危险），停下";
            return finish(player);
        }

        // 站在山坡上时，前方一格下面常常是空气（那就是斜坡），这**不是**深坑 ——
        // 一开始把这种情况也拒了，结果在山地出生点一格都挖不下去（真机自检抓到的）。
        // 正确的做法是允许挖，但盯着「有没有一直往下掉」：
        if (expectedY != Integer.MIN_VALUE && feet.getY() < expectedY - MAX_FREE_FALL) {
            stopReason = "前方是悬崖/深坑（已经掉了 " + (expectedY - feet.getY()) + " 格），停下";
            return finish(player);
        }

        // 前方下方没有支撑 → 走到那会直接掉下去，别去
        if (mc.level.getBlockState(landing).isAir() && mc.level.getBlockState(support).isAir()) {
            stopReason = "前方悬空（山坡边缘/悬崖），走过去会直接掉下去，停下。"
                    + "换个方向重试，或者先用 Minecraft 指令/垫方块过去。";
            return finish(player);
        }

        // ---- 挖到想要的矿就停
        if (!stopAt.isEmpty()) {
            checkNearbyOres(mc, player);
            if (!stopReason.isEmpty()) {
                return finish(player);
            }
        }

        // ---- 挖落点那一格
        if (!minedBelow) {
            if (!landingState.isAir()) {
                startMine(landing, "挖落点 " + GameUtils.format(landing));
                minedBelow = true;
                return null;
            }
            minedBelow = true;
        }

        // ---- 挖前方同一层那格（站的进去）
        if (!minedFront) {
            final BlockState frontState = mc.level.getBlockState(front);
            if (GameUtils.isDangerous(frontState)) {
                stopReason = "前方是 " + GameUtils.blockId(frontState) + "（危险），停下";
                return finish(player);
            }
            if (!frontState.isAir()) {
                startMine(front, "挖通道 " + GameUtils.format(front));
                minedFront = true;
                return null;
            }
            minedFront = true;
        }

        // ---- 走进去（下降一格）。注意：这里**不**记账，等确认高度降了再说
        startStep("move_to", moveParams(front.getX(), landing.getY(), front.getZ()),
                "下到第 " + (dug + 1) + " 层");
        pendingLandingY = landing.getY();
        return null;
    }

    /** 往下挖的方向：优先用参数，其次是玩家当前水平朝向；都不行就用东。 */
    private Direction pickDirection(final LocalPlayer player) {
        if (!directionSpec.isEmpty()) {
            final Direction parsed = GameUtils.parseDirection(directionSpec, Direction.EAST);
            if (parsed.getAxis().isHorizontal()) {
                return parsed;
            }
        }
        final Direction facing = player.getDirection();
        if (facing.getAxis().isHorizontal()) {
            return facing;
        }
        return Direction.EAST;
    }

    /** 挖到 stopAt 指定的东西就收工。 */
    private void checkNearbyOres(final Minecraft mc, final LocalPlayer player) {
        final BlockPos origin = player.blockPosition();
        for (int dy = -6; dy <= 2; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                for (int dz = -4; dz <= 4; dz++) {
                    final BlockPos pos = origin.offset(dx, dy, dz);
                    if (!mc.level.hasChunkAt(pos)) {
                        continue;
                    }
                    final BlockState state = mc.level.getBlockState(pos);
                    if (state.isAir() || !GameUtils.matchesBlock(state, stopAt)) {
                        continue;
                    }
                    final JsonObject o = new JsonObject();
                    o.add("pos", StateCollector.blockPos(pos));
                    o.addProperty("block", GameUtils.blockId(state));
                    o.addProperty("name", GameUtils.blockName(state));
                    found.add(o);
                    stopReason = "挖到了 " + GameUtils.blockName(state) + " @ " + GameUtils.format(pos);
                    return;
                }
            }
        }
    }

    // ------------------------------------------------------------ 子动作

    private TaskResult tickStep(final Minecraft mc) {
        final TaskResult result;
        try {
            result = step.advance(mc);
        } catch (final Throwable t) {
            step = null;
            return TaskResult.fail("子动作内部出错：" + t);
        }
        if (!result.isFinished()) {
            return null;
        }
        step = null;
        return result;
    }

    private void startStep(final String action, final JsonObject params, final String description) {
        subCounter++;
        stepKind = action;
        try {
            step = ActionExecutor.get().createTask(id + "-g" + subCounter, action, params, STEP_BUDGET_MS);
            log.add("→ " + description);
        } catch (final IllegalArgumentException e) {
            step = null;
            stepKind = "";
            log.add("✗ " + action + " 用不了：" + e.getMessage());
        }
    }

    private void startMine(final BlockPos pos, final String description) {
        final JsonObject params = new JsonObject();
        params.addProperty("x", pos.getX());
        params.addProperty("y", pos.getY());
        params.addProperty("z", pos.getZ());
        startStep("mine", params, description);
    }

    private static JsonObject moveParams(final int x, final int y, final int z) {
        final JsonObject params = new JsonObject();
        params.addProperty("x", x);
        params.addProperty("y", y);
        params.addProperty("z", z);
        params.addProperty("range", 0);
        return params;
    }

    // ------------------------------------------------------------ 收尾

    private TaskResult finish(final LocalPlayer player) {
        final JsonObject out = new JsonObject();
        out.addProperty("dug", dug);
        out.addProperty("requestedDepth", depth);
        out.addProperty("direction", direction == null ? "?" : direction.getName());
        out.add("pos", StateCollector.blockPos(player.blockPosition()));
        out.addProperty("startY", player.blockPosition().getY() + dug);
        if (!stopReason.isEmpty()) {
            out.addProperty("stoppedBecause", stopReason);
        }
        final JsonArray ores = new JsonArray();
        for (final JsonObject ore : found) {
            ores.add(ore);
        }
        out.add("found", ores);
        final JsonArray steps = new JsonArray();
        for (final String line : log) {
            steps.add(line);
        }
        out.add("log", steps);

        if (dug == 0) {
            return TaskResult.fail("一格都没挖下去。"
                    + (stopReason.isEmpty() ? "可能是起点就在空中或者被挡住。" : stopReason));
        }
        final StringBuilder sb = new StringBuilder("往下挖了 ").append(dug).append(" 格（")
                .append(direction == null ? "?" : direction.getName()).append("方向），现在在 y=")
                .append(player.blockPosition().getY());
        if (!found.isEmpty()) {
            sb.append("，途中看到 ").append(found.size()).append(" 处矿石");
        }
        if (!stopReason.isEmpty()) {
            sb.append("。停下的原因：").append(stopReason);
        }
        out.addProperty("content", sb.toString());
        return TaskResult.success(out);
    }

    private static String shorten(final String text) {
        if (text == null) {
            return "（没有原因）";
        }
        return text.length() <= 70 ? text : text.substring(0, 70) + "…";
    }

    @Override
    protected void onCancel(final Minecraft mc) {
        if (step != null) {
            try {
                step.cancelNow(mc);
            } catch (final Throwable ignored) {
                // 收尾异常忽略
            }
            step = null;
        }
    }

    @Override
    public double progress() {
        return Math.min(1.0, (double) dug / Math.max(1, depth));
    }

    @Override
    public String detail() {
        return "向下挖第 " + (dug + 1) + "/" + depth + " 层（" + (stepKind.isEmpty() ? "规划" : stepKind) + "）";
    }

    @Override
    protected double realProgress() {
        return dug;
    }

    /** 挖矿时人基本在原地，靠 dug 计数判断有没有进展。 */
    @Override
    protected boolean watchdogApplies() {
        return true;
    }
}
