package com.wjx.aifun_gui_test;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.site.AvailableSites;
import com.github.tartaricacid.touhoulittlemaid.ai.service.SerializerRegister;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.*;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.init.InitEntities;
import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import com.wjx.touhou_aifun.chat.agent.*;
import com.wjx.touhou_aifun.config.TouhouAIFunConfig;
import com.wjx.touhou_aifun.maid.gui.MaidGuiSessionManager;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Opt-in single live LLM task; credentials stay in memory and metrics contain only fixture facts. */
@GameTestHolder("aifun_gui_test")
@PrefixGameTestTemplate(false)
public final class CraftingLiveGameTests {
    @GameTest(batch="craftingLive",template="empty",templateNamespace="aifun_gui_test",timeoutTicks=10_000)
    public static void configuredModelQueriesAndCraftsActualPackRecipe(GameTestHelper helper) throws Exception {
        String source = System.getProperty("aifun.craftingQa.sites");
        if (source == null) { helper.succeed(); return; }
        var configs = JsonParser.parseString(Files.readString(Path.of(source))).getAsJsonObject();
        LLMSite chosen = null; String model = "";
        for (String id : List.of("anthropic", "stepfun", "openai")) {
            var config = configs.has(id) ? configs.getAsJsonObject(id) : null;
            if (config == null || !config.get("enabled").getAsBoolean() || !config.has("secret_key") || config.get("secret_key").getAsString().isBlank()) continue;
            var serializer = SerializerRegister.getLLMSerializer(config.get("api_type").getAsString());
            if (serializer == null) continue;
            chosen = serializer.codec().parse(JsonOps.INSTANCE, config).result().orElse(null);
            if (chosen == null) continue;
            var first = config.getAsJsonArray("models").get(0);
            model = first.isJsonObject() ? first.getAsJsonObject().get("name").getAsString() : first.getAsString(); break;
        }
        if (chosen == null) { helper.fail("No enabled configured API site for live crafting QA"); return; }
        LLMSite site = chosen;
        var previous = AvailableSites.LLM_SITES.put(site.id(), site);
        boolean enabled = TouhouAIFunConfig.AGENT_RUNTIME.get(), decision = TouhouAIFunConfig.DECISION_SERVICE_ENABLED.get();
        TouhouAIFunConfig.AGENT_RUNTIME.set(true); TouhouAIFunConfig.DECISION_SERVICE_ENABLED.set(false);
        var owner = helper.makeMockPlayer();
        EntityMaid maid = new EntityMaid(InitEntities.MAID.get(), helper.getLevel()) {
            @Override public net.minecraft.world.entity.LivingEntity getOwner() { return owner; }
        };
        for (var floor : BlockPos.betweenClosed(new BlockPos(0,0,0), new BlockPos(5,0,5))) helper.setBlock(floor, Blocks.STONE);
        helper.setBlock(new BlockPos(1,1,1), Blocks.CRAFTING_TABLE);
        var position = helper.absolutePos(new BlockPos(2,1,2)); maid.setPos(position.getX()+.5, position.getY(), position.getZ()+.5);
        maid.setNoAi(true); maid.setInvulnerable(true); maid.setOwnerUUID(owner.getUUID()); helper.getLevel().addFreshEntity(maid);
        maid.getMaidInv().setStackInSlot(0, new ItemStack(Items.OAK_PLANKS, 6)); maid.getMaidInv().setStackInSlot(1, new ItemStack(Items.IRON_INGOT, 3));
        var manager = maid.getAiChatManager(); manager.llmSite = site.id(); manager.llmModel = model; manager.chatLanguage = "en_us"; manager.ttsSite = "__none__";
        var target = helper.absolutePos(new BlockPos(1,1,1));
        var entryMethod = AgentRuntime.class.getDeclaredMethod("entry", EntityMaid.class); entryMethod.setAccessible(true); var entry = entryMethod.invoke(null, maid);
        var queueField = entry.getClass().getDeclaredField("queue"); queueField.setAccessible(true); var queue = (AgentTaskQueue) queueField.get(entry);
        var task = queue.enqueue("Use the maid's backpack materials to craft exactly 6 aifun_gui_test:machine at the minecraft:crafting_table at world coordinates "
                +target.getX()+","+target.getY()+","+target.getZ()+". Read the current server recipe using query_recipes; do not guess a vanilla recipe. Use the automatic workbench crafting tools. Return the six products to the maid backpack, keep hands empty and close the menu. Do not use web, skills, or visual captures for this fixture.");
        queue.next(); var messages = new ArrayList<LLMMessage>(); messages.add(LLMMessage.userChat(maid, task.goal));
        var callback = new TaskCallback(manager, messages, task);
        var activeField = entry.getClass().getDeclaredField("callback"); activeField.setAccessible(true); activeField.set(entry, callback);
        long started = System.nanoTime(); boolean[] active = {true};
        Runnable restore = () -> {
            active[0] = false; AgentRuntime.stop(maid, true); maid.discard();
            if (previous == null) AvailableSites.LLM_SITES.remove(site.id()); else AvailableSites.LLM_SITES.put(site.id(), previous);
            TouhouAIFunConfig.AGENT_RUNTIME.set(enabled); TouhouAIFunConfig.DECISION_SERVICE_ENABLED.set(decision);
        };
        class Poll {
            void run() {
                if (!active[0]) return;
                try {
                    var archive = AgentArchiveData.get(helper.getLevel()).task(maid.getUUID(), task.id);
                    long requests = archive.records.stream().filter(record -> record.kind().equals("model_request")).count();
                    if ((task.status == AgentTaskState.Status.running || task.status == AgentTaskState.Status.waiting)
                            && System.nanoTime()-started < TimeUnit.SECONDS.toNanos(180) && requests <= 16) { helper.runAfterDelay(5, this::run); return; }
                    int outputs = 0, materials = 0; var backpack = maid.getAvailableBackpackInv();
                    for (int i=0;i<backpack.getSlots();i++) {
                        ItemStack stack = backpack.getStackInSlot(i);
                        if (stack.is(GuiFixtureMod.MACHINE.get().asItem())) outputs += stack.getCount();
                        if (stack.is(Items.OAK_PLANKS) || stack.is(Items.IRON_INGOT)) materials += stack.getCount();
                    }
                    boolean queried = archive.records.stream().anyMatch(record -> record.kind().equals("tool_call") && record.tool().equals("query_recipes"));
                    boolean automatic = archive.records.stream().filter(record -> record.kind().equals("tool_call") && record.tool().equals("gui_action"))
                            .anyMatch(record -> callback.results.read(record.argumentsRef(), 0).contains("craft_recipe"));
                    boolean completed = task.status == AgentTaskState.Status.completed && outputs == 6 && materials == 0 && queried && automatic
                            && maid.getMainHandItem().isEmpty() && maid.getOffhandItem().isEmpty() && MaidGuiSessionManager.session(maid.getUUID()) == null;
                    JsonObject report = new JsonObject(); report.addProperty("completed", completed); report.addProperty("model", manager.llmModel);
                    report.addProperty("status", task.status.name()); report.addProperty("model_requests", requests);
                    report.addProperty("outputs_in_backpack", outputs); report.addProperty("materials_remaining", materials);
                    report.addProperty("used_query_recipes", queried); report.addProperty("used_craft_recipe", automatic);
                    report.addProperty("verified_count", task.verifiedCount); report.addProperty("wall_seconds", (System.nanoTime()-started)/1e9);
                    report.add("failures", new Gson().toJsonTree(task.failures)); report.addProperty("outcome", task.outcome);
                    report.add("tool_calls", new Gson().toJsonTree(archive.records.stream().filter(record -> record.kind().equals("tool_call")).map(AgentTaskArchive.Record::tool).toList()));
                    Files.writeString(Path.of("crafting-live-result.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report));
                    restore.run(); if (completed) helper.succeed(); else helper.fail("Live crafting did not satisfy actual backpack/recipe/tool validation; see crafting-live-result.json");
                } catch (Throwable failure) { restore.run(); helper.fail("Live crafting fixture failed: "+failure.getClass().getSimpleName()); }
            }
        }
        helper.onEachTick(() -> { if (active[0]) try { Thread.sleep(50); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); restore.run(); helper.fail("Live crafting interrupted"); } });
        callback.next(site.client()); helper.runAfterDelay(5, new Poll()::run);
    }
}
