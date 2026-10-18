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
    private final boolean startupTemplates;
    private boolean disabled,pending,initialScan;
    private boolean serviceDiagnosticRecorded;
    private String disabledReason="";
    private int requestAttempts;
    private final long snapshotCreated=System.nanoTime();
    private int executingStep=-1;
    private int templateAttemptVersion;
    private boolean templateDelegated;
    private TaskTemplatePlanner.Selection inspecting;
    private JsonObject inspectionRequest;
    private List<String> explicitItems;
    private JsonObject explicitAnswers;
    private ActionPlan.Step failedStep;
    private DecisionQuestionCompiler.Selection dispatched,failedSelection;
    private String failureReason;
    private boolean recoveryPrepared;
    private final Map<Integer,Integer> recoveries=new HashMap<>();
    private final TargetBindingCache targetBindings=new TargetBindingCache();
    private int bindingVersion;
    private boolean stageSegment,stageContinuation;
    private int stageSegmentPhase;
    private String stageSuppressed="";
    private int plannerHandoffs;
    private final Map<String,Integer> bypassReasons=new LinkedHashMap<>();
    public TaskDecisionRouter(TaskCallback callback) { this.callback=callback;settings=DecisionServiceSettings.snapshot();startupTemplates=com.wjx.touhou_aifun.config.TouhouAIFunConfig.DECISION_STARTUP_TEMPLATES.get(); }
    public boolean enabled() { return settings.view().enabled(); }
    public boolean permitsCompletion() {
        var task=callback.task;
        return task.actionSteps==null || task.actionSteps.isEmpty() || task.actionPlanGoalVersion!=task.goalVersion || task.actionStep>=task.actionSteps.size();
    }
    public boolean route(LLMClient client,JsonObject checkpoint,ToolCatalogSnapshot catalog,boolean freshWorld,boolean recovering) {
        boolean routed=routeInternal(client,checkpoint,catalog,freshWorld,recovering);
        if(!routed && enabled() && requestAttempts==0 && ++plannerHandoffs>=3)serviceDiagnostic("enabled_but_unused");
        return routed;
    }
    public void finishDiagnostic() {
        if(enabled() && requestAttempts==0 && plannerHandoffs>0)serviceDiagnostic("finished_without_decision_requests");
    }
    private boolean routeInternal(LLMClient client,JsonObject checkpoint,ToolCatalogSnapshot catalog,boolean freshWorld,boolean recovering) {
        if(!enabled()) {
            if(!serviceDiagnosticRecorded && com.wjx.touhou_aifun.config.TouhouAIFunConfig.DECISION_SERVICE_ENABLED.get())serviceDiagnostic("snapshot_disabled");
            return false;
        }
        if(pending) return true;
        if(bindingVersion!=callback.task.goalVersion){bindingVersion=callback.task.goalVersion;targetBindings.clear();templateDelegated=false;templateAttemptVersion=0;failedStep=null;recoveryPrepared=false;inspecting=null;inspectionRequest=null;explicitItems=null;explicitAnswers=null;recoveries.clear();stageSegment=false;stageContinuation=false;stageSuppressed="";}
        if(recovering || failedStep!=null) {
            if(routeRecovery(client,recovering)) return true;
            if(recovering) return fallback("recovery_requires_planner");
        }
        if(!freshWorld) {
            if(initialScan || recovering || catalog.decisionEntries().stream().noneMatch(e->e.id().equals("scan_surroundings"))) return bypass("fresh_observation_required");
            initialScan=true;dispatch(new DecisionQuestionCompiler.Selection("scan_surroundings",new JsonObject()),client,-1,"deterministic_observation");return true;
        }
        var task=callback.task;
        if(stageSegment && stageSegmentPhase!=task.completionStage) {
            stageSegment=false;task.clearActionPlan();
            if(MaidGuiSessionManager.session(callback.getMaid().getUUID())!=null){dispatch(new DecisionQuestionCompiler.Selection("close_gui",sessionArgs()),client,-1,"stage_cleanup");return true;}
        }
        if(task.actionPlanGoalVersion!=task.goalVersion || task.actionSteps==null || task.actionSteps.isEmpty()) {
            if(routeStage(client,catalog))return true;
            return bootstrap(client,catalog);
        }
        templateAttemptVersion=task.goalVersion;
        try {
            while(task.actionStep<task.actionSteps.size()) {
                var originalStep=ActionPlan.parse(task.actionSteps.get(task.actionStep).getAsJsonObject());
                if(originalStep.skipIf()!=null && AgentCompletion.verifyPredicate(callback.getMaid(),task,originalStep.skipIf(),true)) {task.actionStep++;continue;}
                if(originalStep.until()!=null && AgentCompletion.verifyPredicate(callback.getMaid(),task,originalStep.until(),true)) {task.actionStep++;continue;}
                var capture=originalStep.targetBinding()==null?null:TaskTargetBindings.capture(callback,false);
                var reused=capture==null?null:targetBindings.reuse(originalStep,capture);
                var step=reused!=null?reused:ObservedTargetCatalog.expand(originalStep,capture==null?List.of():capture.targets());
                if(reused!=null)callback.decisionTrace("decision_binding_reused","step="+task.actionStep);
                if(step.until()!=null && AgentCompletion.verifyPredicate(callback.getMaid(),task,step.until(),true)) {task.actionStep++;continue;}
                if(step.precondition()!=null && !AgentCompletion.verifyPredicate(callback.getMaid(),task,step.precondition(),true)) return fallback("precondition_unmet");
                if(step.alternatives().size()==1 && step.alternatives().get(0).tool().equals("close_gui") && MaidGuiSessionManager.session(callback.getMaid().getUUID())==null) {task.actionStep++;continue;}
                if(reuseOpenMenu(step)){callback.decisionTrace("decision_menu_reused","step="+task.actionStep);task.actionStep++;AgentRuntime.save(callback.getMaid());continue;}
                var preparation=callback.executionTiming.child("decision_prepare");
                var live=callback.decisionLiveState(step);
                if(capture!=null)live.add("binding_proof",capture.fingerprint());
                var tools=new LinkedHashMap<String,DecisionQuestionCompiler.Tool>();
                for(var e:catalog.decisionEntries()) tools.put(e.id(),new DecisionQuestionCompiler.Tool(e.id(),e.summary(),e.anthropicTool().getAsJsonObject("input_schema")));
                var skills=step.alternatives().stream().anyMatch(a->a.tool().equals("use_skill"))?skillCatalog(catalog):null;
                if(skills!=null)live.add("skill_catalog",skills.fingerprint());
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
                if(skills!=null)state.add("skill_candidates",skills.modelView());
                if(checkpoint.has("recent_failures")) state.add("recent_failures",checkpoint.get("recent_failures").deepCopy());
                state.add("observations",callback.decisionObservations());
                state.getAsJsonObject("live").remove("targets");if(capture!=null)state.add("target_candidates",capture.modelTargets());
                int index=task.actionStep;
                return evaluate(client,"action",state,compiled.questions(),live,()->{
                    var current=callback.decisionLiveState(step);if(capture!=null)current.add("binding_proof",TaskTargetBindings.capture(callback,false).fingerprint());if(skills!=null)current.add("skill_catalog",currentSkills().fingerprint());return current;
                },answers->{var selected=compiled.select(answers,settings.view().minConfidence());
                    if(selected==null){fallback("uncertain_or_none");callback.next(client);}else {if(capture!=null)targetBindings.remember(originalStep,capture,selected,live.getAsJsonObject("bindings"));dispatch(selected,client,index,"decision_selected");}});
            }
            if(stageSegment) {stageSegment=false;task.clearActionPlan();callback.next(client);return true;}
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
    private boolean bypass(String reason) {bypassReasons.merge(reason,1,Integer::sum);return false;}
    private String stageKey(){return callback.task.goalVersion+":"+callback.task.completionStage+":"+callback.task.activeCompletion();}
    private boolean routeStage(LLMClient client,ToolCatalogSnapshot catalog) {
        var task=callback.task;
        if(task.completion==null)return bypass("completion_not_registered");
        if(task.steps.isEmpty())return bypass("existing_plan_required");
        if(stageSuppressed.equals(stageKey()))return bypass("stage_delegated_to_flash");
        var session=MaidGuiSessionManager.session(callback.getMaid().getUUID());
        var segments=TaskStageChoices.transfers(task.activeCompletion(),task.activeTransfers(),callback.getMaid().level().dimension().location().toString());
        if(stageContinuation && segments.isEmpty() && AgentCompletion.verify(callback.getMaid(),task,true)) {
            task.currentStep=Math.max(0,task.steps.size()-1);
            AgentRuntime.finished(callback,"已根据当前现场和实际操作记录核验完成。",true);return true;
        }
        if(segments.isEmpty())return bypass("no_supported_pending_transfer_phase");
        if(segments.size()==1 && !stageContinuation && session==null)return bypass("single_choice_no_service_needed");
        var eligible=catalog.decisionEntries().stream().map(e->e.id()).collect(java.util.stream.Collectors.toSet());
        if(!eligible.containsAll(List.of("open_gui","gui_action","close_gui")))return bypass("stage_tools_unavailable");
        var capture=TaskTargetBindings.capture(callback,false);
        var observed=capture.targets().stream().filter(t->t.kind().equals("container")).map(t->t.data().get("position").toString()).collect(java.util.stream.Collectors.toSet());
        if(segments.stream().anyMatch(s->!observed.contains(s.position().toString())))return bypass("phase_targets_not_freshly_observed");
        if(session!=null) {
            var current=segments.stream().filter(s->session.targetPosition!=null
                    && session.targetPosition.getX()==s.position().get(0).getAsInt()
                    && session.targetPosition.getY()==s.position().get(1).getAsInt()
                    && session.targetPosition.getZ()==s.position().get(2).getAsInt()).findFirst();
            if(current.isEmpty())return bypass("existing_menu_outside_pending_phase");
            // Opening this menu already committed the target choice. Reuse it rather than
            // asking either model to select it again or reopening the same container.
            installStage(current.get(),client);return true;
        }
        if(segments.size()==1){installStage(segments.get(0),client);return true;}
        if(segments.size()+1>settings.client().capabilities().maxChoices())return bypass("stage_choice_limit");
        var choices=new LinkedHashMap<String,String>();var candidates=new JsonArray();
        for(var segment:segments) {
            var p=segment.position();double distance=callback.getMaid().distanceToSqr(p.get(0).getAsDouble()+.5,p.get(1).getAsDouble(),p.get(2).getAsDouble()+.5);
            var row=new JsonObject();row.addProperty("id",segment.id());row.add("position",p);row.add("remaining",segment.remaining());row.addProperty("distance_squared",distance);candidates.add(row);
            choices.put(segment.id(),"Execute authorized pending container "+p+", squared travel distance="+distance+", remaining required transfers="+segment.remaining()+". Suitable when this is the shortest feasible travel; every other container stays required.");
        }
        choices.put("llm","A blocker or uncertainty requires the execution planner; do not guess or change requirements.");
        var questions=new JsonObject();questions.add("next_container",DecisionProtocol.choice("Choose only the NEXT container within this already registered same-direction transfer phase. Never select completed work, change quantities, cross a phase boundary, or treat closed-container stock as observed. Prefer the nearest feasible authorized candidate; use llm when uncertain.",choices));
        var state=new JsonObject();state.addProperty("goal",task.goal);state.add("constraints",new Gson().toJsonTree(task.constraints));state.addProperty("completion_stage",task.completionStage);
        state.add("candidates",candidates);state.add("live",DecisionStateProjection.modelView(callback.decisionLiveState()));state.getAsJsonObject("live").remove("targets");
        var live=stageLive();
        boolean routed=evaluate(client,"stage",state,questions,live,this::stageLive,answers->{
            var answer=answers.getAsJsonObject("next_container");
            callback.decisionTrace("decision_stage_answer","choice="+answer.get("choice").getAsString()+"; confidence="+answer.get("confidence").getAsDouble());
            String id=answers.getAsJsonObject("next_container").get("choice").getAsString();
            var chosen=segments.stream().filter(s->s.id().equals(id)).findFirst();
            if(chosen.isEmpty()){stageSuppressed=stageKey();fallback("stage_requires_planner");callback.next(client);return;}
            // Every offered segment is independently authorized by the same committed phase.
            // This confidence describes scheduling preference among required work, not uncertain
            // target/quantity interpretation. The validated highest-probability member is safe to
            // schedule; semantic action/binding decisions retain their strict confidence gate.
            installStage(chosen.get(),client);
        });
        if(!routed)stageSuppressed=stageKey();
        return routed;
    }
    private JsonObject stageLive() {
        var live=callback.decisionLiveState();live.addProperty("completion_stage",callback.task.completionStage);
        live.addProperty("mutation_version",callback.task.appliedMutationVersion);
        live.add("settled_transfers",new Gson().toJsonTree(callback.task.activeTransfers()));
        live.add("binding_proof",TaskTargetBindings.capture(callback,false).fingerprint());return live;
    }
    private void installStage(TaskStageChoices.Segment segment,LLMClient client) {
        var task=callback.task;task.actionSteps=segment.steps().deepCopy();task.actionStep=0;task.actionPlanGoalVersion=task.goalVersion;
        stageSegment=true;stageContinuation=true;stageSegmentPhase=task.completionStage;
        AgentRuntime.save(callback.getMaid());callback.decisionTrace("decision_stage_installed","stage="+stageSegmentPhase+"; target="+segment.position()+"; full_contract_preserved=true; receipts_preserved=true");callback.next(client);
    }
    private boolean evaluate(LLMClient client,String purpose,JsonObject state,JsonObject questions,JsonObject live,Supplier<JsonObject> current,Consumer<JsonObject> accepted) {
        if(disabled || settings.apiKey().isBlank())return fallback("service_unavailable");
        // Action routing already acquires the gate before compiling its state.
        if(!purpose.equals("action") && !DecisionRequestGate.acquire(settings.view().provider(),System.nanoTime()))return fallback("rate_limit");
        var task=callback.task;int version=task.goalVersion,index=task.actionStep;long generation=task.generation;
        var timing=callback.executionTiming.child("decision_request:"+settings.view().provider());pending=true;
        try {
            requestAttempts++;
            settings.client().evaluate(state,questions,callback.operations::add).whenComplete((result,error)->callback.runOnServerThread(()->{
                pending=false;if(callback.stopped()){timing.finish("cancelled",0);return;}
                try {
                    var now=current.get();
                    var previous=live.deepCopy();
                    if(result!=null && error==null){previous=DecisionFreshness.project(previous,purpose,state,result.answers());now=DecisionFreshness.project(now,purpose,state,result.answers());}
                    if(task.generation!=generation || task.goalVersion!=version || task.actionStep!=index || !task.pendingAmendment.isBlank() || !previous.equals(now)) {
                        if(purpose.equals("stage"))stageSuppressed=stageKey();
                        timing.finish("stale",0);callback.decisionTrace("decision_stale_fields",staleFields(previous,now));fallback("stale_observation");callback.next(client);return;
                    }
                    if(error!=null) {
                        if(purpose.equals("stage"))stageSuppressed=stageKey();
                        Throwable cause=error;while(cause.getCause()!=null)cause=cause.getCause();
                        if(cause instanceof NativeDecisionProvider.HttpFailure http){disabled|=http.permanent();if(http.status==429 || http.status==529)DecisionRequestGate.coolDown(settings.view().provider(),System.nanoTime());}
                        else if(cause instanceof IllegalArgumentException)disabled=true;
                        String reason=cause instanceof NativeDecisionProvider.HttpFailure http?"http_"+http.status:cause instanceof java.util.concurrent.TimeoutException || cause instanceof java.net.http.HttpTimeoutException?"timeout":cause instanceof IllegalArgumentException?"invalid_response":"request_error";
                        if(disabled)disabledReason=reason;
                        timing.finish("fallback",0);fallback(reason);callback.next(client);return;
                    }
                    timing.finish("answered",0);callback.decisionTrace("decision_response","purpose="+purpose+"; provider="+settings.view().provider()+"; requested="+settings.view().model()+"; reported="+result.model()+"; input="+result.inputTokens()+"; output="+result.outputTokens()+"; request_id="+result.requestId());
                    com.wjx.touhou_aifun.TouhouAIFun.LOGGER.info("AIFun native decision response: task={} generation={} purpose={} provider={} requested_model={} reported_model={} input_tokens={} output_tokens={} request_id={}",task.id,generation,purpose,settings.view().provider(),settings.view().model(),result.model(),result.inputTokens(),result.outputTokens(),result.requestId());
                    accepted.accept(result.answers());
                }catch(RuntimeException invalid){if(purpose.equals("stage"))stageSuppressed=stageKey();timing.finish("fallback",0);fallback("invalid_response_or_binding");callback.next(client);}
            }));return true;
        }catch(RuntimeException invalid){pending=false;timing.finish("fallback",0);return fallback("request_invalid");}
    }
    private JsonObject fingerprint() {return fingerprint(true);}
    private JsonObject fingerprint(boolean inventory) {var state=callback.decisionLiveState();state.add("binding_proof",TaskTargetBindings.capture(callback,inventory).fingerprint());return state;}
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
        if (!startupTemplates) return bypass("startup_templates_disabled");
        var task=callback.task;
        if(templateDelegated || disabled || settings.apiKey().isBlank())return false;
        if(!task.constraints.isEmpty() && inspecting==null)return amend(client,catalog);
        if(inspecting!=null) {
            if(inspectionRequest!=null) {var request=inspectionRequest;inspectionRequest=null;dispatch(new DecisionQuestionCompiler.Selection("inspect_containers",request),client,-1,"template_inspection");return true;}
            return finishInspection(client);
        }
        if(templateAttemptVersion==task.goalVersion)return false;
        templateAttemptVersion=task.goalVersion;
        // Missing contracts after already committed operations need the owner's complete amended scope.
        if(task.completion==null && (!task.transfers.isEmpty() || task.appliedMutationVersion>0))return false;
        var capture=TaskTargetBindings.capture(callback,false);var schemas=new LinkedHashMap<String,JsonObject>();
        for(var entry:catalog.planningEntries())schemas.put(entry.id(),entry.anthropicTool().getAsJsonObject("input_schema"));
        var start=TaskTemplatePlanner.start(schemas,capture.targets());
        var skills=skillCatalog(catalog);
        if(start==null){if(skills.entries().isEmpty())return false;var questions=new JsonObject();questions.add("template",DecisionProtocol.choice("No supported full template is available; choose the full planner. The skill question may select guidance to read first.",Map.of("llm","Full planning required.")));questions.add("coverage",DecisionProtocol.noul("Is an offered complete executable template available? No: this catalogue offers only full planning with optional skill guidance."));start=new TaskTemplatePlanner.Start(questions,List.of(),Map.copyOf(schemas));}
        var existing=TaskTemplatePlanner.fromCompletion(task.completion);
        if(existing!=null) {
            start.questions().getAsJsonObject("template").getAsJsonObject("criteria").addProperty("contract","Rebuild all steps from the existing immutable FULL completion contract, including already settled quantities; never drop requirements.");
            start.questions().add("coverage",DecisionProtocol.noul("Does the selected template or existing immutable FULL completion contract cover EVERY owner requirement and constraint? A supported contract may combine transfers and final maid state. No for unknown side tasks, ambiguous target scope or unsupported actions. Never weaken a condition."));
        }
        var state=templateState("template_and_target_binding",capture);var observation=callback.decisionObservation("scan_surroundings");var coverage=new JsonObject();
        if(observation!=null)for(String key:List.of("truncated","omitted","omitted_important_target_details","omitted_sign_texts","coverage"))if(observation.has(key))coverage.add(key,observation.get(key));state.add("observation_coverage",coverage);
        var questions=start.questions().deepCopy();var skillQuestion=skills.question(settings.client().capabilities());
        if(skillQuestion!=null){var expanded=questions.deepCopy();expanded.add("skill",skillQuestion);var proposed=state.deepCopy();proposed.add("skill_candidates",skills.modelView());if(StartupItemBatch.fits(settings.view().model(),proposed,expanded,settings.client().capabilities())){questions=expanded;state=proposed;}}
        String goal=task.goal+"\n"+String.join("\n",task.constraints);var sizes=new TreeMap<String,Integer>();var counts=backpack(sizes);
        var batch=StartupItemBatch.prepare(goal,ExplicitItemCatalog.named(goal),sizes,questions.getAsJsonObject("template"));
        var mergedQuestions=batch.append(questions);var mergedState=state.deepCopy();batch.describe(mergedState);mergedState.add("backpack_counts",new Gson().toJsonTree(counts));
        if(!batch.branches().isEmpty() && StartupItemBatch.fits(settings.view().model(),mergedState,mergedQuestions,settings.client().capabilities())){questions=mergedQuestions;state=mergedState;}else batch=new StartupItemBatch.Batch(Map.of());
        var selectedStart=start;var selectedBatch=batch;boolean offersSkill=questions.has("skill");
        var live=fingerprint(false);if(offersSkill)live.add("skill_catalog",skills.fingerprint());
        return evaluate(client,"template",state,questions,live,()->{var current=fingerprint(false);if(offersSkill)current.add("skill_catalog",currentSkills().fingerprint());return current;},answers->{
            if(existing!=null && TaskRecoveryPolicy.confident(answers,"template",settings.view().minConfidence()) && answers.getAsJsonObject("template").get("choice").getAsString().equals("contract")
                    && Boolean.TRUE.equals(TaskRecoveryPolicy.booleanAnswer(answers,"coverage",settings.view().minConfidence()))) {install(existing,client,"contract");return;}
            var selected=TaskTemplatePlanner.select(selectedStart,answers,callback.getMaid().level().dimension().location().toString(),settings.view().minConfidence());
            if(selected==null){
                String skill=offersSkill && TaskRecoveryPolicy.confident(answers,"template",settings.view().minConfidence()) && answers.getAsJsonObject("template").get("choice").getAsString().equals("llm")?skills.select(answers,settings.view().minConfidence()):null;
                if(skill!=null){templateDelegated=true;task.loadedTools.add("use_skill");var args=new JsonObject();args.addProperty("name",skill);callback.decisionTrace("decision_skill_selected","name="+skill+"; full_planning_required=true");dispatch(new DecisionQuestionCompiler.Selection("use_skill",args),client,-1,"skill_guidance");return;}
                fallback("template_uncertain_or_unsupported");callback.next(client);return;
            }
            if(selected.completion()!=null){install(TaskTemplatePlanner.fromCompletion(selected.completion()),client,selected.template());return;}
            inspecting=selected;
            var branch=selectedBatch.branches().get(selected.transferKind());
            if(branch!=null){callback.decisionTrace("decision_startup_merged","template_target_items_quantity=true");acceptExplicitItems(branch.items(),branch.sizes(),selectedBatch.answers(selected.transferKind(),answers),counts,goal,client);}
            else if(!bindExplicitItems(client))inspectSelected(client);
        });
    }
    private static DecisionSkillCatalog.Catalog skillCatalog(ToolCatalogSnapshot catalog) {
        var entry=catalog.planningEntries().stream().filter(e->e.id().equals("use_skill")).findFirst();
        return DecisionSkillCatalog.capture(entry.map(e->e.anthropicTool().getAsJsonObject("input_schema")).orElse(null));
    }
    private DecisionSkillCatalog.Catalog currentSkills(){return skillCatalog(com.wjx.touhou_aifun.compat.ai.openai.ToolContextSelector.snapshot(callback.getMaid(),callback));}
    private boolean amend(LLMClient client,ToolCatalogSnapshot catalog) {
        var task=callback.task;if(templateAttemptVersion==task.goalVersion)return false;templateAttemptVersion=task.goalVersion;
        if(task.amendmentBaseCompletion==null || task.amendmentBaseVersion!=task.goalVersion-1)return false;
        var schemas=new LinkedHashMap<String,JsonObject>();for(var entry:catalog.planningEntries())schemas.put(entry.id(),entry.anthropicTool().getAsJsonObject("input_schema"));
        var sizes=new TreeMap<String,Integer>();backpack(sizes);var capture=TaskTargetBindings.capture(callback,false);
        var start=TaskAmendmentPlanner.start(task.amendmentBaseCompletion,task.amendmentBaseText,capture.targets(),schemas,sizes);if(start==null)return false;
        var state=templateState("owner_amendment",capture);state.add("prior_full_contract",task.amendmentBaseCompletion.deepCopy());state.addProperty("owner_amendment",task.amendmentBaseText);state.add("settled_transfers",new Gson().toJsonTree(task.transfers));
        return evaluate(client,"amendment",state,start.questions(),fingerprint(false),()->fingerprint(false),answers->{
            var plan=TaskAmendmentPlanner.select(start,answers,task.transfers,settings.view().minConfidence());
            if(plan==null){fallback("amendment_unverified_or_unsupported");callback.next(client);return;}
            install(plan,client,"owner_amendment");
        });
    }
    private boolean bindExplicitItems(LLMClient client) {
        String goal=callback.task.goal+"\n"+String.join("\n",callback.task.constraints);boolean receive=inspecting.transferKind().equals("receive");
        var sizes=new TreeMap<String,Integer>();var backpack=backpack(sizes);
        if(receive){sizes.clear();sizes.putAll(ExplicitItemCatalog.named(goal));if(goal.matches("(?is).*?(组|stacks?\\b).*"))return false;}
        var items=List.copyOf(sizes.keySet());if(items.isEmpty() || items.size()>16)return false;
        JsonObject questions;try{questions=TaskTemplatePlanner.itemQuestions(goal,items,sizes);}catch(RuntimeException unsupported){return false;}
        var capture=TaskTargetBindings.capture(callback,false);var state=templateState("explicit_item_and_quantity_binding",capture);state.addProperty("template",inspecting.template());state.addProperty("source_stock_observed",false);state.add("backpack_counts",new Gson().toJsonTree(backpack));state.add("item_candidates",new Gson().toJsonTree(items));var ids=new JsonArray();inspecting.targets().forEach(t->ids.add(t.id()));state.add("selected_targets",ids);
        return evaluate(client,"explicit_items",state,questions,fingerprint(false),()->fingerprint(false),answers->acceptExplicitItems(items,sizes,answers,backpack,goal,client));
    }
    private void acceptExplicitItems(List<String> items,Map<String,Integer> sizes,JsonObject answers,Map<String,Long> backpack,String goal,LLMClient client) {
        if(!Boolean.TRUE.equals(TaskRecoveryPolicy.booleanAnswer(answers,"coverage",settings.view().minConfidence()))){fallback("explicit_quantity_unverified");callback.next(client);return;}
        for(int i=0;i<items.size();i++)if(!TaskRecoveryPolicy.confident(answers,"item_"+i,settings.view().minConfidence()) || answers.getAsJsonObject("item_"+i).get("choice").getAsString().equals("unknown")){fallback("explicit_quantity_unverified");callback.next(client);return;}
        var plan=TaskTemplatePlanner.explicitTransfers(inspecting,items,answers,backpack,goal,settings.view().minConfidence(),sizes);
        if(plan!=null){callback.decisionTrace("decision_inspection_skipped","explicit_quantities=true");install(plan,client,inspecting.template());return;}
        explicitItems=items;explicitAnswers=answers.deepCopy();inspectSelected(client);
    }
    private Map<String,Long> backpack(Map<String,Integer> sizes) {
        var out=new TreeMap<String,Long>();var inventory=callback.getMaid().getAvailableBackpackInv();for(int i=0;i<inventory.getSlots();i++){var stack=inventory.getStackInSlot(i);if(!stack.isEmpty()){out.merge(com.wjx.touhou_aifun.maid.gui.MaidGuiSession.itemId(stack),(long)stack.getCount(),Long::sum);TaskTargetBindings.recordStackSize(sizes,stack);}}return out;
    }
    private void inspectSelected(LLMClient client) {
        var request=new JsonObject();var targets=new JsonArray();request.add("targets",targets);
        // End on the first execution target, so its live menu can be used directly.
        for(int i=inspecting.targets().size()-1;i>=0;i--){var target=inspecting.targets().get(i);var row=new JsonObject();var xyz=target.data().getAsJsonArray("position");for(int n=0;n<3;n++)row.add(List.of("x","y","z").get(n),xyz.get(n));row.add("target",target.data().get("registry_id"));targets.add(row);}
        if(MaidGuiSessionManager.session(callback.getMaid().getUUID())!=null){inspectionRequest=request;dispatch(new DecisionQuestionCompiler.Selection("close_gui",sessionArgs()),client,-1,"template_cleanup");return;}
        dispatch(new DecisionQuestionCompiler.Selection("inspect_containers",request),client,-1,"template_inspection");
    }
    private boolean reuseOpenMenu(ActionPlan.Step step) {
        if(step.skipIf()==null || com.wjx.touhou_aifun.chat.agent.AgentCompletionRules.leaves(step.skipIf()).stream().anyMatch(c->!AgentTaskState.completionKind(c).equals("transfer")) || step.alternatives().size()!=1 || !step.alternatives().get(0).tool().equals("open_gui"))return false;var args=step.alternatives().get(0).arguments();
        if(args.has("item_slot") || !args.has("x") || !args.has("y") || !args.has("z") || step.targetBinding()!=null)return false;
        if(!Set.of("x","y","z","target","coordinate_space","wait_policy","max_distance").containsAll(args.keySet()) || !args.has("coordinate_space") || !args.get("coordinate_space").getAsString().equals("world"))return false;
        var session=MaidGuiSessionManager.session(callback.getMaid().getUUID());if(session==null || !(session.callback instanceof TaskCallback task) || task.task!=callback.task || task.stopped() || session.targetPosition==null || !session.menu().stillValid(session.actor))return false;
        var pos=com.wjx.touhou_aifun.maid.gui.GuiBlockTarget.coordinates(args,callback.getMaid().blockPosition());var level=callback.getMaid().level();
        int radius=args.has("max_distance")?args.get("max_distance").getAsInt():12;if(radius<1 || radius>16 || callback.getMaid().distanceToSqr(pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5)>radius*radius)return false;
        return pos.equals(session.targetPosition) && callback.getMaid().isWithinRestriction(pos) && level.hasChunkAt(pos)
                && (!args.has("target") || args.get("target").getAsString().equals(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString()));
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
            stocks.add(counts);if(inspecting.transferKind().equals("receive")){items.addAll(counts.keySet());TaskTargetBindings.containerStackSizes(callback,target,stackSizes);}
        }
        var backpack=new TreeMap<String,Long>();var inventory=callback.getMaid().getAvailableBackpackInv();for(int i=0;i<inventory.getSlots();i++){var stack=inventory.getStackInSlot(i);if(!stack.isEmpty())backpack.merge(com.wjx.touhou_aifun.maid.gui.MaidGuiSession.itemId(stack),(long)stack.getCount(),Long::sum);}
        if(inspecting.transferKind().equals("deliver"))items.addAll(backpack.keySet());
        if(inspecting.transferKind().equals("deliver"))for(int i=0;i<inventory.getSlots();i++)TaskTargetBindings.recordStackSize(stackSizes,inventory.getStackInSlot(i));
        if(explicitAnswers!=null){var plan=TaskTemplatePlanner.transfers(inspecting,explicitItems,explicitAnswers,stocks,backpack,callback.task.goal+"\n"+String.join("\n",callback.task.constraints),settings.view().minConfidence(),stackSizes);explicitAnswers=null;explicitItems=null;if(plan==null)return fallback("template_quantity_or_scope_unverified");install(plan,client,inspecting.template());return true;}
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
        task.steps=plan.steps().asList().stream().map(v->v.getAsJsonObject().get("description").getAsString()).toList();task.currentStep=0;inspecting=null;explicitItems=null;explicitAnswers=null;
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
        if(stageSegment)stageSuppressed=stageKey();
        stageSegment=false;stageContinuation=false;bypassReasons.merge(reason,1,Integer::sum);
        if(reason.equals("service_unavailable") || disabled)serviceDiagnostic(reason);
        callback.executionTiming.child("decision_fallback:"+reason).finish("fallback",0);
        templateDelegated=true;templateAttemptVersion=callback.task.goalVersion;inspecting=null;inspectionRequest=null;explicitItems=null;explicitAnswers=null;failedStep=null;recoveryPrepared=false;targetBindings.clear();
        callback.task.clearActionPlan();callback.decisionTrace("decision_fallback",reason);return false;
    }
    /** Once per callback generation, including bypassed routing; no secrets, URLs or owner text. */
    private void serviceDiagnostic(String reason) {
        if(serviceDiagnosticRecorded)return;
        serviceDiagnosticRecorded=true;
        var detail=new JsonObject();detail.addProperty("reason",reason);
        detail.addProperty("snapshot_age_ms",java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-snapshotCreated));
        detail.addProperty("request_attempts",requestAttempts);detail.addProperty("disabled",disabled);detail.addProperty("disabled_reason",disabledReason);
        detail.addProperty("planner_handoffs",plannerHandoffs);detail.addProperty("startup_templates",startupTemplates);
        detail.add("bypass_reasons",new Gson().toJsonTree(bypassReasons));
        detail.add("captured",diagnosticView(settings));
        try {
            var current=DecisionServiceSettings.snapshot();detail.add("current",diagnosticView(current));
            var changed=new JsonArray();var old=settings.view();var now=current.view();
            if(old.enabled()!=now.enabled())changed.add("enabled");
            if(!old.provider().equals(now.provider()))changed.add("provider");
            if(!old.endpoint().equals(now.endpoint()))changed.add("endpoint");
            if(!old.model().equals(now.model()))changed.add("model");
            if(!settings.apiKey().equals(current.apiKey()))changed.add("key");
            if(old.timeoutMs()!=now.timeoutMs())changed.add("timeout");
            if(old.minConfidence()!=now.minConfidence())changed.add("confidence");
            detail.add("changed_fields",changed);
        }catch(RuntimeException unavailable){detail.addProperty("current_read_error",unavailable.getClass().getSimpleName());}
        com.wjx.touhou_aifun.TouhouAIFun.LOGGER.warn("AIFun native decision diagnostic: maid={} task={} generation={} {}",callback.getMaid().getUUID(),callback.task.id,callback.task.generation,detail);
        try {callback.decisionDiagnostic(detail);}
        catch(RuntimeException unavailable){com.wjx.touhou_aifun.TouhouAIFun.LOGGER.warn("AIFun native decision diagnostic archive unavailable: task={}; type={}",callback.task.id,unavailable.getClass().getSimpleName());}
    }
    private static JsonObject diagnosticView(DecisionServiceSettings.Snapshot snapshot) {
        var result=new JsonObject();var view=snapshot.view();
        result.addProperty("provider",view.provider());result.addProperty("enabled",view.enabled());
        result.addProperty("model",view.model());
        result.addProperty("has_key",!snapshot.apiKey().isBlank());result.addProperty("view_has_key",view.hasKey());
        result.addProperty("timeout_ms",view.timeoutMs());return result;
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
            task.actionStep++;
            if(!stageSegment)task.currentStep=Math.min(task.actionStep,Math.max(0,task.steps.size()-1));
            AgentRuntime.save(callback.getMaid());
        }
    }
}
