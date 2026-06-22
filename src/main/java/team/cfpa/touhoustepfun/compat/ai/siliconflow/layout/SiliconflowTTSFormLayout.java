package team.cfpa.touhoustepfun.compat.ai.siliconflow.layout;

import com.github.tartaricacid.touhoulittlemaid.ai.service.tts.TTSSite;
import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.layout.FieldDescriptor;
import com.google.gson.JsonObject;
import net.minecraft.network.chat.Component;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.Nullable;
import team.cfpa.touhoustepfun.compat.ai.siliconflow.tts.SiliconflowCompatTTSSite;
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

import static com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.FormField.SECRET_KEY;
import static com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.FormField.URL;
import static com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.Translations.MODEL_IS_EMPTY;
import static com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.Translations.SECRET_KEY_IS_EMPTY;
import static com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.Translations.URL_IS_EMPTY;

public class SiliconflowTTSFormLayout extends CustomVoiceTTSFormLayout {
    private static final String CUSTOM_MODEL_PLACEHOLDER = "__custom_siliconflow_voice__";

    public SiliconflowTTSFormLayout(TTSSite sourceSite) {
        super(sourceSite, ((SiliconflowCompatTTSSite) sourceSite).customVoiceRefAudioPath(),
                ((SiliconflowCompatTTSSite) sourceSite).customVoiceRefText());
    }

    @Override
    public List<FieldDescriptor> getFieldDescriptors() {
        SiliconflowCompatTTSSite site = (SiliconflowCompatTTSSite) this.sourceSite;
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
    public Map<String, String> getInitialModels() {
        SiliconflowCompatTTSSite site = (SiliconflowCompatTTSSite) this.sourceSite;
        Map<String, String> models = new LinkedHashMap<>(site.models());
        if (StringUtils.isNotBlank(site.customVoiceUri()) && StringUtils.isNotBlank(site.customVoiceRefAudioPath())
                && models.keySet().stream().map(VoicePresetSpec::decode).noneMatch(VoicePresetSpec::isReference)) {
            String name = StringUtils.defaultIfBlank(models.remove(site.customVoiceUri()), "SiliconFlow / 自定义复刻音色");
            VoicePresetSpec legacy = VoicePresetSpec.reference(site.customVoiceRefAudioPath(),
                    site.customVoiceRefText(), site.customVoiceUri());
            models.put(legacy.encode(), name);
        }
        return models;
    }

    @Override
    public @Nullable TTSSite buildSite(Function<String, String> fieldValues, Map<String, String> models,
                                       Consumer<Component> showStatus) {
        SiliconflowCompatTTSSite site = (SiliconflowCompatTTSSite) this.sourceSite;
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
                String refText = StringUtils.trimToEmpty(spec.referenceText());
                if (StringUtils.isBlank(refAudioPath)) {
                    continue;
                }
                if (StringUtils.isBlank(refText)) {
                    showStatus.accept(Component.literal("SiliconFlow 自定义音色需要填写参考音频对应文本"));
                    return null;
                }
                String customVoiceUri = spec.resolvedValue();
                if (StringUtils.isBlank(customVoiceUri)) {
                    try {
                        customVoiceUri = createCustomVoice(url, secretKey, site.headers(), refAudioPath, refText);
                    } catch (Exception e) {
                        showStatus.accept(Component.literal("SiliconFlow 自定义音色创建失败: " + e.getMessage()));
                        return null;
                    }
                }
                outputModels.put(spec.withResolvedValue(customVoiceUri).encode(), entry.getValue());
            } else {
                outputModels.put(entry.getKey(), entry.getValue());
            }
        }
        outputModels.remove(CUSTOM_MODEL_PLACEHOLDER);
        if (outputModels.isEmpty()) {
            showStatus.accept(MODEL_IS_EMPTY);
            return null;
        }

        return new SiliconflowCompatTTSSite(site.id(), site.icon(), url, site.enabled(), secretKey,
                StringUtils.EMPTY, StringUtils.EMPTY, StringUtils.EMPTY, site.headers(), outputModels);
    }

    private static String createCustomVoice(String configuredUrl, String secretKey, Map<String, String> headers,
                                            String refAudioPath, String refText) throws Exception {
        Path path = CustomVoiceHttpUtil.requireAudioFile(refAudioPath, "SiliconFlow 参考音频", ".wav", ".mp3");
        var body = CustomVoiceHttpUtil.newFileUploadBody("file", path)
                .addText("model", SiliconflowCompatTTSSite.VOICE_MODEL)
                .addText("customName", CustomVoiceHttpUtil.newManagedVoiceId("tlm_siliconflow"))
                .addText("text", refText);
        URI uploadUri = CustomVoiceHttpUtil.replacePath(configuredUrl, "/v1/uploads/audio/voice");
        var response = CustomVoiceHttpUtil.postMultipart(uploadUri, secretKey, headers, body);
        var json = CustomVoiceHttpUtil.requireSuccessJson(response, "创建 SiliconFlow 自定义音色");
        return CustomVoiceHttpUtil.requireString(json, "uri", "创建 SiliconFlow 自定义音色");
    }
}
