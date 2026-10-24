package com.wjx.touhou_aifun.chat.agent.build;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonObject;

import java.util.concurrent.CompletableFuture;

/**
 * Decides what to do with a block step whose live state does not exactly match the design.
 * The lead wires the concrete implementation; a missing judge means "ask the executor LLM".
 */
public interface BuildStateJudge {
    CompletableFuture<JsonObject> judge(EntityMaid maid, Object callback, BuildDesign design,
                                        BuildStep step, JsonObject actual, int attempts);

    /** Verdict meaning "no confident deterministic answer; the execution LLM must decide". */
    static JsonObject needsLlm(String detail) {
        JsonObject out = new JsonObject();
        out.addProperty("choice", "llm");
        out.addProperty("confident", false);
        out.addProperty("reason", detail == null ? "" : detail);
        return out;
    }
}
