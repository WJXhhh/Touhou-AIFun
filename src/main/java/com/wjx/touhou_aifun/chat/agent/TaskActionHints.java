package com.wjx.touhou_aifun.chat.agent;

import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.*;
import com.google.gson.JsonObject;
import com.wjx.touhou_aifun.compat.ai.openai.ToolContextSelector;
import java.util.List;
import java.util.Set;

/** Optional next-request metadata, committed only after a successful real action. */
public final class TaskActionHints {
    private TaskActionHints() { }

    public static void parameters(ObjectParameter root,boolean selection) {
        root.addProperties("next_step",IntegerParameter.create().setMinimum(0).setMaximum(31)
                .setDescription("Optional plan index AFTER success. Updates progress in this action; never proves completion. Omit if unnecessary."),false);
        if(selection)root.addProperties("next_allowed_tools",ArrayParameter.create().setItems(StringParameter.create().setMaxLength(128)).setRange(0,64)
                .setDescription("Small next-request tools for a repeated phase. Omit when the current selection still fits, or for short/mixed actions. [] restores stage menu."),false);
    }

    public static Set<String> toolIds(TaskCallback task,com.google.gson.JsonElement value) {
        if(value==null || !value.isJsonArray() || value.getAsJsonArray().size()>64)
            throw new IllegalArgumentException("invalid_allowed_tools");
        var available=ToolContextSelector.snapshot(task.getMaid(),task).planningEntries().stream()
                .map(entry->entry.id()).collect(java.util.stream.Collectors.toSet());
        var ids=new java.util.LinkedHashSet<String>();
        for(var id:value.getAsJsonArray()) {
            if(!id.isJsonPrimitive() || !id.getAsJsonPrimitive().isString() || !available.contains(id.getAsString()))
                throw new IllegalArgumentException("unknown_allowed_tool");
            ids.add(id.getAsString());
        }
        return Set.copyOf(ids);
    }

    public static Prepared prepare(TaskCallback task,JsonObject arguments) {
        if(!arguments.has("next_step") && !arguments.has("next_allowed_tools"))return null;
        Integer step=null;
        if(arguments.has("next_step")) {
            var value=arguments.get("next_step");
            if(!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
                    || !value.getAsString().matches("[0-9]{1,2}"))throw new IllegalArgumentException("invalid_next_step");
            step=value.getAsInt();
            if(step<task.task.currentStep || step>=task.task.steps.size())throw new IllegalArgumentException("invalid_next_step");
        }
        Set<String> tools=arguments.has("next_allowed_tools")?toolIds(task,arguments.get("next_allowed_tools")):null;
        return new Prepared(task,task.task.goalVersion,task.task.generation,List.copyOf(task.task.steps),task.task.currentStep,step,tools);
    }

    public record Prepared(TaskCallback callback,int goalVersion,long generation,List<String> plan,int oldStep,
                           Integer nextStep,Set<String> nextTools) {
        public boolean commit() {
            var state=callback.task;
            if(callback.stopped() || !state.pendingAmendment.isBlank() || state.goalVersion!=goalVersion
                    || state.generation!=generation || !state.steps.equals(plan) || state.currentStep!=oldStep)return false;
            // Bind the hint to the phase AFTER the real receipts, not the phase just completed.
            AgentCompletion.verify(callback.getMaid(),state,callback.toolSelection().freshWorld());
            if(nextStep!=null)state.currentStep=nextStep;
            if(nextTools!=null)callback.selectTools(nextTools);
            AgentRuntime.save(callback.getMaid());return true;
        }
    }
}
