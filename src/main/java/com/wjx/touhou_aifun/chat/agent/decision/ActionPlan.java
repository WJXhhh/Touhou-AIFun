package com.wjx.touhou_aifun.chat.agent.decision;

import com.google.gson.*;
import com.wjx.touhou_aifun.chat.agent.AgentTaskState;
import java.util.*;

/** Durable templates contain intent and fixed arguments, never live session/slot observations. */
public final class ActionPlan {
    public record Action(String tool,JsonObject arguments,String when) { }
    public record Step(String description,List<Action> alternatives,JsonObject precondition,JsonObject until,JsonObject targetBinding,JsonObject skipIf) {
        public Step(String description,List<Action> alternatives,JsonObject precondition,JsonObject until) {this(description,alternatives,precondition,until,null,null);}
        public Step(String description,List<Action> alternatives,JsonObject precondition,JsonObject until,JsonObject targetBinding) {this(description,alternatives,precondition,until,targetBinding,null);}
    }
    private ActionPlan() { }
    public static JsonArray validate(JsonArray input) {
        if(input==null || input.isEmpty() || input.size()>32 || input.toString().length()>65536) throw new IllegalArgumentException("invalid_action_plan");
        for(var value:input) parse(value.getAsJsonObject());return input.deepCopy();
    }
    public static Step parse(JsonObject row) {
        String description=row.get("description").getAsString();
        if(description.isBlank() || description.length()>512) throw new IllegalArgumentException("invalid_action_step");
        var choices=row.getAsJsonArray("alternatives");
        if(choices==null || choices.isEmpty() || choices.size()>32) throw new IllegalArgumentException("invalid_action_alternatives");
        var actions=new ArrayList<Action>();
        for(var value:choices) {
            var action=value.getAsJsonObject();String tool=action.get("tool").getAsString();
            if(!tool.matches("[A-Za-z0-9_.:-]{1,128}") || Set.of("task_control","update_task_plan","load_tool_schema").contains(tool)) throw new IllegalArgumentException("invalid_action_tool");
            JsonObject args=action.has("arguments_json") ? JsonParser.parseString(action.get("arguments_json").getAsString()).getAsJsonObject()
                    :action.has("arguments")?action.getAsJsonObject("arguments").deepCopy():new JsonObject();
            if(args.toString().length()>8192) throw new IllegalArgumentException("action_arguments_limit");
            String when=action.has("when")?action.get("when").getAsString():description;
            if(when.length()>512) throw new IllegalArgumentException("action_instructions_limit");
            actions.add(new Action(tool,args,when));
        }
        JsonObject binding=null;
        if(row.has("target_binding")) {
            binding=row.getAsJsonObject("target_binding");
            if(!Set.of("block","container","entity").contains(binding.get("kind").getAsString())
                    || binding.get("description").getAsString().isBlank() || binding.get("description").getAsString().length()>256) throw new IllegalArgumentException("invalid_target_binding");
            if(binding.has("scope") && (!binding.get("scope").getAsJsonPrimitive().isString() || binding.get("scope").getAsString().isBlank() || binding.get("scope").getAsString().length()>64))throw new IllegalArgumentException("invalid_target_binding_scope");
            binding=binding.deepCopy();
        }
        return new Step(description,List.copyOf(actions),condition(row,"precondition"),condition(row,"until"),binding,condition(row,"skip_if"));
    }
    private static JsonObject condition(JsonObject row,String key) {
        var condition=row.has(key)?AgentTaskState.validateCompletion(row.getAsJsonObject(key)):null;
        if(condition!=null && AgentTaskState.completionKind(condition).equals("sequence"))throw new IllegalArgumentException("ordered_phases_are_task_completion_only");
        return condition;
    }
}
