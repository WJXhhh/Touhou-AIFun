package com.wjx.touhou_aifun.client.gui;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatSerializable;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.site.ClientAvailableSitesSync;
import com.github.tartaricacid.touhoulittlemaid.ai.service.tts.SupportLanguage;
import com.github.tartaricacid.touhoulittlemaid.client.gui.widget.button.FlatColorButton;
import com.wjx.touhou_aifun.maid.management.MaidAIConfigSnapshot;
import com.wjx.touhou_aifun.maid.management.MaidManagementEntry;
import com.wjx.touhou_aifun.network.AIFunNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class MaidAIConfigScreen extends Screen {
    private static final int BASE_WIDTH = 500;
    private static final int BASE_HEIGHT = 300;

    private final MaidManagementScreen parent;
    private final MaidManagementEntry entry;
    private String llmSite;
    private String llmModel;
    private String ttsSite;
    private String ttsModel;
    private String ttsLanguage;
    private final String chatLanguage;
    private String ownerNameValue;
    private String customSettingValue;
    private boolean publicMaid;
    private boolean friendlyFireAllowed;
    private int dirtyMask;
    private EditBox ownerName;
    private MultiLineEditBox customSetting;
    private int startX;
    private int startY;
    private int panelWidth;
    private int panelHeight;

    public MaidAIConfigScreen(MaidManagementScreen parent, MaidManagementEntry entry) {
        super(Component.translatable("gui.touhou_aifun.maid_management.edit_title", entry.name()));
        this.parent = parent;
        this.entry = entry;
        MaidAIConfigSnapshot config = entry.config();
        this.llmSite = config.llmSite();
        this.llmModel = config.llmModel();
        this.ttsSite = config.ttsSite();
        this.ttsModel = config.ttsModel();
        this.ttsLanguage = config.ttsLanguage();
        this.chatLanguage = config.chatLanguage();
        this.ownerNameValue = config.ownerName();
        this.customSettingValue = config.customSetting();
        this.publicMaid = entry.publicMaid();
        this.friendlyFireAllowed = entry.friendlyFireAllowed();
        this.dirtyMask = entry.configKnown() ? MaidAIConfigSnapshot.ALL : 0;
    }

    @Override
    protected void init() {
        captureInputs();
        clearWidgets();
        panelWidth = Math.max(350, Math.min(BASE_WIDTH, width - 20));
        panelHeight = Math.max(220, Math.min(BASE_HEIGHT, height - 20));
        startX = (width - panelWidth) / 2;
        startY = (height - panelHeight) / 2;

        int gap = 8;
        int columnWidth = (panelWidth - 24 - gap) / 2;
        int leftX = startX + 8;
        int rightX = leftX + columnWidth + gap;
        int top = startY + 30;

        addRenderableWidget(new FlatColorButton(leftX, top, columnWidth, 20,
                Component.translatable("gui.touhou_aifun.maid_management.llm_site", siteName(llmSite, true)),
                button -> cycleLlmSite()));
        FlatColorButton llmModelButton = new FlatColorButton(leftX, top + 23, columnWidth, 20,
                Component.translatable("gui.touhou_aifun.maid_management.llm_model", llmModelName()),
                button -> cycleLlmModel());
        llmModelButton.active = ClientAvailableSitesSync.getClientLLMSites().containsKey(llmSite);
        addRenderableWidget(llmModelButton);

        addRenderableWidget(new FlatColorButton(leftX, top + 46, columnWidth, 20,
                Component.translatable("gui.touhou_aifun.maid_management.tts_site", siteName(ttsSite, false)),
                button -> cycleTtsSite()));
        FlatColorButton ttsModelButton = new FlatColorButton(leftX, top + 69, columnWidth, 20,
                Component.translatable("gui.touhou_aifun.maid_management.tts_model", ttsModelName()),
                button -> cycleTtsModel());
        ttsModelButton.active = !MaidAIChatSerializable.isNoTTSSite(ttsSite)
                && ClientAvailableSitesSync.getClientTTSSites().containsKey(ttsSite);
        addRenderableWidget(ttsModelButton);

        addRenderableWidget(new FlatColorButton(rightX, top, columnWidth, 20,
                Component.translatable("gui.touhou_aifun.maid_management.tts_language",
                        SupportLanguage.getLanguageName(ttsLanguage)), button -> cycleLanguage()));
        FlatColorButton publicButton = new FlatColorButton(rightX, top + 23, columnWidth, 20,
                toggleLabel("gui.touhou_aifun.maid_config.public_maid", publicMaid), button -> {
            publicMaid = !publicMaid;
            dirtyMask |= MaidAIConfigSnapshot.PUBLIC_MAID;
            init();
        });
        publicButton.setSelect(publicMaid);
        addRenderableWidget(publicButton);

        FlatColorButton friendlyFireButton = new FlatColorButton(rightX, top + 46, columnWidth, 20,
                toggleLabel("gui.touhou_aifun.maid_config.friendly_fire", friendlyFireAllowed), button -> {
            friendlyFireAllowed = !friendlyFireAllowed;
            dirtyMask |= MaidAIConfigSnapshot.FRIENDLY_FIRE;
            init();
        });
        friendlyFireButton.setSelect(friendlyFireAllowed);
        addRenderableWidget(friendlyFireButton);

        int ownerLabelWidth = Math.min(72, columnWidth / 3);
        ownerName = addRenderableWidget(new EditBox(font, rightX + ownerLabelWidth, top + 69,
                columnWidth - ownerLabelWidth, 20,
                Component.translatable("gui.touhou_little_maid.button.maid_ai_chat_config.owner_name")));
        ownerName.setMaxLength(128);
        ownerName.setValue(ownerNameValue);
        ownerName.setResponder(value -> {
            ownerNameValue = value;
            dirtyMask |= MaidAIConfigSnapshot.OWNER_NAME;
        });

        int customTop = top + 112;
        int footerY = startY + panelHeight - 25;
        int customHeight = Math.max(45, footerY - customTop - 8);
        customSetting = addRenderableWidget(new MultiLineEditBox(font, startX + 8, customTop,
                panelWidth - 16, customHeight,
                Component.translatable("gui.touhou_little_maid.button.maid_ai_chat_config.custom_setting"),
                Component.translatable("gui.touhou_aifun.maid_management.custom_setting_hint")));
        customSetting.setCharacterLimit(4096);
        customSetting.setValue(customSettingValue);
        customSetting.setValueListener(value -> {
            customSettingValue = value;
            dirtyMask |= MaidAIConfigSnapshot.CUSTOM_SETTING;
        });

        addRenderableWidget(new FlatColorButton(startX + panelWidth - 166, footerY, 78, 20,
                Component.translatable("selectWorld.edit.save"), button -> save()));
        addRenderableWidget(new FlatColorButton(startX + panelWidth - 84, footerY, 76, 20,
                CommonComponents.GUI_CANCEL, button -> onClose()));
    }

    private void cycleLlmSite() {
        dirtyMask |= MaidAIConfigSnapshot.LLM;
        llmSite = next(new ArrayList<>(ClientAvailableSitesSync.getClientLLMSites().keySet()), llmSite);
        llmModel = firstModel(ClientAvailableSitesSync.getClientLLMSites().get(llmSite));
        init();
    }

    private void cycleLlmModel() {
        dirtyMask |= MaidAIConfigSnapshot.LLM;
        llmModel = nextModel(ClientAvailableSitesSync.getClientLLMSites().get(llmSite), llmModel);
        init();
    }

    private void cycleTtsSite() {
        dirtyMask |= MaidAIConfigSnapshot.TTS;
        List<String> sites = new ArrayList<>(ClientAvailableSitesSync.getClientTTSSites().keySet());
        sites.add(MaidAIChatSerializable.NO_TTS_SITE);
        ttsSite = next(sites, ttsSite);
        ttsModel = MaidAIChatSerializable.isNoTTSSite(ttsSite) ? ""
                : firstModel(ClientAvailableSitesSync.getClientTTSSites().get(ttsSite));
        init();
    }

    private void cycleTtsModel() {
        dirtyMask |= MaidAIConfigSnapshot.TTS;
        ttsModel = nextModel(ClientAvailableSitesSync.getClientTTSSites().get(ttsSite), ttsModel);
        init();
    }

    private void cycleLanguage() {
        dirtyMask |= MaidAIConfigSnapshot.TTS_LANGUAGE;
        ttsLanguage = SupportLanguage.findNext(ttsLanguage);
        init();
    }

    private static String next(List<String> values, String current) {
        if (values.isEmpty()) {
            return "";
        }
        int index = values.indexOf(current);
        return values.get((index + 1 + values.size()) % values.size());
    }

    private static String firstModel(Map<String, String> models) {
        return models == null || models.isEmpty() ? "" : models.keySet().iterator().next();
    }

    private static String nextModel(Map<String, String> models, String current) {
        return models == null ? "" : next(new ArrayList<>(models.keySet()), current);
    }

    private Component siteName(String site, boolean llm) {
        if (MaidAIChatSerializable.isNoTTSSite(site)) {
            return Component.translatable("ai.touhou_little_maid.chat.site.none.name");
        }
        if (site == null || site.isBlank()) {
            return Component.translatable("gui.touhou_aifun.maid_management.default_value");
        }
        String key = "ai.touhou_little_maid.chat.site." + site + ".name";
        return I18n.exists(key) ? Component.translatable(key) : Component.literal(site);
    }

    private Component llmModelName() {
        String value = ClientAvailableSitesSync.getLLMModelName(llmSite, llmModel);
        return Component.literal(value);
    }

    private Component ttsModelName() {
        if (MaidAIChatSerializable.isNoTTSSite(ttsSite)) {
            return Component.literal("-");
        }
        return Component.literal(ClientAvailableSitesSync.getTTSModelName(ttsSite, ttsModel));
    }

    private static Component toggleLabel(String key, boolean enabled) {
        return Component.translatable(key).append(": ")
                .append(Component.translatable(enabled ? "options.on" : "options.off"));
    }

    private void captureInputs() {
        if (ownerName != null) {
            ownerNameValue = ownerName.getValue();
        }
        if (customSetting != null) {
            customSettingValue = customSetting.getValue();
        }
    }

    private void save() {
        captureInputs();
        MaidAIConfigSnapshot config = new MaidAIConfigSnapshot(llmSite, llmModel, ttsSite, ttsModel,
                ttsLanguage, chatLanguage, ownerNameValue, customSettingValue);
        AIFunNetwork.saveManagedMaidConfig(entry.maidId(), config, publicMaid,
                friendlyFireAllowed, dirtyMask);
        onClose();
    }

    @Override
    public void tick() {
        if (ownerName != null) {
            ownerName.tick();
        }
        if (customSetting != null) {
            customSetting.tick();
        }
        super.tick();
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        return super.mouseReleased(x, y, button)
                || customSetting != null && customSetting.mouseReleased(x, y, button);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.fill(startX, startY, startX + panelWidth, startY + panelHeight, 0xE0121212);
        graphics.drawCenteredString(font, title, width / 2, startY + 9, 0xFFF3EFE0);
        int columnWidth = (panelWidth - 32) / 2;
        int rightX = startX + 16 + columnWidth;
        int top = startY + 30;
        graphics.drawString(font,
                Component.translatable("gui.touhou_little_maid.button.maid_ai_chat_config.owner_name"),
                rightX, top + 75, 0xFFAAAAAA, false);
        graphics.drawString(font,
                Component.translatable("gui.touhou_little_maid.button.maid_ai_chat_config.custom_setting"),
                startX + 10, top + 101, 0xFFAAAAAA, false);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
