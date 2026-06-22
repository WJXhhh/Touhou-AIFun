package com.wjx.touhou_aifun.mixin.client;

import com.github.tartaricacid.touhoulittlemaid.ai.service.stt.STTApiType;
import com.github.tartaricacid.touhoulittlemaid.ai.service.stt.STTSite;
import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.settings.AIChatSettingsHubScreen;
import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.settings.AIChatSettingsSTTConfigScreen;
import com.github.tartaricacid.touhoulittlemaid.client.gui.widget.ai.MaidChatDistanceSlider;
import com.github.tartaricacid.touhoulittlemaid.client.gui.widget.button.FlatColorButton;
import net.minecraft.client.gui.components.EditBox;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.wjx.touhou_aifun.client.gui.STTSiteDropdownWidget;
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
    }
}
