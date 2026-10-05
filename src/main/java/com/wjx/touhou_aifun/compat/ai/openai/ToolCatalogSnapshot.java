package com.wjx.touhou_aifun.compat.ai.openai;

import com.github.tartaricacid.touhoulittlemaid.TouhouLittleMaid;
import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ITool;
import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ToolRegister;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.FunctionTool;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.ObjectParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.Parameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.request.ChatCompletion;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.request.Tool;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.wjx.touhou_aifun.chat.context.ContextTokenEstimator;
import com.wjx.touhou_aifun.chat.context.ToolSelectionPolicy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable per-turn view of the tool registry. Third-party hooks are evaluated exactly once;
 * OpenAI, Anthropic, the compact directory and the budget all consume the same frozen schemas.
 */
public final class ToolCatalogSnapshot {
    private static final Gson GSON = new Gson();
    private static final int ADJACENT_PROMPT_RESERVE = 512;

    public record Entry(String id, ITool<?> implementation, String summary,
                        Tool openAITool, JsonObject anthropicTool, int schemaTokens) {
    }

    private final Map<String, Entry> entries;
    private final List<String> optionalIds;
    private final String directory;

    private ToolCatalogSnapshot(Map<String, Entry> entries, List<String> optionalIds, String directory) {
        this.entries = Map.copyOf(entries);
        this.optionalIds = List.copyOf(optionalIds);
        this.directory = directory;
    }

    public static ToolCatalogSnapshot capture(EntityMaid maid, ChatCompletion triggerContext, Object callback) {
        Map<String, Entry> entries = new LinkedHashMap<>();
        List<String> optional = new ArrayList<>();
        StringBuilder directory = new StringBuilder("## Optional tool directory\n"
                + "Tools already present in the request are loaded: invoke them directly. Load only missing optional tools; group:gui loads all GUI tools.\n");

        new java.util.TreeMap<>(ToolRegister.getAllTools()).forEach((id, tool) -> {
            if (tool == null) return;
            if (id.equals("update_task_plan") && !(callback instanceof com.wjx.touhou_aifun.chat.agent.TaskCallback)) return;
            if (callback instanceof com.wjx.touhou_aifun.chat.agent.TaskCallback && id.equals("task_control")) return;
            if (com.wjx.touhou_aifun.chat.agent.AgentRuntime.enabled(maid)
                    && com.wjx.touhou_aifun.chat.agent.AgentExecution.foreground(callback)
                    && !com.wjx.touhou_aifun.chat.agent.AgentExecution.foregroundTool(id)) return;
            try {
                if (!tool.trigger(maid, triggerContext)) return;
                String summary = tool.summary(maid);
                ObjectParameter root = ObjectParameter.create();
                Parameter parameter = tool.parameters(root, maid);
                Tool openAI = FunctionTool.create().setName(id).setDescription(summary)
                        .setParameters(parameter).build();
                JsonObject anthropic = new JsonObject();
                anthropic.addProperty("name", id);
                anthropic.addProperty("description", summary);
                anthropic.add("input_schema", GSON.toJsonTree(parameter));
                int schemaTokens = Math.max(ContextTokenEstimator.estimate(GSON.toJson(openAI)),
                        ContextTokenEstimator.estimate(GSON.toJson(anthropic)));
                entries.put(id, new Entry(id, tool, summary, openAI, anthropic, schemaTokens));
                if (!ToolContextSelector.isCore(id)) {
                    optional.add(id);
                    directory.append("- ").append(id).append(": ").append(compact(summary, 180)).append('\n');
                }
            } catch (RuntimeException e) {
                TouhouLittleMaid.LOGGER.warn("Skipping broken tool schema '{}' for this AIFun turn: {}",
                        id, e.getMessage());
            }
        });
        return new ToolCatalogSnapshot(entries, optional, directory.toString().trim());
    }

    public String directory() {
        return directory;
    }

    public List<String> optionalIds() {
        return optionalIds;
    }

    public boolean hasOptional(String id) {
        return optionalIds.contains(id);
    }

    public List<Entry> selected(Set<String> requestedIds) {
        List<Entry> selected = new ArrayList<>();
        ToolSelectionPolicy.select(ToolContextSelector.coreToolIds(), optionalIds,
                        requestedIds, entries.keySet())
                .forEach(id -> selected.add(entries.get(id)));
        return List.copyOf(selected);
    }

    /** Includes the actual frozen schemas, compact directory and adjacent reminder reserve. */
    public int schemaBudget(Set<String> requestedIds) {
        int tokens = ContextTokenEstimator.estimate(directory)
                + ADJACENT_PROMPT_RESERVE;
        for (Entry entry : selected(requestedIds)) tokens += entry.schemaTokens();
        return Math.max(512, tokens);
    }

    private static String compact(String text, int maxCodePoints) {
        String value = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        int count = value.codePointCount(0, value.length());
        if (count <= maxCodePoints) return value;
        return value.substring(0, value.offsetByCodePoints(0, maxCodePoints)) + "...";
    }
}
