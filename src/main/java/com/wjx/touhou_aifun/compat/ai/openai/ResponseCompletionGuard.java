package com.wjx.touhou_aifun.compat.ai.openai;

import com.google.gson.JsonObject;

/** Never dispatch incomplete tool arguments or expose encoded reasoning as an empty-answer error. */
final class ResponseCompletionGuard {
    private ResponseCompletionGuard() {}

    static String failure(String reason, boolean hasAnswer) {
        if ("length".equals(reason) || "max_tokens".equals(reason) || "max_output_tokens".equals(reason)) {
            return "模型输出预算已耗尽（包含思考和正文），响应被截断。请提高 llm.outputBudgetTokens 或降低思考强度；模型自身输出上限仍然有效。";
        }
        if ("incomplete".equals(reason) || "failed".equals(reason) || "cancelled".equals(reason)) {
            return "模型响应未完成，请重试；未完成的工具调用没有执行。";
        }
        if ("content_filter".equals(reason) || "refusal".equals(reason)) {
            return "模型拒绝了此次请求或响应被服务商过滤。";
        }
        return hasAnswer ? null : "模型没有返回正文或工具调用（可能仅返回了思考）。请检查输出预算和模型设置。";
    }

    static String reason(JsonObject root) {
        if (root.has("stop_reason") && root.get("stop_reason").isJsonPrimitive())
            return root.get("stop_reason").getAsString();
        if (root.has("choices") && root.get("choices").isJsonArray() && !root.getAsJsonArray("choices").isEmpty()) {
            JsonObject choice = root.getAsJsonArray("choices").get(0).getAsJsonObject();
            if (choice.has("finish_reason") && choice.get("finish_reason").isJsonPrimitive())
                return choice.get("finish_reason").getAsString();
        }
        return "";
    }
}
