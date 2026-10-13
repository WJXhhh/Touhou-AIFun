package com.wjx.touhou_aifun.chat.agent.decision;

import com.google.gson.*;
import java.util.*;

/** Independent finite questions can share startup HTTP; only the selected transfer branch is consumed. */
public final class StartupItemBatch {
    public record Branch(List<String> items,Map<String,Integer> sizes,JsonObject questions) { }
    public record Batch(Map<String,Branch> branches) {
        public JsonObject append(JsonObject base) {
            var out=base.deepCopy();branches.forEach((kind,b)->b.questions().entrySet().forEach(e->{
                var q=e.getValue().getAsJsonObject().deepCopy();
                q.addProperty("instructions","Answer for the "+kind+" component ONLY if the selected template contains it. Other templates ignore this answer. "+q.get("instructions").getAsString());
                out.add(kind+"_"+e.getKey(),q);
            }));return out;
        }
        public JsonObject answers(String kind,JsonObject answers) {
            var out=new JsonObject();var branch=branches.get(kind);if(branch==null)return out;
            branch.questions().keySet().forEach(k->{if(answers.has(kind+"_"+k))out.add(k,answers.get(kind+"_"+k).deepCopy());});return out;
        }
        public void describe(JsonObject state) {
            var kinds=new JsonArray();branches.forEach((kind,b)->{kinds.add(kind);state.add(kind+"_item_candidates",new Gson().toJsonTree(b.items()));state.add(kind+"_item_stack_sizes",new Gson().toJsonTree(b.sizes()));});
            state.add("batched_item_branches",kinds);state.addProperty("source_stock_observed",false);
        }
    }
    private StartupItemBatch() { }
    public static Batch prepare(String goal,Map<String,Integer> named,Map<String,Integer> backpack,JsonObject templateQuestion) {
        var branches=new LinkedHashMap<String,Branch>();
        var criteria=templateQuestion.getAsJsonObject("criteria");
        for(String kind:List.of("receive","deliver")) {
            if(criteria.keySet().stream().noneMatch(k->k.contains(kind)))continue;
            if(kind.equals("receive") && goal.matches("(?is).*(组|stacks?\\b).*"))continue;
            var sizes=kind.equals("receive")?named:backpack;var items=List.copyOf(new TreeMap<>(sizes).keySet());
            if(items.isEmpty() || items.size()>16)continue;
            try {branches.put(kind,new Branch(items,Map.copyOf(sizes),TaskTemplatePlanner.itemQuestions(goal,items,sizes)));}catch(RuntimeException limit){ }
        }
        return new Batch(Collections.unmodifiableMap(branches));
    }
    public static boolean fits(String model,JsonObject state,JsonObject questions,DecisionProvider.Capabilities caps) {
        try {DecisionProtocol.request(model,state,questions,caps);return true;}catch(IllegalArgumentException limit){return false;}
    }
}
