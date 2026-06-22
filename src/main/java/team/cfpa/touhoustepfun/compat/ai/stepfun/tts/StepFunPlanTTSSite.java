package team.cfpa.touhoustepfun.compat.ai.stepfun.tts;

import com.github.tartaricacid.touhoulittlemaid.ai.service.SerializableSite;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;
import org.apache.commons.lang3.StringUtils;
import team.cfpa.touhoustepfun.compat.ai.stepfun.StepFunShared;
import team.cfpa.touhoustepfun.compat.ai.tts.VoicePresetSpec;

import java.util.LinkedHashMap;
import java.util.Map;

public final class StepFunPlanTTSSite extends StepFunTTSSite {
    public static final String API_TYPE = "stepfun_plan";
    private static final String MODEL_STEP_TTS_MINI = "step-tts-mini";
    private static final String MODEL_STEP_TTS_2 = "step-tts-2";
    private static final String MODEL_STEPAUDIO_25_TTS = "stepaudio-2.5-tts";

    public StepFunPlanTTSSite(String id, ResourceLocation icon, String url, boolean enabled,
                              String secretKey, String cloneRefAudioPath, String cloneRefText,
                              String cloneVoiceId, Map<String, String> headers, Map<String, String> models) {
        super(id, icon, url, enabled, secretKey, cloneRefAudioPath, cloneRefText, cloneVoiceId,
                headers, filterSupportedModels(models));
    }

    @Override
    public String getApiType() {
        return API_TYPE;
    }

    private static Map<String, String> filterSupportedModels(Map<String, String> models) {
        Map<String, String> supported = new LinkedHashMap<>();
        models.forEach((storedValue, name) -> {
            VoicePresetSpec spec = VoicePresetSpec.decode(storedValue);
            if (spec.mode() != VoicePresetSpec.Mode.DIRECT_ID || isSupportedDirectValue(spec.runtimeValue())) {
                supported.put(storedValue, name);
            }
        });
        return supported;
    }

    public static boolean isSupportedDirectValue(String value) {
        int separator = StringUtils.defaultString(value).indexOf(':');
        return separator < 0 || value.startsWith(MODEL_STEPAUDIO_25_TTS + ":");
    }

    public static final class Serializer implements SerializableSite<StepFunPlanTTSSite> {
        private static final Codec<StepFunPlanTTSSite> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf(ID).forGetter(StepFunPlanTTSSite::id),
                ResourceLocation.CODEC.fieldOf(ICON).forGetter(StepFunPlanTTSSite::icon),
                Codec.STRING.fieldOf(URL).forGetter(StepFunPlanTTSSite::url),
                Codec.BOOL.fieldOf(ENABLED).forGetter(StepFunPlanTTSSite::enabled),
                Codec.STRING.fieldOf(SECRET_KEY).forGetter(StepFunPlanTTSSite::secretKey),
                Codec.STRING.optionalFieldOf("clone_ref_audio_path", StringUtils.EMPTY).forGetter(StepFunPlanTTSSite::cloneRefAudioPath),
                Codec.STRING.optionalFieldOf("clone_ref_text", StringUtils.EMPTY).forGetter(StepFunPlanTTSSite::cloneRefText),
                Codec.STRING.optionalFieldOf("clone_voice_id", StringUtils.EMPTY).forGetter(StepFunPlanTTSSite::cloneVoiceId),
                Codec.unboundedMap(Codec.STRING, Codec.STRING).fieldOf(HEADERS).forGetter(StepFunPlanTTSSite::headers),
                Codec.unboundedMap(Codec.STRING, Codec.STRING).fieldOf(MODELS).forGetter(StepFunPlanTTSSite::models)
        ).apply(instance, StepFunPlanTTSSite::new));

        @Override
        public StepFunPlanTTSSite defaultSite() {
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
            return new StepFunPlanTTSSite(
                    API_TYPE,
                    StepFunShared.ICON,
                    "https://api.stepfun.com/step_plan/v1/audio/speech",
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
        public Codec<StepFunPlanTTSSite> codec() {
            return CODEC;
        }

        private static void addVoice(Map<String, String> models, String displayName, String voiceId, String... supportedModels) {
            for (String model : supportedModels) {
                models.put(model + ":" + voiceId, model + " / " + displayName);
            }
        }
    }
}
