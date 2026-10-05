package com.wjx.touhou_aifun.compat.ai.openai;

import com.github.tartaricacid.touhoulittlemaid.TouhouLittleMaid;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.response.ResponseChat;
import com.github.tartaricacid.touhoulittlemaid.ai.service.ErrorCode;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.DefaultLLMSite;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.Role;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.LLMOpenAIClient;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.LLMOpenAISite;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.request.ResponseFormat;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.response.Message;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.response.Usage;
import com.github.tartaricacid.touhoulittlemaid.capability.ChatTokensCapabilityProvider;
import com.github.tartaricacid.touhoulittlemaid.config.subconfig.AIConfig;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.common.net.HttpHeaders;
import com.google.common.net.MediaType;
import net.minecraft.server.level.ServerPlayer;
import org.apache.commons.lang3.StringUtils;
import com.google.gson.JsonSyntaxException;
import com.wjx.touhou_aifun.compat.ai.openai.request.ReasoningChatCompletion;
import com.wjx.touhou_aifun.compat.ai.openai.response.ReasoningOpenAIChatCompletionResponse;
import com.wjx.touhou_aifun.compat.ai.openai.response.ReasoningOpenAIMessage;
import com.wjx.touhou_aifun.compat.ai.openai.response.StreamAccumulator;
import com.wjx.touhou_aifun.compat.ai.openai.response.StreamChunk;

import com.wjx.touhou_aifun.chat.ChatFlowManager;
import com.wjx.touhou_aifun.chat.agent.AgentTelemetry;
import com.wjx.touhou_aifun.chat.context.AIFunMemoryManager;
import com.wjx.touhou_aifun.chat.context.ContextBudgetPlanner;
import com.wjx.touhou_aifun.compat.ai.EmotionControlPrompts;
import com.wjx.touhou_aifun.config.TouhouAIFunConfig;

