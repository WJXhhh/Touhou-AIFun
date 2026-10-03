package com.wjx.touhou_aifun.vision;

import java.util.concurrent.CompletableFuture;

/** Provider-neutral visual client. */
public interface VisionClient {
    CompletableFuture<VisionObservation> observe(VisionRequest request);

    static VisionClient forSite(VisionSite site) {
        if (site.source() != null && UnifiedModelCatalog.protocol(site.source(), site.model())
                != UnifiedModelCatalog.VisualProtocol.CHAT_COMPLETIONS) return new ProtocolVisionClient(site);
        if (site != null && "sensenova".equalsIgnoreCase(site.provider())) {
            return new SenseNovaVisionClient(site);
        }
        return new OpenAICompatibleVisionClient(site);
    }
}
