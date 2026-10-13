package com.wjx.touhou_aifun.chat.agent;

import com.google.gson.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CompletionSequenceTest {
    private static JsonObject transfer(boolean incoming) {var c=JsonParser.parseString("{\"kind\":\"transfer\",\"dimension\":\"minecraft:overworld\",\"position\":[1,2,3],\"item\":\"minecraft:iron_ingot\",\"count\":2}").getAsJsonObject();c.addProperty("to_maid",incoming);return c;}
    private static JsonObject sequence() {var c=new JsonObject();c.addProperty("kind","sequence");var p=new JsonArray();p.add(transfer(true));p.add(transfer(false));c.add("phases",p);return c;}
    @Test void phaseTransferScopeSurvivesJsonAndArchiveReconciliation() {
        var task=new AgentTaskState("take then return");task.defineCompletion(sequence());String key=AgentCompletionRules.transferKey(transfer(true));
        task.transfers.put(key,2L);task.completionStage=1;task.completionTransferBase=new LinkedHashMap<>(task.transfers);task.transfers.put(key,0L);task.refreshTransferProgress();
        assertEquals(2,task.verifiedCount);assertTrue(task.completionSatisfied());
        var archive=new AgentTaskArchive("maid",task.id);archive.commitFacts(task);
        var old=new AgentTaskState();old.id=task.id;old.completion=sequence();
        new Gson().fromJson(new Gson().toJson(archive),AgentTaskArchive.class).reconcile(old);
        assertEquals(1,old.completionStage);assertEquals(-2L,old.activeTransfers().get(key));assertTrue(old.completionSatisfied());
        assertTrue(new Gson().fromJson(new Gson().toJson(old),AgentTaskState.class).completionSatisfied());
    }
    @Test void singleLegTransfersStillUseNetAndNewSequenceCannotReuseOldReceipt() {
        var task=new AgentTaskState("take");task.defineCompletion(transfer(true));String key=AgentCompletionRules.transferKey(transfer(true));task.transfers.put(key,0L);assertFalse(task.completionSatisfied());
        task.transfers.put(key,2L);task.amend("now take and return",sequence());task.applyAmendment();assertEquals(0L,task.activeTransfers().get(key));assertFalse(task.completionSatisfied());
    }
    @Test void staleArchiveCannotRestoreThePriorGoalsPhaseAndContainerBaseline() {
        var task=new AgentTaskState("old");task.defineCompletion(sequence());task.completionStage=1;var archive=new AgentTaskArchive("maid",task.id);archive.commitFacts(task);
        var newer=new AgentTaskState();newer.goalVersion=2;newer.completion=sequence();archive.reconcile(newer);assertEquals(0,newer.completionStage);
    }
    @Test void validationRejectsNestedOrUnscopedConsumptionAndAllowsExactEmptyInventory() {
        assertNotNull(AgentTaskState.validateCompletion(sequence()));var nested=sequence();nested.getAsJsonArray("phases").set(0,sequence());assertThrows(IllegalArgumentException.class,()->AgentTaskState.validateCompletion(nested));
        var consumption=sequence();consumption.getAsJsonArray("phases").set(0,JsonParser.parseString("{\"kind\":\"eat_food\",\"dimension\":\"minecraft:overworld\",\"food\":\"minecraft:cake\",\"count\":1}"));assertThrows(IllegalArgumentException.class,()->AgentTaskState.validateCompletion(consumption));
        var empty=JsonParser.parseString("{\"kind\":\"inventory\",\"dimension\":\"minecraft:overworld\",\"item\":\"minecraft:iron_ingot\",\"count\":0,\"match\":\"exact\"}").getAsJsonObject();assertNotNull(AgentTaskState.validateCompletion(empty));empty.remove("match");assertThrows(IllegalArgumentException.class,()->AgentTaskState.validateCompletion(empty));
    }
    @Test void corruptPrivatePhaseCheckpointIsRejected() {var archive=new AgentTaskArchive();archive.verifiedCompletionStage=8;assertThrows(IllegalArgumentException.class,archive::validateSize);archive.verifiedCompletionStage=0;archive.verifiedContainerSnapshots.put("chest","bad");assertThrows(IllegalArgumentException.class,archive::validateSize);}
}
