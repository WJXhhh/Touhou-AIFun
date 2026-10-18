package com.wjx.touhou_aifun.chat.agent.decision;

import com.google.gson.*;
import com.wjx.touhou_aifun.chat.agent.AgentCompletionRules;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TaskStageChoicesTest {
    private JsonObject phase(int count) {
        var conditions=new JsonArray();for(int i=0;i<count;i++)conditions.add(JsonParser.parseString("{\"kind\":\"transfer\",\"dimension\":\"minecraft:overworld\",\"position\":["+i+",64,0],\"item\":\"minecraft:iron_ingot\",\"count\":5,\"to_maid\":true}"));
        var out=new JsonObject();out.addProperty("kind","all");out.add("conditions",conditions);return out;
    }
    @Test void largeCommittedPhaseKeepsOriginalBindingIndicesAndSettledReceipts() {
        var phase=phase(12);var before=phase.deepCopy();var leaves=AgentCompletionRules.leaves(phase);
        var receipts=Map.of(AgentCompletionRules.transferKey(leaves.get(0)),5L,AgentCompletionRules.transferKey(leaves.get(8)),3L);
        var segments=TaskStageChoices.transfers(phase,receipts,"minecraft:overworld");assertEquals(11,segments.size());
        var eight=segments.stream().filter(s->s.position().get(0).getAsInt()==8).findFirst().orElseThrow();
        assertTrue(eight.steps().toString().contains("$live.transfer_count_8"));assertEquals(2,eight.remaining().get(0).getAsJsonObject().get("remaining_count").getAsInt());
        assertEquals(before,phase);assertEquals(2,receipts.size());
    }
    @Test void mixedDirectionsAndUnsupportedStagesStayWithPlanner() {
        var phase=phase(2);phase.getAsJsonArray("conditions").get(1).getAsJsonObject().addProperty("to_maid",false);
        assertTrue(TaskStageChoices.transfers(phase,Map.of(),"minecraft:overworld").isEmpty());
        phase=phase(2);phase.getAsJsonArray("conditions").get(1).getAsJsonObject().addProperty("kind","inventory");
        assertTrue(TaskStageChoices.transfers(phase,Map.of(),"minecraft:overworld").isEmpty());
        assertTrue(TaskStageChoices.transfers(phase(2),Map.of(),"minecraft:the_nether").isEmpty());
    }
    @Test void severalItemsInOneContainerKeepOriginalQuantitiesAndSlots() {
        var phase=phase(3);var leaves=AgentCompletionRules.leaves(phase);leaves.get(2).add("position",leaves.get(0).get("position").deepCopy());leaves.get(2).addProperty("item","minecraft:gold_ingot");
        var segments=TaskStageChoices.transfers(phase,Map.of(),"minecraft:overworld");assertEquals(2,segments.size());assertEquals(4,segments.get(0).steps().size());
        assertTrue(segments.get(0).steps().toString().contains("$live.from_slot_2"));assertEquals(2,segments.get(0).remaining().size());
    }
    @Test void negativeOutboundReceiptsPreventReplayWithoutConfusingIncomingCounts() {
        var phase=phase(2);AgentCompletionRules.leaves(phase).forEach(c->c.addProperty("to_maid",false));
        var receipts=Map.of(AgentCompletionRules.transferKey(AgentCompletionRules.leaves(phase).get(0)),-5L);
        var segments=TaskStageChoices.transfers(phase,receipts,"minecraft:overworld");assertEquals(1,segments.size());assertEquals(1,segments.get(0).position().get(0).getAsInt());
    }
    @Test void outboundFinalEmptyCheckIsPreservedAndDoesNotReindexTransferBindings() {
        var phase=phase(2);AgentCompletionRules.leaves(phase).forEach(c->c.addProperty("to_maid",false));
        var conditions=phase.getAsJsonArray("conditions");var first=conditions.remove(0);
        conditions.add(JsonParser.parseString("{\"kind\":\"backpack_empty\",\"dimension\":\"minecraft:overworld\"}"));conditions.add(first);
        var before=phase.deepCopy();var segments=TaskStageChoices.transfers(phase,Map.of(),"minecraft:overworld");assertEquals(2,segments.size());
        assertTrue(segments.get(1).steps().toString().contains("$live.from_slot_2"));assertEquals(before,phase);
        AgentCompletionRules.leaves(phase).stream().filter(c->c.has("to_maid")).forEach(c->c.addProperty("to_maid",true));
        assertTrue(TaskStageChoices.transfers(phase,Map.of(),"minecraft:overworld").isEmpty());
    }
}
