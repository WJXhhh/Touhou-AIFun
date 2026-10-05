package com.wjx.touhou_aifun.chat.agent;

import com.google.gson.*;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.*;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.response.*;
import com.wjx.touhou_aifun.chat.context.ContextTokenEstimator;
import com.wjx.touhou_aifun.maid.gui.GuiSnapshotProjection;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentRuntimeTest {
    @Test void leanTaskSlotsKeepAuthorityAndCapturedGeometryWithoutChangingServerSnapshot() {
        var full=JsonParser.parseString("{\"frame_id\":0,\"revision\":7,\"slots\":[{\"slot\":2,\"item\":\"\",\"count\":0,\"name\":\"\",\"fingerprint\":\"empty\",\"x\":8,\"y\":18,\"enabled\":false,\"may_take\":false},{\"slot\":3,\"item\":\"minecraft:iron_ingot\",\"count\":2,\"name\":\"Iron\",\"fingerprint\":\"actual\",\"x\":26,\"y\":18,\"maid_storage\":\"backpack\",\"backpack_slot\":0}]}").getAsJsonObject();
        var lean=GuiSnapshotProjection.taskView(full);var empty=lean.getAsJsonArray("slots").get(0).getAsJsonObject();var occupied=lean.getAsJsonArray("slots").get(1).getAsJsonObject();
        assertEquals(2,empty.get("slot").getAsInt());assertEquals(0,empty.get("count").getAsInt());assertFalse(empty.get("enabled").getAsBoolean());assertFalse(empty.get("may_take").getAsBoolean());
        assertFalse(empty.has("x"));assertFalse(empty.has("fingerprint"));assertEquals("actual",occupied.get("fingerprint").getAsString());assertEquals("backpack",occupied.get("maid_storage").getAsString());assertEquals(7,lean.get("revision").getAsInt());
        assertTrue(full.getAsJsonArray("slots").get(0).getAsJsonObject().has("x"));assertTrue(full.getAsJsonArray("slots").get(0).getAsJsonObject().has("fingerprint"));
        full.addProperty("frame_id",12);var visual=GuiSnapshotProjection.taskView(full);assertEquals(8,visual.getAsJsonArray("slots").get(0).getAsJsonObject().get("x").getAsInt());assertFalse(visual.has("slot_geometry"));
    }
    @Test void containerInspectionRetainsCountsAndTheStillOpenSlotSnapshot() {
        String raw="{\"snapshot_kind\":\"container_inspection\",\"containers\":[{\"target_position\":[1,2,3],\"items\":[{\"item\":\"minecraft:iron_ingot\",\"count\":2}]}],\"active_gui\":{\"session_id\":\"current\",\"revision\":1,\"slots\":[{\"slot\":0,\"description\":\""+"x".repeat(9000)+"\"}]}}";
        var visible=JsonParser.parseString(ToolResultProjection.project(raw,"evidence")).getAsJsonObject();
        assertEquals(2,visible.getAsJsonArray("containers").get(0).getAsJsonObject().getAsJsonArray("items").get(0).getAsJsonObject().get("count").getAsInt());
        assertEquals("current",visible.getAsJsonObject("active_gui").get("session_id").getAsString());
        assertEquals(0,visible.getAsJsonObject("active_gui").getAsJsonArray("slots").get(0).getAsJsonObject().get("slot").getAsInt());
        var compact=JsonParser.parseString(ToolResultProjection.domainSummary(visible.toString())).getAsJsonObject();
        assertTrue(compact.has("containers"));assertFalse(compact.has("active_gui"));assertEquals("evidence",compact.get("result_ref").getAsString());
    }
    @Test void failedFinalizationRetainsCommittedFactsAndIsCountedAsFailure() {
        String raw="{\"status\":\"ok\",\"moved_item\":\"minecraft:iron_ingot\",\"moved_count\":1,\"completion_requested\":false,\"completion_error\":\"completion_evidence_unmet\",\"slots\":\""+"x".repeat(9000)+"\"}";
        var projected=ToolResultProjection.project(raw,"evidence");assertTrue(AgentToolOutcome.failed(projected));
        var value=JsonParser.parseString(projected).getAsJsonObject();assertEquals(1,value.get("moved_count").getAsInt());assertEquals("completion_evidence_unmet",value.get("completion_error").getAsString());
        assertTrue(ToolResultProjection.domainSummary(projected).contains("completion_evidence_unmet"));
    }
    @Test void stoppingPausedCurrentCancelsItAndPreservesWaitingTasks() {
        var queue=new AgentTaskQueue();var current=queue.enqueue("old sorting");queue.next();queue.suspend("reload");
        var retrieval=queue.enqueue("retrieve from four chests");
        assertSame(current,queue.resumeCandidate());queue.stop(false);
        assertEquals(AgentTaskState.Status.cancelled,current.status);
        assertEquals(AgentTaskState.Status.queued,retrieval.status);assertTrue(queue.paused);
        queue.resume();assertSame(retrieval,queue.next());
    }
    @Test void resumeHonorsCurrentTaskEvenIfCheckpointListOrderChanged() {
        var queue=new AgentTaskQueue();var current=queue.enqueue("current");queue.next();queue.suspend("reload");
        var pending=queue.enqueue("new request");Collections.reverse(queue.tasks);
        assertSame(current,queue.resumeCandidate());queue.resume();assertSame(current,queue.next());
        assertEquals(AgentTaskState.Status.queued,pending.status);
    }
    @Test void historicalTransfersNeedOwnerReviewEvenAfterAnOlderGoalVersionChange() {
        var task=new AgentTaskState("old sorting");task.goalVersion=2;task.appliedMutationVersion=5;
        assertTrue(task.requiresCompletionReview());
        task.amend("Verify these already moved items and leave the new retrieval queued");
        assertFalse(task.requiresCompletionReview());
        var fresh=new AgentTaskState("retrieve");assertFalse(fresh.requiresCompletionReview());
    }
    @Test void missingCompletionCanBeDefinedBeforeTransfersButCannotBeWeakened() {
        var task=new AgentTaskState("Put all five iron in the labelled chest");
        var contract=JsonParser.parseString("{\"kind\":\"transfer\",\"item\":\"minecraft:iron_ingot\",\"count\":5,\"dimension\":\"minecraft:overworld\",\"position\":[1,2,3],\"to_maid\":false}").getAsJsonObject();
        task.defineCompletion(contract);task.defineCompletion(contract.deepCopy());assertEquals(contract,task.completion);
        var easier=contract.deepCopy();easier.addProperty("count",1);
        assertThrows(IllegalArgumentException.class,()->task.defineCompletion(easier));assertEquals(5,task.completion.get("count").getAsInt());
    }
    @Test void completionCannotBeInventedAfterAnUnverifiedTransfer() {
        var task=new AgentTaskState("Put five iron away");task.appliedMutationVersion=1;
        var contract=JsonParser.parseString("{\"kind\":\"inventory\",\"item\":\"minecraft:iron_ingot\",\"count\":1,\"dimension\":\"minecraft:overworld\"}").getAsJsonObject();
        assertThrows(IllegalArgumentException.class,()->task.defineCompletion(contract));assertNull(task.completion);
    }
    @Test void newerArchiveSettlementsRestoreAnOlderEntityCheckpointExactlyOnce() {
        var task=new AgentTaskState("receive iron");task.completion=JsonParser.parseString("{\"item\":\"iron\",\"count\":5,\"dimension\":\"world\",\"position\":[1,2,3],\"to_maid\":true}").getAsJsonObject();
        var archive=new AgentTaskArchive("maid",task.id);task.transfers.put("world:[1,2,3]:iron",3L);archive.commitFacts(task);
        var entityCheckpoint=new Gson().fromJson(new Gson().toJson(task),AgentTaskState.class);
        task.transfers.put("world:[1,2,3]:iron",5L);archive.commitFacts(task);
        var restored=new Gson().fromJson(new Gson().toJson(archive),AgentTaskArchive.class);restored.reconcile(entityCheckpoint);
        assertEquals(5,entityCheckpoint.verifiedCount);assertTrue(entityCheckpoint.completionSatisfied());
        restored.reconcile(entityCheckpoint);assertEquals(5,entityCheckpoint.transfers.get("world:[1,2,3]:iron"));
    }
    @Test void pausedCurrentTaskStillAllowsFourQueuedTasksAndResumesFirst() {
        var queue=new AgentTaskQueue();var current=queue.enqueue("current");queue.next();queue.suspend("offline");
        for(int i=0;i<4;i++) queue.enqueue("queued"+i);
        assertThrows(IllegalArgumentException.class,()->queue.enqueue("overflow"));
        var restored=new Gson().fromJson(new Gson().toJson(queue),AgentTaskQueue.class);restored.resume();
        assertEquals(current.id,restored.next().id);
    }
    @Test void oneClosedBatchOfFullGuiSnapshotsKeepsPairingAndLatestStructureWithinBudget() {
        var calls=new ArrayList<ToolCall>();var messages=new ArrayList<LLMMessage>();
        messages.add(new LLMMessage(Role.USER,"Do not touch gold",0));
        for(int i=0;i<6;i++) calls.add(new ToolCall("g"+i,new FunctionToolCall("gui_action","{}")));
        messages.add(new LLMMessage(Role.ASSISTANT,"",0,calls,null));
        for(int i=0;i<6;i++) messages.add(new LLMMessage(Role.TOOL,"{\"snapshot_kind\":\"full\",\"result_ref\":\"r"+i+"\",\"moved_count\":2,\"slots\":[{\"slot\":1,\"label\":\""+"x".repeat(12000)+"\"}]}",0,null,"g"+i));
        var compact=AgentContext.compact(messages,16384,new TaskResultStore());
        assertTrue(ContextTokenEstimator.estimate(compact)<16384);assertEquals(6,compact.stream().filter(m->m.role()==Role.TOOL).count());
        assertEquals(calls,compact.get(1).toolCalls());
        for(int i=2;i<7;i++) assertFalse(JsonParser.parseString(compact.get(i).message()).getAsJsonObject().has("slots"));
        assertTrue(JsonParser.parseString(compact.get(7).message()).getAsJsonObject().has("slots"));
    }
    @Test void domainCheckpointKeepsQuantitiesCoordinatesAndErrorsWithoutDuplicatingSlots() {
        var raw=JsonParser.parseString("{\"status\":\"ok\",\"moved_item\":\"minecraft:iron_ingot\",\"moved_count\":3,\"target_position\":[1,2,3],\"result_ref\":\"r\",\"slots\":[{\"slot\":0}],\"action_results\":[{\"error\":\"stale_slots_reinspect\"}]}");
        var summary=JsonParser.parseString(ToolResultProjection.domainSummary(raw.toString())).getAsJsonObject();
        assertEquals(3,summary.get("moved_count").getAsInt());assertEquals(raw.getAsJsonObject().get("target_position"),summary.get("target_position"));
        assertEquals("r",summary.get("result_ref").getAsString());assertFalse(summary.has("slots"));
        assertEquals("stale_slots_reinspect",summary.getAsJsonArray("action_results").get(0).getAsJsonObject().get("error").getAsString());
    }
    @Test void actionSeedKeepsPersonaWithoutContradictoryForegroundRouting() {
        assertEquals("persona\nconstraints", AgentPrompts.actionSeed("persona" + AgentPrompts.FOREGROUND + "\nconstraints"));
        assertEquals("user says task_control", AgentPrompts.actionSeed("user says task_control"));
    }
    @Test void initialGuiStructureHasItsOwnBudgetAndIsNotHiddenBehindPagination() {
        JsonObject full=new JsonObject();full.addProperty("snapshot_kind","full");JsonArray slots=new JsonArray();
        for(int i=0;i<120;i++) {JsonObject slot=new JsonObject();slot.addProperty("slot",i);slot.addProperty("item","minecraft:iron_ingot");slot.addProperty("count",64);slot.addProperty("maid_inventory",i>=54);slot.addProperty("enabled",true);slots.add(slot);}
        full.add("slots",slots); assertTrue(full.toString().length()>8192);
        var projected=JsonParser.parseString(ToolResultProjection.project(full.toString(),"ref")).getAsJsonObject();
        assertEquals(120,projected.getAsJsonArray("slots").size());assertFalse(projected.has("details_omitted"));assertEquals("ref",projected.get("result_ref").getAsString());
    }
    @Test void compoundTransferCompletionRequiresEveryItemAndDestination() {
        var task=new AgentTaskState("receive iron and gold from two chests");
        task.completion=AgentTaskState.validateCompletion(JsonParser.parseString("{\"kind\":\"all\",\"conditions\":[{\"item\":\"minecraft:iron_ingot\",\"count\":3,\"dimension\":\"minecraft:overworld\",\"position\":[1,2,3],\"to_maid\":true},{\"item\":\"minecraft:gold_ingot\",\"count\":2,\"dimension\":\"minecraft:overworld\",\"position\":[4,5,6],\"to_maid\":true}]}").getAsJsonObject());
        task.recordResult("{\"external_transfer\":true,\"moved_item\":\"minecraft:iron_ingot\",\"moved_count\":3,\"dimension\":\"minecraft:overworld\",\"target_position\":[1,2,3],\"to_maid\":true}");
        assertFalse(task.completionSatisfied());
        task.recordResult("{\"external_transfer\":true,\"moved_item\":\"minecraft:gold_ingot\",\"moved_count\":2,\"dimension\":\"minecraft:overworld\",\"target_position\":[4,5,6],\"to_maid\":true}");
        assertTrue(task.completionSatisfied()); assertEquals(5,task.verifiedCount);
        task.amend("unchanged quantities",task.completion.deepCopy()); task.applyAmendment(); assertTrue(task.completionSatisfied());
        assertThrows(IllegalArgumentException.class,()->AgentTaskState.validateCompletion(JsonParser.parseString("{\"kind\":\"all\",\"conditions\":[{\"kind\":\"all\",\"conditions\":[]}]}").getAsJsonObject()));
    }
    @Test void additiveTaskFieldsLoadFromEarlierCheckpoints() {
        var task=new Gson().fromJson("{\"id\":\"old\",\"goal\":\"move\",\"status\":\"paused\"}",AgentTaskState.class);
        assertNotNull(task.failures); assertNotNull(task.constraints); assertNotNull(task.transfers);
        assertEquals(0,task.currentStep); assertTrue(task.pendingAmendment.isBlank());
    }
    @Test void malformedOrReplayedIdsAreRejectedBeforeDispatch() {
        var call=new ToolCall("c1",new FunctionToolCall("gui_action","{}"));
        assertFalse(ToolBatchValidation.valid(List.of(call,call),Set.of()));
        assertFalse(ToolBatchValidation.valid(List.of(call),Set.of("c1")));
        assertTrue(ToolBatchValidation.valid(List.of(call),Set.of()));
        assertFalse(ToolBatchValidation.valid(List.of(),Set.of()));
    }
    @Test void typedCompletionContractsValidateAndCannotUseTransferCountersForArrival() {
        var task=new AgentTaskState("arrive");
        task.completion=AgentTaskState.validateCompletion(JsonParser.parseString("{\"kind\":\"position\",\"dimension\":\"minecraft:overworld\",\"position\":[1,2,3]}").getAsJsonObject());
        task.verifiedCount=999; assertFalse(task.completionSatisfied());
        assertThrows(IllegalArgumentException.class,()->AgentTaskState.validateCompletion(JsonParser.parseString("{\"kind\":\"position\",\"dimension\":\"minecraft:overworld\",\"position\":[1,2,3],\"radius\":20}").getAsJsonObject()));
        assertThrows(IllegalArgumentException.class,()->AgentTaskState.validateCompletion(JsonParser.parseString("{\"kind\":\"inventory\",\"dimension\":\"minecraft:overworld\",\"item\":\"minecraft:iron_ingot\",\"count\":0}").getAsJsonObject()));
    }
    @Test void durableResultsKeepReferencesSequenceAndExpiredSummaries() {
        var store = new TaskResultStore(100);
        String old = store.put("delivered 12 iron to north chest " + "x".repeat(50));
        String latest = store.put("live slots " + "y".repeat(60));
        var state = new Gson().fromJson(new Gson().toJson(store.snapshot()), TaskResultStore.State.class);
        var restored = new TaskResultStore(state);
        assertTrue(restored.read(old,0).contains("delivered 12 iron"));
        assertTrue(restored.read(latest,0).contains("live slots"));
        assertNotEquals(latest, restored.put("next"));
        assertThrows(IllegalArgumentException.class, () -> new TaskResultStore(new TaskResultStore.State(2,100,"p",0,Map.of(),Map.of())));
    }
    @Test void alternatingLoopsPauseButQuantityPositionAndAmendmentsReset() {
        var guard = new AgentProgressGuard();
        assertEquals(0,guard.observe("north:0",List.of("scan north"),0,1));
        assertEquals(0,guard.observe("south:0",List.of("scan south"),0,1));
        assertEquals(1,guard.observe("north:0",List.of("scan north"),0,1));
        assertEquals(2,guard.observe("south:0",List.of("scan south"),0,1));
        assertEquals(3,guard.observe("north:0",List.of("scan north"),0,1));
        assertEquals(0,guard.observe("north:12",List.of("scan north"),12,1));
        assertEquals(0,guard.observe("arrived",List.of("scan north"),12,1));
        assertEquals(0,guard.observe("north:0",List.of("scan north"),0,2));
    }
    @Test void timestampsDoNotHideStalledObservationsAndBatchesCountOnce() {
        var guard = new AgentProgressGuard();
        for (int i=0;i<4;i++) assertEquals(i,guard.observe("unchanged",List.of("{\"game_tick\":"+i+",\"slots\":[1]}","{\"revision\":"+i+",\"slots\":[1]}"),0,1));
        assertTrue(ToolExecutionPolicy.requiresFreshWorld("unknown_mod_action"));
        assertFalse(ToolExecutionPolicy.requiresFreshWorld("load_tool_schema"));
    }
    @Test void executionArchiveRoundTripKeepsOrderedRecordsAndBoundedEvidence() {
        var trace=new AgentTaskArchive("maid","task");
        for(int i=0;i<50;i++) trace.append(i,1,"tool_result","call"+i,"inspect_gui",null,"ref"+i,"slots " + i);
        trace.results=new TaskResultStore().snapshot();
        var restored=new Gson().fromJson(new Gson().toJson(trace),AgentTaskArchive.class); restored.validateSize();
        assertEquals(50,restored.records.size()); assertEquals("call49",restored.records.get(49).callId());
        assertTrue(TaskResultStore.page(new Gson().toJson(restored.records),0).startsWith("offset=0"));
    }
    @Test void queueRetainsFourPendingAndStopNeverStartsNext() {
        var queue = new AgentTaskQueue();
        var first = queue.enqueue("one"); assertSame(first, queue.next());
        for (int i=0; i<4; i++) queue.enqueue("queued" + i);
        assertThrows(IllegalArgumentException.class, () -> queue.enqueue("overflow"));
        queue.stop(false); assertNull(queue.next()); assertEquals(AgentTaskState.Status.cancelled, first.status);
        assertEquals(4, queue.tasks.stream().filter(t -> !t.terminal()).count());
        queue.resume(); assertNotNull(queue.next());
    }
    @Test void amendmentsAccumulateAtBoundaryAndInvalidUpdateIsAtomic() {
        var task=new AgentTaskState("original");
        task.amend("keep gold"); task.amend("use only the north chest");
        assertThrows(IllegalArgumentException.class, () -> task.amend("invalid",new JsonObject()));
        assertFalse(task.pendingAmendment.contains("invalid"));
        task.applyAmendment(); assertEquals(2,task.goalVersion);
        assertTrue(task.constraints.get(0).contains("keep gold"));
        assertTrue(task.constraints.get(0).contains("north chest"));
    }
    @Test void reloadRequiresResumeAndPreservesFacts() {
        var queue = new AgentTaskQueue(); var task = queue.enqueue("move"); queue.next();
        task.checkpoint("already delivered 12 to 1,2,3"); task.loadedTools.add("gui_action");
        var restored = new Gson().fromJson(new Gson().toJson(queue), AgentTaskQueue.class);
        restored.suspend("reload"); assertNull(restored.next());
        restored.resume(); var resumed = restored.next();
        assertEquals(task.evidence, resumed.evidence); assertEquals(task.loadedTools, resumed.loadedTools);
        assertTrue(resumed.generation > task.generation);
    }
    @Test void completionRequiresMatchingItemLocationQuantityAndNetTransfers() {
        var task = new AgentTaskState("receive 10 iron");
        task.completion = AgentTaskState.validateCompletion(JsonParser.parseString(
                "{\"item\":\"minecraft:iron_ingot\",\"count\":10,\"dimension\":\"minecraft:overworld\",\"position\":[1,2,3],\"to_maid\":true}").getAsJsonObject());
        String evidence = "{\"moved_item\":\"minecraft:iron_ingot\",\"moved_count\":6,\"dimension\":\"minecraft:overworld\",\"target_position\":[1,2,3],\"to_maid\":true,\"external_transfer\":true}";
        task.recordResult(evidence); assertFalse(task.completionSatisfied());
        task.recordResult(evidence.replace("[1,2,3]", "[4,5,6]")); assertEquals(6, task.verifiedCount);
        task.recordResult(evidence); assertTrue(task.completionSatisfied());
        task.recordResult(evidence.replace("\"to_maid\":true", "\"to_maid\":false")); assertFalse(task.completionSatisfied());
        task.amend("make it 30"); task.applyAmendment(); assertFalse(task.completionSatisfied());
    }
    @Test void reversedParallelCompletionCommitsInCallOrder() {
        var first = new CompletableFuture<String>(); var second = new CompletableFuture<String>();
        List<String> committed = new ArrayList<>();
        var done = OrderedToolResults.commit(List.of(first, second), Runnable::run, committed::addAll);
        second.complete("second"); assertFalse(done.isDone()); assertTrue(committed.isEmpty());
        first.complete("first"); assertEquals(List.of("first", "second"), committed); assertTrue(done.isDone());
        assertFalse(ToolExecutionPolicy.forTool("unknown_mod_tool").parallel());
        assertFalse(ToolExecutionPolicy.forTool("gui_action").parallel());
    }
    @Test void cancelledOperationsRejectLateRegistrations() {
        var operations = new AgentOperations(); var first = new CompletableFuture<>(); operations.add(first);
        operations.cancel(); assertTrue(first.isCancelled());
        var late = new CompletableFuture<>(); operations.add(late); assertTrue(late.isCancelled());
        assertFalse(first.complete("late"));
    }
    @Test void evidenceEvictionAndUnicodePagesAreBounded() {
        var store = new TaskResultStore(12000); String old = store.put("a".repeat(10000));
        String ref = store.put("告示😀".repeat(1800)); assertTrue(store.read(old,0).startsWith("result_expired"));
        String page = store.read(ref,0); assertTrue(ContextTokenEstimator.estimate(page) <= 2048);
        assertFalse(page.endsWith("\uD83D")); assertEquals("invalid_offset", store.read(ref,-1));
    }
    @Test void fiftyToolStepsCompactWithoutLosingUserConstraintsOrToolPairs() {
        List<LLMMessage> messages = new ArrayList<>();
        var user = new LLMMessage(Role.USER,"Only move 64 iron; do not touch gold",0); messages.add(user);
        for(int i=0;i<50;i++) {
            var call = new ToolCall("call"+i,new FunctionToolCall("inspect_gui","{}"));
            messages.add(new LLMMessage(Role.ASSISTANT,"",0,List.of(call),null));
            messages.add(new LLMMessage(Role.TOOL,"inventory evidence ".repeat(100),0,null,call.getId()));
        }
        var pending = new ToolCall("pending",new FunctionToolCall("gui_action","{}"));
        messages.add(new LLMMessage(Role.ASSISTANT,"",0,List.of(pending),null));
        var compact = AgentContext.compact(messages,6000,new TaskResultStore());
        assertTrue(compact.contains(user)); assertTrue(ContextTokenEstimator.estimate(compact) < ContextTokenEstimator.estimate(messages)/2);
        Set<String> ids = new HashSet<>();
        for(var message:compact) {
            if(message.toolCalls()!=null) message.toolCalls().forEach(c -> assertTrue(ids.add(c.getId())));
            if(message.role()==Role.TOOL) assertTrue(ids.remove(message.toolCallId()));
        }
        assertEquals(Set.of("pending"),ids);
    }
    @Test void guiDeltaRetainsChangesAndCutsRedundantVolume() {
        JsonObject snapshot = new JsonObject(); JsonArray slots = new JsonArray();
        for(int i=0;i<90;i++) { JsonObject slot=new JsonObject();slot.addProperty("slot",i);slot.addProperty("item","minecraft:iron_ingot");slot.addProperty("count",64);slots.add(slot); }
        snapshot.add("slots",slots); snapshot.addProperty("status","ok");
        var projection=new GuiSnapshotProjection(); var full=projection.project(snapshot,true,false);
        slots.get(3).getAsJsonObject().addProperty("count",32);
        var delta=projection.project(snapshot,false,false);
        assertEquals(1,delta.getAsJsonArray("slots").size()); assertEquals(1,delta.get("base_revision").getAsLong());
        assertTrue(delta.toString().length()<full.toString().length()/2);
        assertFalse(projection.project(snapshot,false,true).has("slots"));
    }
}
