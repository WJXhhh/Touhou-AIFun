package team.cfpa.touhoustepfun.compat.ai.mimo.tts;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.TTSCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.ErrorCode;
import com.github.tartaricacid.touhoulittlemaid.ai.service.tts.TTSClient;
import com.github.tartaricacid.touhoulittlemaid.ai.service.tts.TTSConfig;
import com.google.common.net.HttpHeaders;
import com.google.common.net.MediaType;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.apache.commons.lang3.StringUtils;
import team.cfpa.touhoustepfun.compat.ai.mimo.MimoEndpointResolver;
import team.cfpa.touhoustepfun.compat.ai.mimo.MimoShared;
import team.cfpa.touhoustepfun.compat.ai.mimo.response.MimoAudio;
import team.cfpa.touhoustepfun.compat.ai.mimo.response.MimoChatCompletionResponse;
import team.cfpa.touhoustepfun.compat.ai.mimo.response.MimoMessage;
import team.cfpa.touhoustepfun.compat.ai.tts.VoicePresetSpec;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

public class MimoTTSClient implements TTSClient {
    private static final Duration MAX_TIMEOUT = Duration.ofSeconds(60);
    private static final String DEFAULT_MODEL = "mimo-v2.5-tts";
    private static final String DEFAULT_VOICE = "mimo_default";

    private final HttpClient httpClient;
    private final MimoTTSSite site;

    public MimoTTSClient(HttpClient httpClient, MimoTTSSite site) {
        this.httpClient = httpClient;
        this.site = site;
    }

    @Override
    public void play(String message, TTSConfig config, TTSCallback callback) {
        VoicePresetSpec preset = VoicePresetSpec.decode(config.model());
        String[] parts = preset.mode() == VoicePresetSpec.Mode.DIRECT_ID
                ? splitModelAndVoice(preset.value())
                : new String[]{preset.isDesign() ? "mimo-v2.5-tts-voicedesign" : "mimo-v2.5-tts-voiceclone", StringUtils.EMPTY};
        URI url = MimoEndpointResolver.resolve(this.site.url(), this.site.secretKey());

        String apiKey = MimoShared.resolveSecretKey(this.site.secretKey());
        if (apiKey == null) {
            callback.onFailure(null, new IllegalArgumentException("MiMo API key is empty"), ErrorCode.REQUEST_SENDING_ERROR);
            return;
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .header(HttpHeaders.CONTENT_TYPE, MediaType.JSON_UTF_8.toString())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(buildRequestBody(message, parts[0], parts[1], preset), StandardCharsets.UTF_8))
                .timeout(MAX_TIMEOUT)
                .uri(url);

        this.site.headers().forEach(builder::header);
        HttpRequest request = builder.build();
        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .whenComplete((response, throwable) -> handle(callback, response, throwable, request));
    }

    private String buildRequestBody(String message, String model, String voice, VoicePresetSpec preset) {
        JsonArray messages = new JsonArray();
        JsonObject audio = new JsonObject();
        audio.addProperty("format", "mp3");

        switch (model) {
            case "mimo-v2.5-tts-voicedesign" -> {
                String prompt = preset.isDesign() ? preset.value() : this.site.voiceDesignPrompt();
                if (StringUtils.isBlank(prompt)) {
                    throw new IllegalStateException("MiMo voice design prompt is empty");
                }
                JsonObject user = new JsonObject();
                user.addProperty("role", "user");
                user.addProperty("content", prompt);
                messages.add(user);
            }
            case "mimo-v2.5-tts-voiceclone" -> {
                String voiceData = preset.isReference() ? toDataUrl(preset.value()) : this.site.voiceCloneDataUrl();
                if (StringUtils.isBlank(voiceData)) {
                    throw new IllegalStateException("MiMo voice clone sample is empty");
                }
                JsonObject user = new JsonObject();
                user.addProperty("role", "user");
                user.addProperty("content", "");
                messages.add(user);
                audio.addProperty("voice", voiceData);
            }
            default -> audio.addProperty("voice", voice);
        }

        JsonObject assistant = new JsonObject();
        assistant.addProperty("role", "assistant");
        assistant.addProperty("content", message);
        messages.add(assistant);

        JsonObject root = new JsonObject();
        root.addProperty("model", model);
        root.add("messages", messages);
        root.add("audio", audio);
        return GSON.toJson(root);
    }

    private void handle(TTSCallback callback, HttpResponse<String> response, Throwable throwable, HttpRequest request) {
        if (this.shouldStopChat(callback.getMaid())) {
            return;
        }
        if (throwable != null) {
            callback.onFailure(request, throwable, ErrorCode.REQUEST_SENDING_ERROR);
            return;
        }
        if (!isSuccessful(response)) {
            String body = response.body();
            String detail = body != null && body.length() > 200 ? body.substring(0, 200) : body;
            if (response.statusCode() == 401) {
                String message = "MiMo TTS API authentication failed (HTTP 401). Please check your API key. Detail: %s".formatted(detail);
                callback.onFailure(request, new Throwable(message), ErrorCode.REQUEST_RECEIVED_ERROR);
            } else {
                String message = "HTTP Error Code: %d, Response %s".formatted(response.statusCode(), detail);
                callback.onFailure(request, new Throwable(message), ErrorCode.REQUEST_RECEIVED_ERROR);
            }
            return;
        }
        try {
            MimoChatCompletionResponse chat = GSON.fromJson(response.body(), MimoChatCompletionResponse.class);
            MimoMessage firstMessage = chat == null ? null : chat.getFirstMessage();
            MimoAudio audio = firstMessage == null ? null : firstMessage.getAudio();
            if (audio == null || StringUtils.isBlank(audio.getData())) {
                throw new IllegalStateException("MiMo TTS returned empty audio data");
            }
            callback.onSuccess(Base64.getDecoder().decode(audio.getData()));
        } catch (Exception e) {
            callback.onFailure(request, e, ErrorCode.JSON_DECODE_ERROR);
        }
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

    private static String toDataUrl(String filePath) {
        try {
            Path path = Path.of(filePath);
            if (!Files.isRegularFile(path)) {
                throw new IOException("文件不存在");
            }
            if (Files.size(path) > 1_000_000) {
                throw new IOException("文件过大，MiMo 参考音色限制为 1000 KB");
            }
            String name = path.getFileName().toString().toLowerCase();
            String mimeType = name.endsWith(".mp3") ? "audio/mpeg"
                    : name.endsWith(".wav") ? "audio/wav" : null;
            if (mimeType == null) {
                throw new IOException("仅支持 mp3 或 wav 参考音频");
            }
            return "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(Files.readAllBytes(path));
        } catch (InvalidPathException | IOException e) {
            throw new IllegalStateException("MiMo 参考音频处理失败: " + e.getMessage(), e);
        }
    }
}
