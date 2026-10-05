package com.wjx.aifun_gui_test;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.response.ResponseChat;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.*;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.response.Message;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.init.InitEntities;
import com.google.gson.*;
import com.wjx.touhou_aifun.chat.*;
import com.wjx.touhou_aifun.chat.agent.*;
import com.wjx.touhou_aifun.config.TouhouAIFunConfig;
import com.wjx.touhou_aifun.maid.gui.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraftforge.gametest.*;
import java.util.*;

@GameTestHolder("aifun_gui_test")
@PrefixGameTestTemplate(false)
public final class AgentRuntimeGameTests {
    @GameTest(template="empty",templateNamespace="aifun_gui_test")
    public static void rejectShovelPreconditionAndPreserveTransferFactsAcrossBeefAmendment(GameTestHelper helper) throws Exception {
        var maid=helper.spawn(InitEntities.MAID.get(),new BlockPos(2,1,2));maid.setNoAi(true);
        helper.setBlock(new BlockPos(1,1,2),Blocks.CHEST);var pos=helper.absolutePos(new BlockPos(1,1,2));
        var state=new AgentTaskState("Put the shovel away, then add 59 beef when asked");state.status=AgentTaskState.Status.running;
        var task=new TaskCallback(maid.getAiChatManager(),new ArrayList<>(),state);
        var fresh=TaskCallback.class.getDeclaredField("freshWorld");fresh.setAccessible(true);fresh.setBoolean(task,true);
        maid.getAvailableBackpackInv().setStackInSlot(0,new ItemStack(Items.IRON_SHOVEL));
        String location="["+pos.getX()+","+pos.getY()+","+pos.getZ()+"]";
        var shovel=JsonParser.parseString("{\"kind\":\"transfer\",\"item\":\"minecraft:iron_shovel\",\"count\":1,\"to_maid\":false,\"dimension\":\"minecraft:overworld\",\"position\":"+location+"}").getAsJsonObject();
        var inventory=JsonParser.parseString("{\"kind\":\"inventory\",\"item\":\"minecraft:iron_shovel\",\"count\":1,\"dimension\":\"minecraft:overworld\"}").getAsJsonObject();
        JsonArray leaves=new JsonArray();leaves.add(inventory);leaves.add(shovel);JsonObject wrong=new JsonObject();wrong.addProperty("kind","all");wrong.add("conditions",leaves);
        try {
            boolean rejected=false;try {task.defineCompletion(wrong);}catch(IllegalArgumentException e){rejected=e.getMessage().contains("completion_supply_conflict") && e.getMessage().contains("minecraft:iron_shovel");}
            helper.assertTrue(rejected && state.completion==null,"Initial possession cannot be confused with final reserved stock; rejection precedes world actions");
            task.defineCompletion(shovel);transferOutForCompletionQa(maid,task,pos,Items.IRON_SHOVEL,1);
            helper.assertTrue(AgentCompletion.verify(maid,state,true),"Actual shovel transfer verifies the valid outbound condition");
            state.completion=wrong;var diagnostic=AgentCompletion.diagnostics(maid,state,true);
            helper.assertTrue(diagnostic.getAsJsonArray("unmet_conditions").size()==1 && diagnostic.toString().contains("final_inventory_below_required_count"),"Legacy wrong contract names only the failed final inventory predicate");
            helper.assertTrue(diagnostic.getAsJsonArray("unmet_conditions").get(0).getAsJsonObject().get("actual_count").getAsInt()==0,"Diagnostic reports actual zero rather than resetting delivered facts");
            state.amend("Keep the shovel in its chest; also deposit all 59 beef. No shovel needs to remain in the backpack.");state.applyAmendment();
            maid.getAvailableBackpackInv().setStackInSlot(0,new ItemStack(Items.COOKED_BEEF,59));
            JsonArray updated=new JsonArray();updated.add(shovel);var beef=shovel.deepCopy();beef.addProperty("item","minecraft:cooked_beef");beef.addProperty("count",59);updated.add(beef);
            JsonObject all=new JsonObject();all.addProperty("kind","all");all.add("conditions",updated);task.defineCompletion(all);
            helper.assertTrue(state.transfers.size()==1 && !AgentCompletion.verify(maid,state,true),"Amendment keeps the completed shovel and still requires beef");
            var unmet=AgentCompletion.diagnostics(maid,state,true).getAsJsonArray("unmet_conditions");
            helper.assertTrue(unmet.size()==1 && unmet.get(0).getAsJsonObject().get("item").getAsString().equals("minecraft:cooked_beef") && unmet.get(0).getAsJsonObject().get("remaining_count").getAsInt()==59,"Only the newly requested beef remains");
            transferOutForCompletionQa(maid,task,pos,Items.COOKED_BEEF,59);task.requestCompletion();
            helper.assertTrue(AgentCompletion.verify(maid,state,true) && state.transfers.size()==2 && state.verifiedCount==60,"Completion follows two actual transfers without replaying the shovel");helper.succeed();
        } finally {MaidGuiSessionManager.cancel(maid.getUUID(),"qa_complete");state.status=AgentTaskState.Status.cancelled;task.archiveState();}
    }
    private static void transferOutForCompletionQa(EntityMaid maid,TaskCallback task,BlockPos pos,Item item,int count) {
        maid.setPos(pos.getX()+1.5,pos.getY(),pos.getZ()+.5);JsonObject open=new JsonObject();open.addProperty("target","minecraft:chest");open.addProperty("x",pos.getX());open.addProperty("y",pos.getY());open.addProperty("z",pos.getZ());
        var result=MaidGuiSessionManager.call(task,"open_gui","open_"+item,open).join();if(result.has("error")) throw new IllegalStateException(result.toString());
        var session=MaidGuiSessionManager.session(maid.getUUID());int source=-1,destination=-1;
        for(var slot:session.menu().slots) {
            if(slot.container==session.actor.getInventory() && slot.getItem().is(item)) source=slot.index;
            if(slot.container!=session.actor.getInventory() && slot.getItem().isEmpty() && destination<0) destination=slot.index;
        }
        if(source<0 || destination<0) throw new IllegalStateException("Missing observed slots");
        JsonObject action=new JsonObject();action.addProperty("session_id",session.id.toString());action.addProperty("action","transfer");action.addProperty("slot",source);action.addProperty("to_slot",destination);action.addProperty("count",count);
        result=MaidGuiSessionManager.call(task,"gui_action","move_"+item,action).join();if(result.has("error") || result.get("moved_count").getAsInt()!=count) throw new IllegalStateException(result.toString());
        MaidGuiSessionManager.cancel(maid.getUUID(),"between_transfers");
    }
    @GameTest(template="empty",templateNamespace="aifun_gui_test")
    public static void ownerSupplementRetainsOriginalTextAndSupersedesOlderExclusions(GameTestHelper helper) throws Exception {
        boolean enabled=TouhouAIFunConfig.AGENT_RUNTIME.get();TouhouAIFunConfig.AGENT_RUNTIME.set(true);
        var maid=helper.spawn(InitEntities.MAID.get(),new BlockPos(2,1,2));maid.setNoAi(true);UUID owner=UUID.randomUUID();maid.setOwnerUUID(owner);
        try {
            AgentRuntime.control(ownerTurn(maid,owner,"sort"),command("enqueue","Sort only four specified items; leave beef untouched until asked."));
            var method=AgentRuntime.class.getDeclaredMethod("entry",EntityMaid.class);method.setAccessible(true);Object entry=method.invoke(null,maid);
            var queueField=entry.getClass().getDeclaredField("queue");queueField.setAccessible(true);var queue=(AgentTaskQueue)queueField.get(entry);var state=queue.next();
            state.transfers.put("minecraft:overworld:[1,2,3]:minecraft:raw_iron",-1L);
            String raw="Also put all 59 cooked beef into the food chest. Keep all original sorting requirements and do not repeat items already placed.";
            var supplement=ownerTurn(maid,owner,raw);supplement.getMessages().add(LLMMessage.userChat(maid,raw));
            var receipt=AgentRuntime.control(supplement,command("amend","Keep ONLY the old four items, nothing else; additionally move beef."));
            helper.assertTrue(state.pendingAmendment.equals(raw) && receipt.get("accepted_owner_amendment").getAsString().equals(raw),"Owner text is authoritative, not the model's contradictory restatement");
            var task=new TaskCallback(maid.getAiChatManager(),new ArrayList<>(List.of(LLMMessage.userChat(maid,state.goal))),state);
            var callbackField=entry.getClass().getDeclaredField("callback");callbackField.setAccessible(true);callbackField.set(entry,task);boolean[] called={false};
            task.next(current->{called[0]=true;var users=current.getMessages().stream().filter(m->m.role()==Role.USER).toList();
                helper.assertTrue(users.get(users.size()-1).message().contains(raw) && users.get(users.size()-1).message().contains("supersedes conflicting"),"Latest user message carries the owner update and override priority");});
            helper.assertTrue(called[0] && state.goalVersion==2 && state.constraints.equals(List.of(raw)) && state.transfers.size()==1,"Boundary update keeps versioned constraints and previous transfer facts");helper.succeed();
        } finally {AgentRuntime.stop(maid,true);ChatFlowManager.forgetMaid(maid.getUUID());maid.discard();TouhouAIFunConfig.AGENT_RUNTIME.set(enabled);}
    }
    @GameTest(template="empty",templateNamespace="aifun_gui_test")
    public static void foregroundStatusNeverAnnouncesUnverifiedTaskCompletion(GameTestHelper helper) throws Exception {
        boolean enabled=TouhouAIFunConfig.AGENT_RUNTIME.get(),tts=com.github.tartaricacid.touhoulittlemaid.config.subconfig.AIConfig.TTS_ENABLED.get();
        TouhouAIFunConfig.AGENT_RUNTIME.set(true);com.github.tartaricacid.touhoulittlemaid.config.subconfig.AIConfig.TTS_ENABLED.set(false);
        var maid=helper.spawn(InitEntities.MAID.get(),new BlockPos(2,1,2));maid.setNoAi(true);UUID owner=UUID.randomUUID();maid.setOwnerUUID(owner);maid.getAiChatManager().chatLanguage="zh_cn";
        try {
            var initial=ownerTurn(maid,owner,"sort");AgentRuntime.control(initial,command("enqueue","put shovel away"));
            var method=AgentRuntime.class.getDeclaredMethod("entry",EntityMaid.class);method.setAccessible(true);Object entry=method.invoke(null,maid);
            var queueField=entry.getClass().getDeclaredField("queue");queueField.setAccessible(true);var queue=(AgentTaskQueue)queueField.get(entry);var state=queue.next();state.transfers.put("world:chest:shovel",-1L);AgentRuntime.save(maid);
            var front=ownerTurn(maid,owner,"嗯，东西放进去了吧？");front.getMessages().add(LLMMessage.userChat(maid,"嗯，东西放进去了吧？"));AgentRuntime.prepareForeground(front);
            helper.assertTrue(AgentRuntime.validateForegroundAnswer(front),"Unverified answers cannot be streamed/spoken before validation");
            var wrong=new ResponseChat("全部照着告示牌送进对应箱子了。","Everything is done.");var guarded=AgentRuntime.guardForegroundReply(front,wrong);
            helper.assertTrue(guarded!=wrong && !ForegroundTaskStatus.claimsCompletion(guarded.getChatText()) && guarded.getChatText().contains("尚未通过"),"Running state overrides empty-backpack completion inference");
            helper.assertTrue(AgentRuntime.guardForegroundReply(front,guarded)==guarded,"Finalization can re-enter the mixin without recursive rewriting");
            front.onSuccess(wrong);
            helper.assertTrue(maid.getAiChatManager().getHistory().peek().message().contains("尚未通过"),"Actual onSuccess mixin writes only the validated reply into history");
            state.status=AgentTaskState.Status.paused;state.outcome="shovel final inventory unmet";AgentRuntime.save(maid);
            helper.assertTrue(AgentRuntime.guardForegroundReply(front,wrong).getChatText().contains("暂停"),"Paused task cannot be presented as successful");
            var casual=ownerTurn(maid,owner,"How is the weather?");casual.getMessages().add(LLMMessage.userChat(maid,"How is the weather?"));var chat=new ResponseChat("今天阳光不错。");
            helper.assertTrue(AgentRuntime.guardForegroundReply(casual,chat)==chat,"Ordinary conversation stays independent");
            state.status=AgentTaskState.Status.completed;AgentRuntime.save(maid);
            helper.assertTrue(AgentRuntime.guardForegroundReply(front,wrong).getChatText().contains("已通过"),"Only verified completed state permits the positive task status");helper.succeed();
        } finally {AgentRuntime.stop(maid,true);ChatFlowManager.forgetMaid(maid.getUUID());maid.discard();TouhouAIFunConfig.AGENT_RUNTIME.set(enabled);com.github.tartaricacid.touhoulittlemaid.config.subconfig.AIConfig.TTS_ENABLED.set(tts);}
    }
    @GameTest(template="empty",templateNamespace="aifun_gui_test")
    public static void missingCompletionRejectsLabelAndNeedsFreshObservation(GameTestHelper helper) throws Exception {
        var maid=helper.spawn(InitEntities.MAID.get(),new BlockPos(2,1,2));maid.setNoAi(true);
        helper.setBlock(new BlockPos(1,1,4),Blocks.CHEST);
        helper.setBlock(new BlockPos(2,1,4),Blocks.OAK_WALL_SIGN.defaultBlockState().setValue(net.minecraft.world.level.block.WallSignBlock.FACING,net.minecraft.core.Direction.EAST));
        var state=new AgentTaskState("put one iron into the labelled chest");state.status=AgentTaskState.Status.running;
        var callback=new TaskCallback(maid.getAiChatManager(),new ArrayList<>(),state);
        var label=helper.absolutePos(new BlockPos(2,1,4));var chest=helper.absolutePos(new BlockPos(1,1,4));
        var spec=JsonParser.parseString("{\"kind\":\"transfer\",\"item\":\"minecraft:iron_ingot\",\"count\":1,\"to_maid\":false,\"dimension\":\"minecraft:overworld\",\"position\":["+label.getX()+","+label.getY()+","+label.getZ()+"]}").getAsJsonObject();
        try {
            boolean rejected=false;try{callback.defineCompletion(spec);}catch(IllegalArgumentException expected){rejected=expected.getMessage().equals("fresh_world_observation_required");}
            helper.assertTrue(rejected && state.completion==null,"Saved labels cannot establish current completion without fresh observation");
            var field=TaskCallback.class.getDeclaredField("freshWorld");field.setAccessible(true);field.setBoolean(callback,true);
            rejected=false;try{callback.defineCompletion(spec);}catch(IllegalArgumentException expected){rejected=expected.getMessage().startsWith("completion_position_is_sign");}
            helper.assertTrue(rejected && state.completion==null,"Reject sign coordinates before freezing an impossible transfer contract");
            JsonArray coordinates=new JsonArray();coordinates.add(chest.getX());coordinates.add(chest.getY());coordinates.add(chest.getZ());spec.add("position",coordinates);
            callback.defineCompletion(spec);helper.assertTrue(state.completion!=null,"Observed actual container can establish completion before any transfer");
            helper.succeed();
        } finally {state.status=AgentTaskState.Status.cancelled;callback.archiveState();}
    }
    @GameTest(template="empty",templateNamespace="aifun_gui_test")
    public static void backgroundOutcomeNotifiesOwnerOnceAfterForegroundFinishes(GameTestHelper helper) throws Exception {
        boolean enabled=TouhouAIFunConfig.AGENT_RUNTIME.get();TouhouAIFunConfig.AGENT_RUNTIME.set(true);
        int[] delivered={0};
        var owner=new net.minecraftforge.common.util.FakePlayer(helper.getLevel(),
                new com.mojang.authlib.GameProfile(UUID.randomUUID(),"notification_owner")) {
            @Override public void sendSystemMessage(net.minecraft.network.chat.Component message) { delivered[0]++; }
        };
        var maid=new EntityMaid(InitEntities.MAID.get(),helper.getLevel()) {
            @Override public net.minecraft.world.entity.LivingEntity getOwner(){return owner;}
        };
        var pos=helper.absolutePos(new BlockPos(2,1,2));maid.setPos(pos.getX()+.5,pos.getY(),pos.getZ()+.5);
        maid.setNoAi(true);maid.setOwnerUUID(owner.getUUID());helper.getLevel().addFreshEntity(maid);
        try {
            var front=ownerTurn(maid,owner.getUUID(),"receive iron");AgentRuntime.control(front,command("enqueue","receive iron"));
            var method=AgentRuntime.class.getDeclaredMethod("entry",EntityMaid.class);method.setAccessible(true);var entry=method.invoke(null,maid);
            var queueField=entry.getClass().getDeclaredField("queue");queueField.setAccessible(true);var queue=(AgentTaskQueue)queueField.get(entry);
            var state=queue.next();var task=new TaskCallback(maid.getAiChatManager(),new ArrayList<>(),state);
            var field=entry.getClass().getDeclaredField("callback");field.setAccessible(true);field.set(entry,task);
            AgentRuntime.finished(task,"Verified iron received.",true);
            var tick=new net.minecraftforge.event.TickEvent.ServerTickEvent(net.minecraftforge.event.TickEvent.Phase.END,()->true,helper.getLevel().getServer());
            AgentRuntime.tick(tick);helper.assertTrue(delivered[0]==0,"Background notification waits while foreground owns the conversation");
            ChatFlowManager.finishRequest(maid.getUUID(),front);
            AgentRuntime.tick(tick);helper.assertTrue(delivered[0]==1,"The bubble manager sends exactly one owner chat message");
            AgentRuntime.tick(tick);helper.assertTrue(delivered[0]==1,"Later ticks do not replay the completion notification");
            helper.succeed();
        } finally {AgentRuntime.stop(maid,true);ChatFlowManager.forgetMaid(maid.getUUID());maid.discard();TouhouAIFunConfig.AGENT_RUNTIME.set(enabled);}
    }
    @GameTest(template="empty",templateNamespace="aifun_gui_test")
    public static void multipleChestsKeepSourceEvidenceSeparateAcrossArchiveReload(GameTestHelper helper) throws Exception {
        boolean enabled=TouhouAIFunConfig.AGENT_RUNTIME.get();TouhouAIFunConfig.AGENT_RUNTIME.set(true);
        for(var floor:BlockPos.betweenClosed(new BlockPos(0,0,0),new BlockPos(5,0,5))) helper.setBlock(floor,Blocks.STONE);
        var maid=helper.spawn(InitEntities.MAID.get(),new BlockPos(2,1,2)); maid.setNoAi(true); maid.setInvulnerable(true);
        BlockPos[] targets={new BlockPos(1,1,1),new BlockPos(4,1,1)};
        var state=new AgentTaskState("Receive two iron from each of two distinct chests."); state.status=AgentTaskState.Status.running;
        JsonArray conditions=new JsonArray();
        for(var target:targets) {
            helper.setBlock(target,Blocks.CHEST);
            ((ChestBlockEntity)helper.getBlockEntity(target)).setItem(0,new ItemStack(Items.IRON_INGOT,2));
            var world=helper.absolutePos(target); JsonArray position=new JsonArray(); position.add(world.getX());position.add(world.getY());position.add(world.getZ());
            JsonObject condition=new JsonObject();condition.addProperty("kind","transfer");condition.addProperty("item","minecraft:iron_ingot");condition.addProperty("count",2);
            condition.addProperty("dimension","minecraft:overworld");condition.addProperty("to_maid",true);condition.add("position",position);conditions.add(condition);
        }
        JsonObject contract=new JsonObject();contract.addProperty("kind","all");contract.add("conditions",conditions);state.completion=AgentTaskState.validateCompletion(contract);
        var callback=new TaskCallback(maid.getAiChatManager(),new ArrayList<>(),state);
        try {
            for(int i=0;i<targets.length;i++) {
                var world=helper.absolutePos(targets[i]); JsonObject open=new JsonObject();open.addProperty("target","minecraft:chest");
                // This fixture checks source accounting, not navigation; never join a walking future on the server thread.
                maid.setPos(world.getX()+1.5,world.getY(),world.getZ()+.5);
                open.addProperty("x",world.getX());open.addProperty("y",world.getY());open.addProperty("z",world.getZ());open.addProperty("wait_policy","AUTO");
                var opened=MaidGuiSessionManager.call(callback,"open_gui","multi_open_"+i,open).join();helper.assertTrue(!opened.has("error"),opened.toString());
                var session=MaidGuiSessionManager.session(maid.getUUID());int destination=-1;
                for(var slot:session.menu().slots) if(slot.container==session.actor.getInventory() && slot.mayPlace(new ItemStack(Items.IRON_INGOT))) { destination=slot.index;break; }
                helper.assertTrue(destination>=0,"Observed destination slot exists");
                JsonObject action=new JsonObject();action.addProperty("session_id",session.id.toString());action.addProperty("action","transfer");
                action.addProperty("slot",0);action.addProperty("to_slot",destination);action.addProperty("count",2);
                var result=MaidGuiSessionManager.call(callback,"gui_action","multi_receive_"+i,action).join();helper.assertTrue(result.get("moved_count").getAsInt()==2,result.toString());
                MaidGuiSessionManager.cancel(maid.getUUID(),"between_chests");
                helper.assertTrue(AgentCompletion.verify(maid,state,true)==(i==targets.length-1),"First chest does not satisfy the second chest condition");
            }
            var saved=AgentArchiveData.get(helper.getLevel()).save(new CompoundTag());
            var restored=AgentArchiveData.load(saved).task(maid.getUUID(),state.id);
            var oldCheckpoint=new AgentTaskState(state.goal);oldCheckpoint.id=state.id;oldCheckpoint.completion=state.completion.deepCopy();
            restored.reconcile(oldCheckpoint);
            helper.assertTrue(oldCheckpoint.transfers.size()==2 && AgentCompletion.verify(maid,oldCheckpoint,true),"Archive reload restores each source once");
            restored.reconcile(oldCheckpoint);helper.assertTrue(oldCheckpoint.transfers.values().stream().mapToLong(Long::longValue).sum()==4,"Repeated reconciliation does not duplicate transfers");
            var method=AgentRuntime.class.getDeclaredMethod("entry",EntityMaid.class);method.setAccessible(true);var entry=method.invoke(null,maid);
            var queueField=entry.getClass().getDeclaredField("queue");queueField.setAccessible(true);var queue=(AgentTaskQueue)queueField.get(entry);queue.tasks.add(state);
            var activeField=entry.getClass().getDeclaredField("callback");activeField.setAccessible(true);activeField.set(entry,callback);
            var observed=TaskCallback.class.getDeclaredField("freshWorld");observed.setAccessible(true);observed.setBoolean(callback,true);
            callback.onFailure(null,new IllegalStateException("final response failed after actual transfers"),1);
            helper.assertTrue(state.status==AgentTaskState.Status.completed,"Final model reply failure cannot turn verified, settled transfers into failure");
            var unmet=new AgentTaskState("unmet transfer");unmet.status=AgentTaskState.Status.running;unmet.completion=state.completion.deepCopy();unmet.transfers.putAll(state.transfers);
            unmet.completion.getAsJsonArray("conditions").get(1).getAsJsonObject().addProperty("count",3);queue.tasks.add(unmet);
            var incomplete=new TaskCallback(maid.getAiChatManager(),new ArrayList<>(),unmet);observed.setBoolean(incomplete,true);activeField.set(entry,incomplete);
            incomplete.onFailure(null,new IllegalStateException("request failed before required count"),1);
            helper.assertTrue(unmet.status==AgentTaskState.Status.paused,"Missing physical evidence still pauses after request failure");
            helper.succeed();
        } finally { MaidGuiSessionManager.cancel(maid.getUUID(),"qa_complete");state.status=AgentTaskState.Status.cancelled;callback.archiveState();AgentRuntime.stop(maid,true);TouhouAIFunConfig.AGENT_RUNTIME.set(enabled); }
    }
    @GameTest(template="empty",templateNamespace="aifun_gui_test")
    public static void auxiliaryOperationsIgnoreChatTakeoverButCancelOnUnload(GameTestHelper helper) {
        var maid=helper.spawn(InitEntities.MAID.get(),new BlockPos(2,1,2));maid.setNoAi(true);
        var first=new LLMCallback(maid.getAiChatManager(),new ArrayList<>(),true);
        var ordinary=new java.util.concurrent.CompletableFuture<Void>();ChatFlowManager.setInFlight(maid.getUUID(),first,ordinary);
        var auxiliary=new LLMCallback(maid.getAiChatManager(),new ArrayList<>(),true) {};
        var auxiliaryFuture=new java.util.concurrent.CompletableFuture<Void>();ChatFlowManager.setInFlight(maid.getUUID(),auxiliary,auxiliaryFuture);
        var state=new AgentTaskState("summary parent");state.status=AgentTaskState.Status.running;
        var task=new TaskCallback(maid.getAiChatManager(),new ArrayList<>(),state);
        final LLMCallback[] child={null};var network=new java.util.concurrent.CompletableFuture<Void>();
        var summary=KnowledgeSummaryCache.compress("fixture", "evidence",task,callback->{child[0]=callback;ChatFlowManager.setInFlight(maid.getUUID(),callback,network);},256);
        var newer=new LLMCallback(maid.getAiChatManager(),new ArrayList<>(),true);
        helper.assertTrue(ordinary.isCancelled() && !auxiliaryFuture.isDone() && !network.isDone(),"Chat takeover only cancels foreground operations");
        helper.assertTrue(!ChatFlowManager.isSuperseded(maid.getUUID(),auxiliary) && !ChatFlowManager.isSuperseded(maid.getUUID(),child[0]),"Settings and task summary are not late chat replies");
        task.operations.cancel();helper.assertTrue(network.isCancelled() && summary.isCompletedExceptionally(),"Cancelling task reaches its original summary HTTP future");
        ChatFlowManager.forgetMaid(maid.getUUID());helper.assertTrue(auxiliaryFuture.isCancelled(),"Unload also cancels independent auxiliary operations");
        state.status=AgentTaskState.Status.cancelled;task.archiveState();helper.succeed();
    }
    @GameTest(template="empty",templateNamespace="aifun_gui_test")
    public static void modelCompactionOnlySummarizesClosedEvidenceAndKeepsLatestPairs(GameTestHelper helper) {
        int budget=TouhouAIFunConfig.CONTEXT_INPUT_BUDGET_TOKENS.get(); TouhouAIFunConfig.CONTEXT_INPUT_BUDGET_TOKENS.set(16384);
        var maid=helper.spawn(InitEntities.MAID.get(),new BlockPos(2,1,2));maid.setNoAi(true);
        var state=new AgentTaskState("keep gold untouched");state.status=AgentTaskState.Status.running;
        var source=new ArrayList<LLMMessage>();source.add(LLMMessage.userChat(maid,"Keep gold untouched; deliver only 3 iron."));
        var latest=new com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.response.ToolCall("latest",new com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.response.FunctionToolCall("gui_action","{}"));
        source.add(new LLMMessage(Role.SYSTEM,"## Archived tool evidence\n"+"x".repeat(48000),0,null,null));
        source.add(new LLMMessage(Role.ASSISTANT,"",0,List.of(latest),null));source.add(new LLMMessage(Role.TOOL,"Latest authoritative transfer: 3 iron",0,null,"latest"));
        var task=new TaskCallback(maid.getAiChatManager(),source,state); int[] calls={0};
        try {
            boolean ready=AgentContext.prepareTask(task,auxiliary->{
                calls[0]++;helper.assertTrue(AgentExecution.context(auxiliary).purpose()==AgentExecution.Purpose.KNOWLEDGE_EXTRACTION && !auxiliary.needAddTools,"Compaction side request is explicitly typed and has no action tools");
                auxiliary.onSuccess(new ResponseChat("Old inventory inspected. Historical evidence is not current slot authorization."));
            }).join();
            helper.assertTrue(ready && calls[0]==1,"Fallback model runs once only after deterministic reduction cannot fit");
            helper.assertTrue(task.getMessages().stream().anyMatch(m->m.role()==Role.USER && m.message().contains("Keep gold untouched")),"Latest user constraints remain verbatim");
            helper.assertTrue(task.getMessages().stream().anyMatch(m->m.role()==Role.TOOL && "latest".equals(m.toolCallId())),"Latest tool batch stays paired");
            helper.assertTrue(task.getMessages().stream().anyMatch(m->m.message()!=null && m.message().contains("Model summary of completed data")),"Only archived closed evidence is replaced by model summary");
            helper.succeed();
        } finally { TouhouAIFunConfig.CONTEXT_INPUT_BUDGET_TOKENS.set(budget);state.status=AgentTaskState.Status.cancelled;task.archiveState(); }
    }
    @GameTest(template="empty",templateNamespace="aifun_gui_test")
    public static void foregroundQueueSwitchStopAndOwnerPermissions(GameTestHelper helper) throws Exception {
        boolean previous=TouhouAIFunConfig.AGENT_RUNTIME.get(); TouhouAIFunConfig.AGENT_RUNTIME.set(true);
        EntityMaid maid=helper.spawn(InitEntities.MAID.get(),new BlockPos(2,1,2)); maid.setNoAi(true);
        try {
            UUID owner=UUID.randomUUID(); maid.setOwnerUUID(owner);
            AgentRuntime.speaker(maid,new ChatSpeakerContext.Snapshot(owner,"owner",owner,"owner","owner",true,false),"work");
            var foreground=new LLMCallback(maid.getAiChatManager(),new ArrayList<>(),true);
            var enqueued=AgentRuntime.control(foreground,command("enqueue","one"));
            helper.assertTrue(!enqueued.has("error"),"First action enqueued: "+enqueued);
            var entryMethod=AgentRuntime.class.getDeclaredMethod("entry",EntityMaid.class); entryMethod.setAccessible(true); Object entry=entryMethod.invoke(null,maid);
            var queueField=entry.getClass().getDeclaredField("queue"); queueField.setAccessible(true); var queue=(AgentTaskQueue)queueField.get(entry);
            var first=queue.next(); var task=new TaskCallback(maid.getAiChatManager(),new ArrayList<>(),first);
            var field=entry.getClass().getDeclaredField("callback"); field.setAccessible(true); field.set(entry,task);
            for(int i=0;i<4;i++) helper.assertTrue(!AgentRuntime.control(ownerTurn(maid,owner,"queued"+i),command("enqueue","queued"+i)).has("error"),"Four distinct owner turns enqueue four actions");
            helper.assertTrue(AgentRuntime.control(ownerTurn(maid,owner,"overflow"),command("enqueue","overflow")).has("error"),"Queue overflow reported");
            helper.assertTrue(AgentRuntime.control(foreground,command("status",null)).getAsJsonArray("tasks").size()==5 && !task.stopped(),"Status query leaves task alive");
            AgentRuntime.speaker(maid,new ChatSpeakerContext.Snapshot(owner,"owner",owner,"owner","owner",true,false),"暂停当前任务。");
            helper.assertTrue(first.status==AgentTaskState.Status.paused && queue.paused && task.stopped(),"Local pause releases operations and preserves the current task");
            AgentRuntime.control(ownerTurn(maid,owner,"continue"),command("resume",null));
            helper.assertTrue(queue.next()==first,"Resume selects the same paused task before the queued work");
            task=new TaskCallback(maid.getAiChatManager(),new ArrayList<>(),first);field.set(entry,task);
            AgentRuntime.speaker(maid,new ChatSpeakerContext.Snapshot(UUID.randomUUID(),"companion",owner,"owner","owner",false,true),"stop");
            var companion=new LLMCallback(maid.getAiChatManager(),new ArrayList<>(),true);
            helper.assertTrue(AgentRuntime.control(companion,command("stop",null)).has("error") && !task.stopped(),"Ordinary public chat cannot cancel owner's task");
            var settlement=new java.util.concurrent.CompletableFuture<Void>();
            var settlementField=TaskCallback.class.getDeclaredField("settlement"); settlementField.setAccessible(true); settlementField.set(task,settlement);
            var switched=AgentRuntime.control(ownerTurn(maid,owner,"switch to replacement"),command("replace","replacement"));
            helper.assertTrue(switched.get("waiting_for_started_operations").getAsBoolean() && task.stopped(),"Switch waits for accepted operation settlement");
            helper.assertTrue(first.status==AgentTaskState.Status.cancelled && queue.tasks.get(0).goal.equals("replacement"),"Explicit switch retains queued tasks and cancels old task");
            settlement.complete(null);
            AgentRuntime.control(ownerTurn(maid,owner,"stop"),command("stop",null));
            helper.assertTrue(queue.paused && queue.next()==null,"Stop never auto-starts queued actions");
            AgentRuntime.control(ownerTurn(maid,owner,"clear"),command("clear",null)); helper.assertTrue(queue.tasks.stream().allMatch(AgentTaskState::terminal),"Explicit clear cancels all pending actions");
            helper.succeed();
        } finally { AgentRuntime.stop(maid,true); TouhouAIFunConfig.AGENT_RUNTIME.set(previous); }
    }
    private static JsonObject command(String action,String goal) { JsonObject out=new JsonObject(); out.addProperty("action",action); if(goal!=null) out.addProperty("goal",goal); return out; }
    @GameTest(template="empty",templateNamespace="aifun_gui_test")
    public static void completionDeclarationFinishesAfterPairedBatchWithoutAnotherModelRequest(GameTestHelper helper) throws Exception {
        boolean previous=TouhouAIFunConfig.AGENT_RUNTIME.get();TouhouAIFunConfig.AGENT_RUNTIME.set(true);
        var owner=helper.makeMockPlayer();var maid=new EntityMaid(InitEntities.MAID.get(),helper.getLevel()) {
            @Override public net.minecraft.world.entity.LivingEntity getOwner(){return owner;}
        };
        var pos=helper.absolutePos(new BlockPos(2,1,2));maid.setPos(pos.getX()+.5,pos.getY(),pos.getZ()+.5);maid.setOwnerUUID(owner.getUUID());maid.setNoAi(true);helper.getLevel().addFreshEntity(maid);
        try {
            var method=AgentRuntime.class.getDeclaredMethod("entry",EntityMaid.class);method.setAccessible(true);Object entry=method.invoke(null,maid);
            var queueField=entry.getClass().getDeclaredField("queue");queueField.setAccessible(true);var queue=(AgentTaskQueue)queueField.get(entry);
            var state=queue.enqueue("Receive two iron and close menu");queue.next();state.completion=JsonParser.parseString("{\"kind\":\"inventory\",\"item\":\"minecraft:iron_ingot\",\"count\":2,\"dimension\":\"minecraft:overworld\"}").getAsJsonObject();
            maid.getAvailableBackpackInv().setStackInSlot(0,new ItemStack(Items.IRON_INGOT,2));
            var callback=new TaskCallback(maid.getAiChatManager(),new ArrayList<>(),state);var fresh=TaskCallback.class.getDeclaredField("freshWorld");fresh.setAccessible(true);fresh.setBoolean(callback,true);
            var active=entry.getClass().getDeclaredField("callback");active.setAccessible(true);active.set(entry,callback);
            JsonObject plan=new JsonObject();plan.add("steps",JsonParser.parseString("[\"Receive two iron\",\"Close menu\"]"));plan.addProperty("current_step",1);plan.addProperty("complete",true);
            JsonArray calls=new JsonArray();JsonObject call=new JsonObject();call.addProperty("id","complete_now");JsonObject function=new JsonObject();function.addProperty("name","update_task_plan");function.addProperty("arguments",plan.toString());call.add("function",function);calls.add(call);
            JsonObject late=call.deepCopy();late.addProperty("id","late_action");late.getAsJsonObject("function").addProperty("name","gui_action");late.getAsJsonObject("function").addProperty("arguments","{}");calls.add(late);
            JsonObject response=new JsonObject();response.add("tool_calls",calls);int[] requests={0};callback.onFunctionCall(new Gson().fromJson(response,Message.class),ignored->requests[0]++);
            helper.assertTrue(state.status==AgentTaskState.Status.completed && requests[0]==0,"Settled declaration completes without a final model report round");
            helper.assertTrue(callback.getMessages().stream().filter(m->m.role()==Role.TOOL).count()==2 && callback.getMessages().stream().anyMatch(m->"late_action".equals(m.toolCallId()) && m.message().contains("cancelled_before_start")),"Later accepted calls are cancelled with paired results instead of mutating after completion");helper.succeed();
        } finally {AgentRuntime.stop(maid,true);maid.discard();TouhouAIFunConfig.AGENT_RUNTIME.set(previous);}
    }
    @GameTest(template="empty",templateNamespace="aifun_gui_test")
    public static void completionDeclarationCannotOverrideMissingQuantityOrOpenMenu(GameTestHelper helper) throws Exception {
        boolean previous=TouhouAIFunConfig.AGENT_RUNTIME.get();TouhouAIFunConfig.AGENT_RUNTIME.set(true);
        var maid=helper.spawn(InitEntities.MAID.get(),new BlockPos(2,1,2));maid.setNoAi(true);
        var state=new AgentTaskState("Receive two iron and close menu");state.status=AgentTaskState.Status.running;
        state.completion=JsonParser.parseString("{\"kind\":\"inventory\",\"item\":\"minecraft:iron_ingot\",\"count\":2,\"dimension\":\"minecraft:overworld\"}").getAsJsonObject();
        var callback=new TaskCallback(maid.getAiChatManager(),new ArrayList<>(),state);
        try {
            var fresh=TaskCallback.class.getDeclaredField("freshWorld");fresh.setAccessible(true);fresh.setBoolean(callback,true);
            maid.getAvailableBackpackInv().setStackInSlot(0,new ItemStack(Items.IRON_INGOT,1));
            try {callback.requestCompletion();helper.fail("Missing quantity cannot complete");}catch(IllegalArgumentException expected){helper.assertTrue(expected.getMessage().contains("evidence_unmet"),"Physical quantity is checked");}
            maid.getAvailableBackpackInv().setStackInSlot(0,new ItemStack(Items.IRON_INGOT,2));helper.setBlock(new BlockPos(1,1,3),Blocks.CHEST);var pos=helper.absolutePos(new BlockPos(1,1,3));
            var args=new JsonObject();args.addProperty("x",pos.getX());args.addProperty("y",pos.getY());args.addProperty("z",pos.getZ());args.addProperty("target","minecraft:chest");args.addProperty("wait_policy","NO_WAIT");
            helper.assertTrue(!MaidGuiSessionManager.call(callback,"open_gui","open",args).join().has("error"),"Actual menu opened");
            try {callback.requestCompletion();helper.fail("Open menu cannot complete");}catch(IllegalArgumentException expected){helper.assertTrue(expected.getMessage().contains("close_gui"),"Completion requires cleanup");}
            helper.assertTrue(state.status==AgentTaskState.Status.running,"Rejected declaration never marks completed");helper.succeed();
        } finally {MaidGuiSessionManager.cancel(maid.getUUID(),"qa_complete");state.status=AgentTaskState.Status.cancelled;callback.finishTiming(false);maid.discard();TouhouAIFunConfig.AGENT_RUNTIME.set(previous);}
    }
    @GameTest(template="empty",templateNamespace="aifun_gui_test")
    public static void liveCompletionEvidenceDoesNotInferUnknownOrDoubleContainersEmpty(GameTestHelper helper) {
        var maid=helper.spawn(InitEntities.MAID.get(),new BlockPos(2,1,2));maid.setNoAi(true);
        var local=new BlockPos(1,1,4);helper.setBlock(local,Blocks.CHEST);var pos=helper.absolutePos(local);
        var state=new AgentTaskState("retrieve two iron and close the menu");
        state.completion=JsonParser.parseString("{\"kind\":\"transfer\",\"item\":\"minecraft:iron_ingot\",\"count\":2,\"to_maid\":true,\"dimension\":\"minecraft:overworld\",\"position\":["+pos.getX()+","+pos.getY()+","+pos.getZ()+"]}").getAsJsonObject();
        state.transfers.put("minecraft:overworld:"+state.completion.get("position")+":minecraft:iron_ingot",2L);
        maid.getAvailableBackpackInv().setStackInSlot(0,new ItemStack(Items.IRON_INGOT,2));
        var evidence=AgentCompletion.snapshot(maid,state,true);
        helper.assertTrue(evidence.get("conditions_verified").getAsBoolean() && evidence.getAsJsonObject("maid_item_counts").get("minecraft:iron_ingot").getAsLong()==2,"Completion evidence contains actual inventory and transfer facts");
        helper.assertTrue(evidence.getAsJsonArray("source_checks").get(0).getAsJsonObject().get("empty").getAsBoolean(),"Single chest inspected directly on the server");
        helper.assertTrue(!AgentCompletion.snapshot(maid,state,false).get("conditions_verified").getAsBoolean(),"Live projection never bypasses fresh observation requirement");
        helper.setBlock(local,Blocks.CHEST.defaultBlockState().setValue(net.minecraft.world.level.block.ChestBlock.TYPE,net.minecraft.world.level.block.state.properties.ChestType.LEFT));
        var doubled=AgentCompletion.snapshot(maid,state,true).getAsJsonArray("source_checks").get(0).getAsJsonObject();
        helper.assertTrue(!doubled.has("empty") && doubled.get("coverage").getAsString().contains("requires_observation"),"A single half cannot verify the full double chest is empty");
        helper.setBlock(local,Blocks.STONE);
        var unknown=AgentCompletion.snapshot(maid,state,true).getAsJsonArray("source_checks").get(0).getAsJsonObject();
        helper.assertTrue(!unknown.has("empty") && unknown.get("coverage").getAsString().equals("unknown"),"Unknown menus never imply an empty container");helper.succeed();
    }
    @GameTest(template="empty",templateNamespace="aifun_gui_test")
    public static void replacePausedCurrentPreservesQueueUnlessExplicitlyCleared(GameTestHelper helper) throws Exception {
        boolean previous=TouhouAIFunConfig.AGENT_RUNTIME.get();TouhouAIFunConfig.AGENT_RUNTIME.set(true);
        var maid=helper.spawn(InitEntities.MAID.get(),new BlockPos(2,1,2));maid.setNoAi(true);
        UUID owner=UUID.randomUUID();maid.setOwnerUUID(owner);
        try {
            AgentRuntime.control(ownerTurn(maid,owner,"old"),command("enqueue","old"));
            var method=AgentRuntime.class.getDeclaredMethod("entry",EntityMaid.class);method.setAccessible(true);Object entry=method.invoke(null,maid);
            var queueField=entry.getClass().getDeclaredField("queue");queueField.setAccessible(true);var queue=(AgentTaskQueue)queueField.get(entry);
            var old=queue.next();queue.suspend("reload");for(int i=0;i<4;i++) queue.enqueue("queued"+i);
            var bad=command("replace","replacement");bad.addProperty("clear_queued","true");
            helper.assertTrue(AgentRuntime.control(ownerTurn(maid,owner,"invalid"),bad).has("error") && old.status==AgentTaskState.Status.paused,"Invalid switch does not cancel anything");
            var receipt=AgentRuntime.control(ownerTurn(maid,owner,"switch"),command("replace","replacement"));
            helper.assertTrue(!receipt.has("error") && old.status==AgentTaskState.Status.cancelled,"Full paused queue allows replacement of its current task");
            helper.assertTrue(queue.tasks.stream().filter(t->!t.terminal()).count()==5,"Default switch preserves all four queued tasks");
            helper.assertTrue(queue.next().goal.equals("replacement"),"Replacement starts first; paused old task cannot return");helper.succeed();
        } finally {AgentRuntime.stop(maid,true);TouhouAIFunConfig.AGENT_RUNTIME.set(previous);}
    }
    @GameTest(template="empty",templateNamespace="aifun_gui_test")
    public static void legacyPausedSortingBlocksNewRetrievalWithoutModelOrGuiWork(GameTestHelper helper) throws Exception {
        boolean previous=TouhouAIFunConfig.AGENT_RUNTIME.get();TouhouAIFunConfig.AGENT_RUNTIME.set(true);
        var maid=helper.spawn(InitEntities.MAID.get(),new BlockPos(2,1,2));maid.setNoAi(true);
        UUID owner=UUID.randomUUID();maid.setOwnerUUID(owner);
        try {
            AgentRuntime.control(ownerTurn(maid,owner,"old sorting"),command("enqueue","old sorting"));
            var method=AgentRuntime.class.getDeclaredMethod("entry",EntityMaid.class);method.setAccessible(true);Object entry=method.invoke(null,maid);
            var queueField=entry.getClass().getDeclaredField("queue");queueField.setAccessible(true);var queue=(AgentTaskQueue)queueField.get(entry);
            var old=queue.next();old.goalVersion=2;old.appliedMutationVersion=5;old.pause("missing completion");queue.paused=true;
            for(int i=0;i<3;i++) queue.enqueue("old duplicate "+i);
            var receipt=AgentRuntime.control(ownerTurn(maid,owner,"take everything out"),command("enqueue","retrieve four chests"));
            helper.assertTrue(receipt.get("controlled_task_queue_position").getAsInt()==5 && receipt.get("paused").getAsBoolean(),"New retrieval is fifth and has not started");
            var resumed=AgentRuntime.control(ownerTurn(maid,owner,"continue"),command("resume",null));
            helper.assertTrue(resumed.get("controlled_task_id").getAsString().equals(old.id) && resumed.get("completion_review_required").getAsBoolean(),"Resume identifies old sorting, never claims new retrieval is running");
            helper.assertTrue(queue.next()==old,"Old task resumes first");
            var callback=new TaskCallback(maid.getAiChatManager(),new ArrayList<>(),old);
            var callbackField=entry.getClass().getDeclaredField("callback");callbackField.setAccessible(true);callbackField.set(entry,callback);
            int[] requests={0};callback.next(ignored->requests[0]++);
            helper.assertTrue(requests[0]==0 && old.status==AgentTaskState.Status.paused && queue.paused,"Historical missing contract pauses before any model request or tool");
            helper.assertTrue(queue.tasks.get(4).generation==0,"New retrieval remains unexecuted");
            var amended=AgentRuntime.control(ownerTurn(maid,owner,"review old task"),command("amend","Verify the already sorted items from fresh observations"));
            helper.assertTrue(!amended.has("error") && !old.pendingAmendment.isBlank() && queue.paused,"Owner can amend paused current task without resuming implicitly");
            AgentRuntime.control(ownerTurn(maid,owner,"continue after review"),command("resume",null));queue.next();
            var reviewed=new TaskCallback(maid.getAiChatManager(),new ArrayList<>(),old);callbackField.set(entry,reviewed);
            reviewed.next(ignored->requests[0]++);
            helper.assertTrue(requests[0]==1 && old.goalVersion==3 && old.appliedMutationVersion==5 && old.status==AgentTaskState.Status.running,"Explicit owner review permits fresh planning and preserves historical mutations");
            var switched=command("replace","retrieve four chests");switched.addProperty("clear_queued",true);
            var replaced=AgentRuntime.control(ownerTurn(maid,owner,"clear old queue and switch to retrieval"),switched);
            helper.assertTrue(!replaced.has("error") && queue.tasks.stream().filter(t->!t.terminal()).count()==1 && !queue.paused,"Explicit clear and switch leaves exactly one new retrieval task");
            helper.assertTrue(queue.tasks.stream().filter(t->t!=queue.tasks.get(0)).allMatch(AgentTaskState::terminal),"All old entries including paused current were cancelled");
            helper.succeed();
        } finally {AgentRuntime.stop(maid,true);TouhouAIFunConfig.AGENT_RUNTIME.set(previous);}
    }
    private static LLMCallback ownerTurn(EntityMaid maid,UUID owner,String message) {
        AgentRuntime.speaker(maid,new ChatSpeakerContext.Snapshot(owner,"owner",owner,"owner","owner",true,false),message);
        return new LLMCallback(maid.getAiChatManager(),new ArrayList<>(),true);
    }
    @GameTest(template="empty",templateNamespace="aifun_gui_test")
    public static void rewordedForegroundRetriesCannotCreateOrResumeDuplicateWork(GameTestHelper helper) {
        boolean previous=TouhouAIFunConfig.AGENT_RUNTIME.get();TouhouAIFunConfig.AGENT_RUNTIME.set(true);
        var maid=helper.spawn(InitEntities.MAID.get(),new BlockPos(2,1,2));maid.setNoAi(true);UUID owner=UUID.randomUUID();maid.setOwnerUUID(owner);
        try {
            var caller=ownerTurn(maid,owner,"Sort my backpack into the four labelled chests");
            var first=AgentRuntime.control(caller,command("enqueue","sort backpack"));String id=first.get("controlled_task_id").getAsString();
            for(String action:List.of("enqueue","amend","replace","resume")) {
                var repeated=AgentRuntime.control(caller,command(action,"reworded sorting request"));
                helper.assertTrue(repeated.get("already_applied").getAsBoolean() && repeated.get("controlled_task_id").getAsString().equals(id) && repeated.getAsJsonArray("tasks").size()==1,"One instruction creates exactly one task despite reworded retries");
            }
            new com.wjx.touhou_aifun.compat.ai.openai.LoadToolSchemaTool().onCall("load","group:gui",caller);
            helper.assertTrue(caller.getMessages().get(caller.getMessages().size()-1).message().contains("foreground_action_boundary") && ChatFlowManager.requestedToolIds(maid.getUUID(),caller).isEmpty(),"Foreground cannot load GUI schemas or gain action authority");
            AgentRuntime.previewControl(maid,"pause");AgentRuntime.control(caller,command("resume",null));
            helper.assertTrue(AgentRuntime.previewControl(maid,"status").get("paused").getAsBoolean(),"Old foreground retry does not resume paused work");
            AgentRuntime.control(ownerTurn(maid,owner,"continue"),command("resume",null));
            helper.assertTrue(!AgentRuntime.previewControl(maid,"status").get("paused").getAsBoolean(),"New owner turn can explicitly resume");helper.succeed();
        } finally {AgentRuntime.stop(maid,true);TouhouAIFunConfig.AGENT_RUNTIME.set(previous);}
    }

