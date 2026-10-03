package com.wjx.touhou_aifun.vision;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AvailableVisionSitesTest {
    @Test
    void preferredUsableSiteWins() {
        VisionSite first = usable("first");
        VisionSite preferred = usable("preferred");
        assertEquals(preferred, AvailableVisionSites.chooseSelected(List.of(first, preferred), "preferred"));
    }

    @Test
    void invalidPreferredSiteDoesNotSilentlyChangeProvider() {
        VisionSite fallback = usable("fallback");
        VisionSite invalidPreferred = new VisionSite("preferred", "Preferred", "custom",
                "https://example.invalid/v1", "model", "", false);
        assertNull(AvailableVisionSites.chooseSelected(List.of(invalidPreferred, fallback), "preferred"));
    }

    @Test
    void noUsableSiteDisablesOnlyImageObservation() {
        VisionSite invalid = new VisionSite("invalid", "Invalid", "custom", "not-a-url", "", "", false);
        assertNull(AvailableVisionSites.chooseSelected(List.of(invalid), "invalid"));
    }

    private static VisionSite usable(String id) {
        return new VisionSite(id, id, "custom", "https://example.invalid/v1", "model", "secret", false);
    }
}
