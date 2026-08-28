package com.wjx.touhou_aifun.client.gui;

import com.github.tartaricacid.touhoulittlemaid.client.gui.widget.button.MaidConfigButton;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.List;

/** Fixed-height vertical stack for maid configuration rows, with clipping and a draggable scrollbar. */
public final class MaidConfigStackPanel extends AbstractWidget {
    public static final int ROW_HEIGHT = 13;
    private static final int SCROLLBAR_X_OFFSET = 166;
    private static final int SCROLLBAR_WIDTH = 4;
    private static final int MIN_THUMB_HEIGHT = 18;

    private final List<MaidConfigButton> rows;
    private double scrollAmount;
    private boolean draggingScrollbar;
    private double scrollbarGrabOffset;

    public MaidConfigStackPanel(int x, int y, int width, int height, List<MaidConfigButton> rows) {
        super(x, y, width, height, Component.translatable("gui.touhou_aifun.maid_config.options"));
        this.rows = List.copyOf(rows);
        updateRowPositions();
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        updateRowPositions();

        // A subtle frame makes the clipping boundary visible without covering TLM's own texture.
        int frameColor = 0x80404040;
        graphics.fill(getX() - 1, getY() - 1, getX() + width, getY(), frameColor);
        graphics.fill(getX() - 1, getY() + height, getX() + width, getY() + height + 1, frameColor);
        graphics.fill(getX() - 1, getY(), getX(), getY() + height, frameColor);
        graphics.fill(getX() + width - 1, getY(), getX() + width, getY() + height, frameColor);

        graphics.enableScissor(getX(), getY(), getX() + width, getY() + height);
        try {
            for (MaidConfigButton row : rows) {
                if (row.getY() + row.getHeight() > getY() && row.getY() < getY() + height) {
                    row.render(graphics, mouseX, mouseY, partialTick);
                }
            }
        } finally {
            graphics.disableScissor();
        }

        renderScrollbar(graphics, mouseX, mouseY);
    }

    private void renderScrollbar(GuiGraphics graphics, int mouseX, int mouseY) {
        if (maxScroll() <= 0) {
            return;
        }
        int trackX = scrollbarX();
        graphics.fill(trackX, getY(), trackX + SCROLLBAR_WIDTH, getY() + height, 0x60303030);
        int thumbY = thumbY();
        int thumbHeight = thumbHeight();
        boolean hovered = mouseX >= trackX && mouseX < trackX + SCROLLBAR_WIDTH
                && mouseY >= thumbY && mouseY < thumbY + thumbHeight;
        int thumbColor = draggingScrollbar || hovered ? 0xFFE0E0E0 : 0xFF9A9A9A;
        graphics.fill(trackX, thumbY, trackX + SCROLLBAR_WIDTH, thumbY + thumbHeight, thumbColor);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!active || !visible || button != 0 || !isMouseOver(mouseX, mouseY)) {
            return false;
        }
        updateRowPositions();
        if (maxScroll() > 0 && mouseX >= scrollbarX()) {
            int thumbY = thumbY();
            int thumbHeight = thumbHeight();
            if (mouseY >= thumbY && mouseY < thumbY + thumbHeight) {
                draggingScrollbar = true;
                scrollbarGrabOffset = mouseY - thumbY;
            } else {
                setScrollFromThumb(mouseY - thumbHeight / 2.0);
            }
            return true;
        }
        for (MaidConfigButton row : rows) {
            if (row.getY() + row.getHeight() > getY() && row.getY() < getY() + height
                    && row.mouseClicked(mouseX, mouseY, button)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        boolean wasDragging = draggingScrollbar;
        draggingScrollbar = false;
        boolean handled = false;
        for (MaidConfigButton row : rows) {
            handled |= row.mouseReleased(mouseX, mouseY, button);
        }
        return wasDragging || handled;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggingScrollbar && button == 0) {
            setScrollFromThumb(mouseY - scrollbarGrabOffset);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (!isMouseOver(mouseX, mouseY) || maxScroll() <= 0) {
            return false;
        }
        setScrollAmount(scrollAmount - Math.signum(delta) * ROW_HEIGHT);
        return true;
    }

    private void setScrollFromThumb(double thumbTop) {
        int travel = height - thumbHeight();
        if (travel <= 0) {
            setScrollAmount(0);
            return;
        }
        double ratio = (thumbTop - getY()) / travel;
        setScrollAmount(ratio * maxScroll());
    }

    private void setScrollAmount(double amount) {
        scrollAmount = Mth.clamp(amount, 0, maxScroll());
        updateRowPositions();
    }

    private void updateRowPositions() {
        int y = getY() - (int) Math.round(scrollAmount);
        for (int index = 0; index < rows.size(); index++) {
            MaidConfigButton row = rows.get(index);
            row.setX(getX());
            row.setY(y + index * ROW_HEIGHT);
        }
    }

    private int contentHeight() {
        return rows.size() * ROW_HEIGHT;
    }

    private int maxScroll() {
        return Math.max(0, contentHeight() - height);
    }

    private int scrollbarX() {
        return getX() + Math.min(SCROLLBAR_X_OFFSET, width - SCROLLBAR_WIDTH);
    }

    private int thumbHeight() {
        return Math.max(MIN_THUMB_HEIGHT, height * height / contentHeight());
    }

    private int thumbY() {
        int travel = height - thumbHeight();
        return getY() + (maxScroll() == 0 ? 0 : (int) Math.round(scrollAmount * travel / maxScroll()));
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, getMessage());
    }
}
