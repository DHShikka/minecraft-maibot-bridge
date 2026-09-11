package com.mcai.bridge;

import com.mcai.bridge.action.ActionExecutor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;

/**
 * 左上角的桥接状态 HUD。
 *
 * <p>显示内容：连接状态、当前动作与进度、队列长度、以及最近一条错误。
 * 有了它，调试「AI 到底有没有连上、现在在干什么」非常直观。
 * 按 F1 隐藏 HUD 时也会一起隐藏。</p>
 */
@Mod.EventBusSubscriber(modid = McAiBridge.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class StatusOverlay implements IGuiOverlay {

    private static final int COLOR_OK = 0xFF55FF55;
    private static final int COLOR_WARN = 0xFFFFAA00;
    private static final int COLOR_BAD = 0xFFFF5555;
    private static final int COLOR_TEXT = 0xFFE0E0E0;
    private static final int COLOR_DIM = 0xFFA0A0A0;
    private static final int BACKGROUND = 0x80000000;

    private static String lastError = "";
    private static long lastErrorAt;

    private StatusOverlay() {
    }

    @SubscribeEvent
    public static void registerOverlays(final RegisterGuiOverlaysEvent event) {
        event.registerAboveAll("mcai_bridge_status", new StatusOverlay());
    }

    /** 由桥接层调用，把最近一次失败显示在 HUD 上。 */
    public static void reportError(final String message) {
        lastError = message == null ? "" : message;
        lastErrorAt = System.currentTimeMillis();
    }

    @Override
    public void render(final ForgeGui gui, final GuiGraphics graphics, final float partialTick,
                       final int screenWidth, final int screenHeight) {
        if (!BridgeConfig.enabled || !BridgeConfig.showHud) {
            return;
        }
        final Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.options == null || mc.options.hideGui || mc.player == null) {
            return;
        }

        final ClientBridge bridge = ClientBridge.get();
        final List<String[]> lines = new ArrayList<>();
        lines.add(new String[]{"MaiBot", bridge.statusText()});

        final ActionExecutor executor = ActionExecutor.get();
        String taskText = executor.hudText();
        if (taskText.length() > 42) {
            taskText = taskText.substring(0, 42) + "…";
        }
        lines.add(new String[]{"任务", taskText});

        if (lastError != null && !lastError.isEmpty() && System.currentTimeMillis() - lastErrorAt < 8000L) {
            String error = lastError.length() > 46 ? lastError.substring(0, 46) + "…" : lastError;
            lines.add(new String[]{"最近错误", error});
        }

        final int lineHeight = 10;
        final int padding = 4;
        int maxWidth = 0;
        for (final String[] line : lines) {
            maxWidth = Math.max(maxWidth, mc.font.width(line[0] + ": " + line[1]));
        }

        final int x = 4;
        final int y = 4;
        final int boxWidth = maxWidth + padding * 2;
        final int boxHeight = lines.size() * lineHeight + padding * 2;

        graphics.fill(x, y, x + boxWidth, y + boxHeight, BACKGROUND);

        int textY = y + padding;
        for (final String[] line : lines) {
            final int labelColor = COLOR_DIM;
            final int valueColor = colorFor(line[0], line[1]);
            final int labelWidth = mc.font.width(line[0] + ": ");
            graphics.drawString(mc.font, line[0] + ": ", x + padding, textY, labelColor, true);
            graphics.drawString(mc.font, line[1], x + padding + labelWidth, textY, valueColor, true);
            textY += lineHeight;
        }
    }

    private static int colorFor(final String label, final String value) {
        if (!"MaiBot".equals(label)) {
            return COLOR_TEXT;
        }
        if (value.contains("已连接")) {
            return COLOR_OK;
        }
        if (value.contains("禁用")) {
            return COLOR_DIM;
        }
        return COLOR_WARN;
    }

    /** 未使用但保留：给未来的「连接失败详情」提示用。 */
    static int errorColor() {
        return COLOR_BAD;
    }
}
