package com.wjx.touhou_aifun.compat.ai.openai;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ITool;
import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ToolRegister;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.FunctionTool;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.ObjectParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.Parameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMClient;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.Role;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.request.ChatCompletion;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.Gson;
import com.wjx.touhou_aifun.chat.ChatFlowManager;
import com.wjx.touhou_aifun.chat.context.ContextTokenEstimator;
import com.wjx.touhou_aifun.compat.ai.opencodego.OpenCodeGoLLMClient;
import com.wjx.touhou_aifun.compat.ai.action.EatFoodBlockTool;
import com.wjx.touhou_aifun.compat.ai.time.CurrentDateTimeTool;
import com.wjx.touhou_aifun.compat.ai.web.WebSearchTool;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Owns the immutable tool catalog associated with one ordinary callback/turn. */
public final class ToolContextSelector {
    private static final Gson GSON = new Gson();
    public static final String LOAD_SCHEMA_TOOL = "load_tool_schema";
    private static final List<String> CORE_TOOLS = List.of(
            "use_skill", "query_minecraft_wiki", "query_game_context",
            "switch_follow_state", "switch_work_task", "switch_schedule", "switch_sit",
            EatFoodBlockTool.TOOL_ID,
            LOAD_SCHEMA_TOOL, WebSearchTool.TOOL_ID, CurrentDateTimeTool.TOOL_ID);
    private static final Set<String> CORE_TOOL_SET = Set.copyOf(CORE_TOOLS);
    private static final Map<Object, ToolCatalogSnapshot> SNAPSHOTS = new ConcurrentHashMap<>();

    private ToolContextSelector() {
    }

    public static ToolCatalogSnapshot snapshot(EntityMaid maid, Object callback) {
        return SNAPSHOTS.computeIfAbsent(callback,
                ignored -> ToolCatalogSnapshot.capture(maid, triggerContext(maid, callback)));
    }

    public static void clearSnapshot(Object callback) {
        if (callback != null) SNAPSHOTS.remove(callback);
    }

    public static void clearAllSnapshots() {
        SNAPSHOTS.clear();
    }

    public static List<String> coreToolIds() {
        return CORE_TOOLS;
    }

    public static boolean isCore(String id) {
        return CORE_TOOL_SET.contains(id);
    }

    public static int schemaBudget(EntityMaid maid, Object callback) {
        return snapshot(maid, callback).schemaBudget(ChatFlowManager.requestedToolIds(maid.getUUID(), callback));
    }

    public static List<ToolCatalogSnapshot.Entry> selected(EntityMaid maid, Object callback) {
        return snapshot(maid, callback).selected(ChatFlowManager.requestedToolIds(maid.getUUID(), callback));
    }

    public static String compactDirectory(EntityMaid maid, Object callback) {
        return snapshot(maid, callback).directory();
    }

    public static boolean optionalAvailable(EntityMaid maid, Object callback, String id) {
        return snapshot(maid, callback).hasOptional(id);
    }

    /**
     * The base client keeps its own full-tool construction logic, so it needs a conservative reserve
     * independent from AIFun's frozen compact selector.
     */
    public static int requestSchemaBudget(EntityMaid maid, Object callback) {
        if (usesAIFunClient(maid)) return schemaBudget(maid, callback);

        int tokens = 0;
        for (var entry : ToolRegister.getAllTools().entrySet()) {
            ITool<?> tool = entry.getValue();
            if (tool == null) continue;
            try {
                ObjectParameter root = ObjectParameter.create();
                Parameter parameter = tool.parameters(root, maid);
                Object schema = FunctionTool.create().setName(entry.getKey())
                        .setDescription(tool.summary(maid)).setParameters(parameter).build();
                tokens += ContextTokenEstimator.estimate(GSON.toJson(schema));
            } catch (RuntimeException ignored) {
                tokens += 256;
            }
        }
        return Math.max(4096, tokens);
    }

    /** AIFun clients plan inside every agent round; native clients need constructor-time planning. */
    public static boolean usesAIFunClient(EntityMaid maid) {
        try {
            LLMClient client = maid.getAiChatManager().getLLMSite().client();
            return client instanceof ReasoningCompatOpenAIClient
                    || client instanceof AnthropicCompatLLMClient
                    || client instanceof OpenCodeGoLLMClient;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /** Build the non-null request context required by the base ITool.trigger contract. */
    private static ChatCompletion triggerContext(EntityMaid maid, Object callback) {
        ChatCompletion context = ChatCompletion.create().model(maid.getAiChatManager().getLLMModel());
        if (!(callback instanceof LLMCallback llm)) return context;
        for (LLMMessage message : llm.getMessages()) {
            if (message.role() == Role.USER) context.userChat(message.message());
            else if (message.role() == Role.SYSTEM) context.systemChat(message.message());
            else if (message.role() == Role.TOOL) context.toolChat(message.message(), message.toolCallId());
            else if (message.role() == Role.ASSISTANT) {
                if (message.toolCalls() == null || message.toolCalls().isEmpty()) {
                    context.assistantChat(message.message());
                } else {
                    context.assistantChat(message.message(), message.toolCalls());
                }
            }
        }
        return context;
    }
}
