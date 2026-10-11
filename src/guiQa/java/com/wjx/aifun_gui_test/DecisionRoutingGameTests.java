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
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraftforge.gametest.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Local native HTTP, real menus and authoritative receipts. Includes startup without an LLM-supplied plan. */
@GameTestHolder("aifun_gui_test")
@PrefixGameTestTemplate(false)
public final class DecisionRoutingGameTests {
    private static JsonObject json(String text){return JsonParser.parseString(text).getAsJsonObject();}
    private static JsonObject step(String tool,JsonObject args,JsonObject until){var row=new JsonObject();row.addProperty("description",tool);var alternatives=new JsonArray();var action=new JsonObject();action.addProperty("tool",tool);action.add("arguments",args);alternatives.add(action);row.add("alternatives",alternatives);if(until!=null)row.add("until",until);return row;}
    @GameTest(batch="decisionRouting",template="empty",templateNamespace="aifun_gui_test",timeoutTicks=120000)
    public static void templatesBindingsAndBoundedRecoveryUseRealWorldState(GameTestHelper helper) throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var workers=Executors.newCachedThreadPool();server.setExecutor(workers);
        var scenario=new AtomicInteger();var hits=new AtomicInteger();var seen=new AtomicReference<JsonObject>();var release=new AtomicReference<>(new CountDownLatch(0));
        server.createContext("/decide",exchange->{
            int mode=scenario.get();hits.incrementAndGet();var request=JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();seen.set(request);
            try {Thread.sleep(150);release.get().await(5,TimeUnit.SECONDS);}catch(InterruptedException ignored) { }
            var answers=new JsonObject();request.getAsJsonObject("questions").entrySet().forEach(e->{
                var q=e.getValue().getAsJsonObject();var a=new JsonObject();a.add("type",q.get("type"));
                if(q.get("type").getAsString().equals("noul")) {
                    boolean yes=true;
                    if(e.getKey().startsWith("target_")) {
                        yes=false;for(var target:request.getAsJsonObject("state").getAsJsonArray("target_candidates")){var row=target.getAsJsonObject();if(e.getKey().equals("target_"+row.get("id").getAsString()))yes=row.has("labels") && row.get("labels").toString().contains(mode==3?"Destination":"Source");}
                    }
                    a.addProperty("noul",mode==7?.5:yes?.99:.01);
                }else {
                    var criteria=q.getAsJsonObject("criteria");String selected=criteria.keySet().iterator().next();
                    if(e.getKey().equals("template"))selected=switch(mode){case 1->"sit";case 2,8,9,17->"receive";case 3->"deliver";case 6->"schedule";case 12,15->"contract";case 16->"work_task";default->"follow";};
                    else if(e.getKey().equals("state_value"))selected="true";
                    else if(e.getKey().equals("quantity_scope"))selected=mode==17?"total":"per_target";
                    else if(e.getKey().startsWith("item_")) {
                        int i=Integer.parseInt(e.getKey().substring(5));String item=request.getAsJsonObject("state").getAsJsonArray("item_candidates").get(i).getAsString();
                        selected=mode==17?(item.equals("minecraft:egg")?"n_32":"skip"):item.equals("minecraft:gold_ingot")?(mode==8?"unknown":mode==3?"n_4":"n_5"):"skip";
                    } else if(e.getKey().equals("recovery"))selected="pause";
                    else if(e.getKey().equals("schedule"))selected=criteria.keySet().stream().filter(k->k.equalsIgnoreCase("night")).findFirst().orElse(selected);
                    else if(e.getKey().equals("work_task"))selected=criteria.keySet().stream().filter(k->k.equals("touhou_little_maid:farm")).findFirst().orElse(selected);
                    else if(e.getKey().equals("action")) {
                        selected=criteria.entrySet().stream().filter(c->c.getValue().getAsString().contains("Source") || c.getValue().getAsString().contains("entity_uuid")).map(Map.Entry::getKey).filter(k->k.startsWith("a")).findFirst().orElse(selected);
                    }
                    if(!criteria.has(selected))throw new AssertionError("Missing test choice "+selected+" in "+e.getKey());
                    a.addProperty("choice",selected);a.addProperty("confidence",mode==7?.5:.99);var probabilities=new JsonObject();String choice=selected;criteria.keySet().forEach(k->probabilities.addProperty(k,k.equals(choice)?1:0));a.add("probabilities",probabilities);
                }answers.add(e.getKey(),a);
            });
            var response=new JsonObject();response.add("model",request.get("model"));response.add("answers",answers);var bytes=response.toString().getBytes(StandardCharsets.UTF_8);
            try {exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);}finally{exchange.close();}
        });server.start();
        boolean oldEnabled=TouhouAIFunConfig.DECISION_SERVICE_ENABLED.get(),oldRuntime=TouhouAIFunConfig.AGENT_RUNTIME.get();String oldProvider=TouhouAIFunConfig.DECISION_SERVICE_PROVIDER.get(),oldUrl=TouhouAIFunConfig.DECISION_JEV_URL.get(),oldKey=TouhouAIFunConfig.DECISION_JEV_KEY.get(),oldPUrl=TouhouAIFunConfig.DECISION_PERPLEXITY_URL.get(),oldPKey=TouhouAIFunConfig.DECISION_PERPLEXITY_KEY.get();int oldTimeout=TouhouAIFunConfig.DECISION_TIMEOUT_MS.get();
        String endpoint="http://127.0.0.1:"+server.getAddress().getPort()+"/decide";TouhouAIFunConfig.AGENT_RUNTIME.set(true);TouhouAIFunConfig.DECISION_SERVICE_ENABLED.set(true);TouhouAIFunConfig.DECISION_JEV_URL.set(endpoint);TouhouAIFunConfig.DECISION_JEV_KEY.set("mock");TouhouAIFunConfig.DECISION_PERPLEXITY_URL.set(endpoint);TouhouAIFunConfig.DECISION_PERPLEXITY_KEY.set("mock");TouhouAIFunConfig.DECISION_TIMEOUT_MS.set(2500);
        String siteId="decision_routing_qa";var site=new ReasoningCompatOpenAISite(siteId,new net.minecraft.resources.ResourceLocation("test","icon"),"http://127.0.0.1/never",true,"",false,Map.of(),List.of(new com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.LLMOpenAISite.ModelEntry("mock")));var previous=AvailableSites.LLM_SITES.put(siteId,site);var owner=helper.makeMockPlayer();
        class FixtureMaid extends EntityMaid {boolean pinned;FixtureMaid(){super(InitEntities.MAID.get(),helper.getLevel());}@Override public net.minecraft.world.entity.LivingEntity getOwner(){return owner;}@Override public boolean isPushable(){return false;}@Override public void push(double x,double y,double z){}@Override public void setPos(double x,double y,double z){if(!pinned)super.setPos(x,y,z);}}
        class Runner {
            FixtureMaid maid;AgentTaskState task;TaskCallback callback;AtomicInteger llm=new AtomicInteger();long start;int startHits;boolean changed,active=true;net.minecraft.world.entity.Entity cart;
            Map<String,com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ITool<?>> originalTools;int reinspections;
            BlockPos[] chests={new BlockPos(1,1,1),new BlockPos(3,1,1),new BlockPos(4,1,3)};
            void cleanup(){if(maid!=null){callback.operations.cancel();AgentRuntime.stop(maid,true);ChatFlowManager.forgetMaid(maid.getUUID());maid.discard();maid=null;}if(cart!=null){cart.discard();cart=null;}if(originalTools!=null){try{var field=com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ToolRegister.class.getDeclaredField("TOOLS");field.setAccessible(true);field.set(null,originalTools);originalTools=null;}catch(Exception error){throw new RuntimeException(error);}}}
            void forceRepeatedStaleInspections() throws Exception {
                var field=com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ToolRegister.class.getDeclaredField("TOOLS");field.setAccessible(true);originalTools=(Map<String,com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ITool<?>>)field.get(null);var tools=new LinkedHashMap<>(originalTools);
                var delegate=(com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ITool<JsonObject>)tools.get("inspect_gui");
                tools.put("inspect_gui",new com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ITool<JsonObject>() {
                    public String id(){return delegate.id();}
                    public String summary(EntityMaid m){return delegate.summary(m);}
                    public com.mojang.serialization.Codec<JsonObject> codec(){return delegate.codec();}
                    public com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.Parameter parameters(com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.ObjectParameter root,EntityMaid m){return delegate.parameters(root,m);}
                    public com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback onCall(String id,JsonObject args,com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback c){return delegate.onCall(id,args,c);}
                    public CompletableFuture<com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback> onCallAsync(String id,JsonObject args,com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback c,LLMClient client){return delegate.onCallAsync(id,args,c,client).thenApply(result->{reinspections++;chest(0).setItem(1,new ItemStack(Items.GOLD_INGOT,chest(0).getItem(1).getCount()+1));return result;});}
                });field.set(null,Map.copyOf(tools));
            }
            void restore(){active=false;release.get().countDown();cleanup();server.stop(0);workers.shutdownNow();if(previous==null)AvailableSites.LLM_SITES.remove(siteId);else AvailableSites.LLM_SITES.put(siteId,previous);TouhouAIFunConfig.DECISION_SERVICE_ENABLED.set(oldEnabled);TouhouAIFunConfig.AGENT_RUNTIME.set(oldRuntime);TouhouAIFunConfig.DECISION_SERVICE_PROVIDER.set(oldProvider);TouhouAIFunConfig.DECISION_JEV_URL.set(oldUrl);TouhouAIFunConfig.DECISION_JEV_KEY.set(oldKey);TouhouAIFunConfig.DECISION_PERPLEXITY_URL.set(oldPUrl);TouhouAIFunConfig.DECISION_PERPLEXITY_KEY.set(oldPKey);TouhouAIFunConfig.DECISION_TIMEOUT_MS.set(oldTimeout);}
            void fail(Throwable error){restore();helper.fail("Decision routing scenario "+scenario.get()+": "+error);}
            ChestBlockEntity chest(int i){return (ChestBlockEntity)helper.getBlockEntity(chests[i]);}
            JsonObject coordinates(BlockPos local){var pos=helper.absolutePos(local);var args=new JsonObject();args.addProperty("x",pos.getX());args.addProperty("y",pos.getY());args.addProperty("z",pos.getZ());args.addProperty("target","minecraft:chest");args.addProperty("wait_policy","AUTO");return args;}
            JsonObject transferCondition(){var args=coordinates(chests[0]);var c=json("{\"kind\":\"transfer\",\"dimension\":\"minecraft:overworld\",\"item\":\"minecraft:gold_ingot\",\"count\":5,\"to_maid\":true}");var p=new JsonArray();for(String axis:List.of("x","y","z"))p.add(args.get(axis));c.add("position",p);return c;}
            void label(int index,String text){var p=chests[index].north();var support=helper.absolutePos(chests[index]);var worldSign=helper.absolutePos(p);var facing=Direction.fromDelta(worldSign.getX()-support.getX(),0,worldSign.getZ()-support.getZ());helper.setBlock(p,Blocks.OAK_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING,facing));var sign=(SignBlockEntity)helper.getBlockEntity(p);sign.setText(sign.getFrontText().setMessage(0,Component.literal(text)),true);}
            void begin(){try {
                if(scenario.get()==18){restore();helper.succeed();return;}changed=false;reinspections=0;llm.set(0);seen.set(null);startHits=hits.get();start=System.nanoTime();release.set(new CountDownLatch(scenario.get()==9?1:0));TouhouAIFunConfig.DECISION_SERVICE_PROVIDER.set(scenario.get()%2==0?"jev":"perplexity");
                for(var p:BlockPos.betweenClosed(new BlockPos(0,0,0),new BlockPos(5,0,5)))helper.setBlock(p,Blocks.STONE);
                for(int i=0;i<chests.length;i++){helper.setBlock(chests[i],Blocks.CHEST);chest(i).clearContent();if(scenario.get()!=3){chest(i).setItem(0,new ItemStack(Items.GOLD_INGOT,2));chest(i).setItem(1,new ItemStack(Items.GOLD_INGOT,3));}chest(i).setItem(2,new ItemStack(Items.COOKED_BEEF,4));label(i,i==2?"Excluded; never move these items":(scenario.get()==3?"Destination ":"Source ")+(i+1));}
                if(scenario.get()==17)for(int i=0;i<3;i++){chest(i).setItem(3,new ItemStack(Items.EGG,16));chest(i).setItem(4,new ItemStack(Items.EGG,16));}
                maid=new FixtureMaid();var pos=helper.absolutePos(new BlockPos(2,1,2));owner.setPos(pos.getX()+.5,pos.getY(),pos.getZ()+.5);maid.setPos(pos.getX()+.5,pos.getY(),pos.getZ()+.5);maid.setOwnerUUID(owner.getUUID());maid.setNoAi(true);maid.setNoGravity(true);maid.getSchedulePos().setHomeModeEnable(maid,pos);maid.setHomeModeEnable(true);maid.restrictTo(pos,16);helper.getLevel().addFreshEntity(maid);maid.pinned=true;maid.getAiChatManager().llmSite=siteId;maid.getAiChatManager().llmModel="mock";
                String goal=switch(scenario.get()){case 1->"坐下";case 2->"从Source两个箱子每箱取5个金锭，牛肉和Excluded箱子都不动";case 3->"向Destination两个箱子每箱放4个金锭，其他物品不动";case 4->"从Source1取5个金锭";case 5->"从Source1取5个金锭";case 6->"把工作时间改为夜班";case 16->"切换到种田工作模式，不需要现在种田";case 8->"从Source箱子取一些金锭";case 9->"从Source箱子每箱取5个金锭";default->"Follow owner";};
                var getEntry=AgentRuntime.class.getDeclaredMethod("entry",EntityMaid.class);getEntry.setAccessible(true);var entry=getEntry.invoke(null,maid);var queueField=entry.getClass().getDeclaredField("queue");queueField.setAccessible(true);var queue=(AgentTaskQueue)queueField.get(entry);task=queue.enqueue(goal);queue.next();callback=new TaskCallback(maid.getAiChatManager(),new ArrayList<>(),task);var field=entry.getClass().getDeclaredField("callback");field.setAccessible(true);field.set(entry,callback);
                if(scenario.get()==17)task.goal="从Source两个箱子总共取两组鸡蛋，金锭、牛肉和Excluded箱子都不动";
                // Keep all observed candidates deterministic, while dispatch/open/move/receipts are real.
                var scan=json("{\"dimension\":\"minecraft:overworld\",\"game_tick\":0}");scan.addProperty("game_tick",helper.getLevel().getGameTime());scan.add("maid_position_at_scan_start",new Gson().toJsonTree(new int[]{pos.getX(),pos.getY(),pos.getZ()}));var blocks=new JsonArray();scan.add("important_blocks",blocks);var signs=new JsonArray();scan.add("sign_texts",signs);
                for(int i=0;i<chests.length;i++){var absolute=helper.absolutePos(chests[i]);var row=json("{\"registry_id\":\"minecraft:chest\"}");row.add("relative_position",new Gson().toJsonTree(new int[]{absolute.getX()-pos.getX(),absolute.getY()-pos.getY(),absolute.getZ()-pos.getZ()}));blocks.add(row);var sign=(SignBlockEntity)helper.getBlockEntity(chests[i].north());var s=new JsonObject();var front=new JsonArray();var back=new JsonArray();for(var line:sign.getFrontText().getMessages(false))front.add(line.getString());for(var line:sign.getBackText().getMessages(false))back.add(line.getString());s.add("front_lines",front);s.add("back_lines",back);s.add("relative_position",new Gson().toJsonTree(new int[]{helper.absolutePos(chests[i].north()).getX()-pos.getX(),helper.absolutePos(chests[i].north()).getY()-pos.getY(),helper.absolutePos(chests[i].north()).getZ()-pos.getZ()}));var attached=json("{\"registry_id\":\"minecraft:chest\"}");attached.add("position",new Gson().toJsonTree(new int[]{absolute.getX(),absolute.getY(),absolute.getZ()}));s.add("attached_block",attached);signs.add(s);}
                var fresh=TaskCallback.class.getDeclaredField("freshWorld");fresh.setAccessible(true);fresh.setBoolean(callback,true);var observations=TaskCallback.class.getDeclaredField("decisionObservations");observations.setAccessible(true);((Map<String,JsonObject>)observations.get(callback)).put("scan_surroundings",scan);
                if(scenario.get()==3)maid.getAvailableBackpackInv().setStackInSlot(0,new ItemStack(Items.GOLD_INGOT,8));
                if(Set.of(4,14,15).contains(scenario.get())){callback.defineCompletion(transferCondition());var opened=MaidGuiSessionManager.call(callback,"open_gui","partial_open",coordinates(chests[0])).getNow(null);if(opened==null || opened.has("error"))throw new AssertionError("Open partial fixture");var bindings=callback.decisionLiveState().getAsJsonObject("bindings");var action=json("{\"action\":\"transfer\",\"count\":2}");action.add("slot",bindings.get("from_slot_0"));action.add("to_slot",bindings.get("to_slot_0"));action.addProperty("session_id",MaidGuiSessionManager.session(maid.getUUID()).id.toString());var partial=MaidGuiSessionManager.call(callback,"gui_action","partial_move",action).getNow(null);if(partial==null || partial.has("error"))throw new AssertionError("Move initial two: "+partial);chest(0).setItem(1,new ItemStack(Items.GOLD_INGOT,4));task.actionSteps=TaskTemplatePlanner.fromCompletion(task.completion).steps();task.actionStep=1;task.actionPlanGoalVersion=task.goalVersion;if(scenario.get()==14)forceRepeatedStaleInspections();if(scenario.get()==15){var session=MaidGuiSessionManager.session(maid.getUUID());var close=new JsonObject();close.addProperty("session_id",session.id.toString());MaidGuiSessionManager.call(callback,"close_gui","resume_close",close);callback.operations.cancel();task.generation++;callback=new TaskCallback(maid.getAiChatManager(),new ArrayList<>(),task);fresh.setBoolean(callback,true);((Map<String,JsonObject>)observations.get(callback)).put("scan_surroundings",scan);field.set(entry,callback);task.goal="Take five gold from the authorized first source";}}
                if(scenario.get()==5){callback.defineCompletion(transferCondition());task.actionSteps=TaskTemplatePlanner.fromCompletion(task.completion).steps();task.actionPlanGoalVersion=task.goalVersion;maid.setLeashedTo(owner,false);}
                if(scenario.get()==10 || scenario.get()==11) {
                    callback.defineCompletion(json("{\"kind\":\"maid_state\",\"dimension\":\"minecraft:overworld\",\"state\":\"follow\",\"value\":\"true\"}"));var plan=new JsonArray();var args=json("{\"wait_policy\":\"AUTO\"}");var binding=json("{\"kind\":\"container\",\"description\":\"Only Source 1\"}");
                    if(scenario.get()==10){args.addProperty("x","$target.x");args.addProperty("y","$target.y");args.addProperty("z","$target.z");args.addProperty("target","$target.registry_id");}
                    else {cart=EntityType.CHEST_MINECART.create(helper.getLevel());cart.setPos(pos.getX()+.5,pos.getY(),pos.getZ()+1.5);cart.setNoGravity(true);cart.setCustomName(Component.literal("Owner cart"));helper.getLevel().addFreshEntity(cart);var entities=new JsonArray();var entity=json("{\"registry_id\":\"minecraft:chest_minecart\",\"name\":\"Owner cart\"}");entity.addProperty("entity_id",cart.getId());entities.add(entity);scan.add("entities",entities);args.addProperty("entity_uuid","$target.entity_uuid");binding=json("{\"kind\":\"entity\",\"description\":\"Owner cart\"}");}
                    var open=step("open_gui",args,null);open.add("target_binding",binding);plan.add(open);plan.add(step("close_gui",json("{\"session_id\":\"$live.session_id\"}"),null));plan.add(step("switch_follow_state",json("{\"follow\":true}"),task.completion));task.actionSteps=ActionPlan.validate(plan);task.actionPlanGoalVersion=task.goalVersion;
                }
                if(scenario.get()==12){callback.defineCompletion(transferCondition());task.goal="Take five gold from the authorized first source";}
                if(scenario.get()==13){fresh.setBoolean(callback,false);((Map<String,JsonObject>)observations.get(callback)).clear();}helper.runAfterDelay(2,()->{callback.next(ignored->llm.incrementAndGet());poll();});
            }catch(Throwable error){fail(error);}}
            void poll(){if(!active)return;try{
                if(llm.get()>0 && !Set.of(7,8,9,14).contains(scenario.get()))throw new AssertionError("Unexpected LLM fallback, result="+callback.lastFailedResult()+", trace="+AgentArchiveData.get(helper.getLevel()).task(maid.getUUID(),task.id).records.stream().filter(r->r.kind().startsWith("decision")).map(AgentTaskArchive.Record::summary).toList());if(System.nanoTime()-start>TimeUnit.SECONDS.toNanos(12))throw new AssertionError("Timeout, status="+task.status+", llm="+llm+", failure="+task.failures+", signs="+Arrays.stream(chests).map(p->String.valueOf(helper.getBlockState(p.north()))).toList()+", trace="+AgentArchiveData.get(helper.getLevel()).task(maid.getUUID(),task.id).records.stream().filter(r->r.kind().startsWith("decision")).map(AgentTaskArchive.Record::summary).toList());
                if(scenario.get()==9 && seen.get()!=null && !changed){changed=true;label(0,"Changed label, outside source group");release.get().countDown();}
                boolean fallback=Set.of(7,8,9,14).contains(scenario.get()),done=fallback?llm.get()>0 || scenario.get()==14 && task.status==AgentTaskState.Status.paused:scenario.get()==5?task.status==AgentTaskState.Status.paused:task.status==AgentTaskState.Status.completed;
                if(!done){helper.runAfterDelay(1,this::poll);return;}
                if(scenario.get()==14){helper.assertTrue(reinspections==2 && llm.get()<=1 && task.verifiedCount==2,"At most two recovery refreshes; initial two receipts preserved; no failed transfer replay");}else if(fallback){helper.assertTrue(llm.get()==1 && task.verifiedCount==0,"One fallback, no transfer on unknown quantity, low confidence or stale labels");for(int i=0;i<3;i++)helper.assertTrue(chest(i).getItem(0).getCount()==2 && chest(i).getItem(1).getCount()==3,"Rejected native planning leaves source items unchanged");}
                else {helper.assertTrue(llm.get()==0,"Supported template/binding/recovery needs no execution LLM");if(scenario.get()==2)helper.assertTrue(task.verifiedCount==10,"Exactly five from each authorized source");if(scenario.get()==3)helper.assertTrue(task.verifiedCount==8 && chest(0).getItem(0).getCount()==4 && chest(1).getItem(0).getCount()==4,"Eight backpack items delivered, four per destination");if(scenario.get()==4)helper.assertTrue(task.verifiedCount==5 && !task.failures.isEmpty() && task.failures.stream().anyMatch(f->f.contains("stale_slots")),"Stale-menu recovery preserves the initial two and moves only three remaining");if(scenario.get()==5)helper.assertTrue(hits.get()==startHits+1 && task.verifiedCount==0,"Decision chooses pause for a blocked physical action without replay");if(scenario.get()==15)helper.assertTrue(task.verifiedCount==5,"Rebuilt template resumes from two committed items and moves only three remaining");if(scenario.get()==13)helper.assertTrue(AgentArchiveData.get(helper.getLevel()).task(maid.getUUID(),task.id).records.stream().anyMatch(r->r.kind().equals("tool_call") && r.tool().equals("scan_surroundings")),"Startup scan is code-dispatched before template selection");if(scenario.get()==16)helper.assertTrue(maid.getTask().getUid().toString().equals("touhou_little_maid:farm"),"Actual work mode switched; no crop completion invented");if(scenario.get()==10 || scenario.get()==11){var calls=AgentArchiveData.get(helper.getLevel()).task(maid.getUUID(),task.id).records.stream().filter(r->r.kind().equals("tool_call") && "open_gui".equals(r.tool())).toList();helper.assertTrue(calls.size()==1,"Exactly one grounded GUI opening");String args=callback.results.read(calls.get(0).argumentsRef(),0);helper.assertTrue(scenario.get()==10?args.contains("\"x\":"+helper.absolutePos(chests[0]).getX()):args.contains(cart.getUUID().toString()),"Code binds the real block coordinate/entity UUID");}}
                helper.assertTrue(chest(2).getItem(0).getCount()==(scenario.get()==3?0:2),"Excluded source never touched");for(int i=0;i<3;i++)helper.assertTrue(chest(i).getItem(2).getCount()==4,"Unrelated beef preserved");
                if(scenario.get()==17){helper.assertTrue(task.verifiedCount==32 && chest(2).getItem(3).getCount()+chest(2).getItem(4).getCount()==32,"Two actual egg stacks mean 32 items; excluded eggs preserved");for(int i=0;i<3;i++)helper.assertTrue(chest(i).getItem(0).getCount()+chest(i).getItem(1).getCount()==5,"Egg template preserves all unrelated gold");}
                cleanup();scenario.incrementAndGet();helper.runAfterDelay(2,this::begin);
            }catch(Throwable error){fail(error);}}
        }
        var runner=new Runner();runner.begin();
    }
}
