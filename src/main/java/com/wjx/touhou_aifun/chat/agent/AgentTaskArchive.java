package com.wjx.touhou_aifun.chat.agent;

import java.util.*;

/** Durable execution record. Raw evidence is bounded separately from the model's context. */
public final class AgentTaskArchive {
    public record Record(long tick, long generation, String kind, String callId, String tool,
                         String argumentsRef, String resultRef, String summary) { }
    public int version = 1;
    public String maidId;
    public String taskId;
    public boolean terminal;
    public long updatedTick;
    public List<Record> records = new ArrayList<>();
    public TaskResultStore.State results;
    /** The executor's own written text; inspectable, never displayed or spoken as chat. */
    public List<String> narration = new ArrayList<>();
    public long mutationVersion;
    public Map<String,Long> verifiedTransfers = new LinkedHashMap<>();
    public List<com.google.gson.JsonObject> verifiedActionFacts = new ArrayList<>();
    public int verifiedCompletionStage;
    public int verifiedCompletionGoalVersion;
    public Map<String,Long> verifiedTransferBase = new LinkedHashMap<>();
    public Map<String,String> verifiedContainerSnapshots = new LinkedHashMap<>();
    private transient int recordBytes;

    public AgentTaskArchive() { }
    public AgentTaskArchive(String maidId, String taskId) { this.maidId = maidId; this.taskId = taskId; }
    public void validateSize() {
        if(verifiedCompletionStage<0 || verifiedCompletionStage>7 || verifiedCompletionGoalVersion<0
                || verifiedTransferBase==null || verifiedTransferBase.size()>512 || verifiedTransferBase.values().stream().anyMatch(Objects::isNull)
                || verifiedContainerSnapshots==null || verifiedContainerSnapshots.size()>128 || verifiedContainerSnapshots.values().stream().anyMatch(v->v==null || !v.matches("[0-9a-f]{64}")))
            throw new IllegalArgumentException("invalid_task_phase_checkpoint");
        if(mutationVersion<0 || verifiedTransfers==null || verifiedTransfers.size()>512 || verifiedTransfers.values().stream().anyMatch(Objects::isNull))
            throw new IllegalArgumentException("invalid_task_mutation_checkpoint");
        if(verifiedActionFacts==null || verifiedActionFacts.size()>256 || verifiedActionFacts.stream().anyMatch(Objects::isNull))
            throw new IllegalArgumentException("invalid_task_action_checkpoint");
        // Additive inspection data: repair before validating so older archives load unchanged.
        if (narration == null) narration = new ArrayList<>();
        narration.removeIf(Objects::isNull);
        while (narration.size() > TaskNarration.MAX_ENTRIES) narration.remove(0);
        for (int i = 0; i < narration.size(); i++)
            narration.set(i, AgentTaskState.bounded(narration.get(i), TaskNarration.MAX_ENTRY_CHARS));
        recordBytes = new com.google.gson.Gson().toJson(records).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (recordBytes > 4 * 1024 * 1024) throw new IllegalArgumentException("task_execution_archive_over_budget");
    }
    public boolean hasCapacity() { return records.size() < 8192 && recordBytes < 3584 * 1024; }
    public void commitFacts(AgentTaskState task) {
        verifiedCompletionGoalVersion=task.goalVersion;verifiedCompletionStage=task.completionStage;verifiedTransferBase=new LinkedHashMap<>(task.completionTransferBase);
        verifiedContainerSnapshots=new LinkedHashMap<>(task.initialContainerSnapshots);
        verifiedTransfers=new LinkedHashMap<>(task.transfers);verifiedActionFacts=task.actionFacts.stream().map(com.google.gson.JsonObject::deepCopy).collect(java.util.stream.Collectors.toCollection(ArrayList::new));task.appliedMutationVersion=++mutationVersion;
    }
    public void reconcile(AgentTaskState task) {
        if(mutationVersion>task.appliedMutationVersion) {
            if(verifiedCompletionGoalVersion==task.goalVersion) {
                task.completionStage=verifiedCompletionStage;task.completionTransferBase=new LinkedHashMap<>(verifiedTransferBase);
                task.initialContainerSnapshots=new LinkedHashMap<>(verifiedContainerSnapshots);
            }
            task.transfers=new LinkedHashMap<>(verifiedTransfers);task.actionFacts=verifiedActionFacts.stream().map(com.google.gson.JsonObject::deepCopy).collect(java.util.stream.Collectors.toCollection(ArrayList::new));task.appliedMutationVersion=mutationVersion;task.refreshTransferProgress();
        }
    }
    public boolean canRecord() { return records.size() < 8192 && recordBytes < 4 * 1024 * 1024; }
    /** Keeps one bounded copy of the executor's own written text for later inspection. */
    public void appendNarration(String text) {
        if (text == null || text.isBlank()) return;
        if (narration == null) narration = new ArrayList<>();
        narration.add(AgentTaskState.bounded(text.strip(), TaskNarration.MAX_ENTRY_CHARS));
        while (narration.size() > TaskNarration.MAX_ENTRIES) narration.remove(0);
    }
    public void append(long tick, long generation, String kind, String callId, String tool,
                       String argumentsRef, String resultRef, String summary) {
        if (!canRecord()) throw new IllegalStateException("task_execution_record_limit");
        Record record = new Record(tick, generation, kind, AgentTaskState.bounded(callId, 128), AgentTaskState.bounded(tool, 128),
                argumentsRef, resultRef, AgentTaskState.bounded(summary, 256));
        records.add(record);
        recordBytes += new com.google.gson.Gson().toJson(record).getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 1;
        updatedTick = tick;
    }
}
