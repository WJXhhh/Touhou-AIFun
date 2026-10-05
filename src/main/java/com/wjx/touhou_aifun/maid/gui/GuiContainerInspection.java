package com.wjx.touhou_aifun.maid.gui;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.google.gson.*;
import com.wjx.touhou_aifun.chat.agent.TaskCallback;
import net.minecraft.world.inventory.ChestMenu;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Bounded inspection uses the same navigation, interaction events and cancellation as open_gui. */
public final class GuiContainerInspection {
    private GuiContainerInspection() { }
    public static CompletableFuture<JsonObject> inspect(LLMCallback callback,String callId,JsonObject request) {
        if(!(callback instanceof TaskCallback task)) return CompletableFuture.completedFuture(MaidGuiSessionManager.error("task_execution_only"));
        if(MaidGuiSessionManager.session(callback.getMaid().getUUID())!=null)
            return CompletableFuture.completedFuture(MaidGuiSessionManager.error("close_existing_gui_before_inspection"));
        JsonArray targets=request.getAsJsonArray("targets");
        if(targets==null || targets.isEmpty() || targets.size()>8)
            return CompletableFuture.completedFuture(MaidGuiSessionManager.error("invalid_target_count"));
        Set<String> seen=new HashSet<>();List<JsonObject> opens=new ArrayList<>();
        // Validate the entire batch before dispatching its first opening.
        try {
            for(var value:targets) {
                JsonObject target=value.getAsJsonObject();JsonObject open=new JsonObject();
                for(String key:List.of("x","y","z")) {
                    if(!target.has(key)) throw new IllegalArgumentException("missing_world_coordinate");
                    open.addProperty(key,MaidGuiSessionManager.number(target,key,0,Integer.MIN_VALUE,Integer.MAX_VALUE));
                }
                if(!seen.add(open.toString())) throw new IllegalArgumentException("duplicate_container_target");
                open.addProperty("target",MaidGuiSessionManager.string(target,"target","minecraft:chest"));
                if("full".equals(MaidGuiSessionManager.string(request,"detail",""))) open.addProperty("detail","full");
                // Inspection never calls wait_gui. Do not pin a new task to NO_WAIT if it
                // will later process items; the owner's existing wait policy still wins.
                open.addProperty("coordinate_space","world");open.addProperty("wait_policy","AUTO");opens.add(open);
            }
        } catch(RuntimeException invalid) {return CompletableFuture.completedFuture(MaidGuiSessionManager.error("invalid_container_targets"));}
        JsonObject result=new JsonObject();result.addProperty("status","ok");result.addProperty("snapshot_kind","container_inspection");JsonArray summaries=new JsonArray();result.add("containers",summaries);
        result.addProperty("observation_notice","Each count snapshot is taken when its menu is opened. Closed snapshots are historical planning evidence, not slot authorization; actual actions require a current menu snapshot. No unobserved source is inferred empty.");
        return step(task,callId,opens,0,result);
    }
    private static CompletableFuture<JsonObject> step(TaskCallback task,String callId,List<JsonObject> targets,int index,JsonObject result) {
        if(task.stopped() || !task.task.pendingAmendment.isBlank()) {
            result.addProperty("status","partial");result.addProperty("error","goal_changed_before_inspection");return CompletableFuture.completedFuture(result);
        }
        return MaidGuiSessionManager.call(task,"open_gui",callId+":open:"+index,targets.get(index)).thenCompose(opened -> {
            if(opened.has("error")) {result.addProperty("status","partial");result.add("error",opened.get("error"));result.addProperty("failed_target_index",index);return CompletableFuture.completedFuture(result);}
            var session=MaidGuiSessionManager.session(task.getMaid().getUUID());
            JsonObject summary=new JsonObject();summary.add("target_position",opened.get("target_position"));
            summary.addProperty("game_tick",task.getMaid().level().getGameTime());summary.add("dimension",opened.get("dimension"));
            if(session==null || !(session.menu() instanceof ChestMenu menu)) {
                summary.addProperty("coverage","requires_individual_inspection");result.getAsJsonArray("containers").add(summary);
                result.addProperty("status","partial");result.addProperty("error","unsupported_container_menu");
            } else {
                Map<String,Long> counts=new TreeMap<>();
                for(var slot:menu.slots) if(slot.container!=session.actor.getInventory() && !slot.getItem().isEmpty())
                    counts.merge(MaidGuiSession.itemId(slot.getItem()),(long)slot.getItem().getCount(),Long::sum);
                summary.addProperty("coverage","complete_opened_chest_menu");summary.addProperty("empty",counts.isEmpty());
                JsonArray items=new JsonArray();counts.forEach((item,count)->{JsonObject row=new JsonObject();row.addProperty("item",item);row.addProperty("count",count);items.add(row);});summary.add("items",items);
                result.getAsJsonArray("containers").add(summary);
                if(index==targets.size()-1 && !task.stopped() && task.task.pendingAmendment.isBlank()) {
                    result.add("active_gui",opened);return CompletableFuture.completedFuture(result);
                }
            }
            JsonObject close=new JsonObject();close.add("session_id",opened.get("session_id"));
            return MaidGuiSessionManager.call(task,"close_gui",callId+":close:"+index,close).thenCompose(closed -> {
                if(closed.has("error") || closed.has("dropped_count") && closed.get("dropped_count").getAsInt()!=0) {
                    result.addProperty("error","gui_cleanup_unsettled");result.addProperty("status","partial");result.add("cleanup",closed);return CompletableFuture.completedFuture(result);
                }
                if(result.has("error")) return CompletableFuture.completedFuture(result);
                return step(task,callId,targets,index+1,result);
            });
        });
    }
}
