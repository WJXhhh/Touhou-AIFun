package com.wjx.touhou_aifun.chat.agent;

import com.google.gson.*;
import java.util.*;

/** Checks the supply explicitly described by a plan, without changing its requested outcomes. */
public final class AgentCompletionRules {
    private AgentCompletionRules() { }
    public static String transferKey(JsonObject condition) {
        return condition.get("dimension").getAsString()+":"+condition.get("position")+":"+condition.get("item").getAsString();
    }
    public static List<JsonObject> leaves(JsonObject condition) {
        if(condition==null) return List.of();
        if(!AgentTaskState.completionKind(condition).equals("all")) return List.of(condition);
        return condition.getAsJsonArray("conditions").asList().stream().map(JsonElement::getAsJsonObject).toList();
    }
    public static JsonArray supplyConflicts(JsonObject condition, String dimension,
                                            Map<String,Long> available, Map<String,Long> transfers) {
        Map<String,Long> reserve=new TreeMap<>(), incoming=new HashMap<>(), outgoing=new HashMap<>();
        Map<String,JsonObject> inTargets=new HashMap<>(),outTargets=new HashMap<>();
        for(var leaf:leaves(condition)) {
            if(!leaf.get("dimension").getAsString().equals(dimension)) continue;
            String kind=AgentTaskState.completionKind(leaf);
            if(kind.equals("inventory")) reserve.merge(leaf.get("item").getAsString(),leaf.get("count").getAsLong(),Math::max);
            else if(kind.equals("transfer")) {
                var targets=leaf.get("to_maid").getAsBoolean()?inTargets:outTargets;
                targets.merge(transferKey(leaf),leaf,(a,b)->a.get("count").getAsLong()>=b.get("count").getAsLong()?a:b);
            }
        }
        JsonArray conflicts=new JsonArray();
        for(String key:inTargets.keySet()) if(outTargets.containsKey(key)) {
            JsonObject row=inTargets.get(key).deepCopy();row.addProperty("reason","opposite_net_transfer_conditions_same_container");conflicts.add(row);
        }
        Set<String> outgoingItems=new HashSet<>();
        for(var targets:List.of(inTargets,outTargets)) for(var entry:targets.entrySet()) {
            JsonObject leaf=entry.getValue();boolean receive=leaf.get("to_maid").getAsBoolean();
            String item=leaf.get("item").getAsString();if(!receive) outgoingItems.add(item);
            long done=transfers.getOrDefault(entry.getKey(),0L)*(receive?1:-1);
            long remaining=Math.max(0,leaf.get("count").getAsLong()-done);
            (receive?incoming:outgoing).merge(item,remaining,Long::sum);
        }
        for(var entry:reserve.entrySet()) {
            String item=entry.getKey();long stock=available.getOrDefault(item,0L),in=incoming.getOrDefault(item,0L),out=outgoing.getOrDefault(item,0L);
            if(!outgoingItems.contains(item) || stock+in>=entry.getValue()+out) continue;
            JsonObject row=new JsonObject();row.addProperty("reason","final_inventory_conflicts_with_outbound_transfer");
            row.addProperty("item",item);row.addProperty("available_count",stock);row.addProperty("remaining_incoming",in);
            row.addProperty("remaining_outgoing",out);row.addProperty("required_final_inventory",entry.getValue());conflicts.add(row);
        }
        return conflicts;
    }
}
