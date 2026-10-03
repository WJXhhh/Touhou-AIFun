package com.wjx.touhou_aifun.compat.ai.action;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ITool;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.*;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMClient;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonObject;
import com.mojang.serialization.*;
import com.wjx.touhou_aifun.chat.ChatFlowManager;
import com.wjx.touhou_aifun.maid.gui.MaidGuiSessionManager;
import com.wjx.touhou_aifun.vision.*;
import net.minecraft.network.chat.Component;

import java.util.concurrent.CompletableFuture;

/** Five tools share the same server-authoritative dispatcher and error contract. */
public final class GuiTool implements ITool<JsonObject> {
    public static final String[] IDS = {"open_gui", "inspect_gui", "gui_action", "wait_gui", "close_gui"};
    private final String id;
    public GuiTool(String id) { this.id = id; }
    @Override public String id() { return id; }
    @Override public String summary(EntityMaid maid) {
        return switch (id) {
            case "open_gui" -> "Walk to and open a block/entity GUI, or use a maid-held item. Uses the maid's own inventory. "
                    + "Choose NO_WAIT when told not to wait; AUTO otherwise; UNTIL_GOAL when told to finish and bring products back.";
            case "inspect_gui" -> "Inspect current menu slots, controls, process state and inventory. visual=true captures the actual mod GUI in the background.";
            case "gui_action" -> "Operate an observed GUI: transfer, click_slot, quick_move, button, rename, trade, backpack_to_cursor, "
                    + "cursor_to_backpack, click, widget, type, key or scroll. Visual input requires the latest frame_id and layout. Unknown mod protocols require an adapter.";
            case "wait_gui" -> "Wait for and collect a specified output item/count using server process state. AUTO waits only short jobs, "
                    + "NO_WAIT leaves immediately, UNTIL_GOAL has a cumulative budget. blocked_* permits repair and another wait without resetting budget. "
                    + "A completed result means the products were actually delivered to the maid, not just started.";
            default -> "Close the maid's GUI and return cursor/temporary crafting items. Does not cancel processing in the machine.";
        };
    }
    @Override public Parameter parameters(ObjectParameter root, EntityMaid maid) {
        if (!id.equals("open_gui")) text(root, "session_id", "Session id from open_gui.", true);
        switch (id) {
            case "open_gui" -> {
                text(root, "target", "Nearby block registry id/name, or nearest. Explicit coordinates support mod blocks without MenuProvider discovery.", false);
                for (String key : new String[]{"x", "y", "z"}) integer(root, key, "Exact block coordinate; supply all three together.", false);
                text(root, "entity_uuid", "Nearby entity UUID instead of a block.", false);
                integer(root, "item_slot", "Use a held item: 0=main hand, 40=offhand, 1..35=backpack 0..34.", false);
                integer(root, "max_distance", "Search radius 1..16, default 12.", false);
                text(root, "wait_policy", "NO_WAIT, AUTO, or UNTIL_GOAL. Explicit don't-wait overrides implicit collection.", true);
            }
            case "inspect_gui" -> root.addProperties("visual", BoolParameter.create().setDescription("Request an actual background GUI image and widget geometry."), false);
            case "gui_action" -> {
                text(root, "action", "transfer, click_slot, quick_move, button, rename, trade, backpack_to_cursor, cursor_to_backpack, click, widget, type, key, scroll, or registered adapter action.", true);
                for (String key : new String[]{"slot", "to_slot", "count", "button", "button_id", "trade_index", "backpack_slot", "x", "y", "frame_id", "scroll", "key_code"}) integer(root, key, "Action argument; slot ids/geometry must come from inspection. Transfer count 1..64; key uses GLFW key code, no modifiers.", false);
                text(root, "text", "Text to enter or rename.", false);
                text(root, "widget_id", "Observed widget id. type sets an EditBox; omit for coordinate focus + original Screen charTyped input.", false);
                text(root, "layout", "Layout token from the latest visual inspection.", false);
            }
            case "wait_gui" -> {
                text(root, "item", "Exact output item registry id, e.g. minecraft:iron_ingot.", true);
                integer(root, "count", "Total target count for this task, 1..4096, including products already collected.", true);
                integer(root, "output_slot", "Output slot id; furnace defaults to 2. Other menus can infer noninsertable output slots.", false);
                integer(root, "max_wait_seconds", "Player's explicit maximum wait, if any. Omit otherwise. Cannot recharge task budget.", false);
            }
        }
        return root;
    }
    private static void text(ObjectParameter root, String key, String description, boolean required) {
        root.addProperties(key, StringParameter.create().setDescription(description).setMaxLength(512), required);
    }
    private static void integer(ObjectParameter root, String key, String description, boolean required) {
        root.addProperties(key, IntegerParameter.create().setDescription(description), required);
    }
    @Override public Codec<JsonObject> codec() {
        return Codec.PASSTHROUGH.comapFlatMap(dynamic -> {
            var value = dynamic.convert(JsonOps.INSTANCE).getValue();
            return value.isJsonObject() ? DataResult.success(value.getAsJsonObject()) : DataResult.error(() -> "Expected GUI arguments object");
        }, json -> new Dynamic<>(JsonOps.INSTANCE, json));
    }
    @Override public LLMCallback onCall(String callId, JsonObject request, LLMCallback callback) {
        return callback.addToolResult(MaidGuiSessionManager.error("async_dispatch_required").toString(), callId);
    }
    @Override public CompletableFuture<LLMCallback> onCallAsync(String callId, JsonObject request, LLMCallback callback, LLMClient client) {
        CompletableFuture<LLMCallback> result = new CompletableFuture<>();
        callback.runOnServerThread(() -> MaidGuiSessionManager.call(callback, id, callId, request)
                .thenCompose(value -> GuiVisualObservation.route(callback, client, value))
                .whenComplete((value, failure) -> callback.runOnServerThread(() -> {
                    if (ChatFlowManager.isSuperseded(callback.getMaid().getUUID(), callback)) { result.complete(callback); return; }
                    result.complete(callback.addToolResult((failure == null ? value : MaidGuiSessionManager.error("gui_operation_failed")).toString(), callId));
                })));
        return result;
    }
    @Override public Component invocationSummaryComponent(JsonObject request) {
        return Component.translatable("tool.touhou_aifun." + id);
    }
}
