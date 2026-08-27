package com.wjx.touhou_aifun.chat.context;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Pure deterministic selection used by per-turn tool catalog snapshots. */
public final class ToolSelectionPolicy {
    private ToolSelectionPolicy() {
    }

    public static List<String> select(List<String> coreIds, List<String> optionalIds,
                                      Set<String> requestedIds, Set<String> availableIds) {
        List<String> result = new ArrayList<>();
        for (String id : coreIds) {
            if (availableIds.contains(id) && !result.contains(id)) result.add(id);
        }
        if (requestedIds != null) {
            for (String id : optionalIds) {
                if (requestedIds.contains(id) && availableIds.contains(id) && !result.contains(id)) {
                    result.add(id);
                }
            }
        }
        return List.copyOf(result);
    }
}
