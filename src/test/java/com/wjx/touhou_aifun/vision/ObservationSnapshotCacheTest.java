package com.wjx.touhou_aifun.vision;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class ObservationSnapshotCacheTest {
    @Test void loweredBudgetEvictsOldestDuringPeriodicMaintenance() {
        var budget = new java.util.concurrent.atomic.AtomicLong(6);
        var cache = new ObservationSnapshotCache(10, 1000, budget::get, () -> 100);
        cache.put(snapshot("one", maid, owner, 100));
        cache.put(snapshot("two", maid, owner, 100));
        budget.set(3); cache.prune();
        assertNull(cache.get(maid, owner, "one"));
        assertNotNull(cache.get(maid, owner, "two"));
    }
    private final UUID maid = UUID.randomUUID(), owner = UUID.randomUUID();
    private ObservationSnapshot snapshot(String id, UUID maidId, UUID ownerId, long time) {
        return new ObservationSnapshot(id, maidId, ownerId, "minecraft:overworld", time, 1, 2, 45,
                Map.of("front", "data:image/jpeg;base64,AAAA"), null, "not_requested");
    }
    @Test void capsPerMaidAndExpiresOnHourBoundary() {
        AtomicLong clock = new AtomicLong(100);
        var cache = new ObservationSnapshotCache(10, 3_600_000, 128L * 1024 * 1024, clock::get);
        for (int i = 0; i < 12; i++) cache.put(snapshot("id-" + i, maid, owner, 100));
        assertEquals(10, cache.list(maid, owner).size());
        assertEquals("id-11", cache.get(maid, owner, "").id());
        assertNull(cache.get(maid, owner, "id-0"));
        clock.set(3_600_099);
        assertNotNull(cache.get(maid, owner, "id-11"));
        clock.incrementAndGet();
        assertNull(cache.get(maid, owner, "id-11"));
        assertEquals(0, cache.bytes());
    }
    @Test void globalCapacityEvictsOldestAndNeverCrossesMaidOrOwnerBoundaries() {
        var cache = new ObservationSnapshotCache(10, 1000, 6, () -> 100);
        UUID other = UUID.randomUUID();
        cache.put(snapshot("one", maid, owner, 100));
        cache.put(snapshot("two", other, owner, 100));
        cache.put(snapshot("three", maid, owner, 100));
        assertNull(cache.get(maid, owner, "one"));
        assertNull(cache.get(maid, owner, "two"));
        assertNotNull(cache.get(other, owner, "two"));
        assertEquals(6, cache.bytes());
        UUID replacement = UUID.randomUUID();
        assertTrue(cache.list(maid, replacement).isEmpty());
        assertNull(cache.get(maid, owner, "three"));
    }
    @Test void clearingOneMaidPreservesOtherCachesAndReadDoesNotCreateSnapshots() {
        var cache = new ObservationSnapshotCache(10, 1000, 1000, () -> 100);
        UUID other = UUID.randomUUID();
        cache.put(snapshot("one", maid, owner, 100));
        cache.put(snapshot("two", other, owner, 100));
        assertEquals("one", cache.get(maid, owner, "").id());
        assertEquals(1, cache.list(maid, owner).size());
        cache.clearMaid(maid);
        assertNotNull(cache.get(other, owner, "two"));
        cache.clear();
        assertEquals(0, cache.bytes());
    }
}
