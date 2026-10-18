package com.wjx.touhou_aifun.chat.agent;

import org.junit.jupiter.api.Test;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class TaskToolSelectionTest {
    @Test void ordinaryExecutionMenuDoesNotChurnWhenClosingBetweenOneShotActions() {
        assertEquals(TaskToolSelection.select(context(true,true,false,true),GUI,ALL),
                TaskToolSelection.select(context(true,true,false,false),GUI,ALL));
    }
    @Test void nativeLoopRequiresCommittedDistinctTargetsAndEnoughOutstandingWork() {
        var condition=new com.google.gson.JsonObject();condition.addProperty("kind","all");
        var leaves=new com.google.gson.JsonArray();var receipts=new java.util.HashMap<String,Long>();
        for(int i=0;i<6;i++) {
            var leaf=com.google.gson.JsonParser.parseString("{\"kind\":\"transfer\",\"dimension\":\"minecraft:overworld\",\"position\":["+i+",1,2],\"item\":\"minecraft:iron_ingot\",\"count\":2,\"to_maid\":true}").getAsJsonObject();
            leaves.add(leaf);if(i<2)receipts.put(AgentCompletionRules.transferKey(leaf),2L);
        }
        condition.add("conditions",leaves);var before=condition.deepCopy();
        assertTrue(TaskToolSelection.repeatedTransferSelection(condition,java.util.Map.of()).isEmpty());
        assertEquals(Set.of("open_gui","gui_batch"),TaskToolSelection.repeatedTransferSelection(condition,receipts));
        receipts.put(AgentCompletionRules.transferKey(leaves.get(2).getAsJsonObject()),2L);
        assertTrue(TaskToolSelection.repeatedTransferSelection(condition,receipts).isEmpty());
        assertEquals(before,condition);
    }
    @Test void singleCraftKeepsOrdinaryMenuAndExtensionSelectionsRemainExplicit() {
        var inventory=com.google.gson.JsonParser.parseString("{\"kind\":\"inventory\",\"item\":\"minecraft:iron_ingot\",\"count\":24}").getAsJsonObject();
        assertTrue(TaskToolSelection.oneShotItemPhase(inventory,Set.of("gui_action","open_gui")));
        assertFalse(TaskToolSelection.oneShotItemPhase(inventory,Set.of("extension_work")));
    }
    @Test void activeExecutionSchemasStayIdenticalAcrossMenuOpeningAndClosing() {
        var allowed=Set.of("open_gui","close_gui","gui_action","gui_batch","inspect_gui","wait_gui");
        var closed=TaskToolSelection.select(context(true,true,false,false),GUI,ALL,allowed);
        var opened=TaskToolSelection.select(context(true,true,false,true),GUI,ALL,allowed);
        assertEquals(closed,opened);assertTrue(opened.containsAll(allowed));
    }
    @Test void closedMenuRestrictionKeepsOptionalCapabilitiesAndExplicitRecoveryLoads() {
        var requested=new TaskToolSelection.Context(true,true,false,false,false,Set.of("extension_work"));
        var selected=TaskToolSelection.select(requested,GUI,ALL,Set.of("open_gui","gui_batch","web_search"));
        assertTrue(selected.containsAll(Set.of("open_gui","web_search","extension_work","load_tool_schema")));
        assertTrue(selected.contains("gui_batch"));
    }
    @Test void recipePreparationSelectionIncludesTheOperationButNeverChangesTheContract() {
        var condition=com.google.gson.JsonParser.parseString("{\"kind\":\"inventory\",\"dimension\":\"minecraft:overworld\",\"item\":\"minecraft:iron_ingot\",\"count\":24}").getAsJsonObject();
        var before=condition.deepCopy();
        assertEquals(Set.of("open_gui","query_recipes","gui_action"),TaskToolSelection.executableSelection(Set.of("open_gui","query_recipes"),condition));
        assertEquals(before,condition);
        var transfer=com.google.gson.JsonParser.parseString("{\"kind\":\"transfer\",\"dimension\":\"minecraft:overworld\",\"item\":\"minecraft:iron_ingot\",\"count\":4,\"position\":[1,2,3],\"to_maid\":true}").getAsJsonObject();
        assertTrue(TaskToolSelection.executableSelection(Set.of("open_gui","inspect_gui"),transfer).isEmpty());
        assertEquals(Set.of("open_gui","gui_batch"),TaskToolSelection.executableSelection(Set.of("open_gui","gui_batch"),transfer));
        assertEquals(Set.of("open_gui","extension_work"),TaskToolSelection.executableSelection(Set.of("open_gui","extension_work"),condition));
        condition.addProperty("count",0);
        assertEquals(Set.of("open_gui","inspect_gui"),TaskToolSelection.executableSelection(Set.of("open_gui","inspect_gui"),condition));
    }
    @Test void stepAllowlistSendsOnlySelectedCapabilitiesPlusRecoveryAndExplicitLoads() {
        var context=context(true,true,false,true);
        var selected=TaskToolSelection.select(context,ALL,ALL,Set.of("gui_action"));
        assertEquals(Set.of("gui_action","update_task_plan","load_tool_schema","read_task_result"),Set.copyOf(selected));
        assertFalse(selected.contains("extension_work"));assertFalse(selected.contains("gui_batch"));assertFalse(selected.contains("wait_gui"));
        var expanded=new TaskToolSelection.Context(true,true,false,true,false,Set.of("inspect_gui"));
        assertTrue(TaskToolSelection.select(expanded,ALL,ALL,Set.of("gui_action")).contains("inspect_gui"));
    }
    @Test void allowlistsNeverGrantFreshnessOrMissingToolsAndRecoveryRestoresNormalChoices() {
        var stale=context(false,true,false,true);
        var selected=TaskToolSelection.select(stale,ALL,ALL,Set.of("gui_action","open_gui","task_control","nonexistent"));
        assertFalse(selected.stream().anyMatch(ToolExecutionPolicy::requiresFreshWorld));assertFalse(selected.contains("task_control"));assertFalse(selected.contains("nonexistent"));
        var recovery=new TaskToolSelection.Context(true,true,false,true,true,Set.of());
        assertEquals(TaskToolSelection.select(recovery,ALL,ALL),TaskToolSelection.select(recovery,ALL,ALL,Set.of("gui_action")));
        assertTrue(AgentToolOutcome.failed("tool_not_in_allowed_tools: no action started"));
    }
    @Test void liveRecipeLookupIsReadOnlyAndAvailableBeforeOpeningOrDefiningMutationContract() {
        var available = new HashSet<>(ALL); available.add("query_recipes");
        assertTrue(TaskToolSelection.select(context(false, false, false, false), Set.of(), available).contains("query_recipes"));
        assertTrue(TaskToolSelection.select(context(true, false, false, false), Set.of(), available).contains("query_recipes"));
        assertTrue(AgentExecution.foregroundTool("query_recipes"));
        assertFalse(ToolExecutionPolicy.requiresFreshWorld("query_recipes"));
        assertEquals(ToolExecutionPolicy.ThreadKind.SERVER, ToolExecutionPolicy.forTool("query_recipes").thread());
    }

    private static final Set<String> GUI = Set.of("open_gui", "inspect_gui", "inspect_containers",
            "gui_action", "gui_batch", "wait_gui", "close_gui");
    private static final Set<String> ALL;
    static {
        ALL = new HashSet<>(GUI);
        ALL.addAll(List.of("scan_surroundings", "query_game_context", "read_task_result", "load_tool_schema",
                "update_task_plan", "web_search", "web_fetch", "get_current_datetime", "use_skill",
                "switch_sit", "task_control", "extension_work"));
    }
    private TaskToolSelection.Context context(boolean fresh, boolean completion, boolean met, boolean gui) {
        return new TaskToolSelection.Context(fresh, completion, met, gui, false, Set.of());
    }

    @Test void resumedLoadedToolsCannotSkipFreshObservation() {
        var selection = TaskToolSelection.select(context(false, true, true, true), ALL, ALL);
        assertEquals(TaskToolSelection.Stage.observe, context(false, true, true, true).stage());
        assertFalse(selection.stream().anyMatch(ToolExecutionPolicy::requiresFreshWorld));
        assertFalse(selection.contains("update_task_plan"));
        assertFalse(selection.contains("task_control"));
        assertTrue(selection.containsAll(List.of("scan_surroundings", "read_task_result")));
    }

    @Test void ordinaryMenusAreSmallWithoutRemovingRequiredSteps() {
        assertEquals(4, TaskToolSelection.select(context(false, false, false, false), GUI, ALL).size());
        var planning = TaskToolSelection.select(context(true, false, false, false), GUI, ALL);
        assertEquals(6, planning.size());
        assertTrue(planning.containsAll(List.of("inspect_containers", "open_gui", "update_task_plan")));
        var execution = TaskToolSelection.select(context(true, true, false, true), GUI, ALL);
        assertEquals(8, execution.size());
        assertTrue(execution.containsAll(List.of("open_gui", "gui_action", "gui_batch", "wait_gui", "inspect_gui", "close_gui")));
        assertEquals(3, TaskToolSelection.select(context(true, true, true, false), GUI, ALL).size());
        assertEquals(5, TaskToolSelection.select(context(true, true, true, true), GUI, ALL).size());
    }

    @Test void openPlanningMenuPreservesSameBatchRegistrationAndFirstAction() {
        var selected = TaskToolSelection.select(context(true, false, false, true), GUI, ALL);
        assertEquals(9, selected.size());
        assertTrue(selected.containsAll(List.of("update_task_plan", "inspect_gui", "close_gui", "inspect_containers", "gui_action", "gui_batch", "wait_gui")));
        var closed = TaskToolSelection.select(new TaskToolSelection.Context(true, false, false, false, false, GUI), GUI, ALL);
        assertFalse(closed.stream().anyMatch(id -> Set.of("gui_action", "gui_batch", "wait_gui").contains(id)));
    }

    @Test void extraRequirementsCanExpandVerificationWithoutReplayingOtherGuiActions() {
        var context = new TaskToolSelection.Context(true, true, true, false, false, Set.of("open_gui", "gui_action"));
        var selected = TaskToolSelection.select(context, GUI, ALL);
        assertTrue(selected.containsAll(List.of("open_gui", "gui_action", "update_task_plan")));
        assertFalse(selected.contains("gui_batch"));
        assertFalse(selected.contains("wait_gui"));
        assertEquals(TaskToolSelection.Stage.verify, context.stage());
    }

    @Test void failureRestoresObservationAndEvidenceTools() {
        var context = new TaskToolSelection.Context(true, true, false, true, true, Set.of());
        var selected = TaskToolSelection.select(context, GUI, ALL);
        assertTrue(selected.containsAll(List.of("inspect_gui", "scan_surroundings", "read_task_result", "close_gui")));
        assertFalse(selected.contains("inspect_containers"));
    }

    @Test void explicitlyLoadedNetworkAndThirdPartyToolsRemainAvailable() {
        var selected = TaskToolSelection.select(context(true, true, false, false), Set.of("web_search", "extension_work"), ALL);
        assertTrue(selected.containsAll(List.of("web_search", "extension_work")));
        assertFalse(selected.contains("get_current_datetime"));
        assertFalse(selected.contains("switch_sit"));
        assertFalse(selected.contains("use_skill"));
    }

    @Test void missingImplementationsAreNeverAdvertised() {
        var selected = TaskToolSelection.select(context(true, true, false, true), GUI, Set.of("load_tool_schema", "inspect_gui"));
        assertEquals(List.of("load_tool_schema", "inspect_gui"), selected);
    }
}
