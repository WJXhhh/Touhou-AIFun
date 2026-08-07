package com.wjx.touhou_aifun.vision;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import com.wjx.touhou_aifun.config.TouhouAIFunConfig;
import com.wjx.touhou_aifun.vision.scan.EnvironmentScanRequest;
import com.wjx.touhou_aifun.vision.scan.EnvironmentScanResult;
import com.wjx.touhou_aifun.vision.scan.ScanDirection;
import com.wjx.touhou_aifun.vision.scan.ScanMode;
import com.wjx.touhou_aifun.vision.scan.VisionScanCache;
import org.slf4j.Logger;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Coordinates the optional client capture and the always-local scan. */
public final class VisionObservationManager {
    private static final Logger LOGGER = LogUtils.getLogger();

    private VisionObservationManager() {
    }

    /**
     * Called by the tool on the server thread. A capture transport can complete the returned future;
     * until a client is attached, this safe fallback still gives the LLM the local scan.
     */
    public static CompletableFuture<String> observe(EntityMaid maid, ObservationRequest request) {
        ObservationRequest normalized = request == null ? new ObservationRequest("", ScanMode.BOTH) : request;
        CompletableFuture<ScanOutcome> scanFuture;
        if (TouhouAIFunConfig.SHALLOW_SCAN_ENABLED.get() && normalized.scanMode() != ScanMode.NONE) {
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
        return scanFuture.thenCompose(outcome -> {
            EnvironmentScanResult scan = outcome.result();
            String scanJson = scan == null ? "" : scan.toJson();
            String scanStatus = outcome.failed() ? "failed" : outcome.requested() ? "ok" : "not_requested";
            VisionSite site = TouhouAIFunConfig.VISION_ENABLED.get() ? AvailableVisionSites.selected() : null;
            if (site == null) {
                String uncertainty = outcome.failed()
                        ? "The local scan failed; do not infer exact registry identities from an image."
                        : "No image was sent to a visual model; use scan registry identities only.";
                String fallback = "{\"status\":\"ok\",\"scan_status\":\"" + scanStatus
                        + "\",\"image_status\":\"disabled_or_no_enabled_site\",\"scan\":"
                        + (scanJson.isBlank() ? "null" : scanJson) + ",\"uncertainties\":[\""
                        + uncertainty.replace("\"", "\\\"") + "\"]}";
                return CompletableFuture.completedFuture(fallback);
            }

            // The client capture packet is intentionally isolated from this coordinator. It returns
            // an empty map on timeout or when the owner is offline instead of hanging the tool loop.
            final EnvironmentScanResult completedScan = scan;
            final String completedScanJson = scanJson;
            final String completedScanStatus = scanStatus;
            final long captureTick = maid.level().getGameTime();
            return VisionCaptureTransport.requestCapture(maid, normalized.focus(), completedScanJson)
                    .thenCompose(images -> {
                        if (images == null || images.isEmpty()) {
                            return CompletableFuture.completedFuture("{\"status\":\"failed\",\"scan_status\":\""
                                    + completedScanStatus
                                    + "\",\"error\":\"maid owner client did not provide a cubemap\",\"scan\":"
                                    + (completedScanJson.isBlank() ? "null" : completedScanJson) + "}");
                        }
                        VisionRequest visionRequest = new VisionRequest(site, maid, normalized.focus(), completedScanJson,
                                images, completedScan == null ? -1 : completedScan.gameTick(), captureTick);
                        return VisionClient.forSite(site).observe(visionRequest)
                                .thenApply(observation -> mergeScan(observation.toJson(), completedScanJson,
                                        completedScanStatus));
                    });
        });
    }

    private static String mergeScan(String observationJson, String scanJson, String scanStatus) {
        try {
            JsonObject object = JsonParser.parseString(observationJson).getAsJsonObject();
            if (scanJson != null && !scanJson.isBlank()) {
                object.add("scan", JsonParser.parseString(scanJson));
            }
            object.addProperty("scan_status", scanStatus == null ? "not_requested" : scanStatus);
            GsonBuilder builder = new GsonBuilder().disableHtmlEscaping();
            String json = builder.create().toJson(object);
            if (json.length() <= 16 * 1024) return json;
            object.remove("raw_model_text");
            object.addProperty("truncated", true);
            json = builder.create().toJson(object);
            if (json.length() <= 16 * 1024) return json;
            if (object.has("scene_summary")) {
                object.addProperty("scene_summary", truncate(object.get("scene_summary").getAsString(), 2048));
            }
            if (object.has("answer_to_focus")) {
                object.addProperty("answer_to_focus", truncate(object.get("answer_to_focus").getAsString(), 1024));
            }
            return builder.create().toJson(object);
        } catch (Exception ignored) {
            return observationJson;
        }
    }

    private record ScanOutcome(EnvironmentScanResult result, boolean requested, boolean failed) {
    }

    private static String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }
}
