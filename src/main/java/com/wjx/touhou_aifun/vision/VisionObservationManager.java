package com.wjx.touhou_aifun.vision;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import com.wjx.touhou_aifun.config.TouhouAIFunConfig;
import com.wjx.touhou_aifun.vision.scan.EnvironmentScanRequest;
import com.wjx.touhou_aifun.vision.scan.EnvironmentScanResult;
import com.wjx.touhou_aifun.vision.scan.ScanDirection;
import com.wjx.touhou_aifun.vision.scan.ScanMode;
import com.wjx.touhou_aifun.vision.scan.VisionScanCache;
import com.wjx.touhou_aifun.vision.scan.SurfaceBlockHit;
import com.wjx.touhou_aifun.vision.scan.ImportantBlockHit;
import com.wjx.touhou_aifun.vision.scan.ScannedEntity;
import org.slf4j.Logger;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

/** Coordinates the optional client capture and the always-local scan. */
public final class VisionObservationManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ConcurrentMap<UUID, ActiveObservation> ACTIVE = new ConcurrentHashMap<>();
    private static final ConcurrentMap<ObservationKey, CachedObservation> RECENT = new ConcurrentHashMap<>();
    private static final long RESULT_CACHE_NANOS = 500_000_000L;

    private VisionObservationManager() {
    }

    public static void cancelForMaid(UUID maidId) {
        if (maidId == null) return;
        ActiveObservation active = ACTIVE.remove(maidId);
        if (active != null && !active.future().isDone()) active.future().cancel(false);
        RECENT.keySet().removeIf(key -> maidId.equals(key.maid()));
        VisionCaptureTransport.cancelForMaid(maidId);
        VisionHttpCancellation.cancelForMaid(maidId);
        com.wjx.touhou_aifun.vision.scan.VisionScanScheduler.cancelForMaid(maidId);
    }

    public static void clearAll() {
        ACTIVE.values().forEach(active -> {
            if (!active.future().isDone()) active.future().cancel(false);
        });
        ACTIVE.clear();
        RECENT.clear();
        VisionCaptureTransport.cancelAll();
        VisionHttpCancellation.cancelAll();
        com.wjx.touhou_aifun.vision.scan.VisionScanScheduler.cancelAll();
        VisionScanCache.clearAll();
    }

    /**
     * Called by the tool on the server thread. A capture transport can complete the returned future;
     * until a client is attached, this safe fallback still gives the LLM the local scan.
     */
    public static CompletableFuture<String> observe(EntityMaid maid, ObservationRequest request) {
        return observe(maid, request, ignored -> {
        });
    }

    public static CompletableFuture<String> observe(EntityMaid maid, ObservationRequest request,
                                                     Consumer<ObservationStage> progress) {
        ObservationRequest normalized = request == null ? new ObservationRequest("", ScanMode.BOTH) : request;
        ObservationKey key = observationKey(maid, normalized);
        UUID maidId = maid.getUUID();
        CachedObservation cached = RECENT.get(key);
        long now = System.nanoTime();
        if (cached != null && now >= cached.completedNanos()
                && now - cached.completedNanos() <= RESULT_CACHE_NANOS) {
            return CompletableFuture.completedFuture(cached.result());
        }
        synchronized (ACTIVE) {
            ActiveObservation current = ACTIVE.get(maidId);
            if (current != null && !current.future().isDone()) {
                if (current.key().equals(key)) return current.future();
                cancelForMaid(maidId);
            }
            CompletableFuture<String> future = observeInternal(maid, normalized, progress);
            ActiveObservation active = new ActiveObservation(key, future);
            ACTIVE.put(maidId, active);
            future.whenComplete((value, throwable) -> {
                ACTIVE.remove(maidId, active);
                if (throwable == null && reusableVisualResult(value)) {
                    long completedNanos = System.nanoTime();
                    RECENT.put(key, new CachedObservation(completedNanos, value));
                    if (RECENT.size() > 128) {
                        RECENT.entrySet().removeIf(entry -> completedNanos - entry.getValue().completedNanos()
                                > RESULT_CACHE_NANOS);
                    }
                }
            });
            return future;
        }
    }

    private static ObservationKey observationKey(EntityMaid maid, ObservationRequest request) {
        VisionSite site = TouhouAIFunConfig.VISION_ENABLED.get() ? AvailableVisionSites.selected() : null;
        String siteFingerprint = site == null ? "" : site.id() + "|" + site.endpoint() + "|" + site.model();
        return new ObservationKey(maid.level().dimension().location().toString(), maid.getUUID(),
                maid.blockPosition().asLong(), Math.round(maid.getYRot() * 2.0F) / 2.0F,
                Math.round(maid.getXRot() * 2.0F) / 2.0F, request.scanMode(),
                request.focus().trim().toLowerCase(java.util.Locale.ROOT), siteFingerprint,
                TouhouAIFunConfig.SHALLOW_SCAN_ENABLED.get());
    }

    static boolean reusableVisualResult(String value) {
        if (value == null || value.isBlank()) return false;
        try {
            JsonObject object = JsonParser.parseString(value).getAsJsonObject();
            return "ok".equals(string(object, "status")) && object.has("site_id")
                    && !string(object, "site_id").isBlank() && object.has("image_tick")
                    && object.get("image_tick").getAsLong() >= 0;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static CompletableFuture<String> observeInternal(EntityMaid maid, ObservationRequest normalized,
                                                              Consumer<ObservationStage> progress) {
        VisionSite site = TouhouAIFunConfig.VISION_ENABLED.get() ? AvailableVisionSites.selected() : null;
        if (site != null) {
            LOGGER.info("Starting visual observation with site={} provider={} model={} endpoint={}",
                    site.id(), site.provider(), site.model(), site.endpoint());
        }
        CompletableFuture<ScanOutcome> scanFuture;
        if (TouhouAIFunConfig.SHALLOW_SCAN_ENABLED.get() && normalized.scanMode() != ScanMode.NONE) {
            notifyProgress(progress, ObservationStage.SCANNING);
            try {
                scanFuture = VisionScanCache.scanAsync(maid,
                        new EnvironmentScanRequest(normalized.scanMode(), ScanDirection.ALL, 20, normalized.focus()))
                        .handle((result, throwable) -> {
                            if (throwable != null) {
                                if (throwable instanceof CancellationException
                                        || throwable.getCause() instanceof CancellationException) {
                                    throw new CompletionException(throwable);
                                }
                                LOGGER.warn("Local visual scan failed", throwable);
                                return new ScanOutcome(null, true, true);
                            }
                            return new ScanOutcome(result, true, false);
                        });
            } catch (Throwable throwable) {
                LOGGER.warn("Local visual scan could not be scheduled", throwable);
                scanFuture = CompletableFuture.completedFuture(new ScanOutcome(null, true, true));
            }
        } else {
            scanFuture = CompletableFuture.completedFuture(new ScanOutcome(null, false, false));
        }

        if (site == null) {
            return scanFuture.thenApply(outcome -> {
                EnvironmentScanResult scan = outcome.result();
                String scanStatus = scanStatus(outcome);
                String uncertainty = outcome.failed()
                        ? "The local scan failed; do not infer exact registry identities from an image."
                        : "No image was sent to a visual model; use scan registry identities only.";
                JsonObject fallback = new JsonObject();
                fallback.addProperty("status", "ok".equals(scanStatus) ? "ok" : "failed");
                fallback.addProperty("scan_status", scanStatus);
                fallback.addProperty("image_status", "disabled_or_no_enabled_site");
                JsonArray uncertainties = new JsonArray();
                uncertainties.add(uncertainty);
                fallback.add("uncertainties", uncertainties);
                return boundCombined(fallback, scan);
            });
        }

        if (!VisionRequestLimiter.tryAcquire()) {
            return scanFuture.thenApply(outcome -> {
                EnvironmentScanResult scan = outcome.result();
                String scanStatus = scanStatus(outcome);
                JsonObject fallback = new JsonObject();
                fallback.addProperty("status", "ok".equals(scanStatus) ? "partial" : "failed");
                fallback.addProperty("scan_status", scanStatus);
                fallback.addProperty("image_status", "rate_limited");
                fallback.addProperty("error", "too_many_concurrent_visual_requests");
                JsonArray uncertainties = new JsonArray();
                uncertainties.add("The image request was skipped by the global concurrency guard; use the server scan only.");
                fallback.add("uncertainties", uncertainties);
                return boundCombined(fallback, scan);
            });
        }

        // Start the client capture while the server scan is advancing across ticks. Waiting for the
        // scan first used to make the six images 5-10 ticks newer than their grounding data.
        notifyProgress(progress, ObservationStage.CAPTURING);
        CompletableFuture<VisionCaptureTransport.CaptureResult> captureFuture =
                VisionCaptureTransport.requestCapture(maid);
        CompletableFuture<String> observation = scanFuture.thenCombine(captureFuture, ScanAndCapture::new)
                .thenCompose(combined -> {
                    ScanOutcome outcome = combined.scan();
                    EnvironmentScanResult scan = outcome.result();
                    String scanJson = scan == null ? "" : scan.toJson();
                    String scanStatus = scanStatus(outcome);
                    VisionCaptureTransport.CaptureResult capture = combined.capture();
                    if (capture == null || !capture.success()) {
                        String reason = capture == null ? "capture_failed" : capture.error();
                        JsonObject fallback = new JsonObject();
                        fallback.addProperty("status", "ok".equals(scanStatus) ? "partial" : "failed");
                        fallback.addProperty("scan_status", scanStatus);
                        fallback.addProperty("image_status", "failed");
                        fallback.addProperty("error", reason);
                        JsonArray uncertainties = new JsonArray();
                        uncertainties.add("Image capture failed; conclusions are based only on the server scan.");
                        fallback.add("uncertainties", uncertainties);
                        return CompletableFuture.completedFuture(boundCombined(fallback, scan));
                    }
                    VisionRequest visionRequest = new VisionRequest(site, maid, normalized.focus(), scanJson,
                            capture.images(), scan == null ? -1 : scan.gameTick(),
                            capture.captureStartTick(), capture.captureEndTick(), capture.captureYaw());
                    notifyProgress(progress, ObservationStage.ANALYZING);
                    return VisionClient.forSite(site).observe(visionRequest)
                            .thenApply(result -> mergeScan(result.toJson(), scan, scanStatus));
                });
        // Register on the exact future returned to ACTIVE. Cancelling that future now releases the
        // global paid-request slot immediately instead of waiting for an upstream timeout.
        return VisionRequestLimiter.releaseWhenDone(observation);
    }

    private static String scanStatus(ScanOutcome outcome) {
        return outcome.failed() ? "failed" : outcome.requested() ? "ok" : "not_requested";
    }

    private static void notifyProgress(Consumer<ObservationStage> progress, ObservationStage stage) {
        if (progress == null) return;
        try {
            progress.accept(stage);
        } catch (RuntimeException exception) {
            LOGGER.debug("Unable to publish visual observation progress {}", stage, exception);
        }
    }

    public enum ObservationStage {
        SCANNING,
        CAPTURING,
        ANALYZING
    }

    static String mergeScan(String observationJson, EnvironmentScanResult scan, String scanStatus) {
        try {
            JsonObject object = JsonParser.parseString(observationJson).getAsJsonObject();
            String normalizedScanStatus = scanStatus == null ? "not_requested" : scanStatus;
            boolean imageOk = "ok".equals(string(object, "status"));
            object.addProperty("image_status", imageOk ? "ok" : "failed");
            object.addProperty("scan_status", normalizedScanStatus);
            if (!imageOk && "ok".equals(normalizedScanStatus)) {
                object.addProperty("status", "partial");
                appendUncertainty(object,
                        "The visual provider failed; conclusions are based only on the successful server scan.");
            } else if (imageOk && "failed".equals(normalizedScanStatus)) {
                object.addProperty("status", "partial");
                appendUncertainty(object,
                        "The server scan failed; do not infer exact registry identities from image texture alone.");
            }
            validateGroundedMatches(object, scan);
            return boundCombined(object, scan);
        } catch (Exception ignored) {
            return observationJson;
        }
    }

    static String boundCombined(JsonObject object, EnvironmentScanResult scan) {
        if (scan != null) {
            object.add("scan", JsonParser.parseString(scan.toJson()));
        } else if (!object.has("scan")) {
            object.add("scan", com.google.gson.JsonNull.INSTANCE);
        }
        com.google.gson.Gson gson = new GsonBuilder().disableHtmlEscaping().create();
        String json = gson.toJson(object);
        if (fits(json)) return json;

        object.remove("raw_model_text");
        object.addProperty("truncated", true);
        if (scan != null) {
            object.add("scan", JsonParser.parseString(scan.toCompactJson()));
        }
        json = gson.toJson(object);
        if (fits(json)) return json;

        for (String key : new String[]{"grounded_matches", "directions", "visible_text", "hazards"}) {
            object.remove(key);
        }
        object.addProperty("omitted_visual_details", true);
        if (object.has("scene_summary") && object.get("scene_summary").isJsonPrimitive()) {
            object.addProperty("scene_summary", truncate(object.get("scene_summary").getAsString(), 2048));
        }
        if (object.has("answer_to_focus") && object.get("answer_to_focus").isJsonPrimitive()) {
            object.addProperty("answer_to_focus", truncate(object.get("answer_to_focus").getAsString(), 1024));
        }
        json = gson.toJson(object);
        if (fits(json)) return json;

        JsonObject minimal = new JsonObject();
        copyPrimitive(object, minimal, "status");
        copyPrimitive(object, minimal, "scan_status");
        copyPrimitive(object, minimal, "image_status");
        copyPrimitive(object, minimal, "site_id");
        copyPrimitive(object, minimal, "error");
        minimal.addProperty("truncated", true);
        minimal.addProperty("reason", "combined visual result exceeded 16 KiB; details were omitted");
        if (scan != null) minimal.add("scan", JsonParser.parseString(scan.toCompactJson()));
        json = gson.toJson(minimal);
        if (fits(json)) return json;

        // Do not trust provider error text, custom site ids or even modded dimension identifiers to
        // fit the boundary. The emergency envelope contains no unbounded external strings.
        JsonObject emergency = new JsonObject();
        emergency.addProperty("status", "failed".equals(string(object, "status")) ? "failed" : "partial");
        emergency.addProperty("scan_status", boundedStatus(string(object, "scan_status"), "unknown"));
        emergency.addProperty("image_status", boundedStatus(string(object, "image_status"), "unknown"));
        emergency.addProperty("truncated", true);
        emergency.addProperty("reason", "combined visual result exceeded 16 KiB; variable text was omitted");
        if (scan != null) {
            emergency.add("scan", JsonParser.parseString(scan.toCompactJson()));
            json = gson.toJson(emergency);
            if (fits(json)) return json;
            emergency.remove("scan");
            emergency.addProperty("scan_summary_omitted", true);
        }
        return gson.toJson(emergency);
    }

    private static String boundedStatus(String value, String fallback) {
        if (value == null || value.isBlank() || value.length() > 32
                || !value.chars().allMatch(character -> Character.isLetterOrDigit(character)
                || character == '_' || character == '-')) {
            return fallback;
        }
        return value;
    }

    /** Keep only model claims that point back to an identity and position emitted by the scan. */
    static void validateGroundedMatches(JsonObject observation, EnvironmentScanResult scan) {
        if (!observation.has("grounded_matches")) return;
        JsonElement value = observation.get("grounded_matches");
        if (scan == null || !value.isJsonArray()) {
            observation.remove("grounded_matches");
            appendUncertainty(observation,
                    "Grounded matches were omitted because no successful server scan was available.");
            return;
        }
        JsonArray accepted = new JsonArray();
        int rejected = 0;
        for (JsonElement element : value.getAsJsonArray()) {
            if (element.isJsonObject() && groundedMatchExists(element.getAsJsonObject(), scan)) {
                accepted.add(element);
            } else {
                rejected++;
            }
        }
        observation.add("grounded_matches", accepted);
        if (rejected > 0) {
            appendUncertainty(observation, "Server validation removed " + rejected
                    + " visual grounding claim(s) that were absent from the scan.");
        }
    }

    private static boolean groundedMatchExists(JsonObject match, EnvironmentScanResult scan) {
        String kind = string(match, "scan_kind");
        String registryId = string(match, "registry_id");
        double[] position = position(match.get("relative_position"));
        if (kind.isBlank() || registryId.isBlank() || position == null) return false;
        if ("block".equalsIgnoreCase(kind)) {
            for (ImportantBlockHit block : scan.importantBlocks()) {
                if (registryId.equals(block.registryId()) && near(position, block.dx(), block.dy(), block.dz(), 0.01)) {
                    return true;
                }
            }
            for (SurfaceBlockHit group : scan.surfaces()) {
                if (!registryId.equals(group.registryId())) continue;
                for (SurfaceBlockHit.Representative sample : group.representatives()) {
                    if (near(position, sample.dx(), sample.dy(), sample.dz(), 0.01)) return true;
                }
            }
            return false;
        }
        if ("entity".equalsIgnoreCase(kind)) {
            for (ScannedEntity entity : scan.entities()) {
                if (registryId.equals(entity.registryId())
                        && near(position, entity.dx(), entity.dy(), entity.dz(), 0.75)) return true;
            }
        }
        return false;
    }

    private static String string(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : "";
    }

    private static double[] position(JsonElement element) {
        if (element == null || !element.isJsonArray() || element.getAsJsonArray().size() != 3) return null;
        try {
            return new double[]{element.getAsJsonArray().get(0).getAsDouble(),
                    element.getAsJsonArray().get(1).getAsDouble(), element.getAsJsonArray().get(2).getAsDouble()};
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static boolean near(double[] actual, double x, double y, double z, double tolerance) {
        return Math.abs(actual[0] - x) <= tolerance && Math.abs(actual[1] - y) <= tolerance
                && Math.abs(actual[2] - z) <= tolerance;
    }

    private static void appendUncertainty(JsonObject observation, String message) {
        JsonArray uncertainties;
        if (observation.has("uncertainties") && observation.get("uncertainties").isJsonArray()) {
            uncertainties = observation.getAsJsonArray("uncertainties");
        } else {
            uncertainties = new JsonArray();
            observation.add("uncertainties", uncertainties);
        }
        uncertainties.add(message);
    }

    private static void copyPrimitive(JsonObject from, JsonObject to, String key) {
        if (from.has(key) && from.get(key).isJsonPrimitive()) to.add(key, from.get(key));
    }

    private static boolean fits(String json) {
        return json.getBytes(StandardCharsets.UTF_8).length <= 16 * 1024;
    }

    private record ScanOutcome(EnvironmentScanResult result, boolean requested, boolean failed) {
    }

    private record ScanAndCapture(ScanOutcome scan, VisionCaptureTransport.CaptureResult capture) {
    }

    private record ActiveObservation(ObservationKey key, CompletableFuture<String> future) {
    }

    private record CachedObservation(long completedNanos, String result) {
    }

    private record ObservationKey(String dimension, UUID maid, long blockPosition, float yaw, float pitch,
                                  ScanMode mode, String focus, String siteFingerprint,
                                  boolean shallowScanEnabled) {
    }

    private static String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

}
