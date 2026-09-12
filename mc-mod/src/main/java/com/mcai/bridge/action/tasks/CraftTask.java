package com.mcai.bridge.action.tasks;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mcai.bridge.action.Navigator;
import com.mcai.bridge.action.Task;
import com.mcai.bridge.action.TaskResult;
import com.mcai.bridge.protocol.Json;
import com.mcai.bridge.snapshot.StateCollector;
import com.mcai.bridge.util.GameUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 合成：{@code craft}（合成物品）与 {@code recipes}（查询配方）。
 *
 * <h2>为什么用「配方书」而不是手动摆格子</h2>
 *
 * <p>客户端有两种合成方式：</p>
 * <ol>
 *   <li><b>配方书</b>：发一个 {@code ServerboundPlaceRecipePacket}
 *       （{@code MultiPlayerGameMode.handlePlaceRecipe}），由<b>服务端</b>把材料摆进合成格。
 *       客户端只负责最后点一下结果槽把成品拿走。</li>
 *   <li><b>手动摆格子</b>：用 {@code handleInventoryMouseClick} 一个一个把材料点进格子。</li>
 * </ol>
 *
 * <p>这里选第 1 种。手动摆格子要自己算形状、自己映射格子索引、自己控制每次点几个，
 * 一旦某次点击与服务器的容器状态不一致，客户端和服务端就会<b>静默失步</b>，
 * 后面的操作全部错位。而配方书只有一条报文，摆材料这件事由服务端权威完成 ——
 * 失败模式退化成「什么都没发生」，可以检测、可以给出明确原因。</p>
 *
 * <h2>2x2 与 3x3</h2>
 *
 * <p>能用 2x2 完成的配方（木板、木棍、工作台、火把…）直接用玩家背包自带的合成格，
 * 不需要任何方块。需要 3x3 的配方则要求附近有一张工作台 —— 本动作会去找、走过去、右键打开。
 * 附近没有工作台时不会自己去造，而是明确告诉 AI「先合成并放置一张工作台」，
 * 因为那本身就是几步自然动作，交给 AI 自己规划更清楚。</p>
 */
public final class CraftTask extends Task {

    /** 找附近工作台的搜索半径。 */
    private static final int TABLE_SEARCH_RADIUS = 8;
    /** 摆好材料后等服务器同步的 tick 数。 */
    private static final int SYNC_TICKS = 3;
    /** 工作台界面打开的超时（tick）。 */
    private static final int MENU_OPEN_TIMEOUT = 60;

    private enum Stage {
        /** 解析配方、检查材料。 */
        PLAN,
        /** 走到工作台旁边。 */
        APPROACH_TABLE,
        /** 右键打开工作台。 */
        OPEN_MENU,
        /** 摆材料 + 取成品。 */
        CRAFTING,
        /** 收拾界面、结算。 */
        FINISH
    }

    private final String itemQuery;
    private final int wantedCount;
    private final int maxCrafts;

    private Stage stage = Stage.PLAN;
    private CraftingRecipe recipe;
    private ItemStack expectedResult = ItemStack.EMPTY;
    private boolean needsTable;
    private BlockPos tablePos;

    private Navigator navigator;
    private int waitTicks;
    private int stageTicks;
    private int craftedTotal;
    private int startCount;
    private int lastResultCount = -1;
    private int noProgressStreak;
    private String planNote = "";
    private final JsonObject recipeInfo = new JsonObject();

    private CraftTask(final String id, final String type, final JsonObject params, final long timeoutMs,
                      final String itemQuery, final int wantedCount, final int maxCrafts) {
        super(id, type, params, timeoutMs, true, true);
        this.itemQuery = itemQuery;
        this.wantedCount = Math.max(1, wantedCount);
        this.maxCrafts = Math.max(1, maxCrafts);
    }

    // ------------------------------------------------------------ 工厂方法

    /** {@code craft}：合成指定物品。 */
    public static Task craft(final String id, final JsonObject params, final long timeoutMs) {
        final String item = Json.str(params, "item", Json.str(params, "output", "")).trim();
        if (item.isEmpty()) {
            return new MoveToTask.FailingTask(id, "craft", params,
                    "缺少参数 item（要合成的物品名，例如 oak_planks、stick、crafting_table）");
        }
        return new CraftTask(id, "craft", params, timeoutMs, item,
                Json.intVal(params, "count", 1),
                Json.intVal(params, "maxCrafts", 64));
    }

