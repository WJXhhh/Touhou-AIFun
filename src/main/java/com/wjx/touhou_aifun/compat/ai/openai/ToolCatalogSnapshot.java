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
    private final com.wjx.touhou_aifun.chat.agent.TaskCallback task;

    private ToolCatalogSnapshot(Map<String, Entry> entries, List<String> optionalIds, String directory,
                                com.wjx.touhou_aifun.chat.agent.TaskCallback task) {
        this.entries = Map.copyOf(entries);
        this.optionalIds = List.copyOf(optionalIds);
        this.directory = directory;
        this.task = task;
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
                if (!ToolContextSelector.triggers(tool, maid, callback, triggerContext)) return;
                boolean taskExecutor = callback instanceof com.wjx.touhou_aifun.chat.agent.TaskCallback;
                String summary = tool instanceof com.wjx.touhou_aifun.compat.ai.action.GuiTool gui
                        ? gui.summary(maid, taskExecutor) : tool.summary(maid);
                ObjectParameter root = ObjectParameter.create();
                Parameter parameter = tool instanceof com.wjx.touhou_aifun.chat.agent.TaskPlanningTool planner
                        && callback instanceof com.wjx.touhou_aifun.chat.agent.TaskCallback executor
                        ? planner.parameters(root,maid,executor.decisionServiceEnabled() && executor.task.loadedTools.contains("group:task_actions"),executor.task.completion!=null)
                        : tool instanceof com.wjx.touhou_aifun.compat.ai.action.GuiTool gui
                            ? gui.parameters(root, maid, taskExecutor,callback instanceof com.wjx.touhou_aifun.chat.agent.TaskCallback selectedTask && selectedTask.allowedTools()!=null) : tool.parameters(root, maid);
                entries.put(id, entry(id,tool,summary,parameter));
                if (!ToolContextSelector.isCore(id)) {
                    optional.add(id);
                    directory.append("- ").append(id).append(": ").append(compact(summary, 180)).append('\n');
                }
            } catch (RuntimeException e) {
                TouhouLittleMaid.LOGGER.warn("Skipping broken tool schema '{}' for this AIFun turn: {}",
                        id, e.getMessage());
            }
        });
        if(callback instanceof com.wjx.touhou_aifun.chat.agent.TaskCallback executor && entries.containsKey(ToolContextSelector.LOAD_SCHEMA_TOOL)
                && entries.get(ToolContextSelector.LOAD_SCHEMA_TOOL).implementation() instanceof LoadToolSchemaTool) {
            var loader=entries.get(ToolContextSelector.LOAD_SCHEMA_TOOL);
            var ids=new java.util.TreeSet<>(entries.keySet());ids.add("group:gui");
            if(executor.decisionServiceEnabled())ids.add("group:task_actions");
            entries.put(loader.id(),entry(loader.id(),loader.implementation(),loader.summary(),LoadToolSchemaTool.taskParameters(ids)));
        }
        return new ToolCatalogSnapshot(entries, optional, directory.toString().trim(),
                callback instanceof com.wjx.touhou_aifun.chat.agent.TaskCallback task ? task : null);
    }
    private static Entry entry(String id,ITool<?> tool,String summary,Parameter parameter) {
        Tool openAI=FunctionTool.create().setName(id).setDescription(summary).setParameters(parameter).build();
        var anthropic=new JsonObject();anthropic.addProperty("name",id);anthropic.addProperty("description",summary);anthropic.add("input_schema",GSON.toJsonTree(parameter));
        int tokens=Math.max(ContextTokenEstimator.estimate(GSON.toJson(openAI)),ContextTokenEstimator.estimate(GSON.toJson(anthropic)));
        return new Entry(id,tool,summary,openAI,anthropic,tokens);
    }

    public String directory() {
        if (task != null) {
            var context = task.toolSelection();
            var direct = selected(task.task.loadedTools).stream().map(Entry::id).toList();
            StringBuilder out = new StringBuilder("## Task tool menu\nStage: ").append(context.stage())
                    .append(". Direct tools: ").append(direct).append(".\n")
                    .append("Tools follow the current stage and active selection; do not load tools already present. ")
                    .append("If an additional owner requirement needs a missing tool, load its exact id using load_tool_schema. ")
                    .append("Verification is a tool-selection stage, not a completion declaration.\n");
            if(task.allowedTools()!=null) out.append("Step allowed_tools active.\n");
            if(!context.guiOpen())out.append("Menu closed: open_gui first, then use its NEW session_id/revision. Listed GUI actions do not imply an open session; old ids are invalid. Craft with gui_action(action=craft_recipe,recipe_id,count); craft_recipe is not a standalone tool.\n");
            var deferred = new java.util.TreeMap<>(entries);
            direct.forEach(deferred::remove);
            var phaseIds = deferred.keySet().stream()
                    .filter(com.wjx.touhou_aifun.chat.agent.TaskToolSelection::phaseTool)
                    .filter(id -> com.wjx.touhou_aifun.chat.agent.TaskToolSelection.eligible(context, id)).toList();
            if (!phaseIds.isEmpty()) out.append("Other world/GUI tools (load only when needed): ").append(phaseIds).append(".\n");
            deferred.forEach((id, entry) -> {
                if (!com.wjx.touhou_aifun.chat.agent.TaskToolSelection.phaseTool(id)
                        && com.wjx.touhou_aifun.chat.agent.TaskToolSelection.eligible(context, id))
                    out.append("- ").append(id).append(": ").append(compact(entry.summary(), 180)).append('\n');
            });
            return out.toString().trim();
        }
        return directory;
    }

    public List<String> optionalIds() {
        return optionalIds;
    }

    /** Decision templates may use any currently eligible frozen tool, without loading its LLM schema first. */
    public List<Entry> decisionEntries() {
        return new java.util.TreeMap<>(entries).values().stream()
                .filter(e -> task == null || com.wjx.touhou_aifun.chat.agent.TaskToolSelection.eligible(task.toolSelection(),e.id())).toList();
    }
    /** Planning may describe a post-contract action; dispatch still uses decisionEntries and runtime guards. */
    public List<Entry> planningEntries() {return new java.util.TreeMap<>(entries).values().stream().toList();}

    public boolean hasOptional(String id) {
        return task != null ? entries.containsKey(id) : optionalIds.contains(id);
    }

    /** Compare frozen definitions only; never rerun extension hooks to evaluate a hint. */
    public boolean restrictionSavesSchema(Set<String> ids) {
        if(task==null)return false;
        var current=task.toolSelection();
        // Opening a menu reveals its schemas without another selection call. Account for that
        // when evaluating a hint on an action which has just closed/opened a menu.
        for(boolean guiOpen:new boolean[]{current.guiOpen(),true}) {
            var context=new com.wjx.touhou_aifun.chat.agent.TaskToolSelection.Context(current.freshWorld(),
                    task.task.completion!=null,current.conditionsMet(),guiOpen,current.recovering(),current.requested());
            var all=com.wjx.touhou_aifun.chat.agent.TaskToolSelection.select(context,task.task.loadedTools,entries.keySet());
            var narrowed=com.wjx.touhou_aifun.chat.agent.TaskToolSelection.select(context,task.task.loadedTools,entries.keySet(),ids);
            int before=all.stream().mapToInt(id->entries.get(id).schemaTokens()).sum();
            int after=narrowed.stream().mapToInt(id->entries.get(id).schemaTokens()).sum();
            if(before-after>=512 && (long)(before-after)*5>=before)return true;
        }
        return false;
    }

    public List<Entry> selected(Set<String> requestedIds) {
        List<Entry> selected = new ArrayList<>();
        var ids = task != null
                ? com.wjx.touhou_aifun.chat.agent.TaskToolSelection.select(task.toolSelection(), requestedIds, entries.keySet(),task.allowedTools())
                : ToolSelectionPolicy.select(ToolContextSelector.coreToolIds(), optionalIds, requestedIds, entries.keySet());
        ids.forEach(id -> selected.add(entries.get(id)));
        if(task!=null)selected.sort(java.util.Comparator.comparingInt((Entry entry)->switch(entry.id()) {
            // The planner changes shape on owner amendments. Keep it after stable operation
            // definitions so its full/registered variants do not split their common prefix.
            case "load_tool_schema" -> 0;case "open_gui" -> 1;case "read_task_result" -> 2;case "update_task_plan" -> 4;default -> 3;
        }).thenComparing(Entry::id));
        return List.copyOf(selected);
    }

    /** Includes the actual frozen schemas, compact directory and adjacent reminder reserve. */
    public int schemaBudget(Set<String> requestedIds) {
        int tokens = ContextTokenEstimator.estimate(directory())
                + ADJACENT_PROMPT_RESERVE;
        for (Entry entry : selected(requestedIds)) tokens += entry.schemaTokens();
        if(task!=null && task.allowedTools()!=null)
            tokens+=AllowedToolsRequestPolicy.reserve(selected(requestedIds).stream().map(Entry::id).toList());
        return Math.max(512, tokens);
    }

    private static String compact(String text, int maxCodePoints) {
        String value = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        int count = value.codePointCount(0, value.length());
        if (count <= maxCodePoints) return value;
        return value.substring(0, value.offsetByCodePoints(0, maxCodePoints)) + "...";
    }
}
