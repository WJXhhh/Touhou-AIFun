package com.wjx.touhou_aifun.chat.context;

import com.github.tartaricacid.touhoulittlemaid.TouhouLittleMaid;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Gzip JSON stored in a ByteArrayTag; keeping this payload independent avoids changing TLM codecs. */
public final class MemoryStateCodec {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final int MAX_DECOMPRESSED_BYTES = 8 * 1024 * 1024;

    private MemoryStateCodec() {
    }

    public enum DecodeStatus {
        VALID,
        EMPTY,
        CORRUPT,
        UNSUPPORTED_SCHEMA
    }

    public record DecodeResult(MaidMemoryState state, DecodeStatus status) {
        public boolean valid() {
            return status == DecodeStatus.VALID || status == DecodeStatus.EMPTY;
        }
    }

    public static String encode(MaidMemoryState state) {
        return GSON.toJson(state == null ? new MaidMemoryState() : state);
    }

    /** Binary NBT representation avoids StringTag's modified-UTF length ceiling. */
    public static byte[] encodeBytes(MaidMemoryState state) {
        byte[] json = encode(state).getBytes(StandardCharsets.UTF_8);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write(json);
            gzip.finish();
            return output.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode AIFun memory", e);
        }
    }

    public static MaidMemoryState decode(String value) {
        return decodeResult(value).state();
    }

    public static DecodeResult decodeResult(String value) {
        if (value == null || value.isBlank()) {
            return new DecodeResult(new MaidMemoryState(), DecodeStatus.EMPTY);
        }
        try {
            JsonElement parsed = JsonParser.parseString(value);
            if (!parsed.isJsonObject()) return corrupt("memory JSON root is not an object", null);
            JsonObject object = parsed.getAsJsonObject();
            if (!object.has("schemaVersion") || !object.get("schemaVersion").isJsonPrimitive()
                    || !object.getAsJsonPrimitive("schemaVersion").isNumber()) {
                return corrupt("memory JSON has no numeric schemaVersion", null);
            }
            int schemaVersion = object.get("schemaVersion").getAsInt();
            if (schemaVersion != MaidMemoryState.SCHEMA_VERSION) {
                TouhouLittleMaid.LOGGER.warn("AIFun memory schema {} is newer/unsupported; preserving its raw payload",
                        schemaVersion);
                return new DecodeResult(new MaidMemoryState(), DecodeStatus.UNSUPPORTED_SCHEMA);
            }
            MaidMemoryState state = GSON.fromJson(value, MaidMemoryState.class);
            if (state == null || state.schemaVersion() != MaidMemoryState.SCHEMA_VERSION
                    || state.turns() == null || state.facts() == null || state.openLoops() == null
                    || state.episodes() == null || state.tokenCalibrations() == null) {
                return corrupt("memory JSON is missing required collections", null);
            }
            return new DecodeResult(state, DecodeStatus.VALID);
        } catch (RuntimeException e) {
            return corrupt("failed to decode AIFun memory JSON", e);
        }
    }

    public static MaidMemoryState decodeBytes(byte[] value) {
        return decodeBytesResult(value).state();
    }

    public static DecodeResult decodeBytesResult(byte[] value) {
        if (value == null || value.length == 0) {
            return new DecodeResult(new MaidMemoryState(), DecodeStatus.EMPTY);
        }
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(value))) {
            byte[] decoded = gzip.readNBytes(MAX_DECOMPRESSED_BYTES + 1);
            if (decoded.length > MAX_DECOMPRESSED_BYTES) {
                throw new IOException("decompressed AIFun memory exceeds safety limit");
            }
            return decodeResult(new String(decoded, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            return corrupt("failed to decode compressed AIFun memory", e);
        }
    }

    private static DecodeResult corrupt(String message, Throwable error) {
        if (error == null) TouhouLittleMaid.LOGGER.warn("{}; preserving raw payload for recovery", message);
        else TouhouLittleMaid.LOGGER.warn(message + "; preserving raw payload for recovery", error);
        return new DecodeResult(new MaidMemoryState(), DecodeStatus.CORRUPT);
    }
}