import javax.annotation.Nullable;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ReasoningCompatOpenAIClient extends LLMOpenAIClient {
    public ReasoningCompatOpenAIClient(HttpClient httpClient, LLMOpenAISite site) {
        super(httpClient, site);
    }

    @Override
    public void chat(LLMCallback callback) {
        if (!com.wjx.touhou_aifun.chat.agent.AgentContext.prepare(callback)) return;
        EntityMaid maid = callback.getMaid();
        if (com.wjx.touhou_aifun.chat.agent.AgentExecution.managed(callback)
                && ChatFlowManager.isSuperseded(maid.getUUID(), callback)) {
            return;
        }
        URI url = URI.create(this.site.url());
        String apiKey = this.site.secretKey();
        String model = maid.getAiChatManager().getLLMModel();
        boolean isReasoningModel = this.site.isReasoningModel(model);
        ToolCatalogSnapshot toolSnapshot = com.wjx.touhou_aifun.chat.agent.AgentExecution.managed(callback)
                ? ToolContextSelector.snapshot(maid, callback) : null;

        if (com.wjx.touhou_aifun.chat.agent.AgentExecution.managed(callback)) {
            var planned = ContextBudgetPlanner.trim(callback.getMessages(),
                    TouhouAIFunConfig.CONTEXT_INPUT_BUDGET_TOKENS.get(),
                    toolSnapshot.schemaBudget(ChatFlowManager.requestedToolIds(maid.getUUID(), callback))
                            + com.wjx.touhou_aifun.vision.MultimodalTurnContext.inputReserve(callback),
                    AIFunMemoryManager.calibratedEstimate(maid.getAiChatManager(), callback.getMessages())
                            / (double) Math.max(1, com.wjx.touhou_aifun.chat.context.ContextTokenEstimator.estimate(callback.getMessages())));
            callback.getMessages().clear();
            callback.getMessages().addAll(planned);
        }

        var preparation=AgentTelemetry.root(callback,"model_prepare");
        ReasoningChatCompletion chatCompletion = ReasoningChatCompletion.create()
                .model(model)
                .outputBudget(com.wjx.touhou_aifun.config.LLMRuntimeBudget.outputTokens())
                .setResponseFormat(ResponseFormat.text());
        chatCompletion = this.extraArgs(chatCompletion, model);

        for (LLMMessage message : callback.getMessages()) {
            if (message.role() == Role.USER) {
                chatCompletion.userChat(message.message());
            } else if (message.role() == Role.ASSISTANT) {
                ReasoningContentCodec.DecodedContent decoded = ReasoningContentCodec.decode(message.message());
                String content = ReasoningOpenAIResponseChat.normalizeRepeatedParts(decoded.content());
                if (message.toolCalls() == null || message.toolCalls().isEmpty()) {
                    chatCompletion.assistantChat(content, decoded.reasoningContent());
                } else {
                    chatCompletion.assistantChat(content, decoded.reasoningContent(), message.toolCalls());
                }
            } else if (message.role() == Role.SYSTEM) {
                if (isReasoningModel) {
                    chatCompletion.developerChat(message.message());
                } else {
                    chatCompletion.systemChat(message.message());
                }
            } else if (message.role() == Role.TOOL) {
                chatCompletion.toolChat(message.message(), message.toolCallId());
            }
        }

        // Keep the strict format contract adjacent to the model's next response. Restrict this to
        // ordinary maid chat callbacks so setting generation, history summaries, and grounded
        // knowledge extraction retain their own output formats.
        if (com.wjx.touhou_aifun.chat.agent.AgentExecution.managed(callback)) {
            chatCompletion.systemChat(toolSnapshot.directory());
            // If the emotion setting changed since this maid's last reply, announce the switch once so
            // the model stops imitating the old-format replies still present in the conversation history.
            String emotionChange = EmotionControlPrompts.changeNotice(maid);
            if (emotionChange != null) {
                if (isReasoningModel) {
                    chatCompletion.developerChat(emotionChange);
                } else {
                    chatCompletion.systemChat(emotionChange);
                }
            }
            String reminder = EmotionControlPrompts.turnReminder(maid);
            if (reminder != null) {
                if (isReasoningModel) {
                    chatCompletion.developerChat(reminder);
                } else {
                    chatCompletion.systemChat(reminder);
                }
            }
        }

        if (callback.needAddTools) {
            ToolCatalogSnapshot snapshot = toolSnapshot != null
                    ? toolSnapshot : ToolContextSelector.snapshot(maid, callback);
            for (ToolCatalogSnapshot.Entry entry : snapshot.selected(
                    ChatFlowManager.requestedToolIds(maid.getUUID(), callback))) {
                chatCompletion.addTool(entry.openAITool());
            }
        }

        if (this.site.id().equals(DefaultLLMSite.MINIMAX.id())) {
            chatCompletion.mergeSystemMessages();
        }

        boolean streaming = TouhouAIFunConfig.LLM_STREAMING.get();
        if (streaming) {
            chatCompletion.enableStream();
        }

        com.google.gson.JsonObject requestBody = GSON.toJsonTree(chatCompletion).getAsJsonObject();
        com.wjx.touhou_aifun.vision.MultimodalTurnContext.append(callback,
                com.wjx.touhou_aifun.vision.UnifiedModelCatalog.VisualProtocol.CHAT_COMPLETIONS, requestBody.getAsJsonArray("messages"));
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .header(HttpHeaders.CONTENT_TYPE, MediaType.JSON_UTF_8.toString())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(requestBody)))
                .timeout(com.wjx.touhou_aifun.config.LLMRuntimeBudget.timeout())
                .uri(url);

        if (TouhouLittleMaid.DEBUG && com.wjx.touhou_aifun.chat.agent.AgentExecution.context(callback).task()==null) {
            TouhouLittleMaid.LOGGER.info(GSON.toJson(com.wjx.touhou_aifun.vision.MultimodalContent.redacted(requestBody)));
        }

        this.site.headers().forEach(builder::header);
        HttpRequest httpRequest = builder.build();
        preparation.finish("ok",0);

        if (streaming) {
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
                    try { complete(callback, response, throwable, httpRequest); }
                    finally { timing.finish(ChatFlowManager.isSuperseded(maid.getUUID(),callback)?"cancelled"
                            : throwable!=null || response==null || !isSuccessful(response)?"error":"body_received"); }
                });
    }

    /**
     * Streaming (SSE) variant of {@link #chat}. Tool/agent calls are preserved: the streamed deltas
     * are accumulated into a complete response that is fed into the exact same handling path as the
     * non-streaming flow, so a tool-call reply still drives {@link LLMCallback#onFunctionCall} and
     * keeps the agent loop intact. Completed sentences are forwarded to TTS as they arrive.
     */
    private void chatStreaming(LLMCallback callback, HttpRequest httpRequest) {
        EntityMaid maid = callback.getMaid();
        StreamAccumulator accumulator = new StreamAccumulator();

        // Only ordinary maid chat should stream into the head bubble and speak. Setting generation,
        // history summaries, and grounded-knowledge extraction are LLMCallback subclasses whose
        // onSuccess does its own thing (e.g. writing the result into the character setting), so for
        // those we just accumulate the SSE body and finalize through onSuccess — exactly like the
        // non-streaming path. Without this guard, generating a character setting while streaming is on
        // would type the result into the chat bubble instead of saving it as the setting.
        StreamingTtsReply ttsReply = null;
        StreamingDisplay display = null;
        if (com.wjx.touhou_aifun.chat.agent.AgentExecution.managed(callback)) {
            boolean singleSegment = this.singleSegmentMode(maid);
            boolean showMarkerInChat = !this.stripChatMarker(maid);
            // When emotion control is on for an emotion-aware provider, carry the leading (emotion)
            // marker onto every streamed sentence so sentences after the first are not synthesized neutral.
            boolean propagateEmotion = TouhouAIFunConfig.TTS_EMOTION_CONTROL.get()
                    && EmotionControlPrompts.isSupported(maid);
            ttsReply = new StreamingTtsReply(callback, singleSegment, showMarkerInChat, propagateEmotion);
            display = new StreamingDisplay(callback, singleSegment, showMarkerInChat);
        }
        // Effectively-final copies for the completion lambda.
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
            boolean done = false;
            try (Stream<String> lines = response.body()) {
                for (String line : (Iterable<String>) lines::iterator) {
                    if (this.shouldStopChat(maid) || ChatFlowManager.isSuperseded(maidId, callback)) {
                        break;
                    }
                    if (accumulator.currentContent().isBlank() && accumulator.currentReasoning().isBlank()
                            && line.startsWith("data:") && com.wjx.touhou_aifun.vision.MultimodalTurnContext.tryFallback(
                                    callback, this, 200, line.substring(5).trim())) return;
                    this.acceptStreamLine(line, accumulator, ttsReply, display);
                    timing.output(accumulator.hasEffectiveOutput());
                    if ("data: [DONE]".equals(line.strip())) { done = true; break; }
                }
            }

            // The stream was aborted because a newer request took over (or the maid is gone): abandon
            // this reply just like a cancelled non-streaming request, so no tools run and nothing is
            // spoken on a partial answer.
            if (this.shouldStopChat(maid) || ChatFlowManager.isSuperseded(maidId, callback)) {
                if (com.wjx.touhou_aifun.chat.agent.AgentExecution.managed(callback)) ChatFlowManager.finishRequest(maidId, callback);
                return;
            }

            guard.check();
            if (!done && accumulator.buildResponse().getFinishReason() == null)
                throw new IllegalStateException("模型响应流提前结束，未收到完成原因；未完成的工具调用没有执行。");
            timing.finish("ok");
            this.processChatResponse(callback, accumulator.buildResponse(), request, ttsReply);
            accepted = true;
        } catch (RuntimeException e) {
            if (this.shouldStopChat(maid) || ChatFlowManager.isSuperseded(maid.getUUID(), callback)) return;
            TouhouLittleMaid.LOGGER.error("Failed to process streaming LLM response from {}", request.uri(), e);
            callback.onFailure(request, guard.expired() ? new IllegalStateException("模型请求超时，请检查 llm.requestTimeoutSeconds。") : e,
                    guard.expired() ? ErrorCode.REQUEST_SENDING_ERROR : ErrorCode.JSON_DECODE_ERROR);
        } finally {
            timing.finish(this.shouldStopChat(maid) || ChatFlowManager.isSuperseded(maid.getUUID(),callback)?"cancelled"
                    : guard.expired()?"timeout":accepted?"ok":"error");
            if (!accepted && ttsReply != null) ttsReply.abort();
        }
    }

    private void acceptStreamLine(String line, StreamAccumulator accumulator, @Nullable StreamingTtsReply ttsReply,
                                  @Nullable StreamingDisplay display) {
        if (line == null || !line.startsWith("data:")) {
            return;
        }
        String payload = line.substring(5).trim();
        if (payload.isEmpty() || "[DONE]".equals(payload)) {
            return;
        }
        StreamChunk chunk;
        try {
            chunk = GSON.fromJson(payload, StreamChunk.class);
        } catch (JsonSyntaxException e) {
            return;
        }
        if (chunk == null) {
            return;
        }
        accumulator.accept(chunk);
        // Non-ordinary callbacks (e.g. setting generation) stream silently: no early TTS, no bubble.
        if (ttsReply == null) {
            return;
        }
        // Forward completed sentences to TTS early, but never while the model is producing tool
        // calls (those are agent turns with no spoken text).
        if (ttsReply.isUsable() && !accumulator.hasToolCalls()) {
            ttsReply.onPartial(accumulator.currentContent());
        }
        // Live-stream the reasoning, then the answer text, into the head bubble. Done last so that
        // once the TTS reply has taken over the bubble, the display stops touching it.
        display.onUpdate(accumulator);
    }

    protected ReasoningChatCompletion extraArgs(ReasoningChatCompletion chatCompletion, String model) {
        if (this.site.hasThinkingField()) {
            return chatCompletion.disableThinking();
        }
        return chatCompletion;
    }

    /**
     * When the chat language equals the TTS language no translation is needed, so the model outputs a
     * single reply body (no {@code ---}, no duplicated copy) and we derive the display and TTS texts
     * from it. This removes the divergence that arises when the model is asked to write the reply twice.
     */
    private boolean singleSegmentMode(EntityMaid maid) {
        var chatManager = maid.getAiChatManager();
        return chatManager.getChatLanguage().equals(chatManager.getTTSLanguage());
    }

    /** True when the chat bubble should drop the leading {@code (emotion)} marker (emotion control on, not shown in text). */
    private boolean stripChatMarker(EntityMaid maid) {
        return TouhouAIFunConfig.TTS_EMOTION_CONTROL.get()
                && !TouhouAIFunConfig.TTS_EMOTION_IN_TEXT.get()
                && EmotionControlPrompts.isSupported(maid);
    }

    private void complete(LLMCallback callback, HttpResponse<String> response,
                          Throwable throwable, HttpRequest request) {
        try {
            this.handle(callback, response, throwable, request);
        } catch (RuntimeException e) {
            TouhouLittleMaid.LOGGER.error("Failed to process LLM response from {}", request.uri(), e);
            callback.onFailure(request, e, ErrorCode.JSON_DECODE_ERROR);
        }
    }

    protected void handle(LLMCallback callback, HttpResponse<String> response, Throwable throwable, HttpRequest request) {
        EntityMaid maid = callback.getMaid();
        if (this.shouldStopChat(maid)) {
            if (com.wjx.touhou_aifun.chat.agent.AgentExecution.managed(callback)) ChatFlowManager.finishRequest(maid.getUUID(), callback);
            return;
        }

        if (throwable == null && response != null
                && com.wjx.touhou_aifun.vision.MultimodalTurnContext.tryFallback(callback, this, response.statusCode(), response.body())) return;

        this.<ReasoningOpenAIChatCompletionResponse>handleResponse(callback, response, throwable, request,
                chat -> this.processChatResponse(callback, chat, request, null),
                ReasoningOpenAIChatCompletionResponse.class);
    }

    /**
     * Shared handling for both the non-streaming and streaming paths: token accounting, then either a
     * tool/agent call or a text reply. When {@code ttsReply} is a usable streaming reply, a text
     * answer is finalized through it (so the early-spoken sentences are not re-synthesized);
     * otherwise it falls back to {@link LLMCallback#onSuccess}.
     */
    protected void processChatResponse(LLMCallback callback, ReasoningOpenAIChatCompletionResponse chat,
                                       HttpRequest request, @Nullable StreamingTtsReply ttsReply) {
        if (TouhouLittleMaid.DEBUG && com.wjx.touhou_aifun.chat.agent.AgentExecution.context(callback).task()==null) {
            TouhouLittleMaid.LOGGER.info(GSON.toJson(chat));
        }

        Usage usage = chat.getUsage();
        if (usage != null) {
            AgentTelemetry.usage(callback, this instanceof ChatGPTResponsesClient ? "chatgpt_responses"
                    : this instanceof OpenAIResponsesCompatLLMClient ? "responses" : "chat_completions", chat.getTokenUsage());
            if (usage.getPromptTokens() > 0 && com.wjx.touhou_aifun.chat.agent.AgentExecution.managed(callback)
                    && !com.wjx.touhou_aifun.vision.MultimodalTurnContext.hasImages(callback)) {
                AIFunMemoryManager.recordPromptCalibration(callback.getChatManager(), usage.getPromptTokens(),
                        callback.getMessages(), ToolContextSelector.schemaBudget(callback.getMaid(), callback));
            }
            int totalTokens = usage.getTotalTokens();
            if (totalTokens > 0 && callback.shouldCacheTokenUsage()) {
                callback.getMaid().getAiChatManager().setLastChatTokenUsage(totalTokens);
            }
            if (totalTokens > 0 && callback.getMaid().getOwner() instanceof ServerPlayer serverPlayer) {
                int tokenCount = serverPlayer.getCapability(ChatTokensCapabilityProvider.CHAT_TOKENS_CAP).map(tokens -> {
                    tokens.addCount(totalTokens);
                    return tokens.getCount();
                }).orElse(0);

                int tokenLimit = AIConfig.MAX_TOKENS_PER_PLAYER.get();
                if (tokenCount > tokenLimit) {
                    String message = "Token Limit Exceeded: %d tokens used, limit is %d".formatted(tokenCount, tokenLimit);
                    callback.onFailure(request, new Throwable(message), ErrorCode.CHAT_TOKEN_LIMIT_EXCEEDED);
                    return;
                }
            }
        }

        ReasoningOpenAIMessage firstChoice = chat.getFirstChoice();
        String failure = ResponseCompletionGuard.failure(chat.getFinishReason(), firstChoice != null
                && (firstChoice.hasToolCall() || StringUtils.isNotBlank(firstChoice.getVisibleContent())));
        if (failure != null) {
            if (ttsReply != null) ttsReply.abort();
            callback.onFailure(request, new IllegalStateException(failure), ErrorCode.REQUEST_RECEIVED_ERROR);
            return;
        }
        if (firstChoice == null) {
            String message = "No Choice Found";
            callback.onFailure(request, new Throwable(message), ErrorCode.CHAT_CHOICE_IS_EMPTY);
            return;
        }
        if (firstChoice.hasToolCall()) {
            callback.onFunctionCall(firstChoice.toBaseMessage(), this);
        } else {
            this.onTextCall(callback, firstChoice, ttsReply);
        }
    }

    protected void onTextCall(LLMCallback callback, ReasoningOpenAIMessage firstChoice, @Nullable StreamingTtsReply ttsReply) {
        String content = firstChoice.getVisibleContent();
        if (!com.wjx.touhou_aifun.chat.agent.AgentExecution.foreground(callback)) {
            callback.onSuccess(new ResponseChat(content, content));
            return;
        }
        if (StringUtils.isBlank(content)) {
            if (ttsReply != null) ttsReply.abort();
            callback.onFailure(null, new IllegalStateException(ResponseCompletionGuard.failure("", false)),
                    ErrorCode.REQUEST_RECEIVED_ERROR);
            return;
        }
        EntityMaid maid = callback.getMaid();
        ReasoningOpenAIResponseChat responseChat = this.singleSegmentMode(maid)
                ? ReasoningOpenAIResponseChat.singleSegment(content, firstChoice.getReasoningContent(), !this.stripChatMarker(maid))
                : new ReasoningOpenAIResponseChat(content, firstChoice.getReasoningContent());
        if (ttsReply != null && ttsReply.isUsable()) {
            ttsReply.finish(responseChat);
        } else {
            callback.onSuccess(responseChat);
        }
    }
}
