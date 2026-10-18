package com.wjx.touhou_aifun.compat.ai.stepfun.tts;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.TTSCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.ErrorCode;
import com.github.tartaricacid.touhoulittlemaid.ai.service.tts.TTSClient;
import com.github.tartaricacid.touhoulittlemaid.ai.service.tts.TTSConfig;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.common.net.HttpHeaders;
import com.google.common.net.MediaType;
import com.google.gson.JsonObject;
import com.wjx.touhou_aifun.TouhouAIFun;
import com.wjx.touhou_aifun.chat.ChatFlowManager;
import com.wjx.touhou_aifun.compat.ai.stepfun.StepFunShared;
import com.wjx.touhou_aifun.compat.ai.tts.VoicePresetSpec;
import com.wjx.touhou_aifun.config.TouhouAIFunConfig;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class StepFunTTSClient implements TTSClient {
    private static final Duration MAX_TIMEOUT = Duration.ofSeconds(60);
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

    /** The effective provider model, voice id and session-wide delivery instruction of one request. */
    public record SpeechSpec(String model, String voice, String instruction) {
    }

    @Override
    public void play(String message, TTSConfig config, TTSCallback callback) {
        SpeechSpec spec = resolveSpeech(this.site, config, message);
        boolean singing = isSingingRequest(message);
        if (singing) {
            // Log the effective model (a Step Plan URL can override it), never credentials or lyrics.
            TouhouAIFun.LOGGER.info("StepFun singing request: model={}, characters={}, singingInstruction={}",
                    spec.model(), codePointLength(message), SINGING_INSTRUCTION.equals(spec.instruction()));
        }
        UUID maidId = callback.getMaid().getUUID();
        int generation = ChatFlowManager.ttsGeneration(maidId);
        // A song is one performance: the whole-reply HTTP request (with the singing instruction) is
        // kept for it instead of cutting it into sentences on the realtime endpoint.
        if (!singing && streamingEnabled() && supportsStreaming(this.site.url(), spec.model())) {
            playStreaming(message, spec, callback, generation);
            return;
        }
        playHttp(message, spec, gated(callback, maidId, generation));
    }

    /** Resolves the provider model, voice id and instruction for one synthesis request. */
    public static SpeechSpec resolveSpeech(StepFunTTSSite site, TTSConfig config, String message) {
        VoicePresetSpec preset = VoicePresetSpec.decode(config.model());
        String[] parts = splitModelAndVoice(preset.runtimeValue());
        if (isStepPlanUrl(site.url())) {
            parts[0] = STEP_PLAN_MODEL;
        }
        String instruction = synthesisInstruction(parts[0], preset.instruction(), message);
        return new SpeechSpec(parts[0], parts[1], instruction);
    }

    /**
     * Whether the configured site/model can use the official WebSocket streaming endpoint. Only the
     * official StepFun host is used: its {@code /v1/realtime/audio} path is not part of the
     * OpenAI-compatible surface, so a custom self-hosted URL keeps the plain HTTP contract.
     */
    static boolean supportsStreaming(String configuredUrl, String model) {
        return StepFunTTSStream.supportsModel(model) && StepFunShared.isStepFunApi(configuredUrl);
    }

    /** Whether an LLM-streaming reply may feed this site's TTS incrementally over one WS session. */
    public boolean supportsStreamingReply(TTSConfig config) {
        if (!streamingEnabled()) {
            return false;
        }
        SpeechSpec spec = resolveSpeech(this.site, config, "");
        return supportsStreaming(this.site.url(), spec.model());
    }

    /** Opens one incremental streaming session; used by {@link StepFunStreamingReply}. */
    StepFunTTSStream openStream(EntityMaid maid, int generation, SpeechSpec spec, TTSCallback callback,
                                StepFunTTSStream.Completion completion) {
        return new StepFunTTSStream(this.httpClient, this.site, maid, generation,
                spec.model(), spec.voice(), spec.instruction(), callback, completion);
    }

    /** Resolves the speech spec from the sentence that opens the session, so singing cues work. */
    StepFunTTSStream openStream(EntityMaid maid, int generation, TTSConfig config, String contextText,
                                TTSCallback callback, StepFunTTSStream.Completion completion) {
        return openStream(maid, generation, resolveSpeech(this.site, config, contextText), callback, completion);
    }

    /**
     * HTTP-only synthesis used as the fallback when a streaming session failed before any audio was
     * played. The callback receives the complete encoded audio exactly like the base contract.
     */
    public void playHttpOnly(String message, TTSConfig config, TTSCallback callback) {
        SpeechSpec spec = resolveSpeech(this.site, config, message);
        UUID maidId = callback.getMaid().getUUID();
        playHttp(message, spec, gated(callback, maidId, ChatFlowManager.ttsGeneration(maidId)));
    }

    private void playStreaming(String message, SpeechSpec spec, TTSCallback callback, int generation) {
        StepFunTTSStream stream = openStream(callback.getMaid(), generation, spec, callback,
                (outcome, anyAudio, error) -> {
                    if (outcome == StepFunTTSStream.Outcome.FAILED && !anyAudio) {
                        // Nothing has been played yet, so the ordinary request cannot duplicate speech.
                        TouhouAIFun.LOGGER.warn("StepFun streaming TTS failed ({}), falling back to HTTP",
                                describe(error));
                        playHttp(message, spec, gated(callback, callback.getMaid().getUUID(), generation));
                    }
                });
        stream.start();
        // The complete reply is known here (this entry point is used for non-incremental callers), so
        // every provider-size chunk is queued immediately and the stream starts as soon as it can.
        for (String chunk : splitText(message, MAX_HTTP_INPUT_LENGTH)) {
            stream.feed(chunk);
        }
        stream.finishText();
    }

    private void playHttp(String message, SpeechSpec spec, TTSCallback callback) {
        List<String> chunks = splitText(message, MAX_HTTP_INPUT_LENGTH);
        if (chunks.size() == 1) {
            playHttpChunk(message, spec, callback);
            return;
        }
        playBufferedHttp(chunks, 0, spec, callback, new ArrayList<>());
    }

    private void playHttpChunk(String message, SpeechSpec spec, TTSCallback callback) {
        HttpRequest request = buildHttpRequest(message, spec, "mp3");
        this.httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                .whenComplete((response, throwable) -> handleResponse(callback, response, throwable, request));
    }

    private void playBufferedHttp(List<String> chunks, int index, SpeechSpec spec,
                                  TTSCallback callback, List<byte[]> audioChunks) {
        if (index >= chunks.size()) {
            ByteArrayOutputStream audio = new ByteArrayOutputStream();
            audioChunks.forEach(audio::writeBytes);
            callback.onSuccess(audio.toByteArray());
            return;
        }
        HttpRequest request = buildHttpRequest(chunks.get(index), spec, "mp3");
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
            playBufferedHttp(chunks, index + 1, spec, callback, audioChunks);
        });
    }

    private HttpRequest buildHttpRequest(String message, SpeechSpec spec, String responseFormat) {
        JsonObject requestBody = requestBody(message, spec.model(), spec.voice(),
                spec.instruction(), responseFormat);

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(this.site.url()))
                .header(HttpHeaders.CONTENT_TYPE, MediaType.JSON_UTF_8.toString())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + this.site.secretKey())
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(requestBody)))
                .timeout(MAX_TIMEOUT);
        this.site.headers().forEach(builder::header);
        return builder.build();
    }

    /**
     * Drops late audio (and the chip bubble that would come with it) once a newer reply took over
     * speaking. The generation is captured when the request starts, before any HTTP/WS work.
     */
    private static TTSCallback gated(TTSCallback callback, UUID maidId, int generation) {
        return new TTSCallback(callback.getMaid(), "", -1) {
            @Override
            public void onSuccess(byte[] data) {
                if (ChatFlowManager.ttsGeneration(maidId) == generation) {
                    callback.onSuccess(data);
                }
            }

            @Override
            public void onFailure(HttpRequest request, Throwable throwable, int errorCode) {
                if (ChatFlowManager.ttsGeneration(maidId) == generation) {
                    callback.onFailure(request, throwable, errorCode);
                }
            }
        };
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
        requestBody.addProperty("sample_rate", StepFunTTSStream.SAMPLE_RATE);
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
        return synthesisInstruction(model, isSingingRequest(message) ? SINGING_INSTRUCTION : instruction);
    }

    /** True when the reply asks for singing through the leading {@code (唱歌)} performance cue. */
    public static boolean isSingingRequest(String message) {
        String text = message == null ? "" : message.stripLeading();
        return text.startsWith("(唱歌)") || text.startsWith("（唱歌）");
    }

    private static boolean streamingEnabled() {
        return TouhouAIFunConfig.STEPFUN_TTS_STREAMING.get();
    }

    private static String describe(@Nullable Throwable throwable) {
        if (throwable == null) {
            return "no audio";
        }
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
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

    private static boolean isStepPlanUrl(String configuredUrl) {
        return URI.create(configuredUrl).getPath().contains("/step_plan/");
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
}
