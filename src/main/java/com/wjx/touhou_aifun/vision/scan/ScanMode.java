package com.wjx.touhou_aifun.vision.scan;

import com.mojang.serialization.Codec;

import java.util.Locale;

/** Which parts of the local, server-side observation are requested. */
public enum ScanMode {
    NONE,
    BLOCKS,
    ENTITIES,
    BOTH;

    public static final Codec<ScanMode> CODEC = Codec.STRING.xmap(
            value -> from(value, BOTH),
            value -> value.name().toLowerCase(Locale.ROOT)
    );

    public static ScanMode from(String value, ScanMode fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    public boolean includesBlocks() {
        return this == BLOCKS || this == BOTH;
    }

    public boolean includesEntities() {
        return this == ENTITIES || this == BOTH;
    }
}
