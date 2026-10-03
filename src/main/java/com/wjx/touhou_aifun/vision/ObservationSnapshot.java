package com.wjx.touhou_aifun.vision;

import com.google.gson.JsonObject;
import com.wjx.touhou_aifun.vision.scan.EnvironmentScanResult;
import java.util.Map;
import java.util.UUID;

/** Completed capture, with grounding from the same capture interval. Images are memory-only. */
public record ObservationSnapshot(String id, UUID maidId, UUID ownerId, String dimension,
                                  long capturedAtMillis, long startTick, long endTick, float yaw,
                                  Map<String, String> images, EnvironmentScanResult scan, String scanStatus,
                                  String sourceKind, JsonObject sourceMetadata) {
    public ObservationSnapshot { images = Map.copyOf(images); sourceMetadata = sourceMetadata == null ? new JsonObject() : sourceMetadata.deepCopy(); }
    public ObservationSnapshot(String id, UUID maidId, UUID ownerId, String dimension, long capturedAtMillis,
                               long startTick, long endTick, float yaw, Map<String, String> images, EnvironmentScanResult scan, String scanStatus) {
        this(id, maidId, ownerId, dimension, capturedAtMillis, startTick, endTick, yaw, images, scan, scanStatus, "WORLD", new JsonObject());
    }

    public long compressedBytes() {
        return images.values().stream().mapToLong(value -> {
            int comma = value.indexOf(',');
            return comma < 0 ? value.length() : (value.length() - comma - 1L) * 3 / 4;
        }).sum();
    }

    public JsonObject metadata() {
        JsonObject result = new JsonObject();
        result.addProperty("observation_id", id);
        result.addProperty("dimension", dimension);
        result.addProperty("captured_at", java.time.Instant.ofEpochMilli(capturedAtMillis).toString());
        result.addProperty("image_start_tick", startTick);
        result.addProperty("image_tick", endTick);
        if (Float.isFinite(yaw)) result.addProperty("capture_yaw", yaw);
        result.addProperty("scan_status", scanStatus);
        result.addProperty("historical", true);
        result.addProperty("source_kind", sourceKind);
        if ("GUI".equals(sourceKind)) result.add("gui", sourceMetadata.deepCopy());
        return result;
    }

    public VisionRequest request(VisionSite site, com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid maid, String focus) {
        return new VisionRequest(site, maid, focus, scan == null ? "" : scan.toJson(), images,
                scan == null ? -1 : scan.gameTick(), startTick, endTick, yaw, sourceKind, sourceMetadata);
    }
}
