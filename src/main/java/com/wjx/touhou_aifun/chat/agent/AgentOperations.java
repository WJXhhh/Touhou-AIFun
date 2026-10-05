package com.wjx.touhou_aifun.chat.agent;

import java.util.*;
import java.util.concurrent.*;

/** All asynchronous operations belong to their execution, not to the last HTTP future. */
public final class AgentOperations {
    private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();
    private volatile boolean cancelled;
    public void add(CompletableFuture<?> future) {
        pending.add(future);
        future.whenComplete((value, error) -> pending.remove(future));
        if (cancelled) future.cancel(true);
    }
    public void cancel() { cancelled = true; pending.forEach(f -> f.cancel(true)); pending.clear(); }
    public boolean cancelled() { return cancelled; }
    public boolean empty() { return pending.isEmpty(); }
}
