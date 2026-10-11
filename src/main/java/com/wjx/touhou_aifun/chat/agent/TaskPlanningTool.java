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
        return parameters(root,maid,com.wjx.touhou_aifun.config.TouhouAIFunConfig.SPEC.isLoaded() && com.wjx.touhou_aifun.config.TouhouAIFunConfig.DECISION_SERVICE_ENABLED.get());
    }
    public Parameter parameters(ObjectParameter root,EntityMaid maid,boolean decisionEnabled) {
        root.addProperties("steps",ArrayParameter.create().setItems(StringParameter.create().setMaxLength(256)).setMinItems(1).setMaxItems(32),true);
        root.addProperties("current_step",IntegerParameter.create().setMinimum(0).setMaximum(31),true);
        root.addProperties("completion",AgentTool.completionParameter(),false);
        ObjectParameter action=ObjectParameter.create();
        action.addProperties("tool",StringParameter.create().setDescription("Exact eligible tool id; queue management and nested planning are excluded."),true);
        action.addProperties("arguments_json",StringParameter.create().setMaxLength(8192).setDescription("JSON object of fixed arguments. Omit enum/boolean fields to let the decision service choose. Required free-text fields must be supplied by the planner. Use live bindings, never predict session/slot ids."),false);
        action.addProperties("when",StringParameter.create().setMaxLength(512).setDescription("When this alternative is suitable for the current step."),false);
        ObjectParameter step=ObjectParameter.create();step.addProperties("description",StringParameter.create().setMaxLength(512),true);
        step.addProperties("alternatives",ArrayParameter.create().setItems(action).setRange(1,32),true);
        step.addProperties("precondition",AgentTool.completionParameter().setDescription("Optional live predicate which MUST hold before dispatch; otherwise re-plan."),false);
        step.addProperties("until",AgentTool.completionParameter().setDescription("Optional verified outcome. Repeat this step only until this live predicate is satisfied; counts are calculated by code."),false);
        step.addProperties("skip_if",AgentTool.completionParameter().setDescription("Skip an already unnecessary step when this server predicate is verified. Otherwise execute once; unlike until, this does not repeat the step."),false);
        ObjectParameter targetBinding=ObjectParameter.create();targetBinding.addProperties("kind",StringParameter.create().addEnumValues("block","container","entity"),true);
        targetBinding.addProperties("description",StringParameter.create().setMaxLength(256).setDescription("Owner-authorized target description; choose only from this generation's observed candidates, never broaden scope."),true);
        step.addProperties("target_binding",targetBinding.setDescription("Optional finite target binding. Use $target.x/$target.y/$target.z/$target.registry_id or $target.entity_id/$target.entity_uuid in arguments. Coordinates and identities are supplied by code."),false);
        if(decisionEnabled)
            root.addProperties("action_steps",ArrayParameter.create().setItems(step).setRange(1,32).setDescription("Optional full executable plan covering ALL requirements. One fully specified alternative runs directly; finite enum/boolean alternatives use JEV/Decider. $live.session_id, $live.revision, $live.remaining_N, $live.from_slot_N, $live.to_slot_N, $live.transfer_count_N refer to the Nth atomic completion condition, indexed from 0. Arguments involving images, unknown bindings or required free text return to the execution LLM. Existing text plans remain supported."),false);
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
            JsonArray executable=args.has("action_steps")?com.wjx.touhou_aifun.chat.agent.decision.ActionPlan.validate(args.getAsJsonArray("action_steps")):null;
            if(executable!=null && !task.standalonePlanCall()) throw new IllegalArgumentException("action_plan_requires_standalone_call");
            if(executable!=null && complete) throw new IllegalArgumentException("cannot_complete_new_action_plan");
            if(args.has("completion")) task.defineCompletion(args.getAsJsonObject("completion"));
            if(executable!=null && task.task.completion==null) throw new IllegalArgumentException("completion_required_for_action_plan");
            if(complete) task.requestCompletion();
            task.task.steps=plan; task.task.currentStep=step;
            task.task.clearActionPlan();
            if(executable!=null) {task.task.actionSteps=executable;task.task.actionPlanGoalVersion=task.task.goalVersion;}
            AgentRuntime.save(callback.getMaid());
            return callback.addToolResult("plan_recorded: current_step="+step+"; completion_defined="+(task.task.completion!=null)+"; completion_requested="+complete+(complete?"; awaiting settled live verification":"")+"; user goal and verified facts unchanged",callId);
        } catch(IllegalArgumentException invalid) { return callback.addToolResult("invalid_task_plan: "+invalid.getMessage(),callId); }
        catch(RuntimeException invalid) { return callback.addToolResult("invalid_task_plan",callId); }
    }
}
