package com.wjx.touhou_aifun.compat.ai.stepfun.tts;

import com.github.tartaricacid.touhoulittlemaid.ai.service.SerializableSite;
import com.github.tartaricacid.touhoulittlemaid.ai.service.SupportModelSelect;
import com.github.tartaricacid.touhoulittlemaid.ai.service.tts.TTSClient;
import com.github.tartaricacid.touhoulittlemaid.ai.service.tts.TTSSite;
import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.layout.TTSSiteFormLayout;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;
import org.apache.commons.lang3.StringUtils;
import com.wjx.touhou_aifun.compat.ai.stepfun.StepFunShared;
import com.wjx.touhou_aifun.compat.ai.stepfun.layout.StepFunTTSFormLayout;

import java.util.LinkedHashMap;
import java.util.Map;

public class StepFunTTSSite implements TTSSite, SupportModelSelect {
    public static final String API_TYPE = StepFunShared.API_TYPE;

    private final String id;
    private final ResourceLocation icon;
    private final Map<String, String> headers;
    private final Map<String, String> models;

    private String url;
    private boolean enabled;
    private String secretKey;
    private String cloneRefAudioPath;
    private String cloneRefText;
    private String cloneVoiceId;

    public StepFunTTSSite(String id, ResourceLocation icon, String url, boolean enabled,
                          String secretKey, String cloneRefAudioPath, String cloneRefText,
                          String cloneVoiceId, Map<String, String> headers, Map<String, String> models) {
        this.id = id;
        this.icon = icon;
        this.url = url;
        this.enabled = enabled;
        this.secretKey = secretKey;
        this.cloneRefAudioPath = cloneRefAudioPath;
        this.cloneRefText = cloneRefText;
        this.cloneVoiceId = cloneVoiceId;
        this.headers = headers;
        this.models = models;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public ResourceLocation icon() {
        return icon;
    }

    @Override
    public String url() {
        return url;
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    public String secretKey() {
        return secretKey;
    }

    public String cloneRefAudioPath() {
        return cloneRefAudioPath;
    }

    public String cloneRefText() {
        return cloneRefText;
    }

    public String cloneVoiceId() {
        return cloneVoiceId;
    }

    @Override
    public Map<String, String> headers() {
        return headers;
    }

    @Override
    public Map<String, String> models() {
        return models;
    }

    @Override
    public String getApiType() {
        return API_TYPE;
    }

    @Override
    public TTSClient client() {
        return new StepFunTTSClient(TTS_HTTP_CLIENT, this);
    }

    @Override
    public TTSSiteFormLayout formLayout() {
        return new StepFunTTSFormLayout(this);
    }

    public void setUrl(String url) {
        this.url = url;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public static final class Serializer implements SerializableSite<StepFunTTSSite> {
        private static final String MODEL_STEP_TTS_MINI = "step-tts-mini";
        private static final String MODEL_STEP_TTS_2 = "step-tts-2";
        private static final String MODEL_STEPAUDIO_25_TTS = "stepaudio-2.5-tts";
        private static final Codec<StepFunTTSSite> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf(ID).forGetter(StepFunTTSSite::id),
                ResourceLocation.CODEC.fieldOf(ICON).forGetter(StepFunTTSSite::icon),
                Codec.STRING.fieldOf(URL).forGetter(StepFunTTSSite::url),
                Codec.BOOL.fieldOf(ENABLED).forGetter(StepFunTTSSite::enabled),
                Codec.STRING.fieldOf(SECRET_KEY).forGetter(StepFunTTSSite::secretKey),
                Codec.STRING.optionalFieldOf("clone_ref_audio_path", StringUtils.EMPTY).forGetter(StepFunTTSSite::cloneRefAudioPath),
                Codec.STRING.optionalFieldOf("clone_ref_text", StringUtils.EMPTY).forGetter(StepFunTTSSite::cloneRefText),
                Codec.STRING.optionalFieldOf("clone_voice_id", StringUtils.EMPTY).forGetter(StepFunTTSSite::cloneVoiceId),
                Codec.unboundedMap(Codec.STRING, Codec.STRING).fieldOf(HEADERS).forGetter(StepFunTTSSite::headers),
                Codec.unboundedMap(Codec.STRING, Codec.STRING).fieldOf(MODELS).forGetter(StepFunTTSSite::models)
        ).apply(instance, StepFunTTSSite::new));

        @Override
        public StepFunTTSSite defaultSite() {
            Map<String, String> models = new LinkedHashMap<>();
            addVoice(models, "Vibrant Youth", "vibrant-youth", MODEL_STEPAUDIO_25_TTS, MODEL_STEP_TTS_2);
            addVoice(models, "Lively Girl", "lively-girl", MODEL_STEPAUDIO_25_TTS, MODEL_STEP_TTS_2);
            addVoice(models, "Soft-spoken Gentleman", "soft-spoken-gentleman", MODEL_STEPAUDIO_25_TTS, MODEL_STEP_TTS_2);
            addVoice(models, "Magnetic-voiced Male", "magnetic-voiced-male", MODEL_STEPAUDIO_25_TTS, MODEL_STEP_TTS_2);
            addVoice(models, "自信男声", "zixinnansheng", MODEL_STEPAUDIO_25_TTS, MODEL_STEP_TTS_2);
            addVoice(models, "气质温婉", "elegantgentle-female", MODEL_STEPAUDIO_25_TTS, MODEL_STEP_TTS_2, MODEL_STEP_TTS_MINI);
            addVoice(models, "活力轻快", "livelybreezy-female", MODEL_STEPAUDIO_25_TTS, MODEL_STEP_TTS_2, MODEL_STEP_TTS_MINI);
            addVoice(models, "温柔男声", "wenrounansheng", MODEL_STEPAUDIO_25_TTS, MODEL_STEP_TTS_2, MODEL_STEP_TTS_MINI);
            addVoice(models, "温柔公子", "wenrougongzi", MODEL_STEPAUDIO_25_TTS, MODEL_STEP_TTS_2, MODEL_STEP_TTS_MINI);
            addVoice(models, "元气男声", "yuanqinansheng", MODEL_STEPAUDIO_25_TTS, MODEL_STEP_TTS_2, MODEL_STEP_TTS_MINI);
            addVoice(models, "经典女声", "jingdiannvsheng", MODEL_STEPAUDIO_25_TTS, MODEL_STEP_TTS_2, MODEL_STEP_TTS_MINI);
            addVoice(models, "温柔熟女", "wenroushunv", MODEL_STEPAUDIO_25_TTS, MODEL_STEP_TTS_2, MODEL_STEP_TTS_MINI);
            addVoice(models, "甜美女声", "tianmeinvsheng", MODEL_STEPAUDIO_25_TTS, MODEL_STEP_TTS_2, MODEL_STEP_TTS_MINI);
            addVoice(models, "清纯少女", "qingchunshaonv", MODEL_STEPAUDIO_25_TTS, MODEL_STEP_TTS_2, MODEL_STEP_TTS_MINI);
            addVoice(models, "磁性男声", "cixingnansheng", MODEL_STEPAUDIO_25_TTS, MODEL_STEP_TTS_2, MODEL_STEP_TTS_MINI);
            addVoice(models, "元气少女", "yuanqishaonv", MODEL_STEPAUDIO_25_TTS, MODEL_STEP_TTS_2, MODEL_STEP_TTS_MINI);
            addVoice(models, "邻家姐姐", "linjiajiejie", MODEL_STEPAUDIO_25_TTS, MODEL_STEP_TTS_2, MODEL_STEP_TTS_MINI);
            addVoice(models, "正派青年", "zhengpaiqingnian", MODEL_STEPAUDIO_25_TTS, MODEL_STEP_TTS_2, MODEL_STEP_TTS_MINI);
            addVoice(models, "青年大学生", "qingniandaxuesheng", MODEL_STEPAUDIO_25_TTS, MODEL_STEP_TTS_2, MODEL_STEP_TTS_MINI);
            addVoice(models, "播音男声", "boyinnansheng", MODEL_STEPAUDIO_25_TTS, MODEL_STEP_TTS_2, MODEL_STEP_TTS_MINI);
            addVoice(models, "儒雅男士", "ruyananshi", MODEL_STEPAUDIO_25_TTS, MODEL_STEP_TTS_2, MODEL_STEP_TTS_MINI);
            addVoice(models, "深沉男音", "shenchennanyin", MODEL_STEPAUDIO_25_TTS, MODEL_STEP_TTS_2, MODEL_STEP_TTS_MINI);
            addVoice(models, "亲切女声", "qinqienvsheng", MODEL_STEPAUDIO_25_TTS, MODEL_STEP_TTS_2, MODEL_STEP_TTS_MINI);
            addVoice(models, "温柔女声", "wenrounvsheng", MODEL_STEPAUDIO_25_TTS, MODEL_STEP_TTS_2, MODEL_STEP_TTS_MINI);
            addVoice(models, "机灵少女", "jilingshaonv", MODEL_STEPAUDIO_25_TTS, MODEL_STEP_TTS_2, MODEL_STEP_TTS_MINI);
            addVoice(models, "软萌女声", "ruanmengnvsheng", MODEL_STEPAUDIO_25_TTS, MODEL_STEP_TTS_2, MODEL_STEP_TTS_MINI);
            addVoice(models, "优雅女声", "youyanvsheng", MODEL_STEPAUDIO_25_TTS, MODEL_STEP_TTS_2, MODEL_STEP_TTS_MINI);
            addVoice(models, "冷艳御姐", "lengyanyujie", MODEL_STEPAUDIO_25_TTS, MODEL_STEP_TTS_2, MODEL_STEP_TTS_MINI);
            addVoice(models, "爽快姐姐", "shuangkuaijiejie", MODEL_STEPAUDIO_25_TTS, MODEL_STEP_TTS_2, MODEL_STEP_TTS_MINI);
            addVoice(models, "文静学姐", "wenjingxuejie", MODEL_STEPAUDIO_25_TTS, MODEL_STEP_TTS_2, MODEL_STEP_TTS_MINI);
            addVoice(models, "邻家妹妹", "linjiameimei", MODEL_STEPAUDIO_25_TTS, MODEL_STEP_TTS_2, MODEL_STEP_TTS_MINI);
            addVoice(models, "知性姐姐", "zhixingjiejie", MODEL_STEPAUDIO_25_TTS, MODEL_STEP_TTS_2, MODEL_STEP_TTS_MINI);
            return new StepFunTTSSite(
                    API_TYPE,
                    StepFunShared.ICON,
                    "https://api.stepfun.com/v1/audio/speech",
                    false,
                    StringUtils.EMPTY,
                    StringUtils.EMPTY,
                    StringUtils.EMPTY,
                    StringUtils.EMPTY,
                    Map.of(),
                    models
            );
        }

        @Override
        public Codec<StepFunTTSSite> codec() {
            return CODEC;
        }

        private static void addVoice(Map<String, String> models, String displayName, String voiceId, String... supportedModels) {
            for (String model : supportedModels) {
                models.put(model + ":" + voiceId, model + " / " + displayName);
            }
        }
    }
}
