package team.cfpa.touhoustepfun.compat.ai.mimo.layout;

import com.github.tartaricacid.touhoulittlemaid.ai.service.tts.TTSSite;
import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.layout.FieldDescriptor;
import net.minecraft.network.chat.Component;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.Nullable;
import team.cfpa.touhoustepfun.compat.ai.mimo.tts.MimoTTSSite;
import team.cfpa.touhoustepfun.compat.ai.tts.VoicePresetSpec;
import team.cfpa.touhoustepfun.compat.ai.tts.layout.CustomVoiceTTSFormLayout;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Base64;
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

public class MimoTTSFormLayout extends CustomVoiceTTSFormLayout {
    private static final String MODEL_MIMO_25_VOICE_DESIGN = "mimo-v2.5-tts-voicedesign:custom";
    private static final String MODEL_MIMO_25_VOICE_CLONE = "mimo-v2.5-tts-voiceclone:custom";
    private static final int MAX_VOICE_CLONE_FILE_BYTES = 1_000_000;

    public MimoTTSFormLayout(TTSSite sourceSite) {
        super(sourceSite, ((MimoTTSSite) sourceSite).voiceCloneRefAudioPath(),
                ((MimoTTSSite) sourceSite).voiceCloneRefText());
    }

    @Override
    public boolean supportsVoiceDesign() {
        return true;
    }

    @Override
    public List<FieldDescriptor> getFieldDescriptors() {
        MimoTTSSite site = (MimoTTSSite) this.sourceSite;
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
        MimoTTSSite site = (MimoTTSSite) this.sourceSite;
        Map<String, String> models = new LinkedHashMap<>(site.models());
        String designName = StringUtils.defaultIfBlank(models.remove(MODEL_MIMO_25_VOICE_DESIGN),
                "mimo-v2.5-tts-voicedesign / 自定义音色描述");
        String cloneName = StringUtils.defaultIfBlank(models.remove(MODEL_MIMO_25_VOICE_CLONE),
                "mimo-v2.5-tts-voiceclone / 自定义参考音色");
        if (StringUtils.isNotBlank(site.voiceDesignPrompt())
                && models.keySet().stream().map(VoicePresetSpec::decode).noneMatch(VoicePresetSpec::isDesign)) {
            models.put(VoicePresetSpec.design(site.voiceDesignPrompt()).encode(), designName);
        }
        if (StringUtils.isNotBlank(site.voiceCloneRefAudioPath())
                && models.keySet().stream().map(VoicePresetSpec::decode).noneMatch(VoicePresetSpec::isReference)) {
            models.put(VoicePresetSpec.reference(site.voiceCloneRefAudioPath(),
                    site.voiceCloneRefText(), StringUtils.EMPTY).encode(), cloneName);
        }
        return models;
    }

    @Override
    public @Nullable TTSSite buildSite(Function<String, String> fieldValues, Map<String, String> models, Consumer<Component> showStatus) {
        MimoTTSSite site = (MimoTTSSite) this.sourceSite;
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
        Map<String, String> outputModels = new LinkedHashMap<>(models);
        outputModels.remove(MODEL_MIMO_25_VOICE_DESIGN);
        outputModels.remove(MODEL_MIMO_25_VOICE_CLONE);
        for (String storedValue : outputModels.keySet()) {
            VoicePresetSpec spec = VoicePresetSpec.decode(storedValue);
            if (spec.isReference()) {
                try {
                    toDataUrl(spec.value());
                } catch (Exception e) {
                    showStatus.accept(Component.literal("MiMo 参考音频处理失败: " + e.getMessage()));
                    return null;
                }
            }
        }
        if (outputModels.isEmpty()) {
            showStatus.accept(MODEL_IS_EMPTY);
            return null;
        }
        return new MimoTTSSite(site.id(), site.icon(), url, site.enabled(), secretKey,
                StringUtils.EMPTY, StringUtils.EMPTY, StringUtils.EMPTY, StringUtils.EMPTY,
                site.headers(), outputModels);
    }

    private static String toDataUrl(String filePath) throws IOException {
        Path path;
        try {
            path = Path.of(filePath);
        } catch (InvalidPathException e) {
            throw new IOException("无效的文件路径");
        }
        if (!Files.isRegularFile(path)) {
            throw new IOException("文件不存在");
        }
        long size = Files.size(path);
        if (size > MAX_VOICE_CLONE_FILE_BYTES) {
            throw new IOException("文件过大，MiMo 自定义参考音色当前限制为 1000 KB，请换更短的 mp3/wav 样本");
        }
        String mimeType = detectMimeType(path);
        byte[] data = Files.readAllBytes(path);
        return "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(data);
    }

    private static String detectMimeType(Path path) throws IOException {
        String fileName = path.getFileName().toString().toLowerCase();
        if (fileName.endsWith(".mp3")) {
            return "audio/mpeg";
        }
        if (fileName.endsWith(".wav")) {
            return "audio/wav";
        }
        throw new IOException("仅支持 mp3 或 wav 参考音频");
    }
}
