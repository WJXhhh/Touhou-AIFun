package com.wjx.touhou_aifun.vision.scan;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/** Wire-level arguments of scan_surroundings. */
public record EnvironmentScanRequest(ScanMode mode, ScanDirection direction, int maxDistance, String focus,
                                     String intent, String detail, ScanArea area, int offset, int limit, String observationId) {
    public EnvironmentScanRequest(ScanMode mode, ScanDirection direction, int maxDistance, String focus, String intent, String detail) {
        this(mode, direction, maxDistance, focus, intent, detail, null, 0, 32, "");
    }
    public EnvironmentScanRequest(ScanMode mode, ScanDirection direction, int maxDistance, String focus) {
        this(mode, direction, maxDistance, focus, "overview", "full");
    }
    public static final Codec<EnvironmentScanRequest> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ScanMode.CODEC.optionalFieldOf("mode", ScanMode.BOTH).forGetter(EnvironmentScanRequest::mode),
            ScanDirection.CODEC.optionalFieldOf("direction", ScanDirection.ALL).forGetter(EnvironmentScanRequest::direction),
            Codec.INT.optionalFieldOf("max_distance", 20).forGetter(EnvironmentScanRequest::maxDistance),
            Codec.STRING.optionalFieldOf("focus", "").forGetter(EnvironmentScanRequest::focus),
            Codec.STRING.optionalFieldOf("intent", "overview").forGetter(EnvironmentScanRequest::intent),
            Codec.STRING.optionalFieldOf("detail", "full").forGetter(EnvironmentScanRequest::detail),
            ScanArea.CODEC.optionalFieldOf("area").forGetter(request -> java.util.Optional.ofNullable(request.area())),
            Codec.INT.optionalFieldOf("offset", 0).forGetter(EnvironmentScanRequest::offset),
            Codec.INT.optionalFieldOf("limit", 32).forGetter(EnvironmentScanRequest::limit),
            Codec.STRING.optionalFieldOf("observation_id", "").forGetter(EnvironmentScanRequest::observationId)
    ).apply(instance, (mode, direction, distance, focus, intent, detail, area, offset, limit, id) ->
            new EnvironmentScanRequest(mode, direction, distance, focus, intent, detail, area.orElse(null), offset, limit, id)));

    public EnvironmentScanRequest {
        if (!java.util.Set.of("overview", "locate", "read_signs", "inspect_area").contains(intent)) throw new IllegalArgumentException("invalid_scan_intent");
        if (!java.util.Set.of("summary", "full").contains(detail)) throw new IllegalArgumentException("invalid_scan_detail");
        mode = mode == null ? ScanMode.BOTH : mode;
        direction = direction == null ? ScanDirection.ALL : direction;
        observationId = observationId == null ? "" : observationId.trim();
        if (observationId.length() > 64) throw new IllegalArgumentException("invalid_observation_id");
        if (area != null) intent = "inspect_area";
        if (intent.equals("inspect_area")) {
            if (area == null && observationId.isBlank()) throw new IllegalArgumentException("inspect_area_requires_area");
            mode = ScanMode.BLOCKS;
            direction = ScanDirection.ALL;
        }
        offset = Math.max(0, offset);
        limit = Math.max(1, Math.min(64, limit));
        maxDistance = Math.max(1, Math.min(20, maxDistance));
        focus = focus == null ? "" : focus.trim();
        if (focus.length() > 256) focus = focus.substring(0, 256);
    }

    public static EnvironmentScanRequest defaults() {
        return new EnvironmentScanRequest(ScanMode.BOTH, ScanDirection.ALL, 20, "");
    }
}
