package com.wjx.touhou_aifun.mixin.client;

import com.github.tartaricacid.touhoulittlemaid.ai.service.stt.STTApiType;
import com.github.tartaricacid.touhoulittlemaid.ai.service.stt.STTSite;
import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.settings.AIChatSettingsHubScreen;
import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.settings.AIChatSettingsSTTConfigScreen;
import com.github.tartaricacid.touhoulittlemaid.client.gui.widget.ai.MaidChatDistanceSlider;
import com.github.tartaricacid.touhoulittlemaid.client.gui.widget.button.FlatColorButton;
import com.github.tartaricacid.touhoulittlemaid.config.subconfig.AIConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.wjx.touhou_aifun.client.gui.DecisionServiceSettingsScreen;
import com.wjx.touhou_aifun.client.gui.STTSiteDropdownWidget;
import com.wjx.touhou_aifun.client.voice.FreeChatController;
import com.wjx.touhou_aifun.client.voice.FreeChatGateMeterWidget;
import com.wjx.touhou_aifun.compat.ai.stt.SttSiteSelection;
import com.wjx.touhou_aifun.config.TouhouAIFunConfig;

import java.util.List;

@Mixin(value = AIChatSettingsSTTConfigScreen.class, remap = false)
public abstract class AIChatSettingsSTTConfigScreenMixin {
    @Shadow
    private FlatColorButton typeBtn;

    @Shadow
    private FlatColorButton microphoneBtn;

    @Shadow
    private MaidChatDistanceSlider distanceSlider;

    @Shadow
    private EditBox proxyInput;

    @Unique
    private boolean touhouAIFun$hideMicWhenOpen;
    @Unique
    private boolean touhouAIFun$hideSliderWhenOpen;
    @Unique
    private boolean touhouAIFun$hideProxyWhenOpen;
    @Unique
    private boolean touhouAIFun$hideFreeChatRowWhenOpen;
    @Unique
    private boolean touhouAIFun$hideMeterWhenOpen;
    @Unique
    private FlatColorButton touhouAIFun$freeChatButton;
    @Unique
    private FlatColorButton touhouAIFun$decisionButton;
    @Unique
    private FreeChatGateMeterWidget touhouAIFun$meter;

    @Inject(method = "initContent", at = @At("TAIL"))
    private void touhouAIFun$replaceTypeButtonWithSiteList(CallbackInfo ci) {
        AIChatSettingsSTTConfigScreen screen = (AIChatSettingsSTTConfigScreen) (Object) this;
        AIChatSettingsHubScreen.SharedState state = ((AIChatSettingsHubAccessor) this).touhouAIFun$getState();
        List<STTSite> enabledSites = state.sttSites.values().stream().filter(STTSite::enabled).toList();
        String selectedId = this.touhouAIFun$resolveSelectedSite(enabledSites, state);

        this.typeBtn.visible = false;
        this.typeBtn.active = false;
        STTSiteDropdownWidget dropdown = new STTSiteDropdownWidget(
                this.typeBtn.getX(), this.typeBtn.getY(), this.typeBtn.getWidth(), enabledSites, selectedId,
                siteId -> {
                    TouhouAIFunConfig.setSelectedSttSite(siteId);
                    for (STTApiType type : STTApiType.values()) {
                        if (type.getName().equals(siteId)) {
                            state.sttType = type;
                            break;
                        }
                    }
                }, open -> this.touhouAIFun$setOtherInputsActive(!open));
        screen.addRenderableWidget(dropdown);

        // Decide which widgets the expanded list will overlap, so we can hide only those while it is open.
        int dropdownBottom = this.typeBtn.getY()
                + STTSiteDropdownWidget.ROW_HEIGHT * (enabledSites.size() + 1);
        this.touhouAIFun$hideMicWhenOpen = this.microphoneBtn.getY() < dropdownBottom;
        this.touhouAIFun$hideSliderWhenOpen = this.distanceSlider.getY() < dropdownBottom;
        this.touhouAIFun$hideProxyWhenOpen = this.proxyInput.getY() < dropdownBottom;

        int contentX = ((AIChatSettingsHubAccessor) this).touhouAIFun$invokeGetContentX();
        int contentWidth = ((AIChatSettingsHubAccessor) this).touhouAIFun$invokeGetContentWidth();
        int contentY = this.typeBtn.getY() - 12;
        int freeChatY = contentY + 148;
        int meterY = contentY + 170;

        boolean freeChat = false;
        try {
            freeChat = TouhouAIFunConfig.FREE_CHAT.get();
        } catch (RuntimeException ignored) {
        }
        this.touhouAIFun$freeChatButton = new FlatColorButton(contentX, freeChatY, 190, 20,
                Component.translatable(freeChat
                        ? "gui.touhou_aifun.free_chat.on"
                        : "gui.touhou_aifun.free_chat.off"),
                button -> this.touhouAIFun$toggleFreeChat(screen));
        this.touhouAIFun$freeChatButton.setSelect(freeChat);
        this.touhouAIFun$freeChatButton.setTooltip(Tooltip.create(
                Component.translatable("gui.touhou_aifun.free_chat.tooltip")));
        screen.addRenderableWidget(this.touhouAIFun$freeChatButton);

        this.touhouAIFun$decisionButton = new FlatColorButton(contentX + 194, freeChatY, 101, 20,
                Component.translatable("gui.touhou_aifun.free_chat.decision"),
                button -> screen.getMinecraft().setScreen(new DecisionServiceSettingsScreen(screen)));
        this.touhouAIFun$decisionButton.setTooltip(Tooltip.create(
                Component.translatable("gui.touhou_aifun.free_chat.decision_tooltip")));
        screen.addRenderableWidget(this.touhouAIFun$decisionButton);

        this.touhouAIFun$meter = new FreeChatGateMeterWidget(contentX, meterY, contentWidth, 30);
        screen.addRenderableWidget(this.touhouAIFun$meter);
        FreeChatController.startMonitor(state.sttMicrophone);

        this.touhouAIFun$hideFreeChatRowWhenOpen = freeChatY < dropdownBottom;
        this.touhouAIFun$hideMeterWhenOpen = meterY < dropdownBottom;
    }

