package com.wjx.touhou_aifun.vision;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.wjx.touhou_aifun.vision.scan.ScanMode;

/** Arguments of observe_surroundings. */
public record ObservationRequest(String focus, ScanMode scanMode) {
    public static final Codec<ObservationRequest> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.optionalFieldOf("focus", "").forGetter(ObservationRequest::focus),
            ScanMode.CODEC.optionalFieldOf("scan_mode", ScanMode.BOTH).forGetter(ObservationRequest::scanMode)
    ).apply(instance, ObservationRequest::new));

    public ObservationRequest {
        focus = focus == null ? "" : focus.trim();
        if (focus.length() > 256) focus = focus.substring(0, 256);
        scanMode = scanMode == null ? ScanMode.BOTH : scanMode;
    }
}
