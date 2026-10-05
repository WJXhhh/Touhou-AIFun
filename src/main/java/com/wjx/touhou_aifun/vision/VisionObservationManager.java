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
    private static final ConcurrentMap<UUID, CompletableFuture<String>> ACTIVE = new ConcurrentHashMap<>();

    private VisionObservationManager() { }

    public static void cancelForMaid(UUID maidId) {
        if (maidId == null) return;
        CompletableFuture<String> active = ACTIVE.remove(maidId);
        if (active != null) active.cancel(false);
        MultimodalTurnContext.clearMaid(maidId);
        VisionCaptureTransport.cancelForMaid(maidId);
        VisionHttpCancellation.cancelForMaid(maidId);
        com.wjx.touhou_aifun.vision.scan.VisionScanScheduler.cancelForMaid(maidId);
    }

    public static void clearMaid(UUID maidId) {
        cancelForMaid(maidId);
        ObservationSnapshotCache.INSTANCE.clearMaid(maidId);
    }

    public static void clearAll() {
        ACTIVE.values().forEach(future -> future.cancel(false));
        ACTIVE.clear();
        MultimodalTurnContext.clearAll();
        ObservationSnapshotCache.INSTANCE.clear();
        UnifiedModelCatalog.clearSessionRejections();
        VisionCaptureTransport.cancelAll();
        VisionHttpCancellation.cancelAll();
        com.wjx.touhou_aifun.vision.scan.VisionScanScheduler.cancelAll();
        VisionScanCache.clearAll();
    }

    public static CompletableFuture<String> observe(
            com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback callback,
            ObservationRequest request, com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMClient client,
            Consumer<ObservationStage> progress) {
        EntityMaid maid = callback.getMaid();
        if (!TouhouAIFunConfig.VISION_ENABLED.get()) return CompletableFuture.completedFuture(error("vision_disabled"));
        if (com.wjx.touhou_aifun.chat.ChatFlowManager.isSuperseded(maid.getUUID(), callback)) {
            return CompletableFuture.failedFuture(new CancellationException());
        }
        ObservationRequest normalized = request == null ? new ObservationRequest("", ScanMode.BOTH) : request;
        long ticket = VisionHttpCancellation.ticket(maid.getUUID());
        CompletableFuture<ScanOutcome> scanFuture = scan(maid, normalized, progress);
        if (!VisionRequestLimiter.tryAcquire()) {
            return scanFuture.thenApply(outcome -> scanFailure(outcome, "rate_limited"));
        }
        notifyProgress(progress, ObservationStage.CAPTURING);
        CompletableFuture<ScanAndCapture> captured = scanFuture.thenCombine(
                VisionCaptureTransport.requestCapture(maid), ScanAndCapture::new);
        VisionRequestLimiter.releaseWhenDone(captured);
        CompletableFuture<String> result = captured.thenCompose(combined -> {
            CompletableFuture<String> routed = new CompletableFuture<>();
            callback.runOnServerThread(() -> {
                if (!VisionHttpCancellation.isCurrent(maid.getUUID(), ticket)
                        || com.wjx.touhou_aifun.chat.ChatFlowManager.isSuperseded(maid.getUUID(), callback) || maid.isRemoved()) {
                    routed.completeExceptionally(new CancellationException());
                    return;
                }
                var capture = combined.capture();
                if (capture == null || !capture.success()) {
                    routed.complete(scanFailure(combined.scan(), capture == null ? "capture_failed" : capture.error()));
                    return;
                }
                ObservationSnapshot snapshot = new ObservationSnapshot(UUID.randomUUID().toString(), maid.getUUID(),
                        maid.getOwnerUUID(), maid.level().dimension().location().toString(), System.currentTimeMillis(),
                        capture.captureStartTick(), capture.captureEndTick(), capture.captureYaw(), capture.images(),
                        combined.scan().result(), scanStatus(combined.scan()));
                ObservationSnapshotCache.INSTANCE.put(snapshot);
                LOGGER.debug("Captured observation {}", snapshot.id());
                route(callback, client, snapshot, normalized.focus(), progress).whenComplete((value, failure) -> {
                    if (failure == null) routed.complete(value); else routed.completeExceptionally(failure);
                });
            });
            return routed;
        });
        ACTIVE.put(maid.getUUID(), result);
        result.whenComplete((value, error) -> ACTIVE.remove(maid.getUUID(), result));
        return result;
    }

    private static CompletableFuture<ScanOutcome> scan(EntityMaid maid, ObservationRequest request, Consumer<ObservationStage> progress) {
        if (!TouhouAIFunConfig.SHALLOW_SCAN_ENABLED.get() || request.scanMode() == ScanMode.NONE) {
            return CompletableFuture.completedFuture(new ScanOutcome(null, false, false));
        }
        notifyProgress(progress, ObservationStage.SCANNING);
        try {
            return VisionScanCache.scanAsync(maid, new EnvironmentScanRequest(request.scanMode(), ScanDirection.ALL, 20, request.focus()))
                    .handle((value, error) -> {
                        if (error instanceof CancellationException || (error != null && error.getCause() instanceof CancellationException)) {
                            throw new CompletionException(error);
                        }
                        return new ScanOutcome(value, true, error != null);
                    });
        } catch (RuntimeException error) {
            LOGGER.warn("Unable to schedule observation grounding", error);
            return CompletableFuture.completedFuture(new ScanOutcome(null, true, true));
        }
    }

    public static CompletableFuture<String> review(
            com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback callback,
            com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMClient client, String action, String id, String focus,
            Consumer<ObservationStage> progress) {
        var maid = callback.getMaid();
        if (!TouhouAIFunConfig.VISION_ENABLED.get()) return CompletableFuture.completedFuture(error("vision_disabled"));
        if ("list".equals(action)) {
            JsonObject result = new JsonObject();
            result.addProperty("status", "ok");
            JsonArray entries = new JsonArray();
            ObservationSnapshotCache.INSTANCE.list(maid.getUUID(), maid.getOwnerUUID()).forEach(snapshot -> entries.add(snapshot.metadata()));
            result.add("observations", entries);
            return CompletableFuture.completedFuture(result.toString());
        }
        if (!"view".equals(action)) return CompletableFuture.completedFuture(error("invalid_action"));
        ObservationSnapshot snapshot = ObservationSnapshotCache.INSTANCE.get(maid.getUUID(), maid.getOwnerUUID(), id);
        if (snapshot == null) return CompletableFuture.completedFuture(error("observation_expired_or_not_found"));
        return route(callback, client, snapshot, focus, progress);
    }

    private static CompletableFuture<String> route(
            com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback callback,
            com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMClient client,
            ObservationSnapshot snapshot, String focus, Consumer<ObservationStage> progress) {
        if (com.wjx.touhou_aifun.chat.ChatFlowManager.isSuperseded(callback.getMaid().getUUID(), callback)) {
            return CompletableFuture.failedFuture(new CancellationException());
        }
        MultimodalTurnContext.clear(callback);
        if (MultimodalTurnContext.canAttach(callback, client)) {
            LOGGER.debug("Observation {} attaches images to the main model", snapshot.id());
            MultimodalTurnContext.attach(callback, snapshot, focus);
            JsonObject result = snapshot.metadata();
            result.addProperty("status", "failed".equals(snapshot.scanStatus()) ? "partial" : "ok");
            result.addProperty("image_status", "attached_to_main_model");
            result.addProperty("note", "Images follow the complete tool-result batch. Use review_observation to view this capture in a later turn; observe_surroundings captures the current world again.");
            return CompletableFuture.completedFuture(boundCombined(result, snapshot.scan()));
        }
        return identify(callback, snapshot, focus, progress);
    }

    public static CompletableFuture<String> identify(
            com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback callback,
            ObservationSnapshot snapshot, String focus, Consumer<ObservationStage> progress) {
        if (com.wjx.touhou_aifun.chat.ChatFlowManager.isSuperseded(callback.getMaid().getUUID(), callback)) {
            return CompletableFuture.failedFuture(new CancellationException());
        }
        VisionSite site = AvailableVisionSites.selected();
        if (site == null) return CompletableFuture.completedFuture(snapshotFailure(snapshot, "no_usable_independent_visual_model"));
        if (!VisionRequestLimiter.tryAcquire()) return CompletableFuture.completedFuture(snapshotFailure(snapshot, "rate_limited"));
        notifyProgress(progress, ObservationStage.ANALYZING);
        LOGGER.debug("Independent vision inference for observation {} using {}", snapshot.id(), site.id());
        CompletableFuture<String> result;
        long inferenceStarted=System.nanoTime();
        var timing=com.wjx.touhou_aifun.chat.agent.AgentTelemetry.start(callback,"vision_inference");
        try {
            result = VisionClient.forSite(site).observe(snapshot.request(site, callback.getMaid(), focus))
                    .thenApply(observation -> {
                        JsonObject value = JsonParser.parseString(mergeScan(observation.toJson(), snapshot.scan(), snapshot.scanStatus())).getAsJsonObject();
                        snapshot.metadata().entrySet().forEach(entry -> value.add(entry.getKey(), entry.getValue()));
                        return boundCombined(value, snapshot.scan());
                    });
        } catch (RuntimeException error) {
            result = CompletableFuture.completedFuture(snapshotFailure(snapshot, "visual_request_failed"));
        }
        result.whenComplete((value,error)->com.wjx.touhou_aifun.chat.agent.AgentTelemetry.stage("vision_inference",inferenceStarted,value==null?0:value.length()));
        result.whenComplete((value,error)->timing.finish(error==null?"received":com.wjx.touhou_aifun.chat.agent.AgentTelemetry.failureStatus(error),value==null?0:value.length()));
        return VisionRequestLimiter.releaseWhenDone(result);
    }

    public static String snapshotFailure(ObservationSnapshot snapshot, String reason) {
        JsonObject value = snapshot.metadata();
        value.addProperty("status", "ok".equals(snapshot.scanStatus()) ? "partial" : "failed");
        value.addProperty("image_status", "failed");
        value.addProperty("error", reason);
        value.addProperty("uncertainty", "Images have not been interpreted. Use successful scan data only; never claim visual recognition.");
        return boundCombined(value, snapshot.scan());
    }

    private static String scanFailure(ScanOutcome outcome, String reason) {
        JsonObject value = new JsonObject();
        value.addProperty("status", "ok".equals(scanStatus(outcome)) ? "partial" : "failed");
        value.addProperty("scan_status", scanStatus(outcome));
        value.addProperty("image_status", "failed");
        value.addProperty("error", reason);
        return boundCombined(value, outcome.result());
    }

    private static String error(String reason) {
        JsonObject value = new JsonObject(); value.addProperty("status", "failed"); value.addProperty("error", reason);
        return value.toString();
    }

    static boolean reusableVisualResult(String value) {
        try {
            JsonObject object = JsonParser.parseString(value).getAsJsonObject();
            return "ok".equals(string(object, "status")) && !string(object, "site_id").isBlank()
                    && object.has("image_tick") && object.get("image_tick").getAsLong() >= 0;
        } catch (RuntimeException ignored) { return false; }
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
        copyPrimitive(object, minimal, "observation_id");
        copyPrimitive(object, minimal, "captured_at");
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
        String observationId = string(object, "observation_id");
        if (observationId.matches("[a-fA-F0-9-]{36}")) emergency.addProperty("observation_id", observationId);
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
            for (var sign : scan.signTexts()) {
                if (registryId.equals(sign.registryId()) && near(position, sign.dx(), sign.dy(), sign.dz(), 0.01)) {
                    return true;
                }
            }
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

    private static String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

}
