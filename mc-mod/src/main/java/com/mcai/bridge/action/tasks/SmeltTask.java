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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.ForgeHooks;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code smelt}：在熔炉里烧东西（原矿 → 铁锭、沙子 → 玻璃、生肉 → 熟肉…）。
 *
 * <h2>为什么必须有它</h2>
 *
 * <p>没有熔炼，「生存模式盖地狱门」这种流程在第一道关卡就过不去：
 * 挖出来的只是**原矿**，而做桶、做打火石、做铁镐全都要**铁锭**。
 * 合成解决的是「把 A 拼成 B」，熔炼是另一半。</p>
 *
 * <h2>怎么用</h2>
 *
 * <p>给的是<b>想要的东西</b>，不是原料 —— 和合成任务一个风格：</p>
 * <pre>
 * {"item": "iron_ingot", "count": 3}   自动在背包里找能烧成铁锭的东西
 * {"item": "glass", "count": 8}        自动找沙子
 * </pre>
 *
 * <p>熔炉要**已经在附近**（找不到会告诉你先 craft + place），
 * 和 {@code craft} 要求附近有工作台是一致的态度：模组不替玩家决定设施摆在哪。</p>
 *
 * <h2>实现要点</h2>
 *
 * <ul>
 *   <li>放料取料都用原版的 <b>shift-click（QUICK_MOVE）</b>：熔炉菜单自己会把原料分到输入槽、
 *       可燃物分到燃料槽、产物从输出槽挪回背包 —— 不用我们算槽位。
 *       手算槽位一旦和服务端不同步就是静默错位，这类 bug 极难查。</li>
 *   <li>「哪个背包格对应哪个菜单槽」不靠猜布局，而是遍历菜单里的 {@link Slot}
 *       按 {@code container + containerSlot} 反查（原版把主背包和快捷栏的排列顺序
 *       和玩家背包下标并不一致，猜错了就会把不相干的东西塞进熔炉）。</li>
 *   <li>燃料用 {@link ForgeHooks#getBurnTime} 判断，煤/木炭/木板/原木都认，
 *       模组加了新燃料也自动认得。</li>
 *   <li>「原料 → 产物」查的是游戏自己的 {@link RecipeType#SMELTING} 配方表，不写死物品名。</li>
 * </ul>
 */
public final class SmeltTask extends Task {

    /** 打开界面的等待上限（tick）。 */
    private static final int MENU_OPEN_TIMEOUT = 60;
    /** 烧一个物品最多等多久（tick）。原版一个铁矿石 10 秒，留足余量。 */
    private static final int PER_ITEM_TIMEOUT = 20 * 30;
    /** 默认找熔炉的半径。 */
    private static final int DEFAULT_RADIUS = 8;
    /** 打开菜单后先等几 tick 让内容同步。 */
    private static final int MENU_SYNC_TICKS = 5;

    private enum Stage { FIND, APPROACH, OPEN, LOAD, WAIT, TAKE, FINISH }

    private final String wantSpec;
    private final int wantedCount;
    private final int radius;

    private Stage stage = Stage.FIND;
    private int stageTicks;
    private BlockPos furnacePos;
    private Navigator navigator;

    private int startCount;
    private int produced;
    private String inputSpec = "";
    private String fuelUsed = "";
    private int smeltRounds;
    private final List<String> notes = new ArrayList<>();

    private SmeltTask(final String id, final JsonObject params, final long timeoutMs) {
        super(id, "smelt", params, timeoutMs, true, true);
        this.wantSpec = Json.str(params, "item", "").trim();
        this.wantedCount = Math.max(1, Json.intVal(params, "count", 1));
        this.radius = Math.max(3, Math.min(Json.intVal(params, "radius", DEFAULT_RADIUS), 32));
    }

    /** 参数不对时返回「立刻失败」的任务，把可读原因带回给 LLM，而不是炸掉执行链。 */
    public static Task create(final String id, final JsonObject params, final long timeoutMs) {
        final String item = Json.str(params, "item", "").trim();
        if (item.isEmpty()) {
            return new MoveToTask.FailingTask(id, "smelt", params,
                    "缺少参数 item（要烧出什么，例如 iron_ingot、glass、cooked_beef）。");
        }
        return new SmeltTask(id, params, timeoutMs);
    }

    @Override
    protected void onStart(final Minecraft mc) {
        navigator = new Navigator(mc.level);
    }

    // ------------------------------------------------------------ 主循环

    @Override
    protected TaskResult onTick(final Minecraft mc) {
        final LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            fail("玩家或世界不存在");
        }
        stageTicks++;
        return switch (stage) {
            case FIND -> tickFind(mc, player);
            case APPROACH -> tickApproach(mc, player);
            case OPEN -> tickOpen(mc, player);
            case LOAD -> tickLoad(mc, player);
            case WAIT -> tickWait(mc, player);
            case TAKE -> tickTake(mc, player);
            case FINISH -> finish(player);
        };
    }

    // ---------------------------------------------------------------- FIND

    private TaskResult tickFind(final Minecraft mc, final LocalPlayer player) {
        if (startCount == 0) {
            startCount = CraftTask.countItem(player, wantSpec);
        }
        if (startCount > 0 && CraftTask.countItem(player, wantSpec) - startCount >= wantedCount) {
            moveTo(Stage.FINISH);
            return null;
        }
        if (inputSpec.isEmpty()) {
            inputSpec = findInput(player, mc);
            if (inputSpec.isEmpty()) {
                fail("背包里没有能烧成「" + wantSpec + "」的东西。"
                        + "先用 mc_inventory 看看到底有什么，或者 mc_recipes 查一查需要什么原料。");
            }
        }
        furnacePos = findFurnace(mc, player);
        if (furnacePos == null) {
            fail("附近 " + radius + " 格内没有熔炉。先：\n"
                    + "  1) mc_craft item=furnace（8 个圆石，需要工作台）\n"
                    + "  2) mc_place 把它放在脚边\n"
                    + "  3) 再调用一次 mc_smelt");
        }
        moveTo(Stage.APPROACH);
        return null;
    }

    private BlockPos findFurnace(final Minecraft mc, final LocalPlayer player) {
        final BlockPos origin = player.blockPosition();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -4; dy <= 4; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    final BlockPos pos = origin.offset(dx, dy, dz);
                    if (!mc.level.hasChunkAt(pos)) {
                        continue;
                    }
                    if (!mc.level.getBlockState(pos).is(Blocks.FURNACE)) {
                        continue;
                    }
                    final double d = origin.distSqr(pos);
                    if (d < bestDist) {
                        bestDist = d;
                        best = pos;
                    }
                }
            }
        }
        return best;
    }

    /**
     * 在背包里找一个「烧出来正好是目标」的东西。
     *
     * <p>查游戏自己的熔炼配方表，所以不写死物品名：模组加了自己的矿石也照样认。</p>
     */
    private String findInput(final LocalPlayer player, final Minecraft mc) {
        final List<Recipe<?>> recipes = new ArrayList<>(
                mc.level.getRecipeManager().getAllRecipesFor(RecipeType.SMELTING));
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            final ItemStack stack = player.getInventory().getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            for (final Recipe<?> recipe : recipes) {
                if (recipe.getIngredients().isEmpty() || !recipe.getIngredients().get(0).test(stack)) {
                    continue;
                }
                final ItemStack result = recipe.getResultItem(mc.level.registryAccess());
                if (result.isEmpty() || !CraftTask.matchesSpec(result, wantSpec)) {
                    continue;
                }
                return GameUtils.itemId(stack);
            }
        }
        return "";
    }

    // ------------------------------------------------------------ APPROACH

    private TaskResult tickApproach(final Minecraft mc, final LocalPlayer player) {
        if (player.containerMenu instanceof AbstractFurnaceMenu) {
            moveTo(Stage.LOAD);
            return null;
        }
        final Vec3 hitPoint = GameUtils.blockCenter(furnacePos);
        if (player.getEyePosition().distanceTo(hitPoint) > 4.0) {
            if (navigator.goal() == null || navigator.goal().distSqr(furnacePos) > 2.0) {
                navigator.setGoal(furnacePos, 2);
            }
            return switch (navigator.step(mc, 1.0)) {
                case ARRIVED -> {
                    navigator.releaseControl();
                    yield null;
                }
                case MOVING -> {
                    if (stageTicks > 20 * 30) {
                        navigator.releaseControl();
                        fail("走到熔炉 " + GameUtils.format(furnacePos) + " 旁边超时了："
                                + navigator.failureReason());
                    }
                    yield null;
                }
                case FAILED -> {
                    navigator.releaseControl();
                    fail("无法走到熔炉 " + GameUtils.format(furnacePos) + " 旁边："
                            + navigator.failureReason());
                    yield null;
                }
            };
        }
        moveTo(Stage.OPEN);
        return null;
    }

    // ---------------------------------------------------------------- OPEN

    private TaskResult tickOpen(final Minecraft mc, final LocalPlayer player) {
        if (player.containerMenu instanceof AbstractFurnaceMenu) {
            moveTo(Stage.LOAD);
            return null;
        }
        if (stageTicks > MENU_OPEN_TIMEOUT) {
            fail("右键了熔炉但界面没打开。可能被方块挡住了，或者服务器拒绝了这次交互。");
        }
        GameUtils.lookAt(player, GameUtils.blockCenter(furnacePos));
        final BlockHitResult hit = new BlockHitResult(
                GameUtils.blockCenter(furnacePos).add(0, 0.45, 0), Direction.UP, furnacePos, false);
        final InteractionResult result = mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
        if (result.shouldSwing()) {
            player.swing(InteractionHand.MAIN_HAND);
        }
        return null;
    }

    // ---------------------------------------------------------------- LOAD

    /** 往熔炉里补料。shift-click 交给原版菜单自己分配槽位。 */
    private TaskResult tickLoad(final Minecraft mc, final LocalPlayer player) {
        final AbstractContainerMenu menu = player.containerMenu;
        if (!(menu instanceof AbstractFurnaceMenu)) {
            fail("当前界面不是熔炉（" + menu.getClass().getSimpleName() + "）。");
            return null;
        }
        final AbstractFurnaceMenu furnace = (AbstractFurnaceMenu) menu;
        if (stageTicks < MENU_SYNC_TICKS) {
            return null; // 等服务端把菜单内容同步过来
        }

        // 输出槽有东西先取走，别堵着
        if (!furnace.getSlot(AbstractFurnaceMenu.RESULT_SLOT).getItem().isEmpty()) {
            moveTo(Stage.TAKE);
            return null;
        }

        // 燃料不够就补一个（只放一个：一个煤能烧 8 个，够用了）
        if (furnace.getSlot(AbstractFurnaceMenu.FUEL_SLOT).getItem().isEmpty()) {
            if (putIntoSlot(mc, player, furnace, AbstractFurnaceMenu.FUEL_SLOT, true,
                    slot -> isFuel(player.getInventory().getItem(slot)))) {
                fuelUsed = fuelUsed.isEmpty() ? describeFuel(player) : fuelUsed;
                return null;
            }
            if (produced > 0) {
                notes.add("燃料用完了，只烧出 " + produced + " 个");
                moveTo(Stage.FINISH);
            } else {
                fail("背包里没有能当燃料的东西（煤、木炭、木板、原木都行）。");
            }
            return null;
        }

        // 原料不够就补（整栈放进去，够烧就行）
        final int inFurnace = furnace.getSlot(AbstractFurnaceMenu.INGREDIENT_SLOT).getItem().getCount();
        if (inFurnace + produced < wantedCount) {
            if (putIntoSlot(mc, player, furnace, AbstractFurnaceMenu.INGREDIENT_SLOT, false,
                    slot -> CraftTask.matchesSpec(player.getInventory().getItem(slot), inputSpec))) {
                return null;
            }
            // 背包里没有更多原料了：能烧多少算多少
            notes.add("原料只够烧 " + (produced + inFurnace) + " 个（要 " + wantedCount + " 个）");
        }
        moveTo(Stage.WAIT);
        return null;
    }

    private String describeFuel(final LocalPlayer player) {
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            final ItemStack stack = player.getInventory().getItem(slot);
            if (isFuel(stack)) {
                return GameUtils.itemId(stack);
            }
        }
        return "";
    }

    /**
     * 从玩家背包精确放一些物品到熔炉的**指定**槽位。
     *
     * <p><b>不能用 shift-click（QUICK_MOVE）</b>：原版菜单自己决定去哪个槽，
     * 而它的规则是「先看这个物品能不能烧」—— 原木既能烧成木炭又是燃料，
     * 于是本该进燃料槽的木头被塞进了输入槽，熔炉开始烧木炭，铁锭一个没出
     * （真机自检里就是这么暴露的：背包里凭空多了一个木炭）。</p>
     *
     * <p>做法就是手动摆格子：拿起来 → 放到目标槽 → 光标有剩就放回原处。</p>
     *
     * @param onlyOne true 表示只放一个（燃料够用就行），false 表示放整栈
     */
    private boolean putIntoSlot(final Minecraft mc, final LocalPlayer player,
                                final AbstractContainerMenu menu, final int targetSlot,
                                final boolean onlyOne, final java.util.function.IntPredicate accept) {
        if (!menu.getSlot(targetSlot).getItem().isEmpty()) {
            return false; // 目标槽已经有东西了，别叠
        }
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            final ItemStack stack = player.getInventory().getItem(slot);
            if (stack.isEmpty() || !accept.test(slot)) {
                continue;
            }
            final int menuSlot = BasicTasks.findMenuSlot(menu, player, slot);
            if (menuSlot < 0) {
                continue;
            }
            // 拿起来 → 放到目标槽（右键只放一个）→ 有剩的放回原槽
            mc.gameMode.handleInventoryMouseClick(menu.containerId, menuSlot, 0, ClickType.PICKUP, player);
            mc.gameMode.handleInventoryMouseClick(menu.containerId, targetSlot,
                    onlyOne ? 1 : 0, ClickType.PICKUP, player);
            if (!menu.getCarried().isEmpty()) {
                mc.gameMode.handleInventoryMouseClick(menu.containerId, menuSlot, 0, ClickType.PICKUP, player);
            }
            smeltRounds++;
            return true;
        }
        return false;
    }

    /**
     * 反查「玩家背包第 N 格」在菜单里是第几个槽（实现已抽到 {@link BasicTasks#findMenuSlot}）。
     */

    /** 能不能当燃料 —— 交给游戏自己的燃烧时间表判断。 */
    private static boolean isFuel(final ItemStack stack) {
        return !stack.isEmpty() && ForgeHooks.getBurnTime(stack, RecipeType.SMELTING) > 0;
    }

    // ---------------------------------------------------------------- WAIT

    private TaskResult tickWait(final Minecraft mc, final LocalPlayer player) {
        final AbstractContainerMenu menu = player.containerMenu;
        if (!(menu instanceof AbstractFurnaceMenu)) {
            fail("熔炉界面被关掉了（可能被别的动作打断）。");
            return null;
        }
        final AbstractFurnaceMenu furnace = (AbstractFurnaceMenu) menu;
        if (!furnace.getSlot(AbstractFurnaceMenu.RESULT_SLOT).getItem().isEmpty()) {
            moveTo(Stage.TAKE);
            return null;
        }
        final boolean inputEmpty = furnace.getSlot(AbstractFurnaceMenu.INGREDIENT_SLOT).getItem().isEmpty();
        if (inputEmpty && !furnace.isLit()) {
            // 输入空了、也不烧了 → 这一炉结束
            if (produced >= wantedCount) {
                moveTo(Stage.FINISH);
            } else if (produced > 0) {
                notes.add("熔炉停了，只烧出 " + produced + " 个（可能燃料或原料不够）");
                moveTo(Stage.FINISH);
            } else {
                fail("熔炉没烧出东西就停了。检查一下燃料够不够、原料是不是放对了。");
            }
            return null;
        }
        if (stageTicks > PER_ITEM_TIMEOUT) {
            fail("等熔炉烧出 " + wantSpec + " 超时了（等了 " + stageTicks / 20 + " 秒）。"
                    + "熔炉里可能没有燃料，或者这个物品根本烧不出来。");
        }
        return null;
    }

    // ---------------------------------------------------------------- TAKE

    private TaskResult tickTake(final Minecraft mc, final LocalPlayer player) {
        final AbstractContainerMenu menu = player.containerMenu;
        if (!(menu instanceof AbstractFurnaceMenu)) {
            fail("熔炉界面被关掉了。");
            return null;
        }
        final AbstractFurnaceMenu furnace = (AbstractFurnaceMenu) menu;
        final ItemStack out = furnace.getSlot(AbstractFurnaceMenu.RESULT_SLOT).getItem();
        if (out.isEmpty()) {
            moveTo(Stage.LOAD);
            return null;
        }
        final int before = out.getCount();
        mc.gameMode.handleInventoryMouseClick(furnace.containerId,
                AbstractFurnaceMenu.RESULT_SLOT, 0, ClickType.QUICK_MOVE, player);
        produced += before;
        moveTo(produced >= wantedCount ? Stage.FINISH : Stage.LOAD);
        return null;
    }

    // -------------------------------------------------------------- FINISH

    private TaskResult finish(final LocalPlayer player) {
        if (!(player.containerMenu instanceof InventoryMenu)) {
            player.closeContainer();
        }
        final int after = CraftTask.countItem(player, wantSpec);
        final int received = after - startCount;

        final JsonObject out = new JsonObject();
        out.addProperty("item", wantSpec);
        out.addProperty("smelted", produced);
        out.addProperty("countBefore", startCount);
        out.addProperty("countAfter", after);
        out.addProperty("received", received);
        out.addProperty("input", inputSpec);
        if (!fuelUsed.isEmpty()) {
            out.addProperty("fuel", fuelUsed);
        }
        if (furnacePos != null) {
            out.add("furnace", StateCollector.blockPos(furnacePos));
        }
        final JsonArray noteArray = new JsonArray();
        for (final String note : notes) {
            noteArray.add(note);
        }
        out.add("notes", noteArray);

        if (received <= 0) {
            return TaskResult.fail("熔炉里没烧出 " + wantSpec + "。"
                    + "用的原料是「" + (inputSpec.isEmpty() ? "没找到" : inputSpec) + "」，"
                    + "燃料是「" + (fuelUsed.isEmpty() ? "没找到" : fuelUsed) + "」。"
                    + (notes.isEmpty() ? "" : String.join("；", notes)));
        }
        out.addProperty("content", "烧出 " + received + " 个 " + wantSpec
                + "（用 " + inputSpec + (fuelUsed.isEmpty() ? "" : " + " + fuelUsed) + "）");
        return TaskResult.success(out);
    }

    // ---------------------------------------------------------------- 小工具

    /** 切阶段时顺手把计时清零，否则超时判断会串到上一个阶段。 */
    private void moveTo(final Stage next) {
        stage = next;
        stageTicks = 0;
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

    @Override
    public double progress() {
        return wantedCount <= 0 ? -1 : Math.min(1.0, (double) produced / wantedCount);
    }

    @Override
    public String detail() {
        return "熔炼 " + wantSpec + "：" + stage.name().toLowerCase()
                + "（已出 " + produced + "/" + wantedCount + "）";
    }

    @Override
    protected double realProgress() {
        return produced;
    }

    @Override
    protected boolean watchdogApplies() {
        // 熔炼是**天生就慢**的活：原版烧一个东西就是 10 秒，而且这 10 秒里玩家站着不动、
        // 方块也不变。卡死检测（24 秒无位移无进展）会把正常的熔炼判成卡住 ——
        // 真机上就是这么在「已出 1/7」的时候被掐断的。
        // 这里跟 bucket 一样交给自己判：要么烧完，要么超时，都不会无限等。
        return false;
    }
}
