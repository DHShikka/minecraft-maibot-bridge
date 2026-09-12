package com.mcai.bridge.action.tasks;

import com.google.gson.JsonObject;
import com.mcai.bridge.McAiBridge;
import com.mcai.bridge.action.ActionExecutor;
import com.mcai.bridge.action.Task;
import com.mcai.bridge.action.TaskResult;
import com.mcai.bridge.protocol.Json;
import com.mcai.bridge.util.GameUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Locale;

/**
 * {@code forage}：**自己去找吃的**。
 *
 * <p>饿着肚子又没食物时，按「最快能吃到嘴」的顺序试三条路：</p>
 *
 * <ol>
 *   <li><b>干草块 → 面包</b>（首选）：1 个干草块合成 9 小麦，3 小麦合成 1 面包。
 *       村庄和草原上到处都是干草块，是最快的一条路。</li>
 *   <li><b>打动物</b>：牛/猪/鸡/羊都在附近就杀一头，掉生肉（能吃，烤熟更好）。</li>
 *   <li><b>翻宝箱</b>：村庄房屋、地牢、废弃矿井里的箱子常有面包/熟肉/苹果 ——
 *       走到箱子旁打开它，把里面的东西报给 AI 看。</li>
 * </ol>
 *
 * <p>每一步都用现成的子任务（{@code scan → move_to → mine_blocks → craft → attack}），
 * 和 {@code defend} 用的是同一套编排方式：万一某条路走不通（附近没干草块、够不着），
 * 自动退到下一条，全部走不通才如实报失败并说明每一条为什么没成。</p>
 */
public final class ForageTask {

    /** 找干草块 / 箱子的半径。 */
    private static final int BLOCK_RADIUS = 40;
    /** 找动物的半径。 */
    private static final int ANIMAL_RADIUS = 32;
    /** 每个子动作最多跑多久。 */
    private static final long STEP_BUDGET_MS = 60_000L;
    /** 要做的面包数量（够吃一阵）。 */
    private static final int BREAD_TARGET = 6;

    private ForageTask() {
    }

