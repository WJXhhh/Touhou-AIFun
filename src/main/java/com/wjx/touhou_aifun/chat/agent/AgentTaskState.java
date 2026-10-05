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
    public List<String> failures=new ArrayList<>();
    public List<String> evidence = new ArrayList<>();
    public Set<String> loadedTools = new LinkedHashSet<>();
    public String pendingAmendment = "";
    public com.google.gson.JsonObject pendingCompletion;
    public com.google.gson.JsonObject completion;
    public long verifiedCount;
    public long appliedMutationVersion;
    public Map<String, Long> transfers = new LinkedHashMap<>();
    public void recordResult(String raw) {
        try { recordObject(com.google.gson.JsonParser.parseString(raw).getAsJsonObject()); }
        catch (RuntimeException ignored) { }
    }
    private void recordObject(com.google.gson.JsonObject value) {
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
        if (completion != null) verifiedCount=transferProgress(completion);
    }
    public boolean completionSatisfied() {
        return completion != null && pendingAmendment.isBlank() && transferSatisfied(completion);
    }
    public boolean requiresCompletionReview() {
        return completion == null && pendingAmendment.isBlank() && (!transfers.isEmpty() || appliedMutationVersion > 0);
    }
    public void refreshTransferProgress() { verifiedCount=completion==null?0:transferProgress(completion); }
    public void defineCompletion(com.google.gson.JsonObject spec) {
        var validated=validateCompletion(spec);
        if(validated==null) throw new IllegalArgumentException("completion_required");
        if(completion!=null) {
            if(completion.equals(validated)) return;
            throw new IllegalArgumentException("completion_already_defined_owner_amendment_required");
        }
        if(goalVersion==1 && (!transfers.isEmpty() || appliedMutationVersion>0))
            throw new IllegalArgumentException("completion_must_precede_transfers_owner_review_required");
        completion=validated;refreshTransferProgress();
    }
    private long transferProgress(com.google.gson.JsonObject condition) {
        if(completionKind(condition).equals("all")) return condition.getAsJsonArray("conditions").asList().stream().mapToLong(c->transferProgress(c.getAsJsonObject())).sum();
        if(!completionKind(condition).equals("transfer")) return 0;
        String key=condition.get("dimension").getAsString()+":"+condition.get("position")+":"+condition.get("item").getAsString();
        return transfers.getOrDefault(key,0L)*(condition.get("to_maid").getAsBoolean()?1:-1);
    }
    private boolean transferSatisfied(com.google.gson.JsonObject condition) {
        if(completionKind(condition).equals("all")) return condition.getAsJsonArray("conditions").asList().stream().allMatch(c->transferSatisfied(c.getAsJsonObject()));
        return completionKind(condition).equals("transfer") && transferProgress(condition)>=condition.get("count").getAsLong();
    }
    public static com.google.gson.JsonObject validateCompletion(com.google.gson.JsonObject spec) {
        if (spec == null) return null;
        try {
            String kind=completionKind(spec);
            if(kind.equals("all")) {
                var conditions=spec.getAsJsonArray("conditions");
                if(conditions==null || conditions.isEmpty() || conditions.size()>16) throw new IllegalArgumentException();
                for(var value:conditions) { var child=value.getAsJsonObject(); if(completionKind(child).equals("all")) throw new IllegalArgumentException(); validateCompletion(child); }
                return spec.deepCopy();
            }
            if (!Set.of("transfer","position","inventory").contains(kind) || spec.get("dimension").getAsString().isBlank()) throw new IllegalArgumentException();
            if (!kind.equals("position") && (spec.get("item").getAsString().isBlank() || spec.get("count").getAsLong() <= 0
                    || spec.get("count").getAsDouble() != spec.get("count").getAsLong())) throw new IllegalArgumentException();
            if (!kind.equals("inventory")) {
                if(spec.getAsJsonArray("position").size()!=3) throw new IllegalArgumentException();
                spec.getAsJsonArray("position").forEach(v -> { if(v.getAsDouble()!=v.getAsInt()) throw new IllegalArgumentException(); });
            }
            if(kind.equals("transfer") && !spec.get("to_maid").getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException();
            if(kind.equals("position") && spec.has("radius") && !(spec.get("radius").getAsDouble()>=0 && spec.get("radius").getAsDouble()<=8)) throw new IllegalArgumentException();
            return spec.deepCopy();
        } catch (RuntimeException invalid) { throw new IllegalArgumentException("invalid_completion_condition"); }
    }
    public static String completionKind(com.google.gson.JsonObject spec) { return spec.has("kind") ? spec.get("kind").getAsString() : "transfer"; }
    public AgentTaskState() { }
    public AgentTaskState(String goal) { this.goal = bounded(goal, 4096); }
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
        String combined = pendingAmendment.isBlank() ? text : pendingAmendment + "\n" + text;
        if (combined == null || combined.length() > 4096) throw new IllegalArgumentException("pending_constraint_limit");
        pendingAmendment = combined; pendingCompletion = validated;
    }
    public void applyAmendment() {
        if (!pendingAmendment.isBlank()) { constraints.add(pendingAmendment); pendingAmendment = ""; goalVersion++; completion = pendingCompletion; pendingCompletion = null;
            verifiedCount = 0;
            if (completion != null) verifiedCount=transferProgress(completion); }
    }
    public static String bounded(String value, int max) {
        if (value == null) return "";
        int n = value.codePointCount(0, value.length());
        return n <= max ? value : value.substring(0, value.offsetByCodePoints(0, max));
    }
}
