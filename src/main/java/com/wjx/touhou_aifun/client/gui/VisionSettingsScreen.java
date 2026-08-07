package com.wjx.touhou_aifun.client.gui;

import com.google.gson.JsonObject;
import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.settings.AIChatSettingsHubScreen;
import com.wjx.touhou_aifun.config.TouhouAIFunConfig;
import com.wjx.touhou_aifun.network.AIFunNetwork;
import com.wjx.touhou_aifun.network.message.AIFunVisionSiteSaveMessage;
import com.wjx.touhou_aifun.network.message.AIFunVisionSitesSyncMessage;
import com.wjx.touhou_aifun.vision.AvailableVisionSites;
import com.wjx.touhou_aifun.vision.VisionSite;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Small addon-owned visual settings page; it intentionally does not extend the base fixed enum hub. */
public final class VisionSettingsScreen extends Screen {
    private final @Nullable Screen parent;
    private boolean insufficientPermissions;
    private boolean visionEnabled;
    private boolean shallowScanEnabled;
    private String selectedId;
    private EditBox endpoint;
    private EditBox model;
    private EditBox apiKey;
    private VisionSite selected;
    private boolean requestedInitialSync;

    public VisionSettingsScreen(@Nullable Screen parent) {
        super(Component.literal("视觉理解"));
        this.parent = parent;
        this.visionEnabled = TouhouAIFunConfig.VISION_ENABLED.get();
        this.shallowScanEnabled = TouhouAIFunConfig.SHALLOW_SCAN_ENABLED.get();
        this.selectedId = TouhouAIFunConfig.VISION_SELECTED_SITE.get();
    }

    @Override
    protected void init() {
        clearWidgets();
        int left = (width - 430) / 2;
        int top = (height - 250) / 2;
        List<VisionSite> sites = AvailableVisionSites.all();
        if (selected == null && !sites.isEmpty()) {
            selected = sites.get(0);
            if (selectedId == null || selectedId.isBlank()) selectedId = selected.id();
        }
        Button imageToggle = Button.builder(Component.literal("图像理解: " + (visionEnabled ? "开启" : "关闭")),
                button -> { visionEnabled = !visionEnabled; button.setMessage(Component.literal("图像理解: " + (visionEnabled ? "开启" : "关闭"))); })
                .bounds(left, top + 20, 125, 20).build();
        Button scanToggle = Button.builder(Component.literal("浅扫描: " + (shallowScanEnabled ? "开启" : "关闭")),
                button -> { shallowScanEnabled = !shallowScanEnabled; button.setMessage(Component.literal("浅扫描: " + (shallowScanEnabled ? "开启" : "关闭"))); })
                .bounds(left + 132, top + 20, 125, 20).build();
        addRenderableWidget(imageToggle);
        addRenderableWidget(scanToggle);

        int rowY = top + 52;
        for (VisionSite site : sites) {
            VisionSite rowSite = site;
            addRenderableWidget(Button.builder(Component.literal((site.enabled() ? "✓ " : "□ ") + site.displayName()),
                    button -> { if (!insufficientPermissions) {
                        rowSite.setEnabled(!rowSite.enabled());
                        AIFunNetwork.sendVisionSiteToServer(new AIFunVisionSiteSaveMessage(
                                AIFunVisionSiteSaveMessage.Action.TOGGLE, rowSite.id(), rowSite.enabled(), ""));
                    } }).bounds(left, rowY, 180, 20).build());
            addRenderableWidget(Button.builder(Component.literal(site.id().equals(selectedId) ? "已选择" : "选择"),
                    button -> { selected = rowSite; selectedId = rowSite.id(); loadFields(); }).bounds(left + 185, rowY, 55, 20).build());
            rowY += 22;
        }

        int formX = left + 250;
        endpoint = new EditBox(font, formX, top + 55, 180, 20, Component.literal("endpoint"));
        model = new EditBox(font, formX, top + 100, 180, 20, Component.literal("model"));
        apiKey = new EditBox(font, formX, top + 145, 180, 20, Component.literal("api key"));
        apiKey.setSuggestion("API key");
        addRenderableWidget(endpoint);
        addRenderableWidget(model);
        addRenderableWidget(apiKey);
        loadFields();

        addRenderableWidget(Button.builder(Component.literal("保存当前站点"), button -> saveSite())
                .bounds(formX, top + 175, 180, 20).build());
        addRenderableWidget(Button.builder(Component.literal("关闭"), button -> onClose())
                .bounds(left + 330, top + 215, 100, 20).build());
        if (insufficientPermissions) {
            imageToggle.active = false;
            scanToggle.active = false;
            endpoint.setEditable(false);
            model.setEditable(false);
            apiKey.setEditable(false);
        }
        if (!requestedInitialSync) {
            requestedInitialSync = true;
            AIFunNetwork.requestVisionSitesFromServer();
        }
    }

    private void loadFields() {
        if (endpoint == null || selected == null) return;
        endpoint.setValue(selected.endpoint());
        model.setValue(selected.model());
        apiKey.setValue(selected.apiKey());
    }

    private void saveSite() {
        if (insufficientPermissions || selected == null) return;
        selected.setEndpoint(endpoint.getValue());
        selected.setModel(model.getValue());
        selected.setApiKey(apiKey.getValue());
        AIFunNetwork.sendVisionSiteToServer(new AIFunVisionSiteSaveMessage(
                AIFunVisionSiteSaveMessage.Action.UPSERT, selected.id(), selected.enabled(), selected.toJson().toString()));
        AIFunNetwork.sendVisionSettingsToServer(visionEnabled, shallowScanEnabled, selectedId);
    }

    public void refreshFromServer(AIFunVisionSitesSyncMessage message) {
        this.insufficientPermissions = message.insufficientPermissions();
        this.visionEnabled = message.visionEnabled();
        this.shallowScanEnabled = message.shallowScanEnabled();
        this.selectedId = message.selectedSite();
        this.selected = AvailableVisionSites.get(selectedId);
        if (minecraft != null) minecraft.execute(this::init);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        int left = (width - 430) / 2;
        int top = (height - 250) / 2;
        graphics.drawCenteredString(font, title, width / 2, top, 0xFFFFFF);
        graphics.drawString(font, "视觉服务商（密钥只由有权限的服务器保存）", left, top + 42, 0xAAAAAA);
        graphics.drawString(font, "端点", left + 250, top + 45, 0xAAAAAA);
        graphics.drawString(font, "模型", left + 250, top + 90, 0xAAAAAA);
        graphics.drawString(font, "API Key", left + 250, top + 135, 0xAAAAAA);
        if (insufficientPermissions) graphics.drawWordWrap(font, Component.literal("权限不足：只能查看，无法保存"),
                left, top + 235, 220, 0xFF5555);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        if (!insufficientPermissions) {
            AIFunNetwork.sendVisionSettingsToServer(visionEnabled, shallowScanEnabled, selectedId);
        }
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