    public static Task create(final String id, final JsonObject params, final long timeoutMs) {
        final int breadTarget = Math.max(1, Math.min(Json.intVal(params, "bread", BREAD_TARGET), 18));
        return new Task(id, "forage", params, timeoutMs, true, true) {
            private Task step;
            private String stepKind = "";
            private int stepCounter;
            /** 走到哪一步了：0=还没开始 1=干草块 2=动物 3=宝箱 4=收尾 */
            private int route;
            private BlockPos hayPos;
            private BlockPos chestPos;
            private String animalId = "";
            private String animalName = "";
            private int breadBefore;
            private final java.util.List<String> log = new java.util.ArrayList<>();

            @Override
            protected TaskResult onTick(final Minecraft mc) {
                final LocalPlayer player = mc.player;
                if (player == null || mc.level == null) {
                    fail("玩家或世界不存在");
                }
                if (step != null) {
                    return tickStep(mc);
                }
                if (route == 0) {
                    breadBefore = CraftTask.countItem(player, "bread");
                    // 手上已经有面包就先不折腾了
                    if (breadBefore > 0) {
                        return finish(player, true, "背包里已经有 " + breadBefore + " 个面包了，不用找。");
                    }
                    route = 1;
                }

                // ---- 路线 1：干草块 → 面包
                if (route == 1) {
                    if (hayPos == null) {
                        hayPos = findBlock(mc, player, Blocks.HAY_BLOCK, "hay_block");
                        if (hayPos == null) {
                            log.add("附近 " + BLOCK_RADIUS + " 格内没有干草块，换打动物。");
                            route = 2;
                        } else {
                            log.add("找到干草块 " + GameUtils.format(hayPos));
                            startStep("move_to", moveParams(hayPos, 3), "走过去");
                            return null;
                        }
                    }
                }
                if (route == 10) {
                    // 走到干草块旁边了 → 挖掉它（挖完才是「拆小麦」，别跳步）
                    startStep("mine_blocks", mineParams("hay_block", 1), "挖干草块");
                    route = 11;
                    return null;
                }
                if (route == 11) {
                    startStep("craft", craftParams("wheat", 9), "干草块拆成小麦");
                    route = 12;
                    return null;
                }
                if (route == 12) {
                    startStep("craft", craftParams("bread", breadTarget), "小麦做面包");
                    route = 13;
                    return null;
                }
                if (route == 13) {
                    final int now = CraftTask.countItem(player, "bread");
                    if (now > breadBefore) {
                        return finish(player, true, "用干草块做出了 " + (now - breadBefore) + " 个面包。");
                    }
                    log.add("干草块这条路没做出面包，换打动物。");
                    route = 2;
                }

                // ---- 路线 2：打动物
                if (route == 2) {
                    final Entity animal = findAnimal(mc, player);
                    if (animal == null) {
                        log.add("附近 " + ANIMAL_RADIUS + " 格内没有能打的动物，换翻宝箱。");
                        route = 3;
                    } else {
                        animalId = animal.getUUID().toString();
                        animalName = GameUtils.entityName(animal);
                        log.add("找到 " + animalName + "，去打它");
                        startStep("attack", attackParams(animalId), "打 " + animalName);
                        route = 21;
                        return null;
                    }
                }
                if (route == 21) {
                    final int meat = countMeat(player);
                    if (meat > 0) {
                        return finish(player, true, "打下了 " + animalName + "，拿到 " + meat
                                + " 份生肉（生肉能吃，用熔炉／烟熏炉烤熟回得更多）。");
                    }
                    log.add("没打到东西，换翻宝箱。");
                    route = 3;
                }

                // ---- 路线 3：翻宝箱
                if (route == 3) {
                    chestPos = findBlock(mc, player, Blocks.CHEST, "chest");
                    if (chestPos == null) {
                        return finish(player, false, "三条路都走不通：附近没有干草块、没有动物、也没有箱子。"
                                + "可以换个方向走走再试（mc_move_to），或者干脆先 mc_craft 手工做点吃的。");
                    }
                    startStep("move_to", moveParams(chestPos, 2), "走到箱子旁");
                    route = 31;
                    return null;
                }
                if (route == 31) {
                    startStep("use_on_block", useParams(chestPos), "打开箱子");
                    route = 32;
                    return null;
                }
                if (route == 32) {
                    return finish(player, true, "打开了 " + GameUtils.format(chestPos)
                            + " 的箱子 —— 里面的东西看上面那步的结果。"
                            + "有面包/熟肉/苹果就用 mc_use_on_block 或直接吃掉。");
                }
                return null;
            }

            /** 推进当前子任务；它结束后由下面这段决定下一步。 */
            private TaskResult tickStep(final Minecraft mc) {
                TaskResult result;
                try {
                    result = step.advance(mc);
                } catch (final Throwable t) {
                    McAiBridge.LOGGER.error("[MaiBot Bridge] 觅食子动作异常", t);
                    result = null;
                }
                if (result != null && !result.isFinished()) {
                    return null;
                }
                final String kind = stepKind;
                final boolean ok = result != null && result.ok;
                step = null;
                stepKind = "";
                if (!ok) {
                    // 子动作失败：记一笔，然后按当前 route 往下退（下一 tick 会做判断）
                    log.add("✗ " + kind + " 没成" + (result == null || result.error == null
                            ? "" : "：" + shorten(result.error)));
                    if (route == 1) {
                        route = 2;
                    } else if (route == 21) {
                        route = 3;
                    } else if (route == 11 || route == 12 || route == 13) {
                        route = 2;
                    } else if (route == 31 || route == 32) {
                        return finish(mc.player, false, "翻箱子也没成。");
                    } else {
                        route = 2;
                    }
                    return null;
                }
                // 成功：让 route 前进一格（上面的状态机会接住）
                // 成功：**只推进「入口型」的两个状态**，其余一律交给各自的分支去推进 ——
                // 这条规则是被真机打出来的：以前这里把 11/12/13 也一起推进了，
                // 于是「挖到干草块」之后直接跳到「做面包」，中间的拆小麦被吃掉，
                // 报「材料不够，还缺：wheat、wheat、wheat」。
                switch (route) {
                    case 1 -> route = 10;      // 走到干草块了 → 下一步挖它
                    case 31 -> route = 32;     // 走到箱子旁 → 下一步开箱
                    default -> { }             // 其它状态由 onTick 里对应的分支自己推进
                }
                return null;
            }

            private void startStep(final String action, final JsonObject params, final String what) {
                stepCounter++;
                stepKind = action;
                try {
                    step = ActionExecutor.get().createTask(id + "-f" + stepCounter, action, params, STEP_BUDGET_MS);
                } catch (final Throwable t) {
                    step = null;
                    stepKind = "";
                    log.add("✗ " + action + " 用不了：" + t.getMessage());
                    return;
                }
                if (step != null) {
                    log.add("→ " + what);
                }
            }

            private TaskResult finish(final LocalPlayer player, final boolean ok, final String why) {
                final JsonObject out = new JsonObject();
                out.addProperty("success", ok);
                out.addProperty("reason", why);
                out.addProperty("breadBefore", breadBefore);
                out.addProperty("breadNow", CraftTask.countItem(player, "bread"));
                out.addProperty("meat", countMeat(player));
                final com.google.gson.JsonArray steps = new com.google.gson.JsonArray();
                for (final String line : log) {
                    steps.add(line);
                }
                out.add("steps", steps);
                out.addProperty("hint", "面包/熟肉到手后，饿了会自动吃（[combat] autoEat）。");
                return ok ? TaskResult.success(out) : TaskResult.fail(why + "（" + String.join("；", log) + "）");
            }

            @Override
            protected void onCancel(final Minecraft mc) {
                // 子动作由执行器统一收尾：这里只把手上的引用放掉，
                // 免得它继续在下一 tick 里推进（真取消由执行器负责）。
                step = null;
                stepKind = "";
            }

            @Override
            public String detail() {
                return "觅食（" + switch (route) {
                    case 0, 1 -> "找干草块做面包";
                    case 11, 12, 13 -> "做面包";
                    case 2, 21 -> "打动物";
                    default -> "翻宝箱";
                } + "）";
            }
        };
    }

