package com.wjx.touhou_aifun.compat.ai.action;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ITool;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.ArrayParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.ObjectParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.Parameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.StringParameter;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.JsonOps;
import com.wjx.touhou_aifun.chat.agent.AgentExecution;
import com.wjx.touhou_aifun.chat.agent.AgentRuntime;
import com.wjx.touhou_aifun.chat.agent.TaskCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Executor-only request for a foreground owner decision. It records structured facts and pauses the
 * task; it never speaks/asks the owner itself. The foreground chat model phrases the question.
 */
public final class RequestOwnerInputTool implements ITool<JsonObject> {
    public static final String TOOL_ID = "request_owner_input";

    @Override
    public String id() {
        return TOOL_ID;
    }

    @Override
    public boolean trigger(EntityMaid maid, com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.request.ChatCompletion request) {
        return AgentRuntime.enabled(maid);
    }

    @Override
    public String summary(EntityMaid maid) {
        return "Pause the active background task and request ONE owner decision through the foreground conversation. "
                + "Provide structured facts (what is blocked, what was tried, exact items/counts) and up to four options. "
                + "Do NOT write the final owner-facing line; the foreground model phrases it with the real conversation context. "
                + "Use this for missing materials, permission choices or unsupported blockers. The executor never speaks to the owner directly.";
    }

    @Override
    public Parameter parameters(ObjectParameter root, EntityMaid maid) {
        root.addProperties("kind", StringParameter.create()
                .addEnumValues("materials", "blocked", "permission", "choice", "other")
                .setDescription("Question category. Use materials for missing build items."), true);
        ObjectParameter facts = ObjectParameter.create();
        facts.addProperties("summary", StringParameter.create()
                .setDescription("Short structured description of the blocker, not a verbatim owner-facing sentence.").setMaxLength(1024), true);
        facts.addProperties("details", StringParameter.create()
                .setDescription("Optional observed facts: item ids/counts, positions, failure reason.").setMaxLength(1024), false);
        root.addProperties("facts", facts, true);
        root.addProperties("options", ArrayParameter.create().setItems(StringParameter.create().setMaxLength(64)).setRange(0, 4)
                .setDescription("Short owner choices, e.g. self_gather, self_source, denied or a concrete destination. The foreground model rephrases them."), false);
        return root;
    }

    @Override
    public Codec<JsonObject> codec() {
        return Codec.PASSTHROUGH.comapFlatMap(value -> {
            var json = value.convert(JsonOps.INSTANCE).getValue();
            return json.isJsonObject() ? DataResult.success(json.getAsJsonObject())
                    : DataResult.error(() -> "object_required");
        }, value -> new Dynamic<>(JsonOps.INSTANCE, value));
    }

    @Override
    public LLMCallback onCall(String toolCallId, JsonObject request, LLMCallback callback) {
        if (AgentExecution.foreground(callback)) {
            return callback.addToolResult("{\"success\":false,\"status\":\"failed\",\"error\":\"foreground_action_boundary\"}", toolCallId);
        }
        if (!(callback instanceof TaskCallback task)) {
            return callback.addToolResult("{\"success\":false,\"status\":\"failed\",\"error\":\"task_execution_only\"}", toolCallId);
        }
        String kind = request.has("kind") ? request.get("kind").getAsString() : "other";
        JsonObject facts = request.has("facts") && request.get("facts").isJsonObject()
                ? request.getAsJsonObject("facts") : new JsonObject();
        List<String> options = new ArrayList<>();
        if (request.has("options") && request.get("options").isJsonArray()) {
            for (var value : request.getAsJsonArray("options"))
                if (value.isJsonPrimitive() && !value.getAsString().isBlank()) options.add(value.getAsString());
        }
        JsonObject result = AgentRuntime.raiseOwnerQuestion(callback.getMaid(), kind, facts, options, "");
        return callback.addToolResult(result.toString(), toolCallId);
    }

    @Override
    public Component invocationSummaryComponent(JsonObject request) {
        return Component.translatable("tool.touhou_aifun.request_owner_input").withStyle(ChatFormatting.GRAY);
    }
}
