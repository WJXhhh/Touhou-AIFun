package com.wjx.touhou_aifun.compat.ai.stepfun.tts;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.TTSCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.ErrorCode;
import com.github.tartaricacid.touhoulittlemaid.ai.service.tts.TTSClient;
import com.github.tartaricacid.touhoulittlemaid.ai.service.tts.TTSConfig;
import com.google.common.net.HttpHeaders;
import com.google.common.net.MediaType;
import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import com.wjx.touhou_aifun.network.StepFunNetwork;
import com.wjx.touhou_aifun.network.message.StepFunTTSStreamMessage;
import com.wjx.touhou_aifun.compat.ai.tts.VoicePresetSpec;
import com.wjx.touhou_aifun.compat.ai.tts.SentenceTextSplitter;
import com.wjx.touhou_aifun.config.TouhouStepFunConfig;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class StepFunTTSClient implements TTSClient {
    private static final Duration MAX_TIMEOUT = Duration.ofSeconds(60);
    private static final int SAMPLE_RATE = 24_000;
    private static final String DEFAULT_MODEL = "step-tts-mini";
    private static final String DEFAULT_VOICE = "cixingnansheng";
    private static final String STEP_PLAN_MODEL = "stepaudio-2.5-tts";
    private static final int MAX_HTTP_INPUT_LENGTH = 1_000;
    private static final int MAX_INSTRUCTION_LENGTH = 200;

    private final HttpClient httpClient;
    private final StepFunTTSSite site;

    public StepFunTTSClient(HttpClient httpClient, StepFunTTSSite site) {
        this.httpClient = httpClient;
        this.site = site;
    }

    @Override
    public void play(String message, TTSConfig config, TTSCallback callback) {
        VoicePresetSpec preset = VoicePresetSpec.decode(config.model());
        String[] parts = splitModelAndVoice(preset.runtimeValue());
        if (isStepPlanUrl(this.site.url())) {
            parts[0] = STEP_PLAN_MODEL;
        }
        String instruction = STEP_PLAN_MODEL.equals(parts[0])
                ? limitCodePoints(preset.instruction(), MAX_INSTRUCTION_LENGTH) : "";
        List<String> chunks = SentenceTextSplitter.split(message, MAX_HTTP_INPUT_LENGTH);
        if (chunks.size() == 1) {
            playHttp(message, parts[0], parts[1], instruction, callback);
            return;
        }
        if (TouhouStepFunConfig.TTS_SENTENCE_STREAMING.get()) {
            playChunkedHttp(chunks, 0, parts[0], parts[1], instruction,
                    callback, System.currentTimeMillis());
        } else {
            playBufferedHttp(chunks, 0, parts[0], parts[1], instruction,
                    callback, new ArrayList<>());
        }
    }

    private void playHttp(String message, String model, String voice, String instruction, TTSCallback callback) {
        HttpRequest request = buildHttpRequest(message, model, voice, instruction, "mp3");
        this.httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                .whenComplete((response, throwable) -> handleResponse(callback, response, throwable, request));
    }

    private void playChunkedHttp(List<String> chunks, int index, String model, String voice,
                                 String instruction, TTSCallback callback, long playbackAtMillis) {
        if (index >= chunks.size()) {
            return;
        }
        String chunk = chunks.get(index);
        HttpRequest request = buildHttpRequest(chunk, model, voice, instruction, "wav");
        this.httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                .whenComplete((response, throwable) -> {
            if (throwable != null) {
                callback.onFailure(request, throwable, ErrorCode.REQUEST_SENDING_ERROR);
                return;
            }
            if (!isSuccessful(response)) {
                String body = new String(response.body(), StandardCharsets.UTF_8);
                String error = "HTTP Error Code: %d, Response: %s".formatted(response.statusCode(), body);
                callback.onFailure(request, new IllegalStateException(error), ErrorCode.REQUEST_RECEIVED_ERROR);
                return;
            }
            byte[] audio = response.body();
            if (audio == null || audio.length == 0) {
                callback.onFailure(request, new IllegalStateException("StepFun returned no audio"),
                        ErrorCode.REQUEST_RECEIVED_ERROR);
                return;
            }

            long sendAt = Math.max(System.currentTimeMillis(), playbackAtMillis);
            long duration = Math.max(250, wavDurationMillis(audio));
            long delay = Math.max(0, sendAt - System.currentTimeMillis());
            CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS)
                    .execute(() -> callback.onSuccess(audio));
            playChunkedHttp(chunks, index + 1, model, voice, instruction,
                    callback, sendAt + duration);
        });
    }

    private void playBufferedHttp(List<String> chunks, int index, String model, String voice,
                                  String instruction, TTSCallback callback, List<byte[]> audioChunks) {
        if (index >= chunks.size()) {
            audioChunks.forEach(callback::onSuccess);
            return;
        }
        HttpRequest request = buildHttpRequest(chunks.get(index), model, voice, instruction, "wav");
        this.httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                .whenComplete((response, throwable) -> {
            if (throwable != null) {
                callback.onFailure(request, throwable, ErrorCode.REQUEST_SENDING_ERROR);
                return;
            }
            if (!isSuccessful(response)) {
                String body = new String(response.body(), StandardCharsets.UTF_8);
                String error = "HTTP Error Code: %d, Response: %s".formatted(response.statusCode(), body);
                callback.onFailure(request, new IllegalStateException(error), ErrorCode.REQUEST_RECEIVED_ERROR);
                return;
            }
            byte[] audio = response.body();
            if (audio == null || audio.length == 0) {
                callback.onFailure(request, new IllegalStateException("StepFun returned no audio"),
                        ErrorCode.REQUEST_RECEIVED_ERROR);
                return;
            }
            audioChunks.add(audio);
            playBufferedHttp(chunks, index + 1, model, voice, instruction, callback, audioChunks);
        });
    }

    private HttpRequest buildHttpRequest(String message, String model, String voice,
                                         String instruction, String responseFormat) {
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", model);
        requestBody.addProperty("input", message);
        requestBody.addProperty("voice", voice);
        requestBody.addProperty("response_format", responseFormat);
        requestBody.addProperty("sample_rate", SAMPLE_RATE);
        if (!instruction.isBlank()) {
            requestBody.addProperty("instruction", instruction);
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(this.site.url()))
                .header(HttpHeaders.CONTENT_TYPE, MediaType.JSON_UTF_8.toString())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + this.site.secretKey())
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(requestBody)))
                .timeout(MAX_TIMEOUT);
        this.site.headers().forEach(builder::header);
        return builder.build();
    }

    private static int codePointLength(String value) {
        return value.codePointCount(0, value.length());
    }

    private static String limitCodePoints(String value, int maxCodePoints) {
        if (codePointLength(value) <= maxCodePoints) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, maxCodePoints));
    }

    private static List<String> splitText(String value, int maxCodePoints) {
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < value.length()) {
            int remaining = value.codePointCount(start, value.length());
            int hardEnd = remaining <= maxCodePoints
                    ? value.length() : value.offsetByCodePoints(start, maxCodePoints);
            int end = findSentenceBoundary(value, start, hardEnd, maxCodePoints / 2);
            chunks.add(value.substring(start, end));
            start = end;
        }
        return chunks;
    }

    private static int findSentenceBoundary(String value, int start, int hardEnd, int minimumCodePoints) {
        if (hardEnd == value.length()) {
            return hardEnd;
        }
        int minimum = value.offsetByCodePoints(start, Math.min(minimumCodePoints,
                value.codePointCount(start, hardEnd)));
        for (int index = hardEnd - 1; index >= minimum; index--) {
            if ("。！？!?；;\n".indexOf(value.charAt(index)) >= 0) {
                return index + 1;
            }
        }
        return hardEnd;
    }

    private static long wavDurationMillis(byte[] wav) {
        if (wav.length < 44 || wav[0] != 'R' || wav[1] != 'I' || wav[2] != 'F' || wav[3] != 'F') {
            return 0;
        }
        ByteBuffer buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        int byteRate = 0;
        int dataSize = 0;
        int offset = 12;
        while (offset + 8 <= wav.length) {
            String chunkId = new String(wav, offset, 4, StandardCharsets.US_ASCII);
            int chunkSize = buffer.getInt(offset + 4);
            if (chunkSize < 0 || offset + 8L + chunkSize > wav.length) {
                break;
            }
            if ("fmt ".equals(chunkId) && chunkSize >= 12) {
                byteRate = buffer.getInt(offset + 16);
            } else if ("data".equals(chunkId)) {
                dataSize = chunkSize;
                break;
            }
            offset += 8 + chunkSize + (chunkSize & 1);
        }
        return byteRate > 0 && dataSize > 0 ? (dataSize * 1_000L + byteRate - 1) / byteRate : 0;
    }

    private static byte[] wrapPcmAsWav(byte[] pcm, int sampleRate) {
        int channels = 1;
        int bitsPerSample = 16;
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        ByteBuffer wav = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        wav.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        wav.putInt(36 + pcm.length);
        wav.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        wav.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        wav.putInt(16);
        wav.putShort((short) 1);
        wav.putShort((short) channels);
        wav.putInt(sampleRate);
        wav.putInt(byteRate);
        wav.putShort((short) (channels * bitsPerSample / 8));
        wav.putShort((short) bitsPerSample);
        wav.put("data".getBytes(StandardCharsets.US_ASCII));
        wav.putInt(pcm.length);
        wav.put(pcm);
        return wav.array();
    }

    private static URI buildWebSocketUri(String configuredUrl, String model) {
        URI configured = URI.create(configuredUrl);
        String scheme = configured.getScheme();
        String path = configured.getPath();
        if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
            scheme = "https".equalsIgnoreCase(scheme) ? "wss" : "ws";
            path = path != null && path.contains("/step_plan/")
                    ? "/step_plan/v1/realtime/audio" : "/v1/realtime/audio";
        }
        try {
            return new URI(scheme, configured.getUserInfo(), configured.getHost(), configured.getPort(),
                    path, "model=" + model, null);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid StepFun TTS URL: " + configuredUrl, e);
        }
    }

    private static boolean isStepPlanUrl(String configuredUrl) {
        return URI.create(configuredUrl).getPath().contains("/step_plan/");
    }

    private static URI toHttpUri(URI webSocketUri) {
        String scheme = "wss".equalsIgnoreCase(webSocketUri.getScheme()) ? "https" : "http";
        try {
            return new URI(scheme, webSocketUri.getUserInfo(), webSocketUri.getHost(), webSocketUri.getPort(),
                    webSocketUri.getPath(), webSocketUri.getQuery(), null);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static String[] splitModelAndVoice(String value) {
        if (value == null || value.isBlank()) {
            return new String[]{DEFAULT_MODEL, DEFAULT_VOICE};
        }
        int splitIndex = value.indexOf(':');
        if (splitIndex < 0) {
            return new String[]{DEFAULT_MODEL, value};
        }
        if (splitIndex == 0 || splitIndex == value.length() - 1) {
            return new String[]{DEFAULT_MODEL, DEFAULT_VOICE};
        }
        return new String[]{value.substring(0, splitIndex), value.substring(splitIndex + 1)};
    }

    private final class StreamingListener implements WebSocket.Listener {
        private final String message;
        private final String voice;
        private final String instruction;
        private final boolean streamToPlayer;
        private final TTSCallback callback;
        private final ServerPlayer player;
        private final UUID streamId;
        private final HttpRequest diagnosticRequest;
        private final StringBuilder textBuffer = new StringBuilder();
        private final AtomicBoolean completed = new AtomicBoolean();
        private final AtomicBoolean started = new AtomicBoolean();
        private final AtomicReference<WebSocket> webSocket = new AtomicReference<>();
        private final ByteArrayOutputStream bufferedPcm = new ByteArrayOutputStream();

        private StreamingListener(String message, String voice, String instruction, boolean streamToPlayer,
                                  TTSCallback callback, ServerPlayer player,
                                  UUID streamId, HttpRequest diagnosticRequest) {
            this.message = message;
            this.voice = voice;
            this.instruction = instruction;
            this.streamToPlayer = streamToPlayer;
            this.callback = callback;
            this.player = player;
            this.streamId = streamId;
            this.diagnosticRequest = diagnosticRequest;
        }

        private void setWebSocket(WebSocket webSocket) {
            this.webSocket.compareAndSet(null, webSocket);
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            setWebSocket(webSocket);
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            textBuffer.append(data);
            if (last) {
                String event = textBuffer.toString();
                textBuffer.setLength(0);
                try {
                    handleEvent(webSocket, GSON.fromJson(event, JsonObject.class));
                } catch (Exception e) {
                    fail(e);
                }
            }
            webSocket.request(1);
            return null;
        }

        private void handleEvent(WebSocket webSocket, JsonObject event) {
            String type = event.has("type") ? event.get("type").getAsString() : "";
            JsonObject data = event.has("data") && event.get("data").isJsonObject()
                    ? event.getAsJsonObject("data") : new JsonObject();
            switch (type) {
                case "tts.connection.done" -> sendCreate(webSocket, data.get("session_id").getAsString());
                case "tts.response.created" -> sendText(webSocket, data.get("session_id").getAsString());
                case "tts.response.audio.delta" -> acceptAudio(data);
                case "tts.response.audio.done" -> succeed();
                case "tts.response.error", "error" -> fail(new IllegalStateException(readError(event, data)));
                default -> {
                }
            }
        }

        private void sendCreate(WebSocket webSocket, String sessionId) {
            JsonObject data = new JsonObject();
            data.addProperty("session_id", sessionId);
            data.addProperty("voice_id", voice);
            data.addProperty("response_format", "pcm");
            data.addProperty("sample_rate", SAMPLE_RATE);
            data.addProperty("speed_ratio", 1.0);
            data.addProperty("volume_ratio", 1.0);
            data.addProperty("mode", "sentence");
            if (!instruction.isBlank()) {
                data.addProperty("instruction", instruction);
            }
            webSocket.sendText(event("tts.create", data), true);
        }

        private void sendText(WebSocket webSocket, String sessionId) {
            JsonObject done = new JsonObject();
            done.addProperty("session_id", sessionId);
            CompletableFuture<WebSocket> chain = CompletableFuture.completedFuture(webSocket);
            for (String chunk : splitText(message, MAX_HTTP_INPUT_LENGTH)) {
                JsonObject delta = new JsonObject();
                delta.addProperty("session_id", sessionId);
                delta.addProperty("text", chunk);
                chain = chain.thenCompose(ignored -> webSocket.sendText(event("tts.text.delta", delta), true));
            }
            chain.thenCompose(ignored -> webSocket.sendText(event("tts.text.done", done), true))
                    .exceptionally(throwable -> {
                        fail(throwable);
                        return null;
                    });
        }

        private String event(String type, JsonObject data) {
            JsonObject event = new JsonObject();
            event.addProperty("type", type);
            event.add("data", data);
            return GSON.toJson(event);
        }

        private void acceptAudio(JsonObject data) {
            if (completed.get() || !data.has("audio")) {
                return;
            }
            byte[] pcm = Base64.getDecoder().decode(data.get("audio").getAsString());
            if (pcm.length == 0) {
                return;
            }
            boolean firstChunk = started.compareAndSet(false, true);
            if (!streamToPlayer) {
                bufferedPcm.writeBytes(pcm);
            } else if (firstChunk) {
                StepFunNetwork.sendToPlayer(StepFunTTSStreamMessage.start(
                        streamId, callback.getMaid().getId(), SAMPLE_RATE, pcm), player);
            } else {
                StepFunNetwork.sendToPlayer(StepFunTTSStreamMessage.data(streamId, pcm), player);
            }
        }

        private void succeed() {
            if (!completed.compareAndSet(false, true)) {
                return;
            }
            closeStream();
            WebSocket socket = webSocket.get();
            if (socket != null) {
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "done");
            }
            if (started.get() && streamToPlayer) {
                return;
            }
            if (started.get()) {
                callback.onSuccess(wrapPcmAsWav(bufferedPcm.toByteArray(), SAMPLE_RATE));
            } else {
                callback.onFailure(diagnosticRequest, new IllegalStateException("StepFun returned no audio"),
                        ErrorCode.REQUEST_RECEIVED_ERROR);
            }
        }

        private void fail(Throwable throwable) {
            if (!completed.compareAndSet(false, true)) {
                return;
            }
            closeStream();
            WebSocket socket = webSocket.get();
            if (socket != null) {
                socket.abort();
            }
            callback.onFailure(diagnosticRequest, throwable, ErrorCode.REQUEST_RECEIVED_ERROR);
        }

        private void timeout() {
            if (!completed.get()) {
                fail(new IllegalStateException("StepFun streaming TTS timed out"));
            }
        }

        private void closeStream() {
            if (streamToPlayer && started.get()) {
                StepFunNetwork.sendToPlayer(StepFunTTSStreamMessage.end(streamId), player);
            }
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

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            if (!completed.get()) {
                fail(new IllegalStateException("StepFun WebSocket closed: " + statusCode + " " + reason));
            }
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            fail(error);
        }
    }
}
