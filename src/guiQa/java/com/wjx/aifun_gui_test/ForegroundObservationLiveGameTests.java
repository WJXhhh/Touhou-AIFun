package com.wjx.aifun_gui_test;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.response.ResponseChat;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.site.AvailableSites;
import com.github.tartaricacid.touhoulittlemaid.ai.service.SerializerRegister;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.*;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.init.InitEntities;
import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import com.wjx.touhou_aifun.chat.*;
import com.wjx.touhou_aifun.chat.agent.*;
import com.wjx.touhou_aifun.config.TouhouAIFunConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.*;
import java.nio.file.*;
import java.util.*;

/** Paid opt-in: actual foreground protocol, actual scan, no seeded model calls/answers. */
@GameTestHolder("aifun_observation_live_test")
@PrefixGameTestTemplate(false)
public final class ForegroundObservationLiveGameTests {
    @GameTest(template="four_chests",templateNamespace="aifun_observation_live_test",timeoutTicks=6000)
    public static void flashAnswersThreeObservationTurnsWithoutBackgroundHandoff(GameTestHelper helper) throws Exception {
        String path=System.getProperty("aifun.observationQa.sites");if(path==null){helper.succeed();return;}
        var cfg=JsonParser.parseString(Files.readString(Path.of(path))).getAsJsonObject().getAsJsonObject("anthropic");
        if(cfg==null || !cfg.get("enabled").getAsBoolean())throw new IllegalStateException("Enabled Anthropic site required");
        String model="deepseek-flash";
        if(cfg.getAsJsonArray("models").asList().stream().noneMatch(v->(v.isJsonObject()?v.getAsJsonObject().get("name").getAsString():v.getAsString()).equals(model)))throw new IllegalStateException("Configured Flash model required");
        var site=SerializerRegister.getLLMSerializer(cfg.get("api_type").getAsString()).codec().parse(JsonOps.INSTANCE,cfg).result().orElseThrow();
        var previousSite=AvailableSites.LLM_SITES.put(site.id(),site);
        boolean enabled=TouhouAIFunConfig.AGENT_RUNTIME.get(),diagnostics=TouhouAIFunConfig.AGENT_DIAGNOSTICS.get(),decisions=TouhouAIFunConfig.DECISION_SERVICE_ENABLED.get();
        String previousModel=TouhouAIFunConfig.AGENT_DECISION_MODEL.get();
        TouhouAIFunConfig.AGENT_RUNTIME.set(true);TouhouAIFunConfig.AGENT_DIAGNOSTICS.set(true);TouhouAIFunConfig.DECISION_SERVICE_ENABLED.set(false);
        // A wrong handoff must not accidentally dispatch the configured production Pro model.
        TouhouAIFunConfig.AGENT_DECISION_MODEL.set(new Gson().toJson(List.of(site.id(),model)));
        FourChestNavigationGameTests.prepareFloor(helper);var table=helper.absolutePos(new BlockPos(2,1,2));helper.setBlock(new BlockPos(2,1,2),Blocks.ENCHANTING_TABLE);
        var owner=helper.makeMockPlayer();var start=helper.absolutePos(new BlockPos(4,1,2));owner.setPos(start.getX()+.5,start.getY(),start.getZ()+1.5);
        var maid=new EntityMaid(InitEntities.MAID.get(),helper.getLevel()){@Override public net.minecraft.world.entity.LivingEntity getOwner(){return owner;}};
        maid.setPos(start.getX()+.5,start.getY(),start.getZ()+.5);maid.setOwnerUUID(owner.getUUID());maid.setNoAi(true);helper.getLevel().addFreshEntity(maid);
        var manager=maid.getAiChatManager();manager.llmSite=site.id();manager.llmModel=model;manager.chatLanguage="zh_cn";manager.ttsSite="__none__";
        String setting=com.github.tartaricacid.touhoulittlemaid.ai.manager.setting.papi.PapiReplacer.replaceSetting("You are a helpful Minecraft maid. Answer the owner's observations using actual current tools and evidence.",maid,"zh_cn");
        String[] questions={"现在看一下附魔台在哪里，请报告实际方块坐标 [x,y,z]。","全扫描","扫描周围方块"};
        boolean existing=Boolean.getBoolean("aifun.observationQa.pausedTask");
        if(existing) {
            AgentRuntime.speaker(maid,new ChatSpeakerContext.Snapshot(owner.getUUID(),"owner",owner.getUUID(),"owner","owner",true,false),"之前的扫描任务先暂停");
            var setup=new LLMCallback(manager,new ArrayList<>(),true);AgentExecution.bind(setup,0,null,0,AgentExecution.Purpose.CHAT);
            var args=new JsonObject();args.addProperty("action","enqueue");args.addProperty("goal","旧任务：扫描附近方块并报告位置");AgentRuntime.control(setup,args);
            var entryMethod=AgentRuntime.class.getDeclaredMethod("entry",EntityMaid.class);entryMethod.setAccessible(true);var entry=entryMethod.invoke(null,maid);var queueField=entry.getClass().getDeclaredField("queue");queueField.setAccessible(true);((AgentTaskQueue)queueField.get(entry)).next();
            AgentRuntime.previewControl(maid,"pause");ChatFlowManager.finishRequest(maid.getUUID(),setup);
        }
        class Runner {
            boolean active=true,done;String answer="",error="";int turn;LLMCallback callback;List<LLMMessage> history=new ArrayList<>();JsonArray rows=new JsonArray();
            void restore(){active=false;AgentRuntime.stop(maid,true);ChatFlowManager.forgetMaid(maid.getUUID());maid.discard();if(previousSite==null)AvailableSites.LLM_SITES.remove(site.id());else AvailableSites.LLM_SITES.put(site.id(),previousSite);TouhouAIFunConfig.AGENT_RUNTIME.set(enabled);TouhouAIFunConfig.AGENT_DIAGNOSTICS.set(diagnostics);TouhouAIFunConfig.DECISION_SERVICE_ENABLED.set(decisions);TouhouAIFunConfig.AGENT_DECISION_MODEL.set(previousModel);}
            void begin(){try {
                if(turn==questions.length){restore();helper.assertTrue(rows.asList().stream().allMatch(v->v.getAsJsonObject().get("passed").getAsBoolean()),"Foreground observation regression; see observation-live-results.json");helper.succeed();return;}
                done=false;answer="";error="";if(history.isEmpty())history.add(LLMMessage.systemChat(maid,setting));history.add(LLMMessage.userChat(maid,questions[turn]));
                AgentRuntime.speaker(maid,new ChatSpeakerContext.Snapshot(owner.getUUID(),"owner",owner.getUUID(),"owner","owner",true,false),questions[turn]);
                callback=new LLMCallback(manager,new ArrayList<>(history),true){
                    @Override public void onSuccess(ResponseChat response){runOnServerThread(()->{answer=AgentRuntime.guardForegroundReply(this,response).getChatText();done=true;ChatFlowManager.finishRequest(maid.getUUID(),this);});}
                    @Override public void onFailure(java.net.http.HttpRequest request,Throwable failure,int code){runOnServerThread(()->{error="request_failed:"+code;done=true;ChatFlowManager.finishRequest(maid.getUUID(),this);});}
                };
                AgentExecution.bind(callback,turn+1,null,0,AgentExecution.Purpose.CHAT);ChatFlowManager.registerRequest(maid.getUUID(),callback);site.client().chat(callback);helper.runAfterDelay(5,this::poll);
            }catch(Throwable e){restore();helper.fail("Observation live setup: "+e.getClass().getSimpleName());}}
            void poll(){if(!active)return;try {
                var status=AgentRuntime.previewControl(maid,"status");
                boolean noTask=existing?status.getAsJsonArray("tasks").size()==1 && status.get("paused").getAsBoolean() && status.getAsJsonArray("tasks").get(0).getAsJsonObject().get("status").getAsString().equals("paused"):status.getAsJsonArray("tasks").isEmpty();
                if(!done && noTask && helper.getTick()<5000){helper.runAfterDelay(5,this::poll);return;}
                var calls=callback.getMessages().stream().filter(m->m.toolCalls()!=null).flatMap(m->m.toolCalls().stream()).map(c->c.getFunction().getName()).toList();
                var row=new JsonObject();row.addProperty("turn",turn+1);row.addProperty("model",model);row.addProperty("answer",answer);row.addProperty("error",error);row.add("calls_in_history",new Gson().toJsonTree(calls));row.addProperty("tasks_created",status.getAsJsonArray("tasks").size());
                row.addProperty("preexisting_paused_task",existing);row.addProperty("new_tasks_created",status.getAsJsonArray("tasks").size()-(existing?1:0));row.addProperty("prior_queue_preserved",noTask);
                String coords="\\[\\s*"+table.getX()+"\\s*[,，]\\s*"+table.getY()+"\\s*[,，]\\s*"+table.getZ()+"\\s*\\]";
                boolean located=turn!=0 || java.util.regex.Pattern.compile(coords).matcher(answer).find();
                boolean evidence=callback.getMessages().stream().anyMatch(m->m.role()==Role.TOOL && m.message()!=null && m.message().contains("minecraft:enchanting_table"));
                boolean passed=done && error.isEmpty() && noTask && !answer.isBlank() && located && evidence && calls.contains("scan_surroundings") && !calls.contains("task_control") && !calls.contains("load_tool_schema");row.addProperty("passed",passed);rows.add(row);
                Files.writeString(Path.of("observation-live-results.json"),new GsonBuilder().setPrettyPrinting().create().toJson(rows));
                if(!passed){restore();helper.fail("Foreground observation failed; see observation-live-results.json");return;}
                history=new ArrayList<>(callback.getMessages());history.add(new LLMMessage(Role.ASSISTANT,answer,maid.level().getGameTime()));turn++;helper.runAfterDelay(1,this::begin);
            }catch(Throwable e){restore();helper.fail("Observation live polling: "+e.getClass().getSimpleName());}}
        }
        var runner=new Runner();helper.onEachTick(()->{if(runner.active)try{Thread.sleep(50);}catch(InterruptedException e){Thread.currentThread().interrupt();runner.restore();helper.fail("Interrupted");}});runner.begin();
    }
}
