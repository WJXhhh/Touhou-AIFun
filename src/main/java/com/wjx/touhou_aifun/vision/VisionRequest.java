package com.wjx.touhou_aifun.vision;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Collections;

/** Normalized request passed to a provider adapter. Image values are in-memory data URLs. */
public record VisionRequest(VisionSite site, EntityMaid maid, String focus, String scanJson,
                            Map<String, String> images, long scanTick, long imageStartTick,
                            long imageTick, float imageYaw, String sourceKind, com.google.gson.JsonObject sourceMetadata) {
    public VisionRequest {
        focus = focus == null ? "" : focus.trim();
        scanJson = scanJson == null ? "" : scanJson;
        images = images == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(images));
        sourceMetadata = sourceMetadata == null ? new com.google.gson.JsonObject() : sourceMetadata.deepCopy();
    }

    public VisionRequest(VisionSite site, EntityMaid maid, String focus, String scanJson, Map<String, String> images,
                         long scanTick, long imageStartTick, long imageTick, float imageYaw) {
        this(site, maid, focus, scanJson, images, scanTick, imageStartTick, imageTick, imageYaw, "WORLD", new com.google.gson.JsonObject());
    }

    public VisionRequest(VisionSite site, EntityMaid maid, String focus, String scanJson,
                         Map<String, String> images, long scanTick, long imageTick) {
        this(site, maid, focus, scanJson, images, scanTick, imageTick, imageTick,
                maid == null ? Float.NaN : maid.getYRot());
    }

    public VisionRequest(VisionSite site, EntityMaid maid, String focus, String scanJson,
                         Map<String, String> images, long scanTick) {
        this(site, maid, focus, scanJson, images, scanTick, -1, -1,
                maid == null ? Float.NaN : maid.getYRot());
    }
}
