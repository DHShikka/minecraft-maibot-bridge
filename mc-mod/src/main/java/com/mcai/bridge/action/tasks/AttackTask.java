package com.mcai.bridge.action.tasks;

import com.google.gson.JsonObject;
import com.mcai.bridge.action.Navigator;
import com.mcai.bridge.action.Task;
import com.mcai.bridge.action.TaskResult;
import com.mcai.bridge.protocol.Json;
import com.mcai.bridge.util.GameUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

/**
 * {@code attack}：攻击指定实体直到它死亡（或达到指定次数）。
 *
 * <p>细节：</p>
 * <ul>
 *   <li>会等待原版的攻击冷却（{@code getAttackStrengthScale}）再出手，否则伤害只有一半；</li>
 *   <li>够不到时会自动走过去；</li>
 *   <li>目标死亡、消失或变成创造模式玩家都会立刻结束并说明原因。</li>
 * </ul>
 *
 * <p>参数：{@code target}（uuid / 玩家名 / 实体名 / {@code nearest_hostile} / {@code nearest}）、
 * {@code count}（最多打几下，默认直到目标死亡，上限 200）、{@code durationMs}（最长打多久）。</p>
 */
public final class AttackTask extends Task {

    /** 原版近战距离是 3.0，留一点余量防止因为走位抖动而打空。 */
    private static final double ATTACK_RANGE = 2.9;

    private final String targetKey;
    private final int maxHits;
    private final long durationMs;
    private final long startAt = System.currentTimeMillis();

    private Navigator navigator;
    private String resolvedUuid;
    private int hits;
    private int swings;
    private int lostTicks;
    private float healthBefore = -1;

    private AttackTask(final String id, final JsonObject params, final long timeoutMs,
                       final String targetKey, final int maxHits, final long durationMs) {
        super(id, "attack", params, timeoutMs, true, true);
        this.targetKey = targetKey;
        this.maxHits = maxHits <= 0 ? 200 : Math.min(maxHits, 200);
        this.durationMs = durationMs;
    }

    public static Task create(final String id, final JsonObject params, final long timeoutMs) {
        final String target = Json.str(params, "target", Json.str(params, "entity", ""));
        if (target.isBlank()) {
            return new MoveToTask.FailingTask(id, "attack", params,
                    "缺少参数 target（要攻击的实体：uuid / 名字 / 类型，或 nearest_hostile）");
        }
        return new AttackTask(id, params, timeoutMs, target,
                Json.intVal(params, "count", 0),
                Json.longVal(params, "durationMs", 30_000L));
    }

    @Override
    protected void onStart(final Minecraft mc) {
        if (mc.player == null || mc.level == null) {
            fail("尚未进入世界");
        }
        navigator = new Navigator(mc.level);
    }

