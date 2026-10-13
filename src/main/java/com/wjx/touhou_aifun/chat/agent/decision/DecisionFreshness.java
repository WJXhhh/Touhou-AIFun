package com.wjx.touhou_aifun.chat.agent.decision;

import com.google.gson.*;
import java.util.*;

/** A private dependency projection; dispatch still validates the complete current action. */
public final class DecisionFreshness {
    private DecisionFreshness() { }
    public static JsonObject project(JsonObject live,String purpose,JsonObject state,JsonObject answers) {
        var out=live.deepCopy();
        if(purpose.equals("template")) {
            String kind=answers.getAsJsonObject("template").get("choice").getAsString();
            boolean physical=kind.contains("receive") || kind.contains("deliver") || kind.equals("contract");
            for(String key:List.of("x","y","z","targets","gui","bindings","position"))out.remove(key);
            if(!kind.contains("deliver") || !state.has("batched_item_branches") || !state.getAsJsonArray("batched_item_branches").contains(new JsonPrimitive("deliver")))out.remove("inventory");
            if(!kind.equals("llm"))out.remove("skill_catalog");
            if(!physical)out.remove("binding_proof");else out.add("binding_proof",identities(live.getAsJsonObject("binding_proof"),null,false));
        } else if(purpose.equals("items") || purpose.equals("explicit_items")) {
            var selected=new HashSet<String>();if(state.has("selected_targets"))state.getAsJsonArray("selected_targets").forEach(id->selected.add(id.getAsString()));
            for(String key:List.of("x","y","z","targets","gui","bindings","position"))out.remove(key);
            if(!state.get("template").getAsString().contains("deliver"))out.remove("inventory");
            out.add("binding_proof",identities(live.getAsJsonObject("binding_proof"),selected,purpose.equals("items")));
        } else if(purpose.equals("amendment")) {
            for(String key:List.of("x","y","z","targets","gui","bindings","position"))out.remove(key);
            if(!state.get("owner_amendment").getAsString().matches("(?is).*(组|stacks?\\b).*"))out.remove("inventory");
            out.add("binding_proof",identities(live.getAsJsonObject("binding_proof"),null,false));
        } else {
            if(purpose.equals("action"))out.remove("targets");
            if(out.has("action_targets"))for(var row:out.getAsJsonArray("action_targets")){row.getAsJsonObject().remove("items");row.getAsJsonObject().remove("sign");}
            if(live.has("binding_proof"))out.add("binding_proof",identities(live.getAsJsonObject("binding_proof"),null,false));
        }
        return out;
    }
    public static JsonObject identities(JsonObject proof,Set<String> selected,boolean inventory) {
        var out=new JsonObject();var rows=new JsonArray();out.add("observed_targets",rows);
        if(proof!=null && proof.has("observed_targets"))for(var value:proof.getAsJsonArray("observed_targets")) {
            var row=value.getAsJsonObject();if(selected!=null && !selected.contains(row.get("id").getAsString()))continue;
            var copy=row.deepCopy();copy.remove("block_entity");if(!inventory)copy.remove("container_inventory");rows.add(copy);
        }
        return out;
    }
}
