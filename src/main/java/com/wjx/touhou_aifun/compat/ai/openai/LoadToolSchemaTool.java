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
import com.wjx.touhou_aifun.compat.ai.opencodego.OpenCodeGoLLMClient;

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
                    || client instanceof AnthropicCompatLLMClient
                    || client instanceof OpenCodeGoLLMClient;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public Parameter parameters(ObjectParameter root, EntityMaid maid) {
        StringParameter value = StringParameter.create();
        // The immutable per-turn directory is authoritative. Avoid rebuilding every third-party
        // trigger/schema here merely to duplicate a potentially huge enum in the meta-tool schema.
        value.setDescription("Exact missing extension tool id, or group:gui to load the GUI family together.");
        root.addProperties("tool_name", value);
        return root;
    }

    @Override
    public Codec<String> codec() { return CODEC; }

    @Override
    public LLMCallback onCall(String toolId, String result, LLMCallback callback) {
        if(com.wjx.touhou_aifun.chat.agent.AgentRuntime.enabled(callback.getMaid())
                && com.wjx.touhou_aifun.chat.agent.AgentExecution.foreground(callback)
                && ("group:gui".equals(result) || !com.wjx.touhou_aifun.chat.agent.AgentExecution.foregroundTool(result)))
            return callback.addToolResult("foreground_action_boundary: GUI, scans and physical tools belong to the background executor. Use task_control once and return an acknowledgement; loading schemas here cannot grant execution permission.",toolId);
        if ("group:gui".equals(result)) {
            for (String id : com.wjx.touhou_aifun.compat.ai.action.GuiTool.IDS)
                if (ToolContextSelector.optionalAvailable(callback.getMaid(), callback, id)) ChatFlowManager.requestToolSchema(callback.getMaid().getUUID(), callback, id);
            return callback.addToolResult("GUI schemas loaded. Invoke directly; do not load again in this task.", toolId);
        }
        if (ChatFlowManager.requestedToolIds(callback.getMaid().getUUID(), callback).contains(result))
            return callback.addToolResult("Already loaded: " + result + ". Invoke directly.", toolId);
        ITool<?> tool = ToolRegister.getTool(result);
        if (tool == null || ToolContextSelector.isCore(result)
                || !ToolContextSelector.optionalAvailable(callback.getMaid(), callback, result)) {
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
