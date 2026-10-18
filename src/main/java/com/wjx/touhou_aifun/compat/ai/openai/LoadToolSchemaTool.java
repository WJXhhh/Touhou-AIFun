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
        return "Load an exact missing tool schema for the next request. Invoke tools already present directly; task world/GUI schemas change automatically with the execution stage.";
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
        value.setDescription("Exact missing extension tool id, group:gui for GUI tools, or group:task_actions for optional executor action-plan syntax.");
        root.addProperties("tool_name", value);
        return root;
    }
    static Parameter taskParameters(java.util.Collection<String> registeredIds) {
        var root=ObjectParameter.create();var name=StringParameter.create().setDescription("Exact registered missing tool id. GUI action names such as craft_recipe are NOT tool ids; open the workbench to receive gui_action automatically. Load only capabilities needed for an owner requirement.");
        // Use the already frozen catalogue; never rerun third-party trigger/schema hooks.
        // Large extension registries keep free-text loading rather than an unbounded enum.
        if(registeredIds.size()<=96)name.addEnumValues(registeredIds.stream().sorted().toArray(String[]::new));
        root.addProperties("tool_name",name,true);return root;
    }

    @Override
    public Codec<String> codec() { return CODEC; }

    @Override
    public LLMCallback onCall(String toolId, String result, LLMCallback callback) {
        if ("group:task_actions".equals(result)) {
            if (!(callback instanceof com.wjx.touhou_aifun.chat.agent.TaskCallback task) || !task.decisionServiceEnabled())
                return callback.addToolResult("task_action_decisions_unavailable", toolId);
            if (task.task.loadedTools.add(result)) {
                int policyIndex=0;
                while(policyIndex<callback.getMessages().size() && callback.getMessages().get(policyIndex).role()==com.github.tartaricacid.touhoulittlemaid.ai.service.llm.Role.SYSTEM)policyIndex++;
                callback.getMessages().add(policyIndex,com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage.systemChat(callback.getMaid(),com.wjx.touhou_aifun.chat.agent.AgentTaskInstructions.DECISION_POLICY));
                ToolContextSelector.clearSnapshot(callback);
            }
            return callback.addToolResult("Optional action_steps syntax loaded. Plan only the next useful executable segment; normal tool calls remain available. Never rewrite the full task just to use decisions.", toolId);
        }
        if(com.wjx.touhou_aifun.chat.agent.AgentRuntime.enabled(callback.getMaid())
                && com.wjx.touhou_aifun.chat.agent.AgentExecution.foreground(callback)
                && ("group:gui".equals(result) || !com.wjx.touhou_aifun.chat.agent.AgentExecution.foregroundTool(result)))
            return callback.addToolResult("foreground_action_boundary: GUI and physical actions belong to the background executor. Read-only scan_surroundings and queries may run directly in chat. For physical work use task_control once and return an acknowledgement; loading schemas here cannot grant action permission.",toolId);
        if ("group:gui".equals(result)) {
            if (callback instanceof com.wjx.touhou_aifun.chat.agent.TaskCallback task) {
                task.task.loadedTools.addAll(java.util.Arrays.asList(com.wjx.touhou_aifun.compat.ai.action.GuiTool.IDS));
                return callback.addToolResult("GUI schemas are selected automatically for the task stage. Invoke those present directly; do not load group:gui again.", toolId);
            }
            for (String id : com.wjx.touhou_aifun.compat.ai.action.GuiTool.IDS)
                if (ToolContextSelector.optionalAvailable(callback.getMaid(), callback, id)) ChatFlowManager.requestToolSchema(callback.getMaid().getUUID(), callback, id);
            return callback.addToolResult("GUI schemas loaded. Invoke directly; do not load again in this task.", toolId);
        }
        if (callback instanceof com.wjx.touhou_aifun.chat.agent.TaskCallback task) {
            if (ToolContextSelector.selected(callback.getMaid(), callback).stream().anyMatch(entry -> entry.id().equals(result)))
                return callback.addToolResult("Already present: " + result + ". Invoke it directly.", toolId);
            if (!com.wjx.touhou_aifun.chat.agent.TaskToolSelection.eligible(task.toolSelection(), result))
                return callback.addToolResult("Tool deferred until fresh observation and a completion contract where required: " + result, toolId);
        }
        if (!(callback instanceof com.wjx.touhou_aifun.chat.agent.TaskCallback)
                && ChatFlowManager.requestedToolIds(callback.getMaid().getUUID(), callback).contains(result))
            return callback.addToolResult("Already loaded: " + result + ". Invoke directly.", toolId);
        ITool<?> tool = ToolRegister.getTool(result);
        if (tool == null || (ToolContextSelector.isCore(result) && !(callback instanceof com.wjx.touhou_aifun.chat.agent.TaskCallback))
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
