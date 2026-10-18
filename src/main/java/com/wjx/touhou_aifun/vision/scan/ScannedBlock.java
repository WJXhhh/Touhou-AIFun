package com.wjx.touhou_aifun.vision.scan;

import java.util.Map;

/** One observed voxel; unknown cells deliberately have no registry identity or state. */
public record ScannedBlock(String registryId, int dx, int dy, int dz, double distance,
                           String status, Map<String, String> properties) {
    public ScannedBlock { properties = Map.copyOf(properties); }
}
