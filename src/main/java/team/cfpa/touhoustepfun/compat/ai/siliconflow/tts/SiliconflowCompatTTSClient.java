package team.cfpa.touhoustepfun.compat.ai.siliconflow.tts;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.TTSCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.tts.TTSClient;
import com.github.tartaricacid.touhoulittlemaid.ai.service.tts.TTSConfig;
import com.github.tartaricacid.touhoulittlemaid.ai.service.tts.siliconflow.TTSSiliconflowRequest;
import com.google.common.net.HttpHeaders;
import com.google.common.net.MediaType;
import team.cfpa.touhoustepfun.compat.ai.tts.VoicePresetSpec;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class SiliconflowCompatTTSClient implements TTSClient {
    private static final Duration MAX_TIMEOUT = Duration.ofSeconds(20);

    private final HttpClient httpClient;
    private final SiliconflowCompatTTSSite site;

    public SiliconflowCompatTTSClient(HttpClient httpClient, SiliconflowCompatTTSSite site) {
        this.httpClient = httpClient;
        this.site = site;
    }

    @Override
    public void play(String message, TTSConfig config, TTSCallback callback) {
        URI url = URI.create(this.site.url());
        String apiKey = this.site.secretKey();
        String voice = VoicePresetSpec.decode(config.model()).runtimeValue();

        TTSSiliconflowRequest request = TTSSiliconflowRequest.create()
                .setInput(message).setModel(SiliconflowCompatTTSSite.VOICE_MODEL)
                .setVoice(voice);

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .header(HttpHeaders.CONTENT_TYPE, MediaType.JSON_UTF_8.toString())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(request)))
                .timeout(MAX_TIMEOUT).uri(url);

        this.site.headers().forEach(builder::header);
        HttpRequest httpRequest = builder.build();

        httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofByteArray())
                .whenComplete((response, throwable) ->
                        handleResponse(callback, response, throwable, httpRequest));
    }
}
