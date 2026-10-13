package com.wjx.aifun_gui_test;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.*;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.response.Message;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.init.InitEntities;
import com.google.gson.*;
import com.wjx.touhou_aifun.chat.ChatFlowManager;
import com.wjx.touhou_aifun.chat.agent.*;
import com.wjx.touhou_aifun.compat.ai.action.GuiTool;
import com.wjx.touhou_aifun.config.TouhouAIFunConfig;
import com.wjx.touhou_aifun.maid.gui.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraftforge.gametest.*;
import java.util.*;

@GameTestHolder("aifun_gui_test")
@PrefixGameTestTemplate(false)
public final class GuiTaskFinalizationGameTests {
    private static final class Fixture implements AutoCloseable {
        final boolean enabled=TouhouAIFunConfig.AGENT_RUNTIME.get();
        final EntityMaid maid;final AgentTaskState state;final TaskCallback task;final GameTestHelper helper;
        final BlockPos local=new BlockPos(1,1,3),position;
        Fixture(GameTestHelper helper,int required) throws Exception {
            this.helper=helper;TouhouAIFunConfig.AGENT_RUNTIME.set(true);var owner=helper.makeMockPlayer();
            maid=new EntityMaid(InitEntities.MAID.get(),helper.getLevel()) {@Override public net.minecraft.world.entity.LivingEntity getOwner(){return owner;}};
            var start=helper.absolutePos(new BlockPos(2,1,2));maid.setPos(start.getX()+.5,start.getY(),start.getZ()+.5);maid.setOwnerUUID(owner.getUUID());maid.setNoAi(true);helper.getLevel().addFreshEntity(maid);
            helper.setBlock(local,Blocks.CHEST);position=helper.absolutePos(local);chest().setItem(0,new ItemStack(Items.IRON_INGOT,2));
            var method=AgentRuntime.class.getDeclaredMethod("entry",EntityMaid.class);method.setAccessible(true);var entry=method.invoke(null,maid);
            var field=entry.getClass().getDeclaredField("queue");field.setAccessible(true);var queue=(AgentTaskQueue)field.get(entry);
            state=queue.enqueue("Collect required iron, leave hands empty and close the menu");queue.next();
            task=new TaskCallback(maid.getAiChatManager(),new ArrayList<>(),state);state.loadedTools.addAll(List.of(GuiTool.IDS));
            var fresh=TaskCallback.class.getDeclaredField("freshWorld");fresh.setAccessible(true);fresh.setBoolean(task,true);
            var active=entry.getClass().getDeclaredField("callback");active.setAccessible(true);active.set(entry,task);
            if(required>0) {
                var condition=new JsonObject();condition.addProperty("kind","transfer");condition.addProperty("item","minecraft:iron_ingot");condition.addProperty("count",required);condition.addProperty("to_maid",true);condition.addProperty("dimension","minecraft:overworld");
                var pos=new JsonArray();pos.add(position.getX());pos.add(position.getY());pos.add(position.getZ());condition.add("position",pos);task.defineCompletion(condition);
            }
        }
        ChestBlockEntity chest(){return (ChestBlockEntity)helper.getBlockEntity(local);}
        JsonObject target(BlockPos position) {JsonObject target=new JsonObject();target.addProperty("x",position.getX());target.addProperty("y",position.getY());target.addProperty("z",position.getZ());return target;}
        JsonObject open() {var request=target(position);request.addProperty("target","minecraft:chest");return MaidGuiSessionManager.call(task,"open_gui","qa_open",request).join();}
        JsonObject action() {var result=new JsonObject();result.addProperty("session_id",MaidGuiSessionManager.session(maid.getUUID()).id.toString());result.addProperty("action","quick_move");result.addProperty("slot",0);return result;}
        int run(String name,JsonObject request,boolean late) {
            JsonArray calls=new JsonArray();calls.add(call("qa_action",name,request));if(late) calls.add(call("qa_late","gui_action",new JsonObject()));
            JsonObject response=new JsonObject();response.add("tool_calls",calls);int[] model={0};task.onFunctionCall(new Gson().fromJson(response,Message.class),ignored->model[0]++);return model[0];
        }
        @Override public void close(){AgentRuntime.stop(maid,true);ChatFlowManager.forgetMaid(maid.getUUID());maid.discard();TouhouAIFunConfig.AGENT_RUNTIME.set(enabled);}
    }
    private static JsonObject call(String id,String name,JsonObject request) {
        JsonObject call=new JsonObject();call.addProperty("id",id);JsonObject function=new JsonObject();function.addProperty("name",name);function.addProperty("arguments",request.toString());call.add("function",function);return call;
    }
    private static JsonObject roundtrip(Fixture f,int count) {
        var receive=new JsonObject();receive.addProperty("kind","transfer");receive.addProperty("dimension","minecraft:overworld");receive.addProperty("item","minecraft:iron_ingot");receive.addProperty("count",count);receive.addProperty("to_maid",true);
        var pos=new JsonArray();pos.add(f.position.getX());pos.add(f.position.getY());pos.add(f.position.getZ());receive.add("position",pos);
        var send=receive.deepCopy();send.addProperty("to_maid",false);
        var restored=new JsonObject();restored.addProperty("kind","container_unchanged");restored.addProperty("dimension","minecraft:overworld");restored.add("position",pos.deepCopy());
        var empty=new JsonObject();empty.addProperty("kind","backpack_empty");empty.addProperty("dimension","minecraft:overworld");
        var finalConditions=new JsonArray();finalConditions.add(send);finalConditions.add(restored);finalConditions.add(empty);
        var last=new JsonObject();last.addProperty("kind","all");last.add("conditions",finalConditions);
        var phases=new JsonArray();phases.add(receive);phases.add(last);var sequence=new JsonObject();sequence.addProperty("kind","sequence");sequence.add("phases",phases);return sequence;
    }
    @GameTest(template="empty",templateNamespace="aifun_gui_test")
    public static void generalExecutionSkipsRemoteTemplateProbeAndLoadsDecisionSyntaxOnlyOnDemand(GameTestHelper helper) throws Exception {
        boolean service=TouhouAIFunConfig.DECISION_SERVICE_ENABLED.get(),templates=TouhouAIFunConfig.DECISION_STARTUP_TEMPLATES.get();
        TouhouAIFunConfig.DECISION_SERVICE_ENABLED.set(true);TouhouAIFunConfig.DECISION_STARTUP_TEMPLATES.set(false);
        try(var f=new Fixture(helper,0)) {
            var catalog=com.wjx.touhou_aifun.compat.ai.openai.ToolContextSelector.snapshot(f.maid,f.task);
            var lean=catalog.planningEntries().stream().filter(e->e.id().equals("update_task_plan")).findFirst().orElseThrow();
            helper.assertTrue(!lean.anthropicTool().getAsJsonObject("input_schema").getAsJsonObject("properties").has("action_steps") && !new Gson().toJson(lean.openAITool()).contains("action_steps"),"Both protocols omit optional full executable syntax");
            var router=new com.wjx.touhou_aifun.chat.agent.decision.TaskDecisionRouter(f.task);
            helper.assertTrue(!router.route(ignored->{throw new AssertionError("Unexpected model dispatch");},new JsonObject(),catalog,true,false),"A generic unplanned task proceeds immediately without speculative remote decision");
            helper.assertTrue(f.task.getMessages().stream().noneMatch(m->AgentTaskInstructions.DECISION_POLICY.equals(m.message())),"Normal requests carry only the short optional decision hint");
            new com.wjx.touhou_aifun.compat.ai.openai.LoadToolSchemaTool().onCall("load_optional_decisions","group:task_actions",f.task);
            var advanced=com.wjx.touhou_aifun.compat.ai.openai.ToolContextSelector.snapshot(f.maid,f.task).planningEntries().stream().filter(e->e.id().equals("update_task_plan")).findFirst().orElseThrow();
            helper.assertTrue(advanced.anthropicTool().getAsJsonObject("input_schema").getAsJsonObject("properties").has("action_steps") && new Gson().toJson(advanced.openAITool()).contains("action_steps"),"Explicit segment request refreshes both protocols' frozen schemas");
            helper.assertTrue(lean.schemaTokens()<advanced.schemaTokens()/2,"Default planner schema removes more than half the optional syntax cost");
            System.out.println("GENERAL_EXECUTION_SCHEMA_TOKENS lean="+lean.schemaTokens()+" advanced="+advanced.schemaTokens());helper.succeed();
        } finally {TouhouAIFunConfig.DECISION_SERVICE_ENABLED.set(service);TouhouAIFunConfig.DECISION_STARTUP_TEMPLATES.set(templates);}
    }
    private static JsonObject roundtripBatch(JsonObject opened,int targetSlot) {
        int backpack=-1;for(var row:opened.getAsJsonArray("slots")) {var slot=row.getAsJsonObject();if(slot.has("backpack_slot") && slot.get("backpack_slot").getAsInt()==0)backpack=slot.get("slot").getAsInt();}
        if(backpack<0)throw new AssertionError("No observed backpack slot");
        var take=new JsonObject();take.addProperty("action","transfer");take.addProperty("slot",0);take.addProperty("to_slot",backpack);take.addProperty("count",2);
        var give=take.deepCopy();give.addProperty("slot",backpack);give.addProperty("to_slot",targetSlot);
        var actions=new JsonArray();actions.add(take);actions.add(give);var request=new JsonObject();request.add("session_id",opened.get("session_id"));request.add("expected_revision",opened.get("revision"));request.add("actions",actions);request.addProperty("complete_task",true);return request;
    }
    @GameTest(template="empty",templateNamespace="aifun_gui_test")
    public static void genericRoundtripCompletesInOneOrderedBatchWithoutTemplateOrExtraModel(GameTestHelper helper) throws Exception {
        try(var f=new Fixture(helper,0)) {
            f.chest().getItem(0).getOrCreateTag().putString("qa_identity","original");var original=f.chest().getItem(0).copy();var opened=f.open();f.task.defineCompletion(roundtrip(f,2));
            var oldCheckpoint=new Gson().fromJson(new Gson().toJson(f.state),AgentTaskState.class);
            helper.assertTrue(f.state.actionSteps.isEmpty(),"Generic execution uses no executable template");
            helper.assertTrue(f.run("gui_batch",roundtripBatch(opened,0),false)==0 && f.state.status==AgentTaskState.Status.completed,"Both ordered phases finalize without another LLM request");
            helper.assertTrue(f.state.completionStage==1 && f.state.transfers.values().stream().allMatch(n->n==0) && f.state.verifiedCount==2,"Opposite legs use phase receipts, while total net remains zero");
            helper.assertTrue(ItemStack.matches(original,f.chest().getItem(0)) && MaidGuiSessionManager.session(f.maid.getUUID())==null,"Exact slot/count/NBT restored and menu settled");
            var archive=AgentArchiveData.get(helper.getLevel()).task(f.maid.getUUID(),f.state.id);
            new Gson().fromJson(new Gson().toJson(archive),AgentTaskArchive.class).reconcile(oldCheckpoint);
            helper.assertTrue(oldCheckpoint.completionStage==1 && AgentCompletion.verify(f.maid,oldCheckpoint,true),"Durable mutation archive restores phase receipts and original private container baseline");
            helper.succeed();
        }
    }
    @GameTest(template="empty",templateNamespace="aifun_gui_test")
    public static void liveOnlyPhaseProgressIsPersistedWithoutInventingATransfer(GameTestHelper helper) throws Exception {
        try(var f=new Fixture(helper,0)) {
            var position=new JsonObject();position.addProperty("kind","position");position.addProperty("dimension","minecraft:overworld");var xyz=new JsonArray();xyz.add(f.maid.blockPosition().getX());xyz.add(f.maid.blockPosition().getY());xyz.add(f.maid.blockPosition().getZ());position.add("position",xyz);
            var stock=new JsonObject();stock.addProperty("kind","inventory");stock.addProperty("dimension","minecraft:overworld");stock.addProperty("item","minecraft:iron_ingot");stock.addProperty("count",2);
            var phases=new JsonArray();phases.add(position);phases.add(stock);var condition=new JsonObject();condition.addProperty("kind","sequence");condition.add("phases",phases);f.task.defineCompletion(condition);
            helper.assertTrue(!AgentCompletion.verify(f.maid,f.state,true) && f.state.completionStage==1 && f.state.transfers.isEmpty(),"Verified arrival advances phase but does not claim final stock or fabricate movement");
            helper.assertTrue(AgentArchiveData.get(helper.getLevel()).task(f.maid.getUUID(),f.state.id).verifiedCompletionStage==1 && f.state.appliedMutationVersion>0,"Live-only stage progress is durably committed");helper.succeed();
        }
    }
    @GameTest(template="empty",templateNamespace="aifun_gui_test")
    public static void genericRoundtripCannotClaimSuccessAfterOnlyPartialRequiredTake(GameTestHelper helper) throws Exception {
        try(var f=new Fixture(helper,0)) {
            var opened=f.open();f.task.defineCompletion(roundtrip(f,3));
            helper.assertTrue(f.run("gui_batch",roundtripBatch(opened,0),false)==1 && f.state.status==AgentTaskState.Status.running && f.state.completionStage==0,"A restored box cannot substitute for the required intermediate quantity");
            helper.assertTrue(!AgentCompletion.verify(f.maid,f.state,true) && f.chest().getItem(0).getCount()==2,"Actual work is retained, and insufficient receipt is reported");helper.succeed();
        }
    }
    @GameTest(template="empty",templateNamespace="aifun_gui_test")
    public static void genericRoundtripRejectsWrongSlotAndCannotRedefineBaseline(GameTestHelper helper) throws Exception {
        try(var f=new Fixture(helper,0)) {
            var opened=f.open();var contract=roundtrip(f,2);f.task.defineCompletion(contract);
            helper.assertTrue(f.run("gui_batch",roundtripBatch(opened,1),false)==1 && f.state.status==AgentTaskState.Status.running && f.state.completionStage==1,"Returning the quantity to a different slot fails restoration");
            f.open();f.task.defineCompletion(contract);
            helper.assertTrue(!AgentCompletion.verify(f.maid,f.state,true),"Repeated identical contract does not recapture a changed baseline");
            f.chest().setItem(0,f.chest().getItem(1));f.chest().setItem(1,ItemStack.EMPTY);helper.assertTrue(AgentCompletion.verify(f.maid,f.state,true),"Original arrangement restores the private baseline");
            f.chest().getItem(0).getOrCreateTag().putString("qa_identity","tampered");helper.assertTrue(!AgentCompletion.verify(f.maid,f.state,true),"Same item and count with different NBT cannot prove restoration");helper.succeed();
        }
    }
    @GameTest(template="empty",templateNamespace="aifun_gui_test")
    public static void finalTransferClosesVerifiesAndPairsLateCallsWithoutAnotherModel(GameTestHelper helper) throws Exception {
        try(var f=new Fixture(helper,2)) {
            helper.assertTrue(!f.open().has("error"),"Observed chest opened");var action=f.action();action.addProperty("complete_task",true);
            helper.assertTrue(f.run("gui_action",action,true)==0 && f.state.status==AgentTaskState.Status.completed,"Final action completes after settlement with no subsequent model request");
            int count=0;for(int i=0;i<f.maid.getAvailableBackpackInv().getSlots();i++) {var stack=f.maid.getAvailableBackpackInv().getStackInSlot(i);if(stack.is(Items.IRON_INGOT)) count+=stack.getCount();}
            helper.assertTrue(f.chest().isEmpty() && count==2 && f.state.verifiedCount==2,"Actual quantity transferred exactly once");
            helper.assertTrue(MaidGuiSessionManager.session(f.maid.getUUID())==null && f.task.settlement().isDone(),"Cleanup and settlement precede task completion");
            helper.assertTrue(f.task.getMessages().stream().filter(m->m.role()==Role.TOOL).count()==2 && f.task.getMessages().stream().anyMatch(m->"qa_late".equals(m.toolCallId()) && m.message().contains("cancelled_before_start")),"Accepted late action is cancelled with its paired result");helper.succeed();
        }
    }
    @GameTest(template="empty",templateNamespace="aifun_gui_test")
    public static void closeAfterIsCleanupAndNeverImplicitCompletion(GameTestHelper helper) throws Exception {
        try(var f=new Fixture(helper,2)) {
            helper.assertTrue(!f.open().has("error"),"Observed chest opened");var action=f.action();action.addProperty("close_after",true);
            helper.assertTrue(f.run("gui_action",action,false)==1 && f.state.status==AgentTaskState.Status.running && AgentCompletion.verify(f.maid,f.state,true),"Even satisfied predicates require the executor's explicit final declaration");
            helper.assertTrue(f.chest().isEmpty() && MaidGuiSessionManager.session(f.maid.getUUID())==null,"Intermediate cleanup settles the real transfer without another close turn");helper.succeed();
        }
    }
    @GameTest(template="empty",templateNamespace="aifun_gui_test")
    public static void invalidFinalizationFlagsCannotMoveItemsAndFullObservationIsAvailable(GameTestHelper helper) throws Exception {
        try(var f=new Fixture(helper,2)) {
            var opened=f.open();helper.assertTrue(!opened.has("error") && !opened.getAsJsonArray("slots").get(0).getAsJsonObject().has("x"),"Default task view needs an actual capture for pixel geometry");
            var inspect=new JsonObject();inspect.add("session_id",opened.get("session_id"));inspect.addProperty("detail","full");
            var full=MaidGuiSessionManager.call(f.task,"inspect_gui","full",inspect).join();helper.assertTrue(full.getAsJsonArray("slots").get(0).getAsJsonObject().has("x"),"Explicit full view retains the complete original snapshot");
            var action=f.action();action.addProperty("complete_task","true");
            helper.assertTrue(f.run("gui_action",action,false)==1 && f.chest().getItem(0).getCount()==2 && f.state.transfers.isEmpty(),"Malformed finalization flag rejects the whole call before item mutation");
            helper.assertTrue(MaidGuiSessionManager.session(f.maid.getUUID())!=null && !f.state.failures.isEmpty(),"Rejected input retains the observed menu and precise failure");helper.succeed();
        }
    }
    @GameTest(template="empty",templateNamespace="aifun_gui_test")
    public static void insufficientFinalTransferRetainsFactsAndReplans(GameTestHelper helper) throws Exception {
        try(var f=new Fixture(helper,3)) {
            helper.assertTrue(!f.open().has("error"),"Observed chest opened");var action=f.action();action.addProperty("complete_task",true);
            helper.assertTrue(f.run("gui_action",action,false)==1 && f.state.status==AgentTaskState.Status.running,"Missing quantity cannot be masked by the final-action flag");
            helper.assertTrue(f.chest().isEmpty() && f.state.verifiedCount==2 && MaidGuiSessionManager.session(f.maid.getUUID())==null,"Two committed items survive the failed finalization and cleanup");
            helper.assertTrue(f.task.getMessages().stream().anyMatch(m->m.role()==Role.TOOL && m.message().contains("completion_evidence_unmet")) && !f.state.failures.isEmpty(),"Exact unmet evidence is returned and counted as a failure");helper.succeed();
        }
    }
    @GameTest(template="empty",templateNamespace="aifun_gui_test")
    public static void partialGuiBatchNeverClosesOrDeclaresCompletion(GameTestHelper helper) throws Exception {
        try(var f=new Fixture(helper,2)) {
            var opened=f.open();helper.assertTrue(!opened.has("error"),"Observed chest opened");var request=new JsonObject();request.add("session_id",opened.get("session_id"));request.add("expected_revision",opened.get("revision"));request.addProperty("complete_task",true);
            var actions=new JsonArray();var first=f.action();first.remove("session_id");actions.add(first);var invalid=first.deepCopy();invalid.addProperty("slot",999);actions.add(invalid);request.add("actions",actions);
            helper.assertTrue(f.run("gui_batch",request,false)==1 && f.state.status==AgentTaskState.Status.running,"Partially failed batch replans even if the quantity predicate happens to pass");
            helper.assertTrue(f.chest().isEmpty() && f.state.verifiedCount==2 && MaidGuiSessionManager.session(f.maid.getUUID())!=null,"Earlier transfer remains committed and failed batch keeps its GUI inspectable");helper.succeed();
        }
    }
    @GameTest(template="empty",templateNamespace="aifun_gui_test")
    public static void inspectMultipleChestsReturnsCountsAndOnlyLastCurrentSnapshot(GameTestHelper helper) throws Exception {
        try(var f=new Fixture(helper,0)) {
            var second=new BlockPos(3,1,3);helper.setBlock(second,Blocks.CHEST);((ChestBlockEntity)helper.getBlockEntity(second)).setItem(0,new ItemStack(Items.GOLD_INGOT,3));
            JsonObject request=new JsonObject();JsonArray targets=new JsonArray();targets.add(f.target(f.position));targets.add(f.target(helper.absolutePos(second)));request.add("targets",targets);
            var result=GuiContainerInspection.inspect(f.task,"qa_inspect",request).join();helper.assertTrue(!result.has("error"),"Bounded ordinary-menu inspection succeeds");
            var containers=result.getAsJsonArray("containers");helper.assertTrue(containers.size()==2 && containers.get(0).getAsJsonObject().getAsJsonArray("items").get(0).getAsJsonObject().get("count").getAsInt()==2 && containers.get(1).getAsJsonObject().getAsJsonArray("items").get(0).getAsJsonObject().get("count").getAsInt()==3,"Both actual item counts are reported in target order");
            helper.assertTrue(result.getAsJsonObject("active_gui").has("slots") && result.getAsJsonObject("active_gui").get("session_id").getAsString().equals(MaidGuiSessionManager.session(f.maid.getUUID()).id.toString()),"Only the final open menu supplies current slot authority");
            helper.assertTrue(result.getAsJsonObject("active_gui").get("wait_policy").getAsString().equals("AUTO"),"Read-only inspection must not force future processing to NO_WAIT");
            helper.assertTrue(f.chest().getItem(0).getCount()==2 && ((ChestBlockEntity)helper.getBlockEntity(second)).getItem(0).getCount()==3 && f.state.transfers.isEmpty(),"Inspection does not collect items or invent transfer evidence");helper.succeed();
        }
    }
    @GameTest(template="empty",templateNamespace="aifun_gui_test")
    public static void inspectionRejectsMalformedBatchAndPendingAmendmentBeforeOpening(GameTestHelper helper) throws Exception {
        try(var f=new Fixture(helper,0)) {
            JsonObject request=new JsonObject();JsonArray targets=new JsonArray();targets.add(f.target(f.position));targets.add(f.target(f.position));request.add("targets",targets);
            helper.assertTrue(GuiContainerInspection.inspect(f.task,"duplicate",request).join().has("error") && MaidGuiSessionManager.session(f.maid.getUUID())==null,"Entire target list validates before first open");
            targets.remove(1);f.state.amend("Do something else first");var result=GuiContainerInspection.inspect(f.task,"changed",request).join();
            helper.assertTrue(result.has("error") && result.getAsJsonArray("containers").isEmpty() && MaidGuiSessionManager.session(f.maid.getUUID())==null && f.state.transfers.isEmpty(),"New owner conditions stop internal dispatch before any inspection");helper.succeed();
        }
    }
}
