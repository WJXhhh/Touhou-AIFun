package com.wjx.touhou_aifun.client.gui;

import com.github.tartaricacid.touhoulittlemaid.client.gui.widget.ai.SideGroupWidget;
import com.github.tartaricacid.touhoulittlemaid.client.gui.widget.button.FlatColorButton;
import com.wjx.touhou_aifun.chat.agent.AgentDecisionModels;
import com.wjx.touhou_aifun.network.AIFunNetwork;
import com.wjx.touhou_aifun.network.message.AIFunDecisionSettingsSyncMessage;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.List;

/** Reuses the server LLM catalog; changing this selection never changes a maid's chat configuration. */
public final class AgentDecisionSettingsScreen extends Screen {
    private final Screen parent;
    private List<AgentDecisionModels.Option> models = List.of();
    private String selected = "", status = "";
    private boolean readOnly = true, requested, pending = true;
    private int offset;
    public AgentDecisionSettingsScreen(Screen parent) {
        super(Component.translatable("gui.touhou_aifun.decision")); this.parent = parent;
    }
    private int x() { return width / 2 - 200; }
    private int y() { return height / 2 - 115; }
    private Component text(String key) { return Component.translatable("gui.touhou_aifun.decision." + key); }
    @Override protected void init() {
        rebuild();
        if (!requested && minecraft != null && minecraft.getConnection() != null) { requested = true; AIFunNetwork.requestDecisionSettings(); }
    }
    private FlatColorButton button(int bx, int by, int bw, Component label, boolean enabled, Runnable click) {
        var row = new FlatColorButton(bx, by, bw, 20, label, ignored -> click.run());
        row.active = enabled; return addRenderableWidget(row);
    }
    private void rebuild() {
        clearWidgets(); int cx = x() + 105;
        addRenderableOnly(new SideGroupWidget(x(), y() + 5, title));
        button(cx, y() + 43, 295, text("follow_chat"), !readOnly && !pending, () -> save("")).setSelect(selected.isBlank());
        offset = Math.min(offset, Math.max(0, models.size() - 5));
        for (int i = offset; i < Math.min(models.size(), offset + 5); i++) {
            var model = models.get(i);
            var row = button(cx, y() + 67 + (i - offset) * 24, 295,
                    Component.literal(font.plainSubstrByWidth(model.label(), 280)), !readOnly && !pending && model.usable(),
                    () -> save(model.id()));
            row.setSelect(model.id().equals(selected));
            row.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(model.label())
                    .append("\n").append(text("model_help"))));
        }
        button(cx + 215, y() + 206, 80, net.minecraft.network.chat.CommonComponents.GUI_BACK, true, this::onClose);
        button(cx,y()+206,205,Component.translatable("gui.touhou_aifun.native_decision"),true,
                ()->minecraft.setScreen(new DecisionServiceSettingsScreen(this)));
    }
    private void save(String selection) { pending = true; AIFunNetwork.saveDecisionSettings(selection); rebuild(); }
    public void refresh(AIFunDecisionSettingsSyncMessage value) {
        selected = value.selection(); models = value.models(); readOnly = value.readOnly(); status = value.status(); pending = false; rebuild();
    }
    @Override public boolean mouseScrolled(double mx, double my, double delta) {
        if (mx >= x() + 105 && my >= y() + 67 && my <= y() + 187) {
            offset = Math.max(0, Math.min(Math.max(0, models.size() - 5), offset + (delta < 0 ? 1 : -1)));
            rebuild(); return true;
        }
        return super.mouseScrolled(mx, my, delta);
    }
    @Override public void render(GuiGraphics graphics, int mx, int my, float partial) {
        renderBackground(graphics); int cx = x() + 107;
        graphics.drawWordWrap(font, text("help"), cx, y() + 9, 291, 0xAAAAAA);
        boolean invalid = !selected.isBlank() && models.stream().noneMatch(row -> row.id().equals(selected) && row.usable());
        if (invalid && !pending) graphics.drawString(font, text("model_unavailable"), cx, y() + 190, 0xFF7777, false);
        if (!status.isBlank() && !invalid)
            graphics.drawString(font, text(status), cx, y() + 190, 0xAAAAAA, false);
        super.render(graphics, mx, my, partial);
    }
    @Override public void onClose() { if (minecraft != null) minecraft.setScreen(parent); }
    @Override public void removed() { requested = false; super.removed(); }
    @Override public boolean isPauseScreen() { return false; }
}
