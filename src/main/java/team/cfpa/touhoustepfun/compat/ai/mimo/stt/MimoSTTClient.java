package team.cfpa.touhoustepfun.compat.ai.mimo.stt;

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
import team.cfpa.touhoustepfun.compat.ai.mimo.MimoEndpointResolver;
import team.cfpa.touhoustepfun.compat.ai.mimo.MimoShared;
import team.cfpa.touhoustepfun.compat.ai.mimo.response.MimoChatCompletionResponse;
import team.cfpa.touhoustepfun.compat.ai.mimo.response.MimoMessage;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.Mixer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

public class MimoSTTClient implements STTClient {
    private static final AudioFormat FORMAT = new AudioFormat(16000, 16, 1, true, false);
    private static final Duration MAX_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient httpClient;
    private final MimoSTTSite site;

    public MimoSTTClient(HttpClient httpClient, MimoSTTSite site) {
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
        URI uri = MimoEndpointResolver.resolve(this.site.url(), this.site.getSecretKey());

        String apiKey = MimoShared.resolveSecretKey(this.site.getSecretKey());
        if (apiKey == null) {
            callback.onFailure(null, new IllegalArgumentException("MiMo API key is empty"), ErrorCode.REQUEST_SENDING_ERROR);
            return;
        }

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

        JsonObject asrOptions = new JsonObject();
        asrOptions.addProperty("language", "auto");

        JsonObject root = new JsonObject();
        root.addProperty("model", this.site.getModel());
        root.add("messages", messages);
        root.add("asr_options", asrOptions);
        root.addProperty("stream", false);
        return GSON.toJson(root);
    }

    private void handle(ResponseCallback<String> callback, HttpResponse<String> response, Throwable throwable, HttpRequest request) {
        this.<MimoChatCompletionResponse>handleResponse(callback, response, throwable, request, chat -> {
            MimoMessage message = chat.getFirstMessage();
            if (message == null || StringUtils.isBlank(message.getContent())) {
                throw new IllegalStateException("MiMo STT returned empty transcript");
            }
            callback.onSuccess(message.getContent());
        }, MimoChatCompletionResponse.class);
    }
}
