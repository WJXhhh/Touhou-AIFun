package com.wjx.touhou_aifun.vision;

import java.util.concurrent.CompletableFuture;

/** Provider-neutral visual client. */
public interface VisionClient {
    CompletableFuture<VisionObservation> observe(VisionRequest request);

    static VisionClient forSite(VisionSite site) {
        return new OpenAICompatibleVisionClient(site);
    }
}
