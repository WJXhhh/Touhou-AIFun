package com.wjx.touhou_aifun.chat.agent.decision;

import com.google.gson.*;
import com.wjx.touhou_aifun.chat.agent.*;
import java.util.*;

/** Bounded execution segments from an already committed phase, never from guessed owner intent. */
public final class TaskStageChoices {
    public record Segment(String id, JsonArray position, JsonArray steps, JsonArray remaining) { }
    private TaskStageChoices() { }

    public static List<Segment> transfers(JsonObject phase, Map<String,Long> receipts, String dimension) {
        var all=AgentCompletionRules.leaves(phase);
        var leaves=all.stream().filter(c->AgentTaskState.completionKind(c).equals("transfer")).toList();
        if(leaves.isEmpty() || all.stream().anyMatch(c->!Set.of("transfer","backpack_empty").contains(AgentTaskState.completionKind(c))
                || !dimension.equals(c.get("dimension").getAsString())))return List.of();
        boolean receive=leaves.get(0).get("to_maid").getAsBoolean();
        if(leaves.stream().anyMatch(c->c.get("to_maid").getAsBoolean()!=receive))return List.of();
        // Final emptiness is a read-only check after outbound work, never an extra action or a
        // reason to discard unrelated stock. The original full phase remains authoritative.
        if(receive && all.size()!=leaves.size())return List.of();
        var remaining=new LinkedHashMap<String,JsonArray>();
        for(var c:leaves) {
            long rest=c.get("count").getAsLong()-receipts.getOrDefault(AgentCompletionRules.transferKey(c),0L)*(receive?1:-1);
            if(rest<=0)continue;
            var row=c.deepCopy();row.addProperty("remaining_count",rest);
            remaining.computeIfAbsent(c.get("position").toString(),ignored->new JsonArray()).add(row);
        }
        var out=new ArrayList<Segment>();
        for(var entry:remaining.entrySet()) {
            var conditions=new JsonArray();var indices=new ArrayList<Integer>();
            for(int i=0;i<all.size();i++)if(AgentTaskState.completionKind(all.get(i)).equals("transfer") && all.get(i).get("position").toString().equals(entry.getKey())) {
                conditions.add(all.get(i).deepCopy());indices.add(i);
            }
            var contract=new JsonObject();contract.addProperty("kind","all");contract.add("conditions",conditions);
            var plan=TaskTemplatePlanner.fromCompletion(contract);if(plan==null)return List.of();
            var steps=remap(plan.steps(),indices).getAsJsonArray();
            out.add(new Segment("container_"+out.size(),conditions.get(0).getAsJsonObject().getAsJsonArray("position").deepCopy(),steps,entry.getValue()));
        }
        return List.copyOf(out);
    }
    private static JsonElement remap(JsonElement value,List<Integer> indices) {
        if(value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            String text=value.getAsString();
            for(String field:List.of("remaining_","from_slot_","to_slot_","transfer_count_"))
                if(text.startsWith("$live."+field))return new JsonPrimitive("$live."+field+indices.get(Integer.parseInt(text.substring(6+field.length()))));
            return value.deepCopy();
        }
        if(value.isJsonArray()){var out=new JsonArray();value.getAsJsonArray().forEach(v->out.add(remap(v,indices)));return out;}
        if(value.isJsonObject()){var out=new JsonObject();value.getAsJsonObject().entrySet().forEach(e->out.add(e.getKey(),remap(e.getValue(),indices)));return out;}
        return value.deepCopy();
    }
}
