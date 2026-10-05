package com.wjx.aifun_gui_test;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.site.AvailableSites;
import com.github.tartaricacid.touhoulittlemaid.ai.service.SerializerRegister;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.*;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.init.InitEntities;
import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import com.wjx.touhou_aifun.chat.agent.*;
import com.wjx.touhou_aifun.config.TouhouAIFunConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraftforge.gametest.*;
import java.nio.file.*;
import java.util.*;

/** Explicitly opted-in live fixed-world trials. Credentials are read in memory and never copied to QA configuration. */
@GameTestHolder("aifun_gui_test")
@PrefixGameTestTemplate(false)
public final class AgentLiveGameTests {
    private record Trial(LLMSite site,String model,boolean compact,int repeat) { }
    @GameTest(batch="agentLive",template="empty",templateNamespace="aifun_gui_test",timeoutTicks=3_000_000)
    public static void configuredProvidersCompleteFixedWorldTasks(GameTestHelper helper) throws Exception {
        String source=System.getProperty("aifun.agentQa.sites");
        if(source==null) { helper.succeed(); return; }
        JsonObject sites=JsonParser.parseString(Files.readString(Path.of(source))).getAsJsonObject();
        List<Trial> trials=new ArrayList<>();
        for(String id:List.of("anthropic","stepfun")) {
            var config=sites.getAsJsonObject(id);
            if(config==null || !config.get("enabled").getAsBoolean() || !config.has("secret_key") || config.get("secret_key").getAsString().isBlank()) continue;
            var serializer=SerializerRegister.getLLMSerializer(config.get("api_type").getAsString()); if(serializer==null) continue;
            LLMSite site=serializer.codec().parse(JsonOps.INSTANCE,config).result().orElse(null); if(site==null) continue;
            var modelEntry=config.getAsJsonArray("models").get(0); String model=modelEntry.isJsonObject()?modelEntry.getAsJsonObject().get("name").getAsString():modelEntry.getAsString();
            for(int repeat=0;repeat<3;repeat++) for(boolean compact:List.of(false,true)) trials.add(new Trial(site,model,compact,repeat));
        }
        if(trials.isEmpty()) { helper.fail("No configured enabled API sites; live-world results unavailable"); return; }
        var benchmarkTools=new AgentBenchmarkTools();
        var originals=new HashMap<>(AvailableSites.LLM_SITES);
        boolean oldRuntime=TouhouAIFunConfig.AGENT_RUNTIME.get(); int oldOutput=TouhouAIFunConfig.LLM_OUTPUT_BUDGET_TOKENS.get();
        TouhouAIFunConfig.AGENT_RUNTIME.set(true); TouhouAIFunConfig.LLM_OUTPUT_BUDGET_TOKENS.set(8192);
        List<JsonObject> reports=new ArrayList<>();
        var owner=helper.makeMockPlayer();
        class Runner {
            int index;
            EntityMaid maid;
            AgentTaskState task;
            long started,tick;
            boolean active=true;
            void restore() { active=false; if(maid!=null) AgentRuntime.stop(maid,true);benchmarkTools.close(); AvailableSites.LLM_SITES.clear();AvailableSites.LLM_SITES.putAll(originals); TouhouAIFunConfig.AGENT_RUNTIME.set(oldRuntime);TouhouAIFunConfig.LLM_OUTPUT_BUDGET_TOKENS.set(oldOutput); }
            void begin() {
                try {
                    if(index>=trials.size()) {
                        Files.writeString(Path.of("agent-live-world-results.json"),new GsonBuilder().setPrettyPrinting().create().toJson(Map.of("scope","Current runtime: full observation + individual actions versus compact observation + batching. This is not an execution of the pre-change jar.","trials",reports,"repeats_per_mode",3)));
                        restore(); if(reports.stream().allMatch(r->r.get("completed").getAsBoolean())) helper.succeed(); else helper.fail("Live-world trials failed completion validation; see redacted metrics JSON");return;
                    }
                    Trial trial=trials.get(index); AvailableSites.LLM_SITES.put(trial.site.id(),trial.site);
                    for(var floor:BlockPos.betweenClosed(new BlockPos(0,0,0),new BlockPos(5,0,5))) helper.setBlock(floor,Blocks.STONE);
                    helper.setBlock(new BlockPos(1,1,1),Blocks.CHEST); var chest=(ChestBlockEntity)helper.getBlockEntity(new BlockPos(1,1,1)); chest.clearContent();
                    Item[] items={Items.IRON_INGOT,Items.GOLD_INGOT,Items.DIAMOND,Items.EMERALD,Items.REDSTONE,Items.COAL};
                    for(int i=0;i<items.length;i++) chest.setItem(i,new ItemStack(items[i],2));
                    maid=new EntityMaid(InitEntities.MAID.get(),helper.getLevel()) { @Override public net.minecraft.world.entity.LivingEntity getOwner() { return owner; } };
                    var pos=helper.absolutePos(new BlockPos(2,1,2));maid.setPos(pos.getX()+.5,pos.getY(),pos.getZ()+.5);maid.setNoAi(true);maid.setInvulnerable(true);helper.getLevel().addFreshEntity(maid);maid.setOwnerUUID(owner.getUUID());
                    var manager=maid.getAiChatManager();manager.llmSite=trial.site.id();manager.llmModel=trial.model;manager.chatLanguage="en_us";manager.ttsSite="__none__";
                    benchmarkTools.mode(maid,trial.compact);
                    var target=helper.absolutePos(new BlockPos(1,1,1));
                    task=new AgentTaskState("Move exactly 2 of each minecraft:iron_ingot, minecraft:gold_ingot, minecraft:diamond, minecraft:emerald, minecraft:redstone and minecraft:coal from the chest at world coordinates "+target.getX()+","+target.getY()+","+target.getZ()+" into the maid BACKPACK. Keep hands empty. Do not move unrelated items. Close the menu and report completion only after all six quantities are received.");
                    JsonObject contract=new JsonObject();contract.addProperty("kind","all");JsonArray conditions=new JsonArray();
                    for(Item item:items) {
                        String id=net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(item).toString();JsonObject inventory=new JsonObject();inventory.addProperty("kind","inventory");inventory.addProperty("item",id);inventory.addProperty("count",2);inventory.addProperty("dimension","minecraft:overworld");conditions.add(inventory);
                        JsonObject transfer=inventory.deepCopy();transfer.addProperty("kind","transfer");transfer.addProperty("to_maid",true);JsonArray coordinates=new JsonArray();coordinates.add(target.getX());coordinates.add(target.getY());coordinates.add(target.getZ());transfer.add("position",coordinates);conditions.add(transfer);
                    }
                    contract.add("conditions",conditions);task.completion=AgentTaskState.validateCompletion(contract);
                    var getEntry=AgentRuntime.class.getDeclaredMethod("entry",EntityMaid.class);getEntry.setAccessible(true);Object entry=getEntry.invoke(null,maid);var q=entry.getClass().getDeclaredField("queue");q.setAccessible(true);((AgentTaskQueue)q.get(entry)).tasks.add(task);((AgentTaskQueue)q.get(entry)).next();
                    String mode=trial.compact?"Prefer intent=locate, detail=summary for scans and gui_batch for independent observed transfers; do not repeat schema loading after group:gui."
                            :"Use intent=overview and detail=full for scans. Set detail=full on each GUI tool. Use individual gui_action transfers; do not use gui_batch.";
                    var messages=new ArrayList<LLMMessage>(); messages.add(LLMMessage.systemChat(maid,"TASK_EXECUTION. Execute world tools directly; do not enqueue tasks. First call scan_surroundings to observe current world. Load group:gui once, inspect slot metadata and use actual backpack slots (not main/offhand). " +mode+" Use tool evidence and the completion contract; never assume a failed action succeeded. Avoid web, skills, visual captures and unrelated tools for this chest-only fixture."));messages.add(LLMMessage.userChat(maid,task.goal));
                    var callback=new TaskCallback(manager,messages,task);var field=entry.getClass().getDeclaredField("callback");field.setAccessible(true);field.set(entry,callback);
                    started=System.nanoTime(); tick=helper.getTick(); callback.next(trial.site.client()); helper.runAfterDelay(1,this::poll);
                } catch(Throwable failure) { restore(); helper.fail("Live fixture failed: "+failure.getClass().getSimpleName()); }
            }
            void poll() {
                try {
                    var archive=AgentArchiveData.get(helper.getLevel()).task(maid.getUUID(),task.id);
                    long requests=archive.records.stream().filter(r->r.kind().equals("model_request")).count();
                    if(task.status==AgentTaskState.Status.running || task.status==AgentTaskState.Status.waiting) {
                        if(System.nanoTime()-started<java.util.concurrent.TimeUnit.SECONDS.toNanos(180) && requests<=24) { helper.runAfterDelay(20,this::poll); return; }
                        AgentRuntime.stop(maid,false);
                    }
                    Trial trial=trials.get(index);JsonObject report=new JsonObject(); report.addProperty("provider",trial.site.getApiType());report.addProperty("model",trial.model);report.addProperty("mode",trial.compact?"compact_batch":"full_individual");report.addProperty("repeat",trial.repeat);report.addProperty("completed",task.status==AgentTaskState.Status.completed);
                    report.addProperty("status",task.status.name()); report.addProperty("wall_seconds",(System.nanoTime()-started)/1_000_000_000d);report.addProperty("model_requests",requests);
                    report.addProperty("last_phase",task.phase);report.addProperty("verified_count",task.verifiedCount);
                    long batchMutations=archive.records.stream().filter(r->r.kind().equals("world_mutation") && "gui_batch".equals(r.tool())).count();report.addProperty("batch_mutations",batchMutations);
                    if(!trial.compact && batchMutations!=0) throw new IllegalStateException("full_mode_must_not_batch");
                    report.add("fixture_failures",new Gson().toJsonTree(task.failures)); report.addProperty("fixture_outcome",task.outcome);
                    reports.add(report);
                    // Fixture-only evidence contains this artificial chest task, never player chat or API credentials.
                    Files.writeString(Path.of("agent-live-world-results.json"),new GsonBuilder().setPrettyPrinting().create().toJson(Map.of("scope","Current runtime mode comparison; not a pre-change jar baseline.","finished",false,"trials",reports)));
                    net.minecraft.nbt.NbtIo.writeCompressed(AgentArchiveData.get(helper.getLevel()).save(new net.minecraft.nbt.CompoundTag()),Path.of("agent-live-fixture-archive.dat").toFile());
                    com.wjx.touhou_aifun.TouhouAIFun.LOGGER.info("AIFun live fixture trial={} provider={} mode={} completed={} model_requests={}",index,trial.site.getApiType(),trial.compact,task.status==AgentTaskState.Status.completed,requests);
                    AgentRuntime.stop(maid,true);maid.discard();maid=null;index++;helper.runAfterDelay(1,this::begin);
                } catch(Throwable failure) { restore();helper.fail("Live fixture poll failed: "+failure.getClass().getSimpleName()); }
            }
        }
        var runner=new Runner();
        // GameTestServer runs as fast as possible. Live HTTP trials require normal world tick pacing.
        helper.onEachTick(()-> { if(runner.active) try { Thread.sleep(50); } catch(InterruptedException interrupted) { Thread.currentThread().interrupt();runner.restore();helper.fail("Live fixture interrupted"); } });
        runner.begin();
    }
}
