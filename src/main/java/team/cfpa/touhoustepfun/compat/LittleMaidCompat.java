package team.cfpa.touhoustepfun.compat;

import com.github.tartaricacid.touhoulittlemaid.api.ILittleMaid;
import com.github.tartaricacid.touhoulittlemaid.api.LittleMaidExtension;
import com.github.tartaricacid.touhoulittlemaid.ai.service.ServiceType;
import com.github.tartaricacid.touhoulittlemaid.ai.service.SerializerRegister;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.DefaultLLMSite;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMSite;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.LLMOpenAISite;
import com.github.tartaricacid.touhoulittlemaid.ai.service.SerializableSite;
import com.github.tartaricacid.touhoulittlemaid.ai.service.tts.fishaudio.TTSFishAudioSite;
import com.github.tartaricacid.touhoulittlemaid.ai.service.tts.minimax.TTSMiniMaxSite;
import com.github.tartaricacid.touhoulittlemaid.ai.service.tts.siliconflow.TTSSiliconflowSite;
import team.cfpa.touhoustepfun.compat.ai.fishaudio.tts.FishAudioCompatTTSSite;
import team.cfpa.touhoustepfun.compat.ai.mimo.MimoLLMSite;
import team.cfpa.touhoustepfun.compat.ai.minimax.tts.MiniMaxCompatTTSSite;
import team.cfpa.touhoustepfun.compat.ai.openai.ReasoningCompatOpenAISite;
import team.cfpa.touhoustepfun.compat.ai.stepfun.StepFunLLMSite;
import team.cfpa.touhoustepfun.compat.ai.stepfun.StepFunPlanLLMSite;
import team.cfpa.touhoustepfun.compat.ai.mimo.stt.MimoSTTSite;
import team.cfpa.touhoustepfun.compat.ai.mimo.tts.MimoTTSSite;
import team.cfpa.touhoustepfun.compat.ai.siliconflow.tts.SiliconflowCompatTTSSite;
import team.cfpa.touhoustepfun.compat.ai.stepfun.stt.StepFunPlanSTTSite;
import team.cfpa.touhoustepfun.compat.ai.stepfun.stt.StepFunSTTSite;
import team.cfpa.touhoustepfun.compat.ai.stepfun.tts.StepFunPlanTTSSite;
import team.cfpa.touhoustepfun.compat.ai.stepfun.tts.StepFunTTSSite;

import java.util.Map;
import java.util.function.Consumer;

@LittleMaidExtension
public final class LittleMaidCompat implements ILittleMaid {
    @Override
    public void registerAIChatSerializer(SerializerRegister register) {
        register.register(ServiceType.LLM, LLMOpenAISite.API_TYPE, new ReasoningCompatOpenAISite.Serializer());
        register.register(ServiceType.LLM, StepFunLLMSite.API_TYPE, new StepFunLLMSite.Serializer());
        register.register(ServiceType.LLM, StepFunPlanLLMSite.API_TYPE, new StepFunPlanLLMSite.Serializer());
        register.register(ServiceType.LLM, MimoLLMSite.API_TYPE, new MimoLLMSite.Serializer());
        register.register(ServiceType.STT, StepFunSTTSite.API_TYPE, new StepFunSTTSite.Serializer());
        register.register(ServiceType.STT, StepFunPlanSTTSite.API_TYPE, new StepFunPlanSTTSite.Serializer());
        register.register(ServiceType.STT, MimoSTTSite.API_TYPE, new MimoSTTSite.Serializer());
        register.register(ServiceType.TTS, StepFunTTSSite.API_TYPE, new StepFunTTSSite.Serializer());
        register.register(ServiceType.TTS, StepFunPlanTTSSite.API_TYPE, new StepFunPlanTTSSite.Serializer());
        register.register(ServiceType.TTS, MimoTTSSite.API_TYPE, new MimoTTSSite.Serializer());
        register.register(ServiceType.TTS, TTSFishAudioSite.API_TYPE, new FishAudioCompatTTSSite.Serializer());
        register.register(ServiceType.TTS, TTSSiliconflowSite.API_TYPE, new SiliconflowCompatTTSSite.Serializer());
        register.register(ServiceType.TTS, TTSMiniMaxSite.API_TYPE, new MiniMaxCompatTTSSite.Serializer());

        DefaultLLMSite.DEEPSEEK = new ReasoningCompatOpenAISite(
                "deepseek",
                SerializableSite.defaultIcon("deepseek"),
                "https://api.deepseek.com/chat/completions",
                true,
                "",
                true,
                Map.of(),
                DefaultLLMSite.DEEPSEEK.modelEntries()
        );

        Consumer<LLMSite> fixedDeepSeek = site -> {
            if (site instanceof LLMOpenAISite openAISite) {
                Map<String, String> models = openAISite.models();
                openAISite.removeModel("deepseek-chat");
                openAISite.removeModel("deepseek-reasoner");
                if (!models.containsKey("deepseek-v4-flash")) {
                    openAISite.addModel("deepseek-v4-flash");
                }
                if (!models.containsKey("deepseek-v4-pro")) {
                    openAISite.addModel("deepseek-v4-pro");
                }
                openAISite.setHasThinkingField(true);
            }
        };
        DefaultLLMSite.FIXED_DEEPSEEK = fixedDeepSeek;
    }
}
