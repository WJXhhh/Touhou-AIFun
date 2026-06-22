package com.wjx.touhou_aifun.compat.ai.stepfun.stt;

import com.github.tartaricacid.touhoulittlemaid.TouhouLittleMaid;
import com.github.tartaricacid.touhoulittlemaid.ai.service.ErrorCode;
import com.github.tartaricacid.touhoulittlemaid.ai.service.ResponseCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.stt.STTClient;
import com.github.tartaricacid.touhoulittlemaid.ai.service.stt.STTConfig;
import com.github.tartaricacid.touhoulittlemaid.client.sound.record.MicrophoneManager;
import com.google.common.net.HttpHeaders;
import com.google.common.net.MediaType;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.Mixer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

public class StepFunSTTClient implements STTClient {
    private static final AudioFormat FORMAT = new AudioFormat(16000, 16, 1, true, false);
    private static final Duration MAX_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient httpClient;
    private final StepFunSTTSite site;

    public StepFunSTTClient(HttpClient httpClient, StepFunSTTSite site) {
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
        URI uri = URI.create(this.site.url());

        MicrophoneManager.startRecord(info.getName(), FORMAT, data -> {
            String requestBody = buildRequestBody(data);
            HttpRequest request = HttpRequest.newBuilder().uri(uri)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.JSON_UTF_8.toString())
                    .header(HttpHeaders.ACCEPT, "text/event-stream")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + this.site.getSecretKey())
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
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
        JsonObject format = new JsonObject();
        format.addProperty("type", "wav");

        JsonObject transcription = new JsonObject();
        transcription.addProperty("language", "zh");
        transcription.addProperty("model", site.getModel());
        transcription.addProperty("enable_itn", true);
        transcription.addProperty("enable_timestamp", false);

        JsonObject input = new JsonObject();
        input.add("transcription", transcription);
        input.add("format", format);

        JsonObject audio = new JsonObject();
        audio.addProperty("data", Base64.getEncoder().encodeToString(wavData));
        audio.add("input", input);

        JsonObject root = new JsonObject();
        root.add("audio", audio);
        return GSON.toJson(root);
    }

    private void handle(ResponseCallback<String> callback, HttpResponse<String> response, Throwable throwable, HttpRequest request) {
        if (throwable != null) {
            callback.onFailure(request, throwable, ErrorCode.REQUEST_SENDING_ERROR);
            return;
        }
        if (!isSuccessful(response)) {
            String message = "HTTP Error Code: %d, Response Body: %s".formatted(response.statusCode(), response.body());
            callback.onFailure(request, new Throwable(message), ErrorCode.REQUEST_RECEIVED_ERROR);
            return;
        }
        try {
            String text = parseSseTranscript(response.body());
            callback.onSuccess(text);
        } catch (Exception e) {
            TouhouLittleMaid.LOGGER.error("Failed to parse StepFun STT SSE response", e);
            callback.onFailure(request, e, ErrorCode.JSON_DECODE_ERROR);
        }
    }

    private String parseSseTranscript(String body) {
        StringBuilder deltaText = new StringBuilder();
        for (String rawLine : body.split("\\R")) {
            String line = rawLine.trim();
            if (!line.startsWith("data:")) {
                continue;
            }
            String payload = line.substring(5).trim();
            if (payload.isEmpty() || "[DONE]".equals(payload)) {
                continue;
            }
            JsonObject json = JsonParser.parseString(payload).getAsJsonObject();
            String type = json.has("type") ? json.get("type").getAsString() : "";
            if ("transcript.text.done".equals(type) && json.has("text")) {
                return json.get("text").getAsString();
            }
            if ("transcript.text.delta".equals(type) && json.has("delta")) {
                deltaText.append(json.get("delta").getAsString());
            }
            if ("error".equals(type)) {
                String message = json.has("message") ? json.get("message").getAsString() : "Unknown StepFun STT error";
                throw new IllegalStateException(message);
            }
        }
        if (deltaText.length() > 0) {
            return deltaText.toString();
        }
        throw new IllegalStateException("No transcript.text.done event found in StepFun STT response");
    }
}
