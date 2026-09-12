package com.mcai.bridge.action.tasks;

import com.google.gson.JsonObject;
import com.mcai.bridge.action.Task;
import com.mcai.bridge.action.TaskResult;
import com.mcai.bridge.protocol.Json;
import com.mcai.bridge.util.CombatKit;
import com.mcai.bridge.util.GameUtils;
import com.mcai.bridge.util.ModHooks;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 远程武器：{@code reload}（换弹）和 {@code shoot}（开火）。
 *
 * <h2>支持哪些远程武器</h2>
 *
 * <ul>
 *   <li><b>枪</b>（永恒枪械工艺 TaCZ 这类）：射击是模组在 tick 里**轮询它自己的开火键状态**
 *       触发的（TaCZ = {@code ShootKey.SHOOT_KEY.isDown()}）—— 原版近战那套
 *       {@code gameMode.attack(player, 实体)} 对枪没用，改原版攻击键也没用；
 *       换弹按模组注册的键（TaCZ 监听 {@code InputEvent.Key}，要发**按键事件**）。</li>
 *   <li><b>弓 / 弩 / 三叉戟</b>：{@code useItem} 按住蓄力再松开。弩的蓄力比弓慢，单独给时长。</li>
 * </ul>
 *
 * <h2>开火之前一定先数弹药</h2>
 *
 * <p><b>「记得带子弹」这条就落在这里</b>：没弹药的时候不开火，而是明确回一句
 * 「没有箭/子弹了，先去补，或者改用近战」—— 空放一枪在游戏里表现为「什么都没发生」，
 * AI 会以为是自己瞄歪了，然后一直瞄。每次开火的结果里也会带上剩余弹药。</p>
 */
public final class GunTasks {

    /** 开火键按住多少 tick（20 tick = 1 秒；默认 3 tick 足够打出一发）。 */
    private static final int DEFAULT_FIRE_TICKS = 3;
    /** 弓拉满约 1 秒。 */
    private static final int BOW_DRAW_TICKS = 22;
    /** 弩上弦比弓慢。 */
    private static final int CROSSBOW_DRAW_TICKS = 26;
    /** 三叉戟蓄力约 10 tick。 */
    private static final int TRIDENT_CHARGE_TICKS = 12;

    private GunTasks() {
    }

    // ------------------------------------------------------------------ 换弹

