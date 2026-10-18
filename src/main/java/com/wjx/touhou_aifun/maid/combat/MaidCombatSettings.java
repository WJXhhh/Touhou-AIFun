package com.wjx.touhou_aifun.maid.combat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/**
 * Pure NBT codec for per-maid combat preferences. Kept separate from
 * {@link MaidCombatData} so the persistence format can be unit tested without a
 * living entity.
 */
public final class MaidCombatSettings {
    public static final int SCHEMA = 1;
    public static final String ROOT_TAG = "TouhouAIFunCombat";
    public static final int MAX_NOTES_CODE_POINTS = 240;

    private MaidCombatSettings() {
    }

    /** Null fields mean "inherit the global config"; notes is never null after read. */
    public record Values(String mode, Boolean protectOwner, Boolean attackPlayers,
                         Integer engageRadius, String notes) {
        public static final Values EMPTY = new Values(null, null, null, null, "");

        public boolean isEmpty() {
            return mode == null && protectOwner == null && attackPlayers == null
                    && engageRadius == null && (notes == null || notes.isBlank());
        }
    }

    public static Values read(CompoundTag parent) {
        if (parent == null || !parent.contains(ROOT_TAG, Tag.TAG_COMPOUND)) {
            return Values.EMPTY;
        }
        CompoundTag root = parent.getCompound(ROOT_TAG);
        String mode = root.contains("mode", Tag.TAG_STRING)
                ? normalizeMode(root.getString("mode")) : null;
        Boolean protectOwner = root.contains("protect_owner", Tag.TAG_BYTE)
                ? root.getBoolean("protect_owner") : null;
        Boolean attackPlayers = root.contains("attack_players", Tag.TAG_BYTE)
                ? root.getBoolean("attack_players") : null;
        Integer engageRadius = root.contains("engage_radius", Tag.TAG_INT)
                ? root.getInt("engage_radius") : null;
        String notes = root.contains("notes", Tag.TAG_STRING)
                ? sanitizeNotes(root.getString("notes")) : "";
        return new Values(mode, protectOwner, attackPlayers, engageRadius, notes);
    }

    public static void write(CompoundTag parent, Values values) {
        if (parent == null) {
            return;
        }
        if (values == null || values.isEmpty()) {
            parent.remove(ROOT_TAG);
            return;
        }
        CompoundTag root = new CompoundTag();
        root.putInt("schema", SCHEMA);
        String mode = normalizeMode(values.mode());
        if (mode != null) {
            root.putString("mode", mode);
        }
        if (values.protectOwner() != null) {
            root.putBoolean("protect_owner", values.protectOwner());
        }
        if (values.attackPlayers() != null) {
            root.putBoolean("attack_players", values.attackPlayers());
        }
        if (values.engageRadius() != null) {
            root.putInt("engage_radius", values.engageRadius());
        }
        String notes = sanitizeNotes(values.notes());
        if (!notes.isBlank()) {
            root.putString("notes", notes);
        }
        if (root.contains("schema") && root.size() > 1) {
            parent.put(ROOT_TAG, root);
        } else {
            parent.remove(ROOT_TAG);
        }
    }

    public static String normalizeMode(String mode) {
        if (mode == null) {
            return null;
        }
        return switch (mode.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "off" -> "off";
            case "defend" -> "defend";
            default -> null;
        };
    }

    /** Single line, no angle brackets, bounded by code points; notes are prompt-only. */
    public static String sanitizeNotes(String notes) {
        if (notes == null) {
            return "";
        }
        String normalized = notes.replace('\r', ' ').replace('\n', ' ')
                .replace('<', '[').replace('>', ']').trim();
        int count = normalized.codePointCount(0, normalized.length());
        if (count <= MAX_NOTES_CODE_POINTS) {
            return normalized;
        }
        return normalized.substring(0, normalized.offsetByCodePoints(0, MAX_NOTES_CODE_POINTS)).trim();
    }
}