    /** {@code recipes}：查询某个物品的配方（不改变世界，给 AI 做规划用）。 */
    public static Task recipes(final String id, final JsonObject params, final boolean instant) {
        final String item = Json.str(params, "item", "").trim();
        if (item.isEmpty()) {
            return new MoveToTask.FailingTask(id, "recipes", params,
                    "缺少参数 item（要查询的物品名）。留空时可以用 mc_recipes 查全部——"
                            + "但那会返回很多数据，建议还是给一个物品名。");
        }
        final int limit = Math.max(1, Math.min(Json.intVal(params, "limit", 10), 50));
        return new QueryRecipesTask(id, params, item, limit, !instant);
    }

    // ============================================================ 主流程

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
        if (player == null || mc.level == null || mc.gameMode == null) {
            fail("玩家或世界不存在");
        }
        stageTicks++;

        // 统一处理「等服务器同步」：等待期间什么都不做
        if (waitTicks > 0) {
            waitTicks--;
            return null;
        }

        return switch (stage) {
            case PLAN -> tickPlan(mc, player);
            case APPROACH_TABLE -> tickApproach(mc, player);
            case OPEN_MENU -> tickOpen(mc, player);
            case CRAFTING -> tickCraft(mc, player);
            case FINISH -> tickFinish(mc, player);
        };
    }

    // ---------------------------------------------------------------- PLAN

    private TaskResult tickPlan(final Minecraft mc, final LocalPlayer player) {
        startCount = countItem(player, itemQuery);

        final List<CraftingRecipe> candidates =
                findCraftingRecipes(mc, player, itemQuery);
        if (candidates.isEmpty()) {
            // 名字太含糊：一口气匹配到好几个不同的物品 —— 说清楚，让 AI 用完整 ID 重来。
            // 绝不在这里「替它挑一个」，那正是做出一堆钻石胸甲的原因。
            if (ambiguousMatches != null && !ambiguousMatches.isEmpty()) {
                fail("「" + itemQuery + "」这个名字**不够明确**：同时匹配到 "
                        + ambiguousMatches.size() + " 个不同的物品（"
                        + String.join("、", ambiguousMatches) + "）。"
                        + "为了不给你做错东西，我没有动手。"
                        + "请改用**完整的物品 ID**，而且一套装备是**四次不同的合成** —— "
                        + "例如钻石头盔 diamond_helmet、钻石胸甲 diamond_chestplate、"
                        + "钻石护腿 diamond_leggings、钻石靴子 diamond_boots，一个一个来"
                        + "（做完一件再调一次 mc_craft 做下一件）。");
            }
            fail("找不到能产出「" + itemQuery + "」的合成配方。"
                    + "可能是名字写错了，或者这个东西压根不能合成（需要用熔炉烧、或者在别的工作台上做）。"
                    + "可以用 mc_recipes 查一下名字对不对，或者用 mc_scan_blocks / mc_inventory 看看手上有什么。");
        }

        recipe = candidates.get(0);
        expectedResult = recipe.getResultItem(mc.level.registryAccess());
        needsTable = !recipe.canCraftInDimensions(2, 2);

        for (final Recipe<?> other : candidates) {
            if (other != recipe) {
                planNote = "（有 " + candidates.size() + " 个配方能产出它，选了当前材料够用的那个）";
                break;
            }
        }
        if (candidates.size() > 1 && planNote.isEmpty()) {
            planNote = "";
        }

        buildRecipeInfo(mc, player);

        if (recipe.isSpecial()) {
            fail("「" + itemQuery + "」是特殊配方（例如给盔甲染色、复制旗帜），"
                    + "不能在普通合成台里自动完成。");
        }

        // 材料够不够
        final List<Ingredient> missing = missingIngredients(player, recipe);
        if (!missing.isEmpty()) {
            final StringBuilder need = new StringBuilder();
            for (final Ingredient ingredient : missing) {
                if (need.length() > 0) {
                    need.append('、');
                }
                need.append(describeIngredient(ingredient));
            }
            fail("材料不够，还缺：" + need + "。"
                    + "可以先 mc_mine_blocks 去挖，或者 mc_inventory 看看背包里到底有什么。");
        }

        // 配方是否已解锁（配方书路径的前提）
        if (!player.getRecipeBook().contains(recipe)) {
            fail("配方「" + itemQuery + "」还没有解锁，配方书里没有它，所以没法自动合成。"
                    + "在游戏里捡到对应材料时通常会自动解锁；确认背包里确实有材料后再试一次。");
        }

        if (needsTable) {
            stage = Stage.APPROACH_TABLE;
            stageTicks = 0;
            return null;
        }
        // 2x2：背包自带的合成格就够。但要先把别的容器界面关掉。
        if (!(player.containerMenu instanceof InventoryMenu)) {
            player.closeContainer();
            waitTicks = SYNC_TICKS;
        }
        stage = Stage.CRAFTING;
        stageTicks = 0;
        return null;
    }

    // ------------------------------------------------------ APPROACH_TABLE

    private TaskResult tickApproach(final Minecraft mc, final LocalPlayer player) {
        // 已经开着一个合成台界面（可能是玩家或 AI 自己开的）就直接用
        if (player.containerMenu instanceof CraftingMenu) {
            stage = Stage.CRAFTING;
            stageTicks = 0;
            return null;
        }

        if (tablePos == null) {
            tablePos = findNearbyTable(mc, player);
            if (tablePos == null) {
                fail("「" + itemQuery + "」需要 3x3 的工作台，但附近 " + TABLE_SEARCH_RADIUS
                        + " 格内没有工作台。请先：\n"
                        + "  1) mc_craft item=crafting_table（4 个木板，2x2 即可合成）\n"
                        + "  2) mc_place 把它放在脚边\n"
                        + "  3) 再调用一次 mc_craft");
            }
        }

        final Vec3 hitPoint = GameUtils.blockCenter(tablePos);
        if (player.getEyePosition().distanceTo(hitPoint) > 4.0) {
            if (navigator.goal() == null || navigator.goal().distSqr(tablePos) > 2.0) {
                navigator.setGoal(tablePos, 2);
            }
            return switch (navigator.step(mc, 1.0)) {
                case ARRIVED -> {
                    navigator.releaseControl();
                    yield null;
                }
                case MOVING -> {
                    if (stageTicks > 20 * 30) {
                        navigator.releaseControl();
                        fail("走到工作台 " + GameUtils.format(tablePos) + " 旁边超时了："
                                + navigator.failureReason());
                    }
                    yield null;
                }
                case FAILED -> {
                    navigator.releaseControl();
                    fail("无法走到工作台 " + GameUtils.format(tablePos) + " 旁边："
                            + navigator.failureReason());
                    yield null;
                }
            };
        }

        stage = Stage.OPEN_MENU;
        stageTicks = 0;
        return null;
    }

    // ----------------------------------------------------------- OPEN_MENU

    private TaskResult tickOpen(final Minecraft mc, final LocalPlayer player) {
        if (player.containerMenu instanceof CraftingMenu) {
            stage = Stage.CRAFTING;
            stageTicks = 0;
            return null;
        }
        if (stageTicks > MENU_OPEN_TIMEOUT) {
            fail("右键了工作台但界面没打开。可能被方块挡住了，或者服务器拒绝了这次交互。");
        }

        GameUtils.lookAt(player, GameUtils.blockCenter(tablePos));
        final BlockHitResult hit = new BlockHitResult(
                GameUtils.blockCenter(tablePos).add(0, 0.45, 0), Direction.UP, tablePos, false);
        final InteractionResult result = mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
        if (result.shouldSwing()) {
            player.swing(InteractionHand.MAIN_HAND);
        }
        waitTicks = 3;
        return null;
    }

    // ------------------------------------------------------------ CRAFTING

    private TaskResult tickCraft(final Minecraft mc, final LocalPlayer player) {
        final AbstractContainerMenu menu = player.containerMenu;
        final boolean isInventoryCraft = menu instanceof InventoryMenu;
        final boolean isTableCraft = menu instanceof CraftingMenu;
        if (!isInventoryCraft && !isTableCraft) {
            fail("当前打开的界面不是合成界面（" + menu.getClass().getSimpleName() + "），无法合成。");
        }
        // InventoryMenu.RESULT_SLOT 和 CraftingMenu.RESULT_SLOT 都是 0
        final int resultSlot = 0;

        final int have = countItem(player, itemQuery);
        if (have - startCount >= wantedCount) {
            stage = Stage.FINISH;
            stageTicks = 0;
            return null;
        }
        // 再按「已经点成功的次数」兜一道 —— 每次循环正好合成一次，craftedTotal 是硬事实。
        // 光靠背包计数不行：真机上出现过计数被材料串味（64 颗钻石算成 64 件胸甲）导致
        // 这个判断永远不成立，于是一路点下去把材料用光，最后卡到看门狗超时。
        if (craftedTotal >= wantedCount) {
            stage = Stage.FINISH;
            stageTicks = 0;
            return null;
        }
        if (craftedTotal >= maxCrafts) {
            stage = Stage.FINISH;
            stageTicks = 0;
            return null;
        }
        if (stageTicks > 20 * 60) {
            fail("合成超时。已经做出 " + craftedTotal + " 个，还可能缺材料。");
        }

        final ItemStack inResultSlot = menu.getSlot(resultSlot).getItem();

        if (inResultSlot.isEmpty()) {
            // 结果槽空着 → 让服务器按配方把材料摆进去
            mc.gameMode.handlePlaceRecipe(menu.containerId, recipe, false);
            waitTicks = SYNC_TICKS;
            stageTicks++;
            return null;
        }

        // 结果槽有东西了 → 直接 shift 点击拿到背包里
        // （用 QUICK_MOVE 而不是 PICKUP，避免成品粘在鼠标上还要再找地方放）
        if (!inResultSlot.is(expectedResult.getItem())) {
            fail("合成结果槽里是 " + GameUtils.itemId(inResultSlot) + "，不是预期的 "
                    + GameUtils.itemId(expectedResult) + "。");
        }

        mc.gameMode.handleInventoryMouseClick(menu.containerId, resultSlot, 0,
                net.minecraft.world.inventory.ClickType.QUICK_MOVE, player);
        craftedTotal++;
        stageTicks = 0;
        waitTicks = SYNC_TICKS;

        // 连续多次「点了但数量没涨」说明材料已经耗尽，早点收手而不是空转到超时
        if (lastResultCount == craftedTotal) {
            noProgressStreak++;
            if (noProgressStreak >= 2) {
                stage = Stage.FINISH;
            }
        } else {
            noProgressStreak = 0;
            lastResultCount = craftedTotal;
        }
        return null;
    }

    // -------------------------------------------------------------- FINISH

    private TaskResult tickFinish(final Minecraft mc, final LocalPlayer player) {
        if (navigator != null) {
            navigator.releaseControl();
        }
        // 关掉合成界面（2x2 用的是背包界面，关了会顺便把物品栏界面也关掉；
        // 3x3 的工作台界面留着更符合直觉，但为了不让 GUI 挡住后续移动，这里一并关掉）
        final boolean hadMenu = !(player.containerMenu instanceof InventoryMenu);
        player.closeContainer();

        final int after = countItem(player, itemQuery);
        final JsonObject out = new JsonObject();
        out.addProperty("item", itemQuery);
        out.addProperty("crafted", craftedTotal);
        out.addProperty("countBefore", startCount);
        out.addProperty("countAfter", after);
        out.addProperty("received", after - startCount);
        out.addProperty("timesCrafted", craftedTotal);
        out.addProperty("recipe", recipe == null ? "" : recipe.getId().toString());
        if (fuzzyPicked != null) {
            // 名字不是精确 ID，是按模糊匹配找到的唯一物品 —— 必须说清楚，
            // 否则 AI 会以为「我说的那个名字 = 做出来的这件东西」。
            out.addProperty("matchedByName", fuzzyPicked);
            out.addProperty("matchedNote", "物品名「" + itemQuery + "」不是精确 ID，"
                    + "按模糊匹配找到的是 " + fuzzyPicked + " —— 确认一下是不是你要的那件。");
        }
        out.add("recipeDetail", recipeInfo);
        if (!planNote.isEmpty()) {
            out.addProperty("note", planNote);
        }
        out.addProperty("closedContainer", hadMenu);

        if (after - startCount <= 0 && craftedTotal == 0) {
            return TaskResult.fail("一次都没合成成功。可能材料不够、配方没解锁，"
                    + "或者服务器拒绝了自动摆放。可以先用 mc_recipes 确认配方与材料，"
                    + "再用 mc_inventory 确认手上确实有东西。");
        }
        if (after - startCount < wantedCount) {
            out.addProperty("shortfall", wantedCount - (after - startCount));
            out.addProperty("hint", "没做够目标数量，通常是材料只够做这么多。");
        }
        return TaskResult.success(out);
    }

    @Override
    protected void onCancel(final Minecraft mc) {
        if (navigator != null) {
            navigator.releaseControl();
        }
        if (mc.player != null && !(mc.player.containerMenu instanceof InventoryMenu)) {
            mc.player.closeContainer();
        }
    }

    /**
     * 卡死检测用的真实进展：已经合成出来的个数。
     *
     * <p>合成时要走到工作台旁边、然后站在原地反复点合成格，中间可以有一段时间不动，
     * 所以必须报这个计数，否则正常的合成会被误判成卡死。</p>
     */
    @Override
    protected double realProgress() {
        return craftedTotal;
    }

    @Override
    public double progress() {
        if (wantedCount <= 0) {
            return -1;
        }
        return Math.min(1.0, (double) craftedTotal / wantedCount);
    }

    @Override
    public String detail() {
        return switch (stage) {
            case PLAN -> "正在解析「" + itemQuery + "」的配方";
            case APPROACH_TABLE -> "正在走向工作台 " + (tablePos == null ? "?" : GameUtils.format(tablePos));
            case OPEN_MENU -> "正在打开工作台";
            case CRAFTING -> "正在合成，已完成 " + craftedTotal + "/" + wantedCount;
            case FINISH -> "正在收尾";
        };
    }

    // ============================================================ 配方查找

    /**
     * 「名字不够明确」时匹配到的候选物品 id。
     *
     * <p>只在「没有精确匹配、而且模糊命中了好几个**不同**物品」时才有值 ——
     * 这种时候宁可报错，也不能随便挑一个做。</p>
     *
     * <p>真机事故：让它做一套钻石套，一个含糊的名字同时命中了头盔/胸甲/护腿/靴子，
     * 而排序是**稳定**的（并列时保持注册表顺序），于是不管要哪一件都默默做出同一个 ——
     * 最后做了一堆钻石胸甲，而且全程没告诉 AI「我做的是别的物品」。</p>
     */
    private List<String> ambiguousMatches;

    /** 这次是**模糊匹配**（名字不是精确 ID）找到的 —— 结果里要说明，免得 AI 以为做对了。 */
    private String fuzzyPicked;

    /** 找到所有能产出目标物品的合成配方，按「材料是否够用」与「是否更容易做」排序。 */
    private List<CraftingRecipe> findCraftingRecipes(
            final Minecraft mc, final LocalPlayer player, final String query) {
        ambiguousMatches = null;
        fuzzyPicked = null;
        final RecipeManager manager = mc.level.getRecipeManager();
        final List<CraftingRecipe> all =
                manager.getAllRecipesFor(RecipeType.CRAFTING);

        final List<CraftingRecipe> exact = new ArrayList<>();
        final List<CraftingRecipe> fuzzy = new ArrayList<>();

        for (final CraftingRecipe candidate : all) {
            final ItemStack result = candidate.getResultItem(mc.level.registryAccess());
            if (result.isEmpty()) {
                continue;
            }
            final MatchKind kind = matchKind(result, query);
            if (kind == MatchKind.EXACT) {
                exact.add(candidate);
            } else if (kind == MatchKind.FUZZY) {
                fuzzy.add(candidate);
            }
        }

        final List<CraftingRecipe> pool;
        if (!exact.isEmpty()) {
            pool = exact;
        } else {
            // 模糊命中：只有**所有候选都是同一个物品**时才敢用。
            // 否则「钻石」「diamond」这种含糊的名字会把 diamond_chestplate / diamond_helmet /
            // diamond_leggings / diamond_boots 全捞进来，稳定排序又把顺序固定成注册表顺序，
            // 于是「不管要哪件都做出同一个」—— 这就是那堆钻石胸甲的来源。
            final java.util.LinkedHashSet<String> outputs = new java.util.LinkedHashSet<>();
            for (final CraftingRecipe r : fuzzy) {
                outputs.add(GameUtils.itemId(r.getResultItem(mc.level.registryAccess())));
            }
            if (outputs.size() != 1) {
                ambiguousMatches = new ArrayList<>(outputs);
                return List.of();
            }
            pool = fuzzy;
            fuzzyPicked = outputs.iterator().next();
        }

        // 优先：材料够 > 2x2 能做（不用找工作台）
        pool.sort(Comparator
                .comparingInt((CraftingRecipe r) ->
                        missingIngredients(player, r).isEmpty() ? 0 : 1)
                .thenComparingInt(r -> r.canCraftInDimensions(2, 2) ? 0 : 1));
        return pool;
    }

    private enum MatchKind {
        NONE, FUZZY, EXACT
    }

    private static MatchKind matchKind(final ItemStack stack, final String query) {
        final String needle = query.trim().toLowerCase(Locale.ROOT);
        final String id = GameUtils.itemId(stack).toLowerCase(Locale.ROOT);
        final String shortId = GameUtils.shortId(id);
        if (!needle.isEmpty() && (id.equals(needle) || shortId.equals(needle))) {
            return MatchKind.EXACT;
        }
        final String hover;
        try {
            hover = stack.getHoverName().getString().toLowerCase(Locale.ROOT);
        } catch (final Exception e) {
            return MatchKind.NONE;
        }
        if (hover.equals(needle)) {
            return MatchKind.EXACT;
        }
        // 模糊：**查询词是物品名的一部分**（"chestplate" → diamond_chestplate）。
        //
        // 这里以前还有反过来的两条 —— `needle.contains(shortId)` / `needle.contains(hover)`，
        // 意思是「查询词里包含物品名」。那两条是个灾难：查 "diamond_chestplate" 时，
        // 钻石的 shortId "diamond" 是查询词的子串，于是**钻石被当成钻石胸甲**。
        // 后果（真机事故「要做一套钻石套，结果做了一堆钻石胸甲」）：
        //   · countItem 在开始前就把 64 颗钻石算成 64 件胸甲 → startCount=64，
        //     循环里 `have - startCount >= 1` 永远不成立，于是一直点、把材料用光、
        //     卡到看门狗超时；
        //   · 结果里 received = 8 - 64 = -56，AI 以为「一件都没做出来」，于是再要一次。
        // 只保留「查询词更短」这一个方向。
        return shortId.contains(needle) || hover.contains(needle) ? MatchKind.FUZZY : MatchKind.NONE;
    }

    /** 背包里是否凑得出这个配方的材料（贪心逐个消耗，够用来挑配方就够了）。 */
    private List<Ingredient> missingIngredients(final LocalPlayer player, final Recipe<?> target) {
        final List<int[]> pool = inventoryCounts(player);
        final List<Ingredient> missing = new ArrayList<>();
        final NonNullList<Ingredient> ingredients = target.getIngredients();
        for (final Ingredient ingredient : ingredients) {
            if (ingredient.isEmpty()) {
                continue;
            }
            boolean consumed = false;
            for (final int[] entry : pool) {
                if (entry[1] <= 0) {
                    continue;
                }
                if (ingredient.test(player.getInventory().getItem(entry[0]))) {
                    entry[1]--;
                    consumed = true;
                    break;
                }
            }
            if (!consumed) {
                missing.add(ingredient);
            }
        }
        return missing;
    }

    /** 返回 [槽位, 可用数量] 的列表（可变的副本，用于贪心消耗）。 */
    private static List<int[]> inventoryCounts(final LocalPlayer player) {
        final List<int[]> out = new ArrayList<>();
        final net.minecraft.world.entity.player.Inventory inv = player.getInventory();
        for (int slot = 0; slot < inv.getContainerSize(); slot++) {
            final ItemStack stack = inv.getItem(slot);
            if (!stack.isEmpty()) {
                out.add(new int[]{slot, stack.getCount()});
            }
        }
        return out;
    }

    private static String describeIngredient(final Ingredient ingredient) {
        final ItemStack[] items = ingredient.getItems();
        if (items.length == 0) {
            return "未知材料";
        }
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(items.length, 3); i++) {
            if (i > 0) {
                sb.append('/');
            }
            sb.append(GameUtils.shortId(GameUtils.itemId(items[i])));
        }
        if (items.length > 3) {
            sb.append(" 等").append(items.length).append("种");
        }
        return sb.toString();
    }

    /** 判断一个物品是否匹配某个模糊描述（脚本条件 has / holding 复用）。 */
    public static boolean matchesSpec(final net.minecraft.world.item.ItemStack stack, final String spec) {
        if (stack == null || stack.isEmpty() || spec == null || spec.isBlank()) {
            return false;
        }
        return matchKind(stack, spec) != MatchKind.NONE;
    }

    /**
     * 统计背包里某个物品的总数。
     *
     * <p><b>精确匹配优先</b>：只要背包里存在精确命中的物品，就只数它们 ——
     * 否则「查 diamond_chestplate 顺手把钻石也数进去」这种串味会让调用方
     * 拿到一个虚高的数字（合成循环靠它判断「做够了没有」，数错就永远做不完）。</p>
     */
    public static int countItem(final LocalPlayer player, final String query) {
        final net.minecraft.world.entity.player.Inventory inv = player.getInventory();
        int exact = 0;
        int fuzzy = 0;
        for (int slot = 0; slot < inv.getContainerSize(); slot++) {
            final ItemStack stack = inv.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            switch (matchKind(stack, query)) {
                case EXACT -> exact += stack.getCount();
                case FUZZY -> fuzzy += stack.getCount();
                default -> { }
            }
        }
        // 副手也算
        final ItemStack offhand = player.getOffhandItem();
        if (!offhand.isEmpty()) {
            switch (matchKind(offhand, query)) {
                case EXACT -> exact += offhand.getCount();
                case FUZZY -> fuzzy += offhand.getCount();
                default -> { }
            }
        }
        return exact > 0 ? exact : fuzzy;
    }

    private void buildRecipeInfo(final Minecraft mc, final LocalPlayer player) {
        recipeInfo.addProperty("id", recipe.getId().toString());
        recipeInfo.addProperty("output", GameUtils.itemId(expectedResult));
        recipeInfo.addProperty("outputName", GameUtils.safeItemName(expectedResult));
        recipeInfo.addProperty("outputPerCraft", expectedResult.getCount());
        recipeInfo.addProperty("needsTable", needsTable);
        recipeInfo.addProperty("unlocked", player.getRecipeBook().contains(recipe));
        final JsonArray ingredients = new JsonArray();
        for (final Ingredient ingredient : recipe.getIngredients()) {
            if (!ingredient.isEmpty()) {
                ingredients.add(describeIngredient(ingredient));
            }
        }
        recipeInfo.add("ingredients", ingredients);
    }

    /** 找附近最近的工作台。 */
    private BlockPos findNearbyTable(final Minecraft mc, final LocalPlayer player) {
        final List<BlockPos> found = QueryTasks.collectBlocks(mc.level, player.blockPosition(),
                "crafting_table", TABLE_SEARCH_RADIUS, 6, 8);
        return found.isEmpty() ? null : found.get(0);
    }

    // ================================================== 只读的配方查询任务

    /** {@code recipes}：列出某个物品的配方与材料需求，不改动世界。 */
    private static final class QueryRecipesTask extends Task {

        private final String itemQuery;
        private final int limit;

        QueryRecipesTask(final String id, final JsonObject params, final String itemQuery,
                         final int limit, final boolean longRunning) {
            super(id, "recipes", params, 10_000L, longRunning, false);
            this.itemQuery = itemQuery;
            this.limit = limit;
        }

        @Override
        protected TaskResult onTick(final Minecraft mc) {
            final LocalPlayer player = mc.player;
            if (player == null || mc.level == null) {
                fail("尚未进入世界");
            }
            final RecipeManager manager = mc.level.getRecipeManager();
            final List<CraftingRecipe> all =
                    manager.getAllRecipesFor(RecipeType.CRAFTING);

            final JsonArray matches = new JsonArray();
            final List<String> lines = new ArrayList<>();
            for (final CraftingRecipe candidate : all) {
                final ItemStack result = candidate.getResultItem(mc.level.registryAccess());
                if (result.isEmpty() || matchKind(result, itemQuery) == MatchKind.NONE) {
                    continue;
                }
                final boolean table = !candidate.canCraftInDimensions(2, 2);
                final boolean unlocked = player.getRecipeBook().contains(candidate);
                final List<Ingredient> missing = new ArrayList<>();
                for (final Ingredient ingredient : candidate.getIngredients()) {
                    if (ingredient.isEmpty()) {
                        continue;
                    }
                    if (!playerHas(player, ingredient)) {
                        missing.add(ingredient);
                    }
                }

                final JsonObject entry = new JsonObject();
                entry.addProperty("recipe", candidate.getId().toString());
                entry.addProperty("output", GameUtils.itemId(result));
                entry.addProperty("outputName", GameUtils.safeItemName(result));
                entry.addProperty("outputPerCraft", result.getCount());
                entry.addProperty("needsTable", table);
                entry.addProperty("unlocked", unlocked);
                final JsonArray ingredients = new JsonArray();
                for (final Ingredient ingredient : candidate.getIngredients()) {
                    if (!ingredient.isEmpty()) {
                        ingredients.add(describeIngredient(ingredient));
                    }
                }
                entry.add("ingredients", ingredients);
                entry.addProperty("ingredientsReady", missing.isEmpty());
                matches.add(entry);

                lines.add(String.format(Locale.ROOT, "· %s ×%d ← %s%s%s%s",
                        GameUtils.safeItemName(result), result.getCount(),
                        ingredients.isEmpty() ? "?" : jsonToList(ingredients),
                        table ? "（需要工作台）" : "（2x2 背包即可）",
                        unlocked ? "" : "【未解锁】",
                        missing.isEmpty() ? "" : "【材料不足】"));

                if (matches.size() >= limit) {
                    break;
                }
            }

            final JsonObject out = new JsonObject();
            out.addProperty("query", itemQuery);
            out.addProperty("found", matches.size());
            out.add("recipes", matches);

            if (matches.isEmpty()) {
                // 没有合成台配方？那可能是**别的模组的加工方式**：Create 的粉碎/混合/辊压、
                // 模组熔炉、枪械装配台…… 原版的 RecipeManager 里装着**所有**模组注册的配方，
                // 翻一遍就能告诉 AI「这东西到底是怎么来的」，而不是一句「不能合成」。
                final JsonArray other = new JsonArray();
                final List<String> otherLines = new ArrayList<>();
                for (final Recipe<?> candidate : manager.getRecipes()) {
                    if (candidate.getType() == RecipeType.CRAFTING || candidate.isSpecial()) {
                        continue;
                    }
                    final ItemStack result = candidate.getResultItem(mc.level.registryAccess());
                    if (result.isEmpty() || matchKind(result, itemQuery) == MatchKind.NONE) {
                        continue;
                    }
                    final String type = GameUtils.shortId(candidate.getType().toString());
                    final JsonObject entry = new JsonObject();
                    entry.addProperty("recipe", candidate.getId().toString());
                    entry.addProperty("type", type);
                    entry.addProperty("output", GameUtils.itemId(result));
                    entry.addProperty("outputName", GameUtils.safeItemName(result));
                    entry.addProperty("outputPerCraft", result.getCount());
                    final JsonArray ingredients = new JsonArray();
                    for (final Ingredient ingredient : candidate.getIngredients()) {
                        if (!ingredient.isEmpty()) {
                            ingredients.add(describeIngredient(ingredient));
                        }
                    }
                    entry.add("ingredients", ingredients);
                    other.add(entry);
                    otherLines.add("· " + GameUtils.safeItemName(result) + " ×" + result.getCount()
                            + " ← " + (ingredients.isEmpty() ? "?" : jsonToList(ingredients))
                            + "【加工方式：" + type + "】");
                    if (other.size() >= limit) {
                        break;
                    }
                }
                if (!other.isEmpty()) {
                    out.add("otherRecipes", other);
                    return TaskResult.success(withContent(out,
                            "没有**合成台**配方，但找到了 " + other.size() + " 条别的加工方式的配方"
                                    + "（模组机器/熔炉之类，模组得自己会用那台机器）：\n"
                                    + String.join("\n", otherLines)));
                }
            }
            if (matches.isEmpty()) {
                return TaskResult.success(withContent(out,
                        "没有找到能产出「" + itemQuery + "」的配方（合成台和模组加工方式都翻过了）。"
                                + "确认名字拼写 —— 中文显示名和内部 ID 都能查，"
                                + "也可以先用 mc_inventory 看看手上有什么。"));
            }
            return TaskResult.success(withContent(out,
                    "找到 " + matches.size() + " 个配方：\n" + String.join("\n", lines)));
        }

        private static String jsonToList(final JsonArray array) {
            final StringBuilder sb = new StringBuilder();
            for (int i = 0; i < array.size(); i++) {
                if (i > 0) {
                    sb.append(" + ");
                }
                sb.append(array.get(i).getAsString());
            }
            return sb.toString();
        }

        private static boolean playerHas(final LocalPlayer player, final Ingredient ingredient) {
            final net.minecraft.world.entity.player.Inventory inv = player.getInventory();
            for (int slot = 0; slot < inv.getContainerSize(); slot++) {
                if (ingredient.test(inv.getItem(slot))) {
                    return true;
                }
            }
            return ingredient.test(player.getOffhandItem());
        }

        /** 把给 LLM 看的文本塞进结果里（模组侧统一用 content 字段）。 */
        private static JsonObject withContent(final JsonObject out, final String content) {
            out.addProperty("content", content);
            return out;
        }

        @Override
        public String detail() {
            return "正在查询「" + itemQuery + "」的配方";
        }
    }
}
