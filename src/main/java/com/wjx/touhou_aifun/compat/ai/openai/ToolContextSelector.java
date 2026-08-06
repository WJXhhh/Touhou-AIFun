package com.wjx.touhou_aifun.compat.ai.openai;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ITool;
import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ToolRegister;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.wjx.touhou_aifun.chat.context.ContextTokenEstimator;
import com.wjx.touhou_aifun.chat.ChatFlowManager;

import java.util.LinkedHashSet;
import java.util.Set;

/** Keeps the stable core tool set small while loading extension schemas only when requested. */
public final class ToolContextSelector {
    public static final String LOAD_SCHEMA_TOOL = "load_tool_schema";
    private static final Set<String> CORE_TOOLS = Set.of(
            "use_skill", "query_minecraft_wiki", "query_game_context",
            "switch_follow_state", "switch_work_task", "switch_schedule", "switch_sit",
            LOAD_SCHEMA_TOOL);

    private ToolContextSelector() {
    }

    public static Set<String> selected(EntityMaid maid, Object callback) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String id : CORE_TOOLS) {
            ITool<?> tool = ToolRegister.getTool(id);
            if (tool != null && isTriggered(maid, tool)) result.add(id);
        }
        result.addAll(ChatFlowManager.requestedToolIds(maid.getUUID(), callback));
        return result;
    }

    public static String compactDirectory(EntityMaid maid, String query) {
        StringBuilder out = new StringBuilder("## Optional tool directory\n"
                + "Core action tools are already available. For an extension tool, call load_tool_schema first.\n");
        ToolRegister.getAllTools().forEach((id, tool) -> {
            if (tool == null || CORE_TOOLS.contains(id) || !isTriggered(maid, tool)) return;
            String summary;
            try {
                summary = tool.summary(maid).replaceAll("\\s+", " ").trim();
            } catch (RuntimeException ignored) {
                summary = "Optional extension tool.";
            }
            if (summary.length() > 180) summary = summary.substring(0, 180) + "...";
            out.append("- ").append(id).append(": ").append(summary).append('\n');
        });
        return out.toString().trim();
    }

    public static boolean isCore(String id) {
        return CORE_TOOLS.contains(id);
    }

    /** Extension triggers are optional hooks; a null-sensitive hook must not break chat construction. */
    public static boolean isTriggered(EntityMaid maid, ITool<?> tool) {
        try {
            return tool.trigger(maid, null);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /** Conservative estimate for the schemas appended after the message planner runs. */
    public static int schemaBudget(EntityMaid maid, Object callback) {
        int tokens = ContextTokenEstimator.estimate(compactDirectory(maid, ""));
        for (String id : selected(maid, callback)) {
            ITool<?> tool = ToolRegister.getTool(id);
            if (tool == null || !isTriggered(maid, tool)) continue;
            String description;
            try {
                description = id + " " + tool.summary(maid);
            } catch (RuntimeException ignored) {
                description = id;
            }
            tokens += ContextTokenEstimator.estimate(description) + 96;
        }
        return Math.max(4096, Math.min(8192, tokens));
    }
}
