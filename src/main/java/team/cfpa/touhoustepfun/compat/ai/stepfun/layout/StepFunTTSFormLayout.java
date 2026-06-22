package team.cfpa.touhoustepfun.compat.ai.stepfun.layout;

import com.github.tartaricacid.touhoulittlemaid.ai.service.tts.TTSSite;
import com.github.tartaricacid.touhoulittlemaid.ai.service.Client;
import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.layout.FieldDescriptor;
import com.github.tartaricacid.touhoulittlemaid.util.http.MultipartBody;
import com.github.tartaricacid.touhoulittlemaid.util.http.MultipartBodyBuilder;
import com.google.common.net.HttpHeaders;
import com.google.common.net.MediaType;
import com.google.gson.JsonObject;
import net.minecraft.network.chat.Component;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.Nullable;
import team.cfpa.touhoustepfun.compat.ai.stepfun.tts.StepFunPlanTTSSite;
import team.cfpa.touhoustepfun.compat.ai.stepfun.tts.StepFunTTSSite;
import team.cfpa.touhoustepfun.compat.ai.tts.VoicePresetSpec;
import team.cfpa.touhoustepfun.compat.ai.tts.layout.CustomVoiceTTSFormLayout;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.FormField.SECRET_KEY;
import static com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.FormField.URL;
import static com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.Translations.MODEL_IS_EMPTY;
import static com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.Translations.SECRET_KEY_IS_EMPTY;
import static com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.Translations.URL_IS_EMPTY;

public class StepFunTTSFormLayout extends CustomVoiceTTSFormLayout {
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    private static final String CLONE_MODEL_ID = "stepaudio-2.5-tts:%s";
    private static final String CUSTOM_MODEL_PLACEHOLDER = "stepaudio-2.5-tts:__custom_voice__";

    public StepFunTTSFormLayout(TTSSite sourceSite) {
        super(sourceSite, ((StepFunTTSSite) sourceSite).cloneRefAudioPath(),
                ((StepFunTTSSite) sourceSite).cloneRefText());
    }

    @Override
    public List<FieldDescriptor> getFieldDescriptors() {
        StepFunTTSSite site = (StepFunTTSSite) this.sourceSite;
        return List.of(
                new FieldDescriptor(URL, site.url(), true, false),
                new FieldDescriptor(SECRET_KEY, site.secretKey(), true, true)
        );
    }

    @Override
    public boolean supportsModelRows() {
        return true;
    }

    @Override
    public boolean supportsSynthesisInstruction() {
        return true;
    }

    @Override
    public Map<String, String> getInitialModels() {
        StepFunTTSSite site = (StepFunTTSSite) this.sourceSite;
        Map<String, String> models = new LinkedHashMap<>(site.models());
        if (isStepPlanSite(site, site.url())) {
            models.entrySet().removeIf(entry -> {
                VoicePresetSpec spec = VoicePresetSpec.decode(entry.getKey());
                return spec.mode() == VoicePresetSpec.Mode.DIRECT_ID
                        && !StepFunPlanTTSSite.isSupportedDirectValue(spec.runtimeValue());
            });
        }
        String[] legacyName = {"stepaudio-2.5-tts / 自定义复刻音色"};
        models.entrySet().removeIf(entry -> {
            boolean isStoredLegacyVoice = StringUtils.isNotBlank(site.cloneVoiceId())
                    && CLONE_MODEL_ID.formatted(site.cloneVoiceId()).equals(entry.getKey());
            if (isManagedCustomVoice(entry.getKey()) || isStoredLegacyVoice) {
                legacyName[0] = entry.getValue();
                return true;
            }
            return false;
        });
        if (StringUtils.isNotBlank(site.cloneRefAudioPath())
                && models.keySet().stream().map(VoicePresetSpec::decode).noneMatch(VoicePresetSpec::isReference)) {
            String resolved = StringUtils.isBlank(site.cloneVoiceId())
                    ? StringUtils.EMPTY : CLONE_MODEL_ID.formatted(site.cloneVoiceId());
            VoicePresetSpec legacy = VoicePresetSpec.reference(site.cloneRefAudioPath(), site.cloneRefText(), resolved);
            models.put(legacy.encode(), legacyName[0]);
        }
        return models;
    }

    @Override
    public @Nullable TTSSite buildSite(Function<String, String> fieldValues, Map<String, String> models, Consumer<Component> showStatus) {
        StepFunTTSSite site = (StepFunTTSSite) this.sourceSite;
        String url = StringUtils.trimToEmpty(fieldValues.apply(URL));
        if (StringUtils.isBlank(url)) {
            showStatus.accept(URL_IS_EMPTY);
            return null;
        }
        String secretKey = StringUtils.trimToEmpty(fieldValues.apply(SECRET_KEY));
        if (StringUtils.isBlank(secretKey)) {
            showStatus.accept(SECRET_KEY_IS_EMPTY);
            return null;
        }
        Map<String, String> outputModels = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : models.entrySet()) {
            VoicePresetSpec spec = VoicePresetSpec.decode(entry.getKey());
            if (spec.isReference()) {
                String refAudioPath = StringUtils.trimToEmpty(spec.value());
                if (StringUtils.isBlank(refAudioPath)) {
                    continue;
                }
                String runtimeModel = spec.resolvedValue();
                if (StringUtils.isBlank(runtimeModel)) {
                    try {
                        String voiceId = createCloneVoice(url, secretKey, site.headers(), refAudioPath, spec.referenceText());
                        runtimeModel = CLONE_MODEL_ID.formatted(voiceId);
                    } catch (Exception e) {
                        showStatus.accept(Component.literal("阶跃音色复刻失败: " + e.getMessage()));
                        return null;
                    }
                }
                outputModels.put(spec.withResolvedValue(runtimeModel).encode(), entry.getValue());
            } else if (!isManagedCustomVoice(entry.getKey())) {
                outputModels.put(entry.getKey(), entry.getValue());
            }
        }