    public static Task reload(final String id, final JsonObject params, final long timeoutMs) {
        // 换弹是有动作时长的（TaCZ 要播完换弹动画才进弹），所以「按下键」不能算完 ——
        // 要等弹匣数字真的涨上去。默认最多等 160 tick（8 秒）。
        //
        // 注意 ticks 常常是调用方沿用 shoot 的默认值（才 3）传进来的，那对换弹毫无意义
        // （真机现象：只等了 20 tick = 1 秒，AK 的换弹动画还没播完，于是误报「换弹失败」）。
        // 所以小于 60 一律当成「没指定」。
        final int asked = Json.intVal(params, "ticks", 160);
        final int waitTicks = Math.max(60, Math.min(asked < 60 ? 160 : asked, 400));
        return new Task(id, "reload", params, timeoutMs, true, false) {
            private int ticks;
            private List<KeyMapping> keys = List.of();
            private String keyLabel = "";
            private boolean pressed;
            private int magBefore = -1;

            @Override
            protected TaskResult onTick(final Minecraft mc) {
                final LocalPlayer player = mc.player;
                if (player == null) {
                    fail("玩家不存在");
                }
                if (CombatKit.rangedKind(player.getMainHandItem()) != CombatKit.Ranged.GUN) {
                    fail("手上不是枪（现在拿着 " + GameUtils.itemId(player.getMainHandItem())
                            + "）。弓/弩/三叉戟不需要换弹，直接 shoot 就行。");
                }
                if (magBefore < 0) {
                    magBefore = CombatKit.magazine(player.getMainHandItem());
                }

                // ---- 第一步：把换弹键按下去
                if (!pressed) {
                    // 首选直接调模组接口 —— 理由和开火一样：TaCZ 的输入闸门要求鼠标锁在窗口里，
                    // 而 AI 托管要把鼠标放开，按键事件根本过不去（真机上它一直是靠模组自己的
                    // autoReload 兜底才进的弹）。
                    if (ModHooks.taczReload(player) != null) {
                        keyLabel = "tacz-api";
                        pressed = true;
                        return null;   // 下一 tick 开始看弹匣
                    }
                    keys = ModHooks.reloadKeys();
                    if (!keys.isEmpty()) {
                        final StringBuilder label = new StringBuilder();
                        for (final KeyMapping key : keys) {
                            if (label.length() > 0) {
                                label.append('、');
                            }
                            label.append(key.getName()).append('=').append(key.getKey().getName());
                            // 关键：发**真正的按键事件**，而不是只改 KeyMapping 状态 ——
                            // TaCZ 的换弹监听的是 Forge 的 InputEvent.Key，收不到状态变化。
                            ModHooks.pressKeyEvent(key);
                            ModHooks.holdKey(key, true);
                        }
                        keyLabel = label.toString();
                    } else {
                        // 按键表里找不到模组的换弹键也不放弃：TaCZ 那种模组比的是**物理键值**，
                        // 直接对 R（默认键）发一个 InputEvent.Key 它照样会响。
                        final int used = ModHooks.triggerReload();
                        if (used < 0) {
                            fail("换弹失败：既没找到枪械模组的换弹键，也没能把按键事件发出去。"
                                    + "确认一下手上是枪，或者手动按 R 试试。"
                                    + "（当前按键表：" + ModHooks.listKeyNames(8) + "）");
                        }
                        keyLabel = "GLFW_KEY_" + used + "（没找到模组的按键，按默认 R 发的）";
                    }
                    pressed = true;
                    return null;   // 下一 tick 开始看弹匣
                }

                // ---- 第二步：等弹匣进弹
                ticks++;
                if (ticks == 3) {
                    for (final KeyMapping key : keys) {
                        ModHooks.holdKey(key, false);
                    }
                }
                final int magNow = CombatKit.magazine(player.getMainHandItem());
                if (magNow > magBefore) {
                    final JsonObject out = new JsonObject();
                    out.addProperty("reloaded", true);
                    out.addProperty("key", keyLabel);
                    out.addProperty("magazineBefore", magBefore);
                    out.addProperty("magazine", magNow);
                    out.add("gun", ModHooks.gunInfo(player.getMainHandItem()));
                    out.add("ranged", CombatKit.rangedStatus(player));
                    return TaskResult.success(out);
                }
                if (ticks < waitTicks) {
                    return null;
                }

                // ---- 等了这么久弹匣还是没变：老实说没换成，并说清可能是为什么
                final JsonObject out = new JsonObject();
                out.addProperty("reloaded", false);
                out.addProperty("key", keyLabel);
                out.addProperty("magazineBefore", magBefore);
                out.addProperty("magazine", magNow);
                out.addProperty("note", "按下换弹键后等了 " + ticks + " tick，弹匣数字没变（还是 "
                        + magNow + " 发）。常见原因："
                        + "①背包里没有**跟这把枪匹配**的子弹（要同口径，例如 7.62×39 的枪不能用 5.45×39 的弹）；"
                        + "②弹匣本来就是满的；"
                        + "③换弹键不对（现在按的是 " + keyLabel + "）。"
                        + "用 mc_inventory 看看有没有对应的子弹。");
                out.add("gun", ModHooks.gunInfo(player.getMainHandItem()));
                out.add("ranged", CombatKit.rangedStatus(player));
                return TaskResult.success(out);
            }

            @Override
            protected void onCancel(final Minecraft mc) {
                ModHooks.releaseAll();
            }

            @Override
            public String detail() {
                return "换弹（" + (keys.isEmpty() ? "找按键" : keys.get(0).getName()) + "）";
            }
        };
    }

    // ------------------------------------------------------------------ 开火

