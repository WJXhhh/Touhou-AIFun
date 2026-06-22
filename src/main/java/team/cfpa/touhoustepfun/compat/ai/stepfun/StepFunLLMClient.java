package team.cfpa.touhoustepfun.compat.ai.stepfun;

import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.LLMOpenAISite;
import team.cfpa.touhoustepfun.compat.ai.openai.ReasoningCompatOpenAIClient;
import team.cfpa.touhoustepfun.compat.ai.openai.request.ReasoningChatCompletion;

import java.net.http.HttpClient;

public final class StepFunLLMClient extends ReasoningCompatOpenAIClient {
    private static final String LOW = "low";

    public StepFunLLMClient(HttpClient httpClient, LLMOpenAISite site) {
        super(httpClient, site);
    }

    @Override
    protected ReasoningChatCompletion extraArgs(ReasoningChatCompletion chatCompletion, String model) {
        ReasoningChatCompletion completion = super.extraArgs(chatCompletion, model);
        if (supportsLowReasoningEffort(model)) {
            completion.reasoningEffort(LOW);
        }
        return completion;
    }

    private static boolean supportsLowReasoningEffort(String model) {
        return "step-3.7-flash".equals(model) || "step-3.5-flash-2603".equals(model);
    }
}
