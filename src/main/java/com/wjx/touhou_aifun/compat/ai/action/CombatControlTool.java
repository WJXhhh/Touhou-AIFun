package com.wjx.touhou_aifun.compat.ai.action;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ITool;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.BoolParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.IntegerParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.ObjectParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.Parameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.StringParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.request.ChatCompletion;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.JsonOps;
import com.wjx.touhou_aifun.chat.agent.AgentRuntime;
import com.wjx.touhou_aifun.config.TouhouAIFunConfig;
import com.wjx.touhou_aifun.maid.combat.MaidCombatData;
import com.wjx.touhou_aifun.maid.combat.MaidCombatReflex;
import com.wjx.touhou_aifun.maid.combat.MaidCombatSettings;

/**
 * Read or change the maid's combat rules. State changes are owner-only; the
 * public/friendly-fire/owner hard gates live in the server policy and cannot be
 * changed through this tool.
 */
public final class CombatControlTool implements ITool<JsonObject> {
    public static final String ID = "combat_control";
    private static final String MODE = "mode";
    private static final String PROTECT_OWNER = "protect_owner";
    private static final String ATTACK_PLAYERS = "attack_players";
    private static final String ENGAGE_RADIUS = "engage_radius";
    private static final String NOTES = "notes";
    private static final String RESET = "reset";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String summary(EntityMaid maid) {
        return "Read or change this maid's combat rules. Only the actual owner may change them; status reads are "
                + "allowed for any authorized speaker. Hard rules (never the owner; no player retaliation while "
                + "public or when friendly fire is disabled) cannot be changed. notes are owner preferences for "
                + "tone/roleplay, not permission. Use combat_target for a specific commanded target.";
    }

    @Override
    public Parameter parameters(ObjectParameter root, EntityMaid maid) {
        root.addProperties(MODE, StringParameter.create()
                .addEnumValues("off", "defend")
                .setDescription("off disables the addon automatic reflex; defend enables it. Explicit combat_target commands still work when off."),
                false);
        root.addProperties(PROTECT_OWNER, BoolParameter.create()
                .setDescription("Attack the owner's attacker when the owner is hurt nearby."), false);
        root.addProperties(ATTACK_PLAYERS, BoolParameter.create()
                .setDescription("Allow player retaliation. Never the owner; blocked entirely by public-maid or disabled friendly fire."), false);
        root.addProperties(ENGAGE_RADIUS, IntegerParameter.create()
                .setMinimum(MaidCombatData.MIN_ENGAGE_RADIUS)
                .setMaximum(MaidCombatData.MAX_ENGAGE_RADIUS)
                .setDescription("Maximum distance from the maid to an automatic/commanded target."), false);
        root.addProperties(NOTES, StringParameter.create()
                .setMaxLength(MaidCombatSettings.MAX_NOTES_CODE_POINTS)
                .setDescription("Owner combat preferences for tone/narration. Advisory only; hard rules still apply."), false);
        root.addProperties(RESET, BoolParameter.create()
                .setDescription("True to remove all stored per-maid overrides before applying any other fields."), false);
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
        if (maid == null || !maid.isAlive()) {
            result = error("maid_unavailable", "no active maid");
            return callback.addToolResult(result.toString(), callId);
        }
        boolean reset = args.has(RESET) && args.get(RESET).getAsBoolean();
        boolean mutating = reset || args.has(MODE) || args.has(PROTECT_OWNER)
                || args.has(ATTACK_PLAYERS) || args.has(ENGAGE_RADIUS) || args.has(NOTES);
        if (mutating && !AgentRuntime.ownerSpeaker(callback)) {
            result = error("combat_policy_requires_owner",
                    "only the actual owner may change combat rules");
            return callback.addToolResult(result.toString(), callId);
        }

        MaidCombatData.Mode mode = null;
        if (args.has(MODE) && !args.get(MODE).isJsonNull()) {
            String requested = args.get(MODE).getAsString();
            String normalized = MaidCombatSettings.normalizeMode(requested);
            if (normalized == null) {
                result = error("invalid_mode", "mode must be off or defend");
                return callback.addToolResult(result.toString(), callId);
            }
            mode = "off".equals(normalized) ? MaidCombatData.Mode.OFF : MaidCombatData.Mode.DEFEND;
        }
        Boolean protectOwner = args.has(PROTECT_OWNER) && !args.get(PROTECT_OWNER).isJsonNull()
                ? args.get(PROTECT_OWNER).getAsBoolean() : null;
        Boolean attackPlayers = args.has(ATTACK_PLAYERS) && !args.get(ATTACK_PLAYERS).isJsonNull()
                ? args.get(ATTACK_PLAYERS).getAsBoolean() : null;
        Integer engageRadius = args.has(ENGAGE_RADIUS) && !args.get(ENGAGE_RADIUS).isJsonNull()
                ? args.get(ENGAGE_RADIUS).getAsInt() : null;
        String notes = args.has(NOTES) && !args.get(NOTES).isJsonNull()
                ? args.get(NOTES).getAsString() : null;

        if (mutating) {
            MaidCombatData.store(maid, mode, protectOwner, attackPlayers, engageRadius, notes, reset);
            MaidCombatReflex.recheck(maid);
        }
        result = MaidCombatReflex.status(maid).toJson();
        result.addProperty("result", mutating ? "updated" : "status");
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