    /**
     * 用远程武器开火。参数：{@code target}（可选，先瞄准）、{@code ticks}（枪按住多久，默认 3）。
     */
    public static Task shoot(final String id, final JsonObject params, final long timeoutMs) {
        final String target = Json.str(params, "target", Json.str(params, "entity", "")).trim();
        final int hold = Math.max(1, Math.min(Json.intVal(params, "ticks", DEFAULT_FIRE_TICKS), 200));
        return new Task(id, "shoot", params, timeoutMs, true, false) {
            private int ticks;
            private boolean prepared;
            private Entity resolved;
            private CombatKit.Ranged kind = CombatKit.Ranged.NONE;
            private int ammoBefore;
            private List<KeyMapping> fireKeys;
            /** 这次开火是走模组 API（true）还是退回按键（false）。 */
            private boolean useApi;
            private boolean apiChecked;
            /** 模组 API 最后回的结果名（SUCCESS / COOL_DOWN / IS_RELOADING…）。 */
            private String taczResult = "";
            /** 这一轮真正打出去几发（按 SUCCESS 计）。 */
            private int shots;

            @Override
            protected TaskResult onTick(final Minecraft mc) {
                final LocalPlayer player = mc.player;
                if (player == null || mc.level == null || mc.gameMode == null) {
                    fail("玩家或世界不存在");
                }

                // ---- 第一步：手上得有远程武器（没有就从背包里换一把）
                //
                // 注意 prepared 只在**武器真的拿到手上之后**才置真：以前是在这个分支开头就置真，
                // 于是「换手 → 下一 tick」会直接跳过检查，带着 kind=NONE 去开火 ——
                // 表现为「报告 fired:true，但一箭都没射出去」（真机实测原话：
                // {"fired": true, "kind": "none", "ammoUsed": 0}）。
                if (!prepared) {
                    final CombatKit.Ranged held = CombatKit.rangedKind(player.getMainHandItem());
                    if (held == CombatKit.Ranged.NONE) {
                        final int slot = CombatKit.findRangedSlot(player);
                        if (slot < 0) {
                            fail("背包里没有远程武器（弓/弩/枪/三叉戟都没有）。"
                                    + "要么先做一把（弓 = 3 木棍 + 3 线），要么改用近战 attack。");
                        }
                        if (slot > 8) {
                            InventoryTasks.swapIntoHotbar(mc, player, slot);
                        } else {
                            player.getInventory().selected = slot;
                        }
                        return null;   // 换手要一个 tick，下一 tick 重新走这个分支
                    }
                    prepared = true;
                    kind = held;
                    ammoBefore = CombatKit.countAmmo(player, kind);

                    // ---- 第二步（关键）：**没弹药不许开火**
                    if (ammoBefore <= 0) {
                        fail(ammoHint(kind));
                    }
                    if (!target.isBlank()) {
                        resolved = scanFor(mc, player, target);
                        if (resolved == null) {
                            fail("找不到目标「" + target + "」。可以用 scan_entities 看附近有什么。");
                        }
                        // 先对准，下一 tick 再开火：服务端要知道我们朝哪
                        GameUtils.lookAt(player, aimPoint(resolved));
                    }
                    return null;   // 站稳一 tick
                }
                if (kind == CombatKit.Ranged.NONE) {
                    fail("手上没有远程武器了（可能在准备过程中被换掉了）。");
                }

                // ---- 开火
                if (resolved != null) {
                    GameUtils.lookAt(player, aimPoint(resolved));
                }
                ticks++;
                switch (kind) {
                    case GUN -> fireGun(mc, player);
                    case TRIDENT -> drawAndRelease(mc, player, TRIDENT_CHARGE_TICKS);
                    case CROSSBOW -> drawAndRelease(mc, player, CROSSBOW_DRAW_TICKS);
                    case BOW -> drawAndRelease(mc, player, BOW_DRAW_TICKS);
                    default -> { }
                }
                if (ticks < 1) {
                    return null;
                }
                if (kind == CombatKit.Ranged.GUN) {
                    // 枪要**真的持续开火 hold tick**。
                    //
                    // 这里以前是漏的：非枪有蓄力等待，枪却直接往下走，于是「按住 20 tick」
                    // 其实只在第一 tick 调了一次就收工（真机现象：ticks=20 却在 0.1 秒内返回）。
                    //
                    // 另外，如果模组回的是「正在换弹 / 正在掏枪」这类**临时**状态，
                    // 再宽限一会儿 —— 不然「刚换完弹立刻开火」会空手而归
                    // （真机就是这么拿到一个 IS_RELOADING 的）。
                    final int budget = hold + (transientState(taczResult) ? 40 : 0);
                    if (ticks < budget) {
                        return null;
                    }
                    releaseFireKeys();
                } else if (ticks < drawTicksFor(kind) + 1) {
                    return null;
                }

                final JsonObject out = new JsonObject();
                out.addProperty("weapon", GameUtils.itemId(player.getMainHandItem()));
                out.addProperty("kind", kind.name().toLowerCase(Locale.ROOT));
                final int ammoAfter = CombatKit.countAmmo(player, kind);
                final int used = Math.max(0, ammoBefore - ammoAfter);
                // 「响没响」以**弹药真的少了**为准。之前在创意模式下弹药不消耗，
                // 所以补一条：能无限用弹药的模式也算打出去了。
                final boolean creative = player.getAbilities().instabuild;
                out.addProperty("fired", kind == CombatKit.Ranged.TRIDENT || creative || used > 0);
                out.addProperty("ammo", ammoAfter);
                out.addProperty("ammoName", CombatKit.ammoName(kind));
                out.addProperty("ammoUsed", used);
                final List<String> warnings = new ArrayList<>();
                if (kind == CombatKit.Ranged.GUN) {
                    final int mag = CombatKit.magazine(player.getMainHandItem());
                    out.addProperty("magazine", mag);
                    out.addProperty("via", useApi ? "tacz-api" : "key(" + fireKeyLabel() + ")");
                    if (!taczResult.isEmpty()) {
                        out.addProperty("taczResult", taczResult);
                        out.addProperty("shots", shots);
                    }
                    if (used == 0 && !creative) {
                        if (mag <= 0) {
                            warnings.add("扣了扳机但弹匣是空的（一发都没少）—— 先 reload 再开火。");
                        } else if (useApi) {
                            warnings.add("枪没响（弹药一发没少，弹匣还有 " + mag + " 发）。"
                                    + "模组接口回的是「" + taczResult + "」——"
                                    + ("IS_RELOADING".equals(taczResult) ? "它正在换弹，等一下再打。"
                                        : "IS_SPRINTING".equals(taczResult) ? "玩家在疾跑，枪打不出去，先停下。"
                                        : "IS_MELEE".equals(taczResult) ? "它正在近战动作里，等一下。"
                                        : "NEED_BOLT".equals(taczResult) ? "要先拉栓。"
                                        : "可能是切枪动作还没做完。"));
                        } else {
                            warnings.add("扣了扳机但**枪没响**（弹药一发没少，弹匣还有 " + mag + " 发）。"
                                    + "用的是按键「" + fireKeyLabel() + "」，"
                                    + "如果这是模组枪，确认它的开火键就是这个。");
                        }
                    }
                    if (mag <= 0 && used > 0) {
                        // 打空了：接着说清楚「接下来会怎样」——托管时模组会自己换弹
                        final int spare = CombatKit.countAmmo(player, CombatKit.Ranged.GUN);
                        warnings.add(spare > 0
                                ? "弹匣打空了，背包里还有 " + spare + " 发备弹 —— 会自动换弹。"
                                : "弹匣打空了，背包里也没有匹配口径的子弹了，先去找子弹。");
                    }
                }
                if (ammoAfter <= 0 && kind != CombatKit.Ranged.TRIDENT) {
                    warnings.add("打完了 —— " + ammoHint(kind));
                } else if (ammoAfter <= 8 && kind != CombatKit.Ranged.TRIDENT) {
                    warnings.add(CombatKit.ammoName(kind) + "只剩 " + ammoAfter + " 发了，记得补。");
                }
                if (!warnings.isEmpty()) {
                    out.addProperty("warning", String.join(" ", warnings));
                }
                if (resolved != null) {
                    out.addProperty("target", GameUtils.entityName(resolved));
                    out.addProperty("targetHealth", resolved instanceof final LivingEntity le
                            ? Math.round(le.getHealth()) : -1);
                }
                return TaskResult.success(out);
            }

            /**
             * 枪：按住**模组自己的开火键**。
             *
             * <p>为什么不是 {@code mc.options.keyAttack}：TaCZ 的开火
             * （{@code com.tacz.guns.client.input.ShootKey.autoShoot}）是在
             * {@code TickEvent.ClientTickEvent} 里每 tick 轮询它自己的
             * {@code SHOOT_KEY.isDown()}，**没有任何按键事件监听** ——
             * 改原版攻击键对它毫无影响。真机症状就是 {@code fired:true}、{@code ammoUsed:0}，
             * 弹匣数字一动不动（明明已经压了 29 发）。</p>
             */
            private void fireGun(final Minecraft mc, final LocalPlayer player) {
                // ---- 首选：直接调模组的开枪接口
                //
                // 走输入那条路时，TaCZ 的 InputExtraCheck.isInGame() 会要求
                // 「鼠标锁在窗口里 + 窗口在前台」，而 AI 托管恰恰要把鼠标放开 ——
                // 于是键按对了也永远打不响（真机：弹匣 29 发、ammoUsed 一直是 0）。
                if (!apiChecked) {
                    useApi = ModHooks.taczReady(player);
                    apiChecked = true;
                }
                if (useApi) {
                    final String result = ModHooks.taczShoot(player);
                    if (result == null) {
                        useApi = false;   // 调不动了（模组被卸/接口变了）→ 退回按键
                    } else {
                        taczResult = result;
                        // 刚切到手上：先做掏枪动作，下一 tick 才能开火
                        if ("NOT_DRAW".equals(result) || "IS_DRAWING".equals(result)) {
                            ModHooks.taczDraw(player, player.getMainHandItem());
                        } else if ("NEED_BOLT".equals(result)) {
                            ModHooks.taczBolt(player);
                        } else if ("SUCCESS".equals(result)) {
                            shots++;
                        }
                        return;
                    }
                }

                // ---- 退路：按模组注册的开火键
                if (fireKeys == null) {
                    fireKeys = ModHooks.shootKeys();
                }
                if (ticks == 1) {
                    for (final KeyMapping key : fireKeys) {
                        ModHooks.holdKey(key, true);
                    }
                    if (hold <= 1) {
                        for (final KeyMapping key : fireKeys) {
                            ModHooks.holdKey(key, false);
                        }
                    }
                } else if (ticks >= hold) {
                    releaseFireKeys();
                }
            }

            /** 松开开火键（别让键一直按着 —— 任务结束后它不会再被谁松开）。 */
            private void releaseFireKeys() {
                if (fireKeys == null) {
                    return;
                }
                for (final KeyMapping key : fireKeys) {
                    ModHooks.holdKey(key, false);
                }
            }

            /** 报给 AI 的「按的是哪个键」——枪没响时要靠它排查。 */
            private String fireKeyLabel() {                if (fireKeys == null || fireKeys.isEmpty()) {
                    return "（一个都没找到）";
                }
                final StringBuilder sb = new StringBuilder();
                for (final KeyMapping key : fireKeys) {
                    if (sb.length() > 0) {
                        sb.append('、');
                    }
                    try {
                        sb.append(key.getName()).append('=').append(key.getKey().getName());
                    } catch (final Throwable t) {
                        sb.append("（读取失败）");
                    }
                }
                return sb.toString();
            }

            /**
             * 弓/弩/三叉戟：**按住右键**蓄力，够了再松开。
             *
             * <p>为什么必须按真键：只调 {@code gameMode.useItem} 的话，
             * 下一个 tick 的 {@code Minecraft.handleKeybinds()} 发现「使用键没按下」，
             * 会立刻替我们松手 —— 服务端收到的蓄力是 0，箭根本射不出去。
             * 真机现象：动作返回 {@code fired:true} 但 {@code ammoUsed:0}（一箭没消耗）。</p>
             */
            private void drawAndRelease(final Minecraft mc, final LocalPlayer player,
                                        final int drawTicks) {
                if (ticks == 1) {
                    mc.options.keyUse.setDown(true);
                    mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
                    return;
                }
                if (ticks == drawTicks) {
                    mc.options.keyUse.setDown(false);
                    mc.gameMode.releaseUsingItem(player);
                }
            }

            @Override
            protected void onCancel(final Minecraft mc) {
                ModHooks.releaseAll();
                try {
                    mc.options.keyUse.setDown(false);
                } catch (final Throwable ignored) {
                    // 忽略
                }
                if (mc.player != null && mc.player.isUsingItem()) {
                    mc.gameMode.releaseUsingItem(mc.player);
                }
            }

            @Override
            public String detail() {
                return "用远程武器开火（" + (kind == CombatKit.Ranged.NONE ? "准备" : kind.name().toLowerCase(Locale.ROOT))
                        + (target.isBlank() ? "" : "，瞄准 " + target) + "）";
            }
        };
    }

