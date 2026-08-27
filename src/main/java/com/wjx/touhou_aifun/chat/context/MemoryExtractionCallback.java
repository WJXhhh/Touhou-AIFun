package com.wjx.touhou_aifun.chat.context;

import com.github.tartaricacid.touhoulittlemaid.TouhouLittleMaid;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.response.ResponseChat;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.commons.lang3.StringUtils;

import javax.annotation.Nullable;
import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.List;

/** Side callback for asynchronous structured memory extraction. */
public final class MemoryExtractionCallback extends LLMCallback {
    private final MaidAIChatManager manager;
    private final List<Long> batchIds;

    public MemoryExtractionCallback(MaidAIChatManager manager, List<LLMMessage> messages,
                                    List<Long> batchIds) {
        super(manager, messages, true);
        this.manager = manager;
        this.batchIds = List.copyOf(batchIds);
        this.needAddTools = false;
    }

    @Override
    public boolean shouldCacheTokenUsage() {
        return false;
    }

    @Override
    public void onSuccess(ResponseChat response) {
        try {
            MemoryExtractionDelta delta = parseDelta(response.getChatText());
            if (delta == null) throw new IllegalArgumentException("empty or invalid memory delta");
            this.runOnServerThread(() -> {
                boolean applied = AIFunMemoryManager.applyExtraction(manager, batchIds, delta);
                if (!applied && AIFunMemoryManager.extractionBatchStillPresent(manager, batchIds)) {
                    AIFunMemoryManager.extractionFailed(manager);
                }
                AIFunMemoryManager.finishExtraction(manager.getMaid().getUUID());
            });
        } catch (RuntimeException e) {
            TouhouLittleMaid.LOGGER.warn("Ignoring invalid AIFun memory extraction response", e);
            this.runOnServerThread(() -> {
                try {
                    if (AIFunMemoryManager.extractionBatchStillPresent(manager, batchIds)) {
                        AIFunMemoryManager.extractionFailed(manager);
                    }
                } finally {
                    AIFunMemoryManager.finishExtraction(manager.getMaid().getUUID());
                }
            });
        }
    }

    @Override
    public void onFailure(@Nullable HttpRequest request, Throwable throwable, int errorCode) {
        TouhouLittleMaid.LOGGER.warn("AIFun background memory extraction failed ({}): {}", errorCode,
                throwable == null ? "unknown" : throwable.getMessage());
        this.runOnServerThread(() -> {
            try {
                if (AIFunMemoryManager.extractionBatchStillPresent(manager, batchIds)) {
                    AIFunMemoryManager.extractionFailed(manager);
                }
            } finally {
                AIFunMemoryManager.finishExtraction(manager.getMaid().getUUID());
            }
        });
    }

    static MemoryExtractionDelta parseDelta(String content) {
        if (StringUtils.isBlank(content)) return null;
        String json = content.trim();
        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(json.substring(start, end + 1));
        } catch (RuntimeException e) {
            return null;
        }
        if (!parsed.isJsonObject()) return null;
        JsonObject root = parsed.getAsJsonObject();
        if (!isArray(root, "facts_upsert") || !isArray(root, "facts_delete")
                || !isArray(root, "open_loops_upsert") || !isArray(root, "open_loops_close")
                || !root.has("episode") || !root.get("episode").isJsonObject()) return null;
        JsonObject episodeObject = root.getAsJsonObject("episode");
        if (!episodeObject.has("summary") || !episodeObject.get("summary").isJsonPrimitive()
                || !episodeObject.getAsJsonPrimitive("summary").isString()
                || !isArray(episodeObject, "keywords") || !episodeObject.has("importance")
                || !episodeObject.get("importance").isJsonPrimitive()
                || !episodeObject.getAsJsonPrimitive("importance").isNumber()) return null;
        MemoryExtractionDelta delta = new MemoryExtractionDelta();
        if (!readFacts(root.getAsJsonArray("facts_upsert"), delta)
                || !readStrings(root.getAsJsonArray("facts_delete"), delta.factDeletes())
                || !readLoops(root.getAsJsonArray("open_loops_upsert"), delta)
                || !readStrings(root.getAsJsonArray("open_loops_close"), delta.loopCloses())) return null;
        JsonObject episode = root.getAsJsonObject("episode");
        delta.setEpisode(episode.has("summary") ? episode.get("summary").getAsString() : "",
                readStringList(episode.getAsJsonArray("keywords")),
                episode.has("importance") ? episode.get("importance").getAsInt() : 0);
        return delta.hasChanges() ? delta : null;
    }

    private static boolean readFacts(JsonArray array, MemoryExtractionDelta delta) {
        if (array == null) return false;
        for (JsonElement element : array) {
            if (!element.isJsonObject()) return false;
            JsonObject o = element.getAsJsonObject();
            if (!validOptionalString(o, "id") || !validString(o, "kind") || !validString(o, "text")
                    || !validNumber(o, "importance")) return false;
            delta.factUpserts().add(new MemoryExtractionDelta.FactChange(
                    string(o, "id"), string(o, "kind"), string(o, "text"), integer(o, "importance")));
        }
        return true;
    }

    private static boolean readLoops(JsonArray array, MemoryExtractionDelta delta) {
        if (array == null) return false;
        for (JsonElement element : array) {
            if (!element.isJsonObject()) return false;
            JsonObject o = element.getAsJsonObject();
            if (!validOptionalString(o, "id") || !validString(o, "text")
                    || !validNumber(o, "importance")) return false;
            delta.loopUpserts().add(new MemoryExtractionDelta.LoopChange(
                    string(o, "id"), string(o, "text"), integer(o, "importance")));
        }
        return true;
    }

    private static boolean readStrings(JsonArray array, List<String> target) {
        if (array == null) return false;
        for (JsonElement element : array) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) return false;
            target.add(element.getAsString());
        }
        return true;
    }

    private static List<String> readStringList(JsonArray array) {
        List<String> result = new ArrayList<>();
        if (!readStrings(array, result)) throw new IllegalArgumentException("array contains a non-string");
        return result;
    }

    private static boolean isArray(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonArray();
    }

    private static boolean validString(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive()
                && object.getAsJsonPrimitive(key).isString();
    }

    private static boolean validOptionalString(JsonObject object, String key) {
        return !object.has(key) || validString(object, key);
    }

    private static boolean validNumber(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive()
                && object.getAsJsonPrimitive(key).isNumber();
    }

    private static String string(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : "";
    }

    private static int integer(JsonObject object, String key) {
        try {
            return object.has(key) ? object.get(key).getAsInt() : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }
}
