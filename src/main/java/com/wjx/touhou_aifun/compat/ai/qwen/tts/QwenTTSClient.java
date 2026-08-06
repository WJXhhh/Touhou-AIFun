package com.wjx.touhou_aifun.compat.ai.qwen.tts;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.TTSCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.ErrorCode;
import com.github.tartaricacid.touhoulittlemaid.ai.service.tts.TTSClient;
import com.github.tartaricacid.touhoulittlemaid.ai.service.tts.TTSConfig;
import com.google.common.net.HttpHeaders;
import com.google.common.net.MediaType;
import com.google.gson.JsonObject;
import org.apache.commons.lang3.StringUtils;
import com.wjx.touhou_aifun.compat.ai.qwen.QwenShared;
import com.wjx.touhou_aifun.compat.ai.tts.VoicePresetSpec;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Text-to-speech via {@code qwen3-tts-flash} on the DashScope native multimodal-generation
 * HTTP endpoint. DashScope has no OpenAI-compatible TTS, so this is the one capability that
 * cannot share the compatible-mode client — but within the {@code qwen3-tts-*} family the
 * request shape is stable, so switching voices/models here does not change the protocol.
 *
 * <p>Unlike the inline-base64 providers (MiMo, StepFun HTTP), the non-streaming response
 * returns a <em>URL</em> to the synthesized audio at {@code output.audio.url}; this client
 * does a second GET to download the bytes. The exact request/response field names below are
 * the established DashScope shape — worth a one-time smoke test against a live key.
 */
public class QwenTTSClient implements TTSClient {
    private static final Duration MAX_TIMEOUT = Duration.ofSeconds(60);
    private static final String DEFAULT_MODEL = QwenShared.TTS_FLASH_MODEL;
    private static final String DEFAULT_VOICE = "Cherry";
    /** qwen3-tts-flash has a low QPS limit; sentence-streaming bursts trip HTTP 429, so back off and retry. */
    private static final int MAX_RETRIES = 3;
    private static final long BASE_RETRY_DELAY_MILLIS = 700;

    private final HttpClient httpClient;
    private final QwenTTSSite site;

    public QwenTTSClient(HttpClient httpClient, QwenTTSSite site) {
        this.httpClient = httpClient;
        this.site = site;
    }

