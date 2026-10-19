package com.wjx.touhou_aifun.chat.agent;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Keeps the short completion wording ready while a task is still running.
 *
 * <p>When the runtime verifies completion it currently needs one more model round trip before it can
 * show or speak the announcement: the {@code brief} line is phrased by a small background request.
 * That request only needs the task goal and the intended result, both known when execution starts, so
 * this cache starts it in the background and the verified completion consumes it immediately. The
 * measured tail from the last world mutation to the owner seeing/hearing the line then drops from
 * roughly one provider round trip to one server tick.</p>
 *
 * <p>Entries are per (maid, task id) and are only reused when the goal text and goal version still
 * match the finished task. An amended goal, a failed task, a stopped task or a stale entry simply
 * falls back to the ordinary on-demand request, so no unverified or outdated wording is ever used.</p>
 */
public final class TaskAnnouncementPrefetch {
    /** Long tasks still get the prefetched line; this only bounds entries for tasks that never finish. */
    private static final long MAX_AGE_MILLIS = 30 * 60_000L;
    private static final int MAX_ENTRIES = 64;
    private static final Map<String, Prepared> PENDING = new ConcurrentHashMap<>();

    private record Prepared(CompletableFuture<String> future, String goal, int goalVersion,
                            long createdAtMillis) { }

    private TaskAnnouncementPrefetch() {
    }

    /**
     * Starts the background completion wording for a task that is about to execute. Repeated calls for
     * the same goal are ignored; an amended goal replaces the stale entry with a new request.
     */
    static void prime(EntityMaid maid, MaidAIChatManager manager, AgentTaskState task) {
        if (maid == null || manager == null || task == null) {
            return;
        }
        prime(maid.getUUID(), task,
                () -> TaskAnnouncementPhrasing.phrase(maid, manager, true, task.announcementGoal(), 0));
    }

    static void prime(UUID maidId, AgentTaskState task, Supplier<CompletableFuture<String>> request) {
        if (maidId == null || task == null || request == null
                || task.announcementGoal() == null || task.announcementGoal().isBlank()) {
            return;
        }
        String goal = task.announcementGoal();
        long now = System.currentTimeMillis();
        prune(now);
        PENDING.compute(key(maidId, task.id), (ignored, existing) -> {
            if (existing != null && existing.goalVersion() == task.goalVersion
                    && existing.goal().equals(goal)) {
                return existing;
            }
            CompletableFuture<String> future;
            try {
                future = request.get();
            } catch (RuntimeException error) {
                future = CompletableFuture.failedFuture(error);
            }
            return new Prepared(future, goal, task.goalVersion, now);
        });
    }

    /**
     * Removes and returns the prepared wording for a finished task, or {@code null} when the task
     * never primed it, amended its goal after priming, or the entry is too old. The caller then uses
     * the ordinary on-demand phrasing path and its fixed fallback.
     */
    static CompletableFuture<String> take(EntityMaid maid, AgentTaskState task) {
        return maid == null ? null : take(maid.getUUID(), task);
    }

    static CompletableFuture<String> take(UUID maidId, AgentTaskState task) {
        if (maidId == null || task == null) {
            return null;
        }
        Prepared prepared = PENDING.remove(key(maidId, task.id));
        if (prepared == null) {
            return null;
        }
        if (prepared.goalVersion() != task.goalVersion
                || !prepared.goal().equals(task.announcementGoal())) {
            return null;
        }
        if (System.currentTimeMillis() - prepared.createdAtMillis() > MAX_AGE_MILLIS) {
            return null;
        }
        return prepared.future();
    }

    /** Drops any prepared wording for one maid: the task finished, was stopped, or the maid unloaded. */
    static void clear(EntityMaid maid) {
        if (maid != null) {
            clear(maid.getUUID());
        }
    }

    static void clear(UUID maidId) {
        if (maidId == null) {
            return;
        }
        String prefix = maidId + "|";
        PENDING.keySet().removeIf(key -> key.startsWith(prefix));
    }

    /** Test hook only. */
    static void clearForTests() {
        PENDING.clear();
    }

    static int sizeForTests() {
        return PENDING.size();
    }

    private static void prune(long now) {
        PENDING.entrySet().removeIf(entry -> now - entry.getValue().createdAtMillis() > MAX_AGE_MILLIS);
        if (PENDING.size() < MAX_ENTRIES) {
            return;
        }
        // Evict the oldest entry rather than refusing the newest task, whose completion is nearest.
        PENDING.entrySet().stream()
                .min(Comparator.comparingLong(entry -> entry.getValue().createdAtMillis()))
                .ifPresent(entry -> PENDING.remove(entry.getKey(), entry.getValue()));
    }

    private static String key(UUID maidId, String taskId) {
        return maidId + "|" + taskId;
    }
}
