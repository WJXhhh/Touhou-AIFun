package com.wjx.touhou_aifun.vision.scan;

import java.util.Map;

/** Exact, visible block of interest. Block entity contents are intentionally not exposed. */
public record ImportantBlockHit(String registryId, int dx, int dy, int dz, double distance,
                                String direction, Map<String, String> properties, String blockEntityType) {
}
