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
        if (current == null) {
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
            case "move_to" -> {
                requirePermission(BridgeConfig.allowMovement, "控制移动（allow.movement�?");
                yield MoveToTask.toCoordinates(id, params, timeoutMs);
            }
            case "move_relative" -> {
                requirePermission(BridgeConfig.allowMovement, "控制移动（allow.movement�?");
                yield MoveToTask.relative(id, params, timeoutMs);
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
            case "mine_blocks" -> {
                requirePermission(BridgeConfig.allowBreak, "破坏方块（allow.breakBlocks�?");
                yield MineTask.multiple(id, params, timeoutMs);
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
            case "recipes" -> CraftTask.recipes(id, params, instant);

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
