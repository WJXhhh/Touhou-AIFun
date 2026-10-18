package com.wjx.touhou_aifun.chat.agent;

import java.util.*;

/** Durable facts only: no callback, world, GUI session, image or future is serialized. */
public final class AgentTaskState {
    public enum Status { queued, running, waiting, paused, completed, failed, cancelled }
    public String id = UUID.randomUUID().toString();
    public String goal;
    public Status status = Status.queued;
    public long generation;
    public int goalVersion = 1;
    public String phase = "";
    public String outcome = "";
    public List<String> constraints = new ArrayList<>();
    public List<String> steps = new ArrayList<>();
    public int currentStep;
    public com.google.gson.JsonArray actionSteps = new com.google.gson.JsonArray();
    public int actionStep;
    public int actionPlanGoalVersion;
    public List<com.google.gson.JsonObject> actionFacts = new ArrayList<>();
    public List<String> failures=new ArrayList<>();
    public List<String> evidence = new ArrayList<>();
    public Set<String> loadedTools = new LinkedHashSet<>();
    public String pendingAmendment = "";
    public com.google.gson.JsonObject pendingCompletion;
    public com.google.gson.JsonObject completion;
    public com.google.gson.JsonObject amendmentBaseCompletion;
    public int amendmentBaseVersion;
    public String amendmentBaseText="";
    public int amendmentBaseStage = -1;
    public Map<String,Long> amendmentBaseTransferBase = new LinkedHashMap<>();
    public com.google.gson.JsonArray amendmentCompletedPhases = new com.google.gson.JsonArray();
    public long verifiedCount;
    public long appliedMutationVersion;
    public Map<String, Long> transfers = new LinkedHashMap<>();
    public int completionStage;
    public Map<String,Long> completionTransferBase = new LinkedHashMap<>();
    public Map<String,String> initialContainerSnapshots = new LinkedHashMap<>();
    public void validatePhaseCheckpoint() {
        int stages=completion!=null && completionKind(completion).equals("sequence")?completion.getAsJsonArray("phases").size():1;
        if(completionStage<0 || completionStage>=stages || completionTransferBase==null || completionTransferBase.size()>512
                || completionTransferBase.values().stream().anyMatch(Objects::isNull) || initialContainerSnapshots==null || initialContainerSnapshots.size()>128
                || amendmentBaseStage < -1 || amendmentBaseStage > 7 || amendmentBaseTransferBase==null || amendmentBaseTransferBase.size()>512
                || amendmentBaseTransferBase.values().stream().anyMatch(Objects::isNull)
                || amendmentCompletedPhases==null || amendmentCompletedPhases.size()>7
                || amendmentBaseStage>=0 && amendmentCompletedPhases.size()!=amendmentBaseStage
                || initialContainerSnapshots.values().stream().anyMatch(v->v==null || !v.matches("[0-9a-f]{64}")))
            throw new IllegalArgumentException("invalid_task_phase_checkpoint");
    }
    public com.google.gson.JsonObject activeCompletion() {
        return completion!=null && completionKind(completion).equals("sequence")
                ? completion.getAsJsonArray("phases").get(completionStage).getAsJsonObject() : completion;
    }
    public Map<String,Long> activeTransfers() {
        if(completion==null || !completionKind(completion).equals("sequence")) return transfers;
        Map<String,Long> relative=new LinkedHashMap<>(transfers);
        completionTransferBase.forEach((key,value)->relative.merge(key,-value,Long::sum));return relative;
    }
    public void recordResult(String raw) {
        try { recordObject(com.google.gson.JsonParser.parseString(raw).getAsJsonObject()); }
        catch (RuntimeException ignored) { }
    }
    private void recordObject(com.google.gson.JsonObject value) {
        if(value.has("kind") && value.get("kind").getAsString().equals("eat_food") && value.has("success")
                && value.get("success").getAsBoolean() && value.has("servings_consumed") && value.get("servings_consumed").getAsInt()>0) {
            var previous=actionFacts.stream().filter(f->java.util.Objects.equals(f.get("dimension"),value.get("dimension"))
                    && java.util.Objects.equals(f.get("block_id"),value.get("block_id")) && java.util.Objects.equals(f.get("position"),value.get("position"))).findFirst();
            if(previous.isPresent()) {
                var fact=previous.get();fact.addProperty("servings_consumed",fact.get("servings_consumed").getAsLong()+value.get("servings_consumed").getAsLong());
                fact.addProperty("finished",fact.get("finished").getAsBoolean() || value.get("finished").getAsBoolean());
            }else {
                if(actionFacts.size()>=256) throw new IllegalStateException("task_action_fact_limit");
                actionFacts.add(value.deepCopy());
            }
        }
        if (value.has("action_results")) {
            for (var item : value.getAsJsonArray("action_results")) {
                var step = item.getAsJsonObject().deepCopy();
                for (String key : List.of("dimension", "target_position"))
                    if (value.has(key)) step.add(key, value.get(key));
                recordObject(step);
            }
        }
        if (!value.has("external_transfer") || !value.get("external_transfer").getAsBoolean() || value.has("error") || !value.has("moved_count") || !value.has("moved_item")
                || !value.has("to_maid") || !value.has("dimension") || !value.has("target_position")) return;
        String target = value.get("dimension").getAsString() + ":" + value.get("target_position") + ":" + value.get("moved_item").getAsString();
        long signed = (value.get("to_maid").getAsBoolean() ? 1 : -1) * (long) Math.max(0, value.get("moved_count").getAsInt());
        transfers.merge(target, signed, Long::sum);
        if (completion != null) refreshTransferProgress();
    }
    public boolean completionSatisfied() {
        return completion != null && pendingAmendment.isBlank()
                && (!completionKind(completion).equals("sequence") || completionStage==completion.getAsJsonArray("phases").size()-1)
                && transferSatisfied(activeCompletion());
    }
    public boolean requiresCompletionReview() {
        return completion == null && pendingAmendment.isBlank() && (!transfers.isEmpty() || appliedMutationVersion > 0);
    }
    public void refreshTransferProgress() { verifiedCount=completion==null?0:transferProgress(activeCompletion()); }
    public void defineCompletion(com.google.gson.JsonObject spec) {
        var validated=validateCompletion(spec);
        if(validated==null) throw new IllegalArgumentException("completion_required");
        if(completion!=null) {
            if(completion.equals(validated)) return;
            throw new IllegalArgumentException("completion_already_defined_owner_amendment_required");
        }
        if(goalVersion==1 && (!transfers.isEmpty() || appliedMutationVersion>0))
            throw new IllegalArgumentException("completion_must_precede_transfers_owner_review_required");
        var progress=amendedProgress(validated,amendmentBaseStage,amendmentBaseTransferBase,amendmentCompletedPhases);
        completion=validated;completionStage=progress.stage();completionTransferBase=progress.base();initialContainerSnapshots.clear();refreshTransferProgress();
    }
    private record PhaseProgress(int stage,Map<String,Long> base) { }
    /** An amendment edits this task, so its current phase keeps its original receipt origin. */
    private PhaseProgress amendedProgress(com.google.gson.JsonObject next,int stage,Map<String,Long> base,com.google.gson.JsonArray prefix) {
        // Starting a newly ordered job from a non-sequence still starts a new receipt scope.
        if(stage<0)return new PhaseProgress(0,completionKind(next).equals("sequence")?new LinkedHashMap<>(transfers):new LinkedHashMap<>());
        if(base==null || prefix==null)throw new IllegalArgumentException("sequence_amendment_progress_unavailable: retained receipts require owner review; no actions started");
        if(!completionKind(next).equals("sequence")) {
            if(stage==0)return new PhaseProgress(0,new LinkedHashMap<>());
            throw new IllegalArgumentException("sequence_amendment_must_keep_completed_phases: retain the verified prefix and add changes to the active or future phase");
        }
        var phases=next.getAsJsonArray("phases");
        if(stage>=phases.size())throw new IllegalArgumentException("sequence_amendment_must_keep_completed_phases");
        for(int i=0;i<stage;i++)if(!prefix.get(i).equals(phases.get(i)))
            throw new IllegalArgumentException("sequence_amendment_must_keep_completed_phases: retain the verified prefix and add changes to the active or future phase");
        return new PhaseProgress(stage,new LinkedHashMap<>(base));
    }
    private com.google.gson.JsonArray completedPhasePrefix() {
        var prefix=new com.google.gson.JsonArray();
        if(completion!=null && completionKind(completion).equals("sequence"))for(int i=0;i<completionStage;i++)prefix.add(completion.getAsJsonArray("phases").get(i).deepCopy());
        return prefix;
    }
    private long transferProgress(com.google.gson.JsonObject condition) {
        if(completionKind(condition).equals("all")) return condition.getAsJsonArray("conditions").asList().stream().mapToLong(c->transferProgress(c.getAsJsonObject())).sum();
        if(!completionKind(condition).equals("transfer")) return 0;
        String key=condition.get("dimension").getAsString()+":"+condition.get("position")+":"+condition.get("item").getAsString();
        return activeTransfers().getOrDefault(key,0L)*(condition.get("to_maid").getAsBoolean()?1:-1);
    }
    private boolean transferSatisfied(com.google.gson.JsonObject condition) {
        if(completionKind(condition).equals("all")) return condition.getAsJsonArray("conditions").asList().stream().allMatch(c->transferSatisfied(c.getAsJsonObject()));
        return completionKind(condition).equals("transfer") && transferProgress(condition)>=condition.get("count").getAsLong();
    }
    public static com.google.gson.JsonObject validateCompletion(com.google.gson.JsonObject spec) {
        if (spec == null) return null;
        try {
            String kind=completionKind(spec);
            if(!spec.has("kind") && (spec.has("conditions") || spec.has("phases")))
                throw new IllegalArgumentException("invalid_completion_condition: composite requires explicit kind=all or kind=sequence; keep every owner requirement and fill missing leaf fields");
            var required=new ArrayList<String>();
            if(!Set.of("all","sequence").contains(kind))required.add("dimension");
            if(Set.of("transfer","inventory").contains(kind))required.addAll(List.of("item","count"));
            if(Set.of("transfer","position","container_unchanged").contains(kind))required.add("position");
            if(kind.equals("transfer"))required.add("to_maid");
            if(kind.equals("maid_state"))required.addAll(List.of("state","value"));
            if(kind.equals("eat_food"))required.addAll(List.of("food","count"));
            var missing=required.stream().filter(key->!spec.has(key) || spec.get(key).isJsonNull()).toList();
            if(!missing.isEmpty())throw new IllegalArgumentException("invalid_completion_condition: "+kind+" missing "+missing+"; EVERY transfer needs dimension,item,count,to_maid,position=[observed container x,y,z]. Keep ALL owner requirements; repair fields, never remove conditions");
            if(kind.equals("sequence")) {
                var phases=spec.getAsJsonArray("phases");
                if(phases==null || phases.size()<2 || phases.size()>8) throw new IllegalArgumentException();
                for(var phase:phases) {
                    var child=phase.getAsJsonObject();if(completionKind(child).equals("sequence"))throw new IllegalArgumentException();validateCompletion(child);
                    // Consumption facts currently have lifetime scope, so they cannot prove repeated ordered phases.
                    if(AgentCompletionRules.leaves(child).stream().anyMatch(c->completionKind(c).equals("eat_food")))throw new IllegalArgumentException();
                }
                return spec.deepCopy();
            }
            if(kind.equals("all")) {
                var conditions=spec.getAsJsonArray("conditions");
                if(conditions==null || conditions.isEmpty() || conditions.size()>16) throw new IllegalArgumentException();
                for(var value:conditions) { var child=value.getAsJsonObject(); if(Set.of("all","sequence").contains(completionKind(child))) throw new IllegalArgumentException(); validateCompletion(child); }
                return spec.deepCopy();
            }
            if (spec.get("dimension").getAsString().isBlank()) throw new IllegalArgumentException();
            if(kind.equals("backpack_empty")) return spec.deepCopy();
            if(kind.equals("container_unchanged")) {
                if(spec.getAsJsonArray("position").size()!=3)throw new IllegalArgumentException();
                spec.getAsJsonArray("position").forEach(v->{if(v.getAsDouble()!=v.getAsInt())throw new IllegalArgumentException();});return spec.deepCopy();
            }
            if(kind.equals("maid_state")) {
                String state=spec.get("state").getAsString(),value=spec.get("value").getAsString();
                if(!Set.of("follow","sit","schedule","work_task").contains(state) || value.isBlank() || value.length()>128
                        || Set.of("follow","sit").contains(state) && !Set.of("true","false").contains(value)) throw new IllegalArgumentException();
                return spec.deepCopy();
            }
            if(kind.equals("eat_food")) {
                if(spec.get("food").getAsString().isBlank() || spec.get("count").getAsInt()<1 || spec.get("count").getAsInt()>4096
                        || spec.get("count").getAsDouble()!=spec.get("count").getAsInt()
                        || spec.has("finished") && !spec.get("finished").getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException();
                if(spec.has("position")) {if(spec.getAsJsonArray("position").size()!=3) throw new IllegalArgumentException();
                    spec.getAsJsonArray("position").forEach(v->{if(v.getAsDouble()!=v.getAsInt()) throw new IllegalArgumentException();});}
                return spec.deepCopy();
            }
            if (!Set.of("transfer","position","inventory").contains(kind)) throw new IllegalArgumentException();
            if (!kind.equals("position") && (spec.get("item").getAsString().isBlank() || spec.get("count").getAsLong() < (kind.equals("inventory") && spec.has("match") && spec.get("match").getAsString().equals("exact")?0:1)
                    || spec.get("count").getAsDouble() != spec.get("count").getAsLong())) throw new IllegalArgumentException();
            if(kind.equals("inventory") && spec.has("match") && !Set.of("exact","at_least").contains(spec.get("match").getAsString()))throw new IllegalArgumentException();
            if (!kind.equals("inventory")) {
                if(spec.getAsJsonArray("position").size()!=3) throw new IllegalArgumentException();
                spec.getAsJsonArray("position").forEach(v -> { if(v.getAsDouble()!=v.getAsInt()) throw new IllegalArgumentException(); });
            }
            if(kind.equals("transfer") && !spec.get("to_maid").getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException();
            if(kind.equals("position") && spec.has("radius") && !(spec.get("radius").getAsDouble()>=0 && spec.get("radius").getAsDouble()<=8)) throw new IllegalArgumentException();
            return spec.deepCopy();
        } catch (RuntimeException invalid) {
            if(invalid instanceof IllegalArgumentException && invalid.getMessage()!=null && invalid.getMessage().startsWith("invalid_completion_condition:"))throw invalid;
            throw new IllegalArgumentException("invalid_completion_condition");
        }
    }
    public static String completionKind(com.google.gson.JsonObject spec) { return spec.has("kind") ? spec.get("kind").getAsString() : "transfer"; }
    public AgentTaskState() { }
    public AgentTaskState(String goal) {
        if (goal == null || goal.isBlank()) throw new IllegalArgumentException("goal_required");
        if (goal.codePointCount(0, goal.length()) > 4096) throw new IllegalArgumentException("task_goal_limit: complete owner requirements exceed 4096 characters; no task was accepted");
        this.goal = goal;
    }

    /** Guard matching transfer requirements using durable facts, independent of model history/call IDs. */
    public void checkTransferAllowance(String dimension, com.google.gson.JsonArray position, String item,
                                       boolean receive, long amount, boolean nativeBatch) {
        if (amount <= 0) return;
        long required = -1;
        String key = dimension + ":" + position + ":" + item;
        for (var condition : AgentCompletionRules.leaves(activeCompletion())) {
            if (completionKind(condition).equals("transfer") && AgentCompletionRules.transferKey(condition).equals(key)
                    && condition.get("to_maid").getAsBoolean() == receive)
                required = Math.max(required, condition.get("count").getAsLong());
        }
        // Intermediate inputs and other actions may have a different completion predicate.
        if (required < 0) {
            if(completion!=null && completionKind(completion).equals("sequence")) {
                for(int i=0;i<completionStage;i++)for(var prior:AgentCompletionRules.leaves(completion.getAsJsonArray("phases").get(i).getAsJsonObject()))
                    if(completionKind(prior).equals("transfer") && AgentCompletionRules.transferKey(prior).equals(key)
                            && prior.get("to_maid").getAsBoolean()==receive)
                        throw new IllegalArgumentException("task_transfer_exceeds_remaining: completed_phase; recorded transfers must not be replayed");
            }
            return;
        }
        long remaining = Math.max(0, required - activeTransfers().getOrDefault(key, 0L) * (receive ? 1 : -1));
        if (remaining == 0 || !nativeBatch && amount > remaining)
            throw new IllegalArgumentException("task_transfer_exceeds_remaining: remaining=" + remaining + "; recorded transfers must not be replayed");
    }

    public void checkConsumptionAllowance(String dimension, String food, com.google.gson.JsonArray position) {
        boolean recorded=actionFacts.stream().anyMatch(f -> f.has("dimension") && f.get("dimension").getAsString().equals(dimension)
                && f.has("block_id") && f.get("block_id").getAsString().equals(food) && f.has("position")
                && f.getAsJsonObject("position").get("x").getAsInt()==position.get(0).getAsInt()
                && f.getAsJsonObject("position").get("y").getAsInt()==position.get(1).getAsInt()
                && f.getAsJsonObject("position").get("z").getAsInt()==position.get(2).getAsInt());
        if(!recorded && actionFacts.size()>=256) throw new IllegalArgumentException("task_action_fact_limit: no new consumption started; existing receipts retained");
        boolean matched=false;
        for (var condition : AgentCompletionRules.leaves(activeCompletion())) {
            if (!completionKind(condition).equals("eat_food") || !condition.get("dimension").getAsString().equals(dimension)
                    || !condition.get("food").getAsString().equals(food)
                    || condition.has("position") && !condition.get("position").equals(position)) continue;
            matched=true;
            if(condition.has("finished") && condition.get("finished").getAsBoolean()) return;
            long consumed=0;
            for (var fact : actionFacts) {
                if (!Objects.equals(fact.get("dimension"),condition.get("dimension")) || !fact.has("block_id")
                        || !fact.get("block_id").getAsString().equals(food)) continue;
                if (condition.has("position")) {
                    var actual=fact.getAsJsonObject("position");var expected=condition.getAsJsonArray("position");
                    if(actual==null || actual.get("x").getAsInt()!=expected.get(0).getAsInt() || actual.get("y").getAsInt()!=expected.get(1).getAsInt()
                            || actual.get("z").getAsInt()!=expected.get(2).getAsInt()) continue;
                }
                consumed+=fact.get("servings_consumed").getAsLong();
            }
            if (consumed<condition.get("count").getAsLong()) return;
        }
        if(matched) throw new IllegalArgumentException("task_consumption_already_recorded: completed servings must not be replayed");
    }
    public boolean terminal() { return status == Status.completed || status == Status.failed || status == Status.cancelled; }
    public void pause(String reason) { if (!terminal()) { status = Status.paused; generation++; outcome = bounded(reason, 1024); } }
    public void checkpoint(String fact) {
        evidence.add(bounded(fact, 2048));
        while (evidence.size() > 64) evidence.remove(0);
    }
    public void amend(String text) { amend(text, null); }
    public void amend(String text, com.google.gson.JsonObject spec) {
        if (constraints.size() >= 64) throw new IllegalArgumentException("task_constraint_limit");
        var validated = validateCompletion(spec);
        if(validated!=null)amendedProgress(validated,completion==null?amendmentBaseStage:completionKind(completion).equals("sequence")?completionStage:-1,
                completion==null?amendmentBaseTransferBase:completionTransferBase,completion==null?amendmentCompletedPhases:completedPhasePrefix());
        String combined = pendingAmendment.isBlank() ? text : pendingAmendment + "\n" + text;
        if (combined == null || combined.length() > 4096) throw new IllegalArgumentException("pending_constraint_limit");
        pendingAmendment = combined; pendingCompletion = validated;
    }
    public void applyAmendment() {
        if (!pendingAmendment.isBlank()) {
            if(completion!=null) {
                amendmentBaseStage=completionKind(completion).equals("sequence")?completionStage:-1;
                amendmentCompletedPhases=completedPhasePrefix();amendmentBaseTransferBase=new LinkedHashMap<>(completionTransferBase);}
            amendmentBaseCompletion=completion==null?null:completion.deepCopy();amendmentBaseVersion=goalVersion;amendmentBaseText=pendingAmendment;
            var progress=pendingCompletion==null?new PhaseProgress(0,new LinkedHashMap<String,Long>())
                    :amendedProgress(pendingCompletion,amendmentBaseStage,amendmentBaseTransferBase,amendmentCompletedPhases);
            constraints.add(pendingAmendment); pendingAmendment = ""; goalVersion++; completion = pendingCompletion; pendingCompletion = null;
            clearActionPlan();
            completionStage=progress.stage();completionTransferBase=progress.base();initialContainerSnapshots.clear();
            refreshTransferProgress(); }
    }
    public void clearActionPlan() { actionSteps=new com.google.gson.JsonArray();actionStep=0;actionPlanGoalVersion=0; }
    public static String bounded(String value, int max) {
        if (value == null) return "";
        int n = value.codePointCount(0, value.length());
        return n <= max ? value : value.substring(0, value.offsetByCodePoints(0, max));
    }
}
