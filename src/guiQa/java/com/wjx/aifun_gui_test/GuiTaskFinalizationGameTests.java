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
