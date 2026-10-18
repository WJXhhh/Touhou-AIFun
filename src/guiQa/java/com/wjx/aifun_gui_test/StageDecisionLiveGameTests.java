package com.wjx.aifun_gui_test;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.*;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.site.AvailableSites;
import com.github.tartaricacid.touhoulittlemaid.ai.service.SerializerRegister;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.*;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.*;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.request.ChatCompletion;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.init.InitEntities;
import com.google.gson.*;
import com.mojang.serialization.*;
import com.wjx.touhou_aifun.chat.agent.*;
import com.wjx.touhou_aifun.config.TouhouAIFunConfig;
import com.wjx.touhou_aifun.maid.gui.MaidGuiSessionManager;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraftforge.gametest.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Paid, opt-in Flash/native A/B on actual server inventories. Never edits a player save. */
@GameTestHolder("aifun_stage_live_test")
@PrefixGameTestTemplate(false)
public final class StageDecisionLiveGameTests {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final BlockPos A=new BlockPos(2,1,2), B=new BlockPos(2,1,5), OUT=new BlockPos(2,1,9),
            TABLE=new BlockPos(5,1,6), FORBIDDEN=new BlockPos(6,1,9);
    private static final boolean REPEATED=true;
    private static List<BlockPos> row(int first,int count){return java.util.stream.IntStream.range(first,first+count).mapToObj(i->new BlockPos(1+2*(i%6),1,1+2*(i/6))).toList();}
    // Four sources, real crafting and four destinations keep the paid comparison bounded.
    private static final List<BlockPos> SOURCES=row(0,4), DESTINATIONS=row(4,4);
    private static BlockPos table(){return REPEATED?new BlockPos(5,1,9):TABLE;}
    private static BlockPos forbidden(){return REPEATED?new BlockPos(11,1,9):FORBIDDEN;}

