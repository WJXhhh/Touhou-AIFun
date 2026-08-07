package com.wjx.touhou_aifun.vision.scan;

import java.util.List;

/** Compact entity observation used by both visual grounding and nearby_entities. */
public record ScannedEntity(String registryId, int entityId, String customName, String category,
                            double dx, double dy, double dz, double distance, String direction,
                            String elevation, String visibility, Float health, Float maxHealth,
                            String pose, List<String> states, String itemId, int itemCount) {
}
