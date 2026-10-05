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

/** GUI tools share the same server-authoritative dispatcher and error contract. */
public final class GuiTool implements ITool<JsonObject> {
    public static final String[] IDS = {"open_gui", "inspect_gui", "inspect_containers", "gui_action", "wait_gui", "close_gui", "gui_batch"};
    private final String id;
    public GuiTool(String id) { this.id = id; }
    @Override public String id() { return id; }
    @Override public boolean trigger(EntityMaid maid,com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.request.ChatCompletion request) {
        return !id.equals("inspect_containers") || com.wjx.touhou_aifun.chat.agent.AgentRuntime.enabled(maid);
    }
    @Override public String summary(EntityMaid maid) {
        return switch (id) {
            case "inspect_containers" -> "Inspect up to eight exact chest targets in one ordered call using normal GUI access. Returns item counts for planning, keeps the final menu open with its current snapshot. Use when collecting all items with unknown quantities; never inspect each chest in separate model rounds. Counts from closed menus do not authorize slot actions.";
            case "gui_batch" -> "Execute at most 16 observed semantic GUI actions in order. Requires expected_revision. Stops at first failure; earlier changes remain committed. Use close_after=true when done with this menu; complete_task=true on the final batch closes and requests verified completion without another model turn.";
            case "open_gui" -> "Walk to and open a block/entity GUI, or use a maid-held item. Uses the maid's own inventory. "
                    + "Choose NO_WAIT when told not to wait; AUTO otherwise; UNTIL_GOAL when told to finish and bring products back.";
            case "inspect_gui" -> "Inspect current menu slots, controls, process state and inventory. visual=true captures the actual mod GUI in the background.";
            case "gui_action" -> "Operate an observed GUI: transfer, click_slot, quick_move, button, rename, trade, backpack_to_cursor, "
                    + "cursor_to_backpack, click, widget, type, key or scroll. Use close_after=true when done with this menu, complete_task=true for the final action. Visual input requires the latest frame_id and layout. Unknown mod protocols require an adapter.";
            case "wait_gui" -> "Wait for and collect a specified output item/count using server process state. AUTO waits only short jobs, "
                    + "NO_WAIT leaves immediately, UNTIL_GOAL has a cumulative budget. blocked_* permits repair and another wait without resetting budget. "
                    + "A completed result means the products were actually delivered to the maid, not just started.";
            default -> "Close the maid's GUI and return cursor/temporary crafting items. Does not cancel processing in the machine.";
        };
    }
    @Override public Parameter parameters(ObjectParameter root, EntityMaid maid) {
        if (!id.equals("open_gui") && !id.equals("inspect_containers")) text(root, "session_id", "Session id from open_gui.", true);
        text(root, "detail", "summary (default for mutations) or full snapshot.", false);
        switch (id) {
            case "inspect_containers" -> {
                ObjectParameter target=ObjectParameter.create();
                for(String key:new String[]{"x","y","z"}) integer(target,key,"Exact WORLD container block coordinate from current scan, not the sign position.",true);
                text(target,"target","Observed block registry id; default minecraft:chest.",false);
                root.addProperties("targets",ArrayParameter.create().setItems(target).setRange(1,8),true);
            }
            case "gui_batch" -> {
                integer(root, "expected_revision", "Revision of latest inspected snapshot.", true);
                ObjectParameter action = ObjectParameter.create();
                action.addProperties("action", StringParameter.create().addEnumValues("transfer", "click_slot", "quick_move", "button", "rename", "trade", "backpack_to_cursor", "cursor_to_backpack")
                        .setDescription("Observed semantic operation. Inspect and close are separate tools and cannot be included in this batch."), true);
                integer(action,"slot","Source/clicked MENU slot id. For transfer this is the source; from_slot is an equivalent alias.",false);
                integer(action,"from_slot","Alias of slot for the source menu slot. If both are supplied they must agree.",false);
                integer(action,"to_slot","Destination MENU slot id from slots, not a backpack_slot index. For delivery choose maid_storage=backpack.",false);
                for (String key : new String[]{"count", "button", "button_id", "trade_index", "backpack_slot"}) integer(action, key, "Observed action argument.", false);
                text(action, "text", "Rename text.", false);
                root.addProperties("actions", ArrayParameter.create().setItems(action).setRange(1, 16), true);
            }
            case "open_gui" -> {
                text(root, "target", "Nearby block registry id/name, or nearest. An exact target can also be registry_id@x,y,z, e.g. minecraft:chest@10,64,-2; its block id is verified before interaction. Prefer separate x/y/z fields when available.", false);
                for (String key : new String[]{"x", "y", "z"}) integer(root, key, "Exact WORLD block coordinate by default; supply all three together. For current maid block offsets set coordinate_space=maid_relative. Never copy relative_position into world coordinates.", false);
                text(root, "coordinate_space", "world (default) or maid_relative (offset from maid's CURRENT block position). Omit x/y/z to find the nearest target. Old scan offsets must be refreshed if the maid moved.", false);
                text(root, "entity_uuid", "Nearby entity UUID instead of a block.", false);
                integer(root, "item_slot", "Use a held item: 0=main hand, 40=offhand, 1..35=backpack 0..34.", false);
                integer(root, "max_distance", "Search radius 1..16, default 12.", false);
                text(root, "wait_policy", "NO_WAIT, AUTO, or UNTIL_GOAL. Explicit don't-wait overrides implicit collection.", true);
            }
            case "inspect_gui" -> root.addProperties("visual", BoolParameter.create().setDescription("Request an actual background GUI image and widget geometry."), false);
            case "gui_action" -> {
                text(root, "action", "transfer, click_slot, quick_move, button, rename, trade, backpack_to_cursor, cursor_to_backpack, click, widget, type, key, scroll, or registered adapter action.", true);
                for (String key : new String[]{"slot", "to_slot", "count", "button", "button_id", "trade_index", "backpack_slot", "x", "y", "frame_id", "scroll", "key_code"}) integer(root, key, "Action argument; slot ids/geometry must come from inspection. Transfer count 1..64; key uses GLFW key code, no modifiers.", false);
                integer(root,"from_slot","Source menu slot alias for slot in transfer, click_slot or quick_move. Conflicting aliases are rejected.",false);
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
        if(id.equals("gui_action") || id.equals("gui_batch")) {
            root.addProperties("close_after",BoolParameter.create().setDescription("Close and settle the GUI after successful actions in this call; omit for further operations in the same menu. Failure leaves it open for inspection."),false);
            root.addProperties("complete_task",BoolParameter.create().setDescription("Action-task executor only. Explicitly declare these are the FINAL actions covering ALL owner requirements. Implies close_after. Runtime verifies the complete registered contract after actual actions, cleanup and ordered result settlement; unmet conditions keep progress and do not complete. Use on the last transfer instead of waiting another model round to close and report."),false);
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
        callback.runOnServerThread(() -> dispatch(callback,callId,request)
                .thenCompose(value -> GuiVisualObservation.route(callback, client, value))
                .whenComplete((value, failure) -> callback.runOnServerThread(() -> {
                    if (!(callback instanceof com.wjx.touhou_aifun.chat.agent.TaskCallback) && ChatFlowManager.isSuperseded(callback.getMaid().getUUID(), callback)) { result.complete(callback); return; }
                    result.complete(callback.addToolResult((failure == null ? value : MaidGuiSessionManager.error("gui_operation_failed")).toString(), callId));
                })));
        return result;
    }
    private CompletableFuture<JsonObject> dispatch(LLMCallback callback,String callId,JsonObject request) {
        if(id.equals("inspect_containers")) return com.wjx.touhou_aifun.maid.gui.GuiContainerInspection.inspect(callback,callId,request);
        boolean action=id.equals("gui_action") || id.equals("gui_batch");
        if(action) for(String flag:new String[]{"complete_task","close_after"}) if(request.has(flag)
                && (!request.get(flag).isJsonPrimitive() || !request.getAsJsonPrimitive(flag).isBoolean()))
            return CompletableFuture.completedFuture(MaidGuiSessionManager.error("invalid_finalization_flag"));
        boolean complete=action && request.has("complete_task") && request.get("complete_task").getAsBoolean();
        boolean close=complete || action && request.has("close_after") && request.get("close_after").getAsBoolean();
        if(complete && !(callback instanceof com.wjx.touhou_aifun.chat.agent.TaskCallback))
            return CompletableFuture.completedFuture(MaidGuiSessionManager.error("task_execution_only"));
        return MaidGuiSessionManager.call(callback,id,callId,request).thenCompose(value -> {
            if(!close || value.has("error")) return CompletableFuture.completedFuture(value);
            JsonObject closing=new JsonObject();closing.add("session_id",request.get("session_id"));
            return MaidGuiSessionManager.call(callback,"close_gui",callId+":cleanup",closing).thenApply(cleanup -> {
                value.add("cleanup",cleanup);
                if(cleanup.has("error") || cleanup.has("dropped_count") && cleanup.get("dropped_count").getAsInt()!=0) {
                    value.addProperty("error","gui_cleanup_unsettled");return value;
                }
                if(complete) {
                    var task=(com.wjx.touhou_aifun.chat.agent.TaskCallback)callback;
                    try {task.requestCompletion();task.task.currentStep=Math.max(0,task.task.steps.size()-1);value.addProperty("completion_requested",true);}
                    catch(IllegalArgumentException rejected) {
                        value.addProperty("completion_requested",false);value.addProperty("completion_error",rejected.getMessage());
                    }
                }
                return value;
            });
        });
    }
    @Override public Component invocationSummaryComponent(JsonObject request) {
        return Component.translatable("tool.touhou_aifun." + id);
    }
}
