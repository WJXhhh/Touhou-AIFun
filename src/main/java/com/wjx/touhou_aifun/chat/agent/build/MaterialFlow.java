package com.wjx.touhou_aifun.chat.agent.build;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.wjx.touhou_aifun.TouhouAIFun;
import com.wjx.touhou_aifun.chat.agent.AgentRuntime;
import com.wjx.touhou_aifun.chat.agent.AgentTaskState;
import com.wjx.touhou_aifun.chat.agent.TaskCallback;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Material policy gate shared by request_materials and run. It never lets the executor obtain
 * materials without an owner policy, and it never writes the owner-facing question itself.
 */
public final class MaterialFlow {
    private MaterialFlow() {
    }

    public static JsonObject missingJson(EntityMaid maid, JsonObject request) {
        BuildQueueState state = BuildQueueManager.state(maid);
        String requested = request != null && request.has("design_id") && request.get("design_id").isJsonPrimitive()
                ? request.get("design_id").getAsString() : "";
        BuildDesign design = state == null ? null
                : requested.isBlank() ? BuildQueueManager.design(maid, state.activeDesignId)
                : BuildQueueManager.design(maid, requested);
        if (design == null) return BuildQueueManager.requestMaterials(maid, request);
        List<BuildMaterialPolicy.Missing> missing = BuildMaterialPolicy.missing(maid, design, state);
        JsonObject out = new JsonObject();
        out.addProperty("success", true);
        out.addProperty("status", missing.isEmpty() ? "nothing_missing" : "materials_required");
        out.addProperty("design_id", design.id);
        out.add("missing_items", BuildMaterialPolicy.missingJson(missing));
        JsonObject counts = new JsonObject();
        for (BuildMaterialPolicy.Missing row : missing) counts.addProperty(row.item(), row.required());
        out.add("required_item_counts", counts);
        return out;
    }

    /** Returns a future because an unset policy may need one native authorization decision. */
    public static CompletableFuture<JsonObject> preflight(EntityMaid maid, TaskCallback callback, JsonObject request) {
        CompletableFuture<JsonObject> result = new CompletableFuture<>();
        if (callback == null) {
            result.complete(failure("task_execution_only"));
            return result;
        }
        JsonObject missing;
        try {
            missing = missingJson(maid, request);
        } catch (RuntimeException failed) {
            result.complete(failure(failed.getMessage() == null ? "materials_check_failed" : failed.getMessage()));
            return result;
        }
        if (!"materials_required".equals(missing.get("status").getAsString())) {
            result.complete(missing);
            return result;
        }
        resolvePolicy(maid, callback, missing, result);
        return result;
    }

    private static void resolvePolicy(EntityMaid maid, TaskCallback callback, JsonObject missing,
                                      CompletableFuture<JsonObject> result) {
        AgentTaskState state = callback.task;
        String policy = AgentTaskState.normalizeMaterialPolicy(state.materialPolicy);
        if (AgentTaskState.MATERIAL_DENIED.equals(policy)) {
            result.complete(withMissing(failure("materials_denied"), missing, policy));
            return;
        }
        if (!AgentTaskState.MATERIAL_ASK_OWNER.equals(policy)) {
            selfSourceOrQuestion(maid, callback, missing, result, policy);
            return;
        }
        JsonArray facts = missing.has("missing_items") && missing.get("missing_items").isJsonArray()
                ? missing.getAsJsonArray("missing_items").deepCopy() : new JsonArray();
        MaterialAuthorizationDecision.decide(maid, state, facts,
                own -> callback.operations.add(own)).whenComplete((decided, error) -> {
            Runnable apply = () -> {
                String chosenPolicy = error != null || decided == null ? AgentTaskState.MATERIAL_ASK_OWNER
                        : AgentTaskState.normalizeMaterialPolicy(decided);
                if (AgentTaskState.MATERIAL_DENIED.equals(chosenPolicy)) {
                    try { state.setMaterialPolicy(chosenPolicy); } catch (RuntimeException ignored) { }
                    AgentRuntime.save(maid);
                    TouhouAIFun.LOGGER.info("AIFun material policy: maid={} policy={} source=decision", maid.getUUID(), chosenPolicy);
                    result.complete(withMissing(failure("materials_denied"), missing, chosenPolicy));
                    return;
                }
                if (!AgentTaskState.MATERIAL_ASK_OWNER.equals(chosenPolicy)) {
                    try {
                        state.setMaterialPolicy(chosenPolicy);
                        state.materialRounds = 0;
                    } catch (RuntimeException ignored) { }
                    AgentRuntime.save(maid);
                    TouhouAIFun.LOGGER.info("AIFun material policy: maid={} policy={} source=decision", maid.getUUID(), chosenPolicy);
                    selfSourceOrQuestion(maid, callback, missing, result, chosenPolicy);
                    return;
                }
                raiseQuestion(maid, callback, missing);
                result.complete(withMissing(failure("owner_decision_required"), missing, policy));
            };
            if (callback.isOnServerThread() && maid.level() instanceof net.minecraft.server.level.ServerLevel level
                    && level.getServer().isSameThread()) apply.run();
            else callback.runOnServerThread(apply);
        });
    }

