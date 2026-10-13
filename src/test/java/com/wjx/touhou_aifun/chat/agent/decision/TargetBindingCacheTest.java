package com.wjx.touhou_aifun.chat.agent.decision;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class TargetBindingCacheTest {
    private static JsonObject json(String text){return JsonParser.parseString(text).getAsJsonObject();}
    private static ActionPlan.Step step(){return new ActionPlan.Step("Open source",List.of(new ActionPlan.Action("open_gui",json("{\"x\":\"$target.x\",\"y\":\"$target.y\",\"z\":\"$target.z\",\"target\":\"$target.registry_id\"}"),"Source")),null,null,json("{\"kind\":\"container\",\"description\":\"Owner Source\",\"scope\":\"source_subtask\"}"));}
    private static TaskTargetBindings.Capture capture(){return new TaskTargetBindings.Capture(List.of(new ObservedTargetCatalog.Target("b0","container",json("{\"position\":[1,64,0],\"registry_id\":\"minecraft:chest\",\"dimension\":\"d\",\"labels\":[\"Source\"]}"),json("{\"target_x\":1,\"target_y\":64,\"target_z\":0,\"target_registry_id\":\"minecraft:chest\"}"))),json("{\"observed_targets\":[{\"id\":\"b0\",\"state\":\"chest\",\"labels\":[\"Source\"],\"container_inventory\":[5]}]}"));}
    @Test void oneSemanticBindingServesLaterStepsWhileSlotsAndAmountsStayLive(){
        var cache=new TargetBindingCache();var capture=capture();var step=step();assertNull(cache.reuse(step,capture));cache.remember(step,capture,new DecisionQuestionCompiler.Selection("open_gui",json("{\"x\":1,\"y\":64,\"z\":0,\"target\":\"minecraft:chest\"}")),new JsonObject());
        var reused=cache.reuse(step,capture);assertNotNull(reused);assertNull(reused.targetBinding());assertEquals(1,reused.alternatives().get(0).arguments().get("x").getAsInt());
        capture.fingerprint().getAsJsonArray("observed_targets").get(0).getAsJsonObject().remove("container_inventory");assertNotNull(cache.reuse(step,capture),"Own transfers must not invalidate the selected target");cache.clear();assertNull(cache.reuse(step,capture));
    }
    @Test void changedLabelOrIdentityRequiresANewDecision(){
        var cache=new TargetBindingCache();var capture=capture();cache.remember(step(),capture,new DecisionQuestionCompiler.Selection("open_gui",json("{\"x\":1,\"y\":64,\"z\":0,\"target\":\"minecraft:chest\"}")),new JsonObject());capture.fingerprint().getAsJsonArray("observed_targets").get(0).getAsJsonObject().remove("labels");assertNull(cache.reuse(step(),capture));
    }
    @Test void independentOrUnscopedQueriesCannotReuseAnotherSubtasksTarget(){
        var cache=new TargetBindingCache();var capture=capture();var step=step();cache.remember(step,capture,new DecisionQuestionCompiler.Selection("open_gui",json("{\"x\":1,\"y\":64,\"z\":0,\"target\":\"minecraft:chest\"}")),new JsonObject());step.targetBinding().addProperty("scope","different_subtask");assertNull(cache.reuse(step,capture));step.targetBinding().remove("scope");assertNull(cache.reuse(step,capture));
    }
}
