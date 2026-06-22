package team.cfpa.touhoustepfun.compat.ai.minimax.layout;

import com.github.tartaricacid.touhoulittlemaid.ai.service.tts.TTSSite;
import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.layout.FieldDescriptor;
import com.google.gson.JsonObject;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.Nullable;
import team.cfpa.touhoustepfun.compat.ai.minimax.tts.MiniMaxCompatTTSSite;
import team.cfpa.touhoustepfun.compat.ai.tts.CustomVoiceHttpUtil;
import team.cfpa.touhoustepfun.compat.ai.tts.VoicePresetSpec;
import team.cfpa.touhoustepfun.compat.ai.tts.layout.CustomVoiceTTSFormLayout;

import java.net.URI;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.FormField.MODEL;
import static com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.FormField.SECRET_KEY;
import static com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.FormField.URL;
import static com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.Translations.MODEL_IS_EMPTY;
import static com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.Translations.SECRET_KEY_IS_EMPTY;
import static com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.Translations.URL_IS_EMPTY;
import static com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.Translations.VOICE_IS_EMPTY;
import static com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.Translations.VOICES_NAME;

public class MiniMaxTTSFormLayout extends CustomVoiceTTSFormLayout {
    private static final String CUSTOM_MODEL_PLACEHOLDER = "__custom_minimax_voice__";
    private static final String VOICE_DESIGN_PREVIEW_TEXT = "你好，我是为女仆设计的新声音，很高兴认识你。";

    public MiniMaxTTSFormLayout(TTSSite sourceSite) {
        super(sourceSite, ((MiniMaxCompatTTSSite) sourceSite).customVoiceRefAudioPath(),
                ((MiniMaxCompatTTSSite) sourceSite).customVoiceRefText());
    }

    @Override
    public boolean supportsVoiceDesign() {
        return true;
    }

    @Override
    public List<FieldDescriptor> getFieldDescriptors() {
        MiniMaxCompatTTSSite site = (MiniMaxCompatTTSSite) this.sourceSite;
        return List.of(
                new FieldDescriptor(URL, site.url(), true, false),
                new FieldDescriptor(SECRET_KEY, site.secretKey(), true, true),
                new FieldDescriptor(MODEL, site.siteModel(), true, false)
        );
    }

    @Override
    public boolean supportsModelRows() {
        return true;
    }

    @Override
    public Map<String, String> getInitialModels() {
        MiniMaxCompatTTSSite site = (MiniMaxCompatTTSSite) this.sourceSite;
        Map<String, String> models = new LinkedHashMap<>(site.models());
        if (StringUtils.isNotBlank(site.customVoiceId()) && StringUtils.isNotBlank(site.customVoiceRefAudioPath())
                && models.keySet().stream().map(VoicePresetSpec::decode).noneMatch(VoicePresetSpec::isReference)) {
            String name = StringUtils.defaultIfBlank(models.remove(site.customVoiceId()), "MiniMax / 自定义复刻音色");
            VoicePresetSpec legacy = VoicePresetSpec.reference(site.customVoiceRefAudioPath(),
                    site.customVoiceRefText(), site.customVoiceId());
            models.put(legacy.encode(), name);
        }
        return models;
    }

    @Override
    public MutableComponent modelsTitle() {
        return VOICES_NAME;
    }

