package com.wjx.touhou_aifun.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.implement.UseSkillTool;
import com.github.tartaricacid.touhoulittlemaid.ai.agent.skill.*;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.*;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMClient;
import com.wjx.touhou_aifun.chat.context.ContextTokenEstimator;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.concurrent.CompletableFuture;

/** Avoid a second model request when the source already fits the tool result budget. */
@Mixin(value = UseSkillTool.class, remap = false)
public abstract class UseSkillCostMixin {
    @Shadow private String getKnowledge(SkillInstance selected, MaidAIChatManager manager) { throw new AssertionError(); }
    @Inject(method = "onCallAsync(Ljava/lang/String;Ljava/lang/String;Lcom/github/tartaricacid/touhoulittlemaid/ai/manager/entity/LLMCallback;Lcom/github/tartaricacid/touhoulittlemaid/ai/service/llm/LLMClient;)Ljava/util/concurrent/CompletableFuture;", at = @At("HEAD"), cancellable = true)
    private void aifun$shortKnowledge(String id, String name, LLMCallback callback, LLMClient client,
                                     CallbackInfoReturnable<CompletableFuture<LLMCallback>> cir) {
        SkillInstance skill = SkillLoader.getSkill(name);
        if (skill == null || !skill.isKnowledgeType()) return;
        String text = getKnowledge(skill, callback.getChatManager());
        var future = com.wjx.touhou_aifun.chat.agent.KnowledgeSummaryCache.summarize("skill:" + name, text, callback, client);
        CompletableFuture<LLMCallback> result = new CompletableFuture<>();
        future.whenComplete((value,error) -> callback.runOnServerThread(() -> result.complete(callback.addToolResult(
                error == null ? value : "knowledge_summary_failed: consult source again only if needed", id))));
        cir.setReturnValue(result);
    }
}
