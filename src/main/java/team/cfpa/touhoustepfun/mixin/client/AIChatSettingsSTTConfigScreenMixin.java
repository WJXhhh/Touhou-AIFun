package team.cfpa.touhoustepfun.mixin.client;

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
import team.cfpa.touhoustepfun.client.gui.STTSiteDropdownWidget;
import team.cfpa.touhoustepfun.config.TouhouStepFunConfig;

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
    private boolean touhouStepFun$hideMicWhenOpen;
    @Unique
    private boolean touhouStepFun$hideSliderWhenOpen;
    @Unique
    private boolean touhouStepFun$hideProxyWhenOpen;

    @Inject(method = "initContent", at = @At("TAIL"))
    private void touhouStepFun$replaceTypeButtonWithSiteList(CallbackInfo ci) {
        AIChatSettingsSTTConfigScreen screen = (AIChatSettingsSTTConfigScreen) (Object) this;
        AIChatSettingsHubScreen.SharedState state = ((AIChatSettingsHubAccessor) this).touhouStepFun$getState();
        List<STTSite> enabledSites = state.sttSites.values().stream().filter(STTSite::enabled).toList();
        String selectedId = this.touhouStepFun$resolveSelectedSite(enabledSites, state);

        this.typeBtn.visible = false;
        this.typeBtn.active = false;
        STTSiteDropdownWidget dropdown = new STTSiteDropdownWidget(
                this.typeBtn.getX(), this.typeBtn.getY(), this.typeBtn.getWidth(), enabledSites, selectedId,
                siteId -> {
                    TouhouStepFunConfig.setSelectedSttSite(siteId);
                    for (STTApiType type : STTApiType.values()) {
                        if (type.getName().equals(siteId)) {
                            state.sttType = type;
                            break;
                        }
                    }
                }, open -> this.touhouStepFun$setOtherInputsActive(!open));
        screen.addRenderableWidget(dropdown);

        // Decide which widgets the expanded list will overlap, so we can hide only those while it is open.
        int dropdownBottom = this.typeBtn.getY()
                + STTSiteDropdownWidget.ROW_HEIGHT * (enabledSites.size() + 1);
        this.touhouStepFun$hideMicWhenOpen = this.microphoneBtn.getY() < dropdownBottom;
        this.touhouStepFun$hideSliderWhenOpen = this.distanceSlider.getY() < dropdownBottom;
        this.touhouStepFun$hideProxyWhenOpen = this.proxyInput.getY() < dropdownBottom;
    }

    private String touhouStepFun$resolveSelectedSite(List<STTSite> enabledSites,
                                                     AIChatSettingsHubScreen.SharedState state) {
        String configured = TouhouStepFunConfig.STT_SELECTED_SITE.get();
        if (enabledSites.stream().anyMatch(site -> site.id().equals(configured))) {
            return configured;
        }
        String legacy = state.sttType.getName();
        String selected = enabledSites.stream().filter(site -> site.id().equals(legacy))
                .findFirst().or(() -> enabledSites.stream().findFirst())
                .map(STTSite::id).orElse("");
        if (!selected.equals(configured)) {
            TouhouStepFunConfig.setSelectedSttSite(selected);
        }
        return selected;
    }

    private void touhouStepFun$setOtherInputsActive(boolean active) {
        // active == false means the dropdown is open: hide+disable only the widgets it overlaps,
        // so their text/render does not bleed through the option rows and they don't steal clicks.
        if (this.touhouStepFun$hideMicWhenOpen) {
            this.microphoneBtn.active = active;
            this.microphoneBtn.visible = active;
        }
        if (this.touhouStepFun$hideSliderWhenOpen) {
            this.distanceSlider.active = active;
            this.distanceSlider.visible = active;
        }
        if (this.touhouStepFun$hideProxyWhenOpen) {
            this.proxyInput.setEditable(active);
            this.proxyInput.visible = active;
        }
    }
}
