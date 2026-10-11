package com.wjx.touhou_aifun.chat.agent.decision;

import com.google.gson.*;
import java.util.*;

/** Stable identities derived from one scan's recorded origin, never from the maid's later position. */
public final class ObservedTargetCatalog {
    public record Target(String id,String kind,JsonObject data,JsonObject binding) { }
    private ObservedTargetCatalog() { }
    public static List<Target> fromScan(JsonObject scan) {
        if(scan==null || !scan.has("maid_position_at_scan_start") || !scan.has("dimension")) return List.of();
        try {
            var origin=scan.getAsJsonArray("maid_position_at_scan_start");if(origin.size()!=3) return List.of();
            var rows=new LinkedHashMap<String,JsonObject>();
            for(String key:List.of("important_blocks","important_targets")) if(scan.has(key) && scan.get(key).isJsonArray()) for(var value:scan.getAsJsonArray(key)) {
                var row=value.getAsJsonObject();var position=position(row,origin);if(position==null || !row.has("registry_id")) continue;
                var data=new JsonObject();data.add("position",position);data.add("registry_id",row.get("registry_id"));data.add("dimension",scan.get("dimension"));
                rows.putIfAbsent(position.toString(),data);
            }
            if(scan.has("sign_texts")) for(var value:scan.getAsJsonArray("sign_texts")) {
                var sign=value.getAsJsonObject();if(!sign.has("attached_block") || sign.has("text_truncated") && sign.get("text_truncated").getAsBoolean()) continue;
                var attached=sign.getAsJsonObject("attached_block");if(!attached.has("position") || !attached.has("registry_id")) continue;
                var p=attached.getAsJsonArray("position");if(p.size()!=3) continue;
                var data=rows.computeIfAbsent(p.toString(),k->{var d=new JsonObject();d.add("position",p.deepCopy());d.add("registry_id",attached.get("registry_id"));d.add("dimension",scan.get("dimension"));return d;});
                if(!data.has("labels")) data.add("labels",new JsonArray());var label=new JsonObject();
                for(String key:List.of("front_lines","back_lines")) if(sign.has(key)) label.add(key,sign.get(key).deepCopy());
                data.getAsJsonArray("labels").add(label);
            }
            var out=new ArrayList<Target>();
            rows.forEach((key,data)->{
                var p=data.getAsJsonArray("position");var binding=new JsonObject();
                for(int n=0;n<3;n++) binding.add("target_"+List.of("x","y","z").get(n),p.get(n));
                binding.add("target_registry_id",data.get("registry_id"));
                out.add(new Target("b"+out.size(),"block",data,binding));
            });
            for(String key:List.of("entities","entity_targets")) if(scan.has(key) && scan.get(key).isJsonArray()) for(var value:scan.getAsJsonArray(key)) {
                var data=value.getAsJsonObject().deepCopy();if(!data.has("entity_id")) continue;
                var binding=new JsonObject();binding.add("target_entity_id",data.get("entity_id"));
                out.add(new Target("e"+data.get("entity_id").getAsInt(),"entity",data,binding));
            }
            return List.copyOf(out);
        } catch(RuntimeException malformed) {return List.of();}
    }
    private static JsonArray position(JsonObject row,JsonArray origin) {
        JsonArray relative=row.has("relative_position")?row.getAsJsonArray("relative_position"):null;
        var p=new JsonArray();for(int n=0;n<3;n++) {
            String axis=List.of("dx","dy","dz").get(n);
            if(relative==null && !row.has(axis)) return null;
            double offset=(relative!=null?relative.get(n):row.get(axis)).getAsDouble();
            if(!Double.isFinite(offset) || offset!=Math.rint(offset)) return null;
            p.add(Math.addExact(origin.get(n).getAsInt(),(int)offset));
        }return p;
    }
    /** Expands a semantic reference into finite, grounded actions; the ordinary compiler then selects once. */
    public static ActionPlan.Step expand(ActionPlan.Step step,List<Target> targets) {
        if(step.targetBinding()==null) return step;
        var query=step.targetBinding();String kind=query.get("kind").getAsString();
        var eligible=targets.stream().filter(t->kind.equals("block")?t.kind().equals("block"):kind.equals("entity")?t.kind().equals("entity"):t.kind().equals("container")).toList();
        if(eligible.isEmpty() || eligible.size()>8 || eligible.size()*step.alternatives().size()>32) throw new IllegalArgumentException("target_candidate_limit");
        var actions=new ArrayList<ActionPlan.Action>();
        for(var target:eligible) for(var action:step.alternatives()) {
            var args=bind(action.arguments(),target.binding()).getAsJsonObject();
            actions.add(new ActionPlan.Action(action.tool(),args,action.when()+"; target="+target.id()+"; "+query.get("description").getAsString()+"; observed="+target.data()));
        }
        return new ActionPlan.Step(step.description(),List.copyOf(actions),step.precondition(),step.until(),step.targetBinding(),step.skipIf());
    }
    private static JsonElement bind(JsonElement input,JsonObject values) {
        if(input.isJsonPrimitive() && input.getAsJsonPrimitive().isString() && input.getAsString().startsWith("$target.")) {
            var value=values.get("target_"+input.getAsString().substring(8));if(value==null) throw new IllegalArgumentException("target_binding_unavailable");return value.deepCopy();
        }
        if(input.isJsonObject()) {var out=new JsonObject();input.getAsJsonObject().entrySet().forEach(e->out.add(e.getKey(),bind(e.getValue(),values)));return out;}
        if(input.isJsonArray()) {var out=new JsonArray();input.getAsJsonArray().forEach(v->out.add(bind(v,values)));return out;}
        return input.deepCopy();
    }
}