    @Override
    protected TaskResult onTick(final Minecraft mc) {
        final LocalPlayer player = mc.player;
        if (player == null || mc.gameMode == null) {
            fail("玩家或 gameMode 不存在");
        }

        if (durationMs > 0 && System.currentTimeMillis() - startAt >= durationMs) {
            releaseNavigator();
            return TaskResult.success(summary("到达时间上限，停止攻击"));
        }

        final Entity target = resolveTarget(mc);
        if (target == null) {
            lostTicks++;
            if (lostTicks > 20 && hits == 0) {
                fail("找不到攻击目标「" + targetKey + "」。可能它已经死亡、走远，或者名字写错了。"
                        + "可以用 scan_entities 查看附近有哪些实体。");
            }
            if (lostTicks > 20) {
                releaseNavigator();
                return TaskResult.success(summary("目标已消失"));
            }
            return null;
        }
        lostTicks = 0;

        // fail() 会抛出 TaskFailure，但编译器无法推断，所以这里显式 return
        if (!(target instanceof LivingEntity)) {
            fail("目标「" + GameUtils.entityName(target) + "」不是可攻击的生物");
            return null;
        }
        final LivingEntity living = (LivingEntity) target;

        if (target instanceof Player other && other.isCreative()) {
            fail("目标玩家「" + other.getName().getString() + "」处于创造模式，攻击无效");
            return null;
        }

        if (living.isDeadOrDying() || !living.isAlive()) {
            releaseNavigator();
            final JsonObject out = summary("目标已死亡");
            out.addProperty("killed", true);
            return TaskResult.success(out);
        }

        if (healthBefore < 0) {
            healthBefore = living.getHealth();
        }

        final double distance = player.distanceTo(target);

        // ---------------------------------------------------------- 够不到就走过去
        if (distance > ATTACK_RANGE) {
            if (navigator == null) {
                navigator = new Navigator(mc.level);
            }
            if (navigator.goal() == null || navigator.goal().distSqr(target.blockPosition()) > 2.0
                    || navigator.pathLength() == 0) {
                navigator.setGoal(target.blockPosition(), 1);
            }
            final Navigator.Status status = navigator.step(mc, 1.0);
            if (status == Navigator.Status.FAILED) {
                releaseNavigator();
                fail("无法靠近目标「" + GameUtils.entityName(target) + "」：" + navigator.failureReason());
            }
            if (hits == 0 && System.currentTimeMillis() - startAt > 20_000L) {
                releaseNavigator();
                fail("追了 20 秒仍然够不到「" + GameUtils.entityName(target)
                        + "」（当前距离 " + String.format(Locale.ROOT, "%.1f", distance)
                        + " 格）。目标可能在墙的另一侧或者移动太快。");
            }
            // 边走边看向目标，观感与真人一致
            GameUtils.lookAt(player, aimPoint(target));
            return null;
        }

        // -------------------------------------------------------------- 出手
        releaseNavigator();
        GameUtils.lookAt(player, aimPoint(target));

        // 攻击冷却：低于 0.9 就再等一 tick，避免打出半伤
        final float cooldown = player.getAttackStrengthScale(0.5f);
        swings++;
        if (cooldown < 0.9f && swings < 60) {
            return null;
        }
        swings = 0;

        mc.gameMode.attack(player, target);
        player.swing(InteractionHand.MAIN_HAND);
        hits++;

        final JsonObject info = new JsonObject();
        info.addProperty("hit", hits);
        info.addProperty("targetHealth", round(living.getHealth()));
        info.addProperty("cooldown", round(cooldown));

        if (hits >= maxHits) {
            final JsonObject out = summary("已达到最大攻击次数");
            out.addProperty("hits", hits);
            out.addProperty("killed", living.isDeadOrDying());
            out.addProperty("targetHealth", round(living.getHealth()));
            return TaskResult.success(out);
        }

        // 血量已经见底但还没死：再等一 tick 让它结算
        if (living.isDeadOrDying()) {
            final JsonObject out = summary("目标已死亡");
            out.addProperty("hits", hits);
            out.addProperty("killed", true);
            return TaskResult.success(out);
        }
        return null;
    }

    private JsonObject summary(final String reason) {
        final JsonObject out = new JsonObject();
        out.addProperty("hits", hits);
        out.addProperty("reason", reason);
        out.addProperty("target", targetKey);
        if (healthBefore >= 0) {
            out.addProperty("initialHealth", round(healthBefore));
        }
        out.addProperty("killed", false);
        return out;
    }

    private Vec3 aimPoint(final Entity target) {
        // 瞄胸口高度比瞄脚更容易命中
        return new Vec3(target.getX(), target.getY() + target.getBbHeight() * 0.6, target.getZ());
    }

    private void releaseNavigator() {
        if (navigator != null) {
            navigator.releaseControl();
        }
    }

    private Entity resolveTarget(final Minecraft mc) {
        if (resolvedUuid != null && mc.level != null) {
            for (final Entity entity : mc.level.entitiesForRendering()) {
                if (entity.getUUID().toString().equals(resolvedUuid)) {
                    return entity;
                }
            }
            resolvedUuid = null;
            return null;
        }
        final Entity found = BasicTasks.TargetResolver.findEntity(mc, targetKey);
        if (found != null) {
            resolvedUuid = found.getUUID().toString();
        }
        return found;
    }

    @Override
    protected void onCancel(final Minecraft mc) {
        releaseNavigator();
    }

    /**
     * 卡死检测用的真实进展：已经打中的次数。
     *
     * <p>注意不能拿下面的 {@link #progress()}：目标血量厚的时候会站着连打好几秒，
     * 而那个 progress 是按时间算的，站着不动也会涨，拿它判断等于没检测。</p>
     */
    @Override
    protected double realProgress() {
        return hits;
    }

    @Override
    public double progress() {
        if (durationMs > 0) {
            return Math.min(1.0, (double) (System.currentTimeMillis() - startAt) / durationMs);
        }
        return maxHits > 0 ? Math.min(1.0, (double) hits / maxHits) : -1;
    }

    @Override
    public String detail() {
        return "已出手 " + hits + " 次，目标：" + targetKey;
    }

    private static double round(final double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static double round(final float value) {
        return round((double) value);
    }
}