    @Override
    public @Nullable TTSSite buildSite(Function<String, String> fieldValues, Map<String, String> models,
                                       Consumer<Component> showStatus) {
        MiniMaxCompatTTSSite site = (MiniMaxCompatTTSSite) this.sourceSite;
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
        String siteModel = StringUtils.trimToEmpty(fieldValues.apply(MODEL));
        if (StringUtils.isBlank(siteModel)) {
            showStatus.accept(MODEL_IS_EMPTY);
            return null;
        }

        Map<String, String> outputModels = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : models.entrySet()) {
            VoicePresetSpec spec = VoicePresetSpec.decode(entry.getKey());
            if (spec.isDesign()) {
                String prompt = StringUtils.trimToEmpty(spec.value());
                if (StringUtils.isBlank(prompt)) {
                    continue;
                }
                String designedVoiceId = spec.resolvedValue();
                if (StringUtils.isBlank(designedVoiceId)) {
                    try {
                        designedVoiceId = createDesignedVoice(url, secretKey, site.headers(), prompt);
                    } catch (Exception e) {
                        showStatus.accept(Component.literal("MiniMax Voice Design 创建失败: " + e.getMessage()));
                        return null;
                    }
                }
                outputModels.put(spec.withResolvedValue(designedVoiceId).encode(), entry.getValue());
            } else if (spec.isReference()) {
                String refAudioPath = StringUtils.trimToEmpty(spec.value());
                String refText = StringUtils.trimToEmpty(spec.referenceText());
                if (StringUtils.isBlank(refAudioPath)) {
                    continue;
                }
                if (StringUtils.isBlank(refText)) {
                    showStatus.accept(Component.literal("MiniMax 自定义音色需要填写参考音频对应文本"));
                    return null;
                }
                String customVoiceId = spec.resolvedValue();
                if (StringUtils.isBlank(customVoiceId)) {
                    try {
                        customVoiceId = createCustomVoice(url, secretKey, site.headers(), refAudioPath, refText, siteModel);
                    } catch (Exception e) {
                        showStatus.accept(Component.literal("MiniMax 自定义音色创建失败: " + e.getMessage()));
                        return null;
                    }
                }
                outputModels.put(spec.withResolvedValue(customVoiceId).encode(), entry.getValue());
            } else {
                outputModels.put(entry.getKey(), entry.getValue());
            }
        }
        outputModels.remove(CUSTOM_MODEL_PLACEHOLDER);
        if (outputModels.isEmpty()) {
            showStatus.accept(VOICE_IS_EMPTY);
            return null;
        }

        return new MiniMaxCompatTTSSite(site.id(), site.icon(), url, site.enabled(), secretKey,
                siteModel, StringUtils.EMPTY, StringUtils.EMPTY, StringUtils.EMPTY, site.headers(), outputModels);
    }

    private static String createDesignedVoice(String configuredUrl, String secretKey, Map<String, String> headers,
                                              String prompt) throws Exception {
        JsonObject payload = new JsonObject();
        payload.addProperty("prompt", prompt);
        payload.addProperty("preview_text", VOICE_DESIGN_PREVIEW_TEXT);
        payload.addProperty("voice_id", CustomVoiceHttpUtil.newManagedVoiceId("tlm_minimax_design"));
        payload.addProperty("aigc_watermark", false);

        URI designUri = CustomVoiceHttpUtil.replacePath(configuredUrl, "/v1/voice_design");
        var response = CustomVoiceHttpUtil.postJson(designUri, secretKey, headers, payload);
        JsonObject responseJson = CustomVoiceHttpUtil.requireSuccessJson(response, "创建 MiniMax Voice Design 音色");
        return CustomVoiceHttpUtil.requireString(responseJson, "voice_id", "创建 MiniMax Voice Design 音色");
    }

    private static String createCustomVoice(String configuredUrl, String secretKey, Map<String, String> headers,
                                            String refAudioPath, String refText, String siteModel) throws Exception {
        Path path = CustomVoiceHttpUtil.requireAudioFile(refAudioPath, "MiniMax 参考音频", ".wav", ".mp3", ".m4a");
        URI uploadUri = CustomVoiceHttpUtil.replacePath(configuredUrl, "/v1/files/upload");
        var uploadBody = CustomVoiceHttpUtil.newFileUploadBody("file", path)
                .addText("purpose", "voice_clone");
        var uploadResponse = CustomVoiceHttpUtil.postMultipart(uploadUri, secretKey, headers, uploadBody);
        JsonObject uploadJson = CustomVoiceHttpUtil.requireSuccessJson(uploadResponse, "上传 MiniMax 克隆音频");
        String fileId = CustomVoiceHttpUtil.requireNestedString(uploadJson, "上传 MiniMax 克隆音频", "file", "file_id");

        String voiceId = CustomVoiceHttpUtil.newManagedVoiceId("tlm_minimax");
        JsonObject payload = new JsonObject();
        payload.addProperty("file_id", fileId);
        payload.addProperty("voice_id", voiceId);
        payload.addProperty("text", refText);
        payload.addProperty("model", siteModel);
        payload.addProperty("accuracy", 0.7);
        payload.addProperty("need_noise_reduction", false);
        payload.addProperty("need_volume_normalization", false);
        payload.addProperty("aigc_watermark", false);

        URI cloneUri = CustomVoiceHttpUtil.replacePath(configuredUrl, "/v1/voice_clone");
        var cloneResponse = CustomVoiceHttpUtil.postJson(cloneUri, secretKey, headers, payload);
        CustomVoiceHttpUtil.requireSuccessJson(cloneResponse, "创建 MiniMax 自定义音色");
        return voiceId;
    }
}
