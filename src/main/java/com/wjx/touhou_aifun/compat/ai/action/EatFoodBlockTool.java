package com.wjx.touhou_aifun.compat.ai.action;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ITool;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.IntegerParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.ObjectParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.Parameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.StringParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMClient;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.wjx.touhou_aifun.chat.ChatFlowManager;
import com.wjx.touhou_aifun.maid.action.MaidEatFoodActionManager;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.util.concurrent.CompletableFuture;

/** LLM-facing entry point for a physically executed, server-validated block-food action. */
public final class EatFoodBlockTool implements ITool<EatFoodBlockTool.Request> {
    public static final String TOOL_ID = "eat_food_block";
    private static final Codec<Request> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.optionalFieldOf("food", "any").forGetter(Request::food),
            Codec.INT.optionalFieldOf("max_distance", 12).forGetter(Request::maxDistance)
    ).apply(instance, Request::new));

    @Override
    public String id() {
        return TOOL_ID;
    }

    @Override
    public String summary(EntityMaid maid) {
        return "Use when the user orders the maid to go eat a placed edible block, especially cake on a snack cabinet. "
                + "The tool finds a valid edible through TLM's edible-block registry, walks to it, revalidates it, "
                + "and returns success only after it is actually consumed.";
    }

    @Override
    public Parameter parameters(ObjectParameter root, EntityMaid maid) {
        StringParameter food = StringParameter.create()
                .setDescription("Optional registry id or short food name, for example minecraft:cake or cake. "
                        + "Use any for the nearest edible block.")
                .setDefaultValue("any")
                .setMaxLength(128);
        IntegerParameter maxDistance = IntegerParameter.create()
                .setDescription("Search radius in blocks; clamped to 1..16.")
                .setMinimum(1).setMaximum(16).setDefaultValue("12");
        root.addProperties("food", food, false);
        root.addProperties("max_distance", maxDistance, false);
        return root;
    }

    @Override
    public Codec<Request> codec() {
        return CODEC;
    }

    @Override
    public LLMCallback onCall(String toolCallId, Request request, LLMCallback callback) {
        return callback.addToolResult("{\"success\":false,\"status\":\"async_dispatch_required\","
                + "\"detail\":\"This physical action must use the asynchronous tool dispatcher.\"}",
                toolCallId);
    }

    @Override
    public CompletableFuture<LLMCallback> onCallAsync(String toolCallId, Request request,
                                                       LLMCallback callback, LLMClient client) {
        CompletableFuture<LLMCallback> result = new CompletableFuture<>();
        Runnable start = () -> {
            if (ChatFlowManager.isSuperseded(callback.getMaid().getUUID(), callback)) {
                result.complete(callback);
                return;
            }
            CompletableFuture<MaidEatFoodActionManager.Result> action = MaidEatFoodActionManager.start(
                    callback.getMaid(), request.food(), request.maxDistance(), callback);
            ChatFlowManager.setInFlight(callback.getMaid().getUUID(), callback, action);
            action.whenComplete((outcome, throwable) -> {
                if (ChatFlowManager.isSuperseded(callback.getMaid().getUUID(), callback)) {
                    result.complete(callback);
                } else if (throwable != null || outcome == null) {
                    result.complete(callback.addToolResult(
                            "{\"success\":false,\"status\":\"cancelled\","
                                    + "\"detail\":\"The eat-food action was cancelled.\"}", toolCallId));
                } else {
                    result.complete(callback.addToolResult(outcome.toJson(), toolCallId));
                }
            });
        };
        if (callback.isOnServerThread()) {
            start.run();
        } else {
            callback.runOnServerThread(start);
        }
        return result;
    }

    @Override
    public Component invocationSummaryComponent(Request request) {
        return Component.translatable("tool.touhou_aifun.eat_food_block")
                .withStyle(ChatFormatting.GRAY);
    }

    public record Request(String food, int maxDistance) {
        public Request {
            food = food == null || food.isBlank() ? "any" : food.trim();
            if (food.length() > 128) {
                food = food.substring(0, 128);
            }
            maxDistance = Math.max(1, Math.min(16, maxDistance));
        }
    }
}
