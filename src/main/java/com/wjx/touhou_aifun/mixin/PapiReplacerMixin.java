package com.wjx.touhou_aifun.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.setting.papi.PapiReplacer;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = PapiReplacer.class, remap = false)
public abstract class PapiReplacerMixin {
    @Inject(method = "replaceSetting", at = @At("RETURN"), cancellable = true)
    private static void touhouStepFun$strengthenSameLanguages(String input, EntityMaid maid, String language,
                                                               CallbackInfoReturnable<String> cir) {
        String result = cir.getReturnValue();
        if (result == null) {
            return;
        }

        String ttsLanguage = maid.getAiChatManager().getTTSLanguage();
        if (language.equals(ttsLanguage)) {
            String strengthened = result.replace(
                    "- Part 2: An exact copy of Part 1 (used for text-to-speech).",
                    "- **CRITICAL**: Part 2 is an **exact copy** of Part 1, do NOT translate. Part 2 MUST be the same language and content as Part 1."
            );
            cir.setReturnValue(strengthened);
        }
    }
}
