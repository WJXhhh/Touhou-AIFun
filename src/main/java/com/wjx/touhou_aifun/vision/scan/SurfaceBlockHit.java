package com.wjx.touhou_aifun.vision.scan;

import java.util.List;

/** One aggregated surface group with a few concrete relative positions for grounding. */
public record SurfaceBlockHit(String registryId, String direction, OpacityClass opacity, int count,
                              double nearestDistance, double farthestDistance,
                              List<Representative> representatives) {
    public SurfaceBlockHit {
        representatives = List.copyOf(representatives);
    }

    public record Representative(int dx, int dy, int dz, double distance, int remainingBudget) {
    }
}