    /**
     * 模组回的是不是「现在还不能打，等一下就好」的临时状态。
     *
     * <p>这类不算失败、也不该立刻收工 —— 换完弹要等它退出换弹状态、切枪要等掏枪动作做完，
     * 这几百毫秒里扣扳机是没用的。</p>
     */
    private static boolean transientState(final String result) {
        return "IS_RELOADING".equals(result) || "IS_DRAWING".equals(result)
                || "IS_BOLTING".equals(result) || "IS_MELEE".equals(result)
                || "NOT_DRAW".equals(result) || "NEED_BOLT".equals(result)
                || "COOL_DOWN".equals(result);
    }

    /** 这种远程武器要蓄力多少 tick（枪不用蓄力）。 */
    private static int drawTicksFor(final CombatKit.Ranged kind) {        return switch (kind) {
            case BOW -> BOW_DRAW_TICKS;
            case CROSSBOW -> CROSSBOW_DRAW_TICKS;
            case TRIDENT -> TRIDENT_CHARGE_TICKS;
            default -> 0;
        };
    }

    /** 「没弹药」时该说的话：不只是拒绝，还要说清楚去哪儿弄。 */
    private static String ammoHint(final CombatKit.Ranged kind) {
        return switch (kind) {
            case BOW -> "手上是弓，但**没有箭**了。箭 = 燧石 + 木棍 + 羽毛（羽毛打鸡掉）。"
                    + "先去补箭，或者改用近战 attack。";
            case CROSSBOW -> "手上是弩，但**没有箭也没有烟花火箭**了。"
                    + "先去补，或者改用近战 attack。";
            case GUN -> "手上是枪，但**没有子弹**了（弹匣是空的，背包里也没有匹配的子弹）。"
                    + "子弹是枪械模组自己的物品，而且**分口径** —— 7.62×39 的枪只能用 7.62×39 的弹，"
                    + "拿错口径等于没带。有子弹就 reload 换弹，没有就先去补，或者改用近战 attack。";
            case TRIDENT -> "三叉戟状态异常（它本身不需要弹药）。";
            default -> "没有可用的远程武器和弹药。";
        };
    }

