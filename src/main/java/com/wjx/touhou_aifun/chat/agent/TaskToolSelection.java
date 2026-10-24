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
            "update_task_plan", "load_tool_schema", "open_gui", "inspect_gui", "inspect_containers", "query_recipes",
            "gui_action", "gui_batch", "wait_gui", "close_gui", "drop_items_to_owner");
    private static final Set<String> GUI_MUTATIONS = Set.of("gui_action", "gui_batch", "wait_gui", "drop_items_to_owner", "block_action");
    private static final Set<String> ALLOWLIST_RECOVERY = Set.of("update_task_plan", "load_tool_schema", "read_task_result");
    private static final Set<String> OPEN_MENU_TOOLS = Set.of("gui_action", "gui_batch", "wait_gui", "inspect_gui", "close_gui");
    private static final Set<String> READ_ONLY_SELECTION = Set.of("open_gui", "inspect_gui", "inspect_containers", "query_recipes",
            "scan_surroundings", "query_game_context", "close_gui", "read_task_result", "load_tool_schema", "update_task_plan");

    private TaskToolSelection() { }

    public static boolean phaseTool(String id) { return PHASE_TOOLS.contains(id); }

    /** A single inventory milestone is normally one craft, not a recurring tool phase. */
    public static boolean oneShotItemPhase(com.google.gson.JsonObject completion, Set<String> ids) {
        var leaves=AgentCompletionRules.leaves(completion);
        return !leaves.isEmpty() && leaves.stream().anyMatch(c->AgentTaskState.completionKind(c).equals("inventory") && c.get("count").getAsLong()>0)
                && leaves.stream().noneMatch(c->AgentTaskState.completionKind(c).equals("transfer"))
                && ids.stream().allMatch(PHASE_TOOLS::contains);
    }

    /** Infer a loop from committed receipts and outstanding container goals, never goal prose. */
    public static Set<String> repeatedTransferSelection(com.google.gson.JsonObject completion, java.util.Map<String,Long> receipts) {
        var targets=new java.util.HashMap<String,Boolean>();
        Boolean direction=null;
        for(var leaf:AgentCompletionRules.leaves(completion)) {
            String kind=AgentTaskState.completionKind(leaf);
            if(!kind.equals("transfer")) {
                if(!Set.of("inventory","backpack_empty","maid_state").contains(kind))return Set.of();
                continue;
            }
            boolean receive=leaf.get("to_maid").getAsBoolean();
            if(direction!=null && direction!=receive)return Set.of();
            direction=receive;
            long done=receipts.getOrDefault(AgentCompletionRules.transferKey(leaf),0L)*(receive?1:-1);
            boolean complete=done>=leaf.get("count").getAsLong();
            targets.merge(leaf.get("dimension")+":"+leaf.get("position"),complete,(a,b)->a&&b);
        }
        long done=targets.values().stream().filter(Boolean::booleanValue).count();
        if(done<2 || targets.size()-done<4)return Set.of();
        return Set.of("open_gui","gui_batch");
    }

    /** Complete an optional hint, never the owner's conditions or action receipts. */
    public static Set<String> executableSelection(Set<String> ids, com.google.gson.JsonObject activeCompletion) {
        if(ids.isEmpty())return ids;
        var leaves=AgentCompletionRules.leaves(activeCompletion);
        boolean inventory=leaves.stream().anyMatch(c->AgentTaskState.completionKind(c).equals("inventory") && c.get("count").getAsLong()>0);
        var selected=new java.util.TreeSet<>(ids);
        if(leaves.stream().anyMatch(c->AgentTaskState.completionKind(c).equals("drop_to_owner")))selected.add("drop_items_to_owner");
        // A recipe query cannot produce inventory. Retain the actual workbench operation
        // even when a model only names preparation tools for a manufacturing phase.
        if(inventory && ids.contains("query_recipes"))selected.addAll(Set.of("open_gui","gui_action"));
        boolean items=inventory || leaves.stream().anyMatch(c->AgentTaskState.completionKind(c).equals("transfer") && c.get("count").getAsLong()>0);
        // A known read-only set cannot finish item work. Restore the normal stage menu;
        // unknown extension operations remain explicit capabilities, not guessed dependencies.
        if(items && READ_ONLY_SELECTION.containsAll(selected))return Set.of();
        return Set.copyOf(selected);
    }

    public static boolean eligible(Context context, String id) {
        if (id.equals("task_control")) return false;
        if (!context.freshWorld() && (ToolExecutionPolicy.requiresFreshWorld(id) || id.equals("update_task_plan"))) return false;
        // An open planning menu can register completion and execute in the SAME ordered batch.
        // TaskCallback still rejects any mutation dispatched before that registration.
        return context.completionDefined() || (context.freshWorld() && context.guiOpen()) || !GUI_MUTATIONS.contains(id);
    }

    public static List<String> select(Context context, Set<String> loaded, Set<String> available) {
        return select(context,loaded,available,null);
    }
    /** A step-local allowlist narrows schemas, never grants a tool that the stage cannot use. */
    public static List<String> select(Context context, Set<String> loaded, Set<String> available, Set<String> allowedTools) {
        var selected = new LinkedHashSet<String>();
        switch (context.stage()) {
            case observe -> selected.addAll(List.of("scan_surroundings", "query_game_context", "query_recipes", "read_task_result", "load_tool_schema"));
            case plan -> {
                selected.addAll(List.of("update_task_plan", "load_tool_schema", "query_recipes", "open_gui", "inspect_containers"));
                if (context.guiOpen()) selected.addAll(List.of("inspect_gui", "gui_action", "gui_batch", "wait_gui", "close_gui"));
                else selected.addAll(List.of("read_task_result", "scan_surroundings"));
            }
            case execute -> {
                selected.addAll(List.of("update_task_plan", "load_tool_schema", "query_recipes", "open_gui"));
                // Stable execution schemas allow the next open/action in the same response.
                // Native session, revision, freshness and quantity checks still authorize effects.
                selected.addAll(List.of("inspect_gui", "gui_action", "gui_batch", "wait_gui", "close_gui"));
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
        if(allowedTools!=null && !context.recovering()) {
            // Keep a small execution-phase menu identical across opening/closing. GUI tools
            // still require a real current session/revision at dispatch; a schema grants no access.
            allowedTools.stream().sorted().filter(id->context.guiOpen() || context.stage()==Stage.execute || !OPEN_MENU_TOOLS.contains(id)).forEach(selected::add);
            selected.addAll(new java.util.TreeSet<>(ALLOWLIST_RECOVERY));
            if(!context.freshWorld())selected.add("scan_surroundings");
            selected.removeIf(id->!allowedTools.contains(id) && !ALLOWLIST_RECOVERY.contains(id) && !context.requested().contains(id)
                    && !(id.equals("scan_surroundings") && !context.freshWorld()));
        }
        selected.removeIf(id -> !available.contains(id) || !eligible(context, id));
        if(allowedTools!=null && !context.recovering())return selected.stream().sorted().toList();
        return List.copyOf(selected);
    }
}
