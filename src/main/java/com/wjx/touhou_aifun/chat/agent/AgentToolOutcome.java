package com.wjx.touhou_aifun.chat.agent;

import com.google.gson.JsonParser;

/** Guard rejections are failed dispatches even when legacy tools return plain text. */
public final class AgentToolOutcome {
    private AgentToolOutcome() { }
    public static boolean failed(String result) {
        if(result==null) return true;
        try {
            var value=JsonParser.parseString(result).getAsJsonObject();
            return value.has("error") || value.has("completion_error") || value.has("status") && java.util.Set.of("failed","error","rejected","cancelled").contains(value.get("status").getAsString());
        } catch(RuntimeException ignored) {
            return java.util.List.of("tool_failed","invalid_","cancelled_","tool_unavailable","tool_returned_no_result",
                    "duplicate_tool_call_id:","goal_updated_before_dispatch:","fresh_world_observation_required:",
                    "completion_contract_required_before_mutation:","task_execution_only","task_cannot_manage_queue")
                    .stream().anyMatch(result::startsWith);
        }
    }
}
