package com.wjx.touhou_aifun.chat.agent;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** A small request menu derived from server facts, with explicit expansion for unusual tasks. */
public final class TaskToolSelection {
    public enum Stage { observe, plan, execute, verify }

    public record Context(boolean freshWorld, boolean completionDefined, boolean conditionsMet,
                          boolean guiOpen, boolean recovering, Set<String> requested) {
        public Context { requested = Set.copyOf(requested); }
        public Stage stage() {
            if (!freshWorld) return Stage.observe;
            if (!completionDefined) return Stage.plan;
            return conditionsMet ? Stage.verify : Stage.execute;
        }
    }

    private static final Set<String> PHASE_TOOLS = Set.of("scan_surroundings", "read_task_result",
            "update_task_plan", "load_tool_schema", "open_gui", "inspect_gui", "inspect_containers",
            "gui_action", "gui_batch", "wait_gui", "close_gui");
    private static final Set<String> GUI_MUTATIONS = Set.of("gui_action", "gui_batch", "wait_gui");

    private TaskToolSelection() { }

    public static boolean phaseTool(String id) { return PHASE_TOOLS.contains(id); }

    public static boolean eligible(Context context, String id) {
        if (id.equals("task_control")) return false;
        if (!context.freshWorld() && (ToolExecutionPolicy.requiresFreshWorld(id) || id.equals("update_task_plan"))) return false;
        // An open planning menu can register completion and execute in the SAME ordered batch.
        // TaskCallback still rejects any mutation dispatched before that registration.
        return context.completionDefined() || (context.freshWorld() && context.guiOpen()) || !GUI_MUTATIONS.contains(id);
    }

    public static List<String> select(Context context, Set<String> loaded, Set<String> available) {
        var selected = new LinkedHashSet<String>();
        switch (context.stage()) {
            case observe -> selected.addAll(List.of("scan_surroundings", "query_game_context", "read_task_result", "load_tool_schema"));
            case plan -> {
                selected.addAll(List.of("update_task_plan", "load_tool_schema", "open_gui", "inspect_containers"));
                if (context.guiOpen()) selected.addAll(List.of("inspect_gui", "gui_action", "gui_batch", "wait_gui", "close_gui"));
                else selected.addAll(List.of("read_task_result", "scan_surroundings"));
            }
            case execute -> {
                selected.addAll(List.of("update_task_plan", "load_tool_schema", "open_gui"));
                if (context.guiOpen()) selected.addAll(List.of("inspect_gui", "gui_action", "gui_batch", "wait_gui", "close_gui"));
                else selected.addAll(List.of("scan_surroundings", "read_task_result"));
            }
            case verify -> {
                selected.addAll(List.of("update_task_plan", "load_tool_schema", "read_task_result"));
                if (context.guiOpen()) selected.addAll(List.of("inspect_gui", "close_gui"));
            }
        }
        if (context.recovering()) selected.addAll(List.of("scan_surroundings", "read_task_result"));
        // Persist extension schemas, but an old GUI group must not restore every GUI action forever.
        loaded.stream().sorted().filter(id -> !phaseTool(id)).forEach(selected::add);
        // Explicit expansion lasts for this stage, so extra requirements do not reload after every action.
        context.requested().stream().sorted().forEach(selected::add);
        selected.removeIf(id -> !available.contains(id) || !eligible(context, id));
        return List.copyOf(selected);
    }
}
