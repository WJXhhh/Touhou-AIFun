package com.wjx.touhou_aifun.chat.agent.decision;

import com.google.gson.*;
import java.util.*;

/** A failed call is not permission to replay it. Only observed, bounded recovery paths qualify. */
public final class TaskRecoveryPolicy {
    public enum Kind { REINSPECT, REOPEN, OBSERVE, CHOOSE, STOP, DELEGATE }
    private TaskRecoveryPolicy() { }
    public static String reason(String result) {
        try {
            var value=JsonParser.parseString(result).getAsJsonObject();
            if(value.has("error")) return value.get("error").getAsString();
            if(value.has("completion_error")) return "completion_unmet";
            if(value.has("status")) return value.get("status").getAsString();
        } catch(RuntimeException ignored) { }
        return result==null?"unknown":result.split(":",2)[0];
    }
    public static Kind classify(String reason) {
        if(Set.of("owner_changed","superseded","stopped_by_owner","maid_unavailable","cancelled_before_start").contains(reason)) return Kind.STOP;
        if(Set.of("stale_revision_reinspect","stale_slots_reinspect","stale_frame_reinspect").contains(reason)) return Kind.REINSPECT;
        if(Set.of("session_expired_or_mismatch","menu_invalid").contains(reason)) return Kind.REOPEN;
        if(Set.of("qualified_target_block_changed_reobserve","fresh_world_observation_required").contains(reason)) return Kind.OBSERVE;
        if(Set.of("movement_blocked","target_out_of_range","action_busy","operation_in_progress","navigation_failed","path_not_found").contains(reason)) return Kind.CHOOSE;
        return Kind.DELEGATE;
    }
    public static boolean repeatable(ActionPlan.Step step) {
        for(var action:step.alternatives()) {
            if(action.tool().equals("open_gui") && action.arguments().has("item_slot"))return false;
            if(Set.of("scan_surroundings","inspect_gui","inspect_containers","open_gui","close_gui",
                    "switch_follow_state","switch_sit","switch_schedule").contains(action.tool())) continue;
            // Remaining quantities are rebound from the immutable contract and actual settled receipts.
            if(!action.tool().equals("gui_action") || step.until()==null || !"transfer".equals(step.until().has("kind")?step.until().get("kind").getAsString():"transfer")
                    || !action.arguments().has("action") || !action.arguments().get("action").getAsString().equals("transfer")) return false;
        }
        return true;
    }
    public static boolean confident(JsonObject answers,String question,double minimum) {
        var a=answers.getAsJsonObject(question);
        return a!=null && a.has("confidence") && DecisionProtocol.probability(a.get("confidence"))>=minimum;
    }
    public static Boolean booleanAnswer(JsonObject answers,String question,double minimum) {
        var a=answers.getAsJsonObject(question);if(a==null) return null;
        double p=DecisionProtocol.probability(a.get("noul"));
        return p>=minimum?true:p<=1-minimum?false:null;
    }
}
