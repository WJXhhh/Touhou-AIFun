package com.wjx.touhou_aifun.compat.ai.openai;

import com.github.tartaricacid.touhoulittlemaid.TouhouLittleMaid;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.ErrorCode;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.Role;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.LLMOpenAISite;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.response.ToolCall;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.common.net.HttpHeaders;
import com.google.common.net.MediaType;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wjx.touhou_aifun.chat.ChatFlowManager;
import com.wjx.touhou_aifun.chat.agent.AgentTelemetry;
import com.wjx.touhou_aifun.chat.context.AIFunMemoryManager;
import com.wjx.touhou_aifun.chat.context.ContextBudgetPlanner;
import com.wjx.touhou_aifun.chat.context.ContextTokenEstimator;
import com.wjx.touhou_aifun.compat.ai.EmotionControlPrompts;
import com.wjx.touhou_aifun.compat.ai.openai.response.ReasoningOpenAIChatCompletionResponse;
import com.wjx.touhou_aifun.config.TouhouAIFunConfig;
import org.apache.commons.lang3.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static com.github.tartaricacid.touhoulittlemaid.ai.service.Client.GSON;

/**
 * OpenAI Responses wire adapter used by OpenCode Go models such as Muse Spark. It converts the
 * provider response into the addon's existing OpenAI-shaped finalizer so tools, history, token
 * accounting, language contracts and TTS all retain the same behavior. Responses streaming is
 * deliberately disabled for now; the completed response still participates in the full agent loop.
 */
public final class OpenAIResponsesCompatLLMClient extends ReasoningCompatOpenAIClient {
    private final HttpClient responsesHttpClient;
    private final LLMOpenAISite responsesSite;

    public OpenAIResponsesCompatLLMClient(HttpClient httpClient, LLMOpenAISite site) {
        super(httpClient, site);
        this.responsesHttpClient = httpClient;
        this.responsesSite = site;
    }

