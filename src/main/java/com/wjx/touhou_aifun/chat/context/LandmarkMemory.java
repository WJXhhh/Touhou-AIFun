package com.wjx.touhou_aifun.chat.context;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Pure helpers for the durable landmark list. Kept free of Minecraft types so the upsert, ordering,
 * eviction and prompt rendering rules can be unit-tested without a running server.
 */
public final class LandmarkMemory {
    public static final int MAX_PLACES = 32;
    public static final int MAX_CHAT_ROWS = 8;
    public static final int MAX_CHECKPOINT_ROWS = 8;
    /** GUI interaction range in blocks (see MaidGuiSessionManager max_distance clamp). */
    public static final int GUI_RANGE = 16;

    private LandmarkMemory() {
    }

    /** Insert or refresh one place, then evict the least recently seen place over capacity. */
    public static void upsert(List<LandmarkPlace> places, LandmarkPlace candidate) {
        if (places == null || candidate == null || !candidate.hasIdentity()) return;
        for (LandmarkPlace existing : places) {
            if (existing.samePosition(candidate.dimension(), candidate.x(), candidate.y(), candidate.z())) {
                existing.seen(candidate.blockId(), candidate.label(), candidate.lastSeenGameTime(),
                        candidate.source());
                return;
            }
        }
        places.add(candidate);
        while (places.size() > MAX_PLACES) {
            LandmarkPlace oldest = places.stream()
                    .min(Comparator.comparingLong(LandmarkPlace::lastSeenGameTime)
                            .thenComparingInt(LandmarkPlace::hitCount))
                    .orElse(null);
            if (oldest == null) break;
            places.remove(oldest);
        }
    }

    /** Places in the current dimension, most used/seen first, capped for prompt injection. */
    public static List<LandmarkPlace> forDimension(List<LandmarkPlace> places, String dimension, int limit) {
        if (places == null || places.isEmpty() || limit <= 0) return List.of();
        return places.stream()
                .filter(LandmarkPlace::hasIdentity)
                .filter(place -> place.dimension().equals(dimension))
                .sorted(Comparator.comparingInt(LandmarkPlace::hitCount).reversed()
                        .thenComparing(Comparator.comparingLong(LandmarkPlace::lastSeenGameTime).reversed()))
                .limit(limit)
                .toList();
    }

    public static double distance(double maidX, double maidY, double maidZ,
                                  LandmarkPlace place) {
        double dx = place.x() + 0.5 - maidX;
        double dy = place.y() + 0.5 - maidY;
        double dz = place.z() + 0.5 - maidZ;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Compact prompt section for ordinary chat and task checkpoints. Falls back to an empty string
     * when nothing is known, so the section never costs tokens for a fresh maid.
     */
    public static String renderSection(List<LandmarkPlace> places, String dimension,
                                       double maidX, double maidY, double maidZ,
                                       long nowGameTime, int limit) {
        List<LandmarkPlace> rows = forDimension(places, dimension, limit);
        if (rows.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        out.append("### Known landmarks (fallible, verify before use)\n")
                .append("World: ").append(dimension)
                .append(". These are remembered absolute positions the maid actually used or observed; ")
                .append("prefer them over guessing, but confirm the block is still there. GUI tools can only open a block within ")
                .append(GUI_RANGE).append(" blocks, so walk closer first for a farther landmark.\n");
        for (LandmarkPlace place : rows) {
            out.append("- ").append(place.blockId()).append(" @ ")
                    .append(place.x()).append(',').append(place.y()).append(',').append(place.z());
            if (!place.label().isBlank()) out.append(" [").append(place.label()).append(']');
            out.append(" (").append(Math.round(distance(maidX, maidY, maidZ, place)))
                    .append(" blocks away, seen ").append(place.hitCount())
                    .append("x, ").append(ageText(nowGameTime - place.lastSeenGameTime())).append(')');
            out.append('\n');
        }
        return out.toString().trim();
    }

    static String ageText(long ageTicks) {
        if (ageTicks <= 0) return "just now";
        long seconds = ageTicks / 20;
        if (seconds < 60) return "just now";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + "m ago";
        long hours = minutes / 60;
        if (hours < 24) return hours + "h ago";
        return (hours / 24) + "d ago";
    }

    /** Defensive copy used when a caller must not expose the live state list. */
    public static List<LandmarkPlace> copy(List<LandmarkPlace> places) {
        return places == null ? List.of() : new ArrayList<>(places);
    }
}
