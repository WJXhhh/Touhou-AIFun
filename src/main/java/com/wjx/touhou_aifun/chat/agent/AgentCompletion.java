package com.wjx.touhou_aifun.chat.agent;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.*;
import java.util.Map;

/** Completion predicates use live server facts; a model's success sentence is not evidence. */
public final class AgentCompletion {
    private AgentCompletion() { }
    /** Small live evidence projection. Unknown menu coverage is explicit, never inferred empty. */
    public static JsonObject snapshot(EntityMaid maid,AgentTaskState task,boolean freshWorld) {
        JsonObject result=new JsonObject();result.addProperty("game_tick",maid.level().getGameTime());
        result.addProperty("conditions_verified",verify(maid,task,freshWorld));
        var session=com.wjx.touhou_aifun.maid.gui.MaidGuiSessionManager.session(maid.getUUID());
        result.addProperty("gui_open",session!=null);
        result.addProperty("hands_empty",maid.getMainHandItem().isEmpty() && maid.getOffhandItem().isEmpty());
        result.add("unmet_conditions",diagnostics(maid,task,freshWorld).getAsJsonArray("unmet_conditions"));
        if(session!=null) result.addProperty("cursor_empty",session.menu().getCarried().isEmpty());
        JsonArray sources=new JsonArray();result.add("source_checks",sources);
        java.util.Set<String> items=new java.util.TreeSet<>(),seen=new java.util.HashSet<>();
        if(task.completion!=null) collectSources(maid,task.completion,sources,items,seen);
        JsonObject counts=new JsonObject();var inventory=maid.getAvailableBackpackInv();
        for(String item:items) {
            long count=0;for(int i=0;i<inventory.getSlots();i++) {
                var stack=inventory.getStackInSlot(i);
                if(item.equals(String.valueOf(net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem())))) count+=stack.getCount();
            }
            counts.addProperty(item,count);
        }
        result.add("maid_item_counts",counts);return result;
    }
    private static long inventoryCount(EntityMaid maid,String item) {
        long count=0;var inventory=maid.getAvailableBackpackInv();
        for(int i=0;i<inventory.getSlots();i++) {
            var stack=inventory.getStackInSlot(i);
            if(item.equals(String.valueOf(net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem())))) count+=stack.getCount();
        }
        return count;
    }
    public static JsonArray supplyConflicts(EntityMaid maid,AgentTaskState task,JsonObject condition) {
        var validated=AgentTaskState.validateCompletion(condition);Map<String,Long> counts=new java.util.HashMap<>();
        for(var leaf:AgentCompletionRules.leaves(validated)) if(leaf.has("item")) {
            String item=leaf.get("item").getAsString();long available=inventoryCount(maid,item);
            for(var stack:java.util.List.of(maid.getMainHandItem(),maid.getOffhandItem()))
                if(item.equals(String.valueOf(net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem())))) available+=stack.getCount();
            counts.put(item,available);
        }
        return AgentCompletionRules.supplyConflicts(validated,maid.level().dimension().location().toString(),counts,task.transfers);
    }
    /** Exact failed predicates, with no mutation of the contract or execution counters. */
    public static JsonObject diagnostics(EntityMaid maid,AgentTaskState task,boolean freshWorld) {
        JsonObject report=new JsonObject();JsonArray unmet=new JsonArray();report.add("unmet_conditions",unmet);
        if(!freshWorld || task.completion==null || !task.pendingAmendment.isBlank()) {
            report.addProperty("reason",!freshWorld?"fresh_world_observation_required":task.completion==null?"completion_contract_missing":"pending_owner_amendment");
        }
        String dimension=maid.level().dimension().location().toString();
        for(var condition:AgentCompletionRules.leaves(task.completion)) {
            JsonObject row=condition.deepCopy();String kind=AgentTaskState.completionKind(condition);boolean satisfied=false;
            if(!condition.get("dimension").getAsString().equals(dimension)) row.addProperty("reason","dimension_mismatch");
            else if(kind.equals("inventory") || kind.equals("transfer")) {
                long actual=kind.equals("inventory")?inventoryCount(maid,condition.get("item").getAsString())
                        :task.transfers.getOrDefault(AgentCompletionRules.transferKey(condition),0L)*(condition.get("to_maid").getAsBoolean()?1:-1);
                long expected=condition.get("count").getAsLong();row.addProperty("actual_count",actual);row.addProperty("remaining_count",Math.max(0,expected-actual));
                row.addProperty("reason",kind.equals("inventory")?"final_inventory_below_required_count":"recorded_transfer_below_required_count");
                satisfied=actual>=expected;
            } else if(kind.equals("position")) {
                var pos=condition.getAsJsonArray("position");double radius=condition.has("radius")?condition.get("radius").getAsDouble():2;
                double distance=Math.sqrt(maid.distanceToSqr(pos.get(0).getAsInt()+.5,pos.get(1).getAsInt(),pos.get(2).getAsInt()+.5));
                row.addProperty("actual_distance",distance);row.addProperty("required_radius",radius);row.addProperty("reason","arrival_outside_required_radius");satisfied=distance<=radius;
            }
            if(!satisfied) unmet.add(row);
        }
        report.add("supply_conflicts",supplyConflicts(maid,task,task.completion));
        report.addProperty("guidance","Inventory is final backpack stock, not a precondition. Continue only unmet requirements. Existing requirements cannot be removed by the executor; report a conflicting contract for an explicit owner amendment. Already recorded transfers are facts and must not be replayed.");
        return report;
    }
    public static String describeUnmet(EntityMaid maid,AgentTaskState task,boolean freshWorld) {
        if(!freshWorld) return "需要重新观察现场。";
        if(task.completion==null) return "需要登记覆盖全部要求的完成条件。";
        if(!task.pendingAmendment.isBlank()) return "主人追加的要求尚待重新规划。";
        StringBuilder text=new StringBuilder();int count=0;
        for(var value:diagnostics(maid,task,true).getAsJsonArray("unmet_conditions")) {
            if(count++==3) {text.append("另有其他条件尚未满足。");break;}
            var row=value.getAsJsonObject();String kind=AgentTaskState.completionKind(row);
            if(row.get("reason").getAsString().equals("dimension_mismatch")) {text.append("目标维度尚未满足。");continue;}
            if(kind.equals("position")) {text.append("尚未到达要求的位置 ").append(row.get("position")).append("。");continue;}
            String item=row.get("item").getAsString();var registered=net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(new net.minecraft.resources.ResourceLocation(item));
            String name=registered==null?item:new net.minecraft.world.item.ItemStack(registered).getHoverName().getString();
            text.append(name).append(kind.equals("inventory")?"：要求最终背包保留 ":"：要求转移 ").append(row.get("count").getAsLong())
                    .append("，目前 ").append(row.get("actual_count").getAsLong()).append("。");
        }
        return text.toString();
    }
    private static void collectSources(EntityMaid maid,JsonObject condition,JsonArray sources,
                                       java.util.Set<String> items,java.util.Set<String> seen) {
        if(AgentTaskState.completionKind(condition).equals("all")) {
            condition.getAsJsonArray("conditions").forEach(c->collectSources(maid,c.getAsJsonObject(),sources,items,seen));return;
        }
        if(condition.has("item")) items.add(condition.get("item").getAsString());
        if(!AgentTaskState.completionKind(condition).equals("transfer")) return;
        String key=condition.get("dimension").getAsString()+":"+condition.get("position");if(!seen.add(key)) return;
        JsonObject row=new JsonObject();row.add("position",condition.get("position").deepCopy());row.add("dimension",condition.get("dimension").deepCopy());sources.add(row);
        row.addProperty("coverage","unknown");
        if(!maid.level().dimension().location().toString().equals(condition.get("dimension").getAsString())) return;
        var coordinates=condition.getAsJsonArray("position");var pos=new net.minecraft.core.BlockPos(coordinates.get(0).getAsInt(),coordinates.get(1).getAsInt(),coordinates.get(2).getAsInt());
        // No chunk loads and no authorization of slot actions from this read-only projection.
        if(!maid.level().hasChunkAt(pos)) {row.addProperty("coverage","unloaded");return;}
        var block=maid.level().getBlockState(pos);
        if(block.hasProperty(net.minecraft.world.level.block.ChestBlock.TYPE)
                && block.getValue(net.minecraft.world.level.block.ChestBlock.TYPE)!=net.minecraft.world.level.block.state.properties.ChestType.SINGLE) {
            row.addProperty("coverage","double_container_requires_observation");return;
        }
        if(!(maid.level().getBlockEntity(pos) instanceof net.minecraft.world.Container container)) return;
        row.addProperty("coverage","complete_single_block_container");row.addProperty("empty",container.isEmpty());
    }
    public static boolean verify(EntityMaid maid, AgentTaskState task, boolean freshWorld) {
        if (!freshWorld || task.completion == null || !task.pendingAmendment.isBlank()) return false;
        JsonObject condition = task.completion;
        return verifyCondition(maid,task,condition);
    }
    private static boolean verifyCondition(EntityMaid maid,AgentTaskState task,JsonObject condition) {
        if(AgentTaskState.completionKind(condition).equals("all")) {
            boolean satisfied=true, hasTransfer=false; long transferCount=task.verifiedCount, inventoryCount=0;
            for(var value:condition.getAsJsonArray("conditions")) {
                var child=value.getAsJsonObject(); satisfied &= verifyCondition(maid,task,child);
                hasTransfer |= AgentTaskState.completionKind(child).equals("transfer");
                if(AgentTaskState.completionKind(child).equals("inventory")) inventoryCount+=task.verifiedCount;
            }
            task.verifiedCount=hasTransfer?transferCount:inventoryCount;
            return satisfied;
        }
        if (!condition.get("dimension").getAsString().equals(maid.level().dimension().location().toString())) return false;
        String kind = AgentTaskState.completionKind(condition);
        if (kind.equals("transfer")) {
            String key=condition.get("dimension").getAsString()+":"+condition.get("position")+":"+condition.get("item").getAsString();
            long count=task.transfers.getOrDefault(key,0L)*(condition.get("to_maid").getAsBoolean()?1:-1);
            JsonObject evidence=condition.deepCopy(); evidence.addProperty("verified_net_count",count); evidence.addProperty("game_tick",maid.level().getGameTime());
            task.checkpoint(evidence.toString()); return count>=condition.get("count").getAsLong();
        }
        JsonObject evidence = new JsonObject(); evidence.addProperty("kind", kind); evidence.addProperty("game_tick",maid.level().getGameTime());
        boolean satisfied;
        if (kind.equals("position")) {
            JsonArray pos = condition.getAsJsonArray("position");
            double distance = Math.sqrt(maid.distanceToSqr(pos.get(0).getAsInt()+.5, pos.get(1).getAsInt(), pos.get(2).getAsInt()+.5));
            evidence.addProperty("distance",distance); evidence.add("position",pos);
            satisfied = distance <= (condition.has("radius") ? condition.get("radius").getAsDouble() : 2);
        } else if (kind.equals("inventory")) {
            long count = 0;
            var handler = maid.getAvailableBackpackInv();
            for (int i=0;i<handler.getSlots();i++) {
                var stack=handler.getStackInSlot(i);
                if (condition.get("item").getAsString().equals(String.valueOf(net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem())))) count += stack.getCount();
            }
            task.verifiedCount=count; evidence.addProperty("item",condition.get("item").getAsString()); evidence.addProperty("count",count);
            satisfied = count >= condition.get("count").getAsLong();
        } else return false;
        evidence.addProperty("completion_verified",satisfied); task.checkpoint(evidence.toString()); return satisfied;
    }
}
