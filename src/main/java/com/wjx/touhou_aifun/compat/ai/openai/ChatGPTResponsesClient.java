package com.wjx.touhou_aifun.compat.ai.openai;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.ErrorCode;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.LLMOpenAISite;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wjx.touhou_aifun.chat.ChatFlowManager;
import com.wjx.touhou_aifun.chat.agent.AgentTelemetry;
import com.wjx.touhou_aifun.chat.context.AIFunMemoryManager;
import com.wjx.touhou_aifun.chat.context.ContextBudgetPlanner;
import com.wjx.touhou_aifun.chat.context.ContextTokenEstimator;
import com.wjx.touhou_aifun.compat.ai.EmotionControlPrompts;
import com.wjx.touhou_aifun.compat.ai.chatgpt.ChatGPTLLMSite;
import com.wjx.touhou_aifun.compat.ai.chatgpt.ChatGPTSession;
import com.wjx.touhou_aifun.config.TouhouAIFunConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** Official SIWC subscription access, not the ChatGPT backend-api or a Codex CLI token. */
public final class ChatGPTResponsesClient extends ReasoningCompatOpenAIClient {
    private static final ChatGPTSearchSourceMemory SEARCH_SOURCES = new ChatGPTSearchSourceMemory();
    private static final ScheduledExecutorService TIMEOUTS = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "AIFun-ChatGPT-Streams"); thread.setDaemon(true); return thread;
    });
    private final HttpClient http;
    private final ChatGPTLLMSite subscriptionSite;

    public ChatGPTResponsesClient(HttpClient http, ChatGPTLLMSite site) { super(http, site); this.http = http; this.subscriptionSite = site; }

    @Override public void chat(LLMCallback callback) {
        if (!com.wjx.touhou_aifun.chat.agent.AgentContext.prepare(callback)) return;
        var maid = callback.getMaid();
        if (stopped(callback)) return;
        String question = ChatGPTSearchSourceMemory.latestQuestion(callback.getMessages());
        boolean sourcesRequested = com.wjx.touhou_aifun.chat.agent.AgentExecution.managed(callback) && ChatGPTSearchSourceMemory.requested(question);
        ToolCatalogSnapshot snapshot = ToolContextSelector.snapshot(maid, callback);
        if (com.wjx.touhou_aifun.chat.agent.AgentExecution.managed(callback)) {
            var planned = ContextBudgetPlanner.trim(callback.getMessages(), TouhouAIFunConfig.CONTEXT_INPUT_BUDGET_TOKENS.get(),
                    snapshot.schemaBudget(ChatFlowManager.requestedToolIds(maid.getUUID(), callback))
                            + com.wjx.touhou_aifun.vision.MultimodalTurnContext.inputReserve(callback),
                    AIFunMemoryManager.calibratedEstimate(maid.getAiChatManager(), callback.getMessages())
                            / (double) Math.max(1, ContextTokenEstimator.estimate(callback.getMessages())));
            callback.getMessages().clear(); callback.getMessages().addAll(planned);
        }
        ArrayList<String> reminders = new ArrayList<>();
        if (com.wjx.touhou_aifun.chat.agent.AgentExecution.managed(callback)) {
            reminders.add(snapshot.directory());
            reminders.add(EmotionControlPrompts.changeNotice(maid));
            reminders.add(EmotionControlPrompts.turnReminder(maid));
            if (sourcesRequested) reminders.add(SEARCH_SOURCES.reminder(maid, true));
        }
        JsonArray functions = new JsonArray();
        if (callback.needAddTools) for (var entry : snapshot.selected(ChatFlowManager.requestedToolIds(maid.getUUID(), callback))) {
            JsonObject source = entry.anthropicTool(), tool = new JsonObject();
            tool.addProperty("type", "function");
            tool.addProperty("name", source.get("name").getAsString());
            tool.addProperty("description", source.get("description").getAsString());
            tool.add("parameters", source.get("input_schema").deepCopy());
            tool.addProperty("strict", false);
            functions.add(tool);
        }
        boolean fastRequested = subscriptionSite.fastMode();
        var preparation=AgentTelemetry.root(callback,"model_prepare");
        JsonObject body = ChatGPTResponsesCodec.request(maid.getAiChatManager().getLLMModel(), callback.getMessages(), reminders, functions,
                subscriptionSite.reasoningSettings(), subscriptionSite.webSearch() && callback.needAddTools, fastRequested);
        com.wjx.touhou_aifun.vision.MultimodalTurnContext.append(callback,
                com.wjx.touhou_aifun.vision.UnifiedModelCatalog.VisualProtocol.SUBSCRIPTION, body.getAsJsonArray("input"));
        HttpRequest unauthed = HttpRequest.newBuilder(URI.create(ChatGPTLLMSite.ENDPOINT)).GET().build();
        preparation.finish("ok",0);
        var auth=AgentTelemetry.root(callback,"model_auth");
        // Token refresh can perform network I/O; never block the Minecraft server thread.
        CompletableFuture.supplyAsync(() -> {
            try { return ChatGPTSession.accessToken(); }
            catch (Exception e) { throw new java.util.concurrent.CompletionException(e); }
        }).whenComplete((token, failure) -> {
            auth.finish(stopped(callback)?"cancelled":failure!=null?"error":"ok",0);
            if (stopped(callback)) return;
            if (failure != null) {
                callback.onFailure(unauthed, new IllegalStateException(ChatGPTSession.safeError(failure)), ErrorCode.REQUEST_SENDING_ERROR);
                return;
            }
            HttpRequest request = HttpRequest.newBuilder(URI.create(ChatGPTLLMSite.ENDPOINT)).timeout(com.wjx.touhou_aifun.config.LLMRuntimeBudget.timeout())
                    .header("Content-Type", "application/json").header("Accept", "text/event-stream")
                    .header("Authorization", "Bearer " + token)
                    .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body))).build();
            var timing=AgentTelemetry.model(callback);
            var future = http.sendAsync(request, HttpResponse.BodyHandlers.ofLines());
            ChatFlowManager.setModelInFlight(maid.getUUID(), callback, future);
            long deadline = System.nanoTime() + request.timeout().orElseThrow().toNanos();
            future.orTimeout(request.timeout().orElseThrow().toSeconds() + 5, TimeUnit.SECONDS)
                    .whenCompleteAsync((response, error) -> consume(callback, request, response, error, question, sourcesRequested, fastRequested, deadline,timing));
        });
    }

    private boolean stopped(LLMCallback callback) {
        return shouldStopChat(callback.getMaid()) || (com.wjx.touhou_aifun.chat.agent.AgentExecution.managed(callback)
                && ChatFlowManager.isSuperseded(callback.getMaid().getUUID(), callback));
    }

    private void consume(LLMCallback callback, HttpRequest request, HttpResponse<Stream<String>> response, Throwable error,
                         String question, boolean sourcesRequested, boolean fastRequested, long deadline,AgentTelemetry.ModelSpan timing) {
        timing.headers(error,true);
        if (error != null) { if (!stopped(callback)) callback.onFailure(request, error, ErrorCode.REQUEST_SENDING_ERROR); return; }
        if (stopped(callback)) { timing.finish("cancelled");response.body().close(); return; }
        var watchdog = TIMEOUTS.scheduleAtFixedRate(() -> {
            if (stopped(callback) || System.nanoTime() > deadline) response.body().close();
        }, 100, 100, TimeUnit.MILLISECONDS);
        StreamingTtsReply tts = null;
        boolean accepted = false;
        try (Stream<String> lines = response.body()) {
            if (response.statusCode() != 200) {
                String raw = lines.limit(32).collect(java.util.stream.Collectors.joining("\n"));
                if (com.wjx.touhou_aifun.vision.MultimodalTurnContext.tryFallback(callback, this, response.statusCode(), raw)) return;
                String message = "ChatGPT HTTP " + response.statusCode();
                try {
                    JsonObject fault = JsonParser.parseString(raw).getAsJsonObject().getAsJsonObject("error");
                    String code = com.wjx.touhou_aifun.compat.ai.chatgpt.OpenAIIdentity.string(fault, "code");
                    if (code.matches("[a-zA-Z0-9_]{1,100}")) message += ": " + ChatGPTResponsesCodec.errorMessage(code);
                } catch (RuntimeException ignored) { }
                throw new IllegalStateException(message);
            }
            ChatGPTResponsesCodec.Events events = new ChatGPTResponsesCodec.Events();
            StreamingDisplay display = null;
            // The wire stream is mandatory even when the user disables progressive display.
            if (com.wjx.touhou_aifun.chat.agent.AgentExecution.managed(callback) && TouhouAIFunConfig.LLM_STREAMING.get()) {
                var maid = callback.getMaid();
                boolean single = maid.getAiChatManager().getChatLanguage().equals(maid.getAiChatManager().getTTSLanguage());
                boolean markers = !(TouhouAIFunConfig.TTS_EMOTION_CONTROL.get() && !TouhouAIFunConfig.TTS_EMOTION_IN_TEXT.get()
                        && EmotionControlPrompts.isSupported(maid));
                display = new StreamingDisplay(callback, single, markers);
                tts = new StreamingTtsReply(callback, single, markers,
                        TouhouAIFunConfig.TTS_EMOTION_CONTROL.get() && EmotionControlPrompts.isSupported(maid));
            }
            // SSE allows multiple data lines per event. Blank lines delimit complete events.
            StringBuilder data = new StringBuilder();
            boolean searchShown = false;
            for (String line : (Iterable<String>) lines::iterator) {
                if (stopped(callback)) return;
                if (line.isEmpty()) {
                    if (data.length() == 0) continue;
                    if (events.partial().currentContent().isBlank() && events.partial().currentReasoning().isBlank()
                            && com.wjx.touhou_aifun.vision.MultimodalTurnContext.tryFallback(callback, this, 200, data.toString())) return;
                    events.accept(JsonParser.parseString(data.toString()).getAsJsonObject());
                    timing.output(events.partial().hasEffectiveOutput() || events.hasTools());
                    data.setLength(0);
                    if (events.isCompleted()) break;
                    if (events.isSearching() && !searchShown && events.partial().currentContent().isBlank()
                            && com.wjx.touhou_aifun.chat.agent.AgentExecution.managed(callback)) {
                        searchShown = true;
                        var progress = net.minecraft.network.chat.Component.translatable("gui.touhou_aifun.chatgpt.searching");
                        if (display != null) display.onActivity(progress);
                        else callback.runOnServerThread(() -> { if (!stopped(callback)) callback.refreshWaitingChatBubble(progress); });
                    }
                    if (!events.isSearching()) searchShown = false;
                    if (display != null) {
                        // Search citations may arrive after their text. Finalize speech only after
                        // their annotations are available, so URL markup is never spoken early.
                        if (!events.hasTools() && !events.hasSearch() && tts.isUsable()) tts.onPartial(events.partial().currentContent());
                        display.onUpdate(events.partial());
                    }
                } else if (line.startsWith("data:")) {
                    if (data.length() > 0) data.append('\n');
                    data.append(line.substring(5).stripLeading());
                    if (data.length() > 4_000_000) throw new IllegalStateException("ChatGPT stream event exceeds size limit");
                }
            }
            if (!stopped(callback)) {
                if (System.nanoTime() >= deadline) throw new IllegalStateException("模型请求超时，请检查 llm.requestTimeoutSeconds。");
                JsonObject terminal = events.completed();
                String actualTier = ChatGPTLLMSite.normalizeServiceTier(
                        com.wjx.touhou_aifun.compat.ai.chatgpt.OpenAIIdentity.string(terminal, "service_tier"));
                var completed = OpenAIResponsesCompatLLMClient.adaptResponse(GSON.toJson(terminal));
                if (com.wjx.touhou_aifun.chat.agent.AgentExecution.managed(callback)) callback.runOnServerThread(() -> {
                    if (!stopped(callback)) subscriptionSite.recordServiceTier(actualTier);
                });
                if (com.wjx.touhou_aifun.chat.agent.AgentExecution.managed(callback))
                    com.github.tartaricacid.touhoulittlemaid.TouhouLittleMaid.LOGGER.info(
                            "ChatGPT subscription reply: native_web_search={}, sources={}, maid_tool_calls={}, fast_requested={}, actual_service_tier={}",
                            events.hasSearch(), events.sources().size(), completed.getFirstChoice() != null
                                    && completed.getFirstChoice().hasToolCall(), fastRequested, actualTier);
                if (completed.getFirstChoice() != null && completed.getFirstChoice().hasToolCall() && tts != null) tts.abort();
                if (com.wjx.touhou_aifun.chat.agent.AgentExecution.managed(callback) && events.hasSearch()) {
                    var sources = events.sources();
                    long turn = ChatFlowManager.currentTurnId(callback.getMaid().getUUID(), callback);
                    callback.runOnServerThread(() -> {
                        if (!stopped(callback)) SEARCH_SOURCES.remember(callback.getMaid(), turn, question, sources);
                    });
                }
                timing.finish("ok");
                processChatResponse(callback, completed, request, tts);
                if (com.wjx.touhou_aifun.chat.agent.AgentExecution.managed(callback) && completed.getFirstChoice() != null
                        && !completed.getFirstChoice().hasToolCall() && sourcesRequested) {
                    callback.runOnServerThread(() -> {
                        var sources = SEARCH_SOURCES.sources(callback.getMaid());
                        if (!stopped(callback) && !sources.isEmpty()
                                && callback.getMaid().getOwner() instanceof net.minecraft.server.level.ServerPlayer owner)
                            owner.sendSystemMessage(ChatGPTSearchSources.links(callback.getMaid().getName(), sources));
                    });
                }
                accepted = true;
            }
        } catch (Exception e) {
            if (!stopped(callback)) callback.onFailure(request, new IllegalStateException(ChatGPTSession.safeError(e)), ErrorCode.REQUEST_RECEIVED_ERROR);
        } finally {
            timing.finish(stopped(callback)?"cancelled":System.nanoTime()>=deadline?"timeout":accepted?"ok":"error");
            watchdog.cancel(false);
            if (!accepted && tts != null) tts.abort();
            if (stopped(callback) && com.wjx.touhou_aifun.chat.agent.AgentExecution.managed(callback))
                ChatFlowManager.finishRequest(callback.getMaid().getUUID(), callback);
        }
    }
}
