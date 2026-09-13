package com.mcai.bridge.action;

import com.google.gson.JsonObject;
import com.mcai.bridge.BridgeConfig;
import com.mcai.bridge.McAiBridge;
import com.mcai.bridge.action.tasks.AttackTask;
import com.mcai.bridge.action.tasks.BasicTasks;
import com.mcai.bridge.action.tasks.BucketTask;
import com.mcai.bridge.action.tasks.CraftTask;
import com.mcai.bridge.action.tasks.DefendTask;
import com.mcai.bridge.action.tasks.DigTask;
import com.mcai.bridge.action.tasks.FollowTask;
import com.mcai.bridge.action.tasks.GunSmithTask;
import com.mcai.bridge.action.tasks.InteractTask;
import com.mcai.bridge.action.tasks.InventoryTasks;
import com.mcai.bridge.action.tasks.MineTask;
import com.mcai.bridge.action.tasks.MoveToTask;
import com.mcai.bridge.action.tasks.PlaceTask;
import com.mcai.bridge.action.tasks.QueryTasks;
import com.mcai.bridge.action.tasks.ScriptTask;
import com.mcai.bridge.action.tasks.SmeltTask;
import com.mcai.bridge.protocol.Json;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * 动作执行器：�?MaiBot 下发的动作变成游戏里的真实行为��? *
 * <p>线程模型：所有公弢�方法�?b>必须</b>在客户端主线程调用��? * 网络线程收到动作后，先��过 {@code Minecraft.execute()} 切到主线程再交给这里�?/p>
 *
 * <p>调度策略�?/p>
 * <ul>
 *   <li><b>即时动作</b>（说话��执行指令��查�?��整理背包��）�?tick 立刻执行并回传结果；</li>
 *   <li><b>长动�?/b>（寻路��挖矿��追击��）进队列，�?tick 推进丢�步；</li>
 *   <li>同一时间只允许一个��移动类」动作��新来的移动动作会顶掉正在执行的移动动作�? *       被顶掉的那个会收到一条明确的「已被新指令取代」结果，这样 AI 改主意时不用等旧任务跑完�?/li>
 * </ul>
 */
public final class ActionExecutor {

    /** 结果回调：把动作结果交回给网络层�?*/
    public interface ResultSink {
        void onActionResult(String actionId, String actionType, boolean ok,
                            JsonObject result, String error, long elapsedMs);

        /** 长任务的进度心跳，让插件侧能看到「正在做仢�么����?*/
        void onTaskProgress(String actionId, String actionType, double progress, String detail);
    }

    private static final ActionExecutor INSTANCE = new ActionExecutor();

    /** 长任务的进度上报间隔（tick）��?*/
    private static final int PROGRESS_INTERVAL = 40;

    private final Deque<Task> queue = new ArrayDeque<>();
    private Task current;
    private ResultSink sink;

    private int windowStartSecond = -1;
    private int actionsThisWindow;

    private int progressTimer;
    private long totalExecuted;
    private long totalFailed;
    /** 上一次自动反击的时间（冷却用）与序号（生成动作 id 用）。 */
    private long lastAutoFightAt;
    private int autoFightSeq;
    /** 上一次「主动出击」的时间与序号。 */
    private long lastAutoDefendAt;
    private int autoDefendSeq;

    private ActionExecutor() {
    }

    public static ActionExecutor get() {
        return INSTANCE;
    }

    public void setSink(final ResultSink sink) {
        this.sink = sink;
    }

    /**
     * 全局限��闸门：每秒朢��?{@code maxActionsPerSecond} 个动作��?     *
     * <p>脚本内部的子动作也要过这道闸�?—��?否则「包进脚本��就成了绕过限��的后门�?     * 只不过脚本被限��时不是报错，��是<b>挂起等下丢�个时间窗</b>（见
     * {@code ScriptTask.tick}），因为限��是「慢丢�点����不是��这件事做不了����?/p>
     *
     * @return {@code null} 表示放行；否则返回可以给 LLM 看的原因
     */
    public String checkRateLimit() {
        final int limit = BridgeConfig.maxActionsPerSecond;
        if (limit <= 0) {
            return null; // 0 或负数表示不限��?
}
        rollWindow();
        actionsThisWindow++;
        if (actionsThisWindow > limit) {
            return "动作触发限��（每秒朢��?" + limit + " 个）";
        }
        return null;
    }

    /**
     * 这个时间窗还有配额吗�?b>只看不消�?/b>�?     *
     * <p>给脚本的挂起重试用：被限速时它每�?tick 都要问一次��能发了吗��，
     * 如果问一次就扣一个配额，那它永远等不到下丢�个时间窗�?/p>
     */
    public boolean rateLimitAllows() {
        final int limit = BridgeConfig.maxActionsPerSecond;
        if (limit <= 0) {
            return true;
        }
        rollWindow();
        return actionsThisWindow < limit;
    }

    private void rollWindow() {
        final int second = (int) (System.currentTimeMillis() / 1000L);
        if (second != windowStartSecond) {
            windowStartSecond = second;
            actionsThisWindow = 0;
        }
    }

    // ------------------------------------------------------------ 接收动作

