package com.wjx.touhou_aifun.compat.ai.vision;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ITool;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMClient;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.IntegerParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.ObjectParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.Parameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.StringParameter;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.serialization.Codec;
import com.wjx.touhou_aifun.config.TouhouAIFunConfig;
import com.wjx.touhou_aifun.vision.scan.EnvironmentScanRequest;
import com.wjx.touhou_aifun.vision.scan.ScanDirection;
import com.wjx.touhou_aifun.vision.scan.ScanMode;
import com.wjx.touhou_aifun.vision.scan.VisionScanCache;
import com.wjx.touhou_aifun.chat.ChatFlowManager;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.util.concurrent.CompletableFuture;

/** Server-only code-level grounding tool. */
public final class ScanSurroundingsTool implements ITool<EnvironmentScanRequest> {
    public static final String TOOL_ID = "scan_surroundings";

    @Override
    public String id() {
        return TOOL_ID;
    }

    @Override
    public String summary(EntityMaid maid) {
        return "Perform a server-side 360-degree shallow scan of nearby blocks and entities without sending an image to any provider. "
                + "Use this before making precise claims about a block/entity identity, state, position, count, or danger. "
                + "It reads only loaded chunks, follows weighted transparency, and returns exact registry ids for visible important targets.";
    }

    @Override
    public Parameter parameters(ObjectParameter root, EntityMaid maid) {
        StringParameter mode = StringParameter.create()
                .setDescription("Scan blocks, entities, or both.")
                .setDefaultValue("both")
                .addEnumValues("blocks", "entities", "both");
        StringParameter direction = StringParameter.create()
                .setDescription("Cubemap face to prioritize, or all for a complete 360-degree scan.")
                .setDefaultValue("all")
                .addEnumValues("all", "front", "right", "back", "left", "up", "down");
        IntegerParameter distance = IntegerParameter.create()
                .setDescription("Maximum distance in blocks; clamped to 1..20.")
                .setMinimum(1).setMaximum(20).setDefaultValue("20");
        StringParameter focus = StringParameter.create()
                .setDescription("Optional target to prioritize in the summary; it never bypasses visibility rules.")
                .setMaxLength(256);
        root.addProperties("mode", mode, false);
        root.addProperties("direction", direction, false);
        root.addProperties("max_distance", distance, false);
        root.addProperties("focus", focus, false);
        return root;
    }

    @Override
    public Codec<EnvironmentScanRequest> codec() {
        return EnvironmentScanRequest.CODEC;
    }

    @Override
    public LLMCallback onCall(String toolCallId, EnvironmentScanRequest request, LLMCallback callback) {
        // TLM 1.5.3 dispatches tools through onCallAsync. Never let a direct compatibility call
        // bypass the server-wide per-tick ray and DDA budgets with a synchronous 360-degree scan.
        return callback.addToolResult("{\"status\":\"failed\",\"error\":\"async_dispatch_required\","
                + "\"uncertainties\":[\"The shallow scan must use the asynchronous tool dispatcher.\"]}",
                toolCallId);
    }

    @Override
    public CompletableFuture<LLMCallback> onCallAsync(String toolCallId, EnvironmentScanRequest request,
                                                       LLMCallback callback, LLMClient client) {
        EnvironmentScanRequest normalized = normalize(request);
        CompletableFuture<LLMCallback> next = new CompletableFuture<>();
        callback.runOnServerThread(() -> VisionScanCache.scanAsync(callback.getMaid(), normalized)
                .whenComplete((result, throwable) -> callback.runOnServerThread(() -> {
                    if (ChatFlowManager.isSuperseded(callback.getMaid().getUUID(), callback)) {
                        next.complete(callback);
                        return;
                    }
                    String value;
                    if (throwable != null || result == null) {
                        value = "{\"status\":\"failed\",\"error\":\"server scan failed\"}";
                    } else {
                        value = result.toJson();
                    }
                    next.complete(callback.addToolResult(value, toolCallId));
                })));
        return next;
    }

    @Override
    public boolean trigger(EntityMaid maid, com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.request.ChatCompletion chatCompletion) {
        return TouhouAIFunConfig.SHALLOW_SCAN_ENABLED.get();
    }

    @Override
    public String invocationSummary(EnvironmentScanRequest result) {
        return "scan surroundings (%s)".formatted(result == null ? "both" : result.mode().name().toLowerCase());
    }

    @Override
    public Component invocationSummaryComponent(EnvironmentScanRequest result) {
        String mode = result == null ? "both" : result.mode().name().toLowerCase();
        return Component.translatable("tool.touhou_aifun.scan_surroundings", mode)
                .withStyle(ChatFormatting.GRAY);
    }

    private static EnvironmentScanRequest normalize(EnvironmentScanRequest request) {
        if (request == null) {
            return EnvironmentScanRequest.defaults();
        }
        return new EnvironmentScanRequest(request.mode(), request.direction(), request.maxDistance(), request.focus());
    }
}
