package com.wjx.touhou_aifun.compat.ai.stt;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.site.AvailableSites;
import com.github.tartaricacid.touhoulittlemaid.ai.service.stt.STTSite;
import com.wjx.touhou_aifun.config.TouhouAIFunConfig;

import javax.annotation.Nullable;

/** Shared client-side resolution of the configured STT site and its buffered transcription ability. */
public final class SttSiteSelection {
    private SttSiteSelection() {
    }

    @Nullable
    public static STTSite resolve() {
        String configured;
        try {
            configured = TouhouAIFunConfig.STT_SELECTED_SITE.get();
        } catch (RuntimeException unavailable) {
            configured = "";
        }
        STTSite selected = configured == null ? null : AvailableSites.STT_SITES.get(configured);
        if (selected != null && selected.enabled()) {
            return selected;
        }
        return AvailableSites.STT_SITES.values().stream()
                .filter(STTSite::enabled)
                .findFirst()
                .orElse(null);
    }

    public static boolean supportsBufferedTranscription(@Nullable STTSite site) {
        if (site == null) {
            return false;
        }
        try {
            return site.client() instanceof BufferedSTTClient;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    @Nullable
    public static STTSite resolveBuffered() {
        STTSite site = resolve();
        return supportsBufferedTranscription(site) ? site : null;
    }
}
