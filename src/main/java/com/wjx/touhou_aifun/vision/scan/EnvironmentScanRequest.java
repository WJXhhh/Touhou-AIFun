package com.wjx.touhou_aifun.vision.scan;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/** Wire-level arguments of scan_surroundings. */
public record EnvironmentScanRequest(ScanMode mode, ScanDirection direction, int maxDistance, String focus, String intent, String detail) {
    public EnvironmentScanRequest(ScanMode mode, ScanDirection direction, int maxDistance, String focus) {
        this(mode, direction, maxDistance, focus, "overview", "full");
    }
    public static final Codec<EnvironmentScanRequest> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ScanMode.CODEC.optionalFieldOf("mode", ScanMode.BOTH).forGetter(EnvironmentScanRequest::mode),
            ScanDirection.CODEC.optionalFieldOf("direction", ScanDirection.ALL).forGetter(EnvironmentScanRequest::direction),
            Codec.INT.optionalFieldOf("max_distance", 20).forGetter(EnvironmentScanRequest::maxDistance),
            Codec.STRING.optionalFieldOf("focus", "").forGetter(EnvironmentScanRequest::focus),
            Codec.STRING.optionalFieldOf("intent", "overview").forGetter(EnvironmentScanRequest::intent),
            Codec.STRING.optionalFieldOf("detail", "full").forGetter(EnvironmentScanRequest::detail)
    ).apply(instance, EnvironmentScanRequest::new));

    public EnvironmentScanRequest {
        if (!java.util.Set.of("overview", "locate", "read_signs").contains(intent)) throw new IllegalArgumentException("invalid_scan_intent");
        if (!java.util.Set.of("summary", "full").contains(detail)) throw new IllegalArgumentException("invalid_scan_detail");
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
