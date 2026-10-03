package com.wjx.touhou_aifun.compat.ai.vision;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ITool;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.*;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMClient;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.request.ChatCompletion;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.wjx.touhou_aifun.chat.ChatFlowManager;
import com.wjx.touhou_aifun.config.TouhouAIFunConfig;
import com.wjx.touhou_aifun.vision.VisionObservationManager;
import java.util.concurrent.CompletableFuture;

/** Historical view, deliberately separate from observe_surroundings (which takes fresh images). */
public final class ReviewObservationTool implements ITool<ReviewObservationTool.Request> {
    public static final String TOOL_ID = "review_observation";
    public record Request(String action, String observationId, String focus) {
        public static final Codec<Request> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.optionalFieldOf("action", "view").forGetter(Request::action),
                Codec.STRING.optionalFieldOf("observation_id", "").forGetter(Request::observationId),
                Codec.STRING.optionalFieldOf("focus", "").forGetter(Request::focus)
        ).apply(instance, Request::new));
    }
    @Override public String id() { return TOOL_ID; }
    @Override public String summary(EntityMaid maid) {
        return "List or view previously captured screenshots for this maid, without capturing the current world. "
                + "Use list to find observation IDs; view without an ID loads the latest. Images are historical, expire after an hour, "
                + "and must never be described as live. Use observe_surroundings to look again now.";
    }
    @Override public Parameter parameters(ObjectParameter root, EntityMaid maid) {
        root.addProperties("action", StringParameter.create().addEnumValues("list", "view").setDefaultValue("view"), false);
        root.addProperties("observation_id", StringParameter.create().setMaxLength(64)
                .setDescription("ID from observe_surroundings or list; omit for latest."), false);
        root.addProperties("focus", StringParameter.create().setMaxLength(256).setDescription("Question about the historical images."), false);
        return root;
    }
    @Override public Codec<Request> codec() { return Request.CODEC; }
    @Override public LLMCallback onCall(String id, Request request, LLMCallback callback) {
        return callback.addToolResult("{\"status\":\"failed\",\"error\":\"async_review_required\"}", id);
    }
    @Override public CompletableFuture<LLMCallback> onCallAsync(String id, Request request, LLMCallback callback, LLMClient client) {
        CompletableFuture<LLMCallback> result = new CompletableFuture<>();
        callback.runOnServerThread(() -> VisionObservationManager.review(callback, client,
                request.action(), request.observationId(), request.focus(), stage -> ObserveSurroundingsTool.updateProgress(callback, stage))
                .whenComplete((value, error) -> callback.runOnServerThread(() -> {
                    if (!ChatFlowManager.isSuperseded(callback.getMaid().getUUID(), callback)) {
                        callback.addToolResult(error == null ? value : "{\"status\":\"failed\",\"error\":\"review_failed\"}", id);
                    }
                    result.complete(callback);
                })));
        return result;
    }
    @Override public boolean trigger(EntityMaid maid, ChatCompletion request) { return TouhouAIFunConfig.VISION_ENABLED.get(); }
}
