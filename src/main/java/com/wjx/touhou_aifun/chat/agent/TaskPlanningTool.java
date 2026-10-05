package com.wjx.touhou_aifun.chat.agent;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ITool;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.*;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.request.ChatCompletion;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.*;
import com.mojang.serialization.Codec;
import java.util.*;

/** Plans may define a missing completion before transfers, but cannot weaken an existing contract. */
public final class TaskPlanningTool implements ITool<JsonObject> {
    @Override public String id() { return "update_task_plan"; }
    @Override public boolean trigger(EntityMaid maid,ChatCompletion request) { return AgentRuntime.enabled(maid); }
    @Override public String summary(EntityMaid maid) { return "Record a short task plan/current step. Define missing completion from fresh observations BEFORE transfers, covering every user requirement. When all steps and requirements are finished, set complete=true with current_step at the last step; the runtime verifies live facts and closes the task without another model request. Can follow close_gui in the same ordered batch. Existing conditions cannot be weakened."; }
    @Override public Parameter parameters(ObjectParameter root,EntityMaid maid) {
        root.addProperties("steps",ArrayParameter.create().setItems(StringParameter.create().setMaxLength(256)).setMinItems(1).setMaxItems(32),true);
        root.addProperties("current_step",IntegerParameter.create().setMinimum(0).setMaximum(31),true);
        root.addProperties("completion",AgentTool.completionParameter(),false);
        root.addProperties("complete",BoolParameter.create().setDescription("Default false. Declare all user requirements and steps finished; current_step must be the final index, GUI must be closed. Runtime verifies settled live facts before sending the completion notification. Not an automatic success override."),false);
        return root;
    }
    @Override public Codec<JsonObject> codec() { return new AgentTool("read_task_result").codec(); }
    @Override public LLMCallback onCall(String callId,JsonObject args,LLMCallback callback) {
        if(!(callback instanceof TaskCallback task)) return callback.addToolResult("task_execution_only",callId);
        try {
            var values=args.getAsJsonArray("steps"); int step=args.get("current_step").getAsInt();
            if(values==null || values.isEmpty() || values.size()>32 || step<0 || step>=values.size()) throw new IllegalArgumentException();
            List<String> plan=new ArrayList<>(); for(var value:values) { String text=value.getAsString(); if(text.isBlank() || text.codePointCount(0,text.length())>256) throw new IllegalArgumentException(); plan.add(text); }
            boolean complete=false;
            if(args.has("complete")) {
                if(!args.get("complete").isJsonPrimitive() || !args.getAsJsonPrimitive("complete").isBoolean()) throw new IllegalArgumentException("complete_must_be_boolean");
                complete=args.get("complete").getAsBoolean();
            }
            if(complete && step!=values.size()-1) throw new IllegalArgumentException("unfinished_plan_steps");
            if(args.has("completion")) task.defineCompletion(args.getAsJsonObject("completion"));
            if(complete) task.requestCompletion();
            task.task.steps=plan; task.task.currentStep=step; AgentRuntime.save(callback.getMaid());
            return callback.addToolResult("plan_recorded: current_step="+step+"; completion_defined="+(task.task.completion!=null)+"; completion_requested="+complete+(complete?"; awaiting settled live verification":"")+"; user goal and verified facts unchanged",callId);
        } catch(IllegalArgumentException invalid) { return callback.addToolResult("invalid_task_plan: "+invalid.getMessage(),callId); }
        catch(RuntimeException invalid) { return callback.addToolResult("invalid_task_plan",callId); }
    }
}
