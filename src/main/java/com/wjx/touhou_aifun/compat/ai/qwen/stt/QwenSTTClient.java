package com.wjx.touhou_aifun.compat.ai.qwen.stt;

import com.github.tartaricacid.touhoulittlemaid.ai.service.ErrorCode;
import com.github.tartaricacid.touhoulittlemaid.ai.service.ResponseCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.stt.STTClient;
import com.github.tartaricacid.touhoulittlemaid.ai.service.stt.STTConfig;
import com.github.tartaricacid.touhoulittlemaid.client.sound.record.MicrophoneManager;
import com.google.common.net.HttpHeaders;
import com.google.common.net.MediaType;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.apache.commons.lang3.StringUtils;
import com.wjx.touhou_aifun.compat.ai.qwen.QwenShared;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.Mixer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * Speech-to-text via {@code qwen3-asr-flash} on the DashScope OpenAI-compatible endpoint.
 *
 * <p>The wire shape is a normal chat-completions call carrying an {@code input_audio}
 * content part — the same shape used for the LLM, which is exactly why the request method
 * does not change when you switch between qwen text and ASR models. Stay on the
 * {@code qwen3-asr-*} family; the async {@code fun-asr}/{@code *-filetrans} models and the
 * realtime {@code paraformer} model use different protocols.
 */
public class QwenSTTClient implements STTClient {
    private static final AudioFormat FORMAT = new AudioFormat(16000, 16, 1, true, false);
    private static final Duration MAX_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient httpClient;
    private final QwenSTTSite site;

    public QwenSTTClient(HttpClient httpClient, QwenSTTSite site) {
        this.httpClient = httpClient;
        this.site = site;
    }

    @Override
    public void startRecord(STTConfig config, ResponseCallback<String> callback) {
        Mixer.Info info = MicrophoneManager.getMicrophoneInfo(FORMAT);
        if (info == null) {
            callback.onFailure(null, new Throwable("No suitable microphone found"), ErrorCode.MICROPHONE_NOT_FOUND);
            return;
        }
        String apiKey = QwenShared.resolveSecretKey(this.site.getSecretKey());
        if (apiKey == null) {
            callback.onFailure(null, new IllegalArgumentException("Qwen API key is empty"), ErrorCode.REQUEST_SENDING_ERROR);
            return;
        }
        URI uri = URI.create(this.site.url());

        MicrophoneManager.startRecord(info.getName(), FORMAT, data -> {
            HttpRequest request = HttpRequest.newBuilder().uri(uri)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.JSON_UTF_8.toString())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(buildRequestBody(data), StandardCharsets.UTF_8))
                    .timeout(MAX_TIMEOUT)
                    .build();
            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                    .whenComplete((response, throwable) -> handle(callback, response, throwable, request));
        });
    }

    @Override
    public void stopRecord(STTConfig config, ResponseCallback<String> callback) {
        MicrophoneManager.stopRecord();
    }

    private String buildRequestBody(byte[] wavData) {
        JsonObject inputAudio = new JsonObject();
        inputAudio.addProperty("data", "data:audio/wav;base64," + Base64.getEncoder().encodeToString(wavData));

        JsonObject contentPart = new JsonObject();
        contentPart.addProperty("type", "input_audio");
        contentPart.add("input_audio", inputAudio);

        JsonArray content = new JsonArray();
        content.add(contentPart);

        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.add("content", content);

        JsonArray messages = new JsonArray();
        messages.add(message);

        JsonObject root = new JsonObject();
        root.addProperty("model", this.site.getModel());
        root.add("messages", messages);
        root.addProperty("stream", false);
        // Language is auto-detected by qwen3-asr. To pin it, add an "asr_options" object
        // here, e.g. {"language":"zh"} — verify accepted keys against the DashScope ASR docs.
        return GSON.toJson(root);
    }

    private void handle(ResponseCallback<String> callback, HttpResponse<String> response, Throwable throwable, HttpRequest request) {
        this.<JsonObject>handleResponse(callback, response, throwable, request, json -> {
            String transcript = extractContent(json);
            if (StringUtils.isBlank(transcript)) {
                throw new IllegalStateException("Qwen STT returned empty transcript");
            }
            callback.onSuccess(transcript);
        }, JsonObject.class);
    }

    /** Reads {@code choices[0].message.content}; content may be a plain string or a parts array. */
    private static String extractContent(JsonObject json) {
        if (json == null || !json.has("choices")) {
            return StringUtils.EMPTY;
        }
        JsonArray choices = json.getAsJsonArray("choices");
        if (choices.isEmpty()) {
            return StringUtils.EMPTY;
        }
        JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
        if (message == null || !message.has("content")) {
            return StringUtils.EMPTY;
        }
        if (message.get("content").isJsonArray()) {
            StringBuilder sb = new StringBuilder();
            for (var element : message.getAsJsonArray("content")) {
                JsonObject part = element.getAsJsonObject();
                if (part.has("text")) {
                    sb.append(part.get("text").getAsString());
                }
            }
            return sb.toString();
        }
        return message.get("content").getAsString();
    }
}
