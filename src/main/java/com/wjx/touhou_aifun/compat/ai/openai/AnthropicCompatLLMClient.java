package com.wjx.touhou_aifun.compat.ai.openai;

import com.github.tartaricacid.touhoulittlemaid.TouhouLittleMaid;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.response.ResponseChat;
import com.github.tartaricacid.touhoulittlemaid.ai.service.ErrorCode;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMClient;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.Role;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.LLMOpenAISite;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.response.Message;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.response.ToolCall;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.response.FunctionToolCall;
import com.github.tartaricacid.touhoulittlemaid.capability.ChatTokensCapabilityProvider;
import com.github.tartaricacid.touhoulittlemaid.config.subconfig.AIConfig;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.common.net.HttpHeaders;
import com.google.common.net.MediaType;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import net.minecraft.server.level.ServerPlayer;
import org.apache.commons.lang3.StringUtils;
import com.wjx.touhou_aifun.chat.ChatFlowManager;
import com.wjx.touhou_aifun.chat.agent.AgentTelemetry;
import com.wjx.touhou_aifun.chat.context.AIFunMemoryManager;
import com.wjx.touhou_aifun.chat.context.ContextBudgetPlanner;
import com.wjx.touhou_aifun.compat.ai.EmotionControlPrompts;
import com.wjx.touhou_aifun.compat.ai.openai.response.StreamAccumulator;
import com.wjx.touhou_aifun.compat.ai.openai.response.StreamChunk;
import com.wjx.touhou_aifun.compat.ai.openai.response.TokenUsage;
import com.wjx.touhou_aifun.config.TouhouAIFunConfig;

