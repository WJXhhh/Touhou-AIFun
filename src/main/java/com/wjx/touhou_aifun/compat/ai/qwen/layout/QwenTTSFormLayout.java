package com.wjx.touhou_aifun.compat.ai.qwen.layout;

import com.github.tartaricacid.touhoulittlemaid.ai.service.tts.TTSSite;
import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.layout.FieldDescriptor;
import net.minecraft.network.chat.Component;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.Nullable;
import com.wjx.touhou_aifun.compat.ai.qwen.tts.QwenTTSSite;
import com.wjx.touhou_aifun.compat.ai.qwen.tts.QwenVoiceEnrollment;
import com.wjx.touhou_aifun.compat.ai.tts.VoicePresetSpec;
import com.wjx.touhou_aifun.compat.ai.tts.layout.CustomVoiceTTSFormLayout;

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

public class QwenTTSFormLayout extends CustomVoiceTTSFormLayout {
    public QwenTTSFormLayout(TTSSite sourceSite) {
        super(sourceSite, StringUtils.EMPTY, StringUtils.EMPTY);
    }

    @Override
    public boolean supportsVoiceDesign() {
        return true;
    }

    @Override
    public List<FieldDescriptor> getFieldDescriptors() {
        QwenTTSSite site = (QwenTTSSite) this.sourceSite;
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
        return new LinkedHashMap<>(((QwenTTSSite) this.sourceSite).models());
    }

    @Override
    public @Nullable TTSSite buildSite(Function<String, String> fieldValues, Map<String, String> models,
                                       Consumer<Component> showStatus) {
        QwenTTSSite site = (QwenTTSSite) this.sourceSite;
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

        // Enroll any clone/design rows that have not been registered yet, then store the returned
        // voice id (resolvedValue) so synthesis can use it directly. Already-enrolled rows are kept
        // as-is to avoid creating a duplicate voice on every save.
        Map<String, String> outputModels = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : models.entrySet()) {
            VoicePresetSpec spec = VoicePresetSpec.decode(entry.getKey());
            if (spec.isDesign() && StringUtils.isBlank(spec.resolvedValue())) {
                if (StringUtils.isBlank(spec.value())) {
                    showStatus.accept(Component.literal("Qwen 声音设计需要填写音色描述"));
                    return null;
                }
                try {
                    String voiceId = QwenVoiceEnrollment.createDesign(url, secretKey, site.headers(), spec.value());
                    outputModels.put(spec.withResolvedValue(voiceId).encode(), entry.getValue());
                } catch (Exception e) {
                    showStatus.accept(Component.literal("Qwen 声音设计创建失败: " + e.getMessage()));
                    return null;
                }
            } else if (spec.isReference() && StringUtils.isBlank(spec.resolvedValue())) {
                if (StringUtils.isBlank(spec.value())) {
                    showStatus.accept(Component.literal("Qwen 声音复刻需要选择参考音频"));
                    return null;
                }
                try {
                    String voiceId = QwenVoiceEnrollment.createClone(url, secretKey, site.headers(), spec.value());
                    outputModels.put(spec.withResolvedValue(voiceId).encode(), entry.getValue());
                } catch (Exception e) {
                    showStatus.accept(Component.literal("Qwen 声音复刻创建失败: " + e.getMessage()));
                    return null;
                }
            } else {
                outputModels.put(entry.getKey(), entry.getValue());
            }
        }
        if (outputModels.isEmpty()) {
            showStatus.accept(MODEL_IS_EMPTY);
            return null;
        }
        return new QwenTTSSite(site.id(), site.icon(), url, site.enabled(), secretKey,
                site.headers(), outputModels);
    }
}
