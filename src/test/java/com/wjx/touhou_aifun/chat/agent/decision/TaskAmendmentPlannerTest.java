package com.wjx.touhou_aifun.chat.agent.decision;
import com.google.gson.*;
import com.wjx.touhou_aifun.chat.agent.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class TaskAmendmentPlannerTest {
    private static JsonObject json(String text){return JsonParser.parseString(text).getAsJsonObject();}
    private static JsonObject base(){return json("{\"kind\":\"all\",\"conditions\":[{\"kind\":\"transfer\",\"dimension\":\"d\",\"position\":[1,64,0],\"item\":\"minecraft:gold_ingot\",\"count\":5,\"to_maid\":true},{\"kind\":\"maid_state\",\"dimension\":\"d\",\"state\":\"sit\",\"value\":\"true\"}]}");}
    private static JsonObject answers(String count,String target){return json("{\"coverage\":{\"noul\":0.99},\"count_0\":{\"choice\":\""+count+"\",\"confidence\":0.99},\"target_0\":{\"choice\":\""+target+"\",\"confidence\":0.99},\"state_1\":{\"choice\":\"keep\",\"confidence\":0.99}}");}
    @Test void countAmendmentRetainsFinalStateAndPreviouslySettledQuantities(){
        var start=TaskAmendmentPlanner.start(base(),"金锭总量改为6个，其他要求不变",List.of(),Map.of(),Map.of());var key=AgentCompletionRules.transferKey(AgentCompletionRules.leaves(base()).get(0));
        var plan=TaskAmendmentPlanner.select(start,answers("n_6","keep"),Map.of(key,2L),.9);assertNotNull(plan);var leaves=AgentCompletionRules.leaves(plan.completion());assertEquals(6,leaves.get(0).get("count").getAsInt());assertEquals(AgentCompletionRules.leaves(base()).get(1),leaves.get(1));
        assertNull(TaskAmendmentPlanner.select(start,answers("n_7","keep"),Map.of(key,2L),.9));
    }
    @Test void retargetingKeepsCommittedOldSourceAndOnlySchedulesRemainderAtNewSource(){
        var target=new ObservedTargetCatalog.Target("b1","container",json("{\"dimension\":\"d\",\"position\":[3,64,0]}"),new JsonObject());var start=TaskAmendmentPlanner.start(base(),"改从新Source箱子取，总量仍为5个，其他要求不变",List.of(target),Map.of(),Map.of());var key=AgentCompletionRules.transferKey(AgentCompletionRules.leaves(base()).get(0));
        var plan=TaskAmendmentPlanner.select(start,answers("keep","b1"),Map.of(key,2L),.9);assertNotNull(plan);var leaves=AgentCompletionRules.leaves(plan.completion());assertEquals(3,leaves.size());assertEquals(2,leaves.get(0).get("count").getAsInt());assertEquals(3,leaves.get(1).get("count").getAsInt());assertEquals(target.data().get("position"),leaves.get(1).get("position"));assertEquals(AgentCompletionRules.leaves(base()).get(1),leaves.get(2));
    }
    @Test void ambiguousUnsupportedEditsAndUndoingCommittedWorkNeverInstallANewContract(){
        var start=TaskAmendmentPlanner.start(base(),"改成1个",List.of(),Map.of(),Map.of());var key=AgentCompletionRules.transferKey(AgentCompletionRules.leaves(base()).get(0));assertNull(TaskAmendmentPlanner.select(start,answers("n_1","keep"),Map.of(key,2L),.9));
        var answer=answers("keep","keep");answer.getAsJsonObject("coverage").addProperty("noul",.5);assertNull(TaskAmendmentPlanner.select(start,answer,Map.of(),.9));
        assertNull(TaskAmendmentPlanner.start(json("{\"kind\":\"inventory\",\"dimension\":\"d\",\"item\":\"minecraft:gold_ingot\",\"count\":5}"),"改成6个",List.of(),Map.of(),Map.of()));
        assertNull(TaskAmendmentPlanner.select(start,answers("keep","keep"),Map.of(),.9),"An untouched old contract cannot acknowledge a new amendment");
    }
    @Test void amendmentBasePersistsThroughSerializationWithoutChangingReceipts(){
        var task=new AgentTaskState("取5个金锭后坐下");task.defineCompletion(base());var key=AgentCompletionRules.transferKey(AgentCompletionRules.leaves(base()).get(0));task.transfers.put(key,2L);task.amend("改成6个");task.applyAmendment();var restored=new Gson().fromJson(new Gson().toJson(task),AgentTaskState.class);
        assertNull(restored.completion);assertEquals(base(),restored.amendmentBaseCompletion);assertEquals(1,restored.amendmentBaseVersion);assertEquals(2L,restored.transfers.get(key));assertEquals(2,restored.goalVersion);
        restored.amend("再增加别的要求");restored.applyAmendment();assertNull(restored.amendmentBaseCompletion,"Unresolved earlier amendments require full replanning, never reuse an older scope");
    }
}
