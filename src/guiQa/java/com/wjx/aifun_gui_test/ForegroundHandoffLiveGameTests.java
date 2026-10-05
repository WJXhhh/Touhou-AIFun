package com.wjx.aifun_gui_test;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.response.ResponseChat;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.*;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.init.InitEntities;
import com.google.gson.*;
import com.wjx.touhou_aifun.chat.*;
import com.wjx.touhou_aifun.chat.agent.*;
import com.wjx.touhou_aifun.config.TouhouAIFunConfig;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraftforge.gametest.*;
import java.nio.file.*;
import java.util.*;

/** Exercises the real foreground model handoff, including initially unknown destinations. */
@GameTestHolder("aifun_four_chest_test")
@PrefixGameTestTemplate(false)
public final class ForegroundHandoffLiveGameTests {
    @GameTest(batch="foregroundLive",template="four_chests",templateNamespace="aifun_gui_test",timeoutTicks=100000)
    public static void deepSeekForegroundSortsBackpackIntoFourLabelledChests(GameTestHelper helper) throws Exception {
        String source=System.getProperty("aifun.foregroundQa.sites");
        if(source==null) {helper.succeed();return;}
        boolean retrieve=Boolean.getBoolean("aifun.foregroundQa.retrieve");
        boolean amend=Boolean.getBoolean("aifun.foregroundQa.amend");
        helper.assertTrue(!retrieve || !amend,"Choose one live fixture mode");
        String prefix=retrieve?"foreground-retrieve":amend?"foreground-amend":"foreground-sort";
        var configuration=JsonParser.parseString(Files.readString(Path.of(source))).getAsJsonObject().getAsJsonObject("anthropic");
        helper.assertTrue(configuration!=null && configuration.get("enabled").getAsBoolean(),"Enabled DeepSeek site required");
        var serializer=com.github.tartaricacid.touhoulittlemaid.ai.service.SerializerRegister.getLLMSerializer(configuration.get("api_type").getAsString());
        var site=serializer.codec().parse(com.mojang.serialization.JsonOps.INSTANCE,configuration).result().orElseThrow();
        var modelEntry=configuration.getAsJsonArray("models").get(0);
        String model=modelEntry.isJsonObject()?modelEntry.getAsJsonObject().get("name").getAsString():modelEntry.getAsString();
        var sites=com.github.tartaricacid.touhoulittlemaid.ai.manager.site.AvailableSites.LLM_SITES;var oldSite=sites.put(site.id(),site);
        boolean enabled=TouhouAIFunConfig.AGENT_RUNTIME.get(),diagnostics=TouhouAIFunConfig.AGENT_DIAGNOSTICS.get();
        TouhouAIFunConfig.AGENT_RUNTIME.set(true);TouhouAIFunConfig.AGENT_DIAGNOSTICS.set(true);
        var reports=new JsonArray();var owner=helper.makeMockPlayer();var ownerPos=helper.absolutePos(new BlockPos(4,1,5));owner.setPos(ownerPos.getX()+.5,ownerPos.getY(),ownerPos.getZ()+.5);
        Item[] items={Items.COOKED_BEEF,Items.SPIDER_EYE,Items.GOLDEN_SWORD,Items.LEATHER_LEGGINGS,Items.ANCIENT_DEBRIS};
        int[] amounts={60,1,1,1,1},destinations={0,1,2,2,3};
        String[] labels={"Food: cooked_beef","Potion ingredients: spider_eye","Equipment: sword and leggings","Materials: ancient_debris"};
        if(amend) {amounts[0]=59;items[3]=Items.IRON_SHOVEL;items[4]=Items.RAW_IRON;labels[2]="Tools and weapons: iron_shovel, golden_sword";labels[3]="Materials: raw_iron";}
        class Runner {
            int repeat;boolean active=true,frontDone;String frontError;EntityMaid maid;LLMCallback front;AgentTaskQueue queue;long started;
            boolean amendmentSent,amendmentDone,statusSent,statusDone;LLMCallback supplement,statusRequest;String guardedStatus;
            void clean() {if(maid!=null) {AgentRuntime.stop(maid,true);ChatFlowManager.forgetMaid(maid.getUUID());maid.discard();maid=null;}}
            void restore() {active=false;clean();if(oldSite==null) sites.remove(site.id());else sites.put(site.id(),oldSite);TouhouAIFunConfig.AGENT_RUNTIME.set(enabled);TouhouAIFunConfig.AGENT_DIAGNOSTICS.set(diagnostics);}
            void begin() {
                try {
                    if(repeat==Integer.getInteger("aifun.foregroundQa.trials",3)) {restore();helper.assertTrue(reports.asList().stream().allMatch(r->r.getAsJsonObject().get("completed").getAsBoolean()),"Foreground sorting trial failed; see metadata report");helper.succeed();return;}
                    frontDone=false;frontError=null;amendmentSent=false;amendmentDone=false;statusSent=false;statusDone=false;supplement=null;statusRequest=null;guardedStatus=null;FourChestNavigationGameTests.prepareFloor(helper);
                    for(int i=0;i<4;i++) {
                        var local=new BlockPos(2,1,2+i*2);helper.setBlock(local,Blocks.CHEST);
                        ((ChestBlockEntity)helper.getBlockEntity(local)).clearContent();
                        helper.setBlock(local.east(),Blocks.OAK_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING,Direction.EAST));
                        var sign=(SignBlockEntity)helper.getBlockEntity(local.east());sign.setText(new SignText().setMessage(0,Component.literal(labels[i])),true);
                    }
                    // Fail before paying for model requests if old GameTest placement leaves
                    // another signed chest group inside the scan radius. This is a fixed fixture.
                    int labelledChests=0;var center=helper.absolutePos(new BlockPos(4,1,2));
                    for(var candidate:BlockPos.betweenClosed(center.offset(-16,-4,-16),center.offset(16,4,16)))
                        if(helper.getLevel().hasChunkAt(candidate) && helper.getLevel().getBlockEntity(candidate) instanceof ChestBlockEntity
                                && helper.getLevel().getBlockEntity(candidate.east()) instanceof SignBlockEntity) labelledChests++;
                    helper.assertTrue(labelledChests==4,"Fixed four-chest fixture is contaminated by neighbouring signed chests; use a fresh isolated QA directory");
                    maid=new EntityMaid(InitEntities.MAID.get(),helper.getLevel()) {@Override public net.minecraft.world.entity.LivingEntity getOwner(){return owner;}};
                    var start=helper.absolutePos(new BlockPos(4,1,2));maid.setPos(start.getX()+.7,start.getY(),start.getZ()+.5);maid.setHealth(maid.getMaxHealth());maid.setNoAi(false);maid.setInvulnerable(true);maid.setOwnerUUID(owner.getUUID());helper.getLevel().addFreshEntity(maid);
                    for(int i=0;i<items.length;i++) {
                        if(retrieve) {
                            var chest=(ChestBlockEntity)helper.getBlockEntity(new BlockPos(2,1,2+destinations[i]*2));
                            chest.setItem(i,new ItemStack(items[i],amounts[i]));
                        } else if(amend && i==0) maid.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND,new ItemStack(items[i],amounts[i]));
                        else maid.getAvailableBackpackInv().setStackInSlot(i,new ItemStack(items[i],amounts[i]));
                    }
                    // Fixed inventory fixture: the maid has just had a work meal. Ordinary AI,
                    // including actual navigation, remains enabled throughout the handoff.
                    maid.getFavorabilityManager().apply(com.github.tartaricacid.touhoulittlemaid.entity.favorability.Type.WORK_MEAL,0);
                    var manager=maid.getAiChatManager();manager.llmSite=site.id();manager.llmModel=model;manager.chatLanguage="en_us";manager.ttsSite="__none__";
                    String goal="There are four nearby chests with category signs. Read the signs and put everything from your backpack into the matching labelled chests. Empty the backpack, keep hands empty and close menus. Do not guess positions or claim completion before checking the actual transfers.";
                    if(retrieve) goal="Take everything out of the four nearby labelled chests and put it into your backpack. All four chests must be empty. Keep hands empty and close menus. Do not guess source positions or claim completion before checking actual transfers.";
                    if(amend) goal="Read the four nearby category signs and put ONLY the raw iron, iron shovel, golden sword and spider eye into their matching labelled chests. Do not keep a shovel in the final backpack. Leave the 59 cooked beef untouched until I ask separately. Close menus when finished.";
                    String setting=com.github.tartaricacid.touhoulittlemaid.ai.manager.setting.papi.PapiReplacer.replaceSetting("You are a helpful Minecraft maid. Follow the owner's requested action and report honestly.",maid,"en_us");
                    var messages=new ArrayList<LLMMessage>();messages.add(LLMMessage.systemChat(maid,setting));messages.add(LLMMessage.userChat(maid,goal));
                    AgentRuntime.speaker(maid,new ChatSpeakerContext.Snapshot(owner.getUUID(),"owner",owner.getUUID(),"owner","owner",true,false),goal);
                    EntityMaid currentMaid=maid;
                    front=new LLMCallback(manager,messages,true) {
                        @Override public void onSuccess(ResponseChat response){runOnServerThread(()->{if(front==this) frontDone=true;ChatFlowManager.finishRequest(currentMaid.getUUID(),this);});}
                        @Override public void onFailure(java.net.http.HttpRequest request,Throwable failure,int code){runOnServerThread(()->{if(front==this) {frontDone=true;frontError="foreground_request_failed:"+code;}ChatFlowManager.finishRequest(currentMaid.getUUID(),this);});}
                    };
                    AgentExecution.bind(front,0,null,0,AgentExecution.Purpose.CHAT);ChatFlowManager.registerRequest(maid.getUUID(),front);
                    var method=AgentRuntime.class.getDeclaredMethod("entry",EntityMaid.class);method.setAccessible(true);var entry=method.invoke(null,maid);var field=entry.getClass().getDeclaredField("queue");field.setAccessible(true);queue=(AgentTaskQueue)field.get(entry);
                    started=System.nanoTime();site.client().chat(front);helper.runAfterDelay(10,this::poll);
                } catch(Throwable failure){restore();helper.fail(failure.toString());}
            }
            void poll() {
                try {
                    AgentTaskState task=queue.tasks.isEmpty()?null:queue.tasks.get(0);
                    if(amend && frontDone && task!=null && !task.transfers.isEmpty() && !amendmentSent && !task.terminal()) {
                        amendmentSent=true;String request="Also put all 59 cooked beef into the food chest. Keep all the original sorting requirements, leave nothing in your backpack or hands and close the GUI. Do not repeat items already placed.";
                        supplement=ownerMessage(request,false);site.client().chat(supplement);
                    }
                    if(amend && amendmentDone && task!=null && task.transfers.values().stream().mapToLong(v->Math.abs(v)).sum()>=63 && !statusSent) {
                        statusSent=true;statusRequest=ownerMessage("Are all the items put away? What is the verified task status?",true);site.client().chat(statusRequest);
                    }
                    boolean done=frontDone && task!=null && (task.terminal() || task.status==AgentTaskState.Status.paused) && (!amend || statusDone || frontError!=null || !statusSent);
                    if(!done && frontError==null && queue.tasks.size()<=1 && System.nanoTime()-started<java.util.concurrent.TimeUnit.SECONDS.toNanos(120)) {helper.runAfterDelay(10,this::poll);return;}
                    boolean actual=true;JsonArray actualCounts=new JsonArray();
                    for(int i=0;i<items.length;i++) {
                        var chest=(ChestBlockEntity)helper.getBlockEntity(new BlockPos(2,1,2+destinations[i]*2));int count=0;
                        if(retrieve) {
                            for(int s=0;s<maid.getAvailableBackpackInv().getSlots();s++) {
                                var stack=maid.getAvailableBackpackInv().getStackInSlot(s);if(stack.is(items[i])) count+=stack.getCount();
                            }
                            actual &= chest.isEmpty();
                        } else for(int s=0;s<chest.getContainerSize();s++) if(chest.getItem(s).is(items[i])) count+=chest.getItem(s).getCount();
                        actualCounts.add(count);actual &= count==amounts[i];
                    }
                    if(!retrieve) for(int s=0;s<maid.getAvailableBackpackInv().getSlots();s++) actual &= maid.getAvailableBackpackInv().getStackInSlot(s).isEmpty();
                    actual &= maid.getMainHandItem().isEmpty() && maid.getOffhandItem().isEmpty() && com.wjx.touhou_aifun.maid.gui.MaidGuiSessionManager.session(maid.getUUID())==null;
                    boolean completed=frontDone && frontError==null && queue.tasks.size()==1 && task.status==AgentTaskState.Status.completed && actual && (!amend || amendmentDone && statusDone && task.goalVersion==2);
                    var row=new JsonObject();row.addProperty("repeat",repeat);row.addProperty("completed",completed);row.addProperty("tasks_created",queue.tasks.size());row.addProperty("foreground_done",frontDone);row.addProperty("foreground_error",frontError);row.addProperty("status",task==null?"no_task":task.status.name());row.addProperty("wall_seconds",(System.nanoTime()-started)/1e9);row.add("actual_destination_counts",actualCounts);
                    long controlCalls=front.getMessages().stream().filter(m->m.toolCalls()!=null).flatMap(m->m.toolCalls().stream()).filter(c->c.getFunction().getName().equals("task_control")).count();row.addProperty("foreground_control_calls",controlCalls);
                    if(amend) {row.addProperty("amendment_sent",amendmentSent);row.addProperty("amendment_done",amendmentDone);row.addProperty("status_checked",statusDone);row.addProperty("status_reply",guardedStatus);row.addProperty("goal_version",task==null?0:task.goalVersion);}
                    if(task!=null) {row.addProperty("task_id",task.id);var archive=AgentArchiveData.get(helper.getLevel()).task(maid.getUUID(),task.id);row.addProperty("background_model_requests",archive.records.stream().filter(r->r.kind().equals("model_request")).count());row.addProperty("mutations",archive.records.stream().filter(r->r.kind().equals("world_mutation")).count());row.addProperty("completion_defined",task.completion!=null);}
                    reports.add(row);Files.writeString(Path.of(prefix+"-live-results.json"),new GsonBuilder().setPrettyPrinting().create().toJson(reports));
                    net.minecraft.nbt.NbtIo.writeCompressed(AgentArchiveData.get(helper.getLevel()).save(new net.minecraft.nbt.CompoundTag()),Path.of(prefix+"-execution-archive.dat").toFile());
                    com.wjx.touhou_aifun.TouhouAIFun.LOGGER.info("AIFun foreground sorting trial={} completed={} tasks_created={}",repeat,completed,queue.tasks.size());
                    clean();repeat++;helper.runAfterDelay(1,this::begin);
                } catch(Throwable failure){restore();helper.fail(failure.toString());}
            }
            LLMCallback ownerMessage(String text,boolean status) {
                AgentRuntime.speaker(maid,new ChatSpeakerContext.Snapshot(owner.getUUID(),"owner",owner.getUUID(),"owner","owner",true,false),text);
                var messages=new ArrayList<LLMMessage>();messages.add(LLMMessage.systemChat(maid,com.github.tartaricacid.touhoulittlemaid.ai.manager.setting.papi.PapiReplacer.replaceSetting("You are a helpful Minecraft maid. Preserve the active task and use runtime facts.",maid,"en_us")));messages.add(LLMMessage.userChat(maid,text));
                EntityMaid current=maid;
                var callback=new LLMCallback(maid.getAiChatManager(),messages,true) {
                    @Override public void onSuccess(ResponseChat response) {runOnServerThread(()->{var validated=AgentRuntime.guardForegroundReply(this,response);if(status) {guardedStatus=validated.getChatText();statusDone=true;}else amendmentDone=true;ChatFlowManager.finishRequest(current.getUUID(),this);});}
                    @Override public void onFailure(java.net.http.HttpRequest request,Throwable error,int code) {runOnServerThread(()->{frontError="supplement_or_status_failed:"+code;if(status)statusDone=true;else amendmentDone=true;ChatFlowManager.finishRequest(current.getUUID(),this);});}
                };
                AgentExecution.bind(callback,0,null,0,AgentExecution.Purpose.CHAT);ChatFlowManager.registerRequest(current.getUUID(),callback);return callback;
            }
        }
        var runner=new Runner();helper.onEachTick(()->{if(runner.active) try{Thread.sleep(50);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();runner.restore();helper.fail("Foreground live trial interrupted");}});runner.begin();
    }
}
