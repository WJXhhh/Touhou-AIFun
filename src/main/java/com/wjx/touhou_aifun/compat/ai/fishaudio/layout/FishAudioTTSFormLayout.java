package com.wjx.touhou_aifun.compat.ai.fishaudio.layout;

import com.github.tartaricacid.touhoulittlemaid.ai.service.tts.TTSSite;
import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.layout.FieldDescriptor;
import net.minecraft.network.chat.Component;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.Nullable;
import com.wjx.touhou_aifun.compat.ai.fishaudio.tts.FishAudioCompatTTSSite;
import com.wjx.touhou_aifun.compat.ai.tts.CustomVoiceHttpUtil;
import com.wjx.touhou_aifun.compat.ai.tts.VoicePresetSpec;
import com.wjx.touhou_aifun.compat.ai.tts.layout.CustomVoiceTTSFormLayout;

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

public class FishAudioTTSFormLayout extends CustomVoiceTTSFormLayout {
    private static final String CUSTOM_MODEL_PLACEHOLDER = "__custom_fish_audio_voice__";

    public FishAudioTTSFormLayout(TTSSite sourceSite) {
        super(sourceSite, ((FishAudioCompatTTSSite) sourceSite).customVoiceRefAudioPath(),
                ((FishAudioCompatTTSSite) sourceSite).customVoiceRefText());
    }

    @Override
    public List<FieldDescriptor> getFieldDescriptors() {
        FishAudioCompatTTSSite site = (FishAudioCompatTTSSite) this.sourceSite;
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
        FishAudioCompatTTSSite site = (FishAudioCompatTTSSite) this.sourceSite;
        Map<String, String> models = new LinkedHashMap<>(site.models());
        if (StringUtils.isNotBlank(site.customVoiceId()) && StringUtils.isNotBlank(site.customVoiceRefAudioPath())
                && models.keySet().stream().map(VoicePresetSpec::decode).noneMatch(VoicePresetSpec::isReference)) {
            String name = StringUtils.defaultIfBlank(models.remove(site.customVoiceId()), "Fish Audio / 自定义复刻音色");
            VoicePresetSpec legacy = VoicePresetSpec.reference(site.customVoiceRefAudioPath(),
                    site.customVoiceRefText(), site.customVoiceId());
            models.put(legacy.encode(), name);
        }
        return models;
    }

    @Override
    public @Nullable TTSSite buildSite(Function<String, String> fieldValues, Map<String, String> models,
                                       Consumer<Component> showStatus) {
        FishAudioCompatTTSSite site = (FishAudioCompatTTSSite) this.sourceSite;
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
                String customVoiceId = spec.resolvedValue();
                if (StringUtils.isBlank(customVoiceId)) {
                    String refAudioPath = StringUtils.trimToEmpty(spec.value());
                    String refText = StringUtils.trimToEmpty(spec.referenceText());
                    if (StringUtils.isBlank(refAudioPath)) {
                        continue;
                    }
                    try {
                        customVoiceId = createCustomVoice(url, secretKey, site.headers(), refAudioPath, refText, entry.getValue());
                    } catch (Exception e) {
                        showStatus.accept(Component.literal("Fish Audio 自定义音色创建失败: " + e.getMessage()));
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
            showStatus.accept(MODEL_IS_EMPTY);
            return null;
        }

        return new FishAudioCompatTTSSite(site.id(), site.icon(), url, site.enabled(), secretKey,
                StringUtils.EMPTY, StringUtils.EMPTY, StringUtils.EMPTY, site.headers(), outputModels);
    }

    private static String createCustomVoice(String configuredUrl, String secretKey, Map<String, String> headers,
                                            String refAudioPath, String refText, String displayName) throws Exception {
        Path path = CustomVoiceHttpUtil.requireAudioFile(refAudioPath, "Fish Audio 参考音频", ".wav", ".mp3");
        var body = CustomVoiceHttpUtil.newFileUploadBody("voices", path)
                .addText("type", "tts")
                .addText("title", StringUtils.defaultIfBlank(displayName, "TouhouLittleMaid Custom Voice"))
                .addText("train_mode", "fast")
                .addText("visibility", "private")
                .addText("enhance_audio_quality", "true")
                .addText("generate_sample", "false");
        if (StringUtils.isNotBlank(refText)) {
            body.addText("texts", refText);
        }
        URI createModelUri = CustomVoiceHttpUtil.replacePath(configuredUrl, "/model");
        var response = CustomVoiceHttpUtil.postMultipart(createModelUri, secretKey, headers, body);
        var json = CustomVoiceHttpUtil.requireSuccessJson(response, "创建 Fish Audio 自定义音色");
        return CustomVoiceHttpUtil.requireString(json, "_id", "创建 Fish Audio 自定义音色");
    }
}
