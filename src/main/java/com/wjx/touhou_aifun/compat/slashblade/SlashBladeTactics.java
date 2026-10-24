package com.wjx.touhou_aifun.compat.slashblade;

import java.util.Set;

/** Pure, conservative SA classification; unknown addon arts are not auto-cast. */
public final class SlashBladeTactics {
    private static final Set<String> ARTS = Set.of("judgement_cut", "sakura_end", "void_slash",
            "circle_slash", "drive_horizontal", "drive_vertical", "wave_edge", "piercing");

    private SlashBladeTactics() {}

    public static boolean knownArt(String id) {
        return id != null && id.startsWith("slashblade:") && ARTS.contains(id.substring(11));
    }

    public static boolean wantsArt(String id, double distance, double reach, int enemies, boolean lowHealth) {
        if (!knownArt(id) || lowHealth) return false;
        return switch (id.substring(11)) {
            case "judgement_cut" -> distance >= reach * 0.65 && distance <= 14;
            case "drive_horizontal", "drive_vertical", "wave_edge" -> distance >= 3 && distance <= 12;
            case "piercing" -> distance >= 2.5 && distance <= 6;
            case "circle_slash" -> enemies >= 2 && distance <= reach;
            case "sakura_end", "void_slash" -> distance <= reach && enemies <= 2;
            default -> false;
        };
    }

    public static boolean phantomAllowed(boolean bewitched, boolean broken, boolean sealed, int power,
                                         int souls, int cost) {
        return bewitched && !broken && !sealed && power > 0 && cost > 0 && souls >= cost;
    }
}
