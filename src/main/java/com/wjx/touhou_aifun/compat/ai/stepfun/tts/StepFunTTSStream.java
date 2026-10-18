package com.wjx.touhou_aifun.compat.ai.stepfun.tts;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.TTSCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.Client;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.common.net.HttpHeaders;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wjx.touhou_aifun.chat.ChatFlowManager;
import com.wjx.touhou_aifun.mixin.TTSCallbackAccessor;
import com.wjx.touhou_aifun.network.AIFunNetwork;
import com.wjx.touhou_aifun.network.message.AIFunTTSStreamMessage;
import net.minecraft.server.level.ServerLevel;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * One WebSocket session of StepFun's official streaming speech synthesis.
 *
 * <p>Protocol (see {@code https://platform.stepfun.com/docs/zh/api-reference/audio/ws-audio}):
 * connect to {@code wss://api.stepfun.com/v1/realtime/audio?model=...}, send {@code tts.create} on
 * {@code tts.connection.done}, send any number of {@code tts.text.delta} events (at most 1000 code points
 * each) once {@code tts.response.created} arrives, then {@code tts.text.done}. The server answers with
 * {@code tts.response.audio.delta} (base64 raw 16-bit little-endian mono PCM at the requested sample
 * rate) and terminates with {@code tts.response.audio.done}.</p>
 *
 * <p>This class owns only one synthesis run. It can be fed incrementally ({@link #feed(String)} called
 * as sentences become available) or with the complete text at once ({@link #feed(String)} then
 * {@link #finishText()}). Playback is relayed to the maid's audience as soon as the first PCM chunk
 * arrives, so no buffering of a complete audio file is needed on the client.</p>
 *
 * <p>A generation captured from {@link ChatFlowManager} stops the relay when a newer reply takes over
 * speaking. Any failure before the first PCM chunk is reported as {@code FAILED} with
 * {@code anyAudio == false} so the caller can fall back to the ordinary HTTP endpoint; failures after
 * playback started only end the stream (never restart it, which would duplicate speech).</p>
 */
final class StepFunTTSStream {
    static final int SAMPLE_RATE = 24_000;
    static final int MAX_TEXT_CODE_POINTS = 1_000;
    /** Models documented for the WebSocket endpoint. */
    private static final Set<String> STREAMING_MODELS =
            Set.of("stepaudio-3-tts", "stepaudio-2.5-tts", "step-tts-2", "step-tts-mini", "step-tts-vivid");
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final long IDLE_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(45);
    private static final long IDLE_CHECK_MILLIS = 5_000;
    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "AIFun-StepFun-TTS-watchdog");
        thread.setDaemon(true);
        return thread;
    });

    enum Outcome {
        /** Audio was generated and relayed to the client. */
        STREAMED,
        /** No audio could be relayed; the caller may retry over HTTP. */
        FAILED,
        /** The run lost the TTS generation (or was explicitly aborted); never retried. */
        ABORTED
    }

    @FunctionalInterface
    interface Completion {
        void finished(Outcome outcome, boolean anyAudio, Throwable error);
    }

    private final HttpClient httpClient;
    private final StepFunTTSSite site;
    private final EntityMaid maid;
    private final UUID maidId;
    private final int generation;
    private final String model;
    private final String voice;
    private final String instruction;
    private final TTSCallback callback;
    private final Completion completion;
    private final UUID streamId = UUID.randomUUID();
    private final Object lock = new Object();
    private final Deque<String> textQueue = new ArrayDeque<>();
    private final StringBuilder fragments = new StringBuilder();

    private volatile WebSocket socket;
    private volatile ScheduledFuture<?> watchdog;
    private volatile long lastActivityNanos = System.nanoTime();
    private volatile String sessionId;
    private boolean sessionCreated;
    private boolean textFinished;
    private boolean textDoneSent;
    private boolean finished;
    private boolean audioStarted;

    StepFunTTSStream(HttpClient httpClient, StepFunTTSSite site, EntityMaid maid, int generation,
                     String model, String voice, String instruction, TTSCallback callback,
                     Completion completion) {
        this.httpClient = httpClient;
        this.site = site;
        this.maid = maid;
        this.maidId = maid.getUUID();
        this.generation = generation;
        this.model = model;
        this.voice = voice;
        this.instruction = instruction == null ? "" : instruction;
        this.callback = callback;
        this.completion = completion;
    }

    /** Queues text for the session; safe to call before the socket is connected. */
    void feed(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        touch();
        synchronized (this.lock) {
            if (this.finished || this.textFinished) {
                return;
            }
            for (String chunk : splitText(text, MAX_TEXT_CODE_POINTS)) {
                this.textQueue.addLast(chunk);
            }
        }
        flushText();
    }

    /** Signals that no more text will be fed; {@code tts.text.done} is sent once it is safe. */
    void finishText() {
        touch();
        synchronized (this.lock) {
            if (this.finished || this.textFinished) {
                return;
            }
            this.textFinished = true;
        }
        flushText();
    }

    /** Connects and starts the synthesis session. Must be called once before feeding text. */
    void start() {
        this.watchdog = WATCHDOG.schedule(this::checkIdle, IDLE_CHECK_MILLIS, TimeUnit.MILLISECONDS);
        try {
            WebSocket.Builder builder = this.httpClient.newWebSocketBuilder()
                    .connectTimeout(CONNECT_TIMEOUT)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + this.site.secretKey());
            this.site.headers().forEach(builder::header);
            builder.buildAsync(realtimeUri(this.site.url(), this.model), new Listener())
                    .whenComplete((webSocket, error) -> {
                        if (error != null) {
                            fail(error);
                            return;
                        }
                        this.socket = webSocket;
                    });
        } catch (RuntimeException error) {
            // Invalid configured headers or an unusable URI: treat it like any other connect failure
            // so the caller can fall back to the HTTP endpoint instead of breaking the chat turn.
            fail(error);
        }
    }

    /** Stops the session without reporting audio as successfully streamed. */
    void abort() {
        if (!markFinished()) {
            return;
        }
        closeSocket();
        boolean anyAudio = this.audioStarted;
        if (anyAudio) {
            // Let the client's PCM stream reach EOF instead of waiting for its read timeout.
            AIFunNetwork.sendMaidTtsStream(this.maid, AIFunTTSStreamMessage.end(this.streamId));
        }
        completion.finished(Outcome.ABORTED, anyAudio, null);
    }

    // ------------------------------------------------------------------
    // Server events
    // ------------------------------------------------------------------

    private final class Listener implements WebSocket.Listener {
        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            fragments.append(data);
            if (last) {
                String event = fragments.toString();
                fragments.setLength(0);
                try {
                    handleEvent(webSocket, JsonParser.parseString(event).getAsJsonObject());
                } catch (RuntimeException error) {
                    fail(error);
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            if (!isFinished()) {
                fail(new IllegalStateException("StepFun streaming TTS closed: " + statusCode + " " + reason));
            }
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            fail(error);
        }
    }

    private void handleEvent(WebSocket webSocket, JsonObject event) {
        touch();
        String type = event.has("type") ? event.get("type").getAsString() : "";
        JsonObject data = event.has("data") && event.get("data").isJsonObject()
                ? event.getAsJsonObject("data") : new JsonObject();
        switch (type) {
            case "tts.connection.done" -> sendCreate(webSocket, readSessionId(data));
            case "tts.response.created" -> {
                synchronized (this.lock) {
                    this.sessionCreated = true;
                }
                flushText();
            }
            case "tts.response.audio.delta" -> acceptAudio(data);
            case "tts.response.audio.done" -> succeed();
            case "tts.response.error", "error" -> fail(new IllegalStateException(readError(event, data)));
            default -> {
                // sentence.start/end, subtitle, flushed: not needed for plain playback.
            }
        }
    }

    private void sendCreate(WebSocket webSocket, String session) {
        JsonObject event = new JsonObject();
        JsonObject data = new JsonObject();
        data.addProperty("session_id", session);
        data.addProperty("voice_id", this.voice);
        // Raw PCM lets the client play the very first chunk; every audio.delta is a real sample
        // range of the same 16-bit little-endian mono stream at this sample rate.
        data.addProperty("response_format", "pcm");
        data.addProperty("sample_rate", SAMPLE_RATE);
        data.addProperty("mode", "sentence");
        if (!this.instruction.isBlank()) {
            data.addProperty("instruction", this.instruction);
        }
        event.addProperty("type", "tts.create");
        event.add("data", data);
        webSocket.sendText(Client.GSON.toJson(event), true).whenComplete((ignored, error) -> {
            if (error != null) {
                // Never invoke completion while a caller may hold one of our locks (send futures can
                // complete inline); the watchdog thread is free of those monitors.
                WATCHDOG.execute(() -> fail(error));
            }
        });
    }

    private void acceptAudio(JsonObject data) {
        if (isFinished() || !data.has("audio") || data.get("audio").isJsonNull()) {
            return;
        }
        String encoded = data.get("audio").getAsString();
        if (encoded.isEmpty()) {
            return;
        }
        byte[] pcm;
        try {
            pcm = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException error) {
            fail(error);
            return;
        }
        if (pcm.length == 0) {
            return;
        }
        if (ChatFlowManager.ttsGeneration(this.maidId) != this.generation) {
            abort();
            return;
        }
        if (!this.maid.isAlive()) {
            abort();
            return;
        }
        touch();
        boolean first;
        synchronized (this.lock) {
            if (this.finished) {
                return;
            }
            first = !this.audioStarted;
            this.audioStarted = true;
        }
        if (first) {
            AIFunNetwork.sendMaidTtsStream(this.maid, AIFunTTSStreamMessage.start(
                    this.streamId, this.maid.getId(), SAMPLE_RATE, pcm));
        } else {
            AIFunNetwork.sendMaidTtsStream(this.maid, AIFunTTSStreamMessage.data(this.streamId, pcm));
        }
    }

    private void succeed() {
        if (!markFinished()) {
            return;
        }
        closeSocket();
        if (this.audioStarted) {
            AIFunNetwork.sendMaidTtsStream(this.maid, AIFunTTSStreamMessage.end(this.streamId));
            showChatBubble();
            completion.finished(Outcome.STREAMED, true, null);
        } else {
            completion.finished(Outcome.FAILED, false,
                    new IllegalStateException("StepFun streaming TTS produced no audio"));
        }
    }

    private void fail(Throwable error) {
        if (!markFinished()) {
            return;
        }
        closeSocket();
        boolean anyAudio = this.audioStarted;
        if (anyAudio) {
            AIFunNetwork.sendMaidTtsStream(this.maid, AIFunTTSStreamMessage.end(this.streamId));
            showChatBubble();
        }
        completion.finished(Outcome.FAILED, anyAudio, error);
    }

    // ------------------------------------------------------------------
    // Text / socket / lifecycle helpers
    // ------------------------------------------------------------------

    private void flushText() {
        if (isFinished()) {
            return;
        }
        List<String> toSend = new ArrayList<>();
        boolean sendDone = false;
        String session;
        WebSocket webSocket = this.socket;
        synchronized (this.lock) {
            if (!this.sessionCreated || this.finished || webSocket == null) {
                return;
            }
            session = this.sessionId;
            while (!this.textQueue.isEmpty()) {
                toSend.add(this.textQueue.pollFirst());
            }
            if (this.textFinished && !this.textDoneSent) {
                this.textDoneSent = true;
                sendDone = true;
            }
            // Invoke the sends while holding the lock: the WebSocket queues send operations in
            // invocation order, so concurrent flushes can never reorder text deltas or the done marker.
            for (String text : toSend) {
                sendText(webSocket, Client.GSON.toJson(textDeltaEvent(session, text)));
            }
            if (sendDone) {
                sendText(webSocket, Client.GSON.toJson(textDoneEvent(session)));
            }
        }
    }

    private void sendText(WebSocket webSocket, String json) {
        try {
            webSocket.sendText(json, true).whenComplete((ignored, error) -> {
                if (error != null) {
                    WATCHDOG.execute(() -> fail(error));
                }
            });
        } catch (RuntimeException error) {
            WATCHDOG.execute(() -> fail(error));
        }
    }

    private void checkIdle() {
        if (isFinished()) {
            return;
        }
        // Also notice a takeover/death between audio chunks: a superseded reply must not keep the
        // socket (and its billing) alive until the next provider event.
        if (ChatFlowManager.ttsGeneration(this.maidId) != this.generation || !this.maid.isAlive()) {
            abort();
            return;
        }
        if (System.nanoTime() - this.lastActivityNanos > IDLE_TIMEOUT_NANOS) {
            fail(new IllegalStateException("StepFun streaming TTS idle timeout"));
            return;
        }
        this.watchdog = WATCHDOG.schedule(this::checkIdle, IDLE_CHECK_MILLIS, TimeUnit.MILLISECONDS);
    }

    private void touch() {
        this.lastActivityNanos = System.nanoTime();
    }

    private boolean isFinished() {
        synchronized (this.lock) {
            return this.finished;
        }
    }

    private boolean markFinished() {
        ScheduledFuture<?> currentWatchdog = this.watchdog;
        if (currentWatchdog != null) {
            currentWatchdog.cancel(false);
        }
        synchronized (this.lock) {
            if (this.finished) {
                return false;
            }
            this.finished = true;
            this.textQueue.clear();
            return true;
        }
    }

    private void closeSocket() {
        WebSocket webSocket = this.socket;
        if (webSocket == null) {
            return;
        }
        try {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "done");
        } catch (RuntimeException ignored) {
            // The server may already have closed the session after audio.done.
        }
    }

    private String readSessionId(JsonObject data) {
        String session = data.has("session_id") ? data.get("session_id").getAsString() : UUID.randomUUID().toString();
        this.sessionId = session;
        return session;
    }

    private String readError(JsonObject event, JsonObject data) {
        if (data.has("message")) {
            return data.get("message").getAsString();
        }
        if (event.has("message")) {
            return event.get("message").getAsString();
        }
        return "StepFun streaming TTS failed: " + event;
    }

    /**
     * Mirrors the base callback's chat bubble, which normally appears in {@code TTSCallback.onSuccess}
     * together with the audio it is no longer sending (audio was already streamed live). Blank chat
     * text (our own streaming reply owns the bubble) means this is a no-op.
     */
    private void showChatBubble() {
        if (!(this.callback instanceof TTSCallbackAccessor accessor)) {
            return;
        }
        String chatText = accessor.touhouAIFun$chatText();
        if (chatText == null || chatText.isBlank()) {
            return;
        }
        long bubbleId = accessor.touhouAIFun$waitingChatBubbleId();
        if (!(this.maid.level() instanceof ServerLevel level)) {
            return;
        }
        level.getServer().execute(() -> {
            if (this.maid.isAlive()) {
                this.maid.getChatBubbleManager().addLLMChatText(chatText, bubbleId);
            }
        });
    }

    // ------------------------------------------------------------------
    // Protocol helpers (package-private for unit tests and the reply stream)
    // ------------------------------------------------------------------

    static boolean supportsModel(String model) {
        return model != null && STREAMING_MODELS.contains(model);
    }

    /** Derives the WebSocket endpoint from the configured HTTP speech URL. */
    static URI realtimeUri(String configuredUrl, String model) {
        URI configured = URI.create(configuredUrl);
        String scheme = configured.getScheme() == null ? "wss" : configured.getScheme();
        if ("https".equalsIgnoreCase(scheme)) {
            scheme = "wss";
        } else if ("http".equalsIgnoreCase(scheme)) {
            scheme = "ws";
        }
        String path = configured.getPath() == null ? "" : configured.getPath();
        String realtimePath = path.contains("/step_plan/") ? "/step_plan/v1/realtime/audio" : "/v1/realtime/audio";
        try {
            return new URI(scheme, configured.getUserInfo(), configured.getHost(), configured.getPort(),
                    realtimePath, "model=" + model, null);
        } catch (URISyntaxException error) {
            throw new IllegalArgumentException("Invalid StepFun TTS URL: " + configuredUrl, error);
        }
    }

    static JsonObject textDeltaEvent(String sessionId, String text) {
        JsonObject event = new JsonObject();
        JsonObject data = new JsonObject();
        data.addProperty("session_id", sessionId);
        data.addProperty("text", text);
        event.addProperty("type", "tts.text.delta");
        event.add("data", data);
        return event;
    }

    static JsonObject textDoneEvent(String sessionId) {
        JsonObject event = new JsonObject();
        JsonObject data = new JsonObject();
        data.addProperty("session_id", sessionId);
        event.addProperty("type", "tts.text.done");
        event.add("data", data);
        return event;
    }

    /** Splits text at code-point boundaries so no surrogate pair is cut in half. */
    static List<String> splitText(String value, int maxCodePoints) {
        List<String> chunks = new ArrayList<>();
        if (value == null || value.isEmpty()) {
            return chunks;
        }
        int start = 0;
        while (start < value.length()) {
            int remaining = value.codePointCount(start, value.length());
            int end = remaining <= maxCodePoints
                    ? value.length() : value.offsetByCodePoints(start, maxCodePoints);
            chunks.add(value.substring(start, end));
            start = end;
        }
        return chunks;
    }

}
