package com.wjx.touhou_aifun.compat.ai.openai;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ITool;
import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ToolRegister;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.ObjectParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.Parameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.StringParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMClient;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.serialization.Codec;
import com.wjx.touhou_aifun.chat.ChatFlowManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Stable meta-tool that requests a full schema for one extension tool on the next agent turn. */
public final class LoadToolSchemaTool implements ITool<String> {
    private static final Codec<String> CODEC = Codec.STRING.fieldOf("tool_name").codec();

    @Override
    public String id() { return ToolContextSelector.LOAD_SCHEMA_TOOL; }

    @Override
    public String summary(EntityMaid maid) {
        return "Load one optional extension tool schema before using that tool.";
    }

    /** Keep this addon-only meta tool out of TLM's native clients. */
    @Override
    public boolean trigger(EntityMaid maid,
                           com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.request.ChatCompletion ignored) {
        try {
            LLMClient client = maid.getAiChatManager().getLLMSite().client();
            return client instanceof ReasoningCompatOpenAIClient
                    || client instanceof AnthropicCompatLLMClient;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public Parameter parameters(ObjectParameter root, EntityMaid maid) {
        StringParameter value = StringParameter.create();
        value.setDescription("Exact extension tool id from the optional tool directory.");
        List<String> ids = new ArrayList<>();
        for (Map.Entry<String, ITool<?>> entry : ToolRegister.getAllTools().entrySet()) {
            if (!ToolContextSelector.isCore(entry.getKey()) && entry.getValue() != null
                    && ToolContextSelector.isTriggered(maid, entry.getValue())) ids.add(entry.getKey());
        }
        if (!ids.isEmpty()) value.addEnumValues(ids.toArray(String[]::new));
        root.addProperties("tool_name", value);
        return root;
    }

    @Override
    public Codec<String> codec() { return CODEC; }

    @Override
    public LLMCallback onCall(String toolId, String result, LLMCallback callback) {
        ITool<?> tool = ToolRegister.getTool(result);
        if (tool == null || ToolContextSelector.isCore(result)
                || !ToolContextSelector.isTriggered(callback.getMaid(), tool)) {
            return callback.addToolResult("Unknown optional extension tool: " + result, toolId);
        }
        ChatFlowManager.requestToolSchema(callback.getMaid().getUUID(), callback, result);
        return callback.addToolResult("Schema loaded for " + result + ". Invoke it on the next turn.", toolId);
    }

    @Override
    public CompletableFuture<LLMCallback> onCallAsync(String toolId, String result,
                                                       LLMCallback callback, LLMClient client) {
        return CompletableFuture.completedFuture(onCall(toolId, result, callback));
    }

    @Override
    public String invocationSummary(String result) {
        return "load_tool_schema { " + result + " }";
    }
}
