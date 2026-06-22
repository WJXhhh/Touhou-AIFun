package team.cfpa.touhoustepfun.client.gui;

import com.github.tartaricacid.touhoulittlemaid.ai.service.stt.STTSite;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Consumer;

public final class STTSiteDropdownWidget extends AbstractWidget {
    public static final int ROW_HEIGHT = 20;

    private final List<STTSite> sites;
    private final Consumer<String> selectionListener;
    private final Consumer<Boolean> openListener;
    private String selectedSiteId;
    private boolean open;

    public STTSiteDropdownWidget(int x, int y, int width, List<STTSite> sites, String selectedSiteId,
                                 Consumer<String> selectionListener, Consumer<Boolean> openListener) {
        super(x, y, width, ROW_HEIGHT, Component.empty());
        this.sites = List.copyOf(sites);
        this.selectedSiteId = selectedSiteId;
        this.selectionListener = selectionListener;
        this.openListener = openListener;
        this.active = !sites.isEmpty();
        this.updateMessage();
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        int row = (int) ((mouseY - this.getY()) / ROW_HEIGHT);
        if (!this.open || row == 0) {
            this.setOpen(!this.open && !this.sites.isEmpty());
            return;
        }
        int siteIndex = row - 1;
        if (siteIndex >= 0 && siteIndex < this.sites.size()) {
            this.selectedSiteId = this.sites.get(siteIndex).id();
            this.updateMessage();
            this.selectionListener.accept(this.selectedSiteId);
        }
        this.setOpen(false);
    }

    private void setOpen(boolean open) {
        this.open = open;
        this.height = ROW_HEIGHT * (open ? this.sites.size() + 1 : 1);
        this.openListener.accept(open);
    }

    private void updateMessage() {
        this.setMessage(this.sites.stream()
                .filter(site -> site.id().equals(this.selectedSiteId))
                .findFirst()
                .map(STTSiteDropdownWidget::siteName)
                .orElseGet(() -> Component.translatable("gui.touhou_stepfun.stt_site.empty")));
    }

    private static Component siteName(STTSite site) {
        return I18n.exists(site.getNameKey())
                ? Component.translatable(site.getNameKey()) : Component.literal(site.id());
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int x = this.getX();
        int y = this.getY();
        int color = this.isHoveredOrFocused() ? 0xFF555555 : 0xFF434242;
        graphics.fill(x, y, x + this.width, y + ROW_HEIGHT, color);
        graphics.drawCenteredString(Minecraft.getInstance().font, this.getMessage(),
                x + this.width / 2, y + 6, this.active ? 0xFFF3EFE0 : 0xFF777777);
        graphics.drawString(Minecraft.getInstance().font, this.open ? "▲" : "▼",
                x + this.width - 13, y + 6, this.active ? 0xFFF3EFE0 : 0xFF777777, false);

        if (!this.open) {
            return;
        }
        for (int i = 0; i < this.sites.size(); i++) {
            STTSite site = this.sites.get(i);
            int rowY = y + (i + 1) * ROW_HEIGHT;
            boolean hovered = x <= mouseX && mouseX <= x + this.width
                    && rowY <= mouseY && mouseY < rowY + ROW_HEIGHT;
            boolean selected = site.id().equals(this.selectedSiteId);
            graphics.fill(x, rowY, x + this.width, rowY + ROW_HEIGHT,
                    selected ? 0xFF1E90FF : hovered ? 0xFF555555 : 0xFF292929);
            graphics.drawString(Minecraft.getInstance().font, siteName(site), x + 7, rowY + 6,
                    0xFFF3EFE0, false);
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, this.createNarrationMessage());
    }
}
