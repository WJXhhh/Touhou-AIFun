package com.wjx.aifun_gui_test;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.site.AvailableSites;
import com.github.tartaricacid.touhoulittlemaid.ai.service.SerializerRegister;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.*;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import com.wjx.touhou_aifun.chat.agent.*;
import com.wjx.touhou_aifun.config.TouhouAIFunConfig;
import com.wjx.touhou_aifun.maid.gui.MaidGuiSessionManager;
import moze_intel.projecte.utils.EMCHelper;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.*;
import net.minecraftforge.gametest.*;
import net.minecraftforge.registries.ForgeRegistries;

import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Optional real-model end-to-end test. Credentials are read only in memory from the existing site. */
@GameTestHolder("aifun_projecte_test")
@PrefixGameTestTemplate(false)
public final class ProjectELiveGameTests {
    @GameTest(batch="projecteLive",template="empty",templateNamespace="aifun_projecte_test",timeoutTicks=10_000)
    public static void configuredModelDepositsSearchesAndExchangesOwnersEmc(GameTestHelper helper) throws Exception {
        String source=System.getProperty("aifun.projecteQa.sites");if(source==null){helper.succeed();return;}
        var configs=JsonParser.parseString(Files.readString(Path.of(source))).getAsJsonObject();LLMSite chosen=null;String model="";
        for(String id:List.of("anthropic","stepfun","openai")) {
            var config=configs.has(id)?configs.getAsJsonObject(id):null;
            if(config==null || !config.get("enabled").getAsBoolean() || !config.has("secret_key") || config.get("secret_key").getAsString().isBlank())continue;
            var serializer=SerializerRegister.getLLMSerializer(config.get("api_type").getAsString());if(serializer==null)continue;
            chosen=serializer.codec().parse(JsonOps.INSTANCE,config).result().orElse(null);if(chosen==null)continue;
            var first=config.getAsJsonArray("models").get(0);model=first.isJsonObject()?first.getAsJsonObject().get("name").getAsString():first.getAsString();break;
        }
        if(chosen==null){helper.fail("No enabled configured API site for ProjectE live QA");return;}
        LLMSite site=chosen;var previous=AvailableSites.LLM_SITES.put(site.id(),site);
        boolean enabled=TouhouAIFunConfig.AGENT_RUNTIME.get(),decision=TouhouAIFunConfig.DECISION_SERVICE_ENABLED.get();
        TouhouAIFunConfig.AGENT_RUNTIME.set(true);TouhouAIFunConfig.DECISION_SERVICE_ENABLED.set(false);
        var f=new ProjectEGameTests.Fixture(helper);var maid=f.maid;
        Item output=ForgeRegistries.ITEMS.getValue(new ResourceLocation("projecte:alchemical_coal"));f.knowledge().addKnowledge(new ItemStack(output));
        BigInteger cost=BigInteger.valueOf(EMCHelper.getEmcValue(output)).multiply(BigInteger.valueOf(70));
        BigInteger diamondSell=BigInteger.valueOf(EMCHelper.getEmcSellValue(new ItemStack(Items.DIAMOND)));
        int inputCount=cost.add(diamondSell).subtract(BigInteger.ONE).divide(diamondSell).intValueExact();
        helper.assertTrue(inputCount>0 && inputCount<=64,"Fixture funds exact target using actual ProjectE prices");
        f.put(0,new ItemStack(Items.DIAMOND,inputCount));
        var manager=maid.getAiChatManager();manager.llmSite=site.id();manager.llmModel=model;manager.chatLanguage="zh_cn";manager.ttsSite="__none__";
        var method=AgentRuntime.class.getDeclaredMethod("entry",EntityMaid.class);method.setAccessible(true);var entry=method.invoke(null,maid);
        var queueField=entry.getClass().getDeclaredField("queue");queueField.setAccessible(true);var queue=(AgentTaskQueue)queueField.get(entry);
        var task=queue.enqueue("请使用世界坐标 "+f.target.getX()+","+f.target.getY()+","+f.target.getZ()+" 的 ProjectE 转化桌。"
                +"把女仆背包里的 "+inputCount+" 颗 minecraft:diamond 全部投入转化桌，记入主人的 EMC 和已学知识；查询主人已学的 projecte:alchemical_coal，精确兑换 70 个放回女仆背包，保持双手为空并关闭界面。"
                +"请实际调用 ProjectE 搜索、投入、兑换工具，读取现场数据。不要联网、加载技能或截取图片。");queue.next();
        var messages=new ArrayList<LLMMessage>();messages.add(LLMMessage.userChat(maid,task.goal));var callback=new TaskCallback(manager,messages,task);
        var field=entry.getClass().getDeclaredField("callback");field.setAccessible(true);field.set(entry,callback);
        long started=System.nanoTime();boolean[] active={true};
        Runnable restore=()->{
            active[0]=false;AgentRuntime.stop(maid,true);f.close();
            if(previous==null)AvailableSites.LLM_SITES.remove(site.id());else AvailableSites.LLM_SITES.put(site.id(),previous);
            TouhouAIFunConfig.AGENT_RUNTIME.set(enabled);TouhouAIFunConfig.DECISION_SERVICE_ENABLED.set(decision);
        };
        class Poll {
            void run() {
                if(!active[0])return;
                try {
                    var archive=AgentArchiveData.get(helper.getLevel()).task(maid.getUUID(),task.id);
                    long requests=archive.records.stream().filter(record->record.kind().equals("model_request")).count();
                    if((task.status==AgentTaskState.Status.running || task.status==AgentTaskState.Status.waiting)
                            && System.nanoTime()-started<TimeUnit.SECONDS.toNanos(180) && requests<=16){helper.runAfterDelay(5,this::run);return;}
                    List<String> actions=archive.records.stream().filter(record->record.kind().equals("tool_call") && record.tool().equals("gui_action"))
                            .map(record->JsonParser.parseString(callback.results.snapshot().values().getOrDefault(record.argumentsRef(),"{}")).getAsJsonObject())
                            .filter(args->args.has("action")).map(args->args.get("action").getAsString()).toList();
                    BigInteger expected=BigInteger.valueOf(EMCHelper.getEmcSellValue(new ItemStack(Items.DIAMOND))).multiply(BigInteger.valueOf(inputCount))
                            .subtract(BigInteger.valueOf(EMCHelper.getEmcValue(output)).multiply(BigInteger.valueOf(70)));
                    boolean completed=task.status==AgentTaskState.Status.completed && f.count(output)==70 && f.count(Items.DIAMOND)==0
                            && f.knowledge().getEmc().equals(expected) && f.knowledge().hasKnowledge(new ItemStack(Items.DIAMOND))
                            && actions.containsAll(List.of("projecte_deposit","projecte_search","projecte_exchange"))
                            && maid.getMainHandItem().isEmpty() && maid.getOffhandItem().isEmpty() && MaidGuiSessionManager.session(maid.getUUID())==null;
                    var report=new JsonObject();report.addProperty("completed",completed);report.addProperty("model",manager.llmModel);
                    report.addProperty("status",task.status.name());report.addProperty("model_requests",requests);report.add("actions",new Gson().toJsonTree(actions));
                    report.addProperty("deposited_diamonds",inputCount-f.count(Items.DIAMOND));report.addProperty("mod_outputs_in_backpack",f.count(output));
                    report.addProperty("owner_emc",f.knowledge().getEmc().toString());report.addProperty("expected_emc",expected.toString());
                    report.addProperty("owner_learned_diamond",f.knowledge().hasKnowledge(new ItemStack(Items.DIAMOND)));report.addProperty("verified_count",task.verifiedCount);
                    report.addProperty("wall_seconds",(System.nanoTime()-started)/1e9);report.add("failures",new Gson().toJsonTree(task.failures));report.addProperty("outcome",task.outcome);
                    Files.writeString(Path.of("projecte-live-result.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report));
                    restore.run();if(completed)helper.succeed();else helper.fail("ProjectE live task did not satisfy actual owner/backpack settlement; see projecte-live-result.json");
                } catch(Throwable failure){restore.run();helper.fail("ProjectE live fixture failed: "+failure.getClass().getSimpleName());}
            }
        }
        helper.onEachTick(()->{if(active[0])try{Thread.sleep(50);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();restore.run();helper.fail("ProjectE live test interrupted");}});
        callback.next(site.client());helper.runAfterDelay(5,new Poll()::run);
    }
}
