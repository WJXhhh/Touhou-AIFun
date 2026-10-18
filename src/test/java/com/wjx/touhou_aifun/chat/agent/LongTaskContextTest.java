package com.wjx.touhou_aifun.chat.agent;

import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.*;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.response.*;
import com.google.gson.*;
import com.wjx.touhou_aifun.chat.context.ContextTokenEstimator;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LongTaskContextTest {
    @Test void invalidNestedPlansNameMissingTransferFieldsWithoutDroppingOrGuessingRequirements() {
        var leaf=transfer(5,true);leaf.remove("position");var contract=sequence(leaf,transfer(5,false));var before=contract.deepCopy();
        var task=new AgentTaskState("取五个后归还，不准减少要求");
        var failure=assertThrows(IllegalArgumentException.class,()->task.defineCompletion(contract));
        assertTrue(failure.getMessage().contains("missing [position]"));assertTrue(failure.getMessage().contains("Keep ALL owner requirements"));
        assertEquals(before,contract);assertNull(task.completion);assertTrue(task.transfers.isEmpty());
        var group=new JsonObject();group.add("conditions",new JsonArray());
        assertTrue(assertThrows(IllegalArgumentException.class,()->AgentTaskState.validateCompletion(group)).getMessage().contains("explicit kind"));
    }
    @Test void largeTaskBudgetKeepsHistoryUntilPressureThenPreservesRequirementsLatestBatchAndProgress() {
        int budget=475000;
        var state=new AgentTaskState("只取五个铁锭，禁止碰金锭");state.defineCompletion(transfer(5,true));state.recordResult(receipt(2,true));
        var owner=new LLMMessage(Role.USER,state.goal,0);
        var amendment=new LLMMessage(Role.USER,"补充：只使用北边箱子，搬完之后回到主人身边。",0);
        var messages=new ArrayList<LLMMessage>(List.of(owner,amendment));
        for(int i=0;i<60;i++)addObservation(messages,i);
        assertTrue(ContextTokenEstimator.estimate(messages)>32768);
        assertTrue(ContextTokenEstimator.estimate(messages)<budget*.8);
        var store=new TaskResultStore();
        assertEquals(messages,AgentContext.compact(messages,budget,store),"Roomy task contexts retain closed batches verbatim");
        for(int i=60;i<70;i++)addObservation(messages,i);
        assertTrue(ContextTokenEstimator.estimate(messages)>budget*.8);
        var latest=messages.subList(messages.size()-2,messages.size());
        var compacted=AgentContext.compact(messages,budget,store);
        assertTrue(ContextTokenEstimator.estimate(compacted)<=budget*.6);
        assertTrue(compacted.contains(owner));assertTrue(compacted.contains(amendment));assertTrue(compacted.containsAll(latest));
        assertTrue(compacted.stream().anyMatch(m->m.role()==Role.SYSTEM && m.message().startsWith("## Archived tool evidence")));
        var restored=new Gson().fromJson(new Gson().toJson(state),AgentTaskState.class);
        assertEquals(2,restored.verifiedCount);
        assertThrows(IllegalArgumentException.class,()->restored.checkTransferAllowance("minecraft:overworld",transfer(5,true).getAsJsonArray("position"),"minecraft:iron_ingot",true,5,false));
        assertDoesNotThrow(()->restored.checkTransferAllowance("minecraft:overworld",transfer(5,true).getAsJsonArray("position"),"minecraft:iron_ingot",true,3,false));
    }
    private static void addObservation(List<LLMMessage> messages,int index) {
        var call=new ToolCall("observation_"+index,new FunctionToolCall("inspect_gui","{}"));
        messages.add(new LLMMessage(Role.ASSISTANT,"",0,List.of(call),null));
        messages.add(new LLMMessage(Role.TOOL,"观".repeat(3400),0,null,call.getId()));
    }
    private static JsonObject transfer(int count, boolean receive) {
        return JsonParser.parseString("{\"kind\":\"transfer\",\"dimension\":\"minecraft:overworld\",\"position\":[1,2,3],\"item\":\"minecraft:iron_ingot\",\"to_maid\":"+receive+",\"count\":"+count+"}").getAsJsonObject();
    }
    private static String receipt(int count, boolean receive) {
        return "{\"external_transfer\":true,\"moved_item\":\"minecraft:iron_ingot\",\"moved_count\":"+count+",\"to_maid\":"+receive+",\"dimension\":\"minecraft:overworld\",\"target_position\":[1,2,3]}";
    }
    private static String foodReceipt() {
        return "{\"kind\":\"eat_food\",\"success\":true,\"block_id\":\"minecraft:cake\",\"dimension\":\"minecraft:overworld\",\"position\":{\"x\":1,\"y\":2,\"z\":3},\"servings_consumed\":1,\"finished\":false}";
    }

    private static JsonObject sequence(JsonObject... phases) {
        var out=new JsonObject();out.addProperty("kind","sequence");var values=new JsonArray();
        for(var phase:phases)values.add(phase);out.add("phases",values);return out;
    }
    @Test void sequenceOwnerAmendmentKeepsMaterialsAlreadyCollectedBeforeCompressionAndReload() {
        var task=new AgentTaskState("先取五个铁锭，再放回，禁止重复取料");task.defineCompletion(sequence(transfer(5,true),transfer(5,false)));
        task.recordResult(receipt(2,true));task.amend("补充：放完后确认空手，已经取的两个不要再取");task.applyAmendment();
        task=new Gson().fromJson(new Gson().toJson(task),AgentTaskState.class);
        task.defineCompletion(sequence(transfer(5,true),transfer(5,false)));assertEquals(2,task.verifiedCount);
        var restored=task;
        assertDoesNotThrow(()->restored.checkTransferAllowance("minecraft:overworld",transfer(5,true).getAsJsonArray("position"),"minecraft:iron_ingot",true,3,false));
        assertThrows(IllegalArgumentException.class,()->restored.checkTransferAllowance("minecraft:overworld",transfer(5,true).getAsJsonArray("position"),"minecraft:iron_ingot",true,5,false));
    }
    @Test void laterSequenceAmendmentsKeepVerifiedPrefixAndPartialCurrentPhaseWithoutReplayingEitherLeg() {
        var first=transfer(5,true);var task=new AgentTaskState("先取五个，再放回五个");task.defineCompletion(sequence(first,transfer(5,false)));
        task.recordResult(receipt(5,true));task.completionStage=1;task.completionTransferBase=new LinkedHashMap<>(task.transfers);
        task.recordResult(receipt(2,false));task.amend("补充最终检查，前面的动作不要重做");task.applyAmendment();
        task.amend("再补充：不要碰金锭");task.applyAmendment();
        var invalid=sequence(transfer(6,true),transfer(5,false));
        assertThrows(IllegalArgumentException.class,()->task.defineCompletion(invalid));assertNull(task.completion);
        task.defineCompletion(sequence(first,transfer(5,false)));assertEquals(1,task.completionStage);assertEquals(2,task.verifiedCount);
        assertDoesNotThrow(()->task.checkTransferAllowance("minecraft:overworld",first.getAsJsonArray("position"),"minecraft:iron_ingot",false,3,false));
        task.recordResult(receipt(3,false));assertTrue(task.completionSatisfied());
        assertThrows(IllegalArgumentException.class,()->task.checkTransferAllowance("minecraft:overworld",first.getAsJsonArray("position"),"minecraft:iron_ingot",false,1,false));
    }
    @Test void directOwnerCompletionUpdateKeepsTheSameSequenceReceiptOriginAndRejectsPrefixChangesAtomically() {
        var first=transfer(5,true);var task=new AgentTaskState("取出后放回");var contract=sequence(first,transfer(5,false));task.defineCompletion(contract);
        task.recordResult(receipt(5,true));task.completionStage=1;task.completionTransferBase=new LinkedHashMap<>(task.transfers);task.recordResult(receipt(2,false));
        assertThrows(IllegalArgumentException.class,()->task.amend("change",sequence(transfer(6,true),transfer(5,false))));assertTrue(task.pendingAmendment.isBlank());
        task.amend("保留已做的动作",contract);task.applyAmendment();assertEquals(1,task.completionStage);assertEquals(2,task.verifiedCount);
    }

    @Test void longOwnerRequirementsAreRejectedWholeInsteadOfSilentlyDroppingTheTail() {
        var queue=new AgentTaskQueue();
        String exact="😀".repeat(4095)+"禁";
        assertEquals(exact,queue.enqueue(exact).goal);
        var rejected=assertThrows(IllegalArgumentException.class,()->queue.enqueue("取铁。".repeat(1400)+"最后千万不要碰金锭"));
        assertTrue(rejected.getMessage().startsWith("task_goal_limit"));assertEquals(1,queue.tasks.size());
    }

    @Test void eightyCompressionCyclesKeepEveryOwnerAmendmentAndDurableReceiptsAfterEvictionAndReload() {
        var state=new AgentTaskState("只取五个铁锭，禁止碰金锭");state.defineCompletion(transfer(5,true));state.recordResult(receipt(2,true));
        state.amend("最后把手腾空，不要重复取已经拿到的两个");state.applyAmendment();state.defineCompletion(transfer(5,true));
        var archive=new AgentTaskArchive("maid",state.id);archive.commitFacts(state);
        var store=new TaskResultStore(12000);var messages=new ArrayList<LLMMessage>();
        var original=new LLMMessage(Role.USER,state.goal,0);var amendment=new LLMMessage(Role.USER,state.constraints.get(0),0);
        messages.add(original);messages.add(amendment);String first=store.put(receipt(2,true));
        for(int i=0;i<80;i++) {
            var call=new ToolCall("inspect_"+i,new FunctionToolCall("inspect_gui","{}"));
            messages.add(new LLMMessage(Role.ASSISTANT,"",0,List.of(call),null));
            messages.add(new LLMMessage(Role.TOOL,"observed inventory ".repeat(200),0,null,call.getId()));
            messages=new ArrayList<>(AgentContext.compact(messages,2500,store));
            assertTrue(messages.contains(original));assertTrue(messages.contains(amendment));
            store.put("new observation ".repeat(500));
        }
        assertTrue(store.read(first,0).startsWith("result_expired"));
        var restored=new Gson().fromJson(new Gson().toJson(state),AgentTaskState.class);
        new Gson().fromJson(new Gson().toJson(archive),AgentTaskArchive.class).reconcile(restored);
        assertEquals(2,restored.verifiedCount);assertEquals(state.constraints,restored.constraints);
        assertDoesNotThrow(()->restored.checkTransferAllowance("minecraft:overworld",transfer(5,true).getAsJsonArray("position"),"minecraft:iron_ingot",true,3,false));
        assertThrows(IllegalArgumentException.class,()->restored.checkTransferAllowance("minecraft:overworld",transfer(5,true).getAsJsonArray("position"),"minecraft:iron_ingot",true,5,false));
    }

    @Test void fulfilledTransferCannotBeReplayedEvenWhenARecipeProducesAnIndivisibleBatch() {
        var state=new AgentTaskState("取两个铁锭");state.defineCompletion(transfer(2,true));state.recordResult(receipt(2,true));
        for(boolean nativeBatch:List.of(false,true))
            assertThrows(IllegalArgumentException.class,()->state.checkTransferAllowance("minecraft:overworld",transfer(2,true).getAsJsonArray("position"),"minecraft:iron_ingot",true,1,nativeBatch));
    }

    @Test void aNewOrderedReturnPhaseUsesItsOwnBaselineRatherThanBlockingAnAuthorizedOppositeLeg() {
        var phases=new JsonArray();phases.add(transfer(2,true));phases.add(transfer(2,false));
        var contract=new JsonObject();contract.addProperty("kind","sequence");contract.add("phases",phases);
        var state=new AgentTaskState("取两个再原样放回");state.defineCompletion(contract);state.recordResult(receipt(2,true));
        state.completionStage=1;state.completionTransferBase=new LinkedHashMap<>(state.transfers);
        assertDoesNotThrow(()->state.checkTransferAllowance("minecraft:overworld",transfer(2,false).getAsJsonArray("position"),"minecraft:iron_ingot",false,2,false));
        state.recordResult(receipt(2,false));
        assertThrows(IllegalArgumentException.class,()->state.checkTransferAllowance("minecraft:overworld",transfer(2,false).getAsJsonArray("position"),"minecraft:iron_ingot",false,1,false));
    }

    @Test void archivedConsumptionRetainsServingsAndBlocksARepeatedBiteAfterReload() {
        var summary=JsonParser.parseString(ToolResultProjection.domainSummary(foodReceipt())).getAsJsonObject();
        assertTrue(summary.get("success").getAsBoolean());assertEquals(1,summary.get("servings_consumed").getAsInt());assertFalse(summary.get("finished").getAsBoolean());
        assertEquals("minecraft:cake",summary.get("block_id").getAsString());
        var state=new AgentTaskState("只吃一口蛋糕");
        state.defineCompletion(JsonParser.parseString("{\"kind\":\"eat_food\",\"dimension\":\"minecraft:overworld\",\"food\":\"minecraft:cake\",\"count\":1}").getAsJsonObject());
        state.recordResult(foodReceipt());var restored=new Gson().fromJson(new Gson().toJson(state),AgentTaskState.class);
        assertThrows(IllegalArgumentException.class,()->restored.checkConsumptionAllowance("minecraft:overworld","minecraft:cake",transfer(1,true).getAsJsonArray("position")));
        restored.completion.addProperty("finished",true);
        assertDoesNotThrow(()->restored.checkConsumptionAllowance("minecraft:overworld","minecraft:cake",transfer(1,true).getAsJsonArray("position")));
    }

    @Test void exhaustingConsumptionReceiptCapacityStopsNewTargetsInsteadOfForgettingOldActions() {
        var state=new AgentTaskState("分批处理许多零食柜");
        for(int i=0;i<256;i++) {
            var fact=JsonParser.parseString(foodReceipt()).getAsJsonObject();fact.getAsJsonObject("position").addProperty("x",i);state.recordResult(fact.toString());
        }
        var next=new JsonArray();next.add(256);next.add(2);next.add(3);
        assertTrue(assertThrows(IllegalArgumentException.class,()->state.checkConsumptionAllowance("minecraft:overworld","minecraft:cake",next))
                .getMessage().startsWith("task_action_fact_limit"));
        assertEquals(256,state.actionFacts.size());assertEquals(0,state.actionFacts.get(0).getAsJsonObject("position").get("x").getAsInt());
        next.set(0,new JsonPrimitive(0));assertDoesNotThrow(()->state.checkConsumptionAllowance("minecraft:overworld","minecraft:cake",next));
    }
}
