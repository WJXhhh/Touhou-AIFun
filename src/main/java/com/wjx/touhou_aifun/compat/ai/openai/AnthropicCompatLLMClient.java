package com.wjx.touhou_aifun.compat.ai.openai;

import com.github.tartaricacid.touhoulittlemaid.TouhouLittleMaid;
import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ITool;
import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ToolRegister;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.response.ResponseChat;
import com.github.tartaricacid.touhoulittlemaid.ai.service.ErrorCode;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.ObjectParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.Parameter;
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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.apache.commons.lang3.StringUtils;
import com.wjx.touhou_aifun.chat.ChatFlowManager;
import com.wjx.touhou_aifun.chat.context.AIFunMemoryManager;
import com.wjx.touhou_aifun.chat.context.ContextBudgetPlanner;
import com.wjx.touhou_aifun.compat.ai.EmotionControlPrompts;
import com.wjx.touhou_aifun.compat.ai.openai.response.ReasoningOpenAIMessage;
import com.wjx.touhou_aifun.compat.ai.openai.response.StreamAccumulator;
import com.wjx.touhou_aifun.compat.ai.openai.response.StreamChunk;
import com.wjx.touhou_aifun.config.TouhouAIFunConfig;

import javax.annotation.Nullable;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 *   <li>Web search is a <em>server-executed</em> tool: the request declares
 *       {@code {"type": "web_search_20250305", "name": "web_search"}} and DeepSeek runs the
 *       search itself, returning {@code server_tool_use} / {@code web_search_tool_result}
 *       blocks in the same turn. Those blocks are ignored by the parser — the follow-up
 *       {@code text} blocks already contain the answer grounded in the search results.</li>
 * </ul>
 *
 * <p>Text replies flow through the same finalization machinery as the OpenAI clients
 * ({@link StreamingTtsReply}, {@link StreamingDisplay}, the {@code ---} two-segment contract,
 * emotion control), and function tool calls drive the base mod's agent loop via
 * {@link LLMCallback#onFunctionCall}.
 */
public class AnthropicCompatLLMClient implements LLMClient {
    protected static final Duration MAX_TIMEOUT = Duration.ofSeconds(60);
    /** Anthropic requires an explicit {@code max_tokens}; maid replies are short, 2048 is generous. */
    private static final int MAX_TOKENS = 2048;
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    /** Server tool id used by DeepSeek's Anthropic-compatible endpoint (basic web search; the
     * {@code 20260209}+ variants need code execution, which DeepSeek does not support). */
    private static final String WEB_SEARCH_TOOL_TYPE = "web_search_20250305";
    private static final String WEB_SEARCH_TOOL_NAME = "web_search";
    private static final int WEB_SEARCH_MAX_USES = 3;

    protected final HttpClient httpClient;
    protected final LLMOpenAISite site;

    /** How many empty-text retries (server-tool turns) a single conversation may take. */
    private static final int MAX_EMPTY_TEXT_RETRIES = 3;
    private int emptyTextRetries;
    /**
     * Server-executed tool calls ({@code server_tool_use} blocks) that returned no matching
     * {@code web_search_tool_result} in the same turn. The Anthropic protocol requires such
     * unfinished calls to be echoed back on the next request so the provider keeps executing
     * them; when a turn produces no text at all, the client retries with these blocks attached.
     */
    private final Map<String, JsonObject> pendingServerToolUses = new HashMap<>();
    /** Streaming: per-block-index accumulation state for an in-flight {@code server_tool_use} block. */
    private final Map<Integer, ServerToolUseBuilder> streamingServerTools = new HashMap<>();
    /** Streaming: server tool ids whose result block already arrived in this turn. */
    private final Set<String> completedServerToolResultIds = new HashSet<>();

    public AnthropicCompatLLMClient(HttpClient httpClient, LLMOpenAISite site) {
        this.httpClient = httpClient;
        this.site = site;
    }

    @Override
    public void chat(LLMCallback callback) {
        EntityMaid maid = callback.getMaid();
        if (callback.getClass() == LLMCallback.class
                && ChatFlowManager.isSuperseded(maid.getUUID(), callback)) {
            return;
        }
        if (callback.getClass() == LLMCallback.class && !callback.getMessages().stream()
                .anyMatch(message -> message.role() == Role.TOOL)) {
            double factor = AIFunMemoryManager.calibratedEstimate(maid.getAiChatManager(), callback.getMessages())
                    / (double) Math.max(1, com.wjx.touhou_aifun.chat.context.ContextTokenEstimator.estimate(callback.getMessages()));
            List<LLMMessage> planned = ContextBudgetPlanner.trim(callback.getMessages(),
                    TouhouAIFunConfig.CONTEXT_INPUT_BUDGET_TOKENS.get(),
                    ToolContextSelector.schemaBudget(maid, callback), factor);
            callback.getMessages().clear();
            callback.getMessages().addAll(planned);
        }
        // Per-request streaming state. The pending server-tool blocks survive only across the
        // auto-retry of an unfinished server-tool turn (detected by the trailing empty assistant
        // message); a brand-new conversation starts clean.
        if (!this.isRetryTurn(callback)) {
            this.pendingServerToolUses.clear();
        }
        this.completedServerToolResultIds.clear();
        this.streamingServerTools.clear();

        JsonObject body = this.buildRequestBody(callback);

        if (TouhouLittleMaid.DEBUG) {
            TouhouLittleMaid.LOGGER.info(GSON.toJson(body));
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .header(HttpHeaders.CONTENT_TYPE, MediaType.JSON_UTF_8.toString())
                .header("x-api-key", this.site.secretKey())
                .header("anthropic-version", ANTHROPIC_VERSION)
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                .timeout(MAX_TIMEOUT)
                .uri(URI.create(this.endpoint()));
        this.site.headers().forEach(builder::header);
        HttpRequest httpRequest = builder.build();

        if (TouhouAIFunConfig.LLM_STREAMING.get()) {
            this.chatStreaming(callback, httpRequest);
            return;
        }

        // Keep the raw sendAsync future so a newer request can cancel it (cancelling this future
        // aborts the underlying HTTP exchange, stopping the model from generating further).
        CompletableFuture<HttpResponse<String>> future =
                this.httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString());
        ChatFlowManager.setInFlight(maid.getUUID(), callback, future);
        future.orTimeout(MAX_TIMEOUT.toSeconds() + 5, TimeUnit.SECONDS)
                .whenComplete((response, throwable) -> this.complete(callback, response, throwable, httpRequest));
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
     * function tools from {@link ToolRegister}, and the {@code web_search} server tool that
     * DeepSeek's Anthropic-compatible endpoint executes on its side.
     */
    protected JsonObject buildRequestBody(LLMCallback callback) {
        EntityMaid maid = callback.getMaid();
        String model = maid.getAiChatManager().getLLMModel();

        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("max_tokens", MAX_TOKENS);

        // --- system ---
        List<String> systemParts = new ArrayList<>();
        for (LLMMessage message : callback.getMessages()) {
            if (message.role() == Role.SYSTEM && StringUtils.isNotBlank(message.message())) {
                systemParts.add(message.message().trim());
            }
        }
        // Keep the emotion reminders adjacent to the model's next response, exactly like the OpenAI path.
        if (callback.getClass() == LLMCallback.class) {
            systemParts.add(ToolContextSelector.compactDirectory(maid,
                    callback.getMessages().stream().filter(m -> m.role() == Role.USER)
                            .reduce((first, second) -> second).map(LLMMessage::message).orElse("")));
            String emotionChange = EmotionControlPrompts.changeNotice(maid);
            if (emotionChange != null) {
                systemParts.add(emotionChange);
            }
            String reminder = EmotionControlPrompts.turnReminder(maid);
            if (reminder != null) {
                systemParts.add(reminder);
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
        // Unfinished server-executed tool calls (e.g. a web search the provider is still running)
        // must be echoed back so the provider keeps executing them on the retry turn.
        if (!this.pendingServerToolUses.isEmpty()) {
            JsonArray blocks = new JsonArray();
            for (JsonObject block : this.pendingServerToolUses.values()) {
                blocks.add(block);
            }
            JsonObject assistant = new JsonObject();
            assistant.addProperty("role", "assistant");
            assistant.add("content", blocks);
            messages.add(assistant);
        }
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
        body.add("messages", messages);

        // --- tools ---
        if (callback.needAddTools) {
            Set<String> selectedTools = ToolContextSelector.selected(maid, callback);
            JsonArray tools = new JsonArray();
            // Server-executed web search goes first; DeepSeek runs it and returns the results in
            // the same turn, so no client-side tool_result round trip is needed.
            JsonObject webSearch = new JsonObject();
            webSearch.addProperty("type", WEB_SEARCH_TOOL_TYPE);
            webSearch.addProperty("name", WEB_SEARCH_TOOL_NAME);
            webSearch.addProperty("max_uses", WEB_SEARCH_MAX_USES);
            tools.add(webSearch);

            for (var entry : ToolRegister.getAllTools().entrySet()) {
                String toolId = entry.getKey();
                if (!selectedTools.contains(toolId)) {
                    continue;
                }
                ITool<?> tool = entry.getValue();
                if (tool == null || !ToolContextSelector.isTriggered(maid, tool)) {
                    continue;
                }
                JsonObject function = new JsonObject();
                function.addProperty("name", toolId);
                function.addProperty("description", tool.summary(maid));
                ObjectParameter root = ObjectParameter.create();
                Parameter parameter = tool.parameters(root, maid);
                // Parameter subclasses serialize as a plain JSON Schema (type/properties/required/...),
                // which is exactly what Anthropic's input_schema expects.
                function.add("input_schema", GSON.toJsonTree(parameter));
                tools.add(function);
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
            if (callback.getClass() == LLMCallback.class) ChatFlowManager.finishRequest(maid.getUUID(), callback);
            return;
        }
        if (!this.isSuccessful(response)) {
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
        if (TouhouLittleMaid.DEBUG) {
            TouhouLittleMaid.LOGGER.info(GSON.toJson(root));
        }

        if (root.has("usage") && root.get("usage").isJsonObject()) {
            JsonObject usage = root.getAsJsonObject("usage");
            int inputTokens = optInt(usage, "input_tokens");
            int outputTokens = optInt(usage, "output_tokens");
            if (inputTokens + outputTokens == 0) {
                // Streamed responses are normalized to the OpenAI usage shape (prompt/completion).
                inputTokens = optInt(usage, "prompt_tokens");
                outputTokens = optInt(usage, "completion_tokens");
            }
            if (inputTokens > 0 && callback.getClass() == LLMCallback.class) {
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
                } else if ("server_tool_use".equals(type)) {
                    // Executed by the provider; keep the block only while it has no matching result
                    // yet (see onTextCall's retry), otherwise it is dropped right away.
                    this.pendingServerToolUses.put(optString(block, "id"), block);
                } else if ("web_search_tool_result".equals(type)) {
                    this.pendingServerToolUses.remove(optString(block, "tool_use_id"));
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

        if (!toolUses.isEmpty()) {
            this.pendingServerToolUses.clear();
            callback.onFunctionCall(this.buildFunctionCallMessage(text, reasoning, toolUses), this);
            return;
        }
        if (StringUtils.isNotBlank(text)) {
            this.pendingServerToolUses.clear();
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
        message.addProperty("content", text);
        if (StringUtils.isNotBlank(reasoning)) {
            message.addProperty("reasoning_content", reasoning);
        }
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
        return GSON.fromJson(message, ReasoningOpenAIMessage.class);
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
            // A server-tool turn (e.g. web search kicked off by the provider) may legitimately
            // produce no text yet. Echo the unfinished server_tool_use blocks back and retry once
            // (a bounded number of times) so the provider finishes the tool and answers; an empty
            // reply with nothing pending keeps the old behaviour.
            if (!this.pendingServerToolUses.isEmpty() && this.emptyTextRetries < MAX_EMPTY_TEXT_RETRIES) {
                this.retryTurn(callback);
                return;
            }
            callback.onSuccess(new ReasoningOpenAIResponseChat(StringUtils.EMPTY, reasoning));
            return;
        }
        if (callback.getClass() != LLMCallback.class) {
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

    /**
     * Re-issues the conversation with the empty reply recorded in the history. A fresh
     * {@link LLMCallback} is required because the base mod's callbacks hold an immutable message
     * list; its constructor registers it as the latest request (via {@code LLMCallbackMixin}) and
     * creates the waiting bubble, so it must run on the server thread.
     */
    private void retryTurn(LLMCallback callback) {
        this.emptyTextRetries++;
        EntityMaid maid = callback.getMaid();
        List<LLMMessage> history = new ArrayList<>(callback.getMessages());
        history.add(LLMMessage.assistantChat(maid, StringUtils.EMPTY));
        if (maid.level() instanceof ServerLevel serverLevel) {
            serverLevel.getServer().submit(() -> this.chat(new LLMCallback(callback.getChatManager(), history)));
        } else {
            this.chat(new LLMCallback(callback.getChatManager(), history));
        }
    }

    /** True when this request is the auto-retry of an unfinished server-tool turn. */
    private boolean isRetryTurn(LLMCallback callback) {
        List<LLMMessage> messages = callback.getMessages();
        if (messages.isEmpty()) {
            return false;
        }
        LLMMessage last = messages.get(messages.size() - 1);
        return last.role() == Role.ASSISTANT && StringUtils.isBlank(last.message())
                && (last.toolCalls() == null || last.toolCalls().isEmpty());
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

    /** Streaming accumulation state for an in-flight {@code server_tool_use} block. */
    private static final class ServerToolUseBuilder {
        private String id = StringUtils.EMPTY;
        private String name = StringUtils.EMPTY;
        private final StringBuilder input = new StringBuilder();
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
        if (callback.getClass() == LLMCallback.class) {
            boolean singleSegment = this.singleSegmentMode(maid);
            boolean showMarkerInChat = !this.stripChatMarker(maid);
            boolean propagateEmotion = TouhouAIFunConfig.TTS_EMOTION_CONTROL.get()
                    && EmotionControlPrompts.isSupported(maid);
            ttsReply = new StreamingTtsReply(callback, singleSegment, showMarkerInChat, propagateEmotion);
            display = new StreamingDisplay(callback, singleSegment, showMarkerInChat);
        }
        StreamingTtsReply ttsReplyRef = ttsReply;
        StreamingDisplay displayRef = display;

        // The raw sendAsync future lets a newer request abort this one before the body is consumed;
        // once streaming starts, the consume loop additionally bails out as soon as it is superseded.
        CompletableFuture<HttpResponse<Stream<String>>> future =
                this.httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofLines());
        ChatFlowManager.setInFlight(maid.getUUID(), callback, future);
        future.orTimeout(MAX_TIMEOUT.toSeconds() + 5, TimeUnit.SECONDS)
                .whenCompleteAsync((response, throwable) ->
                        this.consumeStream(callback, response, throwable, httpRequest, accumulator, ttsReplyRef, displayRef));
    }

    private void consumeStream(LLMCallback callback, HttpResponse<Stream<String>> response, Throwable throwable,
                               HttpRequest request, StreamAccumulator accumulator, @Nullable StreamingTtsReply ttsReply,
                               @Nullable StreamingDisplay display) {
        EntityMaid maid = callback.getMaid();
        if (throwable != null) {
            callback.onFailure(request, throwable, ErrorCode.REQUEST_SENDING_ERROR);
            return;
        }
        if (this.shouldStopChat(maid)) {
            response.body().close();
            if (callback.getClass() == LLMCallback.class) ChatFlowManager.finishRequest(maid.getUUID(), callback);
            return;
        }
        try {
            if (!this.isSuccessful(response)) {
                String body;
                try (Stream<String> lines = response.body()) {
                    body = lines.collect(Collectors.joining("\n"));
                }
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
                    this.acceptStreamLine(line, blockTypes, accumulator, ttsReply, display);
                }
            }

            // The stream was aborted because a newer request took over (or the maid is gone):
            // abandon this reply, so no tools run and nothing is spoken on a partial answer.
            if (this.shouldStopChat(maid) || ChatFlowManager.isSuperseded(maidId, callback)) {
                if (callback.getClass() == LLMCallback.class) ChatFlowManager.finishRequest(maidId, callback);
                return;
            }

            this.processStreamedResponse(callback, accumulator, request, ttsReply);
        } catch (RuntimeException e) {
            TouhouLittleMaid.LOGGER.error("Failed to process streaming Anthropic LLM response from {}", request.uri(), e);
            callback.onFailure(request, e, ErrorCode.JSON_DECODE_ERROR);
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
            default -> {
                // message_stop / ping / error events carry nothing we accumulate.
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
        int input = optInt(usage, "input_tokens");
        if (input > 0) {
            accumulator.accept(usageChunk(input, 0));
        }
    }

    /** {@code content_block_start}: registers the block kind; a {@code tool_use} block seeds its id/name. */
    private void onBlockStart(JsonObject event, Map<Integer, String> blockTypes, StreamAccumulator accumulator) {
        int index = optInt(event, "index");
        JsonObject block = event.has("content_block") && event.get("content_block").isJsonObject()
                ? event.getAsJsonObject("content_block") : null;
        String type = block == null ? "skip" : optString(block, "type");
        if ("server_tool_use".equals(type)) {
            // Unfinished server tool: accumulate its input deltas; if no result block pairs up
            // with it this turn, it is echoed back on the retry turn (see onTextCall).
            blockTypes.put(index, "server_tool_use");
            ServerToolUseBuilder builder = new ServerToolUseBuilder();
            builder.id = optString(block, "id");
            builder.name = optString(block, "name");
            JsonElement input = block.get("input");
            if (input != null && input.isJsonObject() && input.getAsJsonObject().size() > 0) {
                builder.input.append(GSON.toJson(input));
            }
            this.streamingServerTools.put(index, builder);
            return;
        }
        if ("web_search_tool_result".equals(type)) {
            // The result paired with its server_tool_use arrived: mark the call as completed so
            // its block is not echoed back. The payload itself is provider-internal, never shown.
            String toolUseId = block == null ? StringUtils.EMPTY : optString(block, "tool_use_id");
            if (StringUtils.isNotBlank(toolUseId)) {
                this.completedServerToolResultIds.add(toolUseId);
            }
            blockTypes.put(index, "skip");
            return;
        }
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
                } else if ("server_tool_use".equals(blockType)) {
                    ServerToolUseBuilder builder = this.streamingServerTools.get(index);
                    if (builder != null) {
                        builder.input.append(optString(delta, "partial_json"));
                    }
                }
            }
            default -> {
                // signature_delta etc. carry no visible content.
            }
        }
    }

    /** {@code content_block_stop}: finalizes an in-flight {@code server_tool_use} block. */
    private void onBlockStop(JsonObject event, Map<Integer, String> blockTypes) {
        int index = optInt(event, "index");
        blockTypes.remove(index);
        ServerToolUseBuilder builder = this.streamingServerTools.remove(index);
        if (builder == null || StringUtils.isBlank(builder.id)) {
            return;
        }
        if (this.completedServerToolResultIds.contains(builder.id)) {
            return;
        }
        JsonObject block = new JsonObject();
        block.addProperty("type", "server_tool_use");
        block.addProperty("id", builder.id);
        block.addProperty("name", builder.name);
        block.add("input", parseArguments(builder.input.toString()));
        this.pendingServerToolUses.put(builder.id, block);
    }

    /** {@code message_delta}: final usage and stop reason. */
    private void onMessageDelta(JsonObject event, StreamAccumulator accumulator) {
        if (event.has("usage") && event.get("usage").isJsonObject()) {
            JsonObject usage = event.getAsJsonObject("usage");
            int input = optInt(usage, "input_tokens");
            int output = optInt(usage, "output_tokens");
            if (input + output > 0) {
                accumulator.accept(usageChunk(input, output));
            }
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

    /** Anthropic usage is translated into the OpenAI shape the accumulator stores. */
    private static StreamChunk usageChunk(int inputTokens, int outputTokens) {
        JsonObject usage = new JsonObject();
        usage.addProperty("prompt_tokens", inputTokens);
        usage.addProperty("completion_tokens", outputTokens);
        usage.addProperty("total_tokens", inputTokens + outputTokens);
        JsonObject root = new JsonObject();
        root.add("usage", usage);
        return GSON.fromJson(root, StreamChunk.class);
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
