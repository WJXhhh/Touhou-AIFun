package com.wjx.touhou_aifun.chat.agent;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMClient;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMSite;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.LLMOpenAISite;
import com.wjx.touhou_aifun.compat.ai.openai.AnthropicCompatLLMClient;
import com.wjx.touhou_aifun.compat.ai.openai.ReasoningCompatOpenAIClient;
import com.wjx.touhou_aifun.compat.ai.opencodego.OpenCodeGoLLMClient;
import com.wjx.touhou_aifun.vision.ModelRef;
import com.wjx.touhou_aifun.vision.UnifiedModelCatalog;
import java.util.ArrayList;
import java.util.List;

/** A task-local selection, never a mutation of the maid's ordinary chat model or credentials. */
public final class AgentDecisionModels {
    public record Target(LLMSite site, String model) { }
    public record Option(String id, String label, boolean usable) { }
    private AgentDecisionModels() { }

    public static boolean supported(LLMClient client) {
        return client instanceof ReasoningCompatOpenAIClient || client instanceof AnthropicCompatLLMClient
                || client instanceof OpenCodeGoLLMClient;
    }
    public static Target resolve(String selection, LLMSite fallback, String fallbackModel) {
        if (selection == null || selection.isBlank()) return new Target(fallback, fallbackModel);
        ModelRef ref = ModelRef.decode(selection);
        LLMSite site = UnifiedModelCatalog.site(ref);
        if (site == null || !UnifiedModelCatalog.usable(ref) || !supported(site.client()))
            throw new IllegalArgumentException("decision_model_unavailable");
        return new Target(site, ref.modelId());
    }
    public static boolean selectable(String selection) {
        if (selection == null || selection.isBlank()) return true;
        try { resolve(selection, null, null); return true; }
        catch (RuntimeException ignored) { return false; }
    }
    public static List<Option> options() {
        List<Option> result = new ArrayList<>();
        new java.util.TreeMap<>(com.github.tartaricacid.touhoulittlemaid.ai.manager.site.AvailableSites.LLM_SITES)
                .forEach((id, site) -> {
                    if (!(site instanceof LLMOpenAISite openAI)) return;
                    new java.util.TreeMap<>(openAI.models()).forEach((model, label) -> {
                        var ref = new ModelRef(id, model);
                        if (ref.encode().length() <= 1024)
                            result.add(new Option(ref.encode(), id + " / " + label, selectable(ref.encode())));
                    });
                });
        return List.copyOf(result);
    }
    public static String model(LLMCallback callback) {
        LLMCallback parent = KnowledgeSummaryCache.parent(callback);
        if (parent != null) return model(parent);
        return callback instanceof TaskCallback task && task.decisionTarget != null
                ? task.decisionTarget.model() : callback.getChatManager().getLLMModel();
    }
    public static LLMSite site(LLMCallback callback) {
        LLMCallback parent = KnowledgeSummaryCache.parent(callback);
        if (parent != null) return site(parent);
        return callback instanceof TaskCallback task && task.decisionTarget != null
                ? task.decisionTarget.site() : callback.getChatManager().getLLMSite();
    }
}