        if (outputModels.isEmpty()) {
            showStatus.accept(MODEL_IS_EMPTY);
            return null;
        }
        if (isStepPlanSite(site, url)) {
            return new StepFunPlanTTSSite(site.id(), site.icon(), url, site.enabled(), secretKey,
                    StringUtils.EMPTY, StringUtils.EMPTY, StringUtils.EMPTY, site.headers(), outputModels);
        }
        return new StepFunTTSSite(site.id(), site.icon(), url, site.enabled(), secretKey,
                StringUtils.EMPTY, StringUtils.EMPTY, StringUtils.EMPTY, site.headers(), outputModels);
    }

    private static boolean isStepPlanSite(StepFunTTSSite site, String url) {
        return site instanceof StepFunPlanTTSSite || StepFunPlanTTSSite.API_TYPE.equals(site.id())
                || StringUtils.defaultString(url).contains("/step_plan/");
    }

    private static boolean isManagedCustomVoice(String modelId) {
        return CUSTOM_MODEL_PLACEHOLDER.equals(modelId)
                || modelId.startsWith("stepaudio-2.5-tts:voice-tone-");
    }

    private static String createCloneVoice(String speechUrl, String secretKey, Map<String, String> headers,
                                           String refAudioPath, String refText) throws Exception {
        Path path;
        try {
            path = Path.of(refAudioPath);
        } catch (InvalidPathException e) {
            throw new IOException("无效的参考音频路径");
        }
        if (!Files.isRegularFile(path)) {
            throw new IOException("参考音频文件不存在");
        }
        String fileName = path.getFileName().toString().toLowerCase();
        if (!(fileName.endsWith(".mp3") || fileName.endsWith(".wav"))) {
            throw new IOException("阶跃仅支持 mp3 或 wav 参考音频");
        }

        URI uploadUri = rewritePath(speechUrl, "/audio/speech", "/files");
        MultipartBody multipartBody = new MultipartBodyBuilder()
                .addText("purpose", "storage")
                .addPart("file", new File(path.toString()), MediaType.OCTET_STREAM.toString(), path.getFileName().toString())
                .build();
        HttpRequest.Builder uploadBuilder = HttpRequest.newBuilder(uploadUri)
                .header(HttpHeaders.CONTENT_TYPE, multipartBody.getContentType())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + secretKey)
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipartBody.getBody()))
                .timeout(Duration.ofSeconds(60));
        headers.forEach(uploadBuilder::header);
        HttpResponse<String> uploadResponse = HTTP_CLIENT.send(uploadBuilder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (uploadResponse.statusCode() / 100 != 2) {
            throw new IOException("上传文件失败: " + uploadResponse.statusCode() + " " + uploadResponse.body());
        }
        JsonObject uploadJson = Client.GSON.fromJson(uploadResponse.body(), JsonObject.class);
        if (uploadJson == null || !uploadJson.has("id")) {
            throw new IOException("上传文件后未返回 file id");
        }

        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("file_id", uploadJson.get("id").getAsString());
        requestBody.addProperty("model", "stepaudio-2.5-tts");
        if (StringUtils.isNotBlank(refText)) {
            requestBody.addProperty("text", refText);
        }

        URI cloneUri = rewritePath(speechUrl, "/audio/speech", "/audio/voices");
        HttpRequest.Builder cloneBuilder = HttpRequest.newBuilder(cloneUri)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.JSON_UTF_8.toString())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + secretKey)
                .POST(HttpRequest.BodyPublishers.ofString(Client.GSON.toJson(requestBody), StandardCharsets.UTF_8))
                .timeout(Duration.ofSeconds(60));
        headers.forEach(cloneBuilder::header);
        HttpResponse<String> cloneResponse = HTTP_CLIENT.send(cloneBuilder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (cloneResponse.statusCode() / 100 != 2) {
            throw new IOException("创建复刻音色失败: " + cloneResponse.statusCode() + " " + cloneResponse.body());
        }
        JsonObject cloneJson = Client.GSON.fromJson(cloneResponse.body(), JsonObject.class);
        if (cloneJson == null || !cloneJson.has("id")) {
            throw new IOException("复刻音色后未返回 voice id");
        }
        return cloneJson.get("id").getAsString();
    }

    private static URI rewritePath(String configuredUrl, String sourceSuffix, String targetSuffix) throws URISyntaxException {
        URI uri = URI.create(configuredUrl);
        String path = uri.getPath();
        if (path == null) {
            path = "";
        }
        if (path.endsWith(sourceSuffix)) {
            path = path.substring(0, path.length() - sourceSuffix.length()) + targetSuffix;
        } else {
            path = path.replace("/v1/realtime/audio", "/v1" + targetSuffix)
                    .replace("/step_plan/v1/realtime/audio", "/step_plan/v1" + targetSuffix);
            if (!path.endsWith(targetSuffix)) {
                path = targetSuffix.startsWith("/") ? targetSuffix : "/" + targetSuffix;
            }
        }
        return new URI(uri.getScheme(), uri.getUserInfo(), uri.getHost(), uri.getPort(), path, null, null);
    }
}
