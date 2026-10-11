package com.wjx.touhou_aifun.chat.agent.decision;

import com.google.gson.JsonObject;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** A decision is a typed probability evaluation, never a generated chat/tool-call message. */
public interface DecisionProvider {
    record Capabilities(int maxQuestions, int maxChoices, int maxScoreLevels, boolean images) { }
    record Result(String model, JsonObject answers, long inputTokens, long outputTokens, String requestId) { }
    Capabilities capabilities();
    CompletableFuture<Result> evaluate(JsonObject state, JsonObject questions,
                                       Consumer<CompletableFuture<?>> ownRawRequest);
}
