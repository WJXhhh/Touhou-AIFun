package com.wjx.touhou_aifun.chat.agent.decision;

import com.google.gson.*;
import java.util.*;

/** Per-generation semantic bindings; every reuse requires current identity/label validation. */
public final class TargetBindingCache {
    private record Bound(ObservedTargetCatalog.Target target,JsonObject identity) { }
    private final Map<String,Bound> bound=new HashMap<>();
    public void clear(){bound.clear();}
    private static String key(ActionPlan.Step step){return step.targetBinding().toString();}
    private static JsonObject identity(TaskTargetBindings.Capture capture,String id) {
        for(var row:DecisionFreshness.identities(capture.fingerprint(),Set.of(id),false).getAsJsonArray("observed_targets"))return row.getAsJsonObject();
        return null;
    }
    public ActionPlan.Step reuse(ActionPlan.Step step,TaskTargetBindings.Capture capture) {
        if(step.targetBinding()==null || !step.targetBinding().has("scope"))return null;var previous=bound.get(key(step));if(previous==null)return null;
        var target=capture.targets().stream().filter(t->t.id().equals(previous.target().id()) && t.data().equals(previous.target().data()) && t.binding().equals(previous.target().binding())).findFirst();
        if(target.isEmpty() || !Objects.equals(previous.identity(),identity(capture,target.get().id()))){bound.remove(key(step));return null;}
        var expanded=ObservedTargetCatalog.expand(step,List.of(target.get()));
        return new ActionPlan.Step(expanded.description(),expanded.alternatives(),expanded.precondition(),expanded.until(),null,expanded.skipIf());
    }
    public void remember(ActionPlan.Step step,TaskTargetBindings.Capture capture,DecisionQuestionCompiler.Selection selected,JsonObject liveBindings) {
        if(step.targetBinding()==null || !step.targetBinding().has("scope") || !step.alternatives().stream().anyMatch(a->a.arguments().toString().contains("$target.")))return;
        var matches=new ArrayList<ObservedTargetCatalog.Target>();
        for(var target:capture.targets())try {
            var expanded=ObservedTargetCatalog.expand(step,List.of(target));
            if(expanded.alternatives().stream().anyMatch(a->a.tool().equals(selected.tool()) && subset(DecisionQuestionCompiler.resolve(a.arguments(),liveBindings),selected.arguments())))matches.add(target);
        }catch(RuntimeException unavailable){ }
        if(matches.size()==1){var target=matches.get(0);var identity=identity(capture,target.id());if(identity!=null)bound.put(key(step),new Bound(target,identity.deepCopy()));}
    }
    private static boolean subset(JsonElement expected,JsonElement actual) {
        if(expected.isJsonObject() && actual.isJsonObject())return expected.getAsJsonObject().entrySet().stream().allMatch(e->actual.getAsJsonObject().has(e.getKey()) && subset(e.getValue(),actual.getAsJsonObject().get(e.getKey())));
        return expected.equals(actual);
    }
}
