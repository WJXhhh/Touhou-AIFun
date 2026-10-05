package com.wjx.touhou_aifun.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.implement.QueryMinecraftWikiTool;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMClient;
import com.wjx.touhou_aifun.chat.context.ContextTokenEstimator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.concurrent.CompletableFuture;

@Mixin(value = QueryMinecraftWikiTool.class, remap = false)
public abstract class WikiCostMixin {
    @Inject(method = "summarizeWithChildCallback", at = @At("HEAD"), cancellable = true)
    private void aifun$shortArticle(String text, LLMCallback callback, LLMClient client,
                                   CallbackInfoReturnable<CompletableFuture<String>> cir) {
        cir.setReturnValue(com.wjx.touhou_aifun.chat.agent.KnowledgeSummaryCache.summarize("Minecraft Wiki", text, callback, client));
    }
}
