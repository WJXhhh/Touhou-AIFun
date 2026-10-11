package com.wjx.touhou_aifun.chat.agent.decision;

import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMClient;
import com.google.gson.*;
import com.wjx.touhou_aifun.chat.agent.*;
import com.wjx.touhou_aifun.compat.ai.openai.ToolCatalogSnapshot;
import com.wjx.touhou_aifun.maid.gui.MaidGuiSessionManager;
import java.util.*;
import java.util.function.*;

/** Per execution generation. All world reads/dispatches occur on the authoritative server thread. */
public final class TaskDecisionRouter {
    private final TaskCallback callback;
    private final DecisionServiceSettings.Snapshot settings;
    private boolean disabled,pending,initialScan;
    private int executingStep=-1;
    private int templateAttemptVersion;
    private boolean templateDelegated;
    private TaskTemplatePlanner.Selection inspecting;
    private JsonObject inspectionRequest;
    private ActionPlan.Step failedStep;
    private DecisionQuestionCompiler.Selection dispatched,failedSelection;
    private String failureReason;
    private boolean recoveryPrepared;
    private final Map<Integer,Integer> recoveries=new HashMap<>();
    public TaskDecisionRouter(TaskCallback callback) { this.callback=callback;settings=DecisionServiceSettings.snapshot(); }
    public boolean enabled() { return settings.view().enabled(); }
    public boolean permitsCompletion() {
        var task=callback.task;
        return task.actionSteps==null || task.actionSteps.isEmpty() || task.actionPlanGoalVersion!=task.goalVersion || task.actionStep>=task.actionSteps.size();
    }
    public boolean route(LLMClient client,JsonObject checkpoint,ToolCatalogSnapshot catalog,boolean freshWorld,boolean recovering) {
        if(!enabled()) return false;
        if(pending) return true;
        if(recovering || failedStep!=null) {
            if(routeRecovery(client,recovering)) return true;
            if(recovering) return fallback("recovery_requires_planner");
        }
        if(!freshWorld) {
            if(initialScan || recovering || catalog.decisionEntries().stream().noneMatch(e->e.id().equals("scan_surroundings"))) return false;
            initialScan=true;dispatch(new DecisionQuestionCompiler.Selection("scan_surroundings",new JsonObject()),client,-1,"deterministic_observation");return true;
        }
        var task=callback.task;
        if(task.actionPlanGoalVersion!=task.goalVersion || task.actionSteps==null || task.actionSteps.isEmpty()) return bootstrap(client,catalog);
        templateAttemptVersion=task.goalVersion;
        try {
            while(task.actionStep<task.actionSteps.size()) {
                var originalStep=ActionPlan.parse(task.actionSteps.get(task.actionStep).getAsJsonObject());
                if(originalStep.skipIf()!=null && AgentCompletion.verifyPredicate(callback.getMaid(),task,originalStep.skipIf(),true)) {task.actionStep++;continue;}
                if(originalStep.until()!=null && AgentCompletion.verifyPredicate(callback.getMaid(),task,originalStep.until(),true)) {task.actionStep++;continue;}
                var capture=originalStep.targetBinding()==null?null:TaskTargetBindings.capture(callback);
                var step=ObservedTargetCatalog.expand(originalStep,capture==null?List.of():capture.targets());
                if(step.until()!=null && AgentCompletion.verifyPredicate(callback.getMaid(),task,step.until(),true)) {task.actionStep++;continue;}
                if(step.precondition()!=null && !AgentCompletion.verifyPredicate(callback.getMaid(),task,step.precondition(),true)) return fallback("precondition_unmet");
                if(step.alternatives().size()==1 && step.alternatives().get(0).tool().equals("close_gui") && MaidGuiSessionManager.session(callback.getMaid().getUUID())==null) {task.actionStep++;continue;}
                var preparation=callback.executionTiming.child("decision_prepare");
                var live=callback.decisionLiveState(step);
                if(capture!=null)live.add("binding_proof",capture.fingerprint());
                var tools=new LinkedHashMap<String,DecisionQuestionCompiler.Tool>();
                for(var e:catalog.decisionEntries()) tools.put(e.id(),new DecisionQuestionCompiler.Tool(e.id(),e.summary(),e.anthropicTool().getAsJsonObject("input_schema")));
                var provider=settings.client();
                var compiled=DecisionQuestionCompiler.compile(step,tools,live.getAsJsonObject("bindings"),provider.capabilities());
                preparation.finish(compiled==null?"fallback":"ok",compiled==null?0:compiled.questions().toString().length());
                if(compiled==null) return fallback("open_parameters_or_limit");
                if(compiled.deterministic()!=null) {
                    dispatch(compiled.deterministic(),client,task.actionStep,"deterministic_step");return true;
                }
                if(disabled || settings.apiKey().isBlank()) return fallback("service_unavailable");
                if(!DecisionRequestGate.acquire(settings.view().provider(),System.nanoTime())) return fallback("rate_limit");
                JsonObject state=new JsonObject();state.addProperty("goal",task.goal);state.addProperty("planned_step",step.description());
                state.add("constraints",new Gson().toJsonTree(task.constraints));state.add("live",DecisionStateProjection.modelView(live));
                if(checkpoint.has("recent_failures")) state.add("recent_failures",checkpoint.get("recent_failures").deepCopy());
                state.add("observations",callback.decisionObservations());
                state.getAsJsonObject("live").remove("binding_proof");if(capture!=null)state.add("target_candidates",capture.modelTargets());
                int index=task.actionStep;
                return evaluate(client,"action",state,compiled.questions(),live,()->{
                    var current=callback.decisionLiveState(step);if(capture!=null)current.add("binding_proof",TaskTargetBindings.capture(callback).fingerprint());return current;
                },answers->{var selected=compiled.select(answers,settings.view().minConfidence());
                    if(selected==null){fallback("uncertain_or_none");callback.next(client);}else dispatch(selected,client,index,"decision_selected");});
            }
            if(AgentCompletion.verify(callback.getMaid(),task,true)) {
                if(MaidGuiSessionManager.session(callback.getMaid().getUUID())!=null) {
                    var args=new JsonObject();args.addProperty("session_id",MaidGuiSessionManager.session(callback.getMaid().getUUID()).id.toString());
                    dispatch(new DecisionQuestionCompiler.Selection("close_gui",args),client,-1,"deterministic_cleanup");return true;
                }
                task.currentStep=Math.max(0,task.steps.size()-1);
                callback.decisionTrace("completion_check","typed_plan_exhausted; verified=true");
                AgentRuntime.finished(callback,"已根据当前现场和实际操作记录核验完成。",true);return true;
            }
            return fallback("plan_exhausted_unmet");
        } catch(RuntimeException invalid) {return fallback("invalid_plan_or_settings");}
    }
    private boolean evaluate(LLMClient client,String purpose,JsonObject state,JsonObject questions,JsonObject live,Supplier<JsonObject> current,Consumer<JsonObject> accepted) {
        if(disabled || settings.apiKey().isBlank())return fallback("service_unavailable");
        // Action routing already acquires the gate before compiling its state.
        if(!purpose.equals("action") && !DecisionRequestGate.acquire(settings.view().provider(),System.nanoTime()))return fallback("rate_limit");
        var task=callback.task;int version=task.goalVersion,index=task.actionStep;long generation=task.generation;
        var timing=callback.executionTiming.child("decision_request:"+settings.view().provider());pending=true;
        try {
            settings.client().evaluate(state,questions,callback.operations::add).whenComplete((result,error)->callback.runOnServerThread(()->{
                pending=false;if(callback.stopped()){timing.finish("cancelled",0);return;}
                try {
                    var now=current.get();
                    var previous=live.deepCopy();
                    // A posture/schedule decision does not depend on unrelated containers or their labels.
                    if(purpose.equals("template") && result!=null && error==null && Set.of("follow","sit","schedule","work_task").contains(result.answers().getAsJsonObject("template").get("choice").getAsString())) {previous.remove("binding_proof");now.remove("binding_proof");}
                    if(task.generation!=generation || task.goalVersion!=version || task.actionStep!=index || !task.pendingAmendment.isBlank() || !previous.equals(now)) {
                        timing.finish("stale",0);callback.decisionTrace("decision_stale_fields",staleFields(previous,now));fallback("stale_observation");callback.next(client);return;
                    }
                    if(error!=null) {
                        Throwable cause=error;while(cause.getCause()!=null)cause=cause.getCause();
                        if(cause instanceof NativeDecisionProvider.HttpFailure http){disabled|=http.permanent();if(http.status==429 || http.status==529)DecisionRequestGate.coolDown(settings.view().provider(),System.nanoTime());}
                        else if(cause instanceof IllegalArgumentException)disabled=true;
                        String reason=cause instanceof NativeDecisionProvider.HttpFailure http?"http_"+http.status:cause instanceof java.util.concurrent.TimeoutException || cause instanceof java.net.http.HttpTimeoutException?"timeout":cause instanceof IllegalArgumentException?"invalid_response":"request_error";
                        timing.finish("fallback",0);fallback(reason);callback.next(client);return;
                    }
                    timing.finish("answered",0);callback.decisionTrace("decision_response","purpose="+purpose+"; provider="+settings.view().provider()+"; requested="+settings.view().model()+"; reported="+result.model()+"; input="+result.inputTokens()+"; output="+result.outputTokens()+"; request_id="+result.requestId());
                    accepted.accept(result.answers());
                }catch(RuntimeException invalid){timing.finish("fallback",0);fallback("invalid_response_or_binding");callback.next(client);}
            }));return true;
        }catch(RuntimeException invalid){pending=false;timing.finish("fallback",0);return fallback("request_invalid");}
    }
    private JsonObject fingerprint() {var state=callback.decisionLiveState();state.add("binding_proof",TaskTargetBindings.capture(callback).fingerprint());return state;}
    private static String staleFields(JsonObject old,JsonObject current) {
        var changed=new ArrayList<String>();for(String key:old.keySet())if(!Objects.equals(old.get(key),current.get(key)))changed.add(key);
        if(changed.contains("binding_proof")) {
            var before=old.getAsJsonObject("binding_proof").getAsJsonArray("observed_targets");var after=current.getAsJsonObject("binding_proof").getAsJsonArray("observed_targets");
            if(before!=null && after!=null && before.size()!=after.size())changed.add("target_count:"+before.size()+"->"+after.size());
            if(before!=null && after!=null)for(int n=0;n<Math.min(before.size(),after.size());n++){var b=before.get(n).getAsJsonObject();var a=after.get(n).getAsJsonObject();for(String key:b.keySet())if(!Objects.equals(b.get(key),a.get(key)))changed.add("target["+n+"]."+key);}
        }return changed.toString();
    }
    private JsonObject templateState(String purpose,TaskTargetBindings.Capture capture) {
        var state=new JsonObject();state.addProperty("purpose",purpose);state.addProperty("goal",callback.task.goal);
        state.add("constraints",new Gson().toJsonTree(callback.task.constraints));state.add("live",DecisionStateProjection.modelView(callback.decisionLiveState()));
        var candidates=new JsonArray();for(var target:capture.targets())if(target.kind().equals("container")){var row=target.data().deepCopy();row.addProperty("id",target.id());row.addProperty("kind",target.kind());candidates.add(row);}state.add("target_candidates",candidates);return state;
    }
    private boolean bootstrap(LLMClient client,ToolCatalogSnapshot catalog) {
        var task=callback.task;
        if(templateDelegated || disabled || settings.apiKey().isBlank())return false;
        if(!task.constraints.isEmpty())return false;
        if(inspecting!=null) {
            if(inspectionRequest!=null) {var request=inspectionRequest;inspectionRequest=null;dispatch(new DecisionQuestionCompiler.Selection("inspect_containers",request),client,-1,"template_inspection");return true;}
            return finishInspection(client);
        }
        if(templateAttemptVersion==task.goalVersion)return false;
        templateAttemptVersion=task.goalVersion;
        // Missing contracts after already committed operations need the owner's complete amended scope.
        if(task.completion==null && (!task.transfers.isEmpty() || task.appliedMutationVersion>0))return false;
        var capture=TaskTargetBindings.capture(callback);var schemas=new LinkedHashMap<String,JsonObject>();
        for(var entry:catalog.planningEntries())schemas.put(entry.id(),entry.anthropicTool().getAsJsonObject("input_schema"));
        var start=TaskTemplatePlanner.start(schemas,capture.targets());if(start==null)return false;
        var existing=TaskTemplatePlanner.fromCompletion(task.completion);
        if(existing!=null) {
            start.questions().getAsJsonObject("template").getAsJsonObject("criteria").addProperty("contract","Rebuild all steps from the existing immutable FULL completion contract, including already settled quantities; never drop requirements.");
            start.questions().add("coverage",DecisionProtocol.noul("Does the selected template or existing immutable FULL completion contract cover EVERY owner requirement and constraint? A supported contract may combine transfers and final maid state. No for unknown side tasks, ambiguous target scope or unsupported actions. Never weaken a condition."));
        }
        var state=templateState("template_and_target_binding",capture);var observation=callback.decisionObservation("scan_surroundings");var coverage=new JsonObject();
        if(observation!=null)for(String key:List.of("truncated","omitted","omitted_important_target_details","omitted_sign_texts","coverage"))if(observation.has(key))coverage.add(key,observation.get(key));state.add("observation_coverage",coverage);
        var live=fingerprint();return evaluate(client,"template",state,start.questions(),live,this::fingerprint,answers->{
            if(existing!=null && TaskRecoveryPolicy.confident(answers,"template",settings.view().minConfidence()) && answers.getAsJsonObject("template").get("choice").getAsString().equals("contract")
                    && Boolean.TRUE.equals(TaskRecoveryPolicy.booleanAnswer(answers,"coverage",settings.view().minConfidence()))) {install(existing,client,"contract");return;}
            var selected=TaskTemplatePlanner.select(start,answers,callback.getMaid().level().dimension().location().toString(),settings.view().minConfidence());
            if(selected==null){fallback("template_uncertain_or_unsupported");callback.next(client);return;}
            if(selected.completion()!=null){install(TaskTemplatePlanner.fromCompletion(selected.completion()),client,selected.template());return;}
            inspecting=selected;var request=new JsonObject();var targets=new JsonArray();request.add("targets",targets);
            for(var target:selected.targets()){var row=new JsonObject();var xyz=target.data().getAsJsonArray("position");for(int n=0;n<3;n++)row.add(List.of("x","y","z").get(n),xyz.get(n));row.add("target",target.data().get("registry_id"));targets.add(row);}
            if(MaidGuiSessionManager.session(callback.getMaid().getUUID())!=null){inspectionRequest=request;dispatch(new DecisionQuestionCompiler.Selection("close_gui",sessionArgs()),client,-1,"template_cleanup");return;}
            dispatch(new DecisionQuestionCompiler.Selection("inspect_containers",request),client,-1,"template_inspection");
        });
    }
    private boolean finishInspection(LLMClient client) {
        var result=callback.decisionObservation("inspect_containers");
        if(result==null || result.has("error") || !result.has("containers"))return fallback("template_inspection_unavailable");
        var stocks=new ArrayList<Map<String,Long>>();var items=new TreeSet<String>();var stackSizes=new TreeMap<String,Integer>();
        for(var target:inspecting.targets()) {
            JsonObject found=null;for(var value:result.getAsJsonArray("containers")){var row=value.getAsJsonObject();if(row.get("target_position").equals(target.data().get("position")))found=row;}
            if(found==null || !"complete_opened_chest_menu".equals(found.get("coverage").getAsString()))return fallback("template_inspection_incomplete");
            var counts=new TreeMap<String,Long>();for(var value:found.getAsJsonArray("items")){var row=value.getAsJsonObject();counts.put(row.get("item").getAsString(),row.get("count").getAsLong());}
            if(!counts.equals(TaskTargetBindings.containerCounts(callback,target)))return fallback("template_inspection_stale");
            stocks.add(counts);if(inspecting.template().equals("receive")){items.addAll(counts.keySet());TaskTargetBindings.containerStackSizes(callback,target,stackSizes);}
        }
        var backpack=new TreeMap<String,Long>();var inventory=callback.getMaid().getAvailableBackpackInv();for(int i=0;i<inventory.getSlots();i++){var stack=inventory.getStackInSlot(i);if(!stack.isEmpty())backpack.merge(com.wjx.touhou_aifun.maid.gui.MaidGuiSession.itemId(stack),(long)stack.getCount(),Long::sum);}
        if(inspecting.template().equals("deliver"))items.addAll(backpack.keySet());
        if(inspecting.template().equals("deliver"))for(int i=0;i<inventory.getSlots();i++)TaskTargetBindings.recordStackSize(stackSizes,inventory.getStackInSlot(i));
        var ordered=List.copyOf(items);String goal=callback.task.goal+"\n"+String.join("\n",callback.task.constraints);
        JsonObject questions;try{questions=TaskTemplatePlanner.itemQuestions(goal,ordered,stackSizes);}catch(RuntimeException unsupported){return fallback("template_items_or_quantity_limit");}
        var state=templateState("item_and_quantity_binding",TaskTargetBindings.capture(callback));state.addProperty("template",inspecting.template());var targetIds=new JsonArray();inspecting.targets().forEach(t->targetIds.add(t.id()));state.add("selected_targets",targetIds);
        state.add("opened_counts",new Gson().toJsonTree(stocks));state.add("backpack_counts",new Gson().toJsonTree(backpack));state.add("item_candidates",new Gson().toJsonTree(ordered));
        state.add("item_stack_sizes",new Gson().toJsonTree(stackSizes));
        var selected=inspecting;var live=fingerprint();return evaluate(client,"items",state,questions,live,this::fingerprint,answers->{
            var plan=TaskTemplatePlanner.transfers(selected,ordered,answers,stocks,backpack,goal,settings.view().minConfidence(),stackSizes);
            if(plan==null){fallback("template_quantity_or_scope_unverified");callback.next(client);return;}install(plan,client,selected.template());
        });
    }
    private void install(TaskTemplatePlanner.Plan plan,LLMClient client,String template) {
        if(plan==null){fallback("template_plan_unavailable");callback.next(client);return;}
        if(callback.task.completion!=null && !callback.task.completion.equals(plan.completion())){fallback("template_contract_mismatch");callback.next(client);return;}
        for(var row:plan.steps())for(var value:row.getAsJsonObject().getAsJsonArray("alternatives")) {
            var action=value.getAsJsonObject();var args=action.getAsJsonObject("arguments");
            if(action.get("tool").getAsString().equals("open_gui") && args.has("x") && args.has("y") && args.has("z")) {
                var pos=new net.minecraft.core.BlockPos(args.get("x").getAsInt(),args.get("y").getAsInt(),args.get("z").getAsInt());var level=callback.getMaid().level();
                if(!level.hasChunkAt(pos)){fallback("template_target_unloaded");callback.next(client);return;}
                args.addProperty("target",net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString());
            }
        }
        callback.defineCompletion(plan.completion());var task=callback.task;task.actionSteps=plan.steps();task.actionStep=0;task.actionPlanGoalVersion=task.goalVersion;
        // The verified schedule uses lowercase enum names; the tool may expose uppercase wire values.
        for(var row:task.actionSteps)for(var value:row.getAsJsonObject().getAsJsonArray("alternatives")) {
            var action=value.getAsJsonObject();if(!action.get("tool").getAsString().equals("switch_schedule"))continue;
            var args=action.getAsJsonObject("arguments");var entry=com.wjx.touhou_aifun.compat.ai.openai.ToolContextSelector.snapshot(callback.getMaid(),callback).planningEntries().stream().filter(e->e.id().equals("switch_schedule")).findFirst();
            if(entry.isPresent()){var schema=entry.get().anthropicTool().getAsJsonObject("input_schema");var property=schema.getAsJsonObject("properties").getAsJsonObject("schedule");if(property.has("enum"))for(var candidate:property.getAsJsonArray("enum"))if(candidate.getAsString().equalsIgnoreCase(args.get("schedule").getAsString()))args.add("schedule",candidate);}
        }
        task.steps=plan.steps().asList().stream().map(v->v.getAsJsonObject().get("description").getAsString()).toList();task.currentStep=0;inspecting=null;
        AgentRuntime.save(callback.getMaid());callback.decisionTrace("decision_template","template="+template+"; steps="+task.actionSteps.size()+"; full_contract_preserved=true");callback.next(client);
    }
    private JsonObject sessionArgs(){var args=new JsonObject();var session=MaidGuiSessionManager.session(callback.getMaid().getUUID());if(session!=null)args.addProperty("session_id",session.id.toString());return args;}
    private boolean routeRecovery(LLMClient client,boolean recovering) {
        if(failedStep==null)return false;
        if(recoveryPrepared) {
            failedStep=null;recoveryPrepared=false;
            if(recovering){fallback("recovery_observation_failed");return false;}return false;
        }
        var kind=TaskRecoveryPolicy.classify(failureReason);
        if(kind==TaskRecoveryPolicy.Kind.STOP){AgentRuntime.finished(callback,"任务现场或主人状态已改变，已保留实际操作记录并暂停。",false);return true;}
        int count=recoveries.merge(callback.task.actionStep,1,Integer::sum);if(count>2 || !TaskRecoveryPolicy.repeatable(failedStep)){failedStep=null;return false;}
        var session=MaidGuiSessionManager.session(callback.getMaid().getUUID());
        if(kind==TaskRecoveryPolicy.Kind.REINSPECT && session!=null){recoverWith("inspect_gui",sessionArgs(),client,"reinspect");return true;}
        var reopen=reopenArguments();
        if(kind==TaskRecoveryPolicy.Kind.REOPEN && reopen!=null){recoverWith("open_gui",reopen,client,"reopen");return true;}
        if(kind==TaskRecoveryPolicy.Kind.OBSERVE){recoverWith("scan_surroundings",new JsonObject(),client,"observe");return true;}
        if(kind!=TaskRecoveryPolicy.Kind.CHOOSE){failedStep=null;return false;}
        var choices=new LinkedHashMap<String,String>();
        if(session!=null)choices.put("reinspect","Refresh the existing menu; no mutation or replay.");
        if(reopen!=null)choices.put("reopen","Reopen ONLY the same authorized container, then bind remaining quantities from settled facts.");
        choices.put("observe","Refresh surroundings before resuming the original bounded step; do not broaden scope.");choices.put("pause","Confirmed physical blocker needs owner intervention; preserve progress and pause.");choices.put("llm","Unsupported or unclear recovery; delegate full replanning without replaying settled facts.");
        var questions=new JsonObject();questions.add("recovery",DecisionProtocol.choice("Choose a feasible recovery for the observed failure. Never assume a failed mutation did nothing. Already committed receipts are authoritative; do not retry a blocked action directly.",choices));
        var state=new JsonObject();state.addProperty("failure",failureReason);state.addProperty("failed_tool",callback.lastFailedTool());state.addProperty("goal",callback.task.goal);state.add("live",DecisionStateProjection.modelView(callback.decisionLiveState()));
        var live=recoveryLive();return evaluate(client,"recovery",state,questions,live,this::recoveryLive,answers->{
            if(!TaskRecoveryPolicy.confident(answers,"recovery",settings.view().minConfidence())){fallback("recovery_uncertain");callback.next(client);return;}
            String choice=answers.getAsJsonObject("recovery").get("choice").getAsString();
            switch(choice) {
                case "reinspect"->recoverWith("inspect_gui",sessionArgs(),client,"selected_reinspect");
                case "reopen"->recoverWith("open_gui",reopen,client,"selected_reopen");
                case "observe"->recoverWith("scan_surroundings",new JsonObject(),client,"selected_observe");
                case "pause"->AgentRuntime.finished(callback,"现场条件阻止任务继续，已保留实际操作记录并暂停。",false);
                default->{fallback("recovery_requires_planner");callback.next(client);}
            }
        });
    }
    private JsonObject reopenArguments() {
        if(failedSelection!=null && failedSelection.tool().equals("open_gui")) {
            var args=failedSelection.arguments();if(args.has("entity_uuid") || args.has("x") && args.has("y") && args.has("z"))return args.deepCopy();
        }
        var condition=failedStep.until();if(condition==null || !AgentTaskState.completionKind(condition).equals("transfer") || !condition.has("position"))return null;
        var xyz=condition.getAsJsonArray("position");var args=new JsonObject();for(int n=0;n<3;n++)args.add(List.of("x","y","z").get(n),xyz.get(n));args.addProperty("wait_policy","AUTO");args.addProperty("coordinate_space","world");args.addProperty("max_distance",16);return args;
    }
    private JsonObject recoveryLive() {
        return failedSelection==null?callback.decisionLiveState(failedStep):callback.decisionLiveState(new ActionPlan.Step(failedStep.description(),List.of(new ActionPlan.Action(failedSelection.tool(),failedSelection.arguments(),"Failed bound action")),failedStep.precondition(),failedStep.until()));
    }
    private void recoverWith(String tool,JsonObject args,LLMClient client,String kind) {recoveryPrepared=true;callback.decisionTrace("decision_recovery","strategy="+kind+"; attempt="+recoveries.get(callback.task.actionStep));dispatch(new DecisionQuestionCompiler.Selection(tool,args),client,-1,"recovery");}
    private void dispatch(DecisionQuestionCompiler.Selection selection,LLMClient client,int index,String kind) {
        boolean valid;
        try {valid=callback.validateDecisionAction(selection.tool(),selection.arguments());}catch(RuntimeException invalid){valid=false;}
        if(!valid) {fallback("argument_association_unverified");callback.next(client);return;}
        callback.executionTiming.child("decision_dispatch:"+kind).finish("routed",0);
        executingStep=index;dispatched=selection;callback.decisionTrace(kind,"tool="+selection.tool()+"; step="+index);
        callback.dispatchAutomatic(selection.tool(),selection.arguments(),client);
    }
    private boolean fallback(String reason) {
        callback.executionTiming.child("decision_fallback:"+reason).finish("fallback",0);
        templateDelegated=true;templateAttemptVersion=callback.task.goalVersion;inspecting=null;inspectionRequest=null;failedStep=null;recoveryPrepared=false;
        callback.task.clearActionPlan();callback.decisionTrace("decision_fallback",reason);return false;
    }
    public void settled(boolean failed,boolean freshWorld) {
        int index=executingStep;executingStep=-1;
        if(index<0) return;
        var task=callback.task;
        if(task.actionPlanGoalVersion!=task.goalVersion || task.actionStep!=index || !task.pendingAmendment.isBlank()) {task.clearActionPlan();failedStep=null;return;}
        var step=ActionPlan.parse(task.actionSteps.get(index).getAsJsonObject());
        if(failed) {failedStep=step;failedSelection=dispatched;failureReason=TaskRecoveryPolicy.reason(callback.lastFailedResult());recoveryPrepared=false;return;}
        recoveries.remove(index);
        if(step.until()==null || AgentCompletion.verifyPredicate(callback.getMaid(),task,step.until(),freshWorld)) {
            task.actionStep++;task.currentStep=Math.min(task.actionStep,Math.max(0,task.steps.size()-1));AgentRuntime.save(callback.getMaid());
        }
    }
}
