package com.wjx.touhou_aifun.vision.scan;

import com.mojang.serialization.Codec;

import java.util.Locale;

/** A cubemap face, or all six faces. */
public enum ScanDirection {
    ALL,
    FRONT,
    RIGHT,
    BACK,
    LEFT,
    UP,
    DOWN;

    public static final Codec<ScanDirection> CODEC = Codec.STRING.xmap(
            value -> from(value, ALL),
            value -> value.name().toLowerCase(Locale.ROOT)
    );

    public static ScanDirection from(String value, ScanDirection fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }
}
