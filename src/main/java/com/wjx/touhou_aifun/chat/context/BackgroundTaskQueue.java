package com.wjx.touhou_aifun.chat.context;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Small deterministic FIFO gate for low-priority work with per-key de-duplication. */
public final class BackgroundTaskQueue<K> {
    private final int maxConcurrent;
    private final Map<K, Runnable> pending = new LinkedHashMap<>();
    private final Set<K> active = new LinkedHashSet<>();
    private boolean pumping;

    public BackgroundTaskQueue(int maxConcurrent) {
        if (maxConcurrent < 1) throw new IllegalArgumentException("maxConcurrent must be positive");
        this.maxConcurrent = maxConcurrent;
    }

    public synchronized boolean enqueue(K key, Runnable task) {
        if (key == null || task == null || active.contains(key) || pending.containsKey(key)) return false;
        pending.put(key, task);
        return true;
    }

    /** Starts work outside the monitor; concurrent pump calls cannot exceed the reserved slots. */
    public void pump(BooleanSupplier mayStart) {
        synchronized (this) {
            if (pumping) return;
            pumping = true;
        }
        try {
            while (true) {
                Runnable next;
                synchronized (this) {
                    if (active.size() >= maxConcurrent || pending.isEmpty() || !mayStart.getAsBoolean()) return;
                    Map.Entry<K, Runnable> first = pending.entrySet().iterator().next();
                    pending.remove(first.getKey());
                    active.add(first.getKey());
                    next = first.getValue();
                }
                next.run();
            }
        } finally {
            synchronized (this) {
                pumping = false;
            }
        }
    }

    /** Completes an active task and removes a not-yet-started duplicate defensively. */
    public synchronized boolean finish(K key) {
        pending.remove(key);
        return active.remove(key);
    }

    /** Cancels only queued work; an already-issued HTTP request keeps its slot until callback completion. */
    public synchronized boolean cancelPending(K key) {
        return pending.remove(key) != null;
    }

    /** Returns a reserved task to the front when foreground work arrived before it actually began. */
    public synchronized boolean deferActive(K key, Runnable task) {
        if (!active.remove(key) || task == null) return false;
        Map<K, Runnable> reordered = new LinkedHashMap<>();
        reordered.put(key, task);
        reordered.putAll(pending);
        pending.clear();
        pending.putAll(reordered);
        return true;
    }

    public synchronized int activeCount() {
        return active.size();
    }

    public synchronized int pendingCount() {
        return pending.size();
    }

    public synchronized List<K> pendingKeys() {
        return new ArrayList<>(pending.keySet());
    }

    public synchronized void clear() {
        pending.clear();
        active.clear();
        pumping = false;
    }
}
