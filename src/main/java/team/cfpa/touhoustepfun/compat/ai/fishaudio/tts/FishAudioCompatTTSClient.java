package team.cfpa.touhoustepfun.compat.ai.fishaudio.tts;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.TTSCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.tts.Format;
import com.github.tartaricacid.touhoulittlemaid.ai.service.tts.TTSClient;
import com.github.tartaricacid.touhoulittlemaid.ai.service.tts.TTSConfig;
import com.github.tartaricacid.touhoulittlemaid.ai.service.tts.fishaudio.request.OpusBitRate;
import com.github.tartaricacid.touhoulittlemaid.ai.service.tts.fishaudio.request.TTSFishAudioRequest;
import com.google.common.net.HttpHeaders;
import com.google.common.net.MediaType;
import team.cfpa.touhoustepfun.compat.ai.tts.VoicePresetSpec;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class FishAudioCompatTTSClient implements TTSClient {
    private static final Duration MAX_TIMEOUT = Duration.ofSeconds(20);

    private final HttpClient httpClient;
    private final FishAudioCompatTTSSite site;

    public FishAudioCompatTTSClient(HttpClient httpClient, FishAudioCompatTTSSite site) {
        this.httpClient = httpClient;
        this.site = site;
    }

    @Override
    public void play(String message, TTSConfig config, TTSCallback callback) {
        URI url = URI.create(this.site.url());
        String apiKey = this.site.secretKey();
        String model = VoicePresetSpec.decode(config.model()).runtimeValue();

        TTSFishAudioRequest request = TTSFishAudioRequest.create()
                .setReferenceId(model)
                .setFormat(Format.OPUS)
                .setOpusBitrate(OpusBitRate.LOWEST)
                .setText(message);

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .header(HttpHeaders.CONTENT_TYPE, MediaType.JSON_UTF_8.toString())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(request)))
                .timeout(MAX_TIMEOUT).uri(url);

        if (!this.site.headers().containsKey("model")) {
            builder.header("model", "s2-pro");
        }
        this.site.headers().forEach(builder::header);
        HttpRequest httpRequest = builder.build();

        httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofByteArray())
                .whenComplete((response, throwable) ->
                        handleResponse(callback, response, throwable, httpRequest));
    }
}
