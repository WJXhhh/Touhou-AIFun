package com.wjx.touhou_aifun.compat.ai.action;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ITool;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.BoolParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.IntegerParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.ObjectParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.Parameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.request.ChatCompletion;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.JsonOps;
import com.wjx.touhou_aifun.config.TouhouAIFunConfig;
import com.wjx.touhou_aifun.maid.combat.MaidCombatReflex;
import com.wjx.touhou_aifun.maid.combat.MaidCombatStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/**
 * Command or clear the maid's specific combat target (aggro).
 * <p>
 * Target validation is entirely server-side. This tool cannot bypass the owner,
 * public-maid, friendly-fire or attack_players rules.
 */
public final class CombatTargetTool implements ITool<JsonObject> {
    public static final String ID = "combat_target";
    private static final String ENTITY_ID = "entity_id";
    private static final String CLEAR = "clear";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String summary(EntityMaid maid) {
        return "Set or clear this maid's commanded combat target (aggro) when a player explicitly asks to "
                + "attack or stop. Obtain entity_id from nearby_entities or scan_surroundings; never guess. "
                + "clear=true clears the current addon aggro and suppresses that target for a while; the base "
                + "attack work mode may still engage OTHER hostiles, so switch work mode to stop all attacks. "
                + "Hard rules: never the actual owner; player targets are refused while the maid is public, "
                + "friendly fire is disabled, or attack_players is false.";
    }

    @Override
    public Parameter parameters(ObjectParameter root, EntityMaid maid) {
        root.addProperties(ENTITY_ID, IntegerParameter.create()
                .setMinimum(0)
                .setDescription("Exact entity id from nearby_entities or scan_surroundings. Omit only for clear/status."),
                false);
        root.addProperties(CLEAR, BoolParameter.create()
                .setDescription("True to clear the current commanded/reflex target and suppress it. Do not combine blindly with entity_id."),
                false);
        return root;
    }

    @Override
    public Codec<JsonObject> codec() {
        return Codec.PASSTHROUGH.comapFlatMap(dynamic -> {
            var value = dynamic.convert(JsonOps.INSTANCE).getValue();
            return value.isJsonObject() ? DataResult.success(value.getAsJsonObject())
                    : DataResult.error(() -> "object_required");
        }, value -> new Dynamic<>(JsonOps.INSTANCE, value));
    }

    @Override
    public boolean trigger(EntityMaid maid, ChatCompletion request) {
        return TouhouAIFunConfig.SPEC.isLoaded() && TouhouAIFunConfig.COMBAT_ENABLED.get();
    }

    @Override
    public LLMCallback onCall(String callId, JsonObject args, LLMCallback callback) {
        EntityMaid maid = callback.getMaid();
        JsonObject result;
        if (maid == null || !maid.isAlive() || !(maid.level() instanceof ServerLevel)) {
            result = error("maid_unavailable", "no active server-side maid");
        } else {
            boolean clear = args.has(CLEAR) && args.get(CLEAR).getAsBoolean();
            Integer entityId = args.has(ENTITY_ID) && !args.get(ENTITY_ID).isJsonNull()
                    ? args.get(ENTITY_ID).getAsInt() : null;
            Entity specified = entityId == null ? null : maid.level().getEntity(entityId);
            if (entityId != null && specified == null) {
                result = error("target_not_found", "no loaded entity with id " + entityId);
            } else if (clear) {
                MaidCombatReflex.ClearResult cleared = MaidCombatReflex.clearAggro(maid, specified);
                result = MaidCombatReflex.status(maid).toJson();
                result.addProperty("result", "cleared");
                result.addProperty("cleared_target", cleared.target());
                result.addProperty("suppressed", cleared.suppressed());
            } else if (entityId == null) {
                result = MaidCombatReflex.status(maid).toJson();
            } else if (!(specified instanceof LivingEntity target) || !target.isAlive()) {
                result = error("target_not_living", "entity id " + entityId + " is not a living entity");
            } else {
                MaidCombatReflex.CommandResult command = MaidCombatReflex.command(maid, target);
                if (!command.success()) {
                    result = error(command.reason(), "the server rejected this combat target");
                } else {
                    MaidCombatStatus status = MaidCombatReflex.status(maid);
                    result = status.toJson();
                    result.addProperty("result", "commanded");
                    result.addProperty("commanded_target", command.target());
                }
            }
        }
        return callback.addToolResult(result.toString(), callId);
    }

    @Override
    public String invocationSummary(JsonObject args) {
        return ID;
    }

    private static JsonObject error(String reason, String detail) {
        JsonObject result = new JsonObject();
        result.addProperty("status", "rejected");
        result.addProperty("error", reason);
        result.addProperty("detail", detail);
        return result;
    }
}
