package com.wjx.touhou_aifun.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.TTSCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.tts.TTSClient;
import com.github.tartaricacid.touhoulittlemaid.ai.service.tts.TTSConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import com.wjx.touhou_aifun.compat.ai.tts.TTSProgressiveSynthesis;

@Mixin(value = MaidAIChatManager.class, remap = false)
public abstract class MaidAIChatManagerMixin {
    @Redirect(method = "tts",
            at = @At(value = "INVOKE",
                    target = "Lcom/github/tartaricacid/touhoulittlemaid/ai/service/tts/TTSClient;play(Ljava/lang/String;Lcom/github/tartaricacid/touhoulittlemaid/ai/service/tts/TTSConfig;Lcom/github/tartaricacid/touhoulittlemaid/ai/manager/entity/TTSCallback;)V"))
    private void touhouStepFun$playBySentence(TTSClient client, String message,
                                              TTSConfig config, TTSCallback callback) {
        TTSProgressiveSynthesis.play(client, message, config, callback);
    }
}
