package com.wjx.touhou_aifun.client.voice;

import com.wjx.touhou_aifun.config.TouhouAIFunConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Inline free-chat gate meter: live RMS, 5-second history, draggable threshold, the higher TTS
 * threshold and a trigger indicator. It is the only threshold UI; it deliberately does not open a
 * separate screen.
 */
@OnlyIn(Dist.CLIENT)
public final class FreeChatGateMeterWidget extends AbstractWidget {
    private static final int HISTORY_SIZE = 100;
    private static final long SAMPLE_MILLIS = 50L;

    private final int[] history = new int[HISTORY_SIZE];
    private int historyIndex;
    private long lastSampleAt;
    private int draftThreshold;
    private boolean dragging;

    public FreeChatGateMeterWidget(int x, int y, int width, int height) {
        super(x, y, width, height, Component.translatable("gui.touhou_aifun.free_chat.meter.title"));
        int configured = 700;
        try {
            configured = TouhouAIFunConfig.FREE_CHAT_THRESHOLD.get();
        } catch (RuntimeException ignored) {
        }
        this.draftThreshold = GateMeterMath.clampThreshold(configured);
        this.setTooltip(Tooltip.create(Component.translatable("gui.touhou_aifun.free_chat.meter.tooltip")));
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();
        int level = FreeChatController.latestMicLevel();
        boolean ttsActive = FreeChatController.isTtsActive();
        int activeThreshold = GateMeterMath.activeThreshold(draftThreshold, ttsActive);
        sample(level);

        graphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), 0xC0101010);
        graphics.fill(getX() + 1, getY() + 1, getX() + getWidth() - 1, getY() + getHeight() - 1,
                0xFF202020);

        Component thresholdText = Component.translatable(
                "gui.touhou_aifun.free_chat.meter.threshold", draftThreshold);
        graphics.drawString(minecraft.font, thresholdText, getX() + 3, getY() + 1,
                0xFFE0E0E0, false);
        String levelText = Component.translatable("gui.touhou_aifun.free_chat.meter.current", level).getString();
        if (level >= activeThreshold) {
            levelText = levelText + " · " + Component.translatable(
                    "gui.touhou_aifun.free_chat.meter.triggered").getString();
        }
        drawRight(graphics, Component.literal(levelText), getX() + getWidth() - 3, getY() + 1,
                level >= activeThreshold ? 0xFFFF6B6B : 0xFFB0B0B0);

        int barY = getY() + 10;
        int barHeight = 9;
        graphics.fill(getX() + 2, barY, getX() + getWidth() - 2, barY + barHeight, 0xFF303030);
        int usableWidth = Math.max(1, getWidth() - 4);
        for (int i = 0; i < HISTORY_SIZE; i++) {
            int value = history[(historyIndex + i) % HISTORY_SIZE];
            if (value <= 0) {
                continue;
            }
            int barX = getX() + 2 + i * usableWidth / HISTORY_SIZE;
            int nextX = getX() + 2 + (i + 1) * usableWidth / HISTORY_SIZE;
            int height = Math.max(1, (int) Math.round(
                    GateMeterMath.normalize(value) * (barHeight - 1)));
            int color = value >= activeThreshold ? 0xFFE53935
                    : value * 2 >= activeThreshold ? 0xFFFFB300 : 0xFF4CAF50;
            graphics.fill(barX, barY + barHeight - height, Math.max(barX + 1, nextX),
                    barY + barHeight, color);
        }

        int thresholdX = thresholdX(draftThreshold);
        graphics.fill(thresholdX, barY - 2, thresholdX + 1, barY + barHeight + 2, 0xFFFFFFFF);
        graphics.fill(thresholdX - 2, barY - 3, thresholdX + 3, barY - 1, 0xFFFFFFFF);
        if (ttsActive && activeThreshold != draftThreshold) {
            int ttsX = thresholdX(activeThreshold);
            graphics.fill(ttsX, barY - 2, ttsX + 1, barY + barHeight + 2, 0xFFFFB300);
        }
        int levelX = getX() + 2 + (int) Math.round(GateMeterMath.normalize(level) * usableWidth);
        graphics.fill(Math.min(levelX, getX() + getWidth() - 3), barY - 1,
                Math.min(levelX + 2, getX() + getWidth() - 2), barY + barHeight + 1, 0xFF80DEEA);

        String status = switch (FreeChatController.micStatus()) {
            case "listening" -> "gui.touhou_aifun.free_chat.meter.listening";
            case "mic_missing" -> "gui.touhou_aifun.free_chat.meter.mic_missing";
            case "mic_busy" -> "gui.touhou_aifun.free_chat.meter.mic_busy";
            case "mic_error" -> "gui.touhou_aifun.free_chat.meter.mic_error";
            default -> "gui.touhou_aifun.free_chat.meter.inactive";
        };
        graphics.drawString(minecraft.font, Component.translatable(status), getX() + 3,
                getY() + 20, 0xFF909090, false);
        Component right = ttsActive
                ? Component.translatable("gui.touhou_aifun.free_chat.meter.tts_threshold", activeThreshold)
                : Component.translatable("gui.touhou_aifun.free_chat.meter.history");
        drawRight(graphics, right, getX() + getWidth() - 3, getY() + 20, 0xFF909090);
    }

    private void sample(int level) {
        long now = System.currentTimeMillis();
        if (now - lastSampleAt < SAMPLE_MILLIS) {
            return;
        }
        lastSampleAt = now;
        history[historyIndex] = level;
        historyIndex = (historyIndex + 1) % HISTORY_SIZE;
    }

    private int thresholdX(int threshold) {
        int usableWidth = Math.max(1, getWidth() - 4);
        return getX() + 2 + (int) Math.round(
                GateMeterMath.normalize(threshold) * usableWidth);
    }

    private void drawRight(GuiGraphics graphics, Component text, int right, int y, int color) {
        Minecraft minecraft = Minecraft.getInstance();
        graphics.drawString(minecraft.font, text, right - minecraft.font.width(text), y, color, false);
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        this.dragging = true;
        updateThreshold(mouseX);
    }

    @Override
    protected void onDrag(double mouseX, double mouseY, double dragX, double dragY) {
        if (this.dragging) {
            updateThreshold(mouseX);
        }
    }

    @Override
    public void onRelease(double mouseX, double mouseY) {
        if (!this.dragging) {
            return;
        }
        this.dragging = false;
        updateThreshold(mouseX);
        try {
            TouhouAIFunConfig.setFreeChatThreshold(draftThreshold);
        } catch (RuntimeException ignored) {
        }
    }

    private void updateThreshold(double mouseX) {
        this.draftThreshold = GateMeterMath.thresholdFromMouseX(
                mouseX, getX() + 2, Math.max(1, getWidth() - 4));
    }

    public int threshold() {
        return draftThreshold;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE,
                Component.translatable("gui.touhou_aifun.free_chat.meter.narration", draftThreshold));
    }
}
