package com.wjx.touhou_aifun.compat;

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
import com.wjx.touhou_aifun.compat.ai.anthropic.AnthropicLLMSite;
import com.wjx.touhou_aifun.compat.ai.fishaudio.tts.FishAudioCompatTTSSite;
import com.wjx.touhou_aifun.compat.ai.mimo.MimoLLMSite;
import com.wjx.touhou_aifun.compat.ai.minimax.tts.MiniMaxCompatTTSSite;
import com.wjx.touhou_aifun.compat.ai.openai.ReasoningCompatOpenAISite;
import com.wjx.touhou_aifun.compat.ai.openai.LoadToolSchemaTool;
import com.wjx.touhou_aifun.compat.ai.stepfun.StepFunLLMSite;
import com.wjx.touhou_aifun.compat.ai.stepfun.StepFunPlanLLMSite;
import com.wjx.touhou_aifun.compat.ai.mimo.stt.MimoSTTSite;
import com.wjx.touhou_aifun.compat.ai.mimo.tts.MimoTTSSite;
import com.wjx.touhou_aifun.compat.ai.qwen.QwenLLMSite;
import com.wjx.touhou_aifun.compat.ai.qwen.stt.QwenSTTSite;
import com.wjx.touhou_aifun.compat.ai.qwen.tts.QwenTTSSite;
import com.wjx.touhou_aifun.compat.ai.siliconflow.tts.SiliconflowCompatTTSSite;
import com.wjx.touhou_aifun.compat.ai.stepfun.stt.StepFunPlanSTTSite;
import com.wjx.touhou_aifun.compat.ai.stepfun.stt.StepFunSTTSite;
import com.wjx.touhou_aifun.compat.ai.stepfun.tts.StepFunPlanTTSSite;
import com.wjx.touhou_aifun.compat.ai.stepfun.tts.StepFunTTSSite;
import com.wjx.touhou_aifun.compat.ai.vision.ScanSurroundingsTool;
import com.wjx.touhou_aifun.compat.ai.vision.ObserveSurroundingsTool;

import java.util.Map;
import java.util.function.Consumer;

@LittleMaidExtension
public final class LittleMaidCompat implements ILittleMaid {
    @Override
    public void registerAITool(com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ToolRegister register) {
        register.register(new LoadToolSchemaTool());
        register.register(new ScanSurroundingsTool());
        register.register(new ObserveSurroundingsTool());
    }

    @Override
    public void registerAIChatSerializer(SerializerRegister register) {
        register.register(ServiceType.LLM, LLMOpenAISite.API_TYPE, new ReasoningCompatOpenAISite.Serializer());
        register.register(ServiceType.LLM, AnthropicLLMSite.API_TYPE, new AnthropicLLMSite.Serializer());
        register.register(ServiceType.LLM, StepFunLLMSite.API_TYPE, new StepFunLLMSite.Serializer());
        register.register(ServiceType.LLM, StepFunPlanLLMSite.API_TYPE, new StepFunPlanLLMSite.Serializer());
        register.register(ServiceType.LLM, MimoLLMSite.API_TYPE, new MimoLLMSite.Serializer());
        register.register(ServiceType.LLM, QwenLLMSite.API_TYPE, new QwenLLMSite.Serializer());
        register.register(ServiceType.STT, StepFunSTTSite.API_TYPE, new StepFunSTTSite.Serializer());
        register.register(ServiceType.STT, StepFunPlanSTTSite.API_TYPE, new StepFunPlanSTTSite.Serializer());
        register.register(ServiceType.STT, MimoSTTSite.API_TYPE, new MimoSTTSite.Serializer());
        register.register(ServiceType.STT, QwenSTTSite.API_TYPE, new QwenSTTSite.Serializer());
        register.register(ServiceType.TTS, StepFunTTSSite.API_TYPE, new StepFunTTSSite.Serializer());
        register.register(ServiceType.TTS, StepFunPlanTTSSite.API_TYPE, new StepFunPlanTTSSite.Serializer());
        register.register(ServiceType.TTS, MimoTTSSite.API_TYPE, new MimoTTSSite.Serializer());
        register.register(ServiceType.TTS, QwenTTSSite.API_TYPE, new QwenTTSSite.Serializer());
        register.register(ServiceType.TTS, TTSFishAudioSite.API_TYPE, new FishAudioCompatTTSSite.Serializer());
        register.register(ServiceType.TTS, TTSSiliconflowSite.API_TYPE, new SiliconflowCompatTTSSite.Serializer());
        register.register(ServiceType.TTS, TTSMiniMaxSite.API_TYPE, new MiniMaxCompatTTSSite.Serializer());

        // 夺舍：让阿里云默认 LLM 预置站点改用 Qwen 的 reasoning 兼容客户端，与上面注册的 "aliyun" 序列化器类型保持一致。
        // 复用基模原有的 qwen 模型列表，仅升级为带思考字段的 QwenLLMSite。
        DefaultLLMSite.ALIYUN = new QwenLLMSite(
                QwenLLMSite.API_TYPE,
                SerializableSite.defaultIcon(QwenLLMSite.API_TYPE),
                "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
                false,
                "",
                true,
                Map.of(),
                DefaultLLMSite.ALIYUN.modelEntries()
        );

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