    /**
     * 处理丢�条来自插件的动作报文。必须在客户端主线程调用�?     *
     * @param message 完整�?{@code action} 报文（含 data 段）
     */
    public void handle(final JsonObject message) {
        final JsonObject data = Json.getData(message);
        final String actionId = Json.str(data, "actionId", Json.str(message, "id", "unknown"));
        final String actionType = Json.str(data, "action", "").toLowerCase(Locale.ROOT);
        final JsonObject params = data.has("params") && data.get("params").isJsonObject()
                ? data.getAsJsonObject("params")
                : new JsonObject();
        // 脚本是一整段流程，默认预算给得比单动作宽得多（插件也能显式传 timeoutMs）
        final long defaultTimeoutMs = "script".equals(actionType)
                ? Math.max(BridgeConfig.actionTimeoutSeconds * 1000L, 600_000L)
                : BridgeConfig.actionTimeoutSeconds * 1000L;
        final long timeoutMs = Json.longVal(data, "timeoutMs", defaultTimeoutMs);

        if (actionType.isEmpty()) {
            emit(actionId, "unknown", false, null, "动作报文缺少 action 字段", 0);
            return;
        }

        // ---------------------------------------------------------- 全局限��?
final String limited = checkRateLimit();
        if (limited != null) {
            emit(actionId, actionType, false, null, limited, 0);
            return;
        }

        // -------------------------------------------------------- 生成任务对象
        final Task task;
        try {
            task = createTask(actionId, actionType, params, timeoutMs);
        } catch (final IllegalArgumentException e) {
            emit(actionId, actionType, false, null, e.getMessage(), 0);
            return;
        } catch (final Throwable t) {
            McAiBridge.LOGGER.error("[MaiBot Bridge] 构��动�?{} 失败", actionType, t);
            emit(actionId, actionType, false, null, "构��动作失�? " + t, 0);
            return;
        }

        if (task == null) {
            emit(actionId, actionType, false, null, "不支持的动作类型: " + actionType, 0);
            return;
        }

        if (BridgeConfig.verboseLog) {
            McAiBridge.LOGGER.info("[MaiBot Bridge] 收到动作 {} ({})", actionType, actionId);
        }

        if (!task.longRunning) {
            // 即时动作：直接跑丢� tick，结果立刻回�?
final TaskResult result = task.advance(Minecraft.getInstance());
            finish(task, result);
            return;
        }

        // -------------------------------------------------------- 长任务入�?
if (queue.size() >= BridgeConfig.maxQueuedActions) {
            emit(actionId, actionType, false, null,
                    "动作队列已满（上�?" + BridgeConfig.maxQueuedActions + " 个），请先等待或使用 stop 清空", 0);
            return;
        }

        if (task.movement) {
            // 顶掉正在执行的移动任务，以及队列里排队的移动任务
            if (current != null && current.movement) {
                final Task superseded = current;
                current = null;
                // 同样要走 onCancel：被顶掉的移动任务也得把按键松开、释放导航控�?                superseded.cancelNow(Minecraft.getInstance());
                InputController.release();
                emit(superseded.id, superseded.type, false, null,
                        "该动作已被更新的移动指令（" + actionType + "）取代", superseded.elapsedMs());
            }
            final List<Task> queuedMovement = new ArrayList<>();
            for (final Task queued : queue) {
                if (queued.movement) {
                    queuedMovement.add(queued);
                }
            }
            for (final Task queued : queuedMovement) {
                queue.remove(queued);
                queued.cancelNow(Minecraft.getInstance());
                emit(queued.id, queued.type, false, null,
                        "该动作已被更新的移动指令（" + actionType + "）取代", 0);
            }
            queue.addFirst(task);
        } else {
            queue.addLast(task);
        }

        if (BridgeConfig.verboseLog) {
            McAiBridge.LOGGER.info("[MaiBot Bridge] 动作 {} 入队，队列长�?{}", actionType, queue.size());
        }
    }

    // -------------------------------------------------------------- �?tick

    /** �?{@code ClientTickEvent.END} 调用：推进当前任务��出队��?*/
    public void tick(final Minecraft mc) {
        if (mc == null || mc.player == null || mc.level == null) {
            // 离开世界：清空所有未完成动作，避免切世界后还在操作旧实体
            if (current != null || !queue.isEmpty()) {
                cancelAll("玩家已离弢�世界");
            }
            return;
        }

        if (current == null && !queue.isEmpty()) {
            current = queue.pollFirst();
            McAiBridge.LOGGER.debug("[MaiBot Bridge] 弢�始执行动�?{} ({})", current.type, current.id);
        }
        if (current == null) {
            progressTimer = 0;
            return;
        }

        final TaskResult result = current.advance(mc);
        if (result.isFinished()) {
            final Task done = current;
            current = null;
            InputController.release();
            finish(done, result);
        } else {
            progressTimer++;
            if (progressTimer >= PROGRESS_INTERVAL) {
                progressTimer = 0;
                if (sink != null) {
                    try {
                        sink.onTaskProgress(current.id, current.type, current.progress(), current.detail());
                    } catch (final Throwable ignored) {
                        // 进度上报失败不影响执�?
}
                }
            }
        }
    }

    /** �?{@code MovementInputUpdateEvent} 调用：把任务的移动意图写进原版输入��?*/
    public void applyInput(final Minecraft mc, final Input input) {
        InputController.apply(mc, input);
    }

    private void finish(final Task task, final TaskResult result) {
        totalExecuted++;
        if (!result.ok) {
            totalFailed++;
        }
        emit(task.id, task.type, result.ok, result.result, result.error, task.elapsedMs());
    }

    private void emit(final String actionId, final String type, final boolean ok,
                      final JsonObject result, final String error, final long elapsedMs) {
        if (sink == null) {
            return;
        }
        try {
            sink.onActionResult(actionId, type, ok, result, error, elapsedMs);
        } catch (final Throwable t) {
            McAiBridge.LOGGER.error("[MaiBot Bridge] 回传动作结果失败", t);
        }
    }

