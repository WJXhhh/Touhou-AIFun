package com.wjx.touhou_aifun.vision.scan;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/** Wire-level arguments of scan_surroundings. */
public record EnvironmentScanRequest(ScanMode mode, ScanDirection direction, int maxDistance, String focus) {
    public static final Codec<EnvironmentScanRequest> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ScanMode.CODEC.optionalFieldOf("mode", ScanMode.BOTH).forGetter(EnvironmentScanRequest::mode),
            ScanDirection.CODEC.optionalFieldOf("direction", ScanDirection.ALL).forGetter(EnvironmentScanRequest::direction),
            Codec.INT.optionalFieldOf("max_distance", 20).forGetter(EnvironmentScanRequest::maxDistance),
            Codec.STRING.optionalFieldOf("focus", "").forGetter(EnvironmentScanRequest::focus)
    ).apply(instance, EnvironmentScanRequest::new));

    public EnvironmentScanRequest {
        mode = mode == null ? ScanMode.BOTH : mode;
        direction = direction == null ? ScanDirection.ALL : direction;
        maxDistance = Math.max(1, Math.min(20, maxDistance));
        focus = focus == null ? "" : focus.trim();
        if (focus.length() > 256) focus = focus.substring(0, 256);
    }

    public static EnvironmentScanRequest defaults() {
        return new EnvironmentScanRequest(ScanMode.BOTH, ScanDirection.ALL, 20, "");
    }
}
