package com.wjx.aifun_gui_test;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.site.AvailableSites;
import com.github.tartaricacid.touhoulittlemaid.ai.service.SerializerRegister;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.*;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import com.wjx.touhou_aifun.chat.agent.*;
import com.wjx.touhou_aifun.config.TouhouAIFunConfig;
import com.wjx.touhou_aifun.vision.*;
import com.wjx.touhou_aifun.maid.gui.MaidGuiSessionManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Real configured model receives the real GUI images and chooses every production action. */
final class MachineLiveRun implements AutoCloseable {
    private final EntityMaid maid;
    private final String machine;
    private final BlockPos position;
    private final boolean fuel;
    private LLMSite site,previous;
    private TaskCallback callback;
    private AgentTaskState task;
    private long started;
    private boolean oldRuntime,oldDecision,oldVision;
    MachineLiveRun(EntityMaid maid,String machine,BlockPos position,boolean fuel){this.maid=maid;this.machine=machine;this.position=position;this.fuel=fuel;}
    void start() throws Exception {
        var configs=JsonParser.parseString(Files.readString(Path.of(System.getProperty("aifun.machineQa.sites")))).getAsJsonObject();String model="";
        for(String id:List.of("anthropic","stepfun","openai")){
            var config=configs.has(id)?configs.getAsJsonObject(id):null;
            if(config==null || !config.get("enabled").getAsBoolean() || !config.has("secret_key") || config.get("secret_key").getAsString().isBlank())continue;
            var serializer=SerializerRegister.getLLMSerializer(config.get("api_type").getAsString());if(serializer==null)continue;
            site=serializer.codec().parse(JsonOps.INSTANCE,config).result().orElse(null);if(site==null)continue;
            var first=config.getAsJsonArray("models").get(0);model=first.isJsonObject()?first.getAsJsonObject().get("name").getAsString():first.getAsString();break;
        }
        if(site==null)throw new IllegalStateException("No enabled API site for native machine live QA");
        previous=AvailableSites.LLM_SITES.put(site.id(),site);
        // Reuse the user's declared image capability; do not pretend a text-only endpoint supports images.
        var metadata=Path.of(System.getProperty("aifun.machineQa.sites")).resolveSibling("aifun_model_metadata.json");
        if(Files.exists(metadata)){
            var entry=ModelMetadataStore.read(metadata).get(new ModelRef(site.id(),model));
            UnifiedModelCatalog.setCapability(new ModelRef(site.id(),model),entry.capability());
        }
        oldRuntime=TouhouAIFunConfig.AGENT_RUNTIME.get();oldDecision=TouhouAIFunConfig.DECISION_SERVICE_ENABLED.get();oldVision=TouhouAIFunConfig.VISION_ENABLED.get();
        TouhouAIFunConfig.AGENT_RUNTIME.set(true);TouhouAIFunConfig.DECISION_SERVICE_ENABLED.set(false);TouhouAIFunConfig.VISION_ENABLED.set(true);
        var manager=maid.getAiChatManager();manager.llmSite=site.id();manager.llmModel=model;manager.chatLanguage="zh_cn";manager.ttsSite="__none__";
        var method=AgentRuntime.class.getDeclaredMethod("entry",EntityMaid.class);method.setAccessible(true);var runtime=method.invoke(null,maid);
        var field=runtime.getClass().getDeclaredField("queue");field.setAccessible(true);var queue=(AgentTaskQueue)field.get(runtime);
        task=queue.enqueue("请操作世界坐标 "+position.getX()+","+position.getY()+","+position.getZ()+" 的 "+machine+"，将女仆背包的3个 minecraft:raw_iron 加工成3个 minecraft:iron_ingot，成品放回女仆背包并关闭界面，保持双手为空。"
                +(fuel?"背包中还有1个minecraft:coal供机器作燃料。":"测试场已提供外部电源。")
                +"这是陌生模组机器，请先实际查看界面图片、识别原料槽、成品槽和相关按钮，再操作；操作后核对实际槽位与背包数量。使用通用GUI工具，不要查询网络或技能，不要假称加工完成。");queue.next();
        var messages=new ArrayList<LLMMessage>();messages.add(LLMMessage.userChat(maid,task.goal));callback=new TaskCallback(manager,messages,task);
        field=runtime.getClass().getDeclaredField("callback");field.setAccessible(true);field.set(runtime,callback);
        started=System.nanoTime();callback.next(site.client());
    }
    private AgentTaskArchive archive(){return AgentArchiveData.get((net.minecraft.server.level.ServerLevel)maid.level()).task(maid.getUUID(),task.id);}
    boolean done(){return task.status!=AgentTaskState.Status.running && task.status!=AgentTaskState.Status.waiting
            || System.nanoTime()-started>TimeUnit.SECONDS.toNanos(240) || archive().records.stream().filter(r->r.kind().equals("model_request")).count()>24;}
    JsonObject verify() throws Exception {
        int output=0,input=0;var bag=maid.getAvailableBackpackInv();for(int n=0;n<bag.getSlots();n++){var stack=bag.getStackInSlot(n);if(stack.is(Items.IRON_INGOT))output+=stack.getCount();if(stack.is(Items.RAW_IRON))input+=stack.getCount();}
        var records=archive().records;
        var calls=records.stream().filter(r->r.kind().equals("tool_call")).map(r->r.tool()).toList();
        boolean image=callback.results.snapshot().values().values().stream().anyMatch(v->v.contains("attached_to_main_model") || v.contains("visual_observation"));
        boolean completed=task.status==AgentTaskState.Status.completed && output==3 && input==0 && image
                && maid.getMainHandItem().isEmpty() && maid.getOffhandItem().isEmpty() && MaidGuiSessionManager.session(maid.getUUID())==null;
        var report=new JsonObject();report.addProperty("machine",machine);report.addProperty("completed",completed);report.addProperty("model",maid.getAiChatManager().llmModel);report.addProperty("status",task.status.name());
        report.addProperty("outputs",output);report.addProperty("inputs_remaining",input);report.addProperty("image_routed",image);report.addProperty("verified_count",task.verifiedCount);
        report.addProperty("model_requests",records.stream().filter(r->r.kind().equals("model_request")).count());report.add("tools",new Gson().toJsonTree(calls));report.addProperty("outcome",task.outcome);report.add("failures",new Gson().toJsonTree(task.failures));
        report.addProperty("wall_seconds",(System.nanoTime()-started)/1e9);
        Files.writeString(Path.of("machine-live-"+machine.replace(':','-')+".json"),new GsonBuilder().setPrettyPrinting().create().toJson(report));
        if(!completed)throw new AssertionError("Native machine live task failed: "+report);
        return report;
    }
    @Override public void close(){
        AgentRuntime.stop(maid,true);if(callback!=null)MaidGuiSessionManager.finish(callback);
        if(site!=null){if(previous==null)AvailableSites.LLM_SITES.remove(site.id());else AvailableSites.LLM_SITES.put(site.id(),previous);}
        TouhouAIFunConfig.AGENT_RUNTIME.set(oldRuntime);TouhouAIFunConfig.DECISION_SERVICE_ENABLED.set(oldDecision);TouhouAIFunConfig.VISION_ENABLED.set(oldVision);
    }
}
