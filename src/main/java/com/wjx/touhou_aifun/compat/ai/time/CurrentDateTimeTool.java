package com.wjx.touhou_aifun.compat.ai.time;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ITool;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.ObjectParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.Parameter;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/** On-demand access to the server host's real-world clock; nothing is injected every turn. */
public final class CurrentDateTimeTool implements ITool<CurrentDateTimeTool.Request> {
    public static final String TOOL_ID = "get_current_datetime";
    private static final Request REQUEST = new Request();
    private static final Codec<Request> CODEC = Codec.unit(REQUEST);

    @Override
    public String id() {
        return TOOL_ID;
    }

    @Override
    public String summary(EntityMaid maid) {
        // Keep the mandatory rule at the front and below ToolCatalogSnapshot's 180-code-point cap.
        return "MUST call before answering about the current real-world date/time or relative dates: "
                + "today, yesterday, tomorrow, or this week. "
                + "Never guess from model knowledge. No parameters.";
    }

    @Override
    public Parameter parameters(ObjectParameter root, EntityMaid maid) {
        return root;
    }

    @Override
    public Codec<Request> codec() {
        return CODEC;
    }

    @Override
    public LLMCallback onCall(String toolCallId, Request request, LLMCallback callback) {
        return callback.addToolResult(format(Instant.now(), ZoneId.systemDefault()), toolCallId);
    }

    @Override
    public Component invocationSummaryComponent(Request request) {
        return Component.translatable("tool.touhou_aifun.get_current_datetime")
                .withStyle(ChatFormatting.GRAY);
    }

    static String format(Instant instant, ZoneId zone) {
        ZonedDateTime value = instant.atZone(zone);
        JsonObject result = new JsonObject();
        result.addProperty("iso_8601", DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(value));
        result.addProperty("date", DateTimeFormatter.ISO_LOCAL_DATE.format(value));
        result.addProperty("time", DateTimeFormatter.ISO_LOCAL_TIME.format(value));
        result.addProperty("day_of_week", value.getDayOfWeek().name());
        result.addProperty("timezone", zone.getId());
        result.addProperty("utc_offset", value.getOffset().getId());
        result.addProperty("unix_epoch_seconds", instant.getEpochSecond());
        return result.toString();
    }

    public static final class Request {
        private Request() {
        }
    }
}