    /**
     * 瞄准点：**眼睛位置**（参考 AutoAim —— 它也是瞄 {@code getEyePosition()}）。
     *
     * <p>比瞄身体中心更准：打头/打上半身命中率高，瞄脚容易打在地上。</p>
     */
    private static Vec3 aimPoint(final Entity target) {
        try {
            return target.getEyePosition();
        } catch (final Throwable t) {
            return new Vec3(target.getX(), target.getY() + target.getBbHeight() * 0.55, target.getZ());
        }
    }

    /** 按名字/uuid/nearest_hostile 找目标（和 attack 的语义一致）。 */
    private static Entity scanFor(final Minecraft mc, final LocalPlayer player, final String key) {
        final String needle = key.trim().toLowerCase(Locale.ROOT);
        Entity best = null;
        double bestDist = Double.MAX_VALUE;
        for (final Entity entity : mc.level.entitiesForRendering()) {
            if (entity == player || !entity.isAlive()) {
                continue;
            }
            // 视线被挡 / 黑名单（村民、宠物…）的不当目标 —— 和 AutoAim 一样的筛选
            if (!CombatKit.isValidTarget(mc, player, entity)) {
                continue;
            }
            final String type = GameUtils.entityTypeId(entity);
            final String name = GameUtils.entityName(entity);
            final boolean match = "nearest".equals(needle) || "nearest_hostile".equals(needle)
                    ? ("nearest".equals(needle) || "HOSTILE".equals(GameUtils.entityCategory(entity)))
                    : (entity.getUUID().toString().equalsIgnoreCase(needle)
                        || type.equalsIgnoreCase(needle)
                        || GameUtils.shortId(type).equalsIgnoreCase(needle)
                        || name.toLowerCase(Locale.ROOT).contains(needle));
            if (!match) {
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

    /** 保留：某件物品是不是枪（给别处复用）。 */
    public static boolean isGun(final ItemStack stack) {
        return ModHooks.isGun(stack);
    }

    /** 保留：换手到快捷栏（InventoryTasks 的封装）。 */
    private static void selectOrMove(final Minecraft mc, final LocalPlayer player, final int slot) {
        if (slot <= 8) {
            player.getInventory().selected = slot;
        } else {
            final Inventory inv = player.getInventory();
            final int hotbar = Math.max(0, Math.min(8, inv.selected));
            mc.gameMode.handleInventoryMouseClick(0, slot, hotbar, ClickType.SWAP, player);
        }
    }
}
