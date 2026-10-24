package com.wjx.touhou_aifun.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

/** Scrollbar for the translated history widgets; scroll offsets run from zero to negative. */
public final class HistoryScrollbar extends AbstractWidget {
    private final int contentHeight;
    private final DoubleSupplier scroll;
    private final DoubleConsumer setScroll;
    private double grabOffset;

    public HistoryScrollbar(int x, int y, int height, int contentHeight,
                            DoubleSupplier scroll, DoubleConsumer setScroll) {
        super(x, y, 6, height, Component.translatable(
                "gui.touhou_little_maid.button.maid_ai_chat_config.history_chat.title"));
        this.contentHeight = contentHeight;
        this.scroll = scroll;
        this.setScroll = setScroll;
        this.active = contentHeight > height;
    }

    private int thumbHeight() {
        return active ? Mth.clamp(height * height / contentHeight, Math.min(20, height), height) : height;
    }

    private int thumbTop() {
        if (!active) {
            return getY();
        }
        double progress = Mth.clamp(-scroll.getAsDouble() / (contentHeight - height), 0, 1);
        return getY() + (int) Math.round(progress * (height - thumbHeight()));
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(getX(), getY(), getX() + width, getY() + height, 0xAA111111);
        int top = thumbTop();
        int color = !active ? 0xFF444444 : isHoveredOrFocused() ? 0xFFCCCCCC : 0xFF888888;
        graphics.fill(getX() + 1, top, getX() + width - 1, top + thumbHeight(), color);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0 || !active || !isMouseOver(mouseX, mouseY)) {
            return false;
        }
        int top = thumbTop();
        grabOffset = mouseY >= top && mouseY < top + thumbHeight() ? mouseY - top : thumbHeight() / 2d;
        scrollTo(mouseY);
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        // Screen routes drags to the focused widget until release, including outside the track.
        if (button != 0 || !active) {
            return false;
        }
        scrollTo(mouseY);
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        return button == 0 && active;
    }

    private void scrollTo(double mouseY) {
        int travel = height - thumbHeight();
        if (travel > 0) {
            double progress = Mth.clamp((mouseY - getY() - grabOffset) / travel, 0, 1);
            setScroll.accept(-progress * (contentHeight - height));
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
