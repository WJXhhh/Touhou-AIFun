package com.wjx.touhou_aifun.vision;

import java.util.*;
import java.util.function.LongSupplier;

/** Insertion-order eviction, bounded by age, per-maid count and global compressed image bytes. */
public final class ObservationSnapshotCache {
    public static final ObservationSnapshotCache INSTANCE = new ObservationSnapshotCache(10, 3_600_000L,
            () -> com.wjx.touhou_aifun.config.TouhouAIFunConfig.VISION_SNAPSHOT_MEMORY_MIB.get() * 1024L * 1024L, System::currentTimeMillis);
    private final int perMaid;
    private final long lifetimeMillis;
    private final LongSupplier maxBytes;
    private final LongSupplier clock;
    private final LinkedHashMap<String, ObservationSnapshot> snapshots = new LinkedHashMap<>();
    private long bytes;

    public ObservationSnapshotCache(int perMaid, long lifetimeMillis, long maxBytes, LongSupplier clock) {
        this(perMaid, lifetimeMillis, () -> maxBytes, clock);
    }

    public ObservationSnapshotCache(int perMaid, long lifetimeMillis, LongSupplier maxBytes, LongSupplier clock) {
        this.perMaid = perMaid;
        this.lifetimeMillis = lifetimeMillis;
        this.maxBytes = maxBytes;
        this.clock = clock;
    }

    public synchronized void put(ObservationSnapshot snapshot) {
        prune();
        // A changed owner invalidates every earlier snapshot of this maid.
        snapshots.values().removeIf(old -> old.maidId().equals(snapshot.maidId()) && !Objects.equals(old.ownerId(), snapshot.ownerId()));
        snapshots.put(snapshot.id(), snapshot);
        List<ObservationSnapshot> maid = snapshots.values().stream().filter(old -> old.maidId().equals(snapshot.maidId())).toList();
        for (int i = 0; i < maid.size() - perMaid; i++) snapshots.remove(maid.get(i).id());
        recount();
        evictOverBudget();
    }

    private void evictOverBudget() {
        while (bytes > maxBytes.getAsLong() && !snapshots.isEmpty()) {
            snapshots.remove(snapshots.keySet().iterator().next());
            recount();
        }
    }

    public synchronized List<ObservationSnapshot> list(UUID maid, UUID owner) {
        prune();
        snapshots.values().removeIf(old -> old.maidId().equals(maid) && !Objects.equals(old.ownerId(), owner));
        recount();
        List<ObservationSnapshot> result = new ArrayList<>(snapshots.values().stream()
                .filter(old -> old.maidId().equals(maid) && Objects.equals(old.ownerId(), owner)).toList());
        Collections.reverse(result);
        return List.copyOf(result);
    }

    public synchronized ObservationSnapshot get(UUID maid, UUID owner, String id) {
        List<ObservationSnapshot> available = list(maid, owner);
        if (id == null || id.isBlank()) return available.isEmpty() ? null : available.get(0);
        return available.stream().filter(snapshot -> snapshot.id().equals(id)).findFirst().orElse(null);
    }

    public synchronized void clearMaid(UUID maid) { snapshots.values().removeIf(old -> old.maidId().equals(maid)); recount(); }
    public synchronized void clear() { snapshots.clear(); bytes = 0; }
    public synchronized long bytes() { prune(); return bytes; }
    public synchronized void prune() {
        long now = clock.getAsLong();
        snapshots.values().removeIf(old -> now - old.capturedAtMillis() >= lifetimeMillis);
        recount();
        evictOverBudget();
    }
    private void recount() { bytes = snapshots.values().stream().mapToLong(ObservationSnapshot::compressedBytes).sum(); }
}
