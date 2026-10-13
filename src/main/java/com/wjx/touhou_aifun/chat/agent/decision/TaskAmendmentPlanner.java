package com.wjx.touhou_aifun.chat.agent.decision;
import com.google.gson.*;
import com.wjx.touhou_aifun.chat.agent.*;
import java.util.*;

/** Owner-authorized, literal finite edits to a complete prior contract. Settled source facts are retained. */
public final class TaskAmendmentPlanner {
    public record Start(JsonObject base,String text,List<ObservedTargetCatalog.Target> targets,Map<String,Integer> stackSizes,JsonObject questions) { }
    private TaskAmendmentPlanner() { }
    public static Start start(JsonObject base,String text,List<ObservedTargetCatalog.Target> targets,Map<String,JsonObject> schemas,Map<String,Integer> sizes) {
        if(base==null || TaskTemplatePlanner.fromCompletion(base)==null || text==null || text.isBlank())return null;
        var containers=targets.stream().filter(t->t.kind().equals("container")).toList();if(containers.size()>8)return null;
        var questions=new JsonObject();questions.add("coverage",DecisionProtocol.noul("Can the ENTIRE latest owner amendment be expressed solely by these offered count, target and existing state edits to the complete prior contract? Keep ALL unaffected original requirements. No for new items, extra jobs/states, added/removed targets, collective totals needing unresolved allocation, reservations, undoing committed operations, unclear quantities or intermediate-only states. No approximation."));
        var leaves=AgentCompletionRules.leaves(base);
        for(int i=0;i<leaves.size();i++) {
            var c=leaves.get(i);String kind=AgentTaskState.completionKind(c);
            if(kind.equals("transfer")) {
                var quantities=new LinkedHashMap<String,String>();quantities.put("keep","Keep the original count for this condition.");
                var literals=TaskTemplatePlanner.literalQuantities(text,sizes.getOrDefault(c.get("item").getAsString(),0));if(literals.size()>8)return null;for(int n:literals)quantities.put("n_"+n,"Owner explicitly changes THIS condition to "+n+" total items, including already settled items; not an extra amount.");
                questions.add("count_"+i,DecisionProtocol.choice("Modify only the explicitly addressed count in prior condition "+i+": "+c+". Otherwise keep it. Never reinterpret a multi-source collective total as a per-condition count.",quantities));
                var choices=new LinkedHashMap<String,String>();choices.put("keep","Keep this original source/destination exactly.");for(var target:containers)choices.put(target.id(),"Explicitly replace this source/destination with observed "+target.data()+". Preserve already completed work at the old source and move only the remaining requested amount at the new target.");
                questions.add("target_"+i,DecisionProtocol.choice("Modify only an explicitly replaced target in prior condition "+i+"; all other source/destination exclusions remain.",choices));
            } else if(kind.equals("maid_state")) {
                String state=c.get("state").getAsString();var choices=new LinkedHashMap<String,String>();choices.put("keep","Preserve this original final state.");
                if(Set.of("follow","sit").contains(state)){choices.put("true","Explicitly enable "+state);choices.put("false","Explicitly disable "+state);}else {
                    String tool=state.equals("schedule")?"switch_schedule":"switch_work_task",field=state.equals("schedule")?"schedule":"task_id";var schema=schemas.get(tool);if(schema==null)return null;
                    var property=schema.getAsJsonObject("properties").getAsJsonObject(field);if(property==null || !property.has("enum"))return null;property.getAsJsonArray("enum").forEach(v->choices.put(v.getAsString(),"Explicitly set "+state+" to "+v.getAsString()));
                }
                questions.add("state_"+i,DecisionProtocol.choice("Edit only this explicitly addressed original final-state condition "+i+": "+c,choices));
            }else return null;
        }
        return new Start(base.deepCopy(),text,List.copyOf(containers),Map.copyOf(sizes),questions);
    }
    public static TaskTemplatePlanner.Plan select(Start start,JsonObject answers,Map<String,Long> settled,double minimum) {
        if(!Boolean.TRUE.equals(TaskRecoveryPolicy.booleanAnswer(answers,"coverage",minimum)))return null;
        var conditions=new JsonArray();var used=new HashSet<String>();var leaves=AgentCompletionRules.leaves(start.base());boolean changed=false;
        for(int i=0;i<leaves.size();i++) {
            var c=leaves.get(i).deepCopy();String kind=AgentTaskState.completionKind(c);
            if(kind.equals("transfer")) {
                if(!TaskRecoveryPolicy.confident(answers,"count_"+i,minimum) || !TaskRecoveryPolicy.confident(answers,"target_"+i,minimum))return null;
                String quantity=answers.getAsJsonObject("count_"+i).get("choice").getAsString(),targetId=answers.getAsJsonObject("target_"+i).get("choice").getAsString();
                long count=c.get("count").getAsLong();if(!quantity.equals("keep")){if(!quantity.matches("n_[0-9]+"))return null;count=Long.parseLong(quantity.substring(2));if(!TaskTemplatePlanner.literalQuantities(start.text(),start.stackSizes().getOrDefault(c.get("item").getAsString(),0)).contains((int)count))return null;}
                long done=Math.max(0,settled.getOrDefault(AgentCompletionRules.transferKey(c),0L)*(c.get("to_maid").getAsBoolean()?1:-1));if(count<done)return null;
                if(!targetId.equals("keep")) {
                    var target=start.targets().stream().filter(t->t.id().equals(targetId)).findFirst();if(target.isEmpty() || !target.get().data().get("dimension").equals(c.get("dimension")))return null;
                    if(!target.get().data().get("position").equals(c.get("position"))) {
                        if(done>0){var committed=c.deepCopy();committed.addProperty("count",done);if(!used.add(AgentCompletionRules.transferKey(committed)))return null;conditions.add(committed);}
                        c.add("position",target.get().data().get("position").deepCopy());count-=done;if(count==0)continue;
                    }
                }
                c.addProperty("count",count);if(!used.add(AgentCompletionRules.transferKey(c)))return null;
            } else {
                if(!TaskRecoveryPolicy.confident(answers,"state_"+i,minimum))return null;String choice=answers.getAsJsonObject("state_"+i).get("choice").getAsString();
                if(!choice.equals("keep")){if(!start.questions().getAsJsonObject("state_"+i).getAsJsonObject("criteria").has(choice))return null;c.addProperty("value",c.get("state").getAsString().equals("schedule")?choice.toLowerCase(Locale.ROOT):choice);}
            }
            changed|=!c.equals(leaves.get(i));
            conditions.add(c);
        }
        // A new owner amendment cannot be acknowledged merely by completing the untouched old goal.
        if(!changed || conditions.isEmpty() || conditions.size()>16)return null;
        return TaskTemplatePlanner.fromCompletion(TaskTemplatePlanner.all(conditions));
    }
}
