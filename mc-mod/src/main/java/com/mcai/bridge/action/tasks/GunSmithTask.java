package com.mcai.bridge.action.tasks;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mcai.bridge.action.Task;
import com.mcai.bridge.action.TaskResult;
import com.mcai.bridge.protocol.Json;
import com.mcai.bridge.util.TaCZSmithTable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 枪械工作台（TaCZ）的两个动作：{@code list} 看能做什么、{@code craft} 报个配方名就做。
 *
 * <p>为什么和原版 {@code craft} 分开写：原版合成是「找 3×3 配方 → 摆材料 → 取成品」，
 * 枪械工作台是「界面列配方 → 选一个 → 服务端从背包扣材料」。两者除了名字像，机制毫无关系，
 * 硬塞进 {@link CraftTask} 只会让那一大团状态机更难读。</p>
 *
 * <p>对 AI 来说这套设计的好处是「先问价再出门」：{@link #list} 不要求站在工作台旁边，
 * 它会直接报出「做这把枪要钢锭×12、你现在只有 4」，于是采材料这件事可以在出门前就想清楚。</p>
 */
public final class GunSmithTask {

    private GunSmithTask() {
    }

    // ------------------------------------------------------------------ 入口

    /** 统一入口：给了 recipe 就是制作，否则是列清单。 */
    public static Task create(final String id, final JsonObject params, final boolean instant) {
        final String recipe = Json.str(params, "recipe", Json.str(params, "item", "")).trim();
        final String mode = Json.str(params, "mode", recipe.isEmpty() ? "list" : "craft")
                .trim().toLowerCase(Locale.ROOT);
        if ("craft".equals(mode)) {
            if (recipe.isEmpty()) {
                return new MoveToTask.FailingTask(id, "gun_smith", params,
                        "要制作就得给 recipe（配方 id，例如 tacz:ak47）。"
                                + "不知道 id 就先不带 recipe 调一次本工具："
                                + "它会列出这台机器能做什么，材料齐的排在前面。");
            }
            return new CraftOneTask(id, params, recipe);
        }
        return new ListTask(id, params);
    }

    // ---------------------------------------------------------------- 列清单

    /** 看这台机器能做什么（只读，随时可查，不需要界面开着）。 */
    private static final class ListTask extends Task {

        private ListTask(final String id, final JsonObject params) {
            super(id, "gun_smith", params, 10_000L, false, false);
        }

        @Override
        protected TaskResult onTick(final Minecraft mc) {
            final LocalPlayer player = mc.player;
            if (player == null || mc.level == null) {
                fail("尚未进入世界");
            }
            if (!TaCZSmithTable.available()) {
                fail("这个整合包里没有枪械工作台（没装永恒枪械工坊 / TaCZ）。"
                        + "想做普通的东西请用 mc_craft。");
            }
            final String query = Json.str(params, "query", Json.str(params, "filter", ""))
                    .trim().toLowerCase(Locale.ROOT);
            final String tab = Json.str(params, "tab", "").trim().toLowerCase(Locale.ROOT);
            final boolean craftableOnly = Json.bool(params, "craftable_only",
                    Json.bool(params, "craftableOnly", false));
            final int limit = Math.max(1, Math.min(Json.intVal(params, "limit", 20), 100));

            final List<Entry> all = new ArrayList<>();
            int craftableTotal = 0;
            for (final TaCZSmithTable.RecipeInfo info : TaCZSmithTable.recipes(mc.level)) {
                final Entry entry = new Entry(info, player);
                if (entry.missing.isEmpty()) {
                    craftableTotal++;
                }
                all.add(entry);
            }

            final List<Entry> matched = new ArrayList<>();
            for (final Entry entry : all) {
                if (!query.isEmpty() && !entry.matches(query)) {
                    continue;
                }
                if (!tab.isEmpty() && (entry.info.tab == null
                        || !entry.info.tab.toLowerCase(Locale.ROOT).contains(tab))) {
                    continue;
                }
                if (craftableOnly && !entry.missing.isEmpty()) {
                    continue;
                }
                matched.add(entry);
            }
            // 材料齐的排前面：AI 问「能做什么」时，第一眼该看到的是「现在就能做的」。
            matched.sort(Comparator
                    .comparingInt((Entry e) -> e.missing.isEmpty() ? 0 : 1)
                    .thenComparing(e -> e.info.id));

            final JsonObject out = new JsonObject();
            out.addProperty("mode", "list");
            out.addProperty("total", all.size());
            out.addProperty("craftableTotal", craftableTotal);
            out.addProperty("matched", matched.size());
            if (!query.isEmpty()) {
                out.addProperty("query", query);
            }
            final JsonArray arr = new JsonArray();
            for (final Entry entry : matched.subList(0, Math.min(limit, matched.size()))) {
                arr.add(entry.toJson());
            }
            out.add("recipes", arr);
            if (matched.size() > limit) {
                out.addProperty("truncated", true);
                out.addProperty("note", "只列了前 " + limit + " 条（共 " + matched.size()
                        + " 条匹配）。要缩小范围就加 query（配方 id 或成品名里的关键字，"
                        + "例如 query=ak47），只想看现在就能做的就加 craftable_only=true。");
            }
            if (matched.isEmpty()) {
                if (all.isEmpty()) {
                    out.addProperty("hint", "这台机器一条配方都没有：枪械配方来自数据包，"
                            + "可能是整合包没装枪包。");
                } else if (craftableOnly && craftableTotal == 0) {
                    out.addProperty("hint", "现在一条材料齐的配方都没有 —— 背包里没有枪械材料。"
                            + "去掉 craftable_only 再列一次，就能看到每个配方到底要什么材料。");
                } else if (craftableOnly) {
                    out.addProperty("hint", "加上 craftable_only 之后没有匹配的了。"
                            + "去掉它看看这些关键字下都有什么。");
                } else {
                    out.addProperty("hint", "没有匹配的配方。query 是按「配方 id / 成品名 / 分类」"
                            + "模糊匹配的，换个关键字或去掉 query 再看看。");
                }
            }
            return TaskResult.success(out);
        }

        @Override
        public String detail() {
            return "查枪械工作台配方";
        }
    }

    // ------------------------------------------------------------------ 制作

    /** 报一个配方 id，做出成品（需要先右键打开工作台界面）。 */
    private static final class CraftOneTask extends Task {

        private final String query;
        private TaCZSmithTable.RecipeInfo target;
        private int before = -1;
        /** 发包前每条材料的数量快照（用来判断「材料被扣了没有」）。 */
        private int[] beforeInputs;
        private int waited;
        /** 材料已经被扣（服务端确实做了），但成品还没同步过来的情况。 */
        private boolean consumed;
        private int consumedAt;

        private CraftOneTask(final String id, final JsonObject params, final String query) {
            // **必须是长任务**（第 5 个参数 = longRunning）。
            //
            // 踩过的坑（真机）：一开始这里写的 false，而 createTask 里的 instant 是按
            // isLongRunning(动作名) 算的 —— "gun_smith" 不在长任务名单里，于是执行器
            // 只推进一 tick 就收工：制作包发出去了，但任务对象被丢掉，回传的是个空结果
            // （真机日志：result={}、elapsed_ms=2，看着像「成功了但什么都没说」）。
            // 制作要等服务端一个来回，必须让执行器一直 tick 到出结果为止。
            super(id, "gun_smith", params, 15_000L, true, false);
            this.query = query;
        }

        @Override
        protected TaskResult onTick(final Minecraft mc) {
            final LocalPlayer player = mc.player;
            if (player == null || mc.level == null) {
                fail("尚未进入世界");
            }
            if (!TaCZSmithTable.available()) {
                fail("这个整合包里没有枪械工作台（没装 TaCZ）。");
            }
            if (target == null) {
                return start(mc, player);
            }

            final int now = count(player, target);
            if (now > before) {
                return done(now, "output", null);
            }

            // 成品没出现，但材料已经少了 —— 说明服务端**确实**把这次制作做掉了。
            //
            // 为什么不能只盯成品（真机踩的坑）：枪械工作台的菜单是 0 槽位的，服务端改玩家背包
            // 之后没有槽位可广播，客户端要等下一次背包同步才看得到那把枪。实测：制作发出后
            // 3 秒时客户端还看不到成品（材料却已经扣了），十几秒后才出现。
            // 只等成品就会把一次成功的制作报成「服务端没接受」，然后 AI 会去重试。
            if (materialsUsed(player)) {
                consumed = true;
                consumedAt = ticks;
            }
            // 只等一小会儿（默认 2 秒）。
            //
            // 为什么不等久一点：枪械工作台的菜单是 0 槽位的，服务端改完玩家背包**没有槽位可以广播**，
            // 客户端要过十几秒才看得见成品（真机实测：3 秒时还是空的，20 秒后才有）。干等 20 秒有两个坏处：
            // 一是动作会撞上模组自己的超时、结果直接丢掉（真机日志：stillRunning，成品和材料都读不到），
            // 二是 AI 白等。所以这里只确认「能确认的部分」，剩下的交给 mc_inventory 事后核实。
            final int wait = Math.max(10, Math.min(Json.intVal(params, "ticks", 40), 200));
            if (++waited > wait) {
                final String note = consumed
                        ? "材料在第 " + consumedAt + " tick 就被扣掉了，说明服务端接受了这次制作并已经完成。"
                          + "成品这会儿可能还没同步到客户端（上面说过原因）—— 关掉界面（mc_close_screen）"
                          + "再看 mc_inventory 最准。"
                        : "制作包已经发出去了，但 " + (wait / 20) + " 秒内客户端这边还看不到任何变化"
                          + "（材料没少、成品没出现）。这在枪械工作台上是正常的：它的菜单 0 槽位，"
                          + "服务端改完背包不会广播给客户端。**先用 mc_close_screen 关掉界面，"
                          + "再 mc_inventory 看一眼**就知道到底做成了没有 —— 别急着重发，那会白扣一份材料。";
                return done(count(player, target), consumed ? "materials" : "sent", note);
            }
            return null;
        }

        /** 组装回执。{@code signal} 说明是靠哪个信号判定成功的。 */
        private TaskResult done(final int have, final String signal, final String note) {
            final JsonObject out = new JsonObject();
            out.addProperty("mode", "craft");
            out.addProperty("recipe", target.id);
            out.addProperty("output", target.outputName());
            out.addProperty("have", have);
            out.addProperty("signal", signal);
            if ("output".equals(signal)) {
                out.addProperty("crafted", have - before);
            }
            out.addProperty("elapsedTicks", ticks);
            if (note != null) {
                out.addProperty("note", note);
            }
            return TaskResult.success(out);
        }

        /** 材料是不是被扣掉了（和发包前的快照逐条比）。 */
        private boolean materialsUsed(final LocalPlayer player) {
            if (beforeInputs == null || target == null) {
                return false;
            }
            final int[] now = inputCounts(player, target);
            for (int i = 0; i < now.length && i < beforeInputs.length; i++) {
                if (now[i] < beforeInputs[i]) {
                    return true;
                }
            }
            return false;
        }

        /** 校验 + 解析配方 + 发包。 */
        private TaskResult start(final Minecraft mc, final LocalPlayer player) {
            final List<TaCZSmithTable.RecipeInfo> all = TaCZSmithTable.recipes(mc.level);
            final TaCZSmithTable.RecipeInfo picked = pick(all, query);
            if (picked == null) {
                final List<TaCZSmithTable.RecipeInfo> fuzzy = fuzzy(all, query);
                if (fuzzy.size() > 1) {
                    final StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < Math.min(fuzzy.size(), 8); i++) {
                        sb.append(i > 0 ? "、" : "").append(fuzzy.get(i).id)
                                .append("（").append(fuzzy.get(i).outputName()).append("）");
                    }
                    fail("「" + query + "」这个名字**不够明确**：同时匹配到 " + fuzzy.size()
                            + " 条配方（" + sb + (fuzzy.size() > 8 ? " 等" : "") + "）。"
                            + "为了不给你做错东西，我没有动手。请改用完整的配方 id。");
                }
                fail("枪械工作台里找不到配方「" + query + "」。"
                        + "不带 recipe 调一次本工具会列出所有配方（材料齐的排在前面）。");
            }
            target = picked;

            // 材料先自己核一遍：让「材料不够」变成一句能照着去采的话，
            // 而不是发完包干等、最后报一句「服务端没接受」。
            final Entry check = new Entry(picked, player);
            if (!check.missing.isEmpty()) {
                fail("材料不够，做不了「" + picked.outputName() + "」：" + check.missingText()
                        + "。先把材料凑齐再来（可以去 mc_forage / mc_mine_blocks / mc_craft 弄）。");
            }

            final TaCZSmithTable.OpenTable table = TaCZSmithTable.open();
            if (table == null) {
                fail("枪械工作台界面没开着 —— 制作请求必须由一个**真的开着的**工作台界面发出去"
                        + "（服务端会核对菜单号）。操作顺序：mc_move_to 走到工作台旁边 → "
                        + "mc_use_on_block 右键那台「枪械工作台」→ 再调本工具。");
            }
            before = count(player, picked);
            beforeInputs = inputCounts(player, picked);
            final String error = TaCZSmithTable.craft(
                    ResourceLocation.tryParse(picked.id), table.menuId);
            if (error != null) {
                fail(error);
            }
            detail = "正在做「" + picked.outputName() + "」（" + picked.id + "）";
            return null;
        }

        @Override
        public String detail() {
            return detail;
        }

        private String detail = "准备制作";
    }

    // ------------------------------------------------------------ 配方匹配

    /**
     * 从配方表里挑一条。
     *
     * <p>严格按「精确 → 短 id 精确 → 模糊唯一」的顺序，任何一步出现歧义都返回 null
     * 交给调用方报错 —— 合成那套踩过的坑（把钻石胸甲数成钻石，一口气做了 64 件）
     * 就是「替 AI 猜」猜出来的。</p>
     */
    private static TaCZSmithTable.RecipeInfo pick(final List<TaCZSmithTable.RecipeInfo> all,
                                                  final String query) {
        final String q = query.trim().toLowerCase(Locale.ROOT);
        final String bare = q.contains(":") ? q.substring(q.indexOf(':') + 1) : q;
        // 1) 完整的配方 id
        for (final TaCZSmithTable.RecipeInfo info : all) {
            if (info.id.toLowerCase(Locale.ROOT).equals(q)) {
                return info;
            }
        }
        // 2) 不带命名空间的短 id（例如 ak47 → tacz:ak47）
        TaCZSmithTable.RecipeInfo shortHit = null;
        for (final TaCZSmithTable.RecipeInfo info : all) {
            final String id = info.id.toLowerCase(Locale.ROOT);
            final String shortId = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
            if (shortId.equals(bare)) {
                if (shortHit != null) {
                    return null;
                }
                shortHit = info;
            }
        }
        if (shortHit != null) {
            return shortHit;
        }
        // 3) 模糊：唯一命中才认
        final List<TaCZSmithTable.RecipeInfo> fuzzy = fuzzy(all, q);
        return fuzzy.size() == 1 ? fuzzy.get(0) : null;
    }

    /** 模糊匹配：配方 id 或成品名里含关键字。 */
    private static List<TaCZSmithTable.RecipeInfo> fuzzy(final List<TaCZSmithTable.RecipeInfo> all,
                                                         final String query) {
        final String q = query.trim().toLowerCase(Locale.ROOT);
        final String bare = q.contains(":") ? q.substring(q.indexOf(':') + 1) : q;
        final List<TaCZSmithTable.RecipeInfo> out = new ArrayList<>();
        for (final TaCZSmithTable.RecipeInfo info : all) {
            if (info.id.toLowerCase(Locale.ROOT).contains(bare)
                    || info.outputName().toLowerCase(Locale.ROOT).contains(bare)) {
                out.add(info);
            }
        }
        return out;
    }

    /** 配方要求的材料在背包里各有多少（含盔甲槽与副手，和 TaCZ 扣材料的口径一致）。 */
    private static int countIngredient(final LocalPlayer player,
                                       final TaCZSmithTable.Ing ing) {
        final net.minecraft.world.entity.player.Inventory inv = player.getInventory();
        int total = 0;
        for (int slot = 0; slot < inv.getContainerSize(); slot++) {
            final ItemStack stack = inv.getItem(slot);
            if (!stack.isEmpty() && ing.ingredient.test(stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** 每条材料当前的数量（顺序和配方的 inputs 一致，用于前后对比）。 */
    private static int[] inputCounts(final LocalPlayer player,
                                     final TaCZSmithTable.RecipeInfo info) {
        final int[] out = new int[info.inputs.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = countIngredient(player, info.inputs.get(i));
        }
        return out;
    }

    /** 统计背包里某个成品的数量（成品指的是同一件物品，枪的 NBT 不参与比较）。 */
    private static int count(final LocalPlayer player, final TaCZSmithTable.RecipeInfo info) {
        if (info.output.isEmpty()) {
            return 0;
        }
        final net.minecraft.world.entity.player.Inventory inv = player.getInventory();
        int total = 0;
        for (int slot = 0; slot < inv.getContainerSize(); slot++) {
            final ItemStack stack = inv.getItem(slot);
            if (!stack.isEmpty() && stack.getItem() == info.output.getItem()) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** 一条配方 + 这名玩家当前的材料情况。 */
    private static final class Entry {
        final TaCZSmithTable.RecipeInfo info;
        final List<String> missing = new ArrayList<>();
        final List<int[]> have;   // 每条 [{需要, 现有}]

        Entry(final TaCZSmithTable.RecipeInfo info, final LocalPlayer player) {
            this.info = info;
            final List<int[]> counts = new ArrayList<>();
            for (final TaCZSmithTable.Ing ing : info.inputs) {
                final int now = countIngredient(player, ing);
                counts.add(new int[]{ing.count, now});
                if (now < ing.count) {
                    missing.add(ing.name() + "×" + (ing.count - now));
                }
            }
            this.have = counts;
        }

        boolean matches(final String q) {
            return info.id.toLowerCase(Locale.ROOT).contains(q)
                    || info.outputName().toLowerCase(Locale.ROOT).contains(q)
                    || (info.tab != null && info.tab.toLowerCase(Locale.ROOT).contains(q));
        }

        String missingText() {
            return String.join("、", missing);
        }

        JsonObject toJson() {
            final JsonObject o = new JsonObject();
            o.addProperty("recipe", info.id);
            o.addProperty("output", info.outputName());
            if (!info.output.isEmpty()) {
                o.addProperty("outputId", com.mcai.bridge.util.GameUtils.itemId(info.output));
                if (info.output.getCount() > 1) {
                    o.addProperty("outputCount", info.output.getCount());
                }
            }
            if (info.tab != null) {
                o.addProperty("tab", info.tab);
            }
            o.addProperty("canCraft", missing.isEmpty());
            final JsonArray inputs = new JsonArray();
            for (int i = 0; i < info.inputs.size(); i++) {
                final TaCZSmithTable.Ing ing = info.inputs.get(i);
                final JsonObject one = new JsonObject();
                one.addProperty("item", ing.itemId());
                one.addProperty("name", ing.name());
                one.addProperty("need", have.get(i)[0]);
                one.addProperty("have", have.get(i)[1]);
                inputs.add(one);
            }
            o.add("inputs", inputs);
            if (!missing.isEmpty()) {
                o.addProperty("missing", missingText());
            }
            return o;
        }
    }
}
