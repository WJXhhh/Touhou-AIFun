package com.wjx.touhou_aifun.chat.agent.decision;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class DecisionFreshnessTest {
    @Test void stageRetainsReceiptAndPhaseDependenciesWhileIgnoringUnobservedStockAndIncidentalMovement(){
        var old=live();old.addProperty("completion_stage",0);old.addProperty("mutation_version",1);old.add("settled_transfers",json("{\"a\":3}"));
        var next=old.deepCopy();next.addProperty("x",20);next.getAsJsonObject("binding_proof").getAsJsonArray("observed_targets").get(0).getAsJsonObject().remove("container_inventory");
        assertEquals(DecisionFreshness.project(old,"stage",new JsonObject(),new JsonObject()),DecisionFreshness.project(next,"stage",new JsonObject(),new JsonObject()));
        next.addProperty("completion_stage",1);assertNotEquals(DecisionFreshness.project(old,"stage",new JsonObject(),new JsonObject()),DecisionFreshness.project(next,"stage",new JsonObject(),new JsonObject()));
        next=old.deepCopy();next.getAsJsonObject("settled_transfers").addProperty("a",4);assertNotEquals(DecisionFreshness.project(old,"stage",new JsonObject(),new JsonObject()),DecisionFreshness.project(next,"stage",new JsonObject(),new JsonObject()));
    }
    @org.junit.jupiter.api.Test void mergedDeliveryDependsOnBackpackButUnusedBranchesDoNot(){
        var state=json("{\"batched_item_branches\":[\"receive\",\"deliver\"]}");var old=live();var next=old.deepCopy();next.remove("inventory");var answers=json("{\"template\":{\"choice\":\"deliver_then_follow\"}}");
        assertNotEquals(DecisionFreshness.project(old,"template",state,answers),DecisionFreshness.project(next,"template",state,answers));answers.getAsJsonObject("template").addProperty("choice","follow");assertEquals(DecisionFreshness.project(old,"template",state,answers),DecisionFreshness.project(next,"template",state,answers));
    }
    @org.junit.jupiter.api.Test void pendingSkillDependsOnCatalogueOnlyWhenFullPlanningWasSelected(){
        var old=live();old.add("skill_catalog",json("{\"x\":1}"));var next=old.deepCopy();next.remove("skill_catalog");var answers=json("{\"template\":{\"choice\":\"llm\"}}");assertNotEquals(DecisionFreshness.project(old,"template",new JsonObject(),answers),DecisionFreshness.project(next,"template",new JsonObject(),answers));answers.getAsJsonObject("template").addProperty("choice","receive");assertEquals(DecisionFreshness.project(old,"template",new JsonObject(),answers),DecisionFreshness.project(next,"template",new JsonObject(),answers));
    }
    private static JsonObject json(String value){return JsonParser.parseString(value).getAsJsonObject();}
    private static JsonObject live(){return json("{\"owner\":\"owner\",\"dimension\":\"d\",\"generation\":1,\"goal_version\":1,\"x\":1,\"inventory\":[1],\"binding_proof\":{\"observed_targets\":[{\"id\":\"b0\",\"state\":\"chest\",\"labels\":[\"source\"],\"container_inventory\":[5]},{\"id\":\"b1\",\"state\":\"chest\",\"container_inventory\":[10]}]}}");}
    @Test void templateIgnoresUnobservedStocksButRetainsLabelsOwnerAndVersion(){
        var old=live();var next=old.deepCopy();next.addProperty("x",9);next.getAsJsonObject("binding_proof").getAsJsonArray("observed_targets").get(1).getAsJsonObject().add("container_inventory",json("{\"a\":[20]}").get("a"));var answers=json("{\"template\":{\"choice\":\"receive\"}}");
        assertEquals(DecisionFreshness.project(old,"template",new JsonObject(),answers),DecisionFreshness.project(next,"template",new JsonObject(),answers));next.addProperty("owner","different");assertNotEquals(DecisionFreshness.project(old,"template",new JsonObject(),answers),DecisionFreshness.project(next,"template",new JsonObject(),answers));
        next=old.deepCopy();next.getAsJsonObject("binding_proof").getAsJsonArray("observed_targets").get(0).getAsJsonObject().remove("labels");assertNotEquals(DecisionFreshness.project(old,"template",new JsonObject(),answers),DecisionFreshness.project(next,"template",new JsonObject(),answers));
    }
    @Test void quantityDependsOnSelectedStocksAndDepositInventoryNotOtherTargets(){
        var state=json("{\"template\":\"receive\",\"selected_targets\":[\"b0\"]}");var old=live();var next=old.deepCopy();next.getAsJsonObject("binding_proof").getAsJsonArray("observed_targets").remove(1);next.remove("inventory");assertEquals(DecisionFreshness.project(old,"items",state,new JsonObject()),DecisionFreshness.project(next,"items",state,new JsonObject()));
        next.getAsJsonObject("binding_proof").getAsJsonArray("observed_targets").get(0).getAsJsonObject().remove("container_inventory");assertNotEquals(DecisionFreshness.project(old,"items",state,new JsonObject()),DecisionFreshness.project(next,"items",state,new JsonObject()));
        state.addProperty("template","deliver");assertNotEquals(DecisionFreshness.project(old,"items",state,new JsonObject()),DecisionFreshness.project(next,"items",state,new JsonObject()));
    }
}
