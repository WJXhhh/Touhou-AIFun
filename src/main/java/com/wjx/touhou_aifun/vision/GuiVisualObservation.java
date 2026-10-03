package com.wjx.touhou_aifun.vision;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMClient;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wjx.touhou_aifun.config.TouhouAIFunConfig;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** GUI images use the same provider/protocol routing without entering persisted chat history. */
public final class GuiVisualObservation {
    private GuiVisualObservation() { }
    public static CompletableFuture<JsonObject> route(LLMCallback callback, LLMClient client, JsonObject value) {
        if (!value.has("_gui_image")) return CompletableFuture.completedFuture(value);
        String image = value.remove("_gui_image").getAsString();
        if (!TouhouAIFunConfig.VISION_ENABLED.get()) {
            value.addProperty("image_status", "vision_disabled"); return CompletableFuture.completedFuture(value);
        }
        var maid = callback.getMaid(); long tick = maid.level().getGameTime();
        ObservationSnapshot snapshot = new ObservationSnapshot(UUID.randomUUID().toString(), maid.getUUID(), maid.getOwnerUUID(),
                maid.level().dimension().location().toString(), System.currentTimeMillis(), tick, tick, Float.NaN,
                Map.of("gui", image), null, "not_requested", "GUI", value.deepCopy());
        ObservationSnapshotCache.INSTANCE.put(snapshot);
        if (MultimodalTurnContext.canAttach(callback, client)) {
            MultimodalTurnContext.attach(callback, snapshot, "Identify GUI controls and operate the user's task. Coordinates use the logical GUI dimensions in metadata.");
            value.addProperty("image_status", "attached_to_main_model"); value.addProperty("observation_id", snapshot.id());
            return CompletableFuture.completedFuture(value);
        }
        return VisionObservationManager.identify(callback, snapshot,
                "Describe controls, labels and their center coordinates in logical GUI coordinates; report uncertainty for ambiguous targets.", ignored -> { })
                .thenApply(identified -> { value.add("visual_observation", JsonParser.parseString(identified)); return value; });
    }
}
