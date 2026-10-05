package com.wjx.aifun_gui_test;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.init.InitEntities;
import com.google.gson.JsonObject;
import com.wjx.touhou_aifun.maid.gui.MaidGuiSessionManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraftforge.gametest.*;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;

/** Mirrors the player's row of signed chests; the maid really walks and is never teleported between them. */
@GameTestHolder("aifun_four_chest_test")
@PrefixGameTestTemplate(false)
public final class FourChestNavigationGameTests {
    static void prepareFloor(GameTestHelper helper) {
        // GameTest placement clears terrain around templates. Establish the walking
        // surface explicitly, as the other movement fixtures do, before spawning.
        for(var floor:BlockPos.betweenClosed(new BlockPos(0,0,0),new BlockPos(7,0,11)))
            helper.setBlock(floor,Blocks.GRASS_BLOCK);
        for(var air:BlockPos.betweenClosed(new BlockPos(0,1,0),new BlockPos(7,5,11)))
            helper.setBlock(air,Blocks.AIR);
        helper.assertTrue(helper.getBlockState(new BlockPos(4,0,2)).is(Blocks.GRASS_BLOCK),"Walking floor exists");
    }
    @GameTest(batch="fourChestLive",template="four_chests",templateNamespace="aifun_gui_test",timeoutTicks=200000)
    public static void liveDeepSeekUsesRuntimeQueueForFourSignedChests(GameTestHelper helper) throws Exception {
        String source=System.getProperty("aifun.fourChestQa.sites");
        if(source==null) { helper.succeed();return; }
        var configuration=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(java.nio.file.Path.of(source))).getAsJsonObject().getAsJsonObject("anthropic");
        helper.assertTrue(configuration!=null && configuration.get("enabled").getAsBoolean(),"Configured DeepSeek Anthropic site required");
        var serializer=com.github.tartaricacid.touhoulittlemaid.ai.service.SerializerRegister.getLLMSerializer(configuration.get("api_type").getAsString());
        var site=serializer.codec().parse(com.mojang.serialization.JsonOps.INSTANCE,configuration).result().orElseThrow();
        var modelEntry=configuration.getAsJsonArray("models").get(0);String model=modelEntry.isJsonObject()?modelEntry.getAsJsonObject().get("name").getAsString():modelEntry.getAsString();
        var sites=com.github.tartaricacid.touhoulittlemaid.ai.manager.site.AvailableSites.LLM_SITES;
        var previousSite=sites.put(site.id(),site);
        boolean enabled=com.wjx.touhou_aifun.config.TouhouAIFunConfig.AGENT_RUNTIME.get();
        boolean diagnostics=com.wjx.touhou_aifun.config.TouhouAIFunConfig.AGENT_DIAGNOSTICS.get();
        com.wjx.touhou_aifun.config.TouhouAIFunConfig.AGENT_RUNTIME.set(true);
        com.wjx.touhou_aifun.config.TouhouAIFunConfig.AGENT_DIAGNOSTICS.set(true);
        var reports=new com.google.gson.JsonArray();var owner=helper.makeMockPlayer();
        var ownerPosition=helper.absolutePos(new BlockPos(4,1,5));owner.setPos(ownerPosition.getX()+.5,ownerPosition.getY(),ownerPosition.getZ()+.5);
        class Runner {
            int repeat;boolean active=true;EntityMaid maid;
            com.wjx.touhou_aifun.chat.agent.AgentTaskState state;
            long started;
            void clean() { if(maid!=null) { com.wjx.touhou_aifun.chat.agent.AgentRuntime.stop(maid,true);maid.discard();maid=null; } }
            void restore() {
                active=false;clean();
                if(previousSite==null) sites.remove(site.id());else sites.put(site.id(),previousSite);
                com.wjx.touhou_aifun.config.TouhouAIFunConfig.AGENT_RUNTIME.set(enabled);
                com.wjx.touhou_aifun.config.TouhouAIFunConfig.AGENT_DIAGNOSTICS.set(diagnostics);
            }
            void begin() {
                try {
                    if(repeat==3) { restore();helper.assertTrue(java.util.stream.StreamSupport.stream(reports.spliterator(),false).allMatch(r->r.getAsJsonObject().get("completed").getAsBoolean()),"Live four-chest task failed; see metadata report");helper.succeed();return; }
                    prepareFloor(helper);
                    com.google.gson.JsonArray conditions=new com.google.gson.JsonArray();StringBuilder positions=new StringBuilder();
                    for(int i=0;i<4;i++) {
                        var local=new BlockPos(2,1,2+i*2);helper.setBlock(local,Blocks.CHEST);((ChestBlockEntity)helper.getBlockEntity(local)).setItem(0,new ItemStack(Items.IRON_INGOT,2));
                        helper.setBlock(local.east(),Blocks.OAK_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING,Direction.EAST));
                        var world=helper.absolutePos(local);positions.append('[').append(world.getX()).append(',').append(world.getY()).append(',').append(world.getZ()).append("] ");
                        JsonObject condition=new JsonObject();condition.addProperty("kind","transfer");condition.addProperty("item","minecraft:iron_ingot");condition.addProperty("count",2);condition.addProperty("to_maid",true);condition.addProperty("dimension","minecraft:overworld");
                        var position=new com.google.gson.JsonArray();position.add(world.getX());position.add(world.getY());position.add(world.getZ());condition.add("position",position);conditions.add(condition);
                    }
                    JsonObject inventory=new JsonObject();inventory.addProperty("kind","inventory");inventory.addProperty("item","minecraft:iron_ingot");inventory.addProperty("count",8);inventory.addProperty("dimension","minecraft:overworld");conditions.add(inventory);
                    JsonObject contract=new JsonObject();contract.addProperty("kind","all");contract.add("conditions",conditions);
                    maid=new EntityMaid(InitEntities.MAID.get(),helper.getLevel()) { @Override public net.minecraft.world.entity.LivingEntity getOwner() { return owner; } };
                    var start=helper.absolutePos(new BlockPos(4,1,2));maid.setPos(start.getX()+.7,start.getY(),start.getZ()+.5);maid.setOwnerUUID(owner.getUUID());maid.setNoAi(false);maid.setInvulnerable(true);helper.getLevel().addFreshEntity(maid);
                    var manager=maid.getAiChatManager();manager.llmSite=site.id();manager.llmModel=model;manager.chatLanguage="en_us";manager.ttsSite="__none__";
                    var messages=new ArrayList<com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage>();
                    messages.add(com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage.systemChat(maid,"Perform the owner's Minecraft action task. Use current world evidence, observe before acting, load group:gui once, and use actual backpack slots. No unrelated web, knowledge or vision calls are needed for this fixed chest fixture."));
                    String goal="Receive exactly two minecraft:iron_ingot from EACH of the four chests at these WORLD positions: "+positions+"Walk normally between them; put all eight into the BACKPACK, keep hands empty, close each menu and confirm completion only from actual tool evidence.";
                    messages.add(com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage.userChat(maid,goal));
                    com.wjx.touhou_aifun.chat.agent.AgentRuntime.speaker(maid,new com.wjx.touhou_aifun.chat.ChatSpeakerContext.Snapshot(owner.getUUID(),"owner",owner.getUUID(),"owner","owner",true,false),goal);
                    var foreground=new LLMCallback(manager,messages,true);JsonObject command=new JsonObject();command.addProperty("action","enqueue");command.addProperty("goal",goal);command.add("completion",contract);
                    var enqueued=com.wjx.touhou_aifun.chat.agent.AgentRuntime.control(foreground,command);helper.assertTrue(!enqueued.has("error"),enqueued.toString());
                    com.wjx.touhou_aifun.chat.ChatFlowManager.finishRequest(maid.getUUID(),foreground);
                    var entryMethod=com.wjx.touhou_aifun.chat.agent.AgentRuntime.class.getDeclaredMethod("entry",EntityMaid.class);entryMethod.setAccessible(true);var entry=entryMethod.invoke(null,maid);
                    var field=entry.getClass().getDeclaredField("queue");field.setAccessible(true);state=((com.wjx.touhou_aifun.chat.agent.AgentTaskQueue)field.get(entry)).tasks.get(0);
                    started=System.nanoTime();helper.runAfterDelay(20,this::poll);
                } catch(Throwable failure) { restore();helper.fail(failure.toString()); }
            }
            void poll() {
                try {
                    var archive=com.wjx.touhou_aifun.chat.agent.AgentArchiveData.get(helper.getLevel()).task(maid.getUUID(),state.id);
                    long requests=archive.records.stream().filter(r->r.kind().equals("model_request")).count();
                    if(!state.terminal() && state.status!=com.wjx.touhou_aifun.chat.agent.AgentTaskState.Status.paused && System.nanoTime()-started<java.util.concurrent.TimeUnit.SECONDS.toNanos(120) && requests<=32) { helper.runAfterDelay(20,this::poll);return; }
                    boolean completed=state.status==com.wjx.touhou_aifun.chat.agent.AgentTaskState.Status.completed;
                    var row=new JsonObject();row.addProperty("repeat",repeat);row.addProperty("completed",completed);row.addProperty("status",state.status.name());row.addProperty("model_requests",requests);row.addProperty("wall_seconds",(System.nanoTime()-started)/1e9);
                    row.addProperty("verified_sources",state.transfers.size());row.addProperty("verified_net_count",state.transfers.values().stream().mapToLong(Long::longValue).sum());
                    row.addProperty("task_id",state.id.toString());
                    net.minecraft.nbt.NbtIo.writeCompressed(com.wjx.touhou_aifun.chat.agent.AgentArchiveData.get(helper.getLevel()).save(new net.minecraft.nbt.CompoundTag()),java.nio.file.Path.of("four-chest-execution-archive.dat").toFile());
                    reports.add(row);java.nio.file.Files.writeString(java.nio.file.Path.of("four-chest-live-results.json"),new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(reports));
                    com.wjx.touhou_aifun.TouhouAIFun.LOGGER.info("AIFun four-chest trial={} completed={} status={} model_requests={}",repeat,completed,state.status,requests);
                    clean();repeat++;helper.runAfterDelay(1,this::begin);
                } catch(Throwable failure) { restore();helper.fail(failure.toString()); }
            }
        }
        var runner=new Runner();helper.onEachTick(()-> { if(runner.active) try {Thread.sleep(50);}catch(InterruptedException interrupted) {Thread.currentThread().interrupt();runner.restore();helper.fail("Live fixture interrupted");} });runner.begin();
    }
    @GameTest(template="four_chests",templateNamespace="aifun_gui_test",timeoutTicks=2400)
    public static void fourSignedChestsOpenWithRealNavigationFromDifferentOffsets(GameTestHelper helper) {
        var owner=helper.makeMockPlayer();
        var ownerPosition=helper.absolutePos(new BlockPos(4,1,5));
        owner.setPos(ownerPosition.getX()+.5,ownerPosition.getY(),ownerPosition.getZ()+.5);
        class Runner {
            int sample,index;
            EntityMaid maid;
            LLMCallback callback;
            CompletableFuture<JsonObject> opening;
            long started;
            final double[] offsets={.10,.30,.50,.70,.90,1.30,1.70,2.30};
            void clean() {
                if(maid!=null) { MaidGuiSessionManager.cancel(maid.getUUID(),"qa_complete");maid.discard();maid=null; }
            }
            void begin() {
                try {
                    if(sample==offsets.length) { helper.succeed();return; }
                    prepareFloor(helper);
                    for(int i=0;i<4;i++) {
                        var target=new BlockPos(2,1,2+i*2);helper.setBlock(target,Blocks.CHEST);
                        ((ChestBlockEntity)helper.getBlockEntity(target)).setItem(0,new ItemStack(Items.IRON_INGOT,2));
                        helper.setBlock(target.east(),Blocks.OAK_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING,Direction.EAST));
                    }
                    maid=new EntityMaid(InitEntities.MAID.get(),helper.getLevel()) {
                        @Override public net.minecraft.world.entity.LivingEntity getOwner() { return owner; }
                    };
                    var start=helper.absolutePos(new BlockPos(4,1,2));maid.setPos(start.getX()+offsets[sample],start.getY(),start.getZ()+.5);
                    maid.setOwnerUUID(owner.getUUID());maid.setInvulnerable(true);maid.setNoAi(false);helper.getLevel().addFreshEntity(maid);
                    callback=new LLMCallback(maid.getAiChatManager(),new ArrayList<>(),true);index=0;open();
                } catch(Throwable failure) { clean();helper.fail(failure.toString()); }
            }
            void open() {
                var target=helper.absolutePos(new BlockPos(2,1,2+index*2));JsonObject request=new JsonObject();
                request.addProperty("target","minecraft:chest");request.addProperty("x",target.getX());request.addProperty("y",target.getY());request.addProperty("z",target.getZ());request.addProperty("wait_policy","AUTO");
                started=helper.getTick();opening=MaidGuiSessionManager.call(callback,"open_gui","walk_"+index,request);
                helper.runAfterDelay(1,this::poll);
            }
            void poll() {
                try {
                    if(!opening.isDone()) {
                        helper.assertTrue(helper.getTick()-started<80,"Navigation stalled: sample="+sample+", chest="+index+", position="+maid.position()+", navigation_done="+maid.getNavigation().isDone());
                        helper.runAfterDelay(1,this::poll);return;
                    }
                    var result=opening.join();
                    if(result.has("error")) {
                        var routeTarget=helper.absolutePos(new BlockPos(3,1,2+index*2));var probe=maid.getNavigation().createPath(routeTarget,0);
                        com.wjx.touhou_aifun.TouhouAIFun.LOGGER.info("QA exact path target={} path={} reached={} nodes={} endpoint={} follow_range={}",routeTarget,probe,probe!=null&&probe.canReach(),probe==null?0:probe.getNodeCount(),probe==null?null:probe.getEndNode(),maid.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.FOLLOW_RANGE));
                        var evaluator=maid.getNavigation().getNodeEvaluator();
                        evaluator.prepare(new net.minecraft.world.level.PathNavigationRegion(helper.getLevel(),maid.blockPosition().offset(-16,-6,-16),maid.blockPosition().offset(16,6,16)),maid);
                        try {
                            var start=evaluator.getStart();var neighbors=new net.minecraft.world.level.pathfinder.Node[32];int count=start==null?0:evaluator.getNeighbors(neighbors,start);
                            StringBuilder route=new StringBuilder();for(int i=0;i<count;i++) route.append(neighbors[i].asBlockPos()).append(':').append(neighbors[i].type).append(' ');
                            com.wjx.touhou_aifun.TouhouAIFun.LOGGER.info("QA four-chest failure sample={} chest={} exact={} grounded={} start={} neighbors={} floor={} restriction={} navigation={}",sample,index,maid.position(),maid.onGround(),start==null?null:start.asBlockPos(),route,helper.getLevel().getBlockState(maid.blockPosition().below()),maid.hasRestriction(),maid.getNavigation().getClass().getSimpleName());
                        } finally { evaluator.done(); }
                    }
                    helper.assertTrue(!result.has("error"),result.toString());
                    var session=MaidGuiSessionManager.session(maid.getUUID());int destination=-1;
                    for(var slot:session.menu().slots) if(slot.container==session.actor.getInventory() && slot.mayPlace(new ItemStack(Items.IRON_INGOT))) { destination=slot.index;break; }
                    helper.assertTrue(destination>=0,"Observed destination exists");
                    JsonObject transfer=new JsonObject();transfer.addProperty("session_id",session.id.toString());transfer.addProperty("action","transfer");transfer.addProperty("slot",0);transfer.addProperty("to_slot",destination);transfer.addProperty("count",2);
                    var moved=MaidGuiSessionManager.call(callback,"gui_action","receive_"+index,transfer).join();helper.assertTrue(moved.get("moved_count").getAsInt()==2,moved.toString());
                    JsonObject close=new JsonObject();close.addProperty("session_id",session.id.toString());
                    var closed=MaidGuiSessionManager.call(callback,"close_gui","close_"+index,close).join();helper.assertTrue(!closed.has("error"),closed.toString());
                    if(++index==4) {
                        int count=0;var inventory=maid.getAvailableBackpackInv();for(int i=0;i<inventory.getSlots();i++) if(inventory.getStackInSlot(i).is(Items.IRON_INGOT)) count+=inventory.getStackInSlot(i).getCount();
                        if(maid.getMainHandItem().is(Items.IRON_INGOT)) count+=maid.getMainHandItem().getCount();
                        helper.assertTrue(count==8,"Eight actual items received without replaying a chest");clean();sample++;helper.runAfterDelay(1,this::begin);
                    } else helper.runAfterDelay(1,this::open);
                } catch(Throwable failure) { clean();helper.fail(failure.toString()); }
            }
        }
        new Runner().begin();
    }
    @GameTest(template="four_chests",templateNamespace="aifun_gui_test",timeoutTicks=120)
    public static void finishedApproachOutsideInteractionRangeIsReplanned(GameTestHelper helper) throws Exception {
        prepareFloor(helper);var chest=new BlockPos(2,1,2);helper.setBlock(chest,Blocks.CHEST);
        var maid=helper.spawn(InitEntities.MAID.get(),new BlockPos(4,1,2));maid.setNoAi(false);maid.setInvulnerable(true);
        var origin=helper.absolutePos(new BlockPos(4,1,2));maid.setPos(origin.getX()+.78062448884627,origin.getY(),origin.getZ()+.82681780989083);
        var callback=new LLMCallback(maid.getAiChatManager(),new ArrayList<>(),true);var target=helper.absolutePos(chest);
        var request=new JsonObject();request.addProperty("target","minecraft:chest");request.addProperty("x",target.getX());request.addProperty("y",target.getY());request.addProperty("z",target.getZ());
        var future=MaidGuiSessionManager.call(callback,"open_gui","stale_arrival",request);
        helper.assertTrue(!future.isDone(),"Observed real position is outside interaction range");
        // Recreate the recorded navigation arrival: the selected standing block has
        // been reached, but exact feet are still 2.35755 blocks from the container.
        var field=MaidGuiSessionManager.class.getDeclaredField("OPENING");field.setAccessible(true);
        var map=(java.util.Map)field.get(null);Object opening=map.get(maid.getUUID());
        var parts=opening.getClass().getRecordComponents();Object[] values=new Object[parts.length];Class<?>[] types=new Class<?>[parts.length];
        for(int i=0;i<parts.length;i++) {var getter=parts[i].getAccessor();getter.setAccessible(true);values[i]=parts[i].getName().equals("approach")?maid.blockPosition():getter.invoke(opening);types[i]=parts[i].getType();}
        var constructor=opening.getClass().getDeclaredConstructor(types);constructor.setAccessible(true);map.put(maid.getUUID(),constructor.newInstance(values));maid.getNavigation().stop();
        long started=helper.getTick();
        class Poll {
            void run() {
                try {
                    if(!future.isDone()) {helper.assertTrue(helper.getTick()-started<80,"A finished approach outside range must not loop on the same standing block");helper.runAfterDelay(1,this::run);return;}
                    helper.assertTrue(!future.join().has("error"),"Replanned navigation opens the actual chest");MaidGuiSessionManager.cancel(maid.getUUID(),"qa_complete");maid.discard();helper.succeed();
                } catch(Throwable failure) {MaidGuiSessionManager.cancel(maid.getUUID(),"qa_failed");maid.discard();helper.fail(failure.toString());}
            }
        }
        helper.runAfterDelay(1,new Poll()::run);
    }
    @GameTest(template="four_chests",templateNamespace="aifun_gui_test",timeoutTicks=120)
    public static void fractionalChestTopStandingHeightCannotLoopOnFinishedApproach(GameTestHelper helper) throws Exception {
        prepareFloor(helper);var chest=new BlockPos(2,1,2);helper.setBlock(chest,Blocks.CHEST);
        helper.setBlock(new BlockPos(2,1,4),Blocks.CHEST);
        var maid=helper.spawn(InitEntities.MAID.get(),new BlockPos(2,2,4));maid.setNoAi(false);maid.setInvulnerable(true);
        var origin=helper.absolutePos(new BlockPos(2,1,4));maid.setPos(origin.getX()+.50320361153984,origin.getY()+.875,origin.getZ()+.95349986141977);
        var target=helper.absolutePos(chest);helper.assertTrue(maid.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(target))>2.25*2.25,"Recorded chest-top feet are outside interaction range");
        var callback=new LLMCallback(maid.getAiChatManager(),new ArrayList<>(),true);var args=new JsonObject();args.addProperty("target","minecraft:chest");args.addProperty("x",target.getX());args.addProperty("y",target.getY());args.addProperty("z",target.getZ());args.addProperty("wait_policy","NO_WAIT");
        var future=MaidGuiSessionManager.call(callback,"open_gui","chest_top",args);
        var field=MaidGuiSessionManager.class.getDeclaredField("OPENING");field.setAccessible(true);var map=(java.util.Map)field.get(null);Object opening=map.get(maid.getUUID());
        var parts=opening.getClass().getRecordComponents();Object[] values=new Object[parts.length];Class<?>[] types=new Class<?>[parts.length];
        for(int i=0;i<parts.length;i++) {var getter=parts[i].getAccessor();getter.setAccessible(true);values[i]=parts[i].getName().equals("approach")?origin.above():getter.invoke(opening);types[i]=parts[i].getType();}
        var constructor=opening.getClass().getDeclaredConstructor(types);constructor.setAccessible(true);map.put(maid.getUUID(),constructor.newInstance(values));maid.getNavigation().stop();
        long started=helper.getTick();
        class Poll {void run() {
            try {
                if(!future.isDone()) {helper.assertTrue(helper.getTick()-started<80,"Fractional standing height must replan instead of waiting 400 ticks");helper.runAfterDelay(1,this::run);return;}
                helper.assertTrue(!future.join().has("error"),"Chest-top replan opens the real target");MaidGuiSessionManager.cancel(maid.getUUID(),"qa_complete");maid.discard();helper.succeed();
            } catch(Throwable failure) {MaidGuiSessionManager.cancel(maid.getUUID(),"qa_failed");maid.discard();helper.fail(failure.toString());}
        }}
        helper.runAfterDelay(1,new Poll()::run);
    }
}