    private void touhouAIFun$toggleFreeChat(AIChatSettingsSTTConfigScreen screen) {
        boolean enabled = false;
        try {
            enabled = TouhouAIFunConfig.FREE_CHAT.get();
        } catch (RuntimeException ignored) {
        }
        if (enabled) {
            TouhouAIFunConfig.setFreeChat(false);
            this.touhouAIFun$notice("gui.touhou_aifun.free_chat.status.off");
            screen.init(screen.getMinecraft(), screen.width, screen.height);
            return;
        }
        if (!AIConfig.STT_ENABLED.get()) {
            this.touhouAIFun$notice("gui.touhou_aifun.free_chat.status.stt_disabled");
            return;
        }
        if (SttSiteSelection.resolveBuffered() == null) {
            this.touhouAIFun$notice("gui.touhou_aifun.free_chat.status.stt_unsupported");
            return;
        }
        if (!FreeChatController.hasSupportedMicrophone()) {
            this.touhouAIFun$notice("gui.touhou_aifun.free_chat.status.mic_missing");
            return;
        }
        TouhouAIFunConfig.setFreeChat(true);
        this.touhouAIFun$notice("gui.touhou_aifun.free_chat.status.on");
        screen.init(screen.getMinecraft(), screen.width, screen.height);
    }

    private void touhouAIFun$notice(String key) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null && minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.translatable(key), true);
        }
    }

    private String touhouAIFun$resolveSelectedSite(List<STTSite> enabledSites,
                                                     AIChatSettingsHubScreen.SharedState state) {
        String configured = TouhouAIFunConfig.STT_SELECTED_SITE.get();
        if (enabledSites.stream().anyMatch(site -> site.id().equals(configured))) {
            return configured;
        }
        String legacy = state.sttType.getName();
        String selected = enabledSites.stream().filter(site -> site.id().equals(legacy))
                .findFirst().or(() -> enabledSites.stream().findFirst())
                .map(STTSite::id).orElse("");
        if (!selected.equals(configured)) {
            TouhouAIFunConfig.setSelectedSttSite(selected);
        }
        return selected;
    }

    private void touhouAIFun$setOtherInputsActive(boolean active) {
        // active == false means the dropdown is open: hide+disable only the widgets it overlaps,
        // so their text/render does not bleed through the option rows and they don't steal clicks.
        if (this.touhouAIFun$hideMicWhenOpen) {
            this.microphoneBtn.active = active;
            this.microphoneBtn.visible = active;
        }
        if (this.touhouAIFun$hideSliderWhenOpen) {
            this.distanceSlider.active = active;
            this.distanceSlider.visible = active;
        }
        if (this.touhouAIFun$hideProxyWhenOpen) {
            this.proxyInput.setEditable(active);
            this.proxyInput.visible = active;
        }
        if (this.touhouAIFun$hideFreeChatRowWhenOpen) {
            if (this.touhouAIFun$freeChatButton != null) {
                this.touhouAIFun$freeChatButton.active = active;
                this.touhouAIFun$freeChatButton.visible = active;
            }
            if (this.touhouAIFun$decisionButton != null) {
                this.touhouAIFun$decisionButton.active = active;
                this.touhouAIFun$decisionButton.visible = active;
            }
        }
        if (this.touhouAIFun$hideMeterWhenOpen && this.touhouAIFun$meter != null) {
            this.touhouAIFun$meter.active = active;
            this.touhouAIFun$meter.visible = active;
        }
    }
}
