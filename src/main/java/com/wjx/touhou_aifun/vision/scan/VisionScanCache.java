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
    private static final Map<String, SavedObservation> OBSERVATIONS = new ConcurrentHashMap<>();
    private static final int MAX_SAVED_OBSERVATIONS = 32;
    private static final int MAX_CACHED_CELLS = 131_072;
    private static final long OBSERVATION_LIFETIME_MILLIS = 5 * 60_000L;

    private VisionScanCache() {
    }

    public static EnvironmentScanResult scan(EntityMaid maid, EnvironmentScanRequest request) {
        EnvironmentScanRequest normalized = request == null ? EnvironmentScanRequest.defaults() : request;
        if (!normalized.observationId().isBlank()) return savedPage(maid, normalized);
        Key key = keyOf(maid, normalized);
        EnvironmentScanResult cached = getIfFresh(maid, normalized);
        if (cached != null) {
            return cached;
        }
        EnvironmentScanResult result = ShallowEnvironmentScanner.scan(maid, geometryRequest(normalized));
        put(key, result, maid.getOwnerUUID());
        return result.project(normalized);
    }

    /** Schedule a server-thread scan while retaining this class as the public cache facade. */
    public static java.util.concurrent.CompletableFuture<EnvironmentScanResult> scanAsync(
            EntityMaid maid, EnvironmentScanRequest request) {
        EnvironmentScanRequest normalized = request == null ? EnvironmentScanRequest.defaults() : request;
        if (!normalized.observationId().isBlank()) {
            try { return java.util.concurrent.CompletableFuture.completedFuture(savedPage(maid, normalized)); }
            catch (IllegalArgumentException expired) { return java.util.concurrent.CompletableFuture.failedFuture(expired); }
        }
        EnvironmentScanResult cached = getIfFresh(maid, normalized);
        return cached == null ? VisionScanScheduler.request(maid, normalized)
                : java.util.concurrent.CompletableFuture.completedFuture(cached);
    }

    static Key keyOf(EntityMaid maid, EnvironmentScanRequest request) {
        return new Key(maid.level().dimension().location().toString(), maid.getUUID(), maid.blockPosition().asLong(),
                Math.round(maid.getYRot() * 2.0f) / 2.0f,
                Math.round(maid.getXRot() * 2.0f) / 2.0f,
                request.mode(), request.direction(), request.maxDistance(), "", request.intent(), maid.getX(), maid.getY(), maid.getZ(),
                ScanRegionVersions.version(maid.level(),maid.blockPosition(),request.maxDistance()), request.area());
    }

    static EnvironmentScanResult getIfFresh(EntityMaid maid, EnvironmentScanRequest request) {
        long tick = maid.level().getGameTime();
        Entry current = CACHE.get(keyOf(maid, request));
        return current != null && java.util.Objects.equals(current.owner(), maid.getOwnerUUID())
                && !normalizedSignRequest(request) && isFresh(tick, current.tick)
                && isFresh(tick, current.result.gameTick()) && (!request.mode().includesEntities() || current.tick==tick)
                ? current.result.project(request) : null;
    }

    static void put(Key key, EnvironmentScanResult result, java.util.UUID owner) {
        if (result == null) {
            return;
        }
        long tick = result.completedTick();
        CACHE.put(key, new Entry(tick, result, owner));
        long now = System.currentTimeMillis();
        OBSERVATIONS.entrySet().removeIf(entry -> now - entry.getValue().savedAtMillis() > OBSERVATION_LIFETIME_MILLIS);
        OBSERVATIONS.put(result.observationId(), new SavedObservation(key.maid(), owner, key.dimension(), result, now));
        while (OBSERVATIONS.size() > MAX_SAVED_OBSERVATIONS
                || OBSERVATIONS.values().stream().mapToInt(saved -> saved.result().observedBlocks().size()).sum() > MAX_CACHED_CELLS) {
            OBSERVATIONS.entrySet().stream().min(java.util.Comparator.comparingLong(entry -> entry.getValue().savedAtMillis()))
                    .ifPresent(entry -> OBSERVATIONS.remove(entry.getKey(), entry.getValue()));
        }
        CACHE.entrySet().removeIf(entry -> !isFresh(tick, entry.getValue().tick));
        while (CACHE.size() > MAX_ENTRIES
                || CACHE.values().stream().mapToInt(entry -> entry.result().observedBlocks().size()).sum() > MAX_CACHED_CELLS) {
                Map.Entry<Key, Entry> oldest = CACHE.entrySet().stream()
                        .min(java.util.Comparator.comparingLong(entry -> entry.getValue().tick))
                        .orElse(null);
                if (oldest == null || !CACHE.remove(oldest.getKey(), oldest.getValue())) break;
        }
    }

    public static void invalidate(EntityMaid maid) {
        CACHE.keySet().removeIf(key -> key.maid.equals(maid.getUUID()));
    }

    public static void clearMaid(java.util.UUID maid) {
        CACHE.keySet().removeIf(key -> key.maid().equals(maid));
        OBSERVATIONS.values().removeIf(saved -> saved.maid().equals(maid));
    }

    public static void clearAll() {
        CACHE.clear();
        OBSERVATIONS.clear();
    }

    static boolean isFresh(long currentTick, long cachedTick) {
        long age = currentTick - cachedTick;
        return age >= 0 && age <= MAX_AGE_TICKS;
    }

    static record Key(String dimension, java.util.UUID maid, long blockPosition, float yaw, float pitch,
                      ScanMode mode, ScanDirection direction, int maxDistance, String focus, String intent, double x, double y, double z,String regionVersion, ScanArea area) {
        Key(String dimension, java.util.UUID maid, long blockPosition, float yaw, float pitch, ScanMode mode, ScanDirection direction, int maxDistance, String focus) { this(dimension, maid, blockPosition, yaw, pitch, mode, direction, maxDistance, focus, "overview", 0, 0, 0,"", null); }
    }

    private static boolean normalizedSignRequest(EnvironmentScanRequest request) { return request.intent().equals("read_signs"); }
    static EnvironmentScanRequest geometryRequest(EnvironmentScanRequest request) {
        return request.intent().equals("read_signs") ? request : new EnvironmentScanRequest(request.mode(),request.direction(),request.maxDistance(),"",request.intent(),"full",request.area(),0,32,"");
    }

    private static String normalizeFocus(String focus) {
        return focus == null ? "" : focus.trim().toLowerCase(Locale.ROOT);
    }

    private record Entry(long tick, EnvironmentScanResult result, java.util.UUID owner) {
    }

    private static EnvironmentScanResult savedPage(EntityMaid maid, EnvironmentScanRequest request) {
        SavedObservation saved = OBSERVATIONS.get(request.observationId());
        if (saved == null || !saved.maid().equals(maid.getUUID())
                || !java.util.Objects.equals(saved.owner(), maid.getOwnerUUID())
                || !saved.dimension().equals(maid.level().dimension().location().toString())
                || System.currentTimeMillis() - saved.savedAtMillis() > OBSERVATION_LIFETIME_MILLIS) {
            throw new IllegalArgumentException("scan_observation_expired_or_unavailable_rescan_required");
        }
        return saved.result().project(request);
    }

    private record SavedObservation(java.util.UUID maid, java.util.UUID owner, String dimension, EnvironmentScanResult result, long savedAtMillis) { }
}
