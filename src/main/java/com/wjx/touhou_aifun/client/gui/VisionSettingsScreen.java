package com.wjx.touhou_aifun.client.gui;

import com.github.tartaricacid.touhoulittlemaid.client.gui.widget.ai.SideGroupWidget;
import com.github.tartaricacid.touhoulittlemaid.client.gui.widget.button.FlatColorButton;
import com.wjx.touhou_aifun.config.TouhouAIFunConfig;
import com.wjx.touhou_aifun.network.AIFunNetwork;
import com.wjx.touhou_aifun.network.message.AIFunVisionSiteSaveMessage;
import com.wjx.touhou_aifun.network.message.AIFunVisionSitesSyncMessage;
import com.wjx.touhou_aifun.vision.ClientVisionSitesSnapshot;
import com.wjx.touhou_aifun.vision.VisionSite;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Visual settings page styled after TLM's AI settings hub. */
public final class VisionSettingsScreen extends Screen {
    private static final int BASE_WIDTH = 400;
    private static final int BASE_HEIGHT = 230;
    private static final int SIDE_WIDTH = 100;
    private static final int CONTENT_X_OFFSET = SIDE_WIDTH + 5;
    private static final int CONTENT_WIDTH = BASE_WIDTH - CONTENT_X_OFFSET;
    private static final int LABEL_COLOR = 0xFF777777;

    private final @Nullable Screen parent;
    private Page page = Page.CONFIG;
    private boolean insufficientPermissions;
    private boolean visionEnabled;
    private boolean shallowScanEnabled;
    private String selectedId;
    private boolean requestedInitialSync;
    private long statusTimestamp = -1;
    private Component statusMessage = Component.empty();
    private int startX;
    private int startY;

    public VisionSettingsScreen(@Nullable Screen parent) {
        super(Component.translatable("gui.touhou_aifun.vision"));
        this.parent = parent;
        this.visionEnabled = TouhouAIFunConfig.VISION_ENABLED.get();
        this.shallowScanEnabled = TouhouAIFunConfig.SHALLOW_SCAN_ENABLED.get();
        this.selectedId = TouhouAIFunConfig.VISION_SELECTED_SITE.get();
    }

    @Override
    protected void init() {
        clearWidgets();
        startX = (width - BASE_WIDTH) / 2;
        startY = (height - BASE_HEIGHT) / 2;
        addNavigation();
        if (page == Page.CONFIG) {
            addConfigPage();
        } else {
            addProviderPage();
        }
        addFooter();
        if (!requestedInitialSync) {
            requestedInitialSync = true;
            AIFunNetwork.requestVisionSitesFromServer();
        }
    }

    private void addNavigation() {
        int x = startX;
        int y = startY + 5;
        addRenderableOnly(new SideGroupWidget(x, y, Component.translatable("gui.touhou_aifun.vision")));
        FlatColorButton config = new FlatColorButton(x, y + 20, 95, 20,
                Component.translatable("gui.touhou_aifun.vision.config"), button -> switchPage(Page.CONFIG));
        config.setSelect(page == Page.CONFIG);
        addRenderableWidget(config);
        FlatColorButton providers = new FlatColorButton(x, y + 40, 95, 20,
                Component.translatable("gui.touhou_aifun.vision.providers"), button -> switchPage(Page.PROVIDERS));
        providers.setSelect(page == Page.PROVIDERS);
        addRenderableWidget(providers);
    }

    private void switchPage(Page target) {
        if (page != target) {
            page = target;
            init();
        }
    }

    private void addConfigPage() {
        int x = contentX();
        int y = contentY();
        FlatColorButton image = new FlatColorButton(x, y + 18, CONTENT_WIDTH, 20,
                onOff(visionEnabled), button -> {
            visionEnabled = !visionEnabled;
            AIFunNetwork.sendVisionSettingsToServer(visionEnabled, shallowScanEnabled, selectedId);
            init();
        });
        image.setSelect(visionEnabled);
        image.active = !insufficientPermissions;
        addRenderableWidget(image);

        FlatColorButton scan = new FlatColorButton(x, y + 58, CONTENT_WIDTH, 20,
                onOff(shallowScanEnabled), button -> {
            shallowScanEnabled = !shallowScanEnabled;
            AIFunNetwork.sendVisionSettingsToServer(visionEnabled, shallowScanEnabled, selectedId);
            init();
        });
        scan.setSelect(shallowScanEnabled);
        scan.active = !insufficientPermissions;
        addRenderableWidget(scan);
    }

    private void addProviderPage() {
        List<VisionSite> sites = ClientVisionSitesSnapshot.all();
        int x = contentX();
        int y = contentY();
        for (VisionSite site : sites) {
            FlatColorButton select = new FlatColorButton(x, y, CONTENT_WIDTH - 61, 24,
                    providerLabel(site),
                    button -> selectProvider(site.id()));
            select.setSelect(site.id().equals(selectedId));
            select.active = !insufficientPermissions;
            addRenderableWidget(select);
            addRenderableWidget(new FlatColorButton(x + CONTENT_WIDTH - 57, y, 57, 24,
                    Component.translatable("gui.touhou_aifun.vision.edit"),
                    button -> openEditor(site)));
            y += 26;
        }
    }

