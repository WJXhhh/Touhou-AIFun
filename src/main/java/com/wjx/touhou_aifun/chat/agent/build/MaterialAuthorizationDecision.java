package com.wjx.touhou_aifun.chat.agent.build;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.wjx.touhou_aifun.TouhouAIFun;
import com.wjx.touhou_aifun.chat.agent.AgentTaskState;
import com.wjx.touhou_aifun.chat.agent.decision.DecisionProtocol;
import com.wjx.touhou_aifun.chat.agent.decision.DecisionRequestGate;
import com.wjx.touhou_aifun.chat.agent.decision.DecisionServiceSettings;
import com.wjx.touhou_aifun.chat.agent.decision.TaskRecoveryPolicy;
import com.wjx.touhou_aifun.config.TouhouAIFunConfig;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Small native decision used only when missing materials are discovered and no owner policy was
 * recorded. It recognizes explicit prior owner permission; anything ambiguous falls back to asking.
 */
public final class MaterialAuthorizationDecision {
    private MaterialAuthorizationDecision() {
    }

    public static JsonObject state(AgentTaskState task, JsonArray missingItems) {
        JsonObject state = new JsonObject();
        state.addProperty("purpose", "material_authorization");
        state.addProperty("goal", AgentTaskState.bounded(task == null ? "" : task.effectiveGoal(), 512));
        JsonArray constraints = new JsonArray();
        if (task != null) {
            int start = Math.max(0, task.constraints.size() - 8);
            for (int i = start; i < task.constraints.size(); i++)
                constraints.add(AgentTaskState.bounded(task.constraints.get(i), 256));
            if (task.pendingGuidance != null && !task.pendingGuidance.isBlank())
                state.addProperty("guidance", AgentTaskState.bounded(task.pendingGuidance, 512));
        }
        state.add("constraints", constraints);
        state.add("missing_items", missingItems == null ? new JsonArray() : missingItems.deepCopy());
        return state;
    }

    public static JsonObject questions() {
        Map<String, String> choices = new LinkedHashMap<>();
        choices.put(AgentTaskState.MATERIAL_SELF_GATHER,
                "The owner explicitly allowed the maid to find/gather/mine/craft the needed materials, but did NOT clearly allow taking them from containers.");
        choices.put(AgentTaskState.MATERIAL_SELF_SOURCE,
                "The owner explicitly allowed taking/using materials from storage or containers as well as gathering/crafting.");
        choices.put(AgentTaskState.MATERIAL_DENIED,
                "The owner explicitly refused obtaining materials or said to use only what is already available.");
        choices.put(AgentTaskState.MATERIAL_ASK_OWNER,
                "The owner did not mention material sourcing, or the permission is ambiguous. Ask once; do not infer permission from a normal build request.");
        JsonObject questions = new JsonObject();
        questions.add("authorization", DecisionProtocol.choice(
                "Read only the owner's explicit words in goal/constraints/guidance. Choose a self_* option ONLY when those words clearly authorize obtaining materials. "
                        + "A request to build something is NOT authorization to take materials. Do not treat the maid's own inventory or crafting as external sourcing. "
                        + "If the wording is ambiguous or silent, choose ask_owner.", choices));
        return questions;
    }

    public static String parse(JsonObject answers, double minConfidence) {
        if (answers == null || !TaskRecoveryPolicy.confident(answers, "authorization", minConfidence))
            return AgentTaskState.MATERIAL_ASK_OWNER;
        try {
            String choice = answers.getAsJsonObject("authorization").get("choice").getAsString();
            return AgentTaskState.normalizeMaterialPolicy(choice);
        } catch (RuntimeException invalid) {
            return AgentTaskState.MATERIAL_ASK_OWNER;
        }
    }

    /** Returns a policy; every transport/uncertainty failure resolves to ask_owner. */
    public static CompletableFuture<String> decide(EntityMaid maid, AgentTaskState task, JsonArray missingItems) {
        return decide(maid, task, missingItems, ignored -> {
        });
    }

    public static CompletableFuture<String> decide(EntityMaid maid, AgentTaskState task, JsonArray missingItems,
                                                    Consumer<CompletableFuture<?>> own) {
        CompletableFuture<String> result = new CompletableFuture<>();
        DecisionServiceSettings.Snapshot settings;
        try {
            settings = DecisionServiceSettings.snapshot();
        } catch (RuntimeException unavailable) {
            result.complete(AgentTaskState.MATERIAL_ASK_OWNER);
            return result;
        }
        DecisionServiceSettings.View view = settings.view();
        if (!view.enabled() || settings.apiKey().isBlank() || !DecisionServiceSettings.provider(view.provider())) {
            result.complete(AgentTaskState.MATERIAL_ASK_OWNER);
            return result;
        }
        if (!DecisionRequestGate.acquire(view.provider(), System.nanoTime())) {
            result.complete(AgentTaskState.MATERIAL_ASK_OWNER);
            return result;
        }
        JsonObject state = state(task, missingItems);
        JsonObject questions = questions();
        double minimum = Math.max(0.51, Math.min(1.0, TouhouAIFunConfig.BUILD_QUEUE_MATERIAL_AUTH_MIN_CONFIDENCE.get()));
        try {
            settings.client().evaluate(state, questions, own).whenComplete((answer, error) -> {
                if (error != null || answer == null) {
                    result.complete(AgentTaskState.MATERIAL_ASK_OWNER);
                    return;
                }
                String policy = parse(answer.answers(), minimum);
                TouhouAIFun.LOGGER.info("AIFun material authorization decision: provider={} verdict={} input_tokens={} output_tokens={}",
                        view.provider(), policy, answer.inputTokens(), answer.outputTokens());
                result.complete(policy);
            });
        } catch (RuntimeException invalid) {
            result.complete(AgentTaskState.MATERIAL_ASK_OWNER);
        }
        return result;
    }
}
