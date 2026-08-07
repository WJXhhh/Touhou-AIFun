package com.wjx.touhou_aifun.vision.scan;

/** One representative surface hit. Coordinates are relative to the maid's block position. */
public record SurfaceBlockHit(String registryId, int dx, int dy, int dz, double distance,
                              String direction, OpacityClass opacity, int remainingBudget,
                              int count, double farthestDistance) {
    public SurfaceBlockHit(String registryId, int dx, int dy, int dz, double distance,
                           String direction, OpacityClass opacity, int remainingBudget) {
        this(registryId, dx, dy, dz, distance, direction, opacity, remainingBudget, 1, distance);
    }
}
