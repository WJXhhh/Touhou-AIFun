package com.wjx.touhou_aifun.chat.context;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/** Small JSON-in-NBT codec; keeping this payload independent avoids changing TLM's codecs. */
public final class MemoryStateCodec {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private MemoryStateCodec() {
    }

    public static String encode(MaidMemoryState state) {
        return GSON.toJson(state == null ? new MaidMemoryState() : state);
    }

    public static MaidMemoryState decode(String value) {
        if (value == null || value.isBlank()) {
            return new MaidMemoryState();
        }
        try {
            MaidMemoryState state = GSON.fromJson(value, MaidMemoryState.class);
            return state == null || state.schemaVersion() != MaidMemoryState.SCHEMA_VERSION
                    || state.turns() == null || state.facts() == null || state.openLoops() == null
                    || state.episodes() == null || state.tokenCalibrations() == null
                    ? new MaidMemoryState() : state;
        } catch (RuntimeException e) {
            return new MaidMemoryState();
        }
    }
}