    @GameTest(template="empty",templateNamespace="aifun_gui_test")
    public static void schemaDependenciesInvalidateDefinitionsWithoutForgettingLoadedTools(GameTestHelper helper) {
        var maid=helper.spawn(InitEntities.MAID.get(),new BlockPos(2,1,2)); maid.setNoAi(true);
        var state=new AgentTaskState("test schema"); state.status=AgentTaskState.Status.running;
        var task=new TaskCallback(maid.getAiChatManager(),new ArrayList<>(),state);
        var version=new java.util.concurrent.atomic.AtomicInteger(1);
        com.wjx.touhou_aifun.compat.ai.openai.ToolSchemaDependencies.register("open_gui",m->Integer.toString(version.get()));
        try {
            var first=com.wjx.touhou_aifun.compat.ai.openai.ToolContextSelector.snapshot(maid,task);
            ChatFlowManager.requestToolSchema(maid.getUUID(),task,"open_gui");
            helper.assertTrue(first==com.wjx.touhou_aifun.compat.ai.openai.ToolContextSelector.snapshot(maid,task),"Loading schemas does not rebuild definitions");
            version.incrementAndGet(); var second=com.wjx.touhou_aifun.compat.ai.openai.ToolContextSelector.snapshot(maid,task);
            helper.assertTrue(first!=second && state.loadedTools.containsAll(Arrays.asList(com.wjx.touhou_aifun.compat.ai.action.GuiTool.IDS)),"Declared dependency changes schema but preserves GUI group loading");
            helper.succeed();
        } finally {
            com.wjx.touhou_aifun.compat.ai.openai.ToolSchemaDependencies.unregister("open_gui");
            com.wjx.touhou_aifun.compat.ai.openai.ToolContextSelector.clearSnapshot(task); state.status=AgentTaskState.Status.cancelled; task.archiveState();
        }
    }
    @GameTest(template="empty",templateNamespace="aifun_gui_test")
    public static void resumedTaskCannotMutateUsingOnlyOldObservation(GameTestHelper helper) {
        var maid=helper.spawn(InitEntities.MAID.get(),new BlockPos(2,1,2)); maid.setNoAi(true);
        var state=new AgentTaskState("resume"); state.status=AgentTaskState.Status.running; state.generation=2;
        state.checkpoint("Old GUI had three iron; generation 1."); state.loadedTools.add("gui_action");
        var task=new TaskCallback(maid.getAiChatManager(),new ArrayList<>(),state);
        var message=new Gson().fromJson("{\"tool_calls\":[{\"id\":\"stale\",\"function\":{\"name\":\"gui_action\",\"arguments\":\"{}\"}}]}",Message.class);
        task.onFunctionCall(message,callback->{
            helper.assertTrue(callback.getMessages().stream().anyMatch(m->m.role()==Role.TOOL && m.message().contains("fresh_world_observation_required")),"Historical evidence cannot authorize a mutation after resume");
            helper.assertTrue(MaidGuiSessionManager.session(maid.getUUID())==null,"No world operation dispatched");
            state.status=AgentTaskState.Status.cancelled; task.archiveState(); helper.succeed();
        });
    }
    @GameTest(template="empty",templateNamespace="aifun_gui_test",timeoutTicks=300)
    public static void fiftyWorldTransfersKeepConstraintsAndForegroundChatSeparate(GameTestHelper helper) throws Exception {
        boolean enabled=TouhouAIFunConfig.AGENT_RUNTIME.get(); TouhouAIFunConfig.AGENT_RUNTIME.set(true);
        for(var floor:BlockPos.betweenClosed(new BlockPos(0,0,0),new BlockPos(5,0,5))) helper.setBlock(floor,Blocks.STONE);
        var owner=helper.makeMockPlayer();
        // A living owner is sufficient for this server-only fixture; Forge's mock ServerPlayer has no Netty channel.
        var maid=new EntityMaid(InitEntities.MAID.get(),helper.getLevel()) {
            @Override public net.minecraft.world.entity.LivingEntity getOwner() { return owner; }
        };
        var maidPos=helper.absolutePos(new BlockPos(2,1,2)); maid.setPos(maidPos.getX()+.5,maidPos.getY(),maidPos.getZ()+.5);
        maid.setNoAi(true); helper.getLevel().addFreshEntity(maid); maid.setOwnerUUID(owner.getUUID());
        helper.setBlock(new BlockPos(1,1,1),Blocks.CHEST);
        var chest=(ChestBlockEntity)helper.getBlockEntity(new BlockPos(1,1,1)); chest.setItem(0,new ItemStack(Items.IRON_INGOT,64)); chest.setItem(1,new ItemStack(Items.GOLD_INGOT,10));
        var pos=helper.absolutePos(new BlockPos(1,1,1));
        var state=new AgentTaskState("Receive exactly 50 iron from this chest; never touch gold."); state.status=AgentTaskState.Status.running; state.generation=1;
        state.completion=JsonParser.parseString("{\"item\":\"minecraft:iron_ingot\",\"count\":50,\"dimension\":\"minecraft:overworld\",\"position\":["+pos.getX()+","+pos.getY()+","+pos.getZ()+"],\"to_maid\":true}").getAsJsonObject();
        var messages=new ArrayList<LLMMessage>(); messages.add(LLMMessage.userChat(maid,state.goal));
        var callback=new TaskCallback(maid.getAiChatManager(),messages,state);
        // The fixture injects a scripted model into one entry, without a test switch in production.
        var getEntry=AgentRuntime.class.getDeclaredMethod("entry",EntityMaid.class); getEntry.setAccessible(true); Object entry=getEntry.invoke(null,maid);
        var queueField=entry.getClass().getDeclaredField("queue"); queueField.setAccessible(true); ((AgentTaskQueue)queueField.get(entry)).tasks.add(state);
        var callbackField=entry.getClass().getDeclaredField("callback"); callbackField.setAccessible(true); callbackField.set(entry,callback);
        helper.assertTrue(AgentRuntime.enabled(maid),"AIFun runtime enabled in isolated fixture");
        LLMClient scripted=new LLMClient() {
            int step;
            boolean compacted;
            boolean completionProbed;
            @Override public void chat(LLMCallback current) {
                helper.runAfterDelay(1,()-> {
                    try {
                        if(current instanceof TaskCallback execution && execution.stopped()) throw new AssertionError("Unexpected task cancellation: "+state.outcome);
                        var compact=AgentContext.compact(current.getMessages(),6000,callback.results);
                        compacted |= compact.size()<current.getMessages().size(); current.getMessages().clear(); current.getMessages().addAll(compact);
                        helper.assertTrue(current.getMessages().stream().anyMatch(m->m.role()==Role.USER && m.message().contains("never touch gold")),"Pinned user constraint survives compression");
                        if(step==25) {
                            AgentRuntime.speaker(maid,new ChatSpeakerContext.Snapshot(owner.getUUID(),"owner",owner.getUUID(),"owner","owner",true,false),"How are you?");
                            var foreground=new LLMCallback(maid.getAiChatManager(),new ArrayList<>(),true);
                            ChatFlowManager.beginTurn(maid,25);
                            helper.assertTrue(!callback.stopped() && MaidGuiSessionManager.session(maid.getUUID())!=null,"Foreground chat leaves task and GUI intact");
                            var status=AgentRuntime.control(foreground,JsonParser.parseString("{\"action\":\"status\"}").getAsJsonObject());
                            helper.assertTrue(status.getAsJsonArray("tasks").size()==1,"Foreground status sees current task");
                            JsonObject amend=new JsonObject(); amend.addProperty("action","amend"); amend.addProperty("goal","Keep every gold ingot untouched."); amend.add("completion",state.completion.deepCopy());
                            helper.assertTrue(!AgentRuntime.control(foreground,amend).has("error"),"Owner can amend without cancelling task");
                        }
                        String tool; JsonObject args=new JsonObject();
                        if(step==3 && !completionProbed) {
                            completionProbed=true;callback.onSuccess(new ResponseChat("Prematurely claimed completion."));
                            helper.assertTrue(state.status==AgentTaskState.Status.running && state.verifiedCount==0,"Machine verification rejects premature success and permits one correction without replaying work");return;
                        }
                        if(step==0) { tool="scan_surroundings"; args.addProperty("intent","locate"); args.addProperty("max_distance",4); args.addProperty("mode","blocks"); }
                        else if(step==1) { tool="load_tool_schema"; args.addProperty("tool_name","group:gui"); }
                        else if(step==2) { tool="open_gui"; args.addProperty("x",pos.getX()); args.addProperty("y",pos.getY()); args.addProperty("z",pos.getZ()); args.addProperty("wait_policy","AUTO"); }
                        else if(state.verifiedCount<50) {
                            tool="gui_action"; var session=MaidGuiSessionManager.session(maid.getUUID());
                            helper.assertTrue(session!=null,"GUI session retained"); int backpack=-1;
                            for(var slot:session.menu().slots) if(slot.container==session.actor.getInventory() && slot.mayPlace(new ItemStack(Items.IRON_INGOT))) { backpack=slot.index; break; }
                            helper.assertTrue(backpack>=0,"Observed backpack slot exists");
                            args.addProperty("session_id",session.id.toString()); args.addProperty("action","transfer"); args.addProperty("slot",0); args.addProperty("to_slot",backpack); args.addProperty("count",1);
                        } else {
                            callback.onSuccess(new ResponseChat("Delivered 50 iron."));
                            helper.assertTrue(state.status==AgentTaskState.Status.completed,"Completion verified from real transfers");
                            helper.assertTrue(chest.getItem(0).getCount()==14 && chest.getItem(1).getCount()==10,"No duplicated transfer and gold untouched");
                            helper.assertTrue(compacted && completionProbed && state.goalVersion==2 && !state.constraints.isEmpty(),"Compression, verified correction and boundary amendment exercised");
                            var saved=AgentArchiveData.get(helper.getLevel()).save(new CompoundTag());
                            var restored=AgentArchiveData.load(saved).task(maid.getUUID(),state.id);
                            helper.assertTrue(restored.terminal && restored.records.stream().filter(r->r.kind().equals("world_mutation")).count()==50,"Durable full trace contains all 50 actual mutations");
                            var raw=new TaskResultStore(restored.results); var last=restored.records.stream().filter(r->r.kind().equals("tool_result")).reduce((a,b)->b).orElseThrow();
                            helper.assertTrue(raw.read(last.resultRef(),0).contains("moved_count"),"Original result reference readable after NBT round trip");
                            TouhouAIFunConfig.AGENT_RUNTIME.set(enabled); helper.succeed(); return;
                        }
                        JsonObject function=new JsonObject(); function.addProperty("name",tool); function.addProperty("arguments",args.toString());
                        JsonObject call=new JsonObject(); call.addProperty("id","scripted_"+(step++)); call.add("function",function); call.addProperty("type","function");
                        JsonArray calls=new JsonArray(); calls.add(call); JsonObject model=new JsonObject(); model.addProperty("role","assistant"); model.add("tool_calls",calls);
                        current.onFunctionCall(new Gson().fromJson(model,Message.class),this);
                    } catch(Throwable failure) { TouhouAIFunConfig.AGENT_RUNTIME.set(enabled); MaidGuiSessionManager.cancel(maid.getUUID(),"qa_failed"); helper.fail(failure.toString()); }
                });
            }
        };
        callback.next(scripted);
    }

