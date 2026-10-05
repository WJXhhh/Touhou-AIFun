package com.wjx.touhou_aifun.chat.agent;

/** AIFun metadata; unknown third-party implementations always stay exclusive. */
public record ToolExecutionPolicy(ThreadKind thread, String resource, boolean parallel,
                                  boolean cancellable, int resultTokens) {
    public enum ThreadKind { SERVER, ASYNC }
    public static boolean requiresFreshWorld(String id) {
        return !java.util.Set.of("update_task_plan", "scan_surroundings", "observe_surroundings", "review_observation", "query_game_context",
                "load_tool_schema", "read_task_result", "current_date_time", "get_current_datetime", "web_search", "web_fetch", "use_skill", "query_minecraft_wiki").contains(id);
    }
    public static ToolExecutionPolicy forTool(String id) {
        return switch (id) {
            case "web_fetch", "web_search" -> new ToolExecutionPolicy(ThreadKind.ASYNC, "network", true, true, 2048);
            case "open_gui", "inspect_gui", "inspect_containers" -> new ToolExecutionPolicy(ThreadKind.SERVER,"maid_gui",false,true,16384);
            default -> new ToolExecutionPolicy(ThreadKind.SERVER, "maid_world", false, false, 2048);
        };
    }
}