    @GameTest(batch="allowedLive",template="four_chests",templateNamespace="aifun_stage_live_test",timeoutTicks=45_000)
    public static void existingStagesUseRealDeciderComparedWithFlashOnly(GameTestHelper helper) throws Exception {
        String source=System.getProperty("aifun.stageQa.sites");if(source==null){helper.succeed();return;}
        // Use the configured background site; paid QA defaults to Flash unless explicitly overridden.
        String config=Files.readString(Path.of(System.getProperty("aifun.stageQa.config")));
        var configScope=new QaConfigScope();
        TouhouAIFunConfig.SPEC.setConfig(new com.electronwill.nightconfig.toml.TomlParser().parse(config));
        if(!com.wjx.touhou_aifun.chat.agent.decision.DecisionServiceSettings.snapshot().view().hasKey())throw new IllegalStateException("Configured native decision credentials required");
        TouhouAIFunConfig.DECISION_STARTUP_TEMPLATES.set(false);
        var matcher=java.util.regex.Pattern.compile("(?m)^\\s*decisionModel\\s*=\\s*\"(.*)\"\\s*$").matcher(config);
        if(!matcher.find())throw new IllegalStateException("Configured background model required");
        var modelPair=JsonParser.parseString(matcher.group(1).replace("\\\"","\"")).getAsJsonArray();
        var siteConfig=JsonParser.parseString(Files.readString(Path.of(source))).getAsJsonObject().getAsJsonObject(modelPair.get(0).getAsString());
        if(siteConfig==null || !siteConfig.get("enabled").getAsBoolean() || siteConfig.get("secret_key").getAsString().isBlank())throw new IllegalStateException("Configured API site required");
        LLMSite site=SerializerRegister.getLLMSerializer(siteConfig.get("api_type").getAsString()).codec().parse(JsonOps.INSTANCE,siteConfig).result().orElseThrow();
        String model=System.getProperty("aifun.stageQa.model","deepseek-flash");
        if(siteConfig.getAsJsonArray("models").asList().stream().noneMatch(value->(value.isJsonObject()?value.getAsJsonObject().get("name").getAsString():value.getAsString()).equals(model)))
            throw new IllegalStateException("QA model must be present in the configured site's models");
        var previous=AvailableSites.LLM_SITES.put(site.id(),site);
        boolean runtime=TouhouAIFunConfig.AGENT_RUNTIME.get(), decision=TouhouAIFunConfig.DECISION_SERVICE_ENABLED.get(), diagnostics=TouhouAIFunConfig.AGENT_DIAGNOSTICS.get(), automatic=TouhouAIFunConfig.AGENT_AUTOMATIC_TOOL_SELECTION.get();
        int budget=TouhouAIFunConfig.AGENT_CONTEXT_INPUT_BUDGET_TOKENS.get();
        TouhouAIFunConfig.AGENT_RUNTIME.set(true);TouhouAIFunConfig.DECISION_SERVICE_ENABLED.set(false);TouhouAIFunConfig.AGENT_DIAGNOSTICS.set(true);
        TouhouAIFunConfig.AGENT_CONTEXT_INPUT_BUDGET_TOKENS.set(475000);
        var tools=new AllowedToolsLiveGameTests.PlannerSwitch();var reports=new JsonArray();var owner=helper.makeMockPlayer();
        String mode=System.getProperty("aifun.stageQa.mode","all");
        class Runner {
            int trial=mode.equals("automatic")?1:0;boolean nativeEnabled,active=true,amended,compressed,compressionPreserved,compressionScopePreserved;EntityMaid maid;AgentTaskState task;TaskCallback callback;
            long started;int beforeMessages,afterMessages;Map<String,Long> receiptsAtCompression=Map.of();
            final Map<String,String> retainedRaw=new LinkedHashMap<>();
            void clean(){if(maid!=null){AgentRuntime.stop(maid,true);maid.discard();maid=null;}}
            void restore(){active=false;clean();tools.close();if(previous==null)AvailableSites.LLM_SITES.remove(site.id());else AvailableSites.LLM_SITES.put(site.id(),previous);
                TouhouAIFunConfig.AGENT_RUNTIME.set(runtime);TouhouAIFunConfig.DECISION_SERVICE_ENABLED.set(decision);TouhouAIFunConfig.AGENT_DIAGNOSTICS.set(diagnostics);TouhouAIFunConfig.AGENT_CONTEXT_INPUT_BUDGET_TOKENS.set(budget);TouhouAIFunConfig.AGENT_AUTOMATIC_TOOL_SELECTION.set(automatic);configScope.close();}
            void begin(){try{
                if(trial==2 || trial==1 && mode.equals("baseline")){restore();helper.assertTrue(java.util.stream.StreamSupport.stream(reports.spliterator(),false).allMatch(v->v.getAsJsonObject().get("completed").getAsBoolean()),"Live comparison failed; see stage-decision-live-results.json");helper.succeed();return;}
                nativeEnabled=mode.equals("automatic") || !mode.equals("baseline") && ((trial==1)!=Boolean.getBoolean("aifun.stageQa.automaticFirst"));
                tools.enabled=true;TouhouAIFunConfig.DECISION_SERVICE_ENABLED.set(nativeEnabled);amended=false;compressed=false;compressionPreserved=false;compressionScopePreserved=false;beforeMessages=afterMessages=0;
                TouhouAIFunConfig.AGENT_AUTOMATIC_TOOL_SELECTION.set(tools.enabled);
                retainedRaw.clear();
                if(REPEATED) {
                    var containers=new ArrayList<>(SOURCES);containers.addAll(DESTINATIONS);containers.add(forbidden());
                    for(var local:containers)if(helper.getBlockEntity(local) instanceof ChestBlockEntity old)old.clearContent();
                    for(var floor:BlockPos.betweenClosed(new BlockPos(0,0,0),new BlockPos(20,0,13)))helper.setBlock(floor,Blocks.GRASS_BLOCK);
                    for(var air:BlockPos.betweenClosed(new BlockPos(0,1,0),new BlockPos(20,5,13)))helper.setBlock(air,Blocks.AIR);
                    for(var local:containers)helper.setBlock(local,Blocks.CHEST);
                    for(var local:SOURCES){chest(local).setItem(0,new ItemStack(Items.OAK_PLANKS,6));chest(local).setItem(1,new ItemStack(Items.IRON_INGOT,3));chest(local).setItem(2,new ItemStack(Items.GOLD_INGOT,4));}
                    chest(SOURCES.get(SOURCES.size()-1)).setItem(3,new ItemStack(Items.REDSTONE,3));
                } else {
                    for(var local:List.of(A,B,OUT,FORBIDDEN))if(helper.getBlockEntity(local) instanceof ChestBlockEntity old)old.clearContent();
                    FourChestNavigationGameTests.prepareFloor(helper);
                    for(var local:List.of(A,B,OUT,FORBIDDEN))helper.setBlock(local,Blocks.CHEST);
                    chest(A).setItem(0,new ItemStack(Items.OAK_PLANKS,8));chest(A).setItem(1,new ItemStack(Items.GOLD_INGOT,4));
                    chest(B).setItem(0,new ItemStack(Items.IRON_INGOT,5));chest(B).setItem(1,new ItemStack(Items.REDSTONE,3));
                }
                chest(forbidden()).setItem(0,new ItemStack(Items.DIAMOND,7));helper.setBlock(table(),Blocks.CRAFTING_TABLE);
                maid=new EntityMaid(InitEntities.MAID.get(),helper.getLevel()){@Override public net.minecraft.world.entity.LivingEntity getOwner(){return owner;}};
                var start=helper.absolutePos(new BlockPos(4,1,2));maid.setPos(start.getX()+.5,start.getY(),start.getZ()+.5);maid.setOwnerUUID(owner.getUUID());maid.setNoAi(false);maid.setInvulnerable(true);helper.getLevel().addFreshEntity(maid);
                FourChestNavigationGameTests.prepareNavigationChunks(maid);owner.setPos(start.getX()+.5,start.getY(),start.getZ()+1.5);
                var manager=maid.getAiChatManager();manager.llmSite=site.id();manager.llmModel=model;manager.chatLanguage="en_us";manager.ttsSite="__none__";
                var entryMethod=AgentRuntime.class.getDeclaredMethod("entry",EntityMaid.class);entryMethod.setAccessible(true);var entry=entryMethod.invoke(null,maid);
                var queueField=entry.getClass().getDeclaredField("queue");queueField.setAccessible(true);var queue=(AgentTaskQueue)queueField.get(entry);
                task=queue.enqueue(REPEATED?repeatGoal():"Complete this workshop order in the overworld. Source A chest at "+pos(A)+" contains 8 oak planks and 4 gold ingots; source B chest at "+pos(B)+" contains 5 iron ingots and 3 redstone. Take exactly 6 oak planks from A and exactly 3 iron ingots from B, leaving the other materials in their original chests. Query the loaded server recipe for aifun_gui_test:machine; do not guess it. Use automatic workbench crafting at "+pos(TABLE)+" to produce exactly 6 machines, then deliver all six into the output chest at "+pos(OUT)+". Do not touch the 4 gold ingots or the separate forbidden diamond chest at "+pos(FORBIDDEN)+". Finish with an empty backpack, empty hands and all menus closed. Preserve every requirement if I supplement this order; already collected quantities must not be collected again. Use actual observations and native tools; no web, skills or image captures are needed.");
                queue.next();callback=new TaskCallback(manager,new ArrayList<>(List.of(LLMMessage.userChat(maid,task.goal))),task);
                if(!tools.enabled){var messages=callback.getMessages();for(int i=0;i<messages.size();i++){var m=messages.get(i);if(m.role()==Role.SYSTEM && m.message().contains("When recording a plan, set allowed_tools"))messages.set(i,new LLMMessage(m.role(),m.message().replaceAll("(?m)^When recording a plan, set allowed_tools[^\\n]*\\n",""),m.gameTime(),m.toolCalls(),m.toolCallId()));}}
                var callbackField=entry.getClass().getDeclaredField("callback");callbackField.setAccessible(true);callbackField.set(entry,callback);
                var catalog=com.wjx.touhou_aifun.compat.ai.openai.ToolCatalogSnapshot.capture(maid,null,callback);
                var planner=catalog.planningEntries().stream().filter(e->e.id().equals("update_task_plan")).findFirst().orElseThrow();
                helper.assertTrue(planner.anthropicTool().getAsJsonObject("input_schema").getAsJsonObject("properties").has("allowed_tools")==tools.enabled,"Only the optional allowlist parameter changes between valid planner schemas");
                for(var id:List.of("open_gui","gui_action","gui_batch")) {
                    var schema=catalog.planningEntries().stream().filter(e->e.id().equals(id)).findFirst().orElseThrow().anthropicTool().getAsJsonObject("input_schema").getAsJsonObject("properties");
                    helper.assertTrue(schema.has("next_step") && schema.has("next_allowed_tools")== (tools.enabled && id.equals("open_gui")),"Both arms support fused progress; inactive selection is offered only on automatic opening");
                }
                started=System.nanoTime();callback.next(site.client());helper.runAfterDelay(5,this::poll);
            }catch(Throwable failure){restore();helper.fail("Live fixture setup failed: "+failure.getClass().getSimpleName());}}
            String pos(BlockPos p){var w=helper.absolutePos(p);return "["+w.getX()+","+w.getY()+","+w.getZ()+"]";}
            String repeatGoal(){return "Complete this warehouse manufacturing order in the overworld, in THREE strictly ordered phases. Phase 1: the two PLANK sources are "+SOURCES.subList(0,2).stream().map(this::pos).toList()+"; take exactly 4 oak planks from EACH, leaving its 2 planks, all 3 iron and all 4 protected gold. The two IRON sources are "+SOURCES.subList(2,4).stream().map(this::pos).toList()+"; take exactly 2 iron ingots from EACH, leaving its 1 iron, all 6 planks and all 4 protected gold. Every source initially contains 6 planks, 3 iron, 4 gold. Collect ALL four sources before crafting. Phase 2: query the loaded server recipe for aifun_gui_test:machine (do not guess it), then use automatic workbench crafting at "+pos(table())+" to manufacture exactly 8 machines from the collected 8 planks and 4 iron. Hold ALL 8 machines before any delivery. Phase 3: visit EACH of the four initially empty destinations at "+DESTINATIONS.stream().map(this::pos).toList()+" and deliver exactly 2 machines into EACH. Never open the forbidden chest at "+pos(forbidden())+" containing 7 diamonds. All targets are within 16 blocks: use max_distance=16 when opening distant menus. Finish with empty backpack, empty hands and all menus closed. Preserve every original requirement if I supplement the order. Already received quantities and completed operations must never be repeated. Use actual observations and native tools; no web, skills or image captures are needed.";}
            ChestBlockEntity chest(BlockPos p){return (ChestBlockEntity)helper.getBlockEntity(p);}
            int count(ChestBlockEntity c,Item item){int n=0;for(int i=0;i<c.getContainerSize();i++)if(c.getItem(i).is(item))n+=c.getItem(i).getCount();return n;}
            void poll(){if(!active)return;try{
                var archive=AgentArchiveData.get(helper.getLevel()).task(maid.getUUID(),task.id);
                retainedRaw.putAll(callback.results.snapshot().values());
                if(helper.getTick()%100<5)FourChestNavigationGameTests.prepareNavigationChunks(maid);
                long requests=archive.records.stream().filter(r->r.kind().equals("model_request")).count();
                if(!amended && task.transfers.size()>=(REPEATED?2:1)){
                    task.amend(REPEATED?"Additional requirement: in phase 1 also take exactly 1 redstone from the LAST iron source chest at "+pos(SOURCES.get(SOURCES.size()-1))+", leaving its other 2 redstone. Carry it through manufacturing and deliver it into the FIRST destination at "+pos(DESTINATIONS.get(0))+" during phase 3. ALL earlier per-source quantities, per-destination quantities, phase order, protected gold/diamond and final empty-backpack/hands/closed-menu requirements still apply. Do not repeat already received materials or completed operations.":"Additional requirement: also take exactly 1 redstone from source B at "+pos(B)+" and put it in the output chest at "+pos(OUT)+"; leave the other 2 redstone in B. ALL earlier quantities, destinations, untouched gold/diamond and final empty-backpack/hands/closed-menu requirements still apply. Do not collect any already received materials again.");amended=true;AgentRuntime.save(maid);
                }
                // Stress real completed history once, at a lower QA projection target. Production input budget remains 475K.
                if(amended && task.goalVersion>=2 && !compressed && task.transfers.size()>=(REPEATED?8:1) && archive.records.stream().filter(r->r.kind().equals("tool_call")).count()>=(REPEATED?20:12)){
                    var owners=callback.getMessages().stream().filter(m->m.role()==Role.USER).toList();var receipts=new LinkedHashMap<>(task.transfers);
                    var selectionBefore=callback.allowedTools();var contractBefore=task.completion.deepCopy();int phaseBefore=task.completionStage;
                    beforeMessages=callback.getMessages().size();var reduced=AgentContext.compact(callback.getMessages(),4000,callback.results);
                    callback.getMessages().clear();callback.getMessages().addAll(reduced);afterMessages=reduced.size();
                    compressed=afterMessages<beforeMessages;compressionPreserved=compressed && reduced.containsAll(owners) && task.transfers.equals(receipts);receiptsAtCompression=receipts;
                    compressionScopePreserved=Objects.equals(selectionBefore,callback.allowedTools()) && contractBefore.equals(task.completion) && phaseBefore==task.completionStage;
                    var pressure=new JsonObject();pressure.addProperty("before_messages",beforeMessages);pressure.addProperty("after_messages",afterMessages);pressure.addProperty("completion_stage",phaseBefore);pressure.addProperty("goal_version",task.goalVersion);pressure.add("allowed_tools",JSON.toJsonTree(selectionBefore));pressure.addProperty("requirements_receipts_scope_retained",compressionPreserved && compressionScopePreserved);
                    callback.decisionTrace("qa_compression",pressure.toString());
                }
                boolean running=task.status==AgentTaskState.Status.running || task.status==AgentTaskState.Status.waiting;
                if(running && System.nanoTime()-started<TimeUnit.SECONDS.toNanos(REPEATED?600:900) && requests<=(REPEATED?64:32)){helper.runAfterDelay(5,this::poll);return;}
                var checks=new JsonObject();
                if(REPEATED) {
                    for(int i=0;i<SOURCES.size();i++){var sourceChest=chest(SOURCES.get(i));checks.addProperty("source_"+i+"_exact_remainder",count(sourceChest,Items.OAK_PLANKS)==(i<2?2:6) && count(sourceChest,Items.IRON_INGOT)==(i<2?3:1) && count(sourceChest,Items.GOLD_INGOT)==4);}
                    for(int i=0;i<DESTINATIONS.size();i++)checks.addProperty("destination_"+i+"_exact_2",count(chest(DESTINATIONS.get(i)),GuiFixtureMod.MACHINE.get().asItem())==2);
                    checks.addProperty("redstone_source_exact_2",count(chest(SOURCES.get(SOURCES.size()-1)),Items.REDSTONE)==2);checks.addProperty("redstone_destination_exact_1",count(chest(DESTINATIONS.get(0)),Items.REDSTONE)==1);
                    checks.addProperty("required_ordered_milestones_verified",milestonesVerified());
                } else {
                checks.addProperty("source_a_planks_exact_2",count(chest(A),Items.OAK_PLANKS)==2);checks.addProperty("gold_unchanged_4",count(chest(A),Items.GOLD_INGOT)==4);
                checks.addProperty("source_b_iron_exact_2",count(chest(B),Items.IRON_INGOT)==2);checks.addProperty("source_b_redstone_exact_2",count(chest(B),Items.REDSTONE)==2);
                checks.addProperty("output_machines_exact_6",count(chest(OUT),GuiFixtureMod.MACHINE.get().asItem())==6);checks.addProperty("output_redstone_exact_1",count(chest(OUT),Items.REDSTONE)==1);
                }
                checks.addProperty("diamonds_unchanged_7",count(chest(forbidden()),Items.DIAMOND)==7);
                boolean empty=true;for(int i=0;i<maid.getAvailableBackpackInv().getSlots();i++)empty &= maid.getAvailableBackpackInv().getStackInSlot(i).isEmpty();
                checks.addProperty("backpack_empty",empty);checks.addProperty("hands_empty",maid.getMainHandItem().isEmpty() && maid.getOffhandItem().isEmpty());checks.addProperty("menus_closed",MaidGuiSessionManager.session(maid.getUUID())==null);
                checks.addProperty("amendment_applied",amended && task.goalVersion==2);checks.addProperty("compressed_owner_and_receipts_retained",compressionPreserved);
                checks.addProperty("compressed_contract_phase_and_selection_retained",compressionScopePreserved);
                checks.addProperty("used_loaded_recipe",archive.records.stream().anyMatch(r->r.kind().equals("tool_call") && r.tool().equals("query_recipes")));
                if(nativeEnabled) {
                    checks.addProperty("real_native_stage_response",archive.records.stream().anyMatch(r->r.kind().equals("decision_response") && r.summary().startsWith("purpose=stage;")));
                    boolean automaticMutation=false,automaticCall=false;
                    for(var record:archive.records) {
                        if(record.kind().equals("tool_call"))automaticCall=record.callId().startsWith("decision_");
                        if(automaticCall && record.kind().equals("world_mutation"))automaticMutation=true;
                    }
                    checks.addProperty("native_stage_committed_real_mutation",automaticMutation);
                }
                var row=new JsonObject();row.addProperty("mode",nativeEnabled?"native_existing_stage":"flash_only");row.addProperty("task_id",task.id);row.addProperty("model",model);row.addProperty("input_budget",475000);row.addProperty("status",task.status.name());row.addProperty("model_requests",requests);row.addProperty("wall_seconds",(System.nanoTime()-started)/1e9);
                row.addProperty("scenario",REPEATED?"four_source_manufacture_four_destination":"workshop");row.add("raw_evidence",JSON.toJsonTree(retainedRaw));row.add("forbidden_position",JsonParser.parseString(pos(forbidden())));
                if(task.completion!=null)row.add("final_completion",task.completion.deepCopy());row.addProperty("final_completion_stage",task.completionStage);
                if(REPEATED){var expected=new JsonArray();for(int i=0;i<SOURCES.size();i++)expected.add(expectedTransfer(SOURCES.get(i),i<2?"minecraft:oak_planks":"minecraft:iron_ingot",true,i<2?4:2));expected.add(expectedTransfer(SOURCES.get(SOURCES.size()-1),"minecraft:redstone",true,1));expected.add(expectedTransfer(table(),"aifun_gui_test:machine",true,8));for(var local:DESTINATIONS)expected.add(expectedTransfer(local,"aifun_gui_test:machine",false,2));expected.add(expectedTransfer(DESTINATIONS.get(0),"minecraft:redstone",false,1));row.add("expected_gross_transfers",expected);}
                row.add("checks",checks);row.addProperty("completed",task.status==AgentTaskState.Status.completed && checks.entrySet().stream().allMatch(e->e.getValue().getAsBoolean()));
                row.addProperty("compression_before_messages",beforeMessages);row.addProperty("compression_after_messages",afterMessages);row.add("receipts_at_compression",JSON.toJsonTree(receiptsAtCompression));row.add("final_receipts",JSON.toJsonTree(task.transfers));row.add("failures",JSON.toJsonTree(task.failures));row.addProperty("outcome",task.outcome);
                var calls=new JsonArray();var selections=new JsonArray();var plans=new JsonArray();var failures=new JsonArray();
                for(var record:archive.records){if(record.kind().equals("tool_selection"))selections.add(record.summary());
                    if(record.kind().equals("tool_call")){calls.add(record.tool());if(record.tool().equals("update_task_plan")){String raw=callback.results.snapshot().values().get(record.argumentsRef());if(raw!=null)plans.add(JsonParser.parseString(raw));}}
                    if(record.kind().equals("tool_result") && (record.summary().contains("failed") || record.summary().contains("error")))failures.add(record.summary());}
                row.addProperty("decision_requests",archive.records.stream().filter(r->r.kind().equals("decision_response")).count());
                row.add("decision_events",JSON.toJsonTree(archive.records.stream().filter(r->r.kind().startsWith("decision")).map(AgentTaskArchive.Record::summary).toList()));
                row.add("tools",calls);row.add("selections",selections);row.add("model_plans",plans);row.add("failed_results",failures);reports.add(row);
                Files.writeString(Path.of("stage-decision-live-results.json"),JSON.toJson(reports));
                net.minecraft.nbt.NbtIo.writeCompressed(AgentArchiveData.get(helper.getLevel()).save(new net.minecraft.nbt.CompoundTag()),Path.of("stage-decision-live-archive.dat").toFile());
                com.wjx.touhou_aifun.TouhouAIFun.LOGGER.info("AIFun stage-decision live mode={} completed={} requests={}",row.get("mode"),row.get("completed"),requests);
                clean();trial++;helper.runAfterDelay(1,this::begin);
            }catch(Throwable failure){restore();helper.fail("Live fixture polling failed: "+failure.getClass().getSimpleName());}}
            JsonObject expectedTransfer(BlockPos local,String item,boolean receive,int count){var value=new JsonObject();value.add("position",JsonParser.parseString(pos(local)));value.addProperty("item",item);value.addProperty("to_maid",receive);value.addProperty("count",count);return value;}
            boolean milestonesVerified(){
                if(task.completion==null || !AgentTaskState.completionKind(task.completion).equals("sequence"))return false;
                var phases=task.completion.getAsJsonArray("phases");int held=-1,firstDelivery=phases.size();
                for(int i=0;i<phases.size();i++)for(var leaf:AgentCompletionRules.leaves(phases.get(i).getAsJsonObject())) {
                    if(!leaf.has("item") || !leaf.get("item").getAsString().equals("aifun_gui_test:machine"))continue;
                    if(AgentTaskState.completionKind(leaf).equals("inventory") && leaf.get("count").getAsInt()==8)held=i;
                    if(AgentTaskState.completionKind(leaf).equals("transfer") && !leaf.get("to_maid").getAsBoolean())firstDelivery=Math.min(firstDelivery,i);
                }
                // A separate final cleanup phase is valid; required milestones still need distinct phases.
                return held>0 && held<firstDelivery && firstDelivery<phases.size() && task.completionStage==phases.size()-1;
            }
        }
        var runner=new Runner();helper.onEachTick(()->{if(runner.active)try{Thread.sleep(50);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();runner.restore();helper.fail("Interrupted");}});runner.begin();
    }
}