    @GameTest(template="empty",templateNamespace="aifun_gui_test")
    public static void liveCompletionRequiresCurrentDimensionPositionAndInventory(GameTestHelper helper) {
        var maid=helper.spawn(InitEntities.MAID.get(),new BlockPos(2,1,2)); maid.setNoAi(true);
        var task=new AgentTaskState("carry 5 iron");
        task.completion=AgentTaskState.validateCompletion(JsonParser.parseString("{\"kind\":\"inventory\",\"item\":\"minecraft:iron_ingot\",\"count\":5,\"dimension\":\"minecraft:overworld\"}").getAsJsonObject());
        maid.getMaidInv().setStackInSlot(0,new ItemStack(Items.IRON_INGOT,5));
        helper.assertTrue(!AgentCompletion.verify(maid,task,false),"Saved facts do not authorize completion before fresh observation");
        helper.assertTrue(AgentCompletion.verify(maid,task,true),"Current inventory verifies count");
        maid.getMaidInv().setStackInSlot(0,new ItemStack(Items.IRON_INGOT,4));
        helper.assertTrue(!AgentCompletion.verify(maid,task,true),"Inventory loss invalidates completion");
        var pos=maid.blockPosition(); task.completion=AgentTaskState.validateCompletion(JsonParser.parseString("{\"kind\":\"position\",\"dimension\":\"minecraft:overworld\",\"position\":["+pos.getX()+","+pos.getY()+","+pos.getZ()+"],\"radius\":2}").getAsJsonObject());
        helper.assertTrue(AgentCompletion.verify(maid,task,true),"Live arrival evidence"); maid.setPos(maid.getX()+8,maid.getY(),maid.getZ());
        helper.assertTrue(!AgentCompletion.verify(maid,task,true),"Old arrival position cannot verify completion"); helper.succeed();
    }
}