    private void openEditor(VisionSite site) {
        if (minecraft != null) {
            minecraft.setScreen(new VisionSiteEditorScreen(this, site, insufficientPermissions));
        }
    }

    private Component providerLabel(VisionSite site) {
        String key;
        if (!site.apiKeyPresent()) {
            key = "gui.touhou_aifun.vision.provider_missing_key";
        } else if (site.model().isBlank()) {
            key = "gui.touhou_aifun.vision.provider_missing_model";
        } else if (!site.hasValidHttpEndpoint()) {
            key = "gui.touhou_aifun.vision.provider_invalid_endpoint";
        } else {
            key = "gui.touhou_aifun.vision.provider_configured";
        }
        return Component.translatable(key, site.displayName());
    }

    private void selectProvider(String siteId) {
        if (insufficientPermissions) return;
        selectedId = siteId;
        // Provider choice is the provider's only active/inactive state. Persist it immediately so
        // opening the editor cannot be followed by a server sync that restores the previous choice.
        AIFunNetwork.sendVisionSettingsToServer(visionEnabled, shallowScanEnabled, selectedId);
        init();
    }

    void saveSite(VisionSite site) {
        if (insufficientPermissions) return;
        AIFunNetwork.sendVisionSiteToServer(new AIFunVisionSiteSaveMessage(
                AIFunVisionSiteSaveMessage.Action.UPSERT, site.id(), site.toJson().toString()));
    }

    void clearSiteApiKey(VisionSite site) {
        if (insufficientPermissions) return;
        AIFunNetwork.sendVisionSiteToServer(new AIFunVisionSiteSaveMessage(
                AIFunVisionSiteSaveMessage.Action.CLEAR_API_KEY, site.id(), ""));
    }

    private void addFooter() {
        int y = startY + BASE_HEIGHT - 24;
        addRenderableWidget(new FlatColorButton(contentX() + CONTENT_WIDTH - 80, y, 80, 20,
                CommonComponents.GUI_BACK, button -> onClose()));
    }

    public void refreshFromServer(AIFunVisionSitesSyncMessage message) {
        insufficientPermissions = message.insufficientPermissions();
        visionEnabled = message.visionEnabled();
        shallowScanEnabled = message.shallowScanEnabled();
        selectedId = message.selectedSite();
        if (message.operationStatus() != null && !message.operationStatus().isBlank()) {
            statusMessage = Component.translatable(
                    "gui.touhou_aifun.vision.status." + message.operationStatus());
            statusTimestamp = System.currentTimeMillis();
        }
        if (minecraft != null) minecraft.execute(this::init);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(graphics);
        if (page == Page.CONFIG) {
            int x = contentX();
            int y = contentY();
            graphics.drawString(font, Component.translatable("gui.touhou_aifun.vision.image_enabled"), x + 2, y + 6, LABEL_COLOR, false);
            graphics.drawString(font, Component.translatable("gui.touhou_aifun.vision.scan_enabled"), x + 2, y + 46, LABEL_COLOR, false);
            graphics.drawWordWrap(font, Component.translatable("gui.touhou_aifun.vision.privacy"), x + 2, y + 92,
                    CONTENT_WIDTH - 4, 0xFF999999);
        } else {
            graphics.drawString(font, Component.translatable("gui.touhou_aifun.vision.provider_hint"),
                    contentX() + 2, contentY() - 11, LABEL_COLOR, false);
        }
        if (insufficientPermissions) {
            graphics.drawString(font, Component.translatable("ai.touhou_little_maid.chat.settings.hub.insufficient_permissions"),
                    contentX(), startY + BASE_HEIGHT - 36, 0xFFFF5555, false);
        }
        if (!insufficientPermissions && System.currentTimeMillis() - statusTimestamp < 3000) {
            graphics.drawString(font, statusMessage, contentX(), startY + BASE_HEIGHT - 36,
                    messageIsError() ? 0xFFFF5555 : 0xFF55AA55, false);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private boolean messageIsError() {
        String text = statusMessage.getString().toLowerCase(java.util.Locale.ROOT);
        return text.contains("失败") || text.contains("无权限") || text.contains("无效")
                || text.contains("不存在") || text.contains("failed") || text.contains("invalid")
                || text.contains("permission") || text.contains("not found") || text.contains("mismatch");
    }

    private Component onOff(boolean enabled) {
        return Component.translatable(enabled ? "gui.touhou_aifun.vision.on" : "gui.touhou_aifun.vision.off");
    }

    private int contentX() { return startX + CONTENT_X_OFFSET; }
    private int contentY() { return startY + 5; }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    private enum Page { CONFIG, PROVIDERS }
}
