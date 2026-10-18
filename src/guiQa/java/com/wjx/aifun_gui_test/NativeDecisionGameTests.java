package com.wjx.aifun_gui_test;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.site.AvailableSites;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.*;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.init.InitEntities;
import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import com.wjx.touhou_aifun.chat.ChatFlowManager;
import com.wjx.touhou_aifun.chat.agent.*;
import com.wjx.touhou_aifun.chat.agent.decision.*;
import com.wjx.touhou_aifun.compat.ai.openai.ReasoningCompatOpenAISite;
import com.wjx.touhou_aifun.config.TouhouAIFunConfig;
import com.wjx.touhou_aifun.maid.gui.MaidGuiSessionManager;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraftforge.gametest.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** One isolated batch runs scenarios sequentially, with local HTTP only and no player configuration writes. */
@GameTestHolder("aifun_gui_test")
@PrefixGameTestTemplate(false)
public final class NativeDecisionGameTests {
    private static JsonObject json(String value){return JsonParser.parseString(value).getAsJsonObject();}
    private static JsonObject alternative(String tool,JsonObject args) {var row=new JsonObject();row.addProperty("tool",tool);row.add("arguments",args);return row;}
    private static JsonObject step(String description,String tool,JsonObject args,JsonObject until) {
        var row=new JsonObject();row.addProperty("description",description);var alternatives=new JsonArray();alternatives.add(alternative(tool,args));row.add("alternatives",alternatives);if(until!=null) row.add("until",until.deepCopy());return row;
    }
    @GameTest(batch="nativeDecision",template="empty",templateNamespace="aifun_native_decision_test",timeoutTicks=60000)
    public static void nativeProvidersAndDeterministicPlansUseRealServerGuards(GameTestHelper helper) throws Exception {
        var configScope=new QaConfigScope();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var executor=Executors.newCachedThreadPool();server.setExecutor(executor);
        var hits=new AtomicInteger();var mode=new AtomicReference<>("high");var seen=new AtomicReference<JsonObject>();
        var release=new AtomicReference<>(new CountDownLatch(0));
        server.createContext("/decision",exchange->{
            var responseMode=mode.get();var responseGate=release.get();
            hits.incrementAndGet();var request=JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();seen.set(request);
            try {responseGate.await(5,TimeUnit.SECONDS);}catch(InterruptedException ignored){}
            var response=new JsonObject();response.add("model",request.get("model"));var answers=new JsonObject();response.add("answers",answers);
            request.getAsJsonObject("questions").entrySet().forEach(e->{
                var q=e.getValue().getAsJsonObject();var a=new JsonObject();a.add("type",q.get("type"));
                if(q.get("type").getAsString().equals("noul")) a.addProperty("noul",responseMode.equals("low")?.5:.99);
                else {
                    String selected=q.getAsJsonObject("criteria").keySet().iterator().next();a.addProperty("choice",selected);a.addProperty("confidence",responseMode.equals("low")?.3:.99);
                    var probabilities=new JsonObject();q.getAsJsonObject("criteria").keySet().forEach(k->probabilities.addProperty(k,k.equals(selected)?1:0));a.add("probabilities",probabilities);
                }answers.add(e.getKey(),a);
            });
            var bytes=response.toString().getBytes(StandardCharsets.UTF_8);
            try {exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);}finally{exchange.close();}
        });server.start();
        String siteId="native_decision_qa";var site=new ReasoningCompatOpenAISite(siteId,new net.minecraft.resources.ResourceLocation("test","icon"),"http://127.0.0.1/never",true,"",false,Map.of(),List.of(new com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.LLMOpenAISite.ModelEntry("mock")));
        var previous=AvailableSites.LLM_SITES.put(siteId,site);var owner=helper.makeMockPlayer();
        TouhouAIFunConfig.AGENT_RUNTIME.set(true);TouhouAIFunConfig.DECISION_SERVICE_ENABLED.set(true);
        TouhouAIFunConfig.DECISION_STARTUP_TEMPLATES.set(false);TouhouAIFunConfig.DECISION_MIN_CONFIDENCE.set(.9);
        TouhouAIFunConfig.DECISION_JEV_MODEL.set("jev-1.13.0");TouhouAIFunConfig.DECISION_PERPLEXITY_MODEL.set("pplx-decider-v1.1-27b");
        String endpoint="http://127.0.0.1:"+server.getAddress().getPort()+"/decision";
        TouhouAIFunConfig.DECISION_JEV_URL.set(endpoint);TouhouAIFunConfig.DECISION_PERPLEXITY_URL.set(endpoint);TouhouAIFunConfig.DECISION_JEV_KEY.set("mock");TouhouAIFunConfig.DECISION_PERPLEXITY_KEY.set("mock");TouhouAIFunConfig.DECISION_TIMEOUT_MS.set(1500);
        if(Boolean.getBoolean("aifun.nativeDecisionQa.configStress")) {
            int missing=0;
            for(int i=0;i<400;i++) {
                TouhouAIFunConfig.DECISION_SERVICE_PROVIDER.set(i%2==0?"jev":"perplexity");
                TouhouAIFunConfig.DECISION_TIMEOUT_MS.set(i%2==0?1500:1501);
                var snapshot=DecisionServiceSettings.snapshot();
                if(!snapshot.view().enabled() || !snapshot.view().endpoint().equals(endpoint) || !snapshot.apiKey().equals("mock")) {
                    missing++;System.out.println("NATIVE_QA config_drift="+i+" view="+snapshot.view()+" expectedKey="+snapshot.apiKey().equals("mock"));
                }
            }
            System.out.println("NATIVE_QA config_stress_missing="+missing);
            helper.assertTrue(missing==0,"Repeated configuration changes must preserve the native provider snapshot");
        }
        class FixtureMaid extends EntityMaid {
            boolean pinned;
            FixtureMaid(){super(InitEntities.MAID.get(),helper.getLevel());}
            @Override public net.minecraft.world.entity.LivingEntity getOwner(){return owner;}
            @Override public boolean isPushable(){return false;}
            @Override public void push(double x,double y,double z) { }
            @Override public void setPos(double x,double y,double z){if(!pinned)super.setPos(x,y,z);}
        }
        class Runner {
            int scenario,polls,startHits;long scenarioStart,notBefore;FixtureMaid maid;AgentTaskState state;TaskCallback task;AtomicInteger llm=new AtomicInteger();boolean changed,active=true;
            final List<String> diagnostics=new ArrayList<>();
            final JsonArray outcomes=new JsonArray();
            void diagnostic(String phase) throws Exception {
                var routerField=TaskCallback.class.getDeclaredField("decisionRouter");routerField.setAccessible(true);var router=routerField.get(task);
                var settingsField=TaskDecisionRouter.class.getDeclaredField("settings");settingsField.setAccessible(true);var settings=(DecisionServiceSettings.Snapshot)settingsField.get(router);
                diagnostics.add(phase+" scenario="+scenario+" configured="+DecisionServiceSettings.snapshot().view()+" captured="+settings.view()+" capturedKey="+!settings.apiKey().isBlank()+" pos="+maid.position());
                helper.assertTrue(settings.view().enabled() && settings.view().endpoint().equals(endpoint)
                        && settings.view().provider().equals(scenario==0?"jev":"perplexity")
                        && settings.apiKey().equals(scenario==9?"":"mock"),"Native fixture must capture its exact isolated settings before dispatch");
            }
            void cleanTask() {if(maid!=null){AgentRuntime.stop(maid,true);task.operations.cancel();ChatFlowManager.forgetMaid(maid.getUUID());maid.discard();maid=null;}}
            void restore() {
                active=false;release.get().countDown();cleanTask();server.stop(0);executor.shutdownNow();
                if(previous==null) AvailableSites.LLM_SITES.remove(siteId);else AvailableSites.LLM_SITES.put(siteId,previous);
                configScope.close();
            }
            void fail(Throwable error){diagnostics.forEach(s->System.out.println("NATIVE_QA "+s));restore();helper.fail("Native decision scenario "+scenario+": "+error);}
            void begin() {
                if(System.nanoTime()<notBefore){helper.runAfterDelay(1,this::begin);return;}
                try {
                    if(scenario==17) {
                        java.nio.file.Files.writeString(java.nio.file.Path.of("native-decision-results.json"),new GsonBuilder().setPrettyPrinting().create().toJson(outcomes),StandardCharsets.UTF_8);
                        System.out.println("NATIVE_QA all "+outcomes.size()+" exact scenarios passed");restore();helper.succeed();return;
                    }
                    polls=0;scenarioStart=System.nanoTime();changed=false;seen.set(null);llm.set(0);startHits=hits.get();
                    mode.set(scenario==2 || scenario==14?"low":"high");release.set(new CountDownLatch(scenario>=3 && scenario<=5 || scenario==7 || scenario==12?1:0));
                    TouhouAIFunConfig.DECISION_SERVICE_PROVIDER.set(scenario==0?"jev":"perplexity");
                    TouhouAIFunConfig.DECISION_TIMEOUT_MS.set(scenario==5?100:1500);
                    TouhouAIFunConfig.DECISION_PERPLEXITY_KEY.set(scenario==9?"":"mock");
                    maid=new FixtureMaid();
                    helper.setBlock(new BlockPos(2,0,2),Blocks.STONE);
                    var ownerPos=helper.absolutePos(new BlockPos(2,1,2));owner.setPos(ownerPos.getX()+.5,ownerPos.getY(),ownerPos.getZ()+.5);
                    var start=helper.absolutePos(new BlockPos(2,1,2));maid.setPos(start.getX()+.5,start.getY(),start.getZ()+.5);maid.setOwnerUUID(owner.getUUID());maid.setNoAi(true);maid.setNoGravity(true);maid.setHomeModeEnable(true);maid.restrictTo(start,16);helper.getLevel().addFreshEntity(maid);
                    maid.pinned=true;
                    var manager=maid.getAiChatManager();manager.llmSite=siteId;manager.llmModel="mock";
                    var getEntry=AgentRuntime.class.getDeclaredMethod("entry",EntityMaid.class);getEntry.setAccessible(true);var entry=getEntry.invoke(null,maid);
                    var field=entry.getClass().getDeclaredField("queue");field.setAccessible(true);var queue=(AgentTaskQueue)field.get(entry);state=queue.enqueue("Follow owner");queue.next();
                    task=new TaskCallback(manager,new ArrayList<>(),state);var fresh=TaskCallback.class.getDeclaredField("freshWorld");fresh.setAccessible(true);fresh.setBoolean(task,true);
                    diagnostic("begin");
                    var callback=entry.getClass().getDeclaredField("callback");callback.setAccessible(true);callback.set(entry,task);
                    task.defineCompletion(json("{\"kind\":\"maid_state\",\"dimension\":\"minecraft:overworld\",\"state\":\"follow\",\"value\":\"true\"}"));
                    state.steps=List.of("Follow owner");state.actionSteps=new JsonArray();state.actionSteps.add(step("Follow owner","switch_follow_state",new JsonObject(),null));state.actionPlanGoalVersion=state.goalVersion;
                    if(scenario==6 || scenario==10 || scenario==12 || scenario==13 || scenario==14 || scenario==15 || scenario==16) fourChests();
                    if(scenario==10 || scenario==12 || scenario==13 || scenario==14 || scenario==15 || scenario==16) {
                        if(scenario==13) {
                            var c=state.completion.getAsJsonArray("conditions").get(0).getAsJsonObject().deepCopy();state.completion=null;task.defineCompletion(c);
                        }
                        state.clearActionPlan();fresh.setBoolean(task,false);
                    }
                    if(scenario==10)state.steps=List.of("Collect the required materials","Next owner phase","Final owner phase");
                    if(scenario==11)state.clearActionPlan();
                    if(scenario==15) {
                        var pos=helper.absolutePos(new BlockPos(1,1,1));var args=new JsonObject();args.addProperty("x",pos.getX());args.addProperty("y",pos.getY());args.addProperty("z",pos.getZ());args.addProperty("wait_policy","AUTO");
                        var opened=MaidGuiSessionManager.call(task,"open_gui","existing-stage-menu",args).getNow(null);helper.assertTrue(opened!=null && !opened.has("error"),"Existing authorized stage menu opens synchronously");
                    }
                    if(scenario==16) {
                        var incoming=state.completion.deepCopy();var outgoing=incoming.deepCopy();outgoing.getAsJsonArray("conditions").forEach(c->c.getAsJsonObject().addProperty("to_maid",false));
                        var phases=new JsonArray();phases.add(incoming);phases.add(outgoing);var sequence=new JsonObject();sequence.addProperty("kind","sequence");sequence.add("phases",phases);
                        fresh.setBoolean(task,true);state.completion=null;task.defineCompletion(sequence);fresh.setBoolean(task,false);
                    }
                    if(scenario==8) {
                        helper.setBlock(new BlockPos(2,0,3),net.minecraftforge.registries.ForgeRegistries.BLOCKS.getValue(new net.minecraft.resources.ResourceLocation("touhou_little_maid","snack_cabinet")));helper.setBlock(new BlockPos(2,1,3),Blocks.CAKE);
                        var condition=json("{\"kind\":\"eat_food\",\"dimension\":\"minecraft:overworld\",\"food\":\"minecraft:cake\",\"count\":1,\"finished\":true}");
                        var pos=helper.absolutePos(new BlockPos(2,1,3));var xyz=new JsonArray();xyz.add(pos.getX());xyz.add(pos.getY());xyz.add(pos.getZ());condition.add("position",xyz);
                        state.completion=null;task.defineCompletion(condition);state.goal="Finish the placed cake";state.actionSteps=new JsonArray();
                        state.actionSteps.add(step("Finish the cake","eat_food_block",json("{\"food\":\"minecraft:cake\",\"max_distance\":12,\"until_finished\":true}"),condition));
                    }
                    // Production dequeues after entity ticks; let this synthetic spawn settle first too.
                    helper.runAfterDelay(2,()->{try {diagnostic("dispatch");if(scenario==9)TouhouAIFunConfig.DECISION_PERPLEXITY_KEY.set("qa-diagnostic-secret");task.next(ignored->llm.incrementAndGet());poll();}catch(Exception error){fail(error);}});
                }catch(Throwable error){fail(error);}
            }
            void fourChests() {
                var conditions=new JsonArray();var steps=new JsonArray();Item[] items={Items.IRON_INGOT,Items.GOLD_INGOT,Items.DIAMOND,Items.EMERALD};
                BlockPos[] positions={new BlockPos(1,1,1),new BlockPos(3,1,1),new BlockPos(1,1,3),new BlockPos(3,1,3)};
                for(var floor:BlockPos.betweenClosed(new BlockPos(0,0,0),new BlockPos(5,0,5))) helper.setBlock(floor,Blocks.STONE);
                for(int i=0;i<4;i++) {
                    helper.setBlock(positions[i],Blocks.CHEST);var chest=(ChestBlockEntity)helper.getBlockEntity(positions[i]);chest.clearContent();
                    chest.setItem(0,new ItemStack(items[i],2));chest.setItem(1,new ItemStack(items[i],3));chest.setItem(2,new ItemStack(Items.COOKED_BEEF,4));
                    var pos=helper.absolutePos(positions[i]);var condition=json("{\"kind\":\"transfer\",\"dimension\":\"minecraft:overworld\",\"item\":\""+net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(items[i])+"\",\"count\":5,\"to_maid\":true}");
                    var xyz=new JsonArray();xyz.add(pos.getX());xyz.add(pos.getY());xyz.add(pos.getZ());condition.add("position",xyz);conditions.add(condition);
                    var open=new JsonObject();open.addProperty("target","minecraft:chest");open.addProperty("x",pos.getX());open.addProperty("y",pos.getY());open.addProperty("z",pos.getZ());open.addProperty("wait_policy","AUTO");steps.add(step("Open exact chest","open_gui",open,null));
                    steps.add(step("Receive required quantity","gui_action",json("{\"session_id\":\"$live.session_id\",\"action\":\"transfer\",\"slot\":\"$live.from_slot_"+i+"\",\"to_slot\":\"$live.to_slot_"+i+"\",\"count\":\"$live.transfer_count_"+i+"\",\"close_after\":false,\"complete_task\":false}"),condition));
                    steps.add(step("Close current chest","close_gui",json("{\"session_id\":\"$live.session_id\"}"),null));
                }
                var completion=new JsonObject();completion.addProperty("kind","all");completion.add("conditions",conditions);state.completion=null;task.defineCompletion(completion);
                state.goal="Receive exactly five of each of four items from four exact chests; leave beef untouched and close menus";state.actionSteps=ActionPlan.validate(steps);state.actionStep=0;state.steps=List.of("All four chests");
                // Probe associations against an actual menu before the full automatic plan starts.
                var open=steps.get(0).getAsJsonObject().getAsJsonArray("alternatives").get(0).getAsJsonObject().getAsJsonObject("arguments");
                var opened=MaidGuiSessionManager.call(task,"open_gui","guard-probe",open).getNow(null);
                helper.assertTrue(opened!=null && !opened.has("error"),"Nearby guard-probe menu opens synchronously");
                var action=DecisionQuestionCompiler.resolve(steps.get(1).getAsJsonObject().getAsJsonArray("alternatives").get(0).getAsJsonObject().get("arguments"),task.decisionLiveState().getAsJsonObject("bindings")).getAsJsonObject();
                helper.assertTrue(task.validateDecisionAction("gui_action",action),"Correct live transfer arguments match the full contract");
                var excess=action.deepCopy();excess.addProperty("count",6);helper.assertTrue(!task.validateDecisionAction("gui_action",excess),"Automatic transfer cannot exceed the contract");
                var wrong=action.deepCopy();wrong.addProperty("slot",2);wrong.addProperty("count",1);helper.assertTrue(!task.validateDecisionAction("gui_action",wrong),"Unrelated item cannot be substituted");
                var batch=new JsonObject();batch.add("session_id",action.get("session_id"));var actions=new JsonArray();for(int i=0;i<3;i++)actions.add(action.deepCopy());batch.add("actions",actions);
                helper.assertTrue(!task.validateDecisionAction("gui_batch",batch),"Aggregate automatic batch quantity cannot exceed the contract");
                var close=new JsonObject();close.add("session_id",action.get("session_id"));MaidGuiSessionManager.call(task,"close_gui","guard-close",close);
            }
            void poll() {
                if(!active)return;
                try {
                    polls++;
                    if(scenario==10 && !state.transfers.isEmpty() && state.transfers.size()<4)helper.assertTrue(state.currentStep==0,"Automatic GUI substeps cannot advance the owner text plan to a future phase");
                    // GameTestServer runs ticks faster than wall time; HTTP deadlines use a real clock.
                    if(System.nanoTime()-scenarioStart>TimeUnit.SECONDS.toNanos(10)) throw new AssertionError("Timed out; LLM="+llm.get()+" status="+state.status+" failures="+state.failures+" requested_live="+(seen.get()==null?null:seen.get().getAsJsonObject("state").get("live"))+" current_live="+task.decisionLiveState()+" decisions="+AgentArchiveData.get(helper.getLevel()).task(maid.getUUID(),state.id).records.stream().filter(r->r.kind().startsWith("decision")).map(AgentTaskArchive.Record::summary).toList());
                    if(seen.get()!=null && !changed && (scenario>=3 && scenario<=4 || scenario==7 || scenario==12)) {
                        changed=true;
                        if(scenario==3 || scenario==12) state.amend("Sit instead of following");
                        else if(scenario==4) {state.pause("test_pause");task.operations.cancel();}
                        else {maid.getAvailableBackpackInv().setStackInSlot(0,new ItemStack(Items.DIAMOND));maid.pinned=false;maid.setPos(maid.getX()+.5,maid.getY(),maid.getZ());maid.pinned=true;}
                        release.get().countDown();
                    }
                    if((scenario==0 || scenario==1 || scenario==4) && llm.get()>0)throw new AssertionError("Unexpected execution-LLM fallback in native success/cancellation scenario");
                    boolean done=switch(scenario){case 0,1,6,8,10,14,15,16->state.status==AgentTaskState.Status.completed;case 2,3,5,7,9,12,13->llm.get()>0;case 4->changed && polls>=15;case 11->llm.get()>=3;default->false;};
                    if(!done){if(scenario==11 && llm.get()>0)task.next(ignored->llm.incrementAndGet());helper.runAfterDelay(1,this::poll);return;}
                    if(scenario==0 || scenario==1) {
                        helper.assertTrue(!maid.isHomeModeEnable() && llm.get()==0 && hits.get()==startHits+1,"Native selection changes actual follow state and finishes without execution-LLM round");
                        helper.assertTrue(seen.get().get("model").getAsString().equals(scenario==0?"jev-1.13.0":"pplx-decider-v1.1-27b"),"Correct native model/profile");
                    }else if(scenario>=2 && scenario<=5 || scenario==7) {
                        helper.assertTrue(maid.isHomeModeEnable(),"Uncertain, amended, cancelled or timed-out decisions cannot mutate the maid");
                        helper.assertTrue(llm.get()==(scenario==4?0:1),"Exactly one fallback, or none after pause");
                        var reasons=AgentArchiveData.get(helper.getLevel()).task(maid.getUUID(),state.id).records.stream()
                                .filter(r->r.kind().equals("decision_fallback")).map(AgentTaskArchive.Record::summary).toList();
                        String expected=switch(scenario){case 2->"uncertain_or_none";case 3,7->"stale_observation";case 5->"timeout";default->null;};
                        if(expected!=null)helper.assertTrue(reasons.equals(List.of(expected)),"Expected exact decision fallback "+expected+", got "+reasons);
                        if(scenario!=5)helper.assertTrue(hits.get()==startHits+1,"Guard scenario must receive its native HTTP request");
                        if(scenario==4)helper.assertTrue(task.operations.cancelled() && task.operations.empty(),"Pause cancels and drains owned HTTP operations");
                        if(scenario==5)helper.assertTrue(task.operations.empty(),"Timeout settles the owned original HTTP operation");
                    }else if(scenario==6) {
                        helper.assertTrue(llm.get()==0 && hits.get()==startHits && state.verifiedCount==20,"Four chests complete through exact repeated transfers without any decision/LLM request");
                        helper.assertTrue(MaidGuiSessionManager.session(maid.getUUID())==null && maid.getMainHandItem().isEmpty(),"Final GUI settles and hands remain empty");
                        for(var pos:List.of(new BlockPos(1,1,1),new BlockPos(3,1,1),new BlockPos(1,1,3),new BlockPos(3,1,3))) helper.assertTrue(((ChestBlockEntity)helper.getBlockEntity(pos)).getItem(2).getCount()==4,"Unrelated beef untouched");
                    }else if(scenario==10 || scenario==14 || scenario==15 || scenario==16) {
                        helper.assertTrue(llm.get()==0 && hits.get()==startHits+(scenario==16?6:scenario==15?2:3) && state.verifiedCount==20,"Existing text plan automatically hands off finite container choices without startup planning or extra Flash rounds");
                        helper.assertTrue(MaidGuiSessionManager.session(maid.getUUID())==null && state.transfers.values().stream().allMatch(n->n==(scenario==16?0L:5L)),"Exactly five real items per container, closed menus, no replay");
                        helper.assertTrue(seen.get().getAsJsonObject("questions").has("next_container") && !TouhouAIFunConfig.DECISION_STARTUP_TEMPLATES.get(),"Native requests are existing-stage decisions, never startup templates");
                    }else if(scenario==11 || scenario==13) {
                        task.finishTiming(false);
                        var records=AgentArchiveData.get(helper.getLevel()).task(maid.getUUID(),state.id).records.stream().filter(r->r.kind().equals("decision_service_diagnostic")).toList();
                        helper.assertTrue(hits.get()==startHits && records.size()==1,"Enabled but unused service emits one durable diagnostic without a probe request");
                        String raw=task.results.snapshot().values().get(records.get(0).resultRef());
                        helper.assertTrue(raw.contains(scenario==11?"no_supported_pending_transfer_phase":"single_choice_no_service_needed") && !raw.contains(endpoint) && !raw.contains("mock"),"Diagnostic explains bypass without secrets");
                    }else if(scenario==12) {
                        helper.assertTrue(llm.get()==1 && hits.get()==startHits+1 && state.transfers.isEmpty(),"Owner amendment invalidates pending automatic stage selection before any transfer");
                    }else if(scenario==8) {
                        helper.assertTrue(llm.get()==0 && hits.get()==startHits && state.actionFacts.size()==1 && state.actionFacts.get(0).get("servings_consumed").getAsInt()==7,"Eating completes only after seven actual servings, without another model round");
                        helper.assertTrue(helper.getBlockState(new BlockPos(2,1,3)).isAir(),"Whole cake physically consumed");
                        helper.setBlock(new BlockPos(2,1,3),Blocks.CAKE);
                        helper.assertTrue(!AgentCompletion.verifyPredicate(maid,state,state.completion,true),"A new cake cannot be completed using the previous finished receipt");
                        helper.setBlock(new BlockPos(2,1,3),Blocks.AIR);
                    }else {
                        helper.assertTrue(maid.isHomeModeEnable() && hits.get()==startHits && llm.get()==1,"Missing credentials fall back once before HTTP or mutation");
                        var reasons=AgentArchiveData.get(helper.getLevel()).task(maid.getUUID(),state.id).records.stream()
                                .filter(r->r.kind().equals("decision_fallback")).map(AgentTaskArchive.Record::summary).toList();
                        helper.assertTrue(reasons.equals(List.of("service_unavailable")),"Missing credential fallback must be distinguished from HTTP timeout or low confidence");
                        var diagnostics=AgentArchiveData.get(helper.getLevel()).task(maid.getUUID(),state.id).records.stream().filter(r->r.kind().equals("decision_service_diagnostic")).toList();
                        helper.assertTrue(diagnostics.size()==1,"Unavailable snapshot records one durable diagnostic");
                        String page=task.results.read(diagnostics.get(0).resultRef(),0);String raw=page.substring(page.indexOf('\n')+1);var detail=json(raw);
                        helper.assertTrue(!raw.contains("qa-diagnostic-secret") && !raw.contains(endpoint),"Diagnostic never retains credentials or endpoint URL");
                        helper.assertTrue(!detail.getAsJsonObject("captured").get("has_key").getAsBoolean() && detail.getAsJsonObject("current").get("has_key").getAsBoolean()
                                && detail.get("request_attempts").getAsInt()==0 && detail.getAsJsonArray("changed_fields").contains(new JsonPrimitive("key")),"Diagnostic identifies a credential change after snapshot capture, before HTTP");
                    }
                    var row=new JsonObject();row.addProperty("scenario",scenario);
                    row.addProperty("case",List.of("jev_native_action","perplexity_native_action","low_confidence","owner_amendment","pause_cancellation","http_timeout","four_chest_exact_transfers","stale_world","whole_cake_receipt","missing_credentials","automatic_existing_stage","enabled_but_unused_diagnostic","automatic_stage_amendment","single_stage_no_probe","equivalent_schedule_low_preference_confidence","reuse_existing_stage_menu","ordered_roundtrip_stage_receipts").get(scenario));
                    row.addProperty("status",state.status.name());row.addProperty("http_requests",hits.get()-startHits);row.addProperty("execution_llm_calls",llm.get());
                    row.add("decision_fallbacks",new Gson().toJsonTree(AgentArchiveData.get(helper.getLevel()).task(maid.getUUID(),state.id).records.stream().filter(r->r.kind().equals("decision_fallback")).map(AgentTaskArchive.Record::summary).toList()));
                    row.addProperty("verified_count",state.verifiedCount);row.addProperty("passed",true);outcomes.add(row);
                    release.get().countDown();cleanTask();scenario++;notBefore=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(scenario>=10?1100:0);helper.runAfterDelay(2,this::begin);
                }catch(Throwable error){fail(error);}
            }
        }
        var runner=new Runner();
        // Native HTTP uses wall-clock deadlines and a shared 10/s gate. Headless ticks otherwise
        // run much faster than real gameplay; space fixture scenarios without changing that gate.
        helper.onEachTick(()->{if(runner.active)try{Thread.sleep(10);}catch(InterruptedException error){Thread.currentThread().interrupt();runner.fail(error);}});
        runner.begin();
    }
}
