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
import com.wjx.touhou_aifun.network.AIFunNetwork;
import com.wjx.touhou_aifun.network.message.AIFunTTSStreamMessage;
import com.wjx.touhou_aifun.compat.ai.tts.VoicePresetSpec;
import com.wjx.touhou_aifun.TouhouAIFun;

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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class StepFunTTSClient implements TTSClient {
    private static final Duration MAX_TIMEOUT = Duration.ofSeconds(60);
    private static final int SAMPLE_RATE = 24_000;
    private static final String DEFAULT_MODEL = "stepaudio-3-tts";
    private static final String DEFAULT_VOICE = "cixingnansheng";
    private static final String STEP_PLAN_MODEL = "stepaudio-2.5-tts";
    private static final int MAX_HTTP_INPUT_LENGTH = 1_000;
    private static final String SINGING_INSTRUCTION =
            "请将整段文本作为歌词，以有明确旋律、节拍和持续音高的人声清唱完整演唱。"
                    + "保持歌唱方式，不要朗读、念白或加入说话式开场结尾。无需伴奏。";

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
        String instruction = synthesisInstruction(parts[0], preset.instruction(), message);
        if (isSinging(message)) {
            // Log the effective model (a Step Plan URL can override it), never credentials or lyrics.
            TouhouAIFun.LOGGER.info("StepFun singing request: model={}, characters={}, singingInstruction={}",
                    parts[0], codePointLength(message), SINGING_INSTRUCTION.equals(instruction));
        }
        // Preserve the reply's delivery and musical context. Only split at the provider's size
        // ceiling; every resulting request receives the same reply-wide instruction and MP3 format.
        List<String> chunks = splitText(message, MAX_HTTP_INPUT_LENGTH);
        if (chunks.size() == 1) {
            playHttp(message, parts[0], parts[1], instruction, callback);
            return;
        }
        playBufferedHttp(chunks, 0, parts[0], parts[1], instruction,
                callback, new ArrayList<>());
    }

    private void playHttp(String message, String model, String voice, String instruction, TTSCallback callback) {
        HttpRequest request = buildHttpRequest(message, model, voice, instruction, "mp3");
        this.httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                .whenComplete((response, throwable) -> handleResponse(callback, response, throwable, request));
    }

    private void playBufferedHttp(List<String> chunks, int index, String model, String voice,
                                  String instruction, TTSCallback callback, List<byte[]> audioChunks) {
        if (index >= chunks.size()) {
            ByteArrayOutputStream audio = new ByteArrayOutputStream();
            audioChunks.forEach(audio::writeBytes);
            callback.onSuccess(audio.toByteArray());
            return;
        }
        HttpRequest request = buildHttpRequest(chunks.get(index), model, voice, instruction, "mp3");
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
        JsonObject requestBody = requestBody(message, model, voice, instruction, responseFormat);

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(this.site.url()))
                .header(HttpHeaders.CONTENT_TYPE, MediaType.JSON_UTF_8.toString())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + this.site.secretKey())
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(requestBody)))
                .timeout(MAX_TIMEOUT);
        this.site.headers().forEach(builder::header);
        return builder.build();
    }

    static JsonObject requestBody(String message, String model, String voice,
                                  String instruction, String responseFormat) {
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", model);
        // Keep the performance cue attached to its first lyric rather than on a standalone line.
        // Later lyric line breaks remain intact; they may carry useful musical phrasing.
        String input = ("stepaudio-3-tts".equals(model) || "stepaudio-2.5-tts".equals(model))
                ? message.replaceFirst("(?U)^(\\s*[(（]唱歌[)）])\\s+(?=\\S)", "$1") : message;
        requestBody.addProperty("input", input);
        requestBody.addProperty("voice", voice);
        requestBody.addProperty("response_format", responseFormat);
        requestBody.addProperty("sample_rate", SAMPLE_RATE);
        if (!instruction.isBlank()) {
            requestBody.addProperty("instruction", instruction);
        }
        return requestBody;
    }

    static String synthesisInstruction(String model, String instruction) {
        int limit = switch (model) {
            case "stepaudio-3-tts" -> 500;
            case "stepaudio-2.5-tts" -> 200;
            default -> 0;
        };
        return limitCodePoints(instruction, limit);
    }

    static String synthesisInstruction(String model, String instruction, String message) {
        // A performance requested by the leading marker overrides this turn's general speaking
        // style. This is a best-effort contextual instruction, not a guaranteed singing API mode.
        return synthesisInstruction(model, isSinging(message) ? SINGING_INSTRUCTION : instruction);
    }

    private static boolean isSinging(String message) {
        String text = message == null ? "" : message.stripLeading();
        return text.startsWith("(唱歌)") || text.startsWith("（唱歌）");
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

    static List<String> splitText(String value, int maxCodePoints) {
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
                AIFunNetwork.sendMaidTtsStream(callback.getMaid(), AIFunTTSStreamMessage.start(
                        streamId, callback.getMaid().getId(), SAMPLE_RATE, pcm));
            } else {
                AIFunNetwork.sendMaidTtsStream(callback.getMaid(), AIFunTTSStreamMessage.data(streamId, pcm));
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
                AIFunNetwork.sendMaidTtsStream(callback.getMaid(), AIFunTTSStreamMessage.end(streamId));
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