    @Override
    public void chat(LLMCallback callback) {
        if (!com.wjx.touhou_aifun.chat.agent.AgentContext.prepare(callback)) return;
        EntityMaid maid = callback.getMaid();
        if (com.wjx.touhou_aifun.chat.agent.AgentExecution.managed(callback)
                && ChatFlowManager.isSuperseded(maid.getUUID(), callback)) return;

        ToolCatalogSnapshot snapshot = ToolContextSelector.snapshot(maid, callback);
        if (com.wjx.touhou_aifun.chat.agent.AgentExecution.managed(callback)) {
            var planned = ContextBudgetPlanner.trim(callback.getMessages(),
                    TouhouAIFunConfig.CONTEXT_INPUT_BUDGET_TOKENS.get(),
                    snapshot.schemaBudget(ChatFlowManager.requestedToolIds(maid.getUUID(), callback))
                            + com.wjx.touhou_aifun.vision.MultimodalTurnContext.inputReserve(callback),
                    AIFunMemoryManager.calibratedEstimate(maid.getAiChatManager(), callback.getMessages())
                            / (double) Math.max(1, ContextTokenEstimator.estimate(callback.getMessages())));
            callback.getMessages().clear();
            callback.getMessages().addAll(planned);
        }

        var preparation=AgentTelemetry.root(callback,"model_prepare");
        JsonObject body = buildRequest(callback, snapshot);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(this.responsesSite.url()))
                .timeout(com.wjx.touhou_aifun.config.LLMRuntimeBudget.timeout())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.JSON_UTF_8.toString())
                .header(HttpHeaders.ACCEPT, MediaType.JSON_UTF_8.toString())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + this.responsesSite.secretKey())
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)));
        this.responsesSite.headers().forEach(builder::header);
        HttpRequest request = builder.build();
        preparation.finish("ok",0);

        if (TouhouLittleMaid.DEBUG) TouhouLittleMaid.LOGGER.info(GSON.toJson(com.wjx.touhou_aifun.vision.MultimodalContent.redacted(body)));
        var timing=AgentTelemetry.model(callback);
        CompletableFuture<HttpResponse<String>> future = this.responsesHttpClient.sendAsync(
                request, HttpResponse.BodyHandlers.ofString());
        ChatFlowManager.setModelInFlight(maid.getUUID(), callback, future);
        future.orTimeout(request.timeout().orElseThrow().toSeconds() + 5, TimeUnit.SECONDS)
                .whenComplete((response, throwable) -> {
                    timing.headers(throwable,false);
                    try { completeResponses(callback, response, throwable, request); }
                    finally { timing.finish(ChatFlowManager.isSuperseded(maid.getUUID(),callback)?"cancelled"
                            : throwable!=null || response==null || !isSuccessful(response)?"error":"body_received"); }
                });
    }

    private JsonObject buildRequest(LLMCallback callback, ToolCatalogSnapshot snapshot) {
        EntityMaid maid = callback.getMaid();
        JsonObject body = new JsonObject();
        body.addProperty("model", maid.getAiChatManager().getLLMModel());
        body.addProperty("max_output_tokens", com.wjx.touhou_aifun.config.LLMRuntimeBudget.outputTokens());
        body.addProperty("store", false);
        body.addProperty("stream", false);

        JsonArray input = new JsonArray();
        for (LLMMessage message : callback.getMessages()) appendInput(input, message);
        if (com.wjx.touhou_aifun.chat.agent.AgentExecution.managed(callback)) {
            appendMessage(input, "system", snapshot.directory());
            String emotionChange = EmotionControlPrompts.changeNotice(maid);
            if (emotionChange != null) appendMessage(input, "system", emotionChange);
            String reminder = EmotionControlPrompts.turnReminder(maid);
            if (reminder != null) appendMessage(input, "system", reminder);
        }
        com.wjx.touhou_aifun.vision.MultimodalTurnContext.append(callback,
                com.wjx.touhou_aifun.vision.UnifiedModelCatalog.VisualProtocol.RESPONSES, input);
        body.add("input", input);

        if (callback.needAddTools) {
            JsonArray tools = new JsonArray();
            for (ToolCatalogSnapshot.Entry entry : snapshot.selected(
                    ChatFlowManager.requestedToolIds(maid.getUUID(), callback))) {
                JsonObject source = entry.anthropicTool();
                JsonObject tool = new JsonObject();
                tool.addProperty("type", "function");
                tool.addProperty("name", source.get("name").getAsString());
                tool.addProperty("description", source.get("description").getAsString());
                tool.add("parameters", source.get("input_schema").deepCopy());
                tools.add(tool);
            }
            body.add("tools", tools);
        }
        return body;
    }

    static void appendInput(JsonArray input, LLMMessage message) {
        if (message.role() == Role.TOOL) {
            if (StringUtils.isBlank(message.toolCallId())) return;
            JsonObject result = new JsonObject();
            result.addProperty("type", "function_call_output");
            result.addProperty("call_id", message.toolCallId());
            result.addProperty("output", StringUtils.defaultString(message.message()));
            input.add(result);
            return;
        }
        if (message.role() == Role.ASSISTANT && message.toolCalls() != null && !message.toolCalls().isEmpty()) {
            ReasoningContentCodec.DecodedContent decoded = ReasoningContentCodec.decode(message.message());
            if (StringUtils.isNotBlank(decoded.content())) appendMessage(input, "assistant", decoded.content());
            for (ToolCall call : message.toolCalls()) {
                if (call == null || call.getFunction() == null) continue;
                JsonObject item = new JsonObject();
                item.addProperty("type", "function_call");
                item.addProperty("call_id", call.getId());
                item.addProperty("name", call.getFunction().getName());
                item.addProperty("arguments", StringUtils.defaultIfBlank(
                        call.getFunction().getArguments(), "{}"));
                input.add(item);
            }
            return;
        }
        String role = message.role() == Role.SYSTEM ? "system"
                : message.role() == Role.ASSISTANT ? "assistant" : "user";
        if (StringUtils.isNotBlank(message.message())) appendMessage(input, role, message.message());
    }

    private static void appendMessage(JsonArray input, String role, String content) {
        if (StringUtils.isBlank(content)) return;
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.addProperty("content", content);
        input.add(message);
    }

    private void completeResponses(LLMCallback callback, HttpResponse<String> response,
                                   Throwable throwable, HttpRequest request) {
        try {
            if (throwable != null) {
                callback.onFailure(request, throwable, ErrorCode.REQUEST_SENDING_ERROR);
                return;
            }
            if (this.shouldStopChat(callback.getMaid())) return;
            if (com.wjx.touhou_aifun.vision.MultimodalTurnContext.tryFallback(callback, this, response.statusCode(), response.body())) return;
            if (!this.isSuccessful(response)) {
                callback.onFailure(request, new Throwable("HTTP Error Code: %d, Response: %s"
                        .formatted(response.statusCode(), response.body())), ErrorCode.REQUEST_RECEIVED_ERROR);
                return;
            }
            this.processChatResponse(callback, adaptResponse(response.body()), request, null);
        } catch (RuntimeException e) {
            TouhouLittleMaid.LOGGER.error("Failed to process Responses API reply from {}", request.uri(), e);
            callback.onFailure(request, e, ErrorCode.JSON_DECODE_ERROR);
        }
    }

    static ReasoningOpenAIChatCompletionResponse adaptResponse(String json) {
        JsonObject source = JsonParser.parseString(json).getAsJsonObject();
        JsonArray output = source.has("output") && source.get("output").isJsonArray()
                ? source.getAsJsonArray("output") : new JsonArray();
        StringBuilder text = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        JsonArray toolCalls = new JsonArray();

        for (JsonElement element : output) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            String type = scalar(item, "type");
            if ("message".equals(type)) {
                JsonArray content = item.has("content") && item.get("content").isJsonArray()
                        ? item.getAsJsonArray("content") : new JsonArray();
                for (JsonElement blockElement : content) {
                    if (!blockElement.isJsonObject()) continue;
                    JsonObject block = blockElement.getAsJsonObject();
                    if ("output_text".equals(scalar(block, "type"))) append(text, scalar(block, "text"));
                }
            } else if ("reasoning".equals(type)) {
                JsonArray summary = item.has("summary") && item.get("summary").isJsonArray()
                        ? item.getAsJsonArray("summary") : new JsonArray();
                for (JsonElement part : summary) {
                    if (part.isJsonObject()) append(reasoning, scalar(part.getAsJsonObject(), "text"));
                }
            } else if ("function_call".equals(type)) {
                JsonObject call = new JsonObject();
                call.addProperty("id", StringUtils.defaultIfBlank(scalar(item, "call_id"), scalar(item, "id")));
                call.addProperty("type", "function");
                JsonObject function = new JsonObject();
                function.addProperty("name", scalar(item, "name"));
                function.addProperty("arguments", StringUtils.defaultIfBlank(scalar(item, "arguments"), "{}"));
                call.add("function", function);
                toolCalls.add(call);
            }
        }
        if (text.length() == 0 && source.has("output_text") && source.get("output_text").isJsonPrimitive()) {
            text.append(source.get("output_text").getAsString());
        }

        JsonObject message = new JsonObject();
        message.addProperty("role", "assistant");
        message.addProperty("content", text.toString());
        if (reasoning.length() > 0) message.addProperty("reasoning_content", reasoning.toString());
        if (!toolCalls.isEmpty()) message.add("tool_calls", toolCalls);
        JsonObject choice = new JsonObject();
        choice.addProperty("index", 0);
        choice.add("message", message);
        String status = scalar(source, "status");
        if ("incomplete".equals(status) && source.has("incomplete_details")
                && source.get("incomplete_details").isJsonObject()) {
            String reason = scalar(source.getAsJsonObject("incomplete_details"), "reason");
            choice.addProperty("finish_reason", "max_output_tokens".equals(reason) ? "length" : "incomplete");
        } else if ("incomplete".equals(status) || "failed".equals(status) || "cancelled".equals(status)) {
            choice.addProperty("finish_reason", status);
        }
        JsonArray choices = new JsonArray();
        choices.add(choice);

        JsonObject adapted = new JsonObject();
        adapted.addProperty("id", scalar(source, "id"));
        adapted.add("choices", choices);
        if (source.has("usage") && source.get("usage").isJsonObject()) {
            JsonObject original = source.getAsJsonObject("usage");
            adapted.add("usage", com.wjx.touhou_aifun.compat.ai.openai.response.TokenUsage.responses(original));
        }
        return GSON.fromJson(adapted, ReasoningOpenAIChatCompletionResponse.class);
    }

    private static void append(StringBuilder target, String value) {
        if (StringUtils.isBlank(value)) return;
        if (target.length() > 0) target.append('\n');
        target.append(value);
    }

    private static String scalar(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    private static int integer(JsonObject object, String key) {
        return integer(object, key, 0);
    }

    private static int integer(JsonObject object, String key, int fallback) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsInt() : fallback;
    }
}
