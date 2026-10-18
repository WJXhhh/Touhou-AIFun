package com.wjx.touhou_aifun.compat.ai.history;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ITool;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.IntegerParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.ObjectParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.Parameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.StringParameter;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.wjx.touhou_aifun.chat.context.AIFunMemoryManager;
import com.wjx.touhou_aifun.chat.context.ToolCallHistory;
import com.wjx.touhou_aifun.chat.context.ToolCallRecord;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * On-demand access to the maid's last 100 tool calls. It is deliberately NOT injected into every
 * request: the model can page through it only when it needs to recall what it actually did.
 */
public final class ToolHistoryTool implements ITool<ToolHistoryTool.Request> {
    public static final String TOOL_ID = "read_tool_history";
    private static final Codec<Request> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.optionalFieldOf("offset", 0).forGetter(Request::offset),
            Codec.INT.optionalFieldOf("limit", 10).forGetter(Request::limit),
            Codec.STRING.optionalFieldOf("tool", "").forGetter(Request::tool)
    ).apply(instance, Request::new));

    @Override
    public String id() {
        return TOOL_ID;
    }

    @Override
    public String summary(EntityMaid maid) {
        return "Read the maid's recent tool-call history: up to the last 100 actions, each with tool name, "
                + "status, game time and a summary of at most 100 characters. Use it to recall what was "
                + "actually done before (where items went, which target was opened, what failed) instead of "
                + "guessing. Page newest-first with offset/limit and optionally filter by tool name.";
    }

    @Override
    public Parameter parameters(ObjectParameter root, EntityMaid maid) {
        root.addProperties("offset", IntegerParameter.create().setMinimum(0)
                .setDescription("Number of newest records to skip; default 0."), false);
        root.addProperties("limit", IntegerParameter.create().setMinimum(1).setMaximum(50)
                .setDescription("Number of records to return; default 10, max 50."), false);
        root.addProperties("tool", StringParameter.create().setMaxLength(64)
                .setDescription("Optional tool-id filter, e.g. open_gui or scan_surroundings."), false);
        return root;
    }

    @Override
    public Codec<Request> codec() {
        return CODEC;
    }

    @Override
    public LLMCallback onCall(String toolCallId, Request request, LLMCallback callback) {
        List<ToolCallRecord> records = AIFunMemoryManager.recentToolCalls(callback.getMaid(),
                request.offset(), ToolCallHistory.pageSize(request.limit()), request.tool());
        JsonObject result = new JsonObject();
        result.addProperty("status", records.isEmpty() ? "empty" : "ok");
        result.addProperty("capacity", ToolCallHistory.MAX_RECORDS);
        result.addProperty("newest_first", true);
        result.addProperty("summary_note", "Each summary is truncated to 100 characters; raw results stay in task evidence.");
        JsonArray entries = new JsonArray();
        long now = callback.getMaid().level().getGameTime();
        for (ToolCallRecord record : records) {
            JsonObject entry = new JsonObject();
            entry.addProperty("tool", record.tool());
            entry.addProperty("status", record.status());
            entry.addProperty("summary", record.summary());
            entry.addProperty("ago_ticks", Math.max(0, now - record.gameTime()));
            entries.add(entry);
        }
        result.add("records", entries);
        return callback.addToolResult(result.toString(), toolCallId);
    }

    @Override
    public Component invocationSummaryComponent(Request request) {
        return Component.literal("read tool history").withStyle(ChatFormatting.GRAY);
    }

    public record Request(int offset, int limit, String tool) {
    }
}
