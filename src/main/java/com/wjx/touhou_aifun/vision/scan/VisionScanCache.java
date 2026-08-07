package com.wjx.touhou_aifun.vision.scan;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Ten-tick cache for scan-then-observe grounding, keyed by the pose that affects ray directions. */
public final class VisionScanCache {
    private static final Map<Key, Entry> CACHE = new ConcurrentHashMap<>();

    private VisionScanCache() {
    }

    public static EnvironmentScanResult scan(EntityMaid maid, EnvironmentScanRequest request) {
        EnvironmentScanRequest normalized = request == null ? EnvironmentScanRequest.defaults() : request;
        Key key = keyOf(maid, normalized);
        EnvironmentScanResult cached = getIfFresh(maid, normalized);
        if (cached != null) {
            return cached;
        }
        EnvironmentScanResult result = ShallowEnvironmentScanner.scan(maid, normalized);
        put(key, result);
        return result;
    }

    /** Schedule a server-thread scan while retaining this class as the public cache facade. */
    public static java.util.concurrent.CompletableFuture<EnvironmentScanResult> scanAsync(
            EntityMaid maid, EnvironmentScanRequest request) {
        EnvironmentScanRequest normalized = request == null ? EnvironmentScanRequest.defaults() : request;
        EnvironmentScanResult cached = getIfFresh(maid, normalized);
        return cached == null ? VisionScanScheduler.request(maid, normalized)
                : java.util.concurrent.CompletableFuture.completedFuture(cached);
    }

    static Key keyOf(EntityMaid maid, EnvironmentScanRequest request) {
        return new Key(maid.level().dimension().location().toString(), maid.getUUID(), maid.blockPosition().asLong(),
                Math.round(maid.getYRot() * 2.0f) / 2.0f,
                Math.round(maid.getXRot() * 2.0f) / 2.0f,
                request.mode(), request.direction(), request.maxDistance());
    }

    static EnvironmentScanResult getIfFresh(EntityMaid maid, EnvironmentScanRequest request) {
        long tick = maid.level().getGameTime();
        Entry current = CACHE.get(keyOf(maid, request));
        return current != null && tick - current.tick <= 10 ? current.result : null;
    }

    static void put(Key key, EnvironmentScanResult result) {
        if (result == null) {
            return;
        }
        long tick = result.gameTick();
        CACHE.put(key, new Entry(tick, result));
        if (CACHE.size() > 256) {
            long now = tick;
            CACHE.entrySet().removeIf(entry -> now - entry.getValue().tick > 10);
        }
    }

    public static void invalidate(EntityMaid maid) {
        CACHE.keySet().removeIf(key -> key.maid.equals(maid.getUUID()));
    }

    static record Key(String dimension, java.util.UUID maid, long blockPosition, float yaw, float pitch,
                      ScanMode mode, ScanDirection direction, int maxDistance) {
    }

    private record Entry(long tick, EnvironmentScanResult result) {
    }
}
