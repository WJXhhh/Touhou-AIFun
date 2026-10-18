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
public class GuiTool implements ITool<JsonObject> {
    public static final String[] IDS = {"open_gui", "inspect_gui", "inspect_containers", "query_recipes", "gui_action", "wait_gui", "close_gui", "gui_batch"};
    private final String id;
    public GuiTool(String id) { this.id = id; }
    @Override public String id() { return id; }
    @Override public boolean trigger(EntityMaid maid,com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.request.ChatCompletion request) {
        return !id.equals("inspect_containers") || com.wjx.touhou_aifun.chat.agent.AgentRuntime.enabled(maid);
    }
    @Override public String summary(EntityMaid maid) {
        return summary(maid, false);
    }
    /** Completion belongs to a TaskCallback, never to an ordinary chat or native client. */
    public String summary(EntityMaid maid, boolean taskExecutor) {
        String summary = switch (id) {
            case "query_recipes" -> "Query CURRENT server-loaded crafting AND cooking recipes by exact output item id or recipe_id, including mod/datapack/script replacements and ingredient alternatives. No GUI session needed. Use before crafting; never guess a pack's recipe. Reports whether one craft can be filled from unlocked maid backpack. Rows identify recipe_type, station and cooking time; smelting glass is discoverable here. Operate cooking recipes through the actual furnace GUI, not craft_recipe.";
            case "inspect_containers" -> "Inspect up to eight exact chest targets in one ordered call using normal GUI access. Returns item counts for planning, keeps the final menu open with its current snapshot. Use when collecting all items with unknown quantities; never inspect each chest in separate model rounds. Counts from closed menus do not authorize slot actions.";
            case "gui_batch" -> "Execute at most 16 observed semantic GUI actions in order. Requires expected_revision. Stops at first failure; earlier changes remain committed. Use close_after=true when done with this menu.";
            case "open_gui" -> "Walk to and open a block/entity GUI, or use a maid-held item. Uses the maid's own inventory. "
                    + "Choose NO_WAIT when told not to wait; AUTO otherwise; UNTIL_GOAL when told to finish and bring products back.";
            case "inspect_gui" -> "Inspect current menu slots, controls, process state and inventory. visual=true captures the actual mod GUI in the background.";
            case "gui_action" -> "Operate an observed GUI: transfer, click_slot, quick_move, button, rename, trade, backpack_to_cursor, "
                    + "cursor_to_backpack, fill_recipe, craft_recipe, click, widget, type, key or scroll. For workbenches query_recipes first: fill_recipe(recipe_id) fills ONE execution from maid backpack without taking output; craft_recipe(recipe_id,count) fills, crafts and puts at least count NEW outputs in the MAID backpack over bounded server ticks. Native batches may produce explicitly reported surplus. Subtract crafted_count before retrying partial results. Unfamiliar mod machines require inspect_gui visual=true first; identify controls/slots, then operate the original GUI. Native Forge play protocols are bridged as the maid. Verify actual server inventory changes, not click success. Use close_after=true when done. Visual input requires the latest frame_id and layout.";
            case "wait_gui" -> "Wait for and collect a specified output item/count using server process state. AUTO waits only short jobs, "
                    + "NO_WAIT leaves immediately, UNTIL_GOAL has a cumulative budget. blocked_* permits repair and another wait without resetting budget. "
                    + "A completed result means the products were actually delivered to the maid, not just started.";
            default -> "Close the maid's GUI and return cursor/temporary crafting items. Does not cancel processing in the machine.";
        };
        if (taskExecutor && java.util.Set.of("gui_action", "gui_batch").contains(id))
            summary += " For craft-only goals put complete_task=true directly on the LAST craft_recipe call; it closes/settles the menu and verifies ALL registered requirements without another model turn. Do not add an owner-delivery step unless requested. The same final flag applies to the last action/batch of other goals.";
        if (taskExecutor && java.util.Set.of("gui_action", "gui_batch", "wait_gui").contains(id))
            summary += " Task item operations require a completion contract; define it using update_task_plan BEFORE this operation, optionally in the SAME ordered tool batch.";
        return summary;
    }
    @Override public Parameter parameters(ObjectParameter root, EntityMaid maid) {
        return parameters(root, maid, false);
    }
    public Parameter parameters(ObjectParameter root, EntityMaid maid, boolean taskExecutor) {
        return parameters(root,maid,taskExecutor,true);
    }
    public Parameter parameters(ObjectParameter root, EntityMaid maid, boolean taskExecutor,boolean selectionActive) {
        if (!id.equals("open_gui") && !id.equals("inspect_containers") && !id.equals("query_recipes")) text(root, "session_id", "Session id from open_gui.", true);
        text(root, "detail", "summary (default for mutations) or full snapshot.", false);
        switch (id) {
            case "query_recipes" -> {
                root.addProperties("recipe_type", StringParameter.create().addEnumValues("all", "minecraft:crafting", "minecraft:smelting", "minecraft:blasting", "minecraft:smoking", "minecraft:campfire_cooking").setDescription("Default all: include cooking routes as well as workbench recipes."), false);
                text(root, "item", "Exact registered output item id. Supply item or recipe_id. Reads live recipes, not a static wiki.", false);
                text(root, "recipe_id", "Exact recipe id to inspect instead of searching output item.", false);
                integer(root, "count", "Desired output count, 1..4096; reports required native recipe batches.", false);
                integer(root, "offset", "Recipe-list page offset from next_offset.", false);
                integer(root, "limit", "Recipes per page 1..8, default 4.", false);
            }
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
                        .setDescription("Observed semantic operation. Inspect and close are separate tools. ProjectE semantic actions use individual gui_action calls and cannot be included in this batch."), true);
                integer(action,"slot","Source/clicked MENU slots[].slot. transfer requires slot (or from_slot), to_slot and count; choose enabled slots.",false);
                integer(action,"from_slot","Alias of slot for the source menu slot. If both are supplied they must agree.",false);
                integer(action,"to_slot","REQUIRED for transfer: enabled destination slots[].slot, never inventory_slot/backpack_slot. Receiving uses maid_storage=backpack; delivering uses maid_inventory=false.",false);
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
            case "inspect_gui" -> {
                root.addProperties("visual", BoolParameter.create().setDescription("Capture actual background GUI image and controls. Required before operating unfamiliar mod machines. Use hover_x/hover_y to inspect a button/item tooltip."), false);
                integer(root,"hover_x","Logical GUI x coordinate to show native tooltip during capture.",false);
                integer(root,"hover_y","Logical GUI y coordinate to show native tooltip during capture.",false);
            }
            case "gui_action" -> {
                text(root, "action", "transfer, click_slot, quick_move, button, rename, trade, backpack_to_cursor, cursor_to_backpack, fill_recipe, craft_recipe, projecte_search, projecte_deposit, projecte_exchange, click, widget, type, key, scroll, or registered adapter action.", true);
                text(root, "item", "Exact item id for ProjectE search/exchange. Uses owner's live learned knowledge and EMC at an opened transmutation table; outputs go to unlocked maid backpack.", false);
                text(root, "selection", "Variant token from projecte_search; required for multiple learned NBT variants of the same item. Never invent it.", false);
                integer(root, "offset", "ProjectE search offset from next_offset.", false);
                integer(root, "limit", "ProjectE search results per page, 1..16, default 8.", false);
                text(root, "recipe_id", "CURRENT workbench recipe id from query_recipes, required for fill_recipe/craft_recipe. Both use only unlocked maid backpack and the actual 3x3 workbench.", false);
                integer(root,"slot","transfer requires slot (or from_slot), to_slot and count. Use an enabled source slots[].slot from the current menu.",false);
                integer(root,"to_slot","REQUIRED for transfer: enabled destination slots[].slot in the current menu, never inventory_slot or backpack_slot. Omit for actions without a destination.",false);
                integer(root,"count","Transfer 1..64; craft_recipe 1..4096 NEW outputs, rounded up to native recipe batches.",false);
                integer(root,"button","Native mouse/slot click button.",false);
                integer(root,"button_id","Observed menu button id.",false);
                integer(root,"trade_index","Observed merchant offer index.",false);
                integer(root,"backpack_slot","Observed unlocked maid backpack slot.",false);
                integer(root,"x","GUI x coordinate from the latest visual inspection.",false);
                integer(root,"y","GUI y coordinate from the latest visual inspection.",false);
                integer(root,"frame_id","Latest visual inspection frame_id.",false);
                integer(root,"scroll","Native scroll amount.",false);
                integer(root,"key_code","GLFW key code without modifiers.",false);
                integer(root,"from_slot","Source menu slot alias for slot in transfer, click_slot or quick_move. Conflicting aliases are rejected.",false);
                text(root, "text", "Text to enter/rename, or ProjectE search by item id/name. projecte_deposit uses backpack_slot,count 1..64; projecte_exchange uses item,selection,count 1..4096 NEW items, exact quantity or preflight failure. Search includes learned items regardless of EMC balance.", false);
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
            if (taskExecutor) root.addProperties("complete_task",BoolParameter.create().setDescription("Action-task executor only. Explicitly declare these are the FINAL actions covering ALL owner requirements. Implies close_after. Runtime verifies the complete registered contract after actual actions, cleanup and ordered result settlement; unmet conditions keep progress and do not complete. Use on the last transfer instead of waiting another model round to close and report."),false);
        }
        if(taskExecutor && java.util.Set.of("open_gui","gui_action","gui_batch").contains(id))
            com.wjx.touhou_aifun.chat.agent.TaskActionHints.parameters(root,id.equals("open_gui") || selectionActive);
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
        if((request.has("next_step") || request.has("next_allowed_tools"))
                && !java.util.Set.of("open_gui","gui_action","gui_batch").contains(id))
            return CompletableFuture.completedFuture(MaidGuiSessionManager.error("task_hint_not_supported"));
        com.wjx.touhou_aifun.chat.agent.TaskActionHints.Prepared parsedHint=null;
        String rejectedHint=null;
        try {
            if(callback instanceof com.wjx.touhou_aifun.chat.agent.TaskCallback task)
                parsedHint=com.wjx.touhou_aifun.chat.agent.TaskActionHints.prepare(task,request);
            else {
                if(request.has("next_step") || request.has("next_allowed_tools"))
                    return CompletableFuture.completedFuture(MaidGuiSessionManager.error("task_execution_only"));
            }
        } catch(IllegalArgumentException invalid) {
            rejectedHint=invalid.getMessage().replace('_',' ');
        }
        final var hint=parsedHint;
        final var hintWarning=rejectedHint;
        var operation = MaidGuiSessionManager.call(callback,id,callId,request);
        if (id.equals("open_gui")) operation = operation.thenCompose(value -> {
            var session = MaidGuiSessionManager.session(callback.getMaid().getUUID());
            if (value.has("error") || session == null || !session.unfamiliarMenu()
                    || !com.wjx.touhou_aifun.config.TouhouAIFunConfig.VISION_ENABLED.get()) return CompletableFuture.completedFuture(value);
            return com.wjx.touhou_aifun.maid.gui.GuiClientBridge.request(session,"capture",new JsonObject()).thenApply(captured -> {
                if (captured.has("error")) { value.add("visual_error",captured.get("error")); return value; }
                captured.addProperty("status","opened");
                var projected = session.projection.project(captured,true,false);
                return callback instanceof com.wjx.touhou_aifun.chat.agent.TaskCallback
                        ? com.wjx.touhou_aifun.maid.gui.GuiSnapshotProjection.taskView(projected) : projected;
            });
        });
        var settled=operation.thenCompose(value -> {
            // Opening already provides an authoritative observation. Expose its follow-up tools
            // in this callback's next request, without a separate schema-loading model round.
            if (id.equals("open_gui") && !value.has("error")
                    && !(callback instanceof com.wjx.touhou_aifun.chat.agent.TaskCallback)
                    && MaidGuiSessionManager.session(callback.getMaid().getUUID()) != null) {
                    for (String tool : new String[]{"inspect_gui", "query_recipes", "gui_action", "gui_batch", "wait_gui", "close_gui"})
                    if (com.wjx.touhou_aifun.compat.ai.openai.ToolContextSelector.optionalAvailable(callback.getMaid(), callback, tool))
                        ChatFlowManager.requestToolSchema(callback.getMaid().getUUID(), callback, tool);
            }
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
        return settled.thenApply(value -> {
            if(hintWarning!=null)value.addProperty("task_hint_ignored",hintWarning);
            if(hint!=null && !complete && !value.has("error"))value.addProperty("task_hint_applied",hint.commit());
            return value;
        });
    }
    @Override public Component invocationSummaryComponent(JsonObject request) {
        return Component.translatable("tool.touhou_aifun." + id);
    }
}