    // ---------------------------------------------------------------- 取消

    /**
     * 取消扢�有动作��?     *
     * <p>�?{@link Task#cancelNow} 而不是只 {@code markCancelled()}：被取消的任务需要跑丢��?     * {@code onCancel} 才能真正收干凢� —��?松开按住的键、停止挖掘��关掉打弢�的工作台�?     * 释放正在蓄力的弓。只标记「已取消」的话，任务对象被丢掉了，但它在游戏里留下的状��还在��?/p>
     *
     * <p>这一条对<b>脚本</b>尤其要紧：脚本是 stop 时正在执行的当前任务�?     * 它需要顺睢� {@code onCancel �?abort()} 把正在跑的子动作也取消掉�?/p>
     */
    public void cancelAll(final String reason) {
        final Minecraft mc = Minecraft.getInstance();
        if (current != null) {
            final Task task = current;
            current = null;
            task.cancelNow(mc);
            emit(task.id, task.type, false, null, reason, task.elapsedMs());
        }
        while (!queue.isEmpty()) {
            final Task task = queue.pollFirst();
            task.cancelNow(mc);
            emit(task.id, task.type, false, null, reason, 0);
        }
        InputController.release();
    }

    /** 取消指定 id 的动作��?*/
    public boolean cancel(final String actionId) {
        final Minecraft mc = Minecraft.getInstance();
        if (current != null && current.id.equals(actionId)) {
            final Task task = current;
            current = null;
            task.cancelNow(mc);
            InputController.release();
            emit(task.id, task.type, false, null, "动作已被主动取消", task.elapsedMs());
            return true;
        }
        for (final Task task : new ArrayList<>(queue)) {
            if (task.id.equals(actionId)) {
                queue.remove(task);
                task.cancelNow(mc);
                emit(task.id, task.type, false, null, "动作已被主动取消（尚未开始执行）", 0);
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- 自动进食

    /** 上一次自动进食的时间与序号。 */
    private long lastAutoEatAt;
    private int autoEatSeq;
    /** 两次自动进食之间至少隔多久（吃东西本身要 1.8 秒，别一直重开）。 */
    private static final long AUTO_EAT_COOLDOWN_MS = 4000L;

    /**
     * **受伤或者饿了就吃东西，而且挑最好的吃。**
     *
     * <p>触发条件（任一）：血量低于 {@link BridgeConfig#autoEatHealth}、
     * 或者饥饿值低于 {@link BridgeConfig#autoEatHunger}。</p>
     *
     * <p>「吃好的」由 {@link com.mcai.bridge.util.CombatKit#findBestFood} 保证：按营养值排、
     * 同营养看饱和度 —— 背包里同时有炖菜和生鸡肉时会先吃炖菜，而不是看谁在背包里排前面。</p>
     *
     * <p>照旧的三条自我约束：只在 AI 托管时、只在自己闲着时、且有冷却（吃东西要 1.8 秒，
     * 别一直重开）。战斗里不归它管 —— 那时由 {@code defend} 的战术决定先吃还是先撤。</p>
     */
    public void autoEatTick(final net.minecraft.client.Minecraft mc) {
        if (!BridgeConfig.autoEat || !com.mcai.bridge.util.Takeover.isActive()) {
            return;
        }
        if (mc == null || mc.player == null || mc.level == null) {
            return;
        }
        if (current != null && ("attack".equals(current.type) || "defend".equals(current.type)
                || "shoot".equals(current.type) || "reload".equals(current.type)
                || "use".equals(current.type) || "use_item".equals(current.type)
                || "equip".equals(current.type))) {
            return;   // 正在打 / 正在吃 / 正在换手：别插手
        }
        // 注意：「忙不忙」的判断挪到后面了（见下面 isBusy 那段）——
        // 「闲等」不该挡住吃东西，饿着肚子干等正是最该打断的状态。
        final net.minecraft.client.player.LocalPlayer player = mc.player;
        final float health = player.getHealth();
        final int hunger = player.getFoodData().getFoodLevel();
        final boolean hurt = health < BridgeConfig.autoEatHealth;
        final boolean hungry = hunger < BridgeConfig.autoEatHunger;
        if (!hurt && !hungry) {
            return;
        }
        // 吃饱了就别再塞（饥饿值满了再吃普通食物是浪费）——
        // 但**受伤时例外**：金苹果这类「吃了能治伤」的东西，饱着也照样有用。
        final net.minecraft.world.item.ItemStack food =
                com.mcai.bridge.util.CombatKit.findBestFood(player);
        if (food.isEmpty()) {
            return;   // 背包里没有能吃的：不折腾（真饿到不行 AI 会自己想办法）
        }
        if (!hungry && hunger >= 20
                && !(hurt && com.mcai.bridge.util.CombatKit.foodHasEffects(food, player))) {
            return;
        }
        final long now = System.currentTimeMillis();
        if (now - lastAutoEatAt < AUTO_EAT_COOLDOWN_MS) {
            return;
        }
        // 「闲等」（wait）直接打断 —— 饿着肚子/带着伤干等没有任何价值；
        // 真正在干活（挖矿、走远路、合成…）则不打断，等它干完再说。
        if (current != null && "wait".equals(current.type)) {
            cancelAll("饿了/受伤了，先吃东西");
        } else if (isBusy()) {
            return;
        }
        lastAutoEatAt = now;

        // 手上不是这份食物就先换到手上（equip 会把背包里的东西挪进快捷栏）
        String equipFirst = null;
        if (!player.getMainHandItem().is(food.getItem())) {
            equipFirst = com.mcai.bridge.util.GameUtils.itemId(food);
        }
        final JsonObject params = new JsonObject();
        params.addProperty("hand", "main");
        params.addProperty("durationMs", 1800);
        params.addProperty("reason", "auto_eat");
        final Task eat = createTask("auto-eat-" + (++autoEatSeq), "use_item", params, 20_000L);
        if (eat == null) {
            return;
        }
        queue.addFirst(eat);
        if (equipFirst != null) {
            final JsonObject equipParams = new JsonObject();
            equipParams.addProperty("item", equipFirst);
            equipParams.addProperty("reason", "auto_eat");
            final Task equipTask = createTask("auto-eat-equip-" + (++autoEatSeq), "equip",
                    equipParams, 10_000L);
            if (equipTask != null) {
                queue.addFirst(equipTask);   // 队首顺序：换食物 → 吃
            }
        }
        final JsonObject info = com.mcai.bridge.util.CombatKit.foodInfo(food, player);
        McAiBridge.LOGGER.info("[MaiBot Bridge] 自动进食：{}（血量 {}，饥饿 {}{}）",
                info == null ? "食物" : info.get("name").getAsString(),
                Math.round(health), hunger, hurt ? "，受伤了" : "，饿了");
    }

    // ---------------------------------------------------------------- 自动反击

    /**
     * 被生物打了：立刻还手。
     *
     * <p>「立刻」是字面意思：先把手上的活（挖矿、放方块、走路…）打断，
     * 再把反击插到队首。真机上最常见的挨打场景就是「挖矿挖到一半被僵尸站背后打」，
     * 不打断的话它会一直挖到你死。</p>
     *
     * <p>自己已经在打同一个目标、或者冷却没到，就不重复下发 —— 被连续攻击时
     * 每一跳都重开任务的话，反而一刀都打不出去。</p>
     *
     * @param uuid   攻击者的 uuid（**服务端实体**的 uuid：客户端那两个 player 对象不是同一个，
     *               只能靠 uuid 让客户端侧的 attack 任务自己去找对应的客户端实体）
     * @param name   显示名，仅用于日志和给 AI 的解释
     */
    public void autoRetaliate(final String uuid, final String name) {
        if (!BridgeConfig.autoFight || uuid == null || uuid.isBlank()) {
            return;
        }
        final long now = System.currentTimeMillis();
        if (now - lastAutoFightAt < BridgeConfig.autoFightCooldownMs) {
            return;
        }
        if (current != null && ("attack".equals(current.type) || "defend".equals(current.type)
                || "shoot".equals(current.type) || "reload".equals(current.type))) {
            return;   // 已经在打了
        }
        // 手上的活让路：不打断的话「立刻反击」就只是句空话
        cancelAll("被 " + name + " 攻击，先还手");
        lastAutoFightAt = System.currentTimeMillis();   // cancelAll 会 emit，别把它算成一次反击

        // 手里有什么就用什么：拿着枪/弓就用远程打，空手/近战武器才上去抡。
        // 距离也要看：枪空弹匣时，贴脸先抡、离远了才换弹（站着换弹会被打死，真机教训）。
        final net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        final double distance = attackerDistance(mc, uuid);
        final String action = mc != null && mc.player != null
                ? com.mcai.bridge.util.CombatKit.autoAction(mc.player, distance) : "attack";
        final JsonObject params = mc != null && mc.player != null
                ? com.mcai.bridge.util.CombatKit.autoAttackParams(mc.player, uuid, 20_000L, distance)
                : new JsonObject();
        params.addProperty("reason", "auto_retaliate");
        final Task task = createTask("auto-fight-" + (++autoFightSeq), action, params, 30_000L);
        if (task != null) {
            queue.addFirst(task);
            // 贴脸（2 格内）要抡近战武器，可手上还是把弓/枪 —— 先插一个换武器的动作。
            // 注意顺序：先 addFirst 攻击、再 addFirst 换武器，队首就成了「换武器 → 打」。
            String equipFirst = null;
            if ("attack".equals(action) && mc != null && mc.player != null
                    && !com.mcai.bridge.util.CombatKit.isMeleeWeapon(mc.player.getMainHandItem())) {
                equipFirst = com.mcai.bridge.util.CombatKit.findMeleeWeaponId(mc.player);
            }
            if (equipFirst != null) {
                final JsonObject equipParams = new JsonObject();
                equipParams.addProperty("item", equipFirst);
                equipParams.addProperty("reason", "auto_retaliate");
                final Task equipTask = createTask("auto-equip-" + (++autoFightSeq), "equip",
                        equipParams, 10_000L);
                if (equipTask != null) {
                    queue.addFirst(equipTask);
                }
            }
            McAiBridge.LOGGER.info("[MaiBot Bridge] 自动反击：{}（打断当前动作，{}）", name,
                    mc != null && mc.player != null
                            ? com.mcai.bridge.util.CombatKit.autoModeText(mc.player, distance) : "近战");
        }
    }

    /** 打我的那个家伙离我多远（按 uuid 找客户端实体；找不到就当成「很远」）。 */
    private static double attackerDistance(final net.minecraft.client.Minecraft mc, final String uuid) {
        if (mc == null || mc.player == null || mc.level == null || uuid == null) {
            return Double.MAX_VALUE;
        }
        for (final net.minecraft.world.entity.Entity entity : mc.level.entitiesForRendering()) {
            if (entity.getUUID().toString().equalsIgnoreCase(uuid)) {
                return Math.sqrt(mc.player.distanceToSqr(entity));
            }
        }
        return Double.MAX_VALUE;
    }

    // ---------------------------------------------------------------- 主动出击

    /** 贴近到这个距离以内的敌人：不管手上在干什么，先打。 */
    private static final double URGENT_DISTANCE = 6.0;

    /**
     * 托管期间：附近有敌对生物就**主动**上去打，不等它先动手。
     *
     * <p>为什么不自己写一套战斗逻辑：{@code defend} 已经把该想的都想好了 ——
     * 血少先吃、被三个以上围住就撤、优先打「正在打我的」。这里只负责「什么时候开打」，
     * 打起来之后交给它。</p>
     *
     * <p>几条自我约束（都是真机上会翻车的地方）：</p>
     * <ul>
     *   <li><b>只在托管时</b>生效 —— 玩家自己玩的时候不该被抢手柄；</li>
     *   <li><b>手上有活就不打扰</b>（正在挖矿/放方块/走远路时不主动开战，
     *       但被打时那条反击的路照样会插队）；</li>
     *   <li><b>Baritone 在干活时不插手</b> —— 它可能正在执行挖矿，开战会互相打架；</li>
     *   <li><b>有冷却、有上限</b> —— 打完一轮先喘口气，一轮最多清几个，不追到天亮。</li>
     * </ul>
     */
    public void autoDefendTick(final net.minecraft.client.Minecraft mc) {
        if (!BridgeConfig.autoAttackHostiles || !com.mcai.bridge.util.Takeover.isActive()) {
            return;
        }
        if (mc == null || mc.player == null || mc.level == null) {
            return;
        }
        final long now = System.currentTimeMillis();
        if (now - lastAutoDefendAt < BridgeConfig.autoAttackCooldownMs) {
            return;
        }
        final net.minecraft.world.entity.LivingEntity target = nearestHostile(mc);
        if (target == null) {
            return;
        }
        final double distance = Math.sqrt(mc.player.distanceToSqr(target));
        // 贴脸的敌人例外：这么近了，不管手上在干什么都先打 ——
        // 不然「主动出击」会被「我正在挖矿」永远挡在门外（真机实测就是这么一次都没触发的）。
        final boolean urgent = distance <= URGENT_DISTANCE;
        if (current != null && ("attack".equals(current.type) || "defend".equals(current.type)
                || "shoot".equals(current.type) || "reload".equals(current.type))) {
            return;   // 已经在打了
        }
        if (isBusy() && !urgent) {
            return;   // 手上有活、又不紧急：先把活干完
        }
        if (com.mcai.bridge.util.BaritoneWatcher.active() && !urgent) {
            return;   // Baritone 在跑（可能正在挖矿），别插手
        }
        lastAutoDefendAt = now;

        if (urgent && isBusy()) {
            cancelAll("有 " + com.mcai.bridge.util.GameUtils.entityName(target) + " 贴到 "
                    + Math.round(distance) + " 格，先打它");
        }

        final JsonObject params = new JsonObject();
        params.addProperty("radius", BridgeConfig.autoAttackRadius);
        params.addProperty("maxKills", BridgeConfig.autoAttackMaxKills);
        params.addProperty("reason", "auto_attack");
        final Task task = createTask("auto-defend-" + (++autoDefendSeq), "defend", params,
                Math.max(BridgeConfig.actionTimeoutSeconds, 90) * 1000L);
        if (task != null) {
            queue.addFirst(task);
            // 日志里带上「打算用什么打」——排查时一眼能看出这次是枪、弓还是抡拳头
            McAiBridge.LOGGER.info("[MaiBot Bridge] 主动出击：附近有 {}（{} 格外{}，{}）",
                    com.mcai.bridge.util.GameUtils.entityName(target), Math.round(distance),
                    urgent ? "，贴脸了" : "",
                    com.mcai.bridge.util.CombatKit.autoModeText(mc.player, distance));
        }
    }

    // ---------------------------------------------------------------- 自动换弹

    /** 上一次自动换弹的时间（防抖：换弹本身要两三秒，别一直重开）。 */
    private long lastAutoReloadAt;
    /** 自动换弹任务的序号（生成动作 id 用）。 */
    private int autoReloadSeq;
    /** 两次自动换弹之间至少隔多久。 */
    private static final long AUTO_RELOAD_COOLDOWN_MS = 3000L;

    /**
     * **弹匣清空就自动换弹。**
     *
     * <p>为什么要有这一条：打完一梭子之后，AI 手上就是一把空枪 —— 下一次开火只会回一句
     * 「没有子弹了」，中间白白浪费一轮往返。有条件（弹匣空 + 背包有同口径子弹）就该自己补上。</p>
     *
     * <p>三条自我约束：</p>
     * <ul>
     *   <li><b>只在 AI 托管时</b>生效 —— 你自己玩的时候绝不抢 R 键；</li>
     *   <li><b>只在闲着的时候</b>动手（{@link #isBusy()}）—— 挖矿挖到一半不该被换弹插队；</li>
     *   <li><b>战斗过程中不走这条</b> —— 那时由战斗逻辑按手里的武器和距离自己决定
     *       该射、该换还是该抡（贴脸时站着换弹会被打死，见 {@code CombatKit.CLOSE_QUARTER}）。</li>
     * </ul>
     */
    public void autoReloadTick(final net.minecraft.client.Minecraft mc) {
        if (!BridgeConfig.autoReload || !com.mcai.bridge.util.Takeover.isActive()) {
            return;
        }
        if (mc == null || mc.player == null || mc.level == null) {
            return;
        }
        if (current != null && ("attack".equals(current.type) || "defend".equals(current.type)
                || "shoot".equals(current.type) || "reload".equals(current.type))) {
            return;   // 正在打或正在换：别插手
        }
        if (isBusy()) {
            return;   // 手上有活（挖矿、走路、合成…）：先把活干完
        }
        final long now = System.currentTimeMillis();
        if (now - lastAutoReloadAt < AUTO_RELOAD_COOLDOWN_MS) {
            return;
        }
        final net.minecraft.world.item.ItemStack held = mc.player.getMainHandItem();
        if (com.mcai.bridge.util.CombatKit.rangedKind(held) != com.mcai.bridge.util.CombatKit.Ranged.GUN) {
            return;   // 手上不是枪（弓/弩不用换弹）
        }
        if (com.mcai.bridge.util.ModHooks.gunLoaded(held) > 0) {
            return;   // 弹匣里还有、或者膛里还压着一发：不用换
        }
        final int spare = com.mcai.bridge.util.CombatKit.countAmmo(mc.player,
                com.mcai.bridge.util.CombatKit.Ranged.GUN);
        if (spare <= 0) {
            return;   // 背包里也没有匹配口径的子弹：换了也白换，先去补
        }
        lastAutoReloadAt = now;
        final JsonObject params = new JsonObject();
        params.addProperty("ticks", 200);
        params.addProperty("reason", "auto_reload");
        final Task task = createTask("auto-reload-" + (++autoReloadSeq), "reload", params, 30_000L);
        if (task != null) {
            queue.addFirst(task);
            McAiBridge.LOGGER.info("[MaiBot Bridge] 自动换弹：弹匣空了（背包还有 {} 发备弹）", spare);
        }
    }

    /** 最近的一个还活着的敌对生物（在警戒半径内）。 */    private net.minecraft.world.entity.LivingEntity nearestHostile(
            final net.minecraft.client.Minecraft mc) {
        final double radius = BridgeConfig.autoAttackRadius;
        net.minecraft.world.entity.LivingEntity best = null;
        double bestDist = radius * radius;
        for (final net.minecraft.world.entity.Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof final net.minecraft.world.entity.LivingEntity living)
                    || entity == mc.player || !living.isAlive() || living.getHealth() <= 0) {
                continue;
            }
            if (!"HOSTILE".equals(com.mcai.bridge.util.GameUtils.entityCategory(entity))) {
                continue;
            }
            // 统一的目标筛选：视线被挡 / 在黑名单里（村民、铁傀儡、宠物…）都不打
            if (!com.mcai.bridge.util.CombatKit.isValidTarget(mc, mc.player, entity)) {
                continue;
            }
            final double d = mc.player.distanceToSqr(entity);
            if (d < bestDist) {
                bestDist = d;
                best = living;
            }
        }
        return best;
    }

    // ---------------------------------------------------------------- 状��?
    /** 当前动作�?JSON 描述，用于状态快照与 {@code task_status}�?*/
    public JsonObject statusJson() {
        final JsonObject o = new JsonObject();
        if (current == null) {
            o.add("current", null);
        } else {
            final JsonObject c = new JsonObject();
            c.addProperty("id", current.id);
            c.addProperty("action", current.type);
            c.addProperty("elapsedMs", current.elapsedMs());
            c.addProperty("timeoutMs", current.timeoutMs);
            c.addProperty("movement", current.movement);
            final double progress = current.progress();
            if (progress >= 0) {
                c.addProperty("progress", Math.round(progress * 100.0) / 100.0);
            }
            final String detail = current.detail();
            if (detail != null && !detail.isEmpty()) {
                c.addProperty("detail", detail);
            }
            o.add("current", c);
        }

        final com.google.gson.JsonArray queued = new com.google.gson.JsonArray();
        for (final Task task : queue) {
            final JsonObject q = new JsonObject();
            q.addProperty("id", task.id);
            q.addProperty("action", task.type);
            q.addProperty("movement", task.movement);
            queued.add(q);
        }
        o.add("queued", queued);
        o.addProperty("queueLength", queue.size());
        o.addProperty("executedTotal", totalExecuted);
        o.addProperty("failedTotal", totalFailed);
        // Baritone 的活不排在我们的队列里：光看队列会一直显示「空闲」，
        // 麦麦就以为没人在做事（可能重复下发，或者在它还在挖的时候改主意）。
        final JsonObject baritone = com.mcai.bridge.util.BaritoneWatcher.statusJson();
        if (baritone != null) {
            o.add("baritone", baritone);
        }
        o.addProperty("idle", current == null && queue.isEmpty() && baritone == null);
        return o;
    }

    public boolean isBusy() {
        return current != null || !queue.isEmpty();
    }

    /** 队列里还排着多少个等待执行的动作。脚本条�?busy 用它�?*/
    public int queueLength() {
        return queue.size();
    }

    /** 当前动作的简短描述，�?HUD 显示�?*/
    public String hudText() {
        // 自己在跑任务就报自己的；否则看看 Baritone 在不在干活 ——
        // HUD 上写「空闲」而 Baritone 正在满地图跑，是最容易让人误判的一种显示。
        if (current == null) {
            final String baritone = com.mcai.bridge.util.BaritoneWatcher.summary();
            if (!baritone.isEmpty()) {
                return "Baritone: " + baritone;
            }
            return queue.isEmpty() ? "空闲" : "排队�?" + queue.size();
        }
        final double progress = current.progress();
        final String base = current.type + (progress >= 0 ? String.format(" %.0f%%", progress * 100) : "");
        return queue.isEmpty() ? base : base + " (+" + queue.size() + ")";
    }

    // ------------------------------------------------------------ 任务工厂

    /**
     * 根据动作类型构��任务��?     *
     * <p>本来只给 {@link #handle} 用；脚本引擎（{@code ScriptTask}）也要复用同丢��?     * 权限校验与参数解析，扢�以开放出来��?*这样脚本里的每一步和单独下发丢�步走的是同一套规�?*�?     * 不会出现「直接调用被禁止、放进脚本里就绕过去了��的口子�?/p>
     *
     * @throws IllegalArgumentException 参数非法或该动作被配置禁�?     */
    public Task createTask(final String id, final String type, final JsonObject params, final long timeoutMs) {
        final boolean instant = !isLongRunning(type);
        return switch (type) {
            // ------------------------------------------------------ 通讯
            case "chat" -> {
                requirePermission(BridgeConfig.allowChat, "在游戏内发言（allow.chat�?");
                yield BasicTasks.chat(id, params, instant);
            }
            case "command" -> {
                requirePermission(BridgeConfig.allowCommand, "执行游戏指令（allow.command�?");
                yield BasicTasks.command(id, params, instant);
            }

            // ------------------------------------------------------ 观察
            case "get_state" -> BasicTasks.getState(id, params, instant);
            case "task_status" -> BasicTasks.taskStatus(id, params, instant);
            case "scan_blocks" -> QueryTasks.scanBlocks(id, params, instant);
            case "scan_entities" -> QueryTasks.scanEntities(id, params, instant);

            // ------------------------------------------------------ 视角
            case "look" -> {
                requirePermission(BridgeConfig.allowLook, "控制视角（allow.look�?");
                yield BasicTasks.look(id, params, instant);
            }

            // ------------------------------------------------------ 移动
            //
            // 装了 Baritone 就**优先交给它**：寻路是它的看家本事，绕障碍/搭桥/垫脚比我们自带那套稳。
            // 想强制用自带的，在动作参数里加 "via": "native"。
            case "move_to" -> {
                requirePermission(BridgeConfig.allowMovement, "控制移动（allow.movement�?");
                final Task byBaritone = com.mcai.bridge.action.tasks.BaritoneTasks
                        .gotoTask(id, params, timeoutMs);
                yield byBaritone != null ? byBaritone : MoveToTask.toCoordinates(id, params, timeoutMs);
            }
            case "move_relative" -> {
                requirePermission(BridgeConfig.allowMovement, "控制移动（allow.movement�?");
                final Task byBaritone = com.mcai.bridge.action.tasks.BaritoneTasks
                        .relativeTask(id, params, timeoutMs);
                yield byBaritone != null ? byBaritone : MoveToTask.relative(id, params, timeoutMs);
            }
            case "follow" -> {
                requirePermission(BridgeConfig.allowMovement, "控制移动（allow.movement�?");
                yield FollowTask.create(id, params, timeoutMs);
            }
            case "jump" -> {
                requirePermission(BridgeConfig.allowMovement, "控制移动（allow.movement�?");
                yield BasicTasks.jump(id, params, timeoutMs);
            }
            case "sneak" -> {
                requirePermission(BridgeConfig.allowMovement, "控制移动（allow.movement�?");
                yield BasicTasks.sneak(id, params, timeoutMs);
            }
            case "sprint" -> {
                requirePermission(BridgeConfig.allowMovement, "控制移动（allow.movement�?");
                yield BasicTasks.sprint(id, params, timeoutMs);
            }
            case "wait" -> BasicTasks.wait(id, params, timeoutMs);
            case "stop" -> BasicTasks.stop(id, params, instant);
            case "cancel" -> BasicTasks.cancel(id, params, instant);

            // ------------------------------------------------------ 交互
            case "mine" -> {
                requirePermission(BridgeConfig.allowBreak, "破坏方块（allow.breakBlocks�?");
                yield MineTask.single(id, params, timeoutMs);
            }
            // 觅食：饿了又没吃的时自己去找（干草块→面包 / 打动物 / 翻宝箱）
            case "forage" -> {
                requirePermission(BridgeConfig.allowBreak, "觅食（allow.breakBlocks�?");
                yield com.mcai.bridge.action.tasks.ForageTask.create(id, params, timeoutMs);
            }
            case "mine_blocks" -> {
                requirePermission(BridgeConfig.allowBreak, "破坏方块（allow.breakBlocks�?");                // 装了 Baritone 就让它的 #mine 去挖（数量在前：`#mine 64 dirt`）。
                // 它比自带实现稳：挖穿、搭桥、绕岩浆都会自己处理。
                final Task byBaritone = com.mcai.bridge.action.tasks.BaritoneTasks
                        .mineTask(id, params, timeoutMs);
                yield byBaritone != null ? byBaritone : MineTask.multiple(id, params, timeoutMs);
            }
            case "place" -> {
                requirePermission(BridgeConfig.allowPlace, "放置方块（allow.placeBlocks�?");
                yield PlaceTask.place(id, params, timeoutMs);
            }
            case "use_on_block" -> {
                requirePermission(BridgeConfig.allowUse, "与方块交互（allow.use�?");
                yield PlaceTask.useOnBlock(id, params, timeoutMs);
            }
            case "jump_on_block" -> {
                requirePermission(BridgeConfig.allowMovement, "控制移动（allow.movement�?");
                yield InteractTask.jumpOnBlock(id, params, timeoutMs);
            }
            case "use_item" -> {
                requirePermission(BridgeConfig.allowUse, "使用物品（allow.use�?");
                yield InventoryTasks.useItem(id, params, instant);
            }
            case "equip" -> {
                requirePermission(BridgeConfig.allowInventory, "整理背包（allow.inventory�?");
                yield InventoryTasks.equip(id, params, instant);
            }
            case "drop" -> {
                requirePermission(BridgeConfig.allowInventory, "整理背包（allow.inventory�?");
                yield InventoryTasks.drop(id, params, instant);
            }
            case "sleep" -> {
                requirePermission(BridgeConfig.allowUse, "与床交互（allow.use�?");
                yield InteractTask.sleep(id, params, timeoutMs);
            }

            // ------------------------------------------------------ 脚本
            // 脚本里的每一步都会走同一�?createTask()，所以权限开关��参数校�?            // 与单独下发时完全丢�致，不存在��包进脚本就绕过禁用」的口子�?
case "script" -> ScriptTask.create(id, params, timeoutMs);

            // ------------------------------------------------------ 合成
            case "craft" -> {
                requirePermission(BridgeConfig.allowInventory, "合成物品（allow.inventory�?");
                yield CraftTask.craft(id, params, timeoutMs);
            }
            // 熔炼：铁矿石→铁锭这类��没有它，生存流程在第一道关卡就过不�?
case "smelt" -> {
                requirePermission(BridgeConfig.allowUse, "使用熔炉（allow.use�?");
                yield SmeltTask.create(id, params, timeoutMs);
            }
            // 桶：装液�?/ 倒液体（做黑曜石必需�?
case "bucket" -> {
                requirePermission(BridgeConfig.allowUse, "使用桶（allow.use�?");
                yield BucketTask.create(id, params, timeoutMs);
            }
            // 向下挖阶梯矿道：铁在 y15~60、钻石在 y<16，够不到就什么都做不�?
case "dig_shaft" -> {
                requirePermission(BridgeConfig.allowBreak, "挖掘方块（allow.break�?");
                yield DigTask.create(id, params, timeoutMs);
            }
            // 枪械（永恒枪械工艺这类）：射击走按键，不走原版近战
            case "reload" -> {
                requirePermission(BridgeConfig.allowUse, "枪械换弹（allow.use）");
                yield com.mcai.bridge.action.tasks.GunTasks.reload(id, params, timeoutMs);
            }
            case "shoot" -> {
                requirePermission(BridgeConfig.allowAttack, "开枪（allow.attack）");
                yield com.mcai.bridge.action.tasks.GunTasks.shoot(id, params, timeoutMs);
            }
            // 连续锁敌扫射：目标死了自动换下一个、弹匣空了自动换弹、顺手给 AutoAim 开锁
            case "lock_on" -> {
                requirePermission(BridgeConfig.allowAttack, "连续锁敌（allow.attack）");
                yield com.mcai.bridge.action.tasks.GunTasks.lockOn(id, params, timeoutMs);
            }
            // AI 托管：麦麦接手后玩家可以放开鼠标切出去，游戏照常跑
            case "takeover" -> new Task(id, "takeover", params, 5_000L, false, false) {
                @Override
                protected TaskResult onTick(final Minecraft mc) {
                    final boolean enable = Json.bool(params, "enabled",
                            Json.bool(params, "on", !com.mcai.bridge.util.Takeover.isActive()));
                    if (enable) {
                        com.mcai.bridge.util.Takeover.enter(mc, "麦麦要求托管");
                    } else {
                        com.mcai.bridge.util.Takeover.exit(mc, "麦麦交还控制权");
                    }
                    final JsonObject out = com.mcai.bridge.util.Takeover.statusJson();
                    out.addProperty("content", enable
                            ? "已进入 AI 托管：窗口失焦也不会暂停，鼠标已放开，玩家可以切出去。"
                            : "已交还控制权：鼠标和暂停设置还给玩家。");
                    return TaskResult.success(out);
                }
            };
            case "recipes" -> CraftTask.recipes(id, params, instant);
            // 关界面：右键开出来的箱子/工作台界面会一直挡着，得有个明确动作关掉它
            case "close_screen" -> BasicTasks.closeScreen(id, params, instant);
            // Baritone 刚说了什么（它的回话只进聊天栏，从游戏日志里捞）
            case "baritone_reply" -> BasicTasks.baritoneReply(id, params, instant);
            // 枪械工作台（TaCZ）：列配方 / 报配方名制作
            case "gun_smith" -> GunSmithTask.create(id, params, instant);

            // ------------------------------------------------------ 战斗
            case "attack" -> {
                requirePermission(BridgeConfig.allowAttack, "攻击实体（allow.attack�?");
                yield AttackTask.create(id, params, timeoutMs);
            }
            // 抵御：自己评估威胁（�?�?撤），内部用 attack/move_to/use_item 子任�?
case "defend" -> {
                requirePermission(BridgeConfig.allowAttack, "抵御威胁（allow.attack�?");
                yield DefendTask.create(id, params, timeoutMs);
            }

            default -> null;
        };
    }

    private static void requirePermission(final boolean allowed, final String what) {
        if (!allowed) {
            throw new IllegalArgumentException("该动作已被模组配置禁用：" + what
                    + "。请�?config/mcai_bridge-client.toml 中打弢�对应弢�关��?");
        }
    }

    /** 判断某个动作类型是否是长任务�?*/
    public static boolean isLongRunning(final String type) {
        return switch (type) {
            case "move_to", "move_relative", "follow", "mine", "mine_blocks", "place",
                 "use_on_block", "attack", "defend", "sleep", "wait", "jump", "sneak", "sprint",
                 "jump_on_block", "craft", "smelt", "dig_shaft", "bucket", "script" -> true;
            default -> false;
        };
    }
}