    @Override
    public void play(String message, TTSConfig config, TTSCallback callback) {
        String apiKey = QwenShared.resolveSecretKey(this.site.secretKey());
        if (apiKey == null) {
            callback.onFailure(null, new IllegalArgumentException("Qwen API key is empty"), ErrorCode.REQUEST_SENDING_ERROR);
            return;
        }
        VoicePresetSpec preset = VoicePresetSpec.decode(config.model());

        // The synth endpoint and body are identical for preset/clone/design voices — only the model
        // and the voice id differ. Clone/design ids are enrolled at save time (see QwenVoiceEnrollment),
        // so by the time we synthesize, runtimeValue() is the enrolled voice id.
        String model;
        String voice;
        switch (preset.mode()) {
            case VOICE_DESIGN -> {
                model = QwenShared.TTS_VD_MODEL;
                voice = preset.runtimeValue();
            }
            case REFERENCE_SAMPLE -> {
                model = QwenShared.TTS_VC_MODEL;
                voice = preset.runtimeValue();
            }
            default -> {
                String[] parts = splitModelAndVoice(preset.runtimeValue());
                model = parts[0];
                voice = parts[1];
            }
        }
        if (preset.mode() != VoicePresetSpec.Mode.DIRECT_ID && StringUtils.isBlank(preset.resolvedValue())) {
            callback.onFailure(null, new IllegalStateException("Qwen 自定义音色尚未注册，请在站点设置里重新保存以创建音色"),
                    ErrorCode.REQUEST_SENDING_ERROR);
            return;
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(this.site.url()))
                .header(HttpHeaders.CONTENT_TYPE, MediaType.JSON_UTF_8.toString())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(buildRequestBody(message, model, voice), StandardCharsets.UTF_8))
                .timeout(MAX_TIMEOUT);
        this.site.headers().forEach(builder::header);
        dispatch(builder.build(), callback, 0);
    }

    private void dispatch(HttpRequest request, TTSCallback callback, int attempt) {
        this.httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .whenComplete((response, throwable) -> handle(callback, response, throwable, request, attempt));
    }

    private String buildRequestBody(String message, String model, String voice) {
        JsonObject input = new JsonObject();
        input.addProperty("text", message);
        input.addProperty("voice", voice);

        // The base mod's sound instance only decodes MP3/Opus/Vorbis. qwen3-tts defaults to WAV,
        // which the Ogg decoder cannot read (NPE on the sound thread), so force mp3 here.
        JsonObject parameters = new JsonObject();
        parameters.addProperty("response_format", "mp3");

        JsonObject root = new JsonObject();
        root.addProperty("model", model);
        root.add("input", input);
        root.add("parameters", parameters);
        return GSON.toJson(root);
    }

    private void handle(TTSCallback callback, HttpResponse<String> response, Throwable throwable, HttpRequest request, int attempt) {
        if (this.shouldStopChat(callback.getMaid())) {
            return;
        }
        if (throwable != null) {
            callback.onFailure(request, throwable, ErrorCode.REQUEST_SENDING_ERROR);
            return;
        }
        if (response.statusCode() == 429 && attempt < MAX_RETRIES) {
            // Exponential backoff with jitter; resend the same request rather than failing the sentence.
            long delay = BASE_RETRY_DELAY_MILLIS * (1L << attempt) + ThreadLocalRandom.current().nextLong(200);
            CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS)
                    .execute(() -> {
                        if (!this.shouldStopChat(callback.getMaid())) {
                            dispatch(request, callback, attempt + 1);
                        }
                    });
            return;
        }
        if (!isSuccessful(response)) {
            String body = response.body();
            String detail = body != null && body.length() > 200 ? body.substring(0, 200) : body;
            callback.onFailure(request,
                    new Throwable("HTTP Error Code: %d, Response %s".formatted(response.statusCode(), detail)),
                    ErrorCode.REQUEST_RECEIVED_ERROR);
            return;
        }
        try {
            JsonObject json = GSON.fromJson(response.body(), JsonObject.class);
            JsonObject audio = json != null && json.has("output")
                    ? json.getAsJsonObject("output").getAsJsonObject("audio") : null;
            if (audio == null) {
                throw new IllegalStateException("Qwen TTS returned no audio");
            }
            // Streaming responses embed base64 directly; non-streaming returns a URL to fetch.
            if (audio.has("data") && StringUtils.isNotBlank(audio.get("data").getAsString())) {
                callback.onSuccess(Base64.getDecoder().decode(audio.get("data").getAsString()));
            } else if (audio.has("url") && StringUtils.isNotBlank(audio.get("url").getAsString())) {
                downloadAudio(callback, request, audio.get("url").getAsString());
            } else {
                throw new IllegalStateException("Qwen TTS response had neither audio data nor url");
            }
        } catch (Exception e) {
            callback.onFailure(request, e, ErrorCode.JSON_DECODE_ERROR);
        }
    }

    private void downloadAudio(TTSCallback callback, HttpRequest sourceRequest, String url) {
        HttpRequest download = HttpRequest.newBuilder(URI.create(url))
                .GET()
                .timeout(MAX_TIMEOUT)
                .build();
        this.httpClient.sendAsync(download, HttpResponse.BodyHandlers.ofByteArray())
                .whenComplete((response, throwable) -> {
                    if (this.shouldStopChat(callback.getMaid())) {
                        return;
                    }
                    if (throwable != null) {
                        callback.onFailure(sourceRequest, throwable, ErrorCode.REQUEST_SENDING_ERROR);
                        return;
                    }
                    if (!isSuccessful(response)) {
                        callback.onFailure(sourceRequest,
                                new Throwable("Qwen TTS audio download failed, HTTP " + response.statusCode()),
                                ErrorCode.REQUEST_RECEIVED_ERROR);
                        return;
                    }
                    byte[] audio = response.body();
                    if (audio == null || audio.length == 0) {
                        callback.onFailure(sourceRequest, new IllegalStateException("Qwen TTS downloaded empty audio"),
                                ErrorCode.REQUEST_RECEIVED_ERROR);
                        return;
                    }
                    callback.onSuccess(audio);
                });
    }

    private static String[] splitModelAndVoice(String value) {
        if (StringUtils.isBlank(value)) {
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
