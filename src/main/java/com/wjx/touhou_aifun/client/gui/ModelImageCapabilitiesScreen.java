package com.wjx.touhou_aifun.client.gui;

import com.github.tartaricacid.touhoulittlemaid.client.gui.widget.button.FlatColorButton;
import com.wjx.touhou_aifun.network.AIFunNetwork;
import com.wjx.touhou_aifun.network.message.AIFunVisionSitesSyncMessage;
import com.wjx.touhou_aifun.vision.ClientVisionSitesSnapshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** LLM settings own model capabilities. Uses the public shared catalog, never a separate model list. */
public final class ModelImageCapabilitiesScreen extends Screen {
    private final Screen parent;
    private boolean readOnly = true;
    private int offset;
    private String status = "";
    public ModelImageCapabilitiesScreen(Screen parent) { super(Component.translatable("gui.touhou_aifun.model_images.title")); this.parent = parent; }
    private int x() { return width / 2 - 200; }
    private int y() { return height / 2 - 120; }
    @Override protected void init() {
        rebuild();
        AIFunNetwork.requestVisionSitesFromServer();
    }
    public void refreshFromServer(AIFunVisionSitesSyncMessage message) {
        readOnly = message.insufficientPermissions();
        status = message.operationStatus();
        rebuild();
    }
    public void onSitesSynced(com.github.tartaricacid.touhoulittlemaid.network.message.ai.SyncAISitesMessage message) {
        if (parent instanceof com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.settings.AIChatSettingsHubScreen hub) {
            var state = ((com.wjx.touhou_aifun.mixin.client.AIChatSettingsHubAccessor) hub).touhouAIFun$getState();
            state.llmSites.clear(); state.llmSites.putAll(message.llmSites());
            state.ttsSites.clear(); state.ttsSites.putAll(message.ttsSites());
        }
        AIFunNetwork.requestVisionSitesFromServer();
    }
    private void rebuild() {
        // Avoid init's network request -> response -> init loop.
        var models = ClientVisionSitesSnapshot.all();
        clearWidgets();
        offset = Math.min(offset, Math.max(0, models.size() - 7));
        for (int i = offset; i < Math.min(models.size(), offset + 7); i++) {
            var model = models.get(i);
            var button = new FlatColorButton(x() + 303, y() + 45 + (i - offset) * 23, 87, 20,
                    Component.translatable("gui.touhou_aifun.model_images." + model.capability().name().toLowerCase(java.util.Locale.ROOT)),
                    clicked -> AIFunNetwork.sendModelCapability(model.id(), model.capability().next()));
            button.active = !readOnly;
            button.setTooltip(Tooltip.create(Component.translatable(model.imageSupported()
                    ? "gui.touhou_aifun.model_images.detected" : "gui.touhou_aifun.model_images.unknown")));
            addRenderableWidget(button);
        }
        addRenderableWidget(new FlatColorButton(x() + 310, y() + 212, 80, 20, net.minecraft.network.chat.CommonComponents.GUI_BACK, clicked -> onClose()));
    }
    @Override public boolean mouseScrolled(double mx, double my, double delta) {
        offset = Math.max(0, Math.min(Math.max(0, ClientVisionSitesSnapshot.all().size() - 7), offset + (delta < 0 ? 1 : -1)));
        rebuild(); return true;
    }
    @Override public void render(GuiGraphics graphics, int mx, int my, float partial) {
        renderBackground(graphics);
        graphics.drawCenteredString(font, title, width / 2, y() + 7, 0xFFFFFF);
        graphics.drawString(font, Component.translatable("gui.touhou_aifun.model_images.help"), x() + 10, y() + 27, 0xAAAAAA, false);
        var models = ClientVisionSitesSnapshot.all();
        for (int i = offset; i < Math.min(models.size(), offset + 7); i++) {
            graphics.drawString(font, font.plainSubstrByWidth(models.get(i).displayName(), 285), x() + 10,
                    y() + 51 + (i - offset) * 23, models.get(i).enabled() ? 0xFFFFFF : 0x888888, false);
        }
        if (!status.isBlank()) graphics.drawString(font, Component.translatable("gui.touhou_aifun.vision.status." + status), x() + 10, y() + 219, 0xAAAAAA, false);
        super.render(graphics, mx, my, partial);
    }
    @Override public void onClose() { if (minecraft != null) minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
