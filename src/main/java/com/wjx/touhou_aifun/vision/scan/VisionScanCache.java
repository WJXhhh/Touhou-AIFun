package com.wjx.touhou_aifun.vision.scan;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

import java.util.Map;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/** Ten-tick cache for scan-then-observe grounding, keyed by the pose that affects ray directions. */
public final class VisionScanCache {
    public static boolean isFreshCached(EntityMaid maid,EnvironmentScanRequest request) { return getIfFresh(maid,request)!=null; }
    private static final int MAX_ENTRIES = 256;
    private static final long MAX_AGE_TICKS = 10;
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
        EnvironmentScanResult result = ShallowEnvironmentScanner.scan(maid, geometryRequest(normalized));
        put(key, result);
        return result.project(normalized);
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
                request.mode(), request.direction(), request.maxDistance(), "", request.intent(), maid.getX(), maid.getY(), maid.getZ(),
                ScanRegionVersions.version(maid.level(),maid.blockPosition(),request.maxDistance()));
    }

    static EnvironmentScanResult getIfFresh(EntityMaid maid, EnvironmentScanRequest request) {
        long tick = maid.level().getGameTime();
        Entry current = CACHE.get(keyOf(maid, request));
        return current != null && !normalizedSignRequest(request) && isFresh(tick, current.tick)
                && isFresh(tick, current.result.gameTick()) && (!request.mode().includesEntities() || current.tick==tick)
                ? current.result.project(request) : null;
    }

    static void put(Key key, EnvironmentScanResult result) {
        if (result == null) {
            return;
        }
        long tick = result.completedTick();
        CACHE.put(key, new Entry(tick, result));
        if (CACHE.size() > MAX_ENTRIES) {
            long now = tick;
            CACHE.entrySet().removeIf(entry -> !isFresh(now, entry.getValue().tick));
            while (CACHE.size() > MAX_ENTRIES) {
                Map.Entry<Key, Entry> oldest = CACHE.entrySet().stream()
                        .min(java.util.Comparator.comparingLong(entry -> entry.getValue().tick))
                        .orElse(null);
                if (oldest == null || !CACHE.remove(oldest.getKey(), oldest.getValue())) break;
            }
        }
    }

    public static void invalidate(EntityMaid maid) {
        CACHE.keySet().removeIf(key -> key.maid.equals(maid.getUUID()));
    }

    public static void clearAll() {
        CACHE.clear();
    }

    static boolean isFresh(long currentTick, long cachedTick) {
        long age = currentTick - cachedTick;
        return age >= 0 && age <= MAX_AGE_TICKS;
    }

    static record Key(String dimension, java.util.UUID maid, long blockPosition, float yaw, float pitch,
                      ScanMode mode, ScanDirection direction, int maxDistance, String focus, String intent, double x, double y, double z,String regionVersion) {
        Key(String dimension, java.util.UUID maid, long blockPosition, float yaw, float pitch, ScanMode mode, ScanDirection direction, int maxDistance, String focus) { this(dimension, maid, blockPosition, yaw, pitch, mode, direction, maxDistance, focus, "overview", 0, 0, 0,""); }
    }

    private static boolean normalizedSignRequest(EnvironmentScanRequest request) { return request.intent().equals("read_signs"); }
    static EnvironmentScanRequest geometryRequest(EnvironmentScanRequest request) {
        return request.intent().equals("read_signs") ? request : new EnvironmentScanRequest(request.mode(),request.direction(),request.maxDistance(),"",request.intent(),"full");
    }

    private static String normalizeFocus(String focus) {
        return focus == null ? "" : focus.trim().toLowerCase(Locale.ROOT);
    }

    private record Entry(long tick, EnvironmentScanResult result) {
    }
}
