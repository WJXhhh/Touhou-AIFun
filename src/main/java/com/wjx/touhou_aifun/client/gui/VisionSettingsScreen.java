package com.wjx.touhou_aifun.client.gui;

import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.settings.AIChatSettingsHubScreen;
import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.settings.AIChatSettingsLLMSiteScreen;
import com.github.tartaricacid.touhoulittlemaid.client.gui.widget.ai.SideGroupWidget;
import com.github.tartaricacid.touhoulittlemaid.client.gui.widget.button.FlatColorButton;
import com.wjx.touhou_aifun.config.TouhouAIFunConfig;
import com.wjx.touhou_aifun.mixin.client.AIChatSettingsHubAccessor;
import com.wjx.touhou_aifun.network.AIFunNetwork;
import com.wjx.touhou_aifun.network.message.AIFunVisionSitesSyncMessage;
import com.wjx.touhou_aifun.vision.ClientVisionSitesSnapshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/** Vision behavior and a shared ModelRef picker. Model connections are edited only in LLM settings. */
public final class VisionSettingsScreen extends Screen {
    private final @Nullable Screen parent;
    private boolean providers, readOnly = true, requested;
    private boolean visionEnabled, scanEnabled;
    private String selected;
    private String status = "";
    private int offset;
    public VisionSettingsScreen(@Nullable Screen parent) {
        super(Component.translatable("gui.touhou_aifun.vision"));
        this.parent = parent;
        visionEnabled = TouhouAIFunConfig.VISION_ENABLED.get();
        scanEnabled = TouhouAIFunConfig.SHALLOW_SCAN_ENABLED.get();
        selected = TouhouAIFunConfig.VISION_SELECTED_SITE.get();
    }
    private int x() { return width / 2 - 200; }
    private int y() { return height / 2 - 115; }
    private int contentX() { return x() + 105; }
    private FlatColorButton button(int bx, int by, int bw, Component text, boolean active, Runnable click) {
        var button = new FlatColorButton(bx, by, bw, 20, text, ignored -> click.run());
        button.active = active;
        return addRenderableWidget(button);
    }
    private Component text(String key) { return Component.translatable("gui.touhou_aifun.vision." + key); }
    @Override protected void init() {
        rebuild();
        if (!requested) { requested = true; AIFunNetwork.requestVisionSitesFromServer(); }
    }
    private void rebuild() {
        clearWidgets();
        addRenderableOnly(new SideGroupWidget(x(), y() + 5, title));
        button(x(), y() + 25, 95, text("config"), true, () -> { providers = false; rebuild(); }).setSelect(!providers);
        button(x(), y() + 45, 95, text("models"), true, () -> { providers = true; rebuild(); }).setSelect(providers);
        if (!providers) {
            button(contentX(), y() + 23, 295, text(visionEnabled ? "on" : "off"), !readOnly,
                    () -> { visionEnabled = !visionEnabled; save(); });
            button(contentX(), y() + 65, 295, text(scanEnabled ? "on" : "off"), !readOnly,
                    () -> { scanEnabled = !scanEnabled; save(); });
        } else {
            var models = ClientVisionSitesSnapshot.all();
            offset = Math.min(offset, Math.max(0, models.size() - 6));
            for (int i = offset; i < Math.min(models.size(), offset + 6); i++) {
                var model = models.get(i);
                Component label = Component.literal(font.plainSubstrByWidth(model.displayName(), 280));
                var row = button(contentX(), y() + 21 + (i - offset) * 24, 295, label,
                        !readOnly && model.enabled() && model.imageSupported() && model.hasValidHttpEndpoint() && model.apiKeyPresent(),
                        () -> { selected = model.id(); save(); });
                row.setSelect(model.id().equals(selected));
                row.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable(model.enabled() && model.imageSupported()
                        ? "gui.touhou_aifun.model_images.detected" : "gui.touhou_aifun.vision.model_unavailable")));
            }
            button(contentX(), y() + 172, 295, text("manage_llm"), parent instanceof AIChatSettingsHubScreen, this::openLLM);
        }
        button(contentX() + 215, y() + 206, 80, net.minecraft.network.chat.CommonComponents.GUI_BACK, true, this::onClose);
    }
    private void save() {
        AIFunNetwork.sendVisionSettingsToServer(visionEnabled, scanEnabled, selected);
        rebuild();
    }
    private void openLLM() {
        if (minecraft != null && parent instanceof AIChatSettingsHubScreen hub) {
            var state = ((AIChatSettingsHubAccessor) hub).touhouAIFun$getState();
            minecraft.setScreen(new AIChatSettingsLLMSiteScreen(this, state, readOnly));
        }
    }
    public void refreshFromServer(AIFunVisionSitesSyncMessage value) {
        visionEnabled = value.visionEnabled(); scanEnabled = value.shallowScanEnabled(); selected = value.selectedSite();
        readOnly = value.insufficientPermissions(); status = value.operationStatus(); rebuild();
    }
    public void onSitesSynced(com.github.tartaricacid.touhoulittlemaid.network.message.ai.SyncAISitesMessage value) {
        if (parent instanceof AIChatSettingsHubScreen hub) {
            var state = ((AIChatSettingsHubAccessor) hub).touhouAIFun$getState();
            state.llmSites.clear(); state.llmSites.putAll(value.llmSites());
            state.ttsSites.clear(); state.ttsSites.putAll(value.ttsSites());
        }
        AIFunNetwork.requestVisionSitesFromServer();
    }
    @Override public boolean mouseScrolled(double mx, double my, double delta) {
        if (providers && mx >= contentX()) {
            offset = Math.max(0, Math.min(Math.max(0, ClientVisionSitesSnapshot.all().size() - 6), offset + (delta < 0 ? 1 : -1)));
            rebuild(); return true;
        }
        return super.mouseScrolled(mx, my, delta);
    }
    @Override public void render(GuiGraphics graphics, int mx, int my, float partial) {
        renderBackground(graphics);
        int cx = contentX();
        if (!providers) {
            graphics.drawString(font, text("image_enabled"), cx + 2, y() + 10, 0xAAAAAA, false);
            graphics.drawString(font, text("scan_enabled"), cx + 2, y() + 52, 0xAAAAAA, false);
            graphics.drawWordWrap(font, text("shared_help"), cx + 2, y() + 97, 291, 0xAAAAAA);
            graphics.drawWordWrap(font, text("cache_help"), cx + 2, y() + 142, 291, 0xAAAAAA);
        } else {
            graphics.drawString(font, text("independent_model"), cx + 2, y() + 7, 0xAAAAAA, false);
            var models = ClientVisionSitesSnapshot.all();
            if (!selected.isBlank() && models.stream().noneMatch(model -> model.id().equals(selected) && model.enabled() && model.imageSupported()
                    && model.hasValidHttpEndpoint() && model.apiKeyPresent())) {
                graphics.drawString(font, text("selection_invalid"), cx + 2, y() + 159, 0xFF7777, false);
            }
        }
        if (!status.isBlank()) graphics.drawString(font, text("status." + status), cx + 2, y() + 212, 0xAAAAAA, false);
        super.render(graphics, mx, my, partial);
    }
    @Override public void removed() { requested = false; super.removed(); }
    @Override public void onClose() { if (minecraft != null) minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
