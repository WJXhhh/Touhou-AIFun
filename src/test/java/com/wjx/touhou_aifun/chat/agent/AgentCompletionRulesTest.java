package com.wjx.touhou_aifun.chat.agent;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentCompletionRulesTest {
    static JsonObject contract(boolean incoming) {
        String out="{\"kind\":\"transfer\",\"dimension\":\"minecraft:overworld\",\"position\":[1,2,3],\"item\":\"minecraft:iron_shovel\",\"count\":1,\"to_maid\":false}";
        String inventory="{\"kind\":\"inventory\",\"dimension\":\"minecraft:overworld\",\"item\":\"minecraft:iron_shovel\",\"count\":1}";
        return JsonParser.parseString("{\"kind\":\"all\",\"conditions\":["+inventory+","+out+(incoming?","+out.replace("[1,2,3]","[4,5,6]").replace("false","true"):"")+"]}").getAsJsonObject();
    }
    @Test void rejectsObservedSingleShovelAsBothFinalInventoryAndOutbound() {
        var errors=AgentCompletionRules.supplyConflicts(contract(false),"minecraft:overworld",Map.of("minecraft:iron_shovel",1L),Map.of());
        assertEquals(1,errors.size());var row=errors.get(0).getAsJsonObject();
        assertEquals("minecraft:iron_shovel",row.get("item").getAsString());assertEquals(1,row.get("required_final_inventory").getAsInt());assertEquals(1,row.get("remaining_outgoing").getAsInt());
    }
    @Test void preservesLegitimateReserveAndPlannedIncomingSupply() {
        assertTrue(AgentCompletionRules.supplyConflicts(contract(false),"minecraft:overworld",Map.of("minecraft:iron_shovel",2L),Map.of()).isEmpty());
        assertTrue(AgentCompletionRules.supplyConflicts(contract(true),"minecraft:overworld",Map.of("minecraft:iron_shovel",1L),Map.of()).isEmpty());
    }
    @Test void completedTransfersAreNotDemandedAgainAfterAmendment() {
        var c=contract(false);String key=AgentCompletionRules.transferKey(c.getAsJsonArray("conditions").get(1).getAsJsonObject());
        var errors=AgentCompletionRules.supplyConflicts(c,"minecraft:overworld",Map.of(),Map.of(key,-1L));
        assertEquals(0,errors.get(0).getAsJsonObject().get("remaining_outgoing").getAsInt());
        c.getAsJsonArray("conditions").remove(0);
        assertTrue(AgentCompletionRules.supplyConflicts(c,"minecraft:overworld",Map.of(),Map.of(key,-1L)).isEmpty());
    }
    @Test void repeatedSameDestinationPredicatesUseMaximumRatherThanDoubleCounting() {
        var c=contract(false);c.getAsJsonArray("conditions").add(c.getAsJsonArray("conditions").get(1).deepCopy());
        assertTrue(AgentCompletionRules.supplyConflicts(c,"minecraft:overworld",Map.of("minecraft:iron_shovel",2L),Map.of()).isEmpty());
    }
    @Test void netTransfersCannotBeBothInboundAndOutboundAtTheSameContainer() {
        var c=contract(true);c.getAsJsonArray("conditions").get(2).getAsJsonObject().add("position",c.getAsJsonArray("conditions").get(1).getAsJsonObject().get("position").deepCopy());
        var errors=AgentCompletionRules.supplyConflicts(c,"minecraft:overworld",Map.of("minecraft:iron_shovel",1L),Map.of());
        assertEquals("opposite_net_transfer_conditions_same_container",errors.get(0).getAsJsonObject().get("reason").getAsString());
    }
    @Test void guardRejectionsAreFailuresButRecordedPlansAndWaitingAreNot() {
        for(String result:List.of("completion_contract_required_before_mutation: no items moved","fresh_world_observation_required: scan","goal_updated_before_dispatch: no action started","duplicate_tool_call_id: not replayed","tool_returned_no_result","{\"status\":\"rejected\"}")) assertTrue(AgentToolOutcome.failed(result),result);
        for(String result:List.of("plan_recorded: current_step=2","GUI schemas loaded.","{\"status\":\"waiting\"}","{\"status\":\"ok\",\"moved_count\":1}")) assertFalse(AgentToolOutcome.failed(result),result);
    }
    @Test void foregroundTaskAssertionsAndStatusQuestionsHaveConservativeBoundaries() {
        assertTrue(ForegroundTaskStatus.isQuestion("嗯，东西放进去了吧？"));assertTrue(ForegroundTaskStatus.isQuestion("How is the task progress?"));
        assertTrue(ForegroundTaskStatus.claimsCompletion("全部照着告示牌送进对应箱子了。"));
        assertFalse(ForegroundTaskStatus.claimsCompletion("我会把全部物品放好。"));assertFalse(ForegroundTaskStatus.claimsCompletion("任务还没有完成。"));
        assertFalse(ForegroundTaskStatus.isQuestion("今天的天气怎么样？"));
        assertFalse(ForegroundTaskStatus.isQuestion("这部电影结束了吗？"));assertFalse(ForegroundTaskStatus.isQuestion("Is my homework completed?"));
        var running=new ForegroundTaskStatus("task",AgentTaskState.Status.running,"sort",63,"");
        assertFalse(running.reply(false).contains("核验完成"));assertTrue(running.reply(false).contains("尚未通过"));
    }
}
