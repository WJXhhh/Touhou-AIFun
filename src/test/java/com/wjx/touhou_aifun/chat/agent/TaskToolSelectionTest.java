package com.wjx.touhou_aifun.chat.agent;

import org.junit.jupiter.api.Test;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class TaskToolSelectionTest {
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