import javax.annotation.Nullable;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * LLM client speaking the <a href="https://platform.claude.com/docs/en/api/messages">Anthropic
 * Messages protocol</a> ({@code POST /v1/messages}, {@code x-api-key} auth). It is the wire
 * client behind {@code AnthropicLLMSite}, whose default endpoint is DeepSeek's
 * Anthropic-compatible API ({@code https://api.deepseek.com/anthropic}).
 *
 * <p>Two protocol differences from the OpenAI chat-completions path are handled here:
 * <ul>
 *   <li>Messages are content <em>blocks</em>: {@code system} is a top-level field, assistant
 *       tool calls become {@code tool_use} blocks and tool results {@code tool_result} blocks
 *       inside {@code user} messages.</li>
 * </ul>
 *
 * <p>Text replies flow through the same finalization machinery as the OpenAI clients
 * ({@link StreamingTtsReply}, {@link StreamingDisplay}, the {@code ---} two-segment contract,
 * emotion control), and function tool calls drive the base mod's agent loop via
 * {@link LLMCallback#onFunctionCall}.
 */
public class AnthropicCompatLLMClient implements LLMClient {
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    protected final HttpClient httpClient;
    protected final LLMOpenAISite site;


    public AnthropicCompatLLMClient(HttpClient httpClient, LLMOpenAISite site) {
        this.httpClient = httpClient;
        this.site = site;
    }

    @Override
    public void chat(LLMCallback callback) {
        if (!com.wjx.touhou_aifun.chat.agent.AgentContext.prepare(callback)) return;
        EntityMaid maid = callback.getMaid();
        if (com.wjx.touhou_aifun.chat.agent.AgentExecution.managed(callback)
                && ChatFlowManager.isSuperseded(maid.getUUID(), callback)) {
            return;
        }
        if (com.wjx.touhou_aifun.chat.agent.AgentExecution.managed(callback)) {
            ToolCatalogSnapshot snapshot = ToolContextSelector.snapshot(maid, callback);
            double factor = AIFunMemoryManager.calibratedEstimate(maid.getAiChatManager(), callback.getMessages())
                    / (double) Math.max(1, com.wjx.touhou_aifun.chat.context.ContextTokenEstimator.estimate(callback.getMessages()));
            List<LLMMessage> planned = ContextBudgetPlanner.trim(callback.getMessages(),
                    TouhouAIFunConfig.CONTEXT_INPUT_BUDGET_TOKENS.get(),
                    snapshot.schemaBudget(ChatFlowManager.requestedToolIds(maid.getUUID(), callback))
                            + com.wjx.touhou_aifun.vision.MultimodalTurnContext.inputReserve(callback), factor);
            callback.getMessages().clear();
            callback.getMessages().addAll(planned);
        }
        var preparation=AgentTelemetry.root(callback,"model_prepare");
        JsonObject body = this.buildRequestBody(callback);

        if (TouhouLittleMaid.DEBUG && com.wjx.touhou_aifun.chat.agent.AgentExecution.context(callback).task()==null) {
            TouhouLittleMaid.LOGGER.info(GSON.toJson(com.wjx.touhou_aifun.vision.MultimodalContent.redacted(body)));
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .header(HttpHeaders.CONTENT_TYPE, MediaType.JSON_UTF_8.toString())
                .header("x-api-key", this.site.secretKey())
                .header("anthropic-version", ANTHROPIC_VERSION)
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                .timeout(com.wjx.touhou_aifun.config.LLMRuntimeBudget.timeout())
                .uri(URI.create(this.endpoint()));
        this.site.headers().forEach(builder::header);
        HttpRequest httpRequest = builder.build();
        preparation.finish("ok",0);

        if (TouhouAIFunConfig.LLM_STREAMING.get()) {
            this.chatStreaming(callback, httpRequest);
            return;
        }

        // Keep the raw sendAsync future so a newer request can cancel it (cancelling this future
        // aborts the underlying HTTP exchange, stopping the model from generating further).
        var timing=AgentTelemetry.model(callback);
        CompletableFuture<HttpResponse<String>> future =
                this.httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString());
        ChatFlowManager.setModelInFlight(maid.getUUID(), callback, future);
        future.orTimeout(httpRequest.timeout().orElseThrow().toSeconds() + 5, TimeUnit.SECONDS)
                .whenComplete((response, throwable) -> {
                    timing.headers(throwable,false);
                    try { this.complete(callback, response, throwable, httpRequest); }
                    finally { timing.finish(ChatFlowManager.isSuperseded(maid.getUUID(),callback)?"cancelled"
                            : throwable!=null || response==null || !isSuccessful(response)?"error":"body_received"); }
                });
    }

    /** The site URL may be {@code https://api.deepseek.com/anthropic} or already include {@code /v1/messages}. */
    private String endpoint() {
        String base = this.site.url().trim();
        if (base.endsWith("/v1/messages")) {
            return base;
        }
        return base.replaceAll("/+$", "") + "/v1/messages";
    }

    /**
     * Translates the base mod's {@link LLMMessage} history into an Anthropic Messages request
     * body: {@code system} top-level field, {@code tool_use} / {@code tool_result} blocks,
     * function tools from the addon/base registry. Provider-specific server tools are deliberately
     * absent; web search is an ordinary addon tool with its own backend seam.
     */
    protected JsonObject buildRequestBody(LLMCallback callback) {
        EntityMaid maid = callback.getMaid();
        String model = maid.getAiChatManager().getLLMModel();

        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("max_tokens", com.wjx.touhou_aifun.config.LLMRuntimeBudget.outputTokens());

        // --- system ---
        boolean managed = com.wjx.touhou_aifun.chat.agent.AgentExecution.managed(callback);
        var layout = AnthropicPromptLayout.split(callback.getMessages());
        List<String> systemParts = new ArrayList<>(layout.system());
        List<String> updates = new ArrayList<>(layout.updates());
        if (!managed) {
            systemParts.clear(); updates.clear();
            for (LLMMessage message : callback.getMessages())
                if (message.role() == Role.SYSTEM && StringUtils.isNotBlank(message.message()))
                    systemParts.add(message.message().trim());
        }
        // Keep the emotion reminders adjacent to the model's next response, exactly like the OpenAI path.
        if (managed) {
            systemParts.add(ToolContextSelector.snapshot(maid, callback).directory());
            updates.add(ToolContextSelector.loadedNotice(maid, callback));
            String emotionChange = EmotionControlPrompts.changeNotice(maid);
            if (emotionChange != null) {
                updates.add(emotionChange);
            }
            String reminder = EmotionControlPrompts.turnReminder(maid);
            if (reminder != null) {
                updates.add(reminder);
            }
        }
        if (!systemParts.isEmpty()) {
            body.addProperty("system", String.join("\n\n", systemParts));
        }

        // --- messages ---
        JsonArray messages = new JsonArray();
        // Consecutive tool messages become one user message holding several tool_result blocks,
        // which is how the Anthropic protocol expects multiple results after one tool_use turn.
        List<JsonObject> pendingToolResults = new ArrayList<>();
        for (LLMMessage message : callback.getMessages()) {
            if (message.role() == Role.SYSTEM) {
                continue;
            }
            if (message.role() == Role.TOOL) {
                // A tool result without its tool_use id cannot be expressed in the Anthropic
                // protocol; drop the orphaned result defensively instead of crashing.
                if (StringUtils.isBlank(message.toolCallId())) {
                    continue;
                }
                JsonObject block = new JsonObject();
                block.addProperty("type", "tool_result");
                block.addProperty("tool_use_id", message.toolCallId());
                block.addProperty("content", StringUtils.defaultString(message.message()));
                pendingToolResults.add(block);
                continue;
            }
            this.flushToolResults(messages, pendingToolResults);

            if (message.role() == Role.USER) {
                if (StringUtils.isBlank(message.message())) {
                    continue;
                }
                JsonObject textBlock = new JsonObject();
                textBlock.addProperty("type", "text");
                textBlock.addProperty("text", message.message());
                JsonObject user = new JsonObject();
                user.addProperty("role", "user");
                JsonArray content = new JsonArray();
                content.add(textBlock);
                user.add("content", content);
                messages.add(user);
            } else if (message.role() == Role.ASSISTANT) {
                ReasoningContentCodec.DecodedContent decoded = ReasoningContentCodec.decode(message.message());
                String content = ReasoningOpenAIResponseChat.normalizeRepeatedParts(decoded.content());
                JsonArray blocks = new JsonArray();
                if (StringUtils.isNotBlank(decoded.reasoningContent())) {
                    JsonObject thinking = new JsonObject();
                    thinking.addProperty("type", "thinking");
                    thinking.addProperty("thinking", decoded.reasoningContent());
                    blocks.add(thinking);
                }
                if (StringUtils.isNotBlank(content)) {
                    JsonObject textBlock = new JsonObject();
                    textBlock.addProperty("type", "text");
                    textBlock.addProperty("text", content);
                    blocks.add(textBlock);
                }
                if (message.toolCalls() != null) {
                    for (ToolCall toolCall : message.toolCalls()) {
                        FunctionToolCall function = toolCall.getFunction();
                        if (function == null || StringUtils.isBlank(function.getName())) {
                            continue;
                        }
                        JsonObject toolUse = new JsonObject();
                        toolUse.addProperty("type", "tool_use");
                        toolUse.addProperty("id", toolCall.getId());
                        toolUse.addProperty("name", function.getName());
                        toolUse.add("input", parseArguments(function.getArguments()));
                        blocks.add(toolUse);
                    }
                }
                if (blocks.isEmpty()) {
                    continue;
                }
                JsonObject assistant = new JsonObject();
                assistant.addProperty("role", "assistant");
                assistant.add("content", blocks);
                messages.add(assistant);
            }
        }
        this.flushToolResults(messages, pendingToolResults);
        AnthropicPromptLayout.appendUpdates(messages, updates);
        // Anthropic-compatible endpoints are not always happy with a request that ENDS in a
        // tool_result block (the agent-loop turn after a tool call); some hang or reject it.
        // Appending an empty user message keeps the conversation open — standard practice for
        // Anthropic-format agents and harmless on endpoints that accept tool_result endings.
        if (this.endsWithToolResult(messages)) {
            JsonObject textBlock = new JsonObject();
            textBlock.addProperty("type", "text");
            textBlock.addProperty("text", StringUtils.EMPTY);
            JsonArray content = new JsonArray();
            content.add(textBlock);
            JsonObject user = new JsonObject();
            user.addProperty("role", "user");
            user.add("content", content);
            messages.add(user);
        }
        com.wjx.touhou_aifun.vision.MultimodalTurnContext.append(callback,
                com.wjx.touhou_aifun.vision.UnifiedModelCatalog.VisualProtocol.ANTHROPIC, messages);
        body.add("messages", messages);

        // --- tools ---
        if (callback.needAddTools) {
            ToolCatalogSnapshot snapshot = ToolContextSelector.snapshot(maid, callback);
            JsonArray tools = new JsonArray();
            for (ToolCatalogSnapshot.Entry entry : snapshot.selected(
                    ChatFlowManager.requestedToolIds(maid.getUUID(), callback))) {
                tools.add(entry.anthropicTool().deepCopy());
            }
            body.add("tools", tools);
        }

        // Streaming must be requested explicitly: without `"stream": true` the endpoint returns a
        // plain JSON body, which the SSE consumer would silently ignore (empty text reply).
        if (TouhouAIFunConfig.LLM_STREAMING.get()) {
            body.addProperty("stream", true);
        }

        // Thinking mode is intentionally NOT controlled here: the Anthropic protocol defaults to
        // non-thinking, and an explicit {"type":"disabled"} is not a valid value per the Anthropic
        // spec (only "enabled" is), so it could be rejected by strict providers. Leaving the field
        // out lets the provider's default behaviour show through — whether the maid thinks or not
        // is then visible in the reply (thinking bubble / latency) instead of being force-set.
        return body;
    }

    private void flushToolResults(JsonArray messages, List<JsonObject> pendingToolResults) {
        if (pendingToolResults.isEmpty()) {
            return;
        }
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        JsonArray content = new JsonArray();
        for (JsonObject block : pendingToolResults) {
            content.add(block);
        }
        user.add("content", content);
        messages.add(user);
        pendingToolResults.clear();
    }

    /** True when the last message of the request body is a {@code tool_result} user message. */
    private boolean endsWithToolResult(JsonArray messages) {
        if (messages.isEmpty()) {
            return false;
        }
        JsonElement last = messages.get(messages.size() - 1);
        if (!last.isJsonObject() || !"user".equals(optString(last.getAsJsonObject(), "role"))) {
            return false;
        }
        JsonElement content = last.getAsJsonObject().get("content");
        if (content == null || !content.isJsonArray()) {
            return false;
        }
        for (JsonElement element : content.getAsJsonArray()) {
            if (element.isJsonObject() && "tool_result".equals(optString(element.getAsJsonObject(), "type"))) {
                return true;
            }
        }
        return false;
    }

    /** Anthropic tool_use {@code input} arrives as parsed JSON; the base mod expects an arguments string. */
    private static JsonElement parseArguments(@Nullable String arguments) {
        if (StringUtils.isBlank(arguments)) {
            return new JsonObject();
        }
        try {
            JsonElement parsed = JsonParser.parseString(arguments);
            return parsed.isJsonObject() ? parsed : new JsonObject();
        } catch (JsonSyntaxException e) {
            return new JsonObject();
        }
    }

    private void complete(LLMCallback callback, HttpResponse<String> response,
                          Throwable throwable, HttpRequest request) {
        try {
            this.handle(callback, response, throwable, request);
        } catch (RuntimeException e) {
            TouhouLittleMaid.LOGGER.error("Failed to process Anthropic LLM response from {}", request.uri(), e);
            callback.onFailure(request, e, ErrorCode.JSON_DECODE_ERROR);
        }
    }

    protected void handle(LLMCallback callback, HttpResponse<String> response, Throwable throwable, HttpRequest request) {
        if (throwable != null) {
            callback.onFailure(request, throwable, ErrorCode.REQUEST_SENDING_ERROR);
            return;
        }
        EntityMaid maid = callback.getMaid();
        if (this.shouldStopChat(maid)) {
            if (com.wjx.touhou_aifun.chat.agent.AgentExecution.managed(callback)) ChatFlowManager.finishRequest(maid.getUUID(), callback);
            return;
        }
        if (!this.isSuccessful(response)) {
            if (com.wjx.touhou_aifun.vision.MultimodalTurnContext.tryFallback(callback, this, response.statusCode(), response.body())) return;
            String message = "HTTP Error Code: %d, Response: %s".formatted(response.statusCode(), response.body());
            callback.onFailure(request, new Throwable(message), ErrorCode.REQUEST_RECEIVED_ERROR);
            return;
        }
        JsonObject root;
        try {
            root = JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (JsonSyntaxException | IllegalStateException e) {
            String message = "Exception %s, JSON is: %s".formatted(e.getLocalizedMessage(), response.body());
            callback.onFailure(request, new Throwable(message), ErrorCode.JSON_DECODE_ERROR);
            return;
        }
        this.processResponse(callback, root, request, null);
    }

    /**
     * Shared handling for the non-streaming path: token accounting, then either a tool/agent call
     * or a text reply. A streaming reply, when provided, finalizes text replies through
     * {@link StreamingTtsReply} so the early-spoken sentences are not re-synthesized.
     */
    protected void processResponse(LLMCallback callback, JsonObject root, HttpRequest request,
                                   @Nullable StreamingTtsReply ttsReply) {
        if (TouhouLittleMaid.DEBUG && com.wjx.touhou_aifun.chat.agent.AgentExecution.context(callback).task()==null) {
            TouhouLittleMaid.LOGGER.info(GSON.toJson(root));
        }

        if (root.has("usage") && root.get("usage").isJsonObject()) {
            var usage = TokenUsage.read(TokenUsage.anthropic(root.getAsJsonObject("usage")));
            AgentTelemetry.usage(callback, "anthropic", usage);
            int inputTokens = usage.input_tokens();
            int outputTokens = usage.output_tokens();
            if (inputTokens > 0 && com.wjx.touhou_aifun.chat.agent.AgentExecution.managed(callback)
                    && !com.wjx.touhou_aifun.vision.MultimodalTurnContext.hasImages(callback)) {
                AIFunMemoryManager.recordPromptCalibration(callback.getChatManager(), inputTokens,
                        callback.getMessages(), ToolContextSelector.schemaBudget(callback.getMaid(), callback));
            }
            if (inputTokens + outputTokens > 0 && !this.recordTokenUsage(callback, inputTokens + outputTokens, request)) {
                return;
            }
        }

        String text = StringUtils.EMPTY;
        String reasoning = StringUtils.EMPTY;
        List<ToolUse> toolUses = new ArrayList<>();
        if (root.has("content") && root.get("content").isJsonArray()) {
            // Anthropic content-block format (non-streaming responses).
            for (JsonElement element : root.getAsJsonArray("content")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject block = element.getAsJsonObject();
                String type = optString(block, "type");
                if ("text".equals(type)) {
                    text += optString(block, "text");
                } else if ("thinking".equals(type)) {
                    reasoning += optString(block, "thinking");
                } else if ("tool_use".equals(type)) {
                    toolUses.add(new ToolUse(optString(block, "id"), optString(block, "name"), block.get("input")));
                }
            }
        } else {
            // OpenAI-normalized shape (streaming responses rebuilt by StreamAccumulator):
            // {choices:[{message:{content, reasoning_content, tool_calls}}]}.
            JsonArray choices = root.has("choices") && root.get("choices").isJsonArray()
                    ? root.getAsJsonArray("choices") : null;
            JsonObject message = choices != null && !choices.isEmpty() && choices.get(0).isJsonObject()
                    ? choices.get(0).getAsJsonObject().getAsJsonObject("message") : null;
            if (message != null) {
                text = optString(message, "content");
                reasoning = optString(message, "reasoning_content");
                if (message.has("tool_calls") && message.get("tool_calls").isJsonArray()) {
                    for (JsonElement element : message.getAsJsonArray("tool_calls")) {
                        if (!element.isJsonObject()) {
                            continue;
                        }
                        JsonObject toolCall = element.getAsJsonObject();
                        JsonObject function = toolCall.has("function") && toolCall.get("function").isJsonObject()
                                ? toolCall.getAsJsonObject("function") : null;
                        String name = function == null ? StringUtils.EMPTY : optString(function, "name");
                        String arguments = function == null ? StringUtils.EMPTY : optString(function, "arguments");
                        toolUses.add(new ToolUse(optString(toolCall, "id"), name, parseArguments(arguments)));
                    }
                }
            }
        }

        String failure = ResponseCompletionGuard.failure(ResponseCompletionGuard.reason(root),
                !toolUses.isEmpty() || StringUtils.isNotBlank(text));
        if (failure != null) {
            if (ttsReply != null) ttsReply.abort();
            callback.onFailure(request, new IllegalStateException(failure), ErrorCode.REQUEST_RECEIVED_ERROR);
            return;
        }
        if (!toolUses.isEmpty()) {
            callback.onFunctionCall(this.buildFunctionCallMessage(text, reasoning, toolUses), this);
            return;
        }
        this.onTextCall(callback, text, reasoning, ttsReply);
    }

    /**
     * Rebuilds a base-mod {@link Message} (with {@code tool_calls}) from Anthropic {@code tool_use}
     * blocks, feeding {@link LLMCallback#onFunctionCall} and thus the agent loop unchanged.
     */
    private Message buildFunctionCallMessage(String text, String reasoning, List<ToolUse> toolUses) {
        JsonObject message = new JsonObject();
        message.addProperty("role", "assistant");
        message.addProperty("content", ReasoningContentCodec.encode(text, reasoning));
        JsonArray toolCalls = new JsonArray();
        for (ToolUse toolUse : toolUses) {
            JsonObject function = new JsonObject();
            function.addProperty("name", toolUse.name);
            // input is protocol-guaranteed to be an object, but a null/JsonNull value would
            // otherwise serialize to the literal "null" and break the base mod's arguments parse.
            String arguments = toolUse.input == null || toolUse.input.isJsonNull() ? "{}" : GSON.toJson(toolUse.input);
            function.addProperty("arguments", arguments);
            JsonObject toolCall = new JsonObject();
            toolCall.addProperty("id", toolUse.id);
            toolCall.addProperty("type", "function");
            toolCall.add("function", function);
            toolCalls.add(toolCall);
        }
        message.add("tool_calls", toolCalls);
        return GSON.fromJson(message, Message.class);
    }

    /** Token accounting shared with the OpenAI path: per-maid cache and per-player quota check. */
    private boolean recordTokenUsage(LLMCallback callback, int totalTokens, HttpRequest request) {
        if (callback.shouldCacheTokenUsage()) {
            callback.getMaid().getAiChatManager().setLastChatTokenUsage(totalTokens);
        }
        if (callback.getMaid().getOwner() instanceof ServerPlayer serverPlayer) {
            int tokenCount = serverPlayer.getCapability(ChatTokensCapabilityProvider.CHAT_TOKENS_CAP).map(tokens -> {
                tokens.addCount(totalTokens);
                return tokens.getCount();
            }).orElse(0);

            int tokenLimit = AIConfig.MAX_TOKENS_PER_PLAYER.get();
            if (tokenCount > tokenLimit) {
                String message = "Token Limit Exceeded: %d tokens used, limit is %d".formatted(tokenCount, tokenLimit);
                callback.onFailure(request, new Throwable(message), ErrorCode.CHAT_TOKEN_LIMIT_EXCEEDED);
                return false;
            }
        }
        return true;
    }

    protected void onTextCall(LLMCallback callback, String content, String reasoning,
                              @Nullable StreamingTtsReply ttsReply) {
        if (StringUtils.isBlank(content)) {
            if (ttsReply != null) ttsReply.abort();
            callback.onFailure(null, new IllegalStateException(ResponseCompletionGuard.failure("", false)),
                    ErrorCode.REQUEST_RECEIVED_ERROR);
            return;
        }
        if (!com.wjx.touhou_aifun.chat.agent.AgentExecution.foreground(callback)) {
            callback.onSuccess(new ResponseChat(content, content));
            return;
        }
        EntityMaid maid = callback.getMaid();
        ReasoningOpenAIResponseChat responseChat = this.singleSegmentMode(maid)
                ? ReasoningOpenAIResponseChat.singleSegment(content, reasoning, !this.stripChatMarker(maid))
                : new ReasoningOpenAIResponseChat(content, reasoning);
        if (ttsReply != null && ttsReply.isUsable()) {
            ttsReply.finish(responseChat);
        } else {
            callback.onSuccess(responseChat);
        }
    }

    /** Same-language replies are one body with no {@code ---} (see {@code PapiReplacerMixin}). */
    private boolean singleSegmentMode(EntityMaid maid) {
        var chatManager = maid.getAiChatManager();
        return chatManager.getChatLanguage().equals(chatManager.getTTSLanguage());
    }

    /** True when the chat bubble should drop the leading {@code (emotion)} marker. */
    private boolean stripChatMarker(EntityMaid maid) {
        return TouhouAIFunConfig.TTS_EMOTION_CONTROL.get()
                && !TouhouAIFunConfig.TTS_EMOTION_IN_TEXT.get()
                && EmotionControlPrompts.isSupported(maid);
    }

    private static String optString(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? StringUtils.EMPTY : element.getAsString();
    }

    private static int optInt(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? 0 : element.getAsInt();
    }

    /** A function {@code tool_use} block from the model. */
    private record ToolUse(String id, String name, @Nullable JsonElement input) {
    }

    // ------------------------------------------------------------------
    // Streaming (SSE)
    // ------------------------------------------------------------------

    /**
     * Streaming variant of {@link #chat}. Anthropic SSE events are translated into the same
     * {@link StreamChunk} deltas the OpenAI path accumulates, so {@link StreamAccumulator},
     * {@link StreamingTtsReply} and {@link StreamingDisplay} are reused unchanged: reasoning
     * ({@code thinking} blocks) streams into the thinking bubble, answer text is spoken early,
     * and tool calls are accumulated from {@code input_json_delta} fragments and finalized
     * through {@link LLMCallback#onFunctionCall}.
     */
    private void chatStreaming(LLMCallback callback, HttpRequest httpRequest) {
        EntityMaid maid = callback.getMaid();
        StreamAccumulator accumulator = new StreamAccumulator();

        // Only ordinary maid chat should stream into the head bubble and speak; setting
        // generation etc. (LLMCallback subclasses) stream silently, exactly like the OpenAI path.
        StreamingTtsReply ttsReply = null;
        StreamingDisplay display = null;
        if (com.wjx.touhou_aifun.chat.agent.AgentExecution.managed(callback)) {
            boolean singleSegment = this.singleSegmentMode(maid);
            boolean showMarkerInChat = !this.stripChatMarker(maid);
            boolean propagateEmotion = TouhouAIFunConfig.TTS_EMOTION_CONTROL.get()
                    && EmotionControlPrompts.isSupported(maid);
            ttsReply = new StreamingTtsReply(callback, singleSegment, showMarkerInChat, propagateEmotion);
            display = new StreamingDisplay(callback, singleSegment, showMarkerInChat);
        }
        StreamingTtsReply ttsReplyRef = ttsReply;
        StreamingDisplay displayRef = display;
        long deadline = System.nanoTime() + httpRequest.timeout().orElseThrow().toNanos();

        // The raw sendAsync future lets a newer request abort this one before the body is consumed;
        // once streaming starts, the consume loop additionally bails out as soon as it is superseded.
        var timing=AgentTelemetry.model(callback);
        CompletableFuture<HttpResponse<Stream<String>>> future =
                this.httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofLines());
        ChatFlowManager.setModelInFlight(maid.getUUID(), callback, future);
        future.orTimeout(httpRequest.timeout().orElseThrow().toSeconds() + 5, TimeUnit.SECONDS)
                .whenCompleteAsync((response, throwable) ->
                        this.consumeStream(callback, response, throwable, httpRequest, accumulator, ttsReplyRef, displayRef, deadline, timing));
    }

    private void consumeStream(LLMCallback callback, HttpResponse<Stream<String>> response, Throwable throwable,
                               HttpRequest request, StreamAccumulator accumulator, @Nullable StreamingTtsReply ttsReply,
                               @Nullable StreamingDisplay display, long deadline, AgentTelemetry.ModelSpan timing) {
        timing.headers(throwable,true);
        EntityMaid maid = callback.getMaid();
        if (throwable != null) {
            callback.onFailure(request, throwable, ErrorCode.REQUEST_SENDING_ERROR);
            return;
        }
        if (this.shouldStopChat(maid)) {
            timing.finish("cancelled");
            response.body().close();
            if (com.wjx.touhou_aifun.chat.agent.AgentExecution.managed(callback)) ChatFlowManager.finishRequest(maid.getUUID(), callback);
            return;
        }
        StreamReadGuard guard = new StreamReadGuard(response.body(), deadline,
                () -> this.shouldStopChat(maid) || ChatFlowManager.isSuperseded(maid.getUUID(), callback));
        boolean accepted = false;
        try (guard) {
            if (!this.isSuccessful(response)) {
                String body;
                try (Stream<String> lines = response.body()) {
                    body = lines.collect(Collectors.joining("\n"));
                }
                if (com.wjx.touhou_aifun.vision.MultimodalTurnContext.tryFallback(callback, this, response.statusCode(), body)) return;
                String message = "HTTP Error Code: %d, Response: %s".formatted(response.statusCode(), body);
                callback.onFailure(request, new Throwable(message), ErrorCode.REQUEST_RECEIVED_ERROR);
                return;
            }

            UUID maidId = maid.getUUID();
            Map<Integer, String> blockTypes = new HashMap<>();
            try (Stream<String> lines = response.body()) {
                for (String line : (Iterable<String>) lines::iterator) {
                    if (this.shouldStopChat(maid) || ChatFlowManager.isSuperseded(maidId, callback)) {
                        break;
                    }
                    if (accumulator.currentContent().isBlank() && accumulator.currentReasoning().isBlank()
                            && line.startsWith("data:") && com.wjx.touhou_aifun.vision.MultimodalTurnContext.tryFallback(
                                    callback, this, 200, line.substring(5).trim())) return;
                    this.acceptStreamLine(line, blockTypes, accumulator, ttsReply, display);
                    timing.output(accumulator.hasEffectiveOutput());
                }
            }

            // The stream was aborted because a newer request took over (or the maid is gone):
            // abandon this reply, so no tools run and nothing is spoken on a partial answer.
            if (this.shouldStopChat(maid) || ChatFlowManager.isSuperseded(maidId, callback)) {
                if (com.wjx.touhou_aifun.chat.agent.AgentExecution.managed(callback)) ChatFlowManager.finishRequest(maidId, callback);
                return;
            }

            guard.check();
            if (accumulator.buildResponse().getFinishReason() == null)
                throw new IllegalStateException("模型响应流提前结束，未收到完成原因；未完成的工具调用没有执行。");
            timing.finish("ok");
            this.processStreamedResponse(callback, accumulator, request, ttsReply);
            accepted = true;
        } catch (RuntimeException e) {
            if (this.shouldStopChat(maid) || ChatFlowManager.isSuperseded(maid.getUUID(), callback)) return;
            TouhouLittleMaid.LOGGER.error("Failed to process streaming Anthropic LLM response from {}", request.uri(), e);
            callback.onFailure(request, guard.expired() ? new IllegalStateException("模型请求超时，请检查 llm.requestTimeoutSeconds。") : e,
                    guard.expired() ? ErrorCode.REQUEST_SENDING_ERROR : ErrorCode.JSON_DECODE_ERROR);
        } finally {
            timing.finish(this.shouldStopChat(maid) || ChatFlowManager.isSuperseded(maid.getUUID(),callback)?"cancelled"
                    : guard.expired()?"timeout":accepted?"ok":"error");
            if (!accepted && ttsReply != null) ttsReply.abort();
        }
    }

    /**
     * Parses one Anthropic SSE event and feeds the equivalent {@link StreamChunk} deltas into the
     * accumulator, then forwards any newly visible content to the streaming TTS/display.
     */
    private void acceptStreamLine(String line, Map<Integer, String> blockTypes, StreamAccumulator accumulator,
                                  @Nullable StreamingTtsReply ttsReply, @Nullable StreamingDisplay display) {
        if (line == null || !line.startsWith("data:")) {
            return;
        }
        String payload = line.substring(5).trim();
        if (payload.isEmpty()) {
            return;
        }
        JsonObject event;
        try {
            event = JsonParser.parseString(payload).getAsJsonObject();
        } catch (JsonSyntaxException | IllegalStateException e) {
            return;
        }

        switch (optString(event, "type")) {
            case "message_start" -> this.onMessageStart(event, accumulator);
            case "content_block_start" -> this.onBlockStart(event, blockTypes, accumulator);
            case "content_block_delta" -> this.onBlockDelta(event, blockTypes, accumulator);
            case "content_block_stop" -> this.onBlockStop(event, blockTypes);
            case "message_delta" -> this.onMessageDelta(event, accumulator);
            case "error" -> throw new IllegalStateException("服务商在响应流中返回了错误，请检查服务商状态并重试。");
            default -> {
                // message_stop / ping events carry nothing we accumulate.
            }
        }

        // Non-ordinary callbacks (e.g. setting generation) stream silently: no early TTS, no bubble.
        if (ttsReply == null) {
            return;
        }
        // Forward completed sentences to TTS early, but never while the model is producing tool
        // calls (those are agent turns with no spoken text).
        if (ttsReply.isUsable() && !accumulator.hasToolCalls()) {
            ttsReply.onPartial(accumulator.currentContent());
        }
        // Live-stream the reasoning, then the answer text, into the head bubble.
        display.onUpdate(accumulator);
    }

    /** {@code message_start}: carries the initial input-token count. */
    private void onMessageStart(JsonObject event, StreamAccumulator accumulator) {
        JsonObject message = event.has("message") && event.get("message").isJsonObject()
                ? event.getAsJsonObject("message") : null;
        if (message == null || !message.has("usage") || !message.get("usage").isJsonObject()) {
            return;
        }
        JsonObject usage = message.getAsJsonObject("usage");
        accumulator.acceptAnthropicUsage(usage);
    }

    /** {@code content_block_start}: registers the block kind; a {@code tool_use} block seeds its id/name. */
    private void onBlockStart(JsonObject event, Map<Integer, String> blockTypes, StreamAccumulator accumulator) {
        int index = optInt(event, "index");
        JsonObject block = event.has("content_block") && event.get("content_block").isJsonObject()
                ? event.getAsJsonObject("content_block") : null;
        String type = block == null ? "skip" : optString(block, "type");
        if (!"text".equals(type) && !"thinking".equals(type) && !"tool_use".equals(type)) {
            // redacted_thinking etc.: never accumulate their payloads.
            blockTypes.put(index, "skip");
            return;
        }
        blockTypes.put(index, type);
        if ("tool_use".equals(type)) {
            accumulator.accept(toolUseStartChunk(index, optString(block, "id"), optString(block, "name")));
            JsonElement input = block.get("input");
            if (input != null && input.isJsonObject() && input.getAsJsonObject().size() > 0) {
                accumulator.accept(toolUseArgumentsChunk(index, GSON.toJson(input)));
                // The full input already arrived with content_block_start; any later
                // input_json_delta fragments would duplicate it, so seal the block.
                blockTypes.put(index, "tool_use_done");
            }
        }
    }

    /** {@code content_block_delta}: text/thinking/tool-call fragments. */
    private void onBlockDelta(JsonObject event, Map<Integer, String> blockTypes, StreamAccumulator accumulator) {
        int index = optInt(event, "index");
        String blockType = blockTypes.getOrDefault(index, "skip");
        JsonObject delta = event.has("delta") && event.get("delta").isJsonObject()
                ? event.getAsJsonObject("delta") : null;
        if (delta == null) {
            return;
        }
        switch (optString(delta, "type")) {
            case "text_delta" -> {
                if ("text".equals(blockType)) {
                    accumulator.accept(textChunk(optString(delta, "text")));
                }
            }
            case "thinking_delta" -> {
                if ("thinking".equals(blockType)) {
                    accumulator.accept(reasoningChunk(optString(delta, "thinking")));
                }
            }
            case "input_json_delta" -> {
                if ("tool_use".equals(blockType)) {
                    accumulator.accept(toolUseArgumentsChunk(index, optString(delta, "partial_json")));
                }
            }
            default -> {
                // signature_delta etc. carry no visible content.
            }
        }
    }

    /** {@code content_block_stop}: releases the block-kind entry. */
    private void onBlockStop(JsonObject event, Map<Integer, String> blockTypes) {
        int index = optInt(event, "index");
        blockTypes.remove(index);
    }

    /** {@code message_delta}: final usage and stop reason. */
    private void onMessageDelta(JsonObject event, StreamAccumulator accumulator) {
        if (event.has("delta") && event.get("delta").isJsonObject()) {
            String reason = optString(event.getAsJsonObject("delta"), "stop_reason");
            if (!reason.isBlank()) {
                JsonObject choice = new JsonObject();
                choice.addProperty("index", 0);
                choice.addProperty("finish_reason", reason);
                JsonArray choices = new JsonArray();
                choices.add(choice);
                JsonObject chunk = new JsonObject();
                chunk.add("choices", choices);
                accumulator.accept(GSON.fromJson(chunk, StreamChunk.class));
            }
        }
        if (event.has("usage") && event.get("usage").isJsonObject()) {
            JsonObject usage = event.getAsJsonObject("usage");
            accumulator.acceptAnthropicUsage(usage);
        }
    }

    /** Normalizes the streamed response into the same JSON shape as the non-streaming one. */
    private void processStreamedResponse(LLMCallback callback, StreamAccumulator accumulator,
                                         HttpRequest request, @Nullable StreamingTtsReply ttsReply) {
        JsonObject root = GSON.toJsonTree(accumulator.buildResponse()).getAsJsonObject();
        this.processResponse(callback, root, request, ttsReply);
    }

    private static StreamChunk textChunk(String text) {
        JsonObject delta = new JsonObject();
        delta.addProperty("content", text);
        return choiceChunk(delta);
    }

    private static StreamChunk reasoningChunk(String reasoning) {
        JsonObject delta = new JsonObject();
        delta.addProperty("reasoning_content", reasoning);
        return choiceChunk(delta);
    }

    private static StreamChunk toolUseStartChunk(int index, String id, String name) {
        JsonObject function = new JsonObject();
        if (StringUtils.isNotBlank(name)) {
            function.addProperty("name", name);
        }
        JsonObject toolCall = new JsonObject();
        toolCall.addProperty("index", index);
        if (StringUtils.isNotBlank(id)) {
            toolCall.addProperty("id", id);
        }
        toolCall.add("function", function);
        JsonArray toolCalls = new JsonArray();
        toolCalls.add(toolCall);
        JsonObject delta = new JsonObject();
        delta.add("tool_calls", toolCalls);
        return choiceChunk(delta);
    }

    private static StreamChunk toolUseArgumentsChunk(int index, String arguments) {
        JsonObject function = new JsonObject();
        function.addProperty("arguments", arguments);
        JsonObject toolCall = new JsonObject();
        toolCall.addProperty("index", index);
        toolCall.add("function", function);
        JsonArray toolCalls = new JsonArray();
        toolCalls.add(toolCall);
        JsonObject delta = new JsonObject();
        delta.add("tool_calls", toolCalls);
        return choiceChunk(delta);
    }

    private static StreamChunk choiceChunk(JsonObject delta) {
        JsonObject choice = new JsonObject();
        choice.add("delta", delta);
        JsonArray choices = new JsonArray();
        choices.add(choice);
        JsonObject root = new JsonObject();
        root.add("choices", choices);
        return GSON.fromJson(root, StreamChunk.class);
    }
}
