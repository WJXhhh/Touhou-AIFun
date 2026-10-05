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
    public long mutationVersion;
    public Map<String,Long> verifiedTransfers = new LinkedHashMap<>();
    private transient int recordBytes;

    public AgentTaskArchive() { }
    public AgentTaskArchive(String maidId, String taskId) { this.maidId = maidId; this.taskId = taskId; }
    public void validateSize() {
        if(mutationVersion<0 || verifiedTransfers==null || verifiedTransfers.size()>512 || verifiedTransfers.values().stream().anyMatch(Objects::isNull))
            throw new IllegalArgumentException("invalid_task_mutation_checkpoint");
        recordBytes = new com.google.gson.Gson().toJson(records).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (recordBytes > 4 * 1024 * 1024) throw new IllegalArgumentException("task_execution_archive_over_budget");
    }
    public boolean hasCapacity() { return records.size() < 8192 && recordBytes < 3584 * 1024; }
    public void commitFacts(AgentTaskState task) {
        verifiedTransfers=new LinkedHashMap<>(task.transfers);task.appliedMutationVersion=++mutationVersion;
    }
    public void reconcile(AgentTaskState task) {
        if(mutationVersion>task.appliedMutationVersion) {
            task.transfers=new LinkedHashMap<>(verifiedTransfers);task.appliedMutationVersion=mutationVersion;task.refreshTransferProgress();
        }
    }
    public boolean canRecord() { return records.size() < 8192 && recordBytes < 4 * 1024 * 1024; }
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
