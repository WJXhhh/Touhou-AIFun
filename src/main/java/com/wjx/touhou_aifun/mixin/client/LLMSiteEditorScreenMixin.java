package com.wjx.touhou_aifun.mixin.client;

import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMSite;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.LLMOpenAISite;
import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.editor.LLMSiteEditorScreen;
import com.wjx.touhou_aifun.compat.ai.anthropic.AnthropicLLMSite;
import com.wjx.touhou_aifun.compat.ai.anthropic.AnthropicShared;
import com.wjx.touhou_aifun.compat.ai.mimo.MimoLLMSite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.wjx.touhou_aifun.compat.ai.mimo.MimoShared;
import com.wjx.touhou_aifun.compat.ai.stepfun.StepFunLLMSite;
import com.wjx.touhou_aifun.compat.ai.stepfun.StepFunPlanLLMSite;
import com.wjx.touhou_aifun.compat.ai.stepfun.StepFunShared;

import java.util.ArrayList;
import java.util.List;

/**
 * The base mod's {@code LLMSiteEditorScreen.buildSite()} always constructs a plain
 * {@link LLMOpenAISite}, regardless of the original site type.  For most custom sites
 * that is harmless — the icon, URL, and client behaviour survive through the saved
 * JSON.  Some providers also need their canonical site subclass/icon restored so
 * the editor does not accidentally keep a stale icon from a previous selection.
 */
@Mixin(value = LLMSiteEditorScreen.class, remap = false)
public abstract class LLMSiteEditorScreenMixin {
    @Shadow
    private LLMSite sourceSite;

    @Shadow
    private boolean supportsReasoning;

    @Shadow
    private boolean createMode;

    @Inject(method = "buildSite", at = @At("RETURN"), cancellable = true)
    private void touhouAIFun$preserveSiteApiType(CallbackInfoReturnable<LLMSite> cir) {
        if (createMode) {
            return;
        }

        LLMSite result = cir.getReturnValue();
        if (result == null) {
            return;
        }
        if (!(result instanceof LLMOpenAISite openAISite)) {
            return;
        }

        List<LLMOpenAISite.ModelEntry> entries = new ArrayList<>(openAISite.modelEntries().values());
        if (isStepFunPlanSource()) {
            cir.setReturnValue(new StepFunPlanLLMSite(
                    openAISite.id(), StepFunShared.ICON, openAISite.url(),
                    openAISite.enabled(), openAISite.secretKey(),
                    supportsReasoning, openAISite.headers(), entries
            ));
            return;
        }
        if (isStepFunSource()) {
            cir.setReturnValue(new StepFunLLMSite(
                    openAISite.id(), StepFunShared.ICON, openAISite.url(),
                    openAISite.enabled(), openAISite.secretKey(),
                    supportsReasoning, openAISite.headers(), entries
            ));
            return;
        }
        if (isMimoSource()) {
            cir.setReturnValue(new MimoLLMSite(
                    openAISite.id(), MimoShared.ICON, openAISite.url(),
                    openAISite.enabled(), openAISite.secretKey(),
                    supportsReasoning, openAISite.headers(), entries
            ));
            return;
        }
        if (isAnthropicSource()) {
            // Keep the original thinking-field flag: the base mod passes supportsReasoning=false
            // for non-"openai" ids, which would silently re-enable thinking after an edit.
            boolean thinkingField = sourceSite instanceof LLMOpenAISite anthropicSite
                    ? anthropicSite.hasThinkingField() : supportsReasoning;
            cir.setReturnValue(new AnthropicLLMSite(
                    openAISite.id(), AnthropicShared.ICON, openAISite.url(),
                    openAISite.enabled(), openAISite.secretKey(),
                    thinkingField, openAISite.headers(), entries
            ));
        }
    }

    private boolean isStepFunSource() {
        if (sourceSite instanceof StepFunPlanLLMSite) {
            return false;
        }
        if (sourceSite instanceof StepFunLLMSite) {
            return true;
        }
        return "stepfun".equals(sourceSite.id());
    }

    private boolean isStepFunPlanSource() {
        return sourceSite instanceof StepFunPlanLLMSite || "stepfun_plan".equals(sourceSite.id());
    }

    private boolean isMimoSource() {
        if (sourceSite instanceof MimoLLMSite) {
            return true;
        }
        return "mimo".equals(sourceSite.id());
    }

    private boolean isAnthropicSource() {
        if (sourceSite instanceof AnthropicLLMSite) {
            return true;
        }
        return AnthropicShared.API_TYPE.equals(sourceSite.id());
    }
}
