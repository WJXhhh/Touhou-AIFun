package com.wjx.touhou_aifun.chat.agent;

import java.util.*;

/** Server-thread owned; independent from chat memory's schema. */
public final class AgentTaskQueue {
    public int schemaVersion = 1;
    public boolean paused;
    public String currentTaskId;
    public List<AgentTaskState> tasks = new ArrayList<>();
    public AgentTaskState current() {
        AgentTaskState active = active();
        return active != null ? active : tasks.stream().filter(t -> !t.terminal() && t.id.equals(currentTaskId)).findFirst().orElse(null);
    }
    public AgentTaskState resumeCandidate() {
        AgentTaskState current = current();
        return current != null ? current : tasks.stream().filter(t -> !t.terminal()).findFirst().orElse(null);
    }
    public AgentTaskState active() {
        return tasks.stream().filter(t -> t.status == AgentTaskState.Status.running || t.status == AgentTaskState.Status.waiting).findFirst().orElse(null);
    }
    public AgentTaskState enqueue(String goal) {
        if (goal == null || goal.isBlank()) throw new IllegalArgumentException("goal_required");
        boolean hasCurrent = active() != null || tasks.stream().anyMatch(t -> !t.terminal() && t.id.equals(currentTaskId));
        if (tasks.stream().filter(t -> !t.terminal()).count() >= (hasCurrent || !paused ? 5 : 4)) throw new IllegalArgumentException("task_queue_full");
        AgentTaskState task = new AgentTaskState(goal); tasks.add(task);
        while (tasks.size() > 16) {
            var old = tasks.stream().filter(AgentTaskState::terminal).findFirst();
            if (old.isEmpty()) break;
            tasks.remove(old.get());
        }
        return task;
    }
    public AgentTaskState next() {
        if (paused || active() != null) return null;
        AgentTaskState task = current();
        if (task == null || task.status != AgentTaskState.Status.queued)
            task = tasks.stream().filter(t -> t.status == AgentTaskState.Status.queued).findFirst().orElse(null);
        if (task != null) { task.status = AgentTaskState.Status.running; task.generation++; currentTaskId = task.id; }
        return task;
    }
    public void stop(boolean clear) {
        AgentTaskState current = current();
        paused = true;
        for (AgentTaskState task : tasks) {
            if (task == current || clear && !task.terminal()) { task.status = AgentTaskState.Status.cancelled; task.generation++; }
        }
        currentTaskId = null;
    }
    public void suspend(String reason) {
        paused = true;
        tasks.stream().filter(t -> !t.terminal()).forEach(t -> t.pause(reason));
    }
    public void resume() { paused = false; tasks.stream().filter(t -> t.status == AgentTaskState.Status.paused).forEach(t -> t.status = AgentTaskState.Status.queued); }
}