    // ------------------------------------------------------------------ 工具

    /** 在玩家周围找一个方块（最近的那个）。 */
    private static BlockPos findBlock(final Minecraft mc, final LocalPlayer player,
                                      final net.minecraft.world.level.block.Block block,
                                      final String what) {
        final BlockPos origin = player.blockPosition();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (final BlockPos pos : BlockPos.betweenClosed(
                origin.offset(-BLOCK_RADIUS, -6, -BLOCK_RADIUS),
                origin.offset(BLOCK_RADIUS, 6, BLOCK_RADIUS))) {
            final BlockState state = mc.level.getBlockState(pos);
            if (!state.is(block)) {
                continue;
            }
            final double d = pos.distSqr(origin);
            if (d < bestDist) {
                bestDist = d;
                best = pos.immutable();
            }
        }
        return best;
    }

    /** 找最近的能吃的动物（牛/猪/鸡/羊……只要不是村民这类）。 */
    private static Entity findAnimal(final Minecraft mc, final LocalPlayer player) {
        Entity best = null;
        double bestDist = (double) ANIMAL_RADIUS * ANIMAL_RADIUS;
        for (final Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof final Animal animal) || !animal.isAlive()) {
                continue;
            }
            if (!com.mcai.bridge.util.CombatKit.isValidTarget(mc, player, entity)) {
                continue;
            }
            final double d = player.distanceToSqr(entity);
            if (d < bestDist) {
                bestDist = d;
                best = entity;
            }
        }
        return best;
    }

    /** 背包里的生肉/熟肉总数（判断「打动物有没有收获」）。 */
    private static int countMeat(final LocalPlayer player) {
        int total = 0;
        for (final String id : new String[]{"beef", "porkchop", "chicken", "mutton", "rabbit",
                "cooked_beef", "cooked_porkchop", "cooked_chicken", "cooked_mutton"}) {
            total += CraftTask.countItem(player, id);
        }
        return total;
    }

    private static String shorten(final String text) {
        if (text == null) {
            return "";
        }
        final String one = text.replace('\n', ' ');
        return one.length() > 80 ? one.substring(0, 80) + "…" : one;
    }

    private static JsonObject moveParams(final BlockPos pos, final int range) {
        final JsonObject p = new JsonObject();
        p.addProperty("x", pos.getX());
        p.addProperty("y", pos.getY());
        p.addProperty("z", pos.getZ());
        p.addProperty("range", range);
        return p;
    }

    private static JsonObject mineParams(final String block, final int count) {
        final JsonObject p = new JsonObject();
        p.addProperty("block", block);
        p.addProperty("count", count);
        return p;
    }

    private static JsonObject craftParams(final String item, final int count) {
        final JsonObject p = new JsonObject();
        p.addProperty("item", item.toLowerCase(Locale.ROOT));
        p.addProperty("count", count);
        return p;
    }

    private static JsonObject attackParams(final String uuid) {
        final JsonObject p = new JsonObject();
        p.addProperty("target", uuid);
        p.addProperty("count", 0);
        p.addProperty("durationMs", 20_000L);
        return p;
    }

    private static JsonObject useParams(final BlockPos pos) {
        final JsonObject p = new JsonObject();
        p.addProperty("x", pos.getX());
        p.addProperty("y", pos.getY());
        p.addProperty("z", pos.getZ());
        return p;
    }
}