    private static void selfSourceOrQuestion(EntityMaid maid, TaskCallback callback, JsonObject missing,
                                             CompletableFuture<JsonObject> result, String policy) {
        AgentTaskState task = callback.task;
        int max = Math.max(1, com.wjx.touhou_aifun.config.TouhouAIFunConfig.BUILD_QUEUE_MAX_MATERIAL_ROUNDS.get());
        if (task.materialRounds >= max) {
            raiseQuestion(maid, callback, missing);
            result.complete(withMissing(failure("owner_decision_required"), missing, policy));
            return;
        }
        task.materialRounds++;
        AgentRuntime.save(maid);
        result.complete(withMissing(failure("missing_items"), missing, policy));
    }

    private static void raiseQuestion(EntityMaid maid, TaskCallback callback, JsonObject missing) {
        JsonObject facts = new JsonObject();
        facts.addProperty("design_id", missing.has("design_id") ? missing.get("design_id").getAsString() : "");
        facts.add("missing_items", missing.has("missing_items") ? missing.getAsJsonArray("missing_items").deepCopy() : new JsonArray());
        facts.add("required_item_counts", missing.has("required_item_counts") ? missing.get("required_item_counts").deepCopy() : new JsonObject());
        List<String> options = new ArrayList<>();
        options.add(AgentTaskState.MATERIAL_SELF_GATHER);
        options.add(AgentTaskState.MATERIAL_SELF_SOURCE);
        options.add(AgentTaskState.MATERIAL_DENIED);
        AgentRuntime.raiseOwnerQuestion(maid, "materials", facts, options, "");
    }

    /** Handles a run result that stopped because a later step lacked materials. */
    public static JsonObject postRun(EntityMaid maid, TaskCallback callback, JsonObject payload) {
        if (payload == null) return null;
        if (payload.has("success") && payload.get("success").isJsonPrimitive() && payload.get("success").getAsBoolean())
            return payload;
        if (payload.has("error") && !"missing_items".equals(payload.get("error").getAsString()))
            return payload;
        JsonObject missing = missingJson(maid, new JsonObject());
        if (!"materials_required".equals(missing.get("status").getAsString())) return payload;
        AgentTaskState state = callback.task;
        String policy = AgentTaskState.normalizeMaterialPolicy(state.materialPolicy);
        if (!AgentTaskState.MATERIAL_ASK_OWNER.equals(policy)) {
            if (AgentTaskState.MATERIAL_DENIED.equals(policy))
                return withMissing(failure("materials_denied"), missing, policy);
            int max = Math.max(1, com.wjx.touhou_aifun.config.TouhouAIFunConfig.BUILD_QUEUE_MAX_MATERIAL_ROUNDS.get());
            if (state.materialRounds >= max) {
                raiseQuestion(maid, callback, missing);
                return withMissing(failure("owner_decision_required"), missing, policy);
            }
            state.materialRounds++;
            AgentRuntime.save(maid);
            return withMissing(payload, missing, policy);
        }
        raiseQuestion(maid, callback, missing);
        return withMissing(failure("owner_decision_required"), missing, policy);
    }

    private static JsonObject withMissing(JsonObject target, JsonObject missing, String policy) {
        JsonObject out = target == null ? new JsonObject() : target.deepCopy();
        out.addProperty("material_policy", policy);
        if (missing.has("missing_items")) out.add("missing_items", missing.get("missing_items").deepCopy());
        if (missing.has("required_item_counts")) out.add("required_item_counts", missing.get("required_item_counts").deepCopy());
        if (missing.has("design_id")) out.addProperty("design_id", missing.get("design_id").getAsString());
        return out;
    }

    private static JsonObject failure(String error) {
        JsonObject out = new JsonObject();
        out.addProperty("success", false);
        out.addProperty("status", "failed");
        out.addProperty("error", error);
        return out;
    }
}
