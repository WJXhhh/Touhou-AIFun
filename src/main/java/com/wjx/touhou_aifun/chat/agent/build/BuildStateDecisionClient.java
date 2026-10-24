package com.wjx.touhou_aifun.chat.agent.build;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonObject;
import com.wjx.touhou_aifun.TouhouAIFun;
import com.wjx.touhou_aifun.chat.agent.TaskCallback;
import com.wjx.touhou_aifun.chat.agent.decision.DecisionRequestGate;
import com.wjx.touhou_aifun.chat.agent.decision.DecisionServiceSettings;
import com.wjx.touhou_aifun.config.TouhouAIFunConfig;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Native decision implementation for ambiguous build states; never blocks the server thread. */
public final class BuildStateDecisionClient implements BuildStateJudge {
    public static void install() {
        BuildQueueRunner.JUDGE = new BuildStateDecisionClient();
    }

    @Override
    public CompletableFuture<JsonObject> judge(EntityMaid maid, Object callback, BuildDesign design,
                                                BuildStep step, JsonObject actual, int attempts) {
        if (!TouhouAIFunConfig.SPEC.isLoaded() || !TouhouAIFunConfig.BUILD_QUEUE_STATE_DECISION.get()) {
            return CompletableFuture.completedFuture(BuildStateDecision.lowConfidence("decision_disabled"));
        }
        DecisionServiceSettings.Snapshot settings;
        try {
            settings = DecisionServiceSettings.snapshot();
        } catch (RuntimeException unavailable) {
            return CompletableFuture.completedFuture(BuildStateDecision.lowConfidence("service_unavailable"));
        }
        DecisionServiceSettings.View view = settings.view();
        if (!view.enabled() || settings.apiKey().isBlank() || !DecisionServiceSettings.provider(view.provider())) {
            return CompletableFuture.completedFuture(BuildStateDecision.lowConfidence("service_unavailable"));
        }
        if (!DecisionRequestGate.acquire(view.provider(), System.nanoTime())) {
            return CompletableFuture.completedFuture(BuildStateDecision.lowConfidence("rate_limit"));
        }
        List<BuildStateDecision.Candidate> candidates = BuildStateDecision.candidates(maid, step);
        JsonObject state = BuildStateDecision.state(maid, design, step, actual, attempts, candidates);
        JsonObject questions = BuildStateDecision.questions(candidates);
        double minimum = Math.max(0.51, Math.min(1.0, TouhouAIFunConfig.BUILD_QUEUE_STATE_MIN_CONFIDENCE.get()));
        CompletableFuture<JsonObject> result = new CompletableFuture<>();
        try {
            settings.client().evaluate(state, questions, raw -> {
                if (callback instanceof TaskCallback task) task.operations.add(raw);
            }).whenComplete((answer, error) -> {
                if (error != null || answer == null) {
                    result.complete(BuildStateDecision.lowConfidence(error == null ? "decision_empty" : "decision_failed"));
                    return;
                }
                BuildStateDecision.Verdict verdict;
                try {
                    verdict = BuildStateDecision.parse(answer.answers(), minimum, candidates, step);
                } catch (RuntimeException invalid) {
                    verdict = new BuildStateDecision.Verdict(false, "llm", "decision_invalid", null);
                }
                TouhouAIFun.LOGGER.info("AIFun build decision: step={} attempts={} verdict={} confident={} input_tokens={} output_tokens={}",
                        step == null ? -1 : step.index, attempts, verdict.choice(), verdict.confident(),
                        answer.inputTokens(), answer.outputTokens());
                result.complete(verdict.json());
            });
        } catch (RuntimeException invalid) {
            result.complete(BuildStateDecision.lowConfidence("request_invalid"));
        }
        return result;
    }
}
