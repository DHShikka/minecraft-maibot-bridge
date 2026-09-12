package com.mcai.bridge.action.tasks;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mcai.bridge.McAiBridge;
import com.mcai.bridge.action.ActionExecutor;
import com.mcai.bridge.action.Task;
import com.mcai.bridge.action.TaskResult;
import com.mcai.bridge.protocol.Json;
import com.mcai.bridge.util.CombatKit;
import com.mcai.bridge.util.GameUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code defend}：抵御 —— 清掉附近的威胁，或者按指示先跑。
 *
 * <h2>它和别的任务有什么不同</h2>
 *
 * <p>别的任务是「把这一件事做完」（走到哪、挖几个、合成什么）。抵御是<b>战术</b>：
 * 每一步做什么取决于局势 —— 血多就打、血少有吃的就先吃、没吃的就撤、被围了就退。
 * 所以它内部是一个小状态机，每轮重新评估一次局势。</p>
 *
 * <h2>怎么复用已有动作</h2>
 *
 * <p>和任务组一样：走位、攻击、吃东西、换武器全部通过
 * {@link ActionExecutor#createTask} 创建<b>真正的子任务</b>并直接驱动
 * （{@code step.advance(mc)}），不经过队列。好处是攻击的追击/对准/冷却、
 * 寻路的绕障碍、吃东西的长按逻辑全都不用重写，而且权限开关走的还是同一套。</p>
 *
 * <p>战术判断本身在 {@link DefenseTactics} 里，是纯逻辑，有单独的单测。</p>
 *
 * <h2>参数</h2>
 * <pre>
 * {"radius": 16,           威胁判定半径
 *  "maxKills": 8,          最多清掉几个
 *  "retreatHealth": 8,     血低于这个值先自保
 *  "flee": false,          只跑不打
 *  "x": 10, "y": 64, "z": -3   可选：驻守点，清完威胁后走回去
 * }
 * </pre>
 */
public final class DefendTask extends Task {

    /** 单个子动作最多跑多久。 */
    private static final long STEP_BUDGET_MS = 20_000L;
    /** 撤退距离。 */
    private static final double FLEE_DISTANCE = 12.0;
    /** 换武器/吃东西这类辅助动作，失败不算防御失败。 */
    private static final int MAX_ASSIST_TRIES = 3;

    private final int radius;
    private final int maxKills;
    private final double retreatHealth;
    private final boolean fleeOnly;
    private final Integer guardX;
    private final Integer guardY;
    private final Integer guardZ;

    private final long durationMs;
    private final long startedAt = System.currentTimeMillis();

    private Task step;
    private String stepKind = "";
    private int stepCounter;
    private int kills;
    private int attackFailures;
    private double healthAtStart = -1;
    private double lowestHealth = -1;
    private boolean returning;          // 清完威胁正在走回驻守点
    /** 换完东西之后要接着干什么：""（没有）/ "eat" / "fight"。 */
    private String pendingAfterEquip = "";
    private final List<String> log = new ArrayList<>();
    /** 最近一次扫描到的实体：撤退算方向时要用它们的坐标。 */
    private final java.util.Map<String, Entity> seen = new java.util.HashMap<>();
    /**
     * 防御期间「见过还活着」的敌人，以及已经计入击杀的那些。
     *
     * <p>击杀数靠观察得出，而不是「attack 任务返回成功就 +1」：铁剑有横扫，
     * 砍一只常常把旁边两只一起带走，只算直接目标会少报（真机上出现过
     * 「实际三只全倒，回执写清掉 1 个」）。</p>
     */
    private final java.util.Set<String> seenAlive = new java.util.HashSet<>();
    private final java.util.Set<String> countedDead = new java.util.HashSet<>();
    private String lastTargetName = "";

    private DefendTask(final String id, final JsonObject params, final long timeoutMs) {
        super(id, "defend", params, timeoutMs, true, true);
        this.radius = Math.max(3, Math.min(Json.intVal(params, "radius", 16), 64));
        this.maxKills = Math.max(1, Json.intVal(params, "maxKills", 8));
        this.retreatHealth = Json.num(params, "retreatHealth", 8.0);
        this.fleeOnly = Json.bool(params, "flee", false);
        this.durationMs = Math.max(3_000L, Json.longVal(params, "durationMs", 60_000L));
        final boolean hasGuard = Json.has(params, "x") && Json.has(params, "z");
        this.guardX = hasGuard ? Json.intVal(params, "x", 0) : null;
        // 没给 y 就用「开始抵御时玩家所在的高度」—— 写死 64 在很多世界里会让角色往天上跑
        this.guardY = hasGuard && Json.has(params, "y") ? Json.intVal(params, "y", 64) : null;
        this.guardZ = hasGuard ? Json.intVal(params, "z", 0) : null;
    }

    public static Task create(final String id, final JsonObject params, final long timeoutMs) {
        return new DefendTask(id, params, timeoutMs);
    }

    // ------------------------------------------------------------ 主循环

    @Override
    protected TaskResult onTick(final Minecraft mc) {
        final LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            fail("玩家或世界不存在");
        }
        if (healthAtStart < 0) {
            healthAtStart = player.getHealth();
            lowestHealth = healthAtStart;
            McAiBridge.LOGGER.info("[MaiBot Bridge] 开始抵御：半径 {} 格，最多清 {} 个，血线 {}",
                    radius, maxKills, retreatHealth);
        }
        lowestHealth = Math.min(lowestHealth, player.getHealth());

        // 总时长到了：不算失败，把已经做到的如实报回去
        if (System.currentTimeMillis() - startedAt > durationMs) {
            return finishSuccessful(player, "抵御时间到（" + durationMs / 1000 + " 秒）");
        }

        // ---- 有子动作在跑：先推进它
        if (step != null) {
            if (tickStep(mc)) {
                return null; // 还在跑
            }
            // 换武器/换食物的动作刚做完：接着把该干的事干完，别白换一趟
            if (handlePendingAfterEquip(mc)) {
                return null;
            }
        }

        // ---- 重新评估局势
        final List<DefenseTactics.Threat> threats = scanThreats(player);
        final DefenseTactics.SelfState self = selfState(player);
        final DefenseTactics.Settings settings = DefenseTactics.Settings.of(
                radius, retreatHealth, 3, fleeOnly);

        if (threats.isEmpty()) {
            // 威胁清完了：如果给了驻守点，先走回去
            if (guardX != null && !returning) {
                returning = true;
                final int gy = guardY != null ? guardY : (int) Math.floor(player.getY());
                startStep("move_to", moveParams(guardX, gy, guardZ, 2), "回到驻守点");
                return null;
            }
            return finishSuccessful(player, kills == 0 ? "附近没有威胁" : "威胁已清除");
        }
        returning = false;

        if (kills >= maxKills) {
            return finishSuccessful(player, "已清掉 " + kills + " 个（达到上限）");
        }

        switch (DefenseTactics.decide(self, threats, settings)) {
            case EAT -> startEat(player);
            case RETREAT -> startRetreat(player, threats);
            case FIGHT -> startFight(player, threats);
            case DONE -> {
                return finishSuccessful(player, "威胁已清除");
            }
            default -> {
                return null;
            }
        }
        return null;
    }

    /** 推进当前子动作。返回 true 表示还在跑。 */
    private boolean tickStep(final Minecraft mc) {
        final TaskResult result;
        try {
            result = step.advance(mc);
        } catch (final Throwable t) {
            McAiBridge.LOGGER.error("[MaiBot Bridge] 抵御子动作异常", t);
            finishStep(false, "子动作内部出错：" + t, mc);
            return false;
        }
        if (!result.isFinished()) {
            return true;
        }
        finishStep(result.ok, result.error, mc);
        return false;
    }

    private void finishStep(final boolean ok, final String error, final Minecraft mc) {
        final String kind = stepKind;
        final String target = lastTargetName;
        step = null;
        stepKind = "";

        if (!ok) {
            if ("attack".equals(kind) || "shoot".equals(kind)) {
                attackFailures++;
                log.add("✗ 打" + target + "没成：" + shorten(error));
                // 连续打不到就放弃这个目标（可能在墙后/够不着），换下一个
                if (attackFailures >= 3) {
                    log.add("（连续 3 次打不到，先换个目标）");
                }
            } else {
                log.add("✗ " + kind + "：" + shorten(error));
            }
            return;
        }
        if ("attack".equals(kind) || "shoot".equals(kind)) {
            attackFailures = 0;
            // 击杀数不在这里加：由扫描时「看到它倒下」来统计（横扫带走的也算得上）
            log.add(("shoot".equals(kind) ? "✓ 打了一轮 " : "✓ 打完了 ") + target
                    + "（击杀以实际倒下的为准）");
        } else if ("reload".equals(kind)) {
            log.add("✓ 换好弹了");
        } else if ("use_item".equals(kind)) {
            // 抵御里的 use_item 只用来吃东西
            log.add("✓ 吃了点东西，血量 " + Math.round(currentHealth(mc)));
        } else if ("equip".equals(kind)) {
            log.add("✓ 换上了 " + target);
        } else if ("move_to".equals(kind) && returning) {
            log.add("✓ 已回到驻守点");
        }
    }

    // ------------------------------------------------------------ 三种动作

    private void startFight(final LocalPlayer player, final List<DefenseTactics.Threat> threats) {
        final DefenseTactics.Threat target = DefenseTactics.pickTarget(threats);
        if (target == null) {
            return;
        }
        lastTargetName = target.name();

        // 手里是远程武器就用远程 —— 拿着枪还上去抡拳头，等于把枪白带了。
        // 只看**手上那把**：手上拿什么就用什么，不去背包里翻（不然「我只想挖矿，
        // 它却掏出枪乱打」）。枪的弹匣空了但有备弹时，先换弹再打。
        final CombatKit.AutoMode mode = CombatKit.autoMode(player, target.distance());
        if (mode == CombatKit.AutoMode.SHOOT) {
            startStep("shoot", CombatKit.autoAttackParams(player, target.id(), STEP_BUDGET_MS,
                            target.distance()),
                    "打 " + target.name() + "（" + Math.round(target.distance()) + " 格外，用远程）");
            return;
        }
        if (mode == CombatKit.AutoMode.RELOAD) {
            startStep("reload", CombatKit.autoAttackParams(player, target.id(), STEP_BUDGET_MS,
                            target.distance()),
                    "先换弹再打 " + target.name());
            return;
        }

        // 近战：先看看手上是不是武器 —— 空手打僵尸要打二十下，换上剑只要几下
        if (!isWeapon(player.getMainHandItem())) {
            final String weapon = findWeaponId(player);
            if (weapon != null) {
                final JsonObject equip = new JsonObject();
                equip.addProperty("item", weapon);
                lastTargetName = weapon;
                pendingAfterEquip = "fight";
                startStep("equip", equip, "换上 " + weapon);
                return;
            }
        }
        final JsonObject params = new JsonObject();
        params.addProperty("target", target.id());
        params.addProperty("count", 0);          // 打到死
        params.addProperty("durationMs", STEP_BUDGET_MS);
        startStep("attack", params, "打 " + target.name() + "（" + Math.round(target.distance()) + " 格外）");
    }

    private void startEat(final LocalPlayer player) {
        final String food = findFoodId(player);
        if (food == null) {
            startRetreat(player, new ArrayList<>());
            return;
        }
        // 手上不是食物就先换过来，再吃
        if (!isEdible(player.getMainHandItem())) {
            final JsonObject equip = new JsonObject();
            equip.addProperty("item", food);
            lastTargetName = food;
            pendingAfterEquip = "eat";
            startStep("equip", equip, "换食物 " + food);
            return;
        }
        final JsonObject use = new JsonObject();
        use.addProperty("hand", "main");
        use.addProperty("durationMs", 1800);   // 吃东西要按住一会儿
        startStep("use_item", use, "吃东西回血");
    }

    private void startRetreat(final LocalPlayer player, final List<DefenseTactics.Threat> threats) {
        final List<double[]> offsets = new ArrayList<>();
        for (final DefenseTactics.Threat threat : threats) {
            final Entity entity = seen.get(threat.id());
            if (entity != null) {
                offsets.add(new double[]{entity.getX() - player.getX(), entity.getZ() - player.getZ()});
            }
        }
        final double[] target = DefenseTactics.retreatTargetFrom(
                player.getX(), player.getY(), player.getZ(), offsets, FLEE_DISTANCE);
        startStep("move_to",
                moveParams((int) Math.floor(target[0]), (int) Math.floor(target[1]), (int) Math.floor(target[2]), 2),
                threats.isEmpty() ? "拉开距离" : "撤退（威胁 " + threats.size() + " 个）");
    }

    private static JsonObject moveParams(final int x, final int y, final int z, final int range) {
        final JsonObject params = new JsonObject();
        params.addProperty("x", x);
        params.addProperty("y", y);
        params.addProperty("z", z);
        params.addProperty("range", range);
        return params;
    }

    // ------------------------------------------------------------ 子动作启动

    private void startStep(final String action, final JsonObject params, final String description) {
        stepCounter++;
        stepKind = action;
        try {
            step = ActionExecutor.get().createTask(id + "-d" + stepCounter, action, params, STEP_BUDGET_MS);
        } catch (final IllegalArgumentException e) {
            step = null;
            stepKind = "";
            log.add("✗ " + action + " 用不了：" + e.getMessage());
        }
        if (step != null) {
            log.add("→ " + description);
        }
    }

    /** 换东西的子动作跑完之后，接着把它该干的事干完（吃东西 / 换完武器就打）。 */
    private boolean handlePendingAfterEquip(final Minecraft mc) {
        if (pendingAfterEquip.isEmpty()) {
            return false;
        }
        final String what = pendingAfterEquip;
        pendingAfterEquip = "";
        final LocalPlayer player = mc.player;
        if (player == null) {
            return false;
        }
        if ("eat".equals(what) && isEdible(player.getMainHandItem())) {
            final JsonObject use = new JsonObject();
            use.addProperty("hand", "main");
            use.addProperty("durationMs", 1800);
            startStep("use_item", use, "吃东西回血");
            return true;
        }
        if ("fight".equals(what)) {
            // 换武器这一步失败也无所谓：空手也能打，只是慢
            final List<DefenseTactics.Threat> threats = scanThreats(player);
            if (!threats.isEmpty()) {
                startFight(player, threats);
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------ 局势侦察

    /** 扫描附近的敌对生物，顺便把 uuid → 实体 记下来（撤退算方向时要用）。 */
    private List<DefenseTactics.Threat> scanThreats(final LocalPlayer player) {
        final List<DefenseTactics.Threat> list = new ArrayList<>();
        seen.clear();
        final AABB area = player.getBoundingBox().inflate(radius);
        final java.util.Set<String> aliveNow = new java.util.HashSet<>();
        for (final LivingEntity entity : player.level().getEntitiesOfClass(LivingEntity.class, area)) {
            if (entity == player) {
                continue;
            }
            final String uuid = entity.getUUID().toString();
            if (!entity.isAlive() || entity.getHealth() <= 0) {
                countKill(uuid, GameUtils.entityName(entity));
                continue;
            }
            if (!isThreat(entity)) {
                continue;
            }
            final boolean aggro = entity instanceof Mob mob && mob.getTarget() == player;
            seen.put(uuid, entity);
            aliveNow.add(uuid);
            seenAlive.add(uuid);
            list.add(new DefenseTactics.Threat(
                    uuid,
                    GameUtils.entityName(entity),
                    player.distanceTo(entity),
                    aggro,
                    Math.abs(entity.getY() - player.getY()) <= 2.0));
        }
        // 上一轮还活着、这一轮连尸体都看不到（客户端把它移除了）→ 也算消灭
        for (final String uuid : new ArrayList<>(seenAlive)) {
            if (!aliveNow.contains(uuid)) {
                countKill(uuid, "");
            }
        }
        list.sort((a, b) -> Double.compare(a.distance(), b.distance()));
        return list;
    }

    /** 记一次击杀，同一个实体只记一次。 */
    private void countKill(final String uuid, final String name) {
        if (!seenAlive.contains(uuid) || !countedDead.add(uuid)) {
            return;
        }
        kills++;
        log.add("✓ " + (name == null || name.isEmpty() ? "敌人" : name) + " 倒下了（累计 " + kills + "）");
    }

    /**
     * 什么算威胁：实现 {@link Enemy} 的（僵尸、骷髅、蜘蛛、苦力怕…），
     * 或者已经盯上某人的中立生物。用接口判断而不是逐个写类型名，模组加的生物也能覆盖到。
     */
    private static boolean isThreat(final LivingEntity entity) {
        if (entity instanceof Enemy) {
            return true;
        }
        return entity instanceof Mob mob && mob.getTarget() != null;
    }

    private DefenseTactics.SelfState selfState(final LocalPlayer player) {
        return new DefenseTactics.SelfState(
                player.getHealth(),
                player.getMaxHealth(),
                isWeapon(player.getMainHandItem()),
                findFoodId(player) != null);
    }

    private static boolean isWeapon(final ItemStack stack) {
        // 判断统一放在 CombatKit（反击那条路也要用同一套）
        return CombatKit.isMeleeWeapon(stack);
    }

    private static boolean isEdible(final ItemStack stack) {
        return !stack.isEmpty() && stack.isEdible();
    }

    /** 在背包里找一个能当武器用的东西：优先剑/三叉戟，其次斧头。 */
    private static String findWeaponId(final LocalPlayer player) {
        return CombatKit.findMeleeWeaponId(player);
    }

    /** 在背包里找一个能吃的东西；没有就返回 null。 */
    private static String findFoodId(final LocalPlayer player) {
        // 扫整个背包而不只是快捷栏：equip 子任务能把背包里的东西换到手上
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            final ItemStack stack = player.getInventory().getItem(slot);
            if (isEdible(stack)) {
                return GameUtils.itemId(stack);
            }
        }
        return null;
    }

    private static Entity findById(final LocalPlayer player, final String uuid) {
        for (final Entity entity : player.level().getEntitiesOfClass(Entity.class,
                player.getBoundingBox().inflate(64))) {
            if (entity.getUUID().toString().equals(uuid)) {
                return entity;
            }
        }
        return null;
    }

    private static double currentHealth(final Minecraft mc) {
        return mc.player == null ? 0 : mc.player.getHealth();
    }

    private static String shorten(final String text) {
        if (text == null) {
            return "（没有原因）";
        }
        return text.length() <= 60 ? text : text.substring(0, 60) + "…";
    }

    // ------------------------------------------------------------ 收尾

    private TaskResult finishSuccessful(final LocalPlayer player, final String why) {
        // 先扫一遍：收尾时「刚倒下的那几个」也要算进击杀数里
        final List<DefenseTactics.Threat> remaining = scanThreats(player);
        final JsonObject out = new JsonObject();
        out.addProperty("kills", kills);
        out.addProperty("reason", why);
        out.addProperty("healthStart", healthAtStart);
        out.addProperty("healthEnd", player.getHealth());
        out.addProperty("healthLowest", lowestHealth);
        out.addProperty("fled", fleeOnly);
        final JsonArray left = new JsonArray();
        for (final DefenseTactics.Threat threat : remaining) {
            final JsonObject t = new JsonObject();
            t.addProperty("name", threat.name());
            t.addProperty("distance", Math.round(threat.distance()));
            left.add(t);
        }
        out.add("remaining", left);
        final JsonArray steps = new JsonArray();
        for (final String line : log) {
            steps.add(line);
        }
        out.add("log", steps);
        out.addProperty("content", render(why, remaining.size()));
        return TaskResult.success(out);
    }

    private String render(final String why, final int remaining) {
        final StringBuilder sb = new StringBuilder();
        sb.append(why).append("。清掉 ").append(kills).append(" 个");
        if (lowestHealth >= 0) {
            sb.append("，血量 ").append(Math.round(healthAtStart))
                    .append(" → ").append(Math.round(currentHealth(Minecraft.getInstance())))
                    .append("（最低 ").append(Math.round(lowestHealth)).append("）");
        }
        if (remaining > 0) {
            sb.append("，还剩 ").append(remaining).append(" 个威胁");
        }
        sb.append("。");
        if (!log.isEmpty()) {
            sb.append("\n过程：");
            for (final String line : log) {
                sb.append("\n  ").append(line);
            }
        }
        return sb.toString();
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
        return maxKills <= 0 ? -1 : Math.min(1.0, (double) kills / maxKills);
    }

    @Override
    public String detail() {
        final Minecraft mc = Minecraft.getInstance();
        final int remaining = mc.player == null ? 0 : scanThreats(mc.player).size();
        final String doing = step == null ? "评估局势" : stepKind;
        return "抵御中：" + doing + "（已清 " + kills + " 个，剩 " + remaining + " 个威胁）";
    }

    @Override
    protected double realProgress() {
        return kills;
    }

    /**
     * 抵御参加卡死检测，但用的是击杀数（见 {@link #realProgress()}）：
     * 打怪时人经常站着不动，只看位移会误判。
     */
    @Override
    protected boolean watchdogApplies() {
        return true;
    }
}
