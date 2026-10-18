package com.wjx.aifun_gui_test;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.response.ResponseChat;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.*;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.response.*;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.init.InitEntities;
import com.google.gson.*;
import com.wjx.touhou_aifun.chat.ChatFlowManager;
import com.wjx.touhou_aifun.chat.agent.*;
import com.wjx.touhou_aifun.config.TouhouAIFunConfig;
import com.wjx.touhou_aifun.maid.action.MaidEatFoodActionManager;
import com.wjx.touhou_aifun.maid.gui.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraftforge.gametest.*;
import java.util.*;

@GameTestHolder("aifun_gui_test")
@PrefixGameTestTemplate(false)
public final class LongTaskContextGameTests {
    private static final class Fixture implements AutoCloseable {
        final GameTestHelper helper;
        final EntityMaid maid;
        final BlockPos local=new BlockPos(2,1,2), target;
        AgentTaskState state;
        TaskCallback task;
        Fixture(GameTestHelper helper,int required) throws Exception {
            this.helper=helper;FourChestNavigationGameTests.prepareFloor(helper);
            maid=helper.spawn(InitEntities.MAID.get(),new BlockPos(4,1,2));maid.setNoAi(true);maid.setInvulnerable(true);
            helper.setBlock(local,Blocks.CHEST);target=helper.absolutePos(local);chest().setItem(0,new ItemStack(Items.IRON_INGOT,8));
            state=new AgentTaskState("只取指定数量铁锭，禁止碰金锭，然后走到远处");state.status=AgentTaskState.Status.running;
            state.defineCompletion(transfer(required));attach();
        }
        JsonObject transfer(int count) {
            JsonObject c=new JsonObject();c.addProperty("kind","transfer");c.addProperty("dimension",maid.level().dimension().location().toString());
            c.addProperty("item","minecraft:iron_ingot");c.addProperty("count",count);c.addProperty("to_maid",true);
            JsonArray pos=new JsonArray();pos.add(target.getX());pos.add(target.getY());pos.add(target.getZ());c.add("position",pos);return c;
        }
        void attach() throws Exception {
            task=new TaskCallback(maid.getAiChatManager(),new ArrayList<>(List.of(LLMMessage.userChat(maid,state.goal))),state);
            var fresh=TaskCallback.class.getDeclaredField("freshWorld");fresh.setAccessible(true);fresh.setBoolean(task,true);
            state.loadedTools.addAll(List.of(com.wjx.touhou_aifun.compat.ai.action.GuiTool.IDS));
        }
        void reload() throws Exception {
            MaidGuiSessionManager.cancel(maid.getUUID(),"qa_reload");state.generation++;
            state=new Gson().fromJson(new Gson().toJson(state),AgentTaskState.class);attach();
        }
        ChestBlockEntity chest(){return (ChestBlockEntity)helper.getBlockEntity(local);}
        JsonObject open(String id) {
            JsonObject request=new JsonObject();request.addProperty("target","minecraft:chest");
            request.addProperty("x",target.getX());request.addProperty("y",target.getY());request.addProperty("z",target.getZ());
            var result=MaidGuiSessionManager.call(task,"open_gui",id,request).join();helper.assertTrue(!result.has("error"),result.toString());return result;
        }
        int backpackSlot(MaidGuiSession session) {
            return session.menu().slots.stream().filter(s->s.container==session.actor.getInventory() && s.getContainerSlot()==1).findFirst().orElseThrow().index;
        }
        JsonObject moveRequest(int count) {
            var session=MaidGuiSessionManager.session(maid.getUUID());JsonObject request=new JsonObject();request.addProperty("session_id",session.id.toString());
            request.addProperty("action","transfer");request.addProperty("slot",0);request.addProperty("to_slot",backpackSlot(session));request.addProperty("count",count);
            return request;
        }
        JsonObject move(String id,int count) {return MaidGuiSessionManager.call(task,"gui_action",id,moveRequest(count)).join();}
        void compact() {
            for(int i=0;i<60;i++) {
                var call=new ToolCall("history_"+i,new FunctionToolCall("inspect_gui","{}"));
                task.getMessages().add(new LLMMessage(Role.ASSISTANT,"",0,List.of(call),null));
                task.getMessages().add(new LLMMessage(Role.TOOL,"old observation ".repeat(600),0,null,call.getId()));
            }
            var messages=AgentContext.compact(task.getMessages(),5000,task.results);task.getMessages().clear();task.getMessages().addAll(messages);
            for(int i=0;i<140;i++)task.results.put("obsolete observation ".repeat(100));
            helper.assertTrue(task.getMessages().stream().anyMatch(m->m.role()==Role.USER && m.message().contains("禁止碰金锭")),"Original restrictions remain verbatim");
        }
        String checkpoint() {
            return task.getMessages().stream().filter(m->m.role()==Role.SYSTEM && m.message().startsWith("## Current task checkpoint\n")).findFirst().orElseThrow().message();
        }
        @Override public void close(){MaidEatFoodActionManager.cancelForMaid(maid.getUUID(),"qa_complete");MaidGuiSessionManager.cancel(maid.getUUID(),"qa_complete");state.status=AgentTaskState.Status.cancelled;task.operations.cancel();task.archiveState();ChatFlowManager.forgetMaid(maid.getUUID());maid.discard();}
    }

    private static Message modelTool(String id,String tool,JsonObject args) {
        return new Gson().fromJson("{\"tool_calls\":[{\"id\":\""+id+"\",\"type\":\"function\",\"function\":{\"name\":\""+tool+"\",\"arguments\":"+new Gson().toJson(args.toString())+"}}]}",Message.class);
    }
    @GameTest(batch="allowedTools",template="four_chests",templateNamespace="aifun_gui_test")
    public static void automaticLoopUsesActualReceiptsAndSurvivesCompressionWithoutReplay(GameTestHelper helper) throws Exception {
        boolean runtime=TouhouAIFunConfig.AGENT_RUNTIME.get(), automatic=TouhouAIFunConfig.AGENT_AUTOMATIC_TOOL_SELECTION.get();
        TouhouAIFunConfig.AGENT_RUNTIME.set(true);TouhouAIFunConfig.AGENT_AUTOMATIC_TOOL_SELECTION.set(true);
        try(var f=new Fixture(helper,2)) {
            var leaves=new JsonArray();
            for(int i=0;i<6;i++) {
                var local=new BlockPos(2+2*(i/3),1,2+2*(i%3));helper.setBlock(local,Blocks.CHEST);
                ((ChestBlockEntity)helper.getBlockEntity(local)).setItem(0,new ItemStack(Items.IRON_INGOT,8));
                var leaf=f.transfer(2);var pos=helper.absolutePos(local);var xyz=new JsonArray();xyz.add(pos.getX());xyz.add(pos.getY());xyz.add(pos.getZ());leaf.add("position",xyz);leaves.add(leaf);
            }
            f.state.completion=null;var contract=new JsonObject();contract.addProperty("kind","all");contract.add("conditions",leaves);f.state.defineCompletion(contract);
            f.task.next(ignored->{});helper.assertTrue(f.task.allowedTools()==null,"A plan alone cannot infer a repeated loop");
            f.open("loop_first");helper.assertTrue(f.move("loop_first_real_two",2).get("moved_count").getAsInt()==2,"First two real iron move");MaidGuiSessionManager.cancel(f.maid.getUUID(),"next_target");
            var second=leaves.get(1).getAsJsonObject().getAsJsonArray("position");var open=new JsonObject();open.addProperty("target","minecraft:chest");open.addProperty("x",second.get(0).getAsInt());open.addProperty("y",second.get(1).getAsInt());open.addProperty("z",second.get(2).getAsInt());
            helper.assertTrue(!MaidGuiSessionManager.call(f.task,"open_gui","loop_second",open).join().has("error"),"Second distinct real chest opens");helper.assertTrue(f.move("loop_second_real_two",2).get("moved_count").getAsInt()==2,"Second two real iron move");
            TouhouAIFunConfig.AGENT_AUTOMATIC_TOOL_SELECTION.set(false);f.task.next(ignored->{});helper.assertTrue(f.task.allowedTools()==null,"Baseline switch disables native inference too");
            TouhouAIFunConfig.AGENT_AUTOMATIC_TOOL_SELECTION.set(true);f.task.next(ignored->{});
            helper.assertTrue(Set.of("open_gui","gui_batch").equals(f.task.allowedTools()),"Two committed targets and four outstanding targets activate a bounded native loop; receipts="+f.state.transfers+"; inferred="+TaskToolSelection.repeatedTransferSelection(f.state.activeCompletion(),f.state.activeTransfers())+"; selected="+f.task.allowedTools());
            var receipts=Map.copyOf(f.state.transfers);f.compact();f.task.next(ignored->{});
            helper.assertTrue(receipts.equals(f.state.transfers) && f.state.completion.equals(contract) && f.task.allowedTools()!=null,"Compression retains requirements, selection and exact receipts");
            helper.assertTrue(f.move("loop_different_id_replay",2).has("error") && f.state.transfers.equals(receipts),"Inferred capabilities cannot repeat a finished target");helper.succeed();
        } finally {TouhouAIFunConfig.AGENT_RUNTIME.set(runtime);TouhouAIFunConfig.AGENT_AUTOMATIC_TOOL_SELECTION.set(automatic);}
    }
    private static JsonObject restrictedPlan() {
        return JsonParser.parseString("{\"steps\":[\"搬取剩余铁锭\",\"回到主人身边\"],\"current_step\":0,\"allowed_tools\":[\"gui_action\"]}").getAsJsonObject();
    }
    @GameTest(batch="allowedTools",template="four_chests",templateNamespace="aifun_gui_test")
    public static void oversizedPlanExplainsHowToKeepFullRequirementsAndRegistersNothing(GameTestHelper helper) throws Exception {
        boolean runtime=TouhouAIFunConfig.AGENT_RUNTIME.get();TouhouAIFunConfig.AGENT_RUNTIME.set(true);
        try(var f=new Fixture(helper,5)) {
            f.state.completion=null;f.task.next(ignored->{});
            var plan=new JsonObject();var steps=new JsonArray();steps.add("coordinates ".repeat(30));plan.add("steps",steps);plan.addProperty("current_step",0);plan.add("completion",f.transfer(5));
            f.task.onFunctionCall(modelTool("oversized_plan","update_task_plan",plan),ignored->{});
            helper.assertTrue(f.state.completion==null && f.state.steps.isEmpty() && f.state.transfers.isEmpty(),"Invalid prose cannot partially register or shrink the contract");
            helper.assertTrue(f.task.getMessages().stream().anyMatch(m->m.message()!=null && m.message().contains("plan_step_too_long") && m.message().contains("FULL completion contract")),"Actionable feedback preserves all requirements instead of returning null");
            steps=new JsonArray();steps.add("Collect the five requested iron; keep protected gold untouched");plan.add("steps",steps);
            f.task.onFunctionCall(modelTool("repaired_prose_same_contract","update_task_plan",plan),ignored->{});
            f.open("after_prose_repair");f.move("only_real_two",2);
            helper.assertTrue(f.state.completion.equals(f.transfer(5)) && f.state.verifiedCount==2 && f.chest().getItem(0).getCount()==6,"Only prose changed; the complete quantity remains and the actual operation occurs once");helper.succeed();
        } finally {TouhouAIFunConfig.AGENT_RUNTIME.set(runtime);}
    }
    @GameTest(batch="recipeEvidence",template="four_chests",templateNamespace="aifun_gui_test")
    public static void recipeDefinitionsSurviveCompressionWithoutCachingStockAndInvalidateOnReload(GameTestHelper helper) throws Exception {
        boolean enabled=TouhouAIFunConfig.AGENT_RUNTIME.get();TouhouAIFunConfig.AGENT_RUNTIME.set(true);
        var manager=helper.getLevel().getRecipeManager();var originals=new ArrayList<net.minecraft.world.item.crafting.Recipe<?>>(manager.getRecipes());
        try(var f=new Fixture(helper,5)) {
            f.open("materials");f.move("collected_two",2);
            var query=new JsonObject();query.addProperty("item","aifun_gui_test:machine");query.addProperty("count",6);
            f.task.onFunctionCall(modelTool("learn_loaded_recipe","query_recipes",query),ignored->{});
            f.compact();f.task.next(ignored->{});
            var evidence=f.task.getMessages().stream().filter(m->m.role()==Role.SYSTEM && m.message().startsWith("## Known crafting and cooking recipes\n")).findFirst().orElseThrow().message();
            helper.assertTrue(evidence.contains("aifun_gui_test:qa_machine_shaped") && evidence.contains("ingredients") && !evidence.contains("matching_backpack_count") && !evidence.contains("can_fill_one_craft"),"Compression retains current recipe definitions, never old backpack availability");
            helper.assertTrue(f.state.verifiedCount==2 && f.chest().getItem(0).getCount()==6,"Recipe retention changes no requirements, receipts or inventory");
            var id=new net.minecraft.resources.ResourceLocation("aifun_gui_test:qa_machine_shaped");
            var replacement=new net.minecraft.world.item.crafting.ShapelessRecipe(id,"",net.minecraft.world.item.crafting.CraftingBookCategory.MISC,
                    new ItemStack(GuiFixtureMod.MACHINE.get().asItem(),2),net.minecraft.core.NonNullList.of(net.minecraft.world.item.crafting.Ingredient.EMPTY,net.minecraft.world.item.crafting.Ingredient.of(Items.OAK_PLANKS)));
            var changed=new ArrayList<net.minecraft.world.item.crafting.Recipe<?>>(originals.stream().filter(recipe->!recipe.getId().equals(id)).toList());changed.add(replacement);manager.replaceRecipes(changed);
            helper.setBlock(new BlockPos(5,1,2),Blocks.CRAFTING_TABLE);var table=helper.absolutePos(new BlockPos(5,1,2));
            f.maid.getMaidInv().setStackInSlot(5,new ItemStack(Items.OAK_PLANKS,6));
            var open=new JsonObject();open.addProperty("target","minecraft:crafting_table");open.addProperty("x",table.getX());open.addProperty("y",table.getY());open.addProperty("z",table.getZ());
            MaidGuiSessionManager.call(f.task,"open_gui","changed_table",open).join();
            var session=MaidGuiSessionManager.session(f.maid.getUUID());var craft=new JsonObject();craft.addProperty("session_id",session.id.toString());craft.addProperty("action","craft_recipe");craft.addProperty("recipe_id",id.toString());craft.addProperty("count",6);
            var result=MaidGuiSessionManager.call(f.task,"gui_action","stale_recipe",craft);
            helper.assertTrue(result.isDone() && result.join().toString().contains("recipes_reloaded_query_again") && f.maid.getMaidInv().getStackInSlot(5).getCount()==6,"Reload invalidates cached definitions before ingredients can move");
            f.task.next(ignored->{});
            helper.assertTrue(f.task.getMessages().stream().anyMatch(m->m.role()==Role.SYSTEM && m.message().startsWith("## Known crafting and cooking recipes\n") && m.message().contains("reload_requires_query")),"The next request explicitly reports invalid recipe ids");
            helper.succeed();
        } finally {manager.replaceRecipes(originals);TouhouAIFunConfig.AGENT_RUNTIME.set(enabled);}
    }
    @GameTest(batch="allowedTools",template="four_chests",templateNamespace="aifun_gui_test")
    public static void changingToolMenusLeaveAnthropicPolicyPrefixStableAndLoaderUsesFrozenNames(GameTestHelper helper) throws Exception {
        boolean enabled=TouhouAIFunConfig.AGENT_RUNTIME.get();TouhouAIFunConfig.AGENT_RUNTIME.set(true);
        try(var f=new Fixture(helper,5)) {
            f.task.next(ignored->{});
            var client=new com.wjx.touhou_aifun.compat.ai.openai.AnthropicCompatLLMClient(null,null);
            var build=client.getClass().getDeclaredMethod("buildRequestBody",LLMCallback.class);build.setAccessible(true);
            var first=(JsonObject)build.invoke(client,f.task);
            f.open("changed_menu");f.task.restrictTools(Set.of("gui_action"));f.task.next(ignored->{});
            var second=(JsonObject)build.invoke(client,f.task);
            helper.assertTrue(first.get("system").equals(second.get("system")) && !second.get("system").getAsString().contains("## Task tool menu"),"Only the final runtime update changes, preserving the immutable policy prefix");
            helper.assertTrue(second.getAsJsonArray("messages").get(second.getAsJsonArray("messages").size()-1).toString().contains("## Task tool menu"),"Current tool menu remains visible after all completed tool results");
            var tools=second.getAsJsonArray("tools");helper.assertTrue(tools.get(0).getAsJsonObject().get("name").getAsString().equals("load_tool_schema") && tools.get(tools.size()-1).getAsJsonObject().get("name").getAsString().equals("update_task_plan"),"Stable definitions precede the planner's amendment-sensitive completion syntax without adding tools");
            var ids=tools.get(0).getAsJsonObject().getAsJsonObject("input_schema").getAsJsonObject("properties").getAsJsonObject("tool_name").getAsJsonArray("enum");
            helper.assertTrue(ids.contains(new JsonPrimitive("gui_action")) && !ids.contains(new JsonPrimitive("craft_recipe")),"Frozen exact names distinguish tool ids from GUI action values");helper.succeed();
        } finally {TouhouAIFunConfig.AGENT_RUNTIME.set(enabled);}
    }
    @GameTest(batch="allowedTools",template="four_chests",templateNamespace="aifun_gui_test")
    public static void inlineProgressAndSelectionShareTransferAndNextOpeningWithoutReplanning(GameTestHelper helper) throws Exception {
        boolean enabled=TouhouAIFunConfig.AGENT_RUNTIME.get();TouhouAIFunConfig.AGENT_RUNTIME.set(true);
        try(var f=new Fixture(helper,5)) {
            f.state.steps=new ArrayList<>(List.of("取两枚铁锭","继续取剩余三枚"));f.open("inline_start");f.task.restrictTools(Set.of("gui_action","open_gui"));f.task.next(ignored->{});
            var request=f.moveRequest(2);request.addProperty("next_step",1);request.add("next_allowed_tools",JsonParser.parseString("[\"gui_batch\"]"));request.addProperty("close_after",true);
            var response=modelTool("inline_transfer","gui_action",request);var opening=new JsonObject();opening.addProperty("target","minecraft:chest");
            opening.addProperty("x",f.target.getX());opening.addProperty("y",f.target.getY());opening.addProperty("z",f.target.getZ());
            response.getToolCalls().add(modelTool("inline_next_open","open_gui",opening).getToolCalls().get(0));
            int[] requests={0};f.task.onFunctionCall(response,ignored->requests[0]++);
            helper.assertTrue(requests[0]==1 && f.state.currentStep==1 && f.chest().getItem(0).getCount()==6 && f.state.verifiedCount==2,"Transfer, progress, selection and next opening settle in one model response");
            helper.assertTrue(f.task.allowedTools().equals(Set.of("gui_batch")) && MaidGuiSessionManager.session(f.maid.getUUID())!=null,"Next request uses the selected action with the new live menu");
            var contract=f.state.completion.deepCopy();f.compact();f.task.next(ignored->{});
            helper.assertTrue(f.state.completion.equals(contract) && f.state.transfers.values().stream().mapToLong(Long::longValue).sum()==2 && f.state.currentStep==1,"Compression preserves full requirements and committed quantities after inline metadata");
            helper.succeed();
        } finally {TouhouAIFunConfig.AGENT_RUNTIME.set(enabled);}
    }
    @GameTest(batch="allowedTools",template="four_chests",templateNamespace="aifun_gui_test")
    public static void successfulInlineHintBindsToTheNewVerifiedPhase(GameTestHelper helper) throws Exception {
        boolean enabled=TouhouAIFunConfig.AGENT_RUNTIME.get();TouhouAIFunConfig.AGENT_RUNTIME.set(true);
        try(var f=new Fixture(helper,2)) {
            f.state.steps=new ArrayList<>(List.of("第一阶段取两枚","第二阶段再取一枚"));
            var sequence=new JsonObject();sequence.addProperty("kind","sequence");var phases=new JsonArray();phases.add(f.transfer(2));phases.add(f.transfer(1));sequence.add("phases",phases);
            f.state.completion=null;f.state.defineCompletion(sequence);f.open("next_phase_hint");f.task.restrictTools(Set.of("gui_action"));f.task.next(ignored->{});
            var contract=f.state.completion.deepCopy();var request=f.moveRequest(2);request.addProperty("next_step",1);request.add("next_allowed_tools",JsonParser.parseString("[\"gui_batch\"]"));
            f.task.onFunctionCall(modelTool("complete_phase_with_hint","gui_action",request),ignored->{});
            helper.assertTrue(f.state.completionStage==1 && f.state.currentStep==1 && Set.of("gui_batch").equals(f.task.allowedTools()),"An action completing a phase installs its hint for the next phase instead of immediately expiring it");
            helper.assertTrue(f.chest().getItem(0).getCount()==6 && f.state.completion.equals(contract) && f.state.transfers.values().stream().mapToLong(Long::longValue).sum()==2 && f.state.activeTransfers().values().stream().mapToLong(Long::longValue).sum()==0,"The new phase keeps its own baseline, the unchanged full contract and completed earlier receipts");
            helper.succeed();
        } finally {TouhouAIFunConfig.AGENT_RUNTIME.set(enabled);}
    }
    @GameTest(batch="allowedTools",template="four_chests",templateNamespace="aifun_gui_test")
    public static void invalidHintsDoNotBlockSafeActionsAndFailedActionsCannotAdvanceProgress(GameTestHelper helper) throws Exception {
        boolean enabled=TouhouAIFunConfig.AGENT_RUNTIME.get();TouhouAIFunConfig.AGENT_RUNTIME.set(true);
        try(var f=new Fixture(helper,5)) {
            f.state.steps=new ArrayList<>(List.of("当前取料","后续动作"));f.open("hint_validation");f.task.next(ignored->{});
            var gui=new com.wjx.touhou_aifun.compat.ai.action.GuiTool("gui_action");
            for(var value:List.of(JsonParser.parseString("[\"not_registered\"]"),new JsonPrimitive("gui_batch"))) {
                var bad=f.moveRequest(1);bad.add("next_allowed_tools",value);
                gui.onCallAsync("invalid_hint_"+value.hashCode(),bad,f.task,ignored->{}).join();
            }
            var badStep=f.moveRequest(1);badStep.addProperty("next_step",99);gui.onCallAsync("invalid_step",badStep,f.task,ignored->{}).join();
            helper.assertTrue(f.task.getMessages().stream().anyMatch(m->m.role()==Role.TOOL && "invalid_step".equals(m.toolCallId()) && m.message().contains("task_hint_ignored") && !AgentToolOutcome.failed(m.message())),"Bad optional metadata is reported without turning an authorized action into a failed retry");
            var unsupported=f.moveRequest(2);unsupported.addProperty("next_step",1);
            new com.wjx.touhou_aifun.compat.ai.action.GuiTool("inspect_gui").onCallAsync("unsupported_hint",unsupported,f.task,ignored->{}).join();
            helper.assertTrue(f.task.getMessages().stream().anyMatch(m->m.role()==Role.TOOL && "unsupported_hint".equals(m.toolCallId()) && m.message().contains("task_hint_not_supported")),"Tools without advertised hint fields cannot apply hidden metadata");
            var failed=f.moveRequest(2);failed.addProperty("to_slot",-1);failed.addProperty("next_step",1);failed.add("next_allowed_tools",JsonParser.parseString("[\"gui_batch\"]"));
            gui.onCallAsync("failed_real_transfer",failed,f.task,ignored->{}).join();
            helper.assertTrue(f.chest().getItem(0).getCount()==5 && f.state.currentStep==0 && f.state.transfers.values().stream().mapToLong(Long::longValue).sum()==3 && f.task.allowedTools()==null,"Only the three valid requested transfers execute; bad metadata and a failed native action cannot change plan progress or selection");
            var pending=TaskActionHints.prepare(f.task,failed);f.state.amend("新增要求：继续保留原条件，禁止碰金锭。");
            helper.assertTrue(!pending.commit() && f.state.currentStep==0 && f.state.transfers.values().stream().mapToLong(Long::longValue).sum()==3,"An owner amendment invalidates old metadata without discarding actual operation receipts");
            helper.succeed();
        } finally {TouhouAIFunConfig.AGENT_RUNTIME.set(enabled);}
    }
    @GameTest(batch="allowedTools",template="four_chests",templateNamespace="aifun_gui_test")
    public static void stableSelectedSchemasDoNotAuthorizeAClosedOrStaleMenu(GameTestHelper helper) throws Exception {
        boolean enabled=TouhouAIFunConfig.AGENT_RUNTIME.get();TouhouAIFunConfig.AGENT_RUNTIME.set(true);
        try(var f=new Fixture(helper,5)) {
            var opened=f.open("stable_wire");f.task.next(ignored->{});f.task.selectTools(Set.of("open_gui","gui_batch"));
            helper.assertTrue(f.task.allowedTools()!=null,"Repeated transfer selection actually narrows schemas");
            var original=f.state.completion.deepCopy();
            java.util.function.Supplier<String> schemas=()->new Gson().toJson(com.wjx.touhou_aifun.compat.ai.openai.ToolContextSelector.selected(f.maid,f.task).stream().map(com.wjx.touhou_aifun.compat.ai.openai.ToolCatalogSnapshot.Entry::anthropicTool).toList());
            String before=schemas.get();MaidGuiSessionManager.cancel(f.maid.getUUID(),"qa_close");f.task.next(ignored->{});
            helper.assertTrue(before.equals(schemas.get()),"Exact schema bytes remain stable when the menu closes");
            var request=new JsonObject();request.add("session_id",opened.get("session_id"));request.add("expected_revision",opened.get("revision"));
            request.add("actions",JsonParser.parseString("[{\"action\":\"transfer\",\"slot\":0,\"to_slot\":28,\"count\":1}]"));
            f.task.onFunctionCall(modelTool("stale_cached_schema","gui_batch",request),ignored->{});
            helper.assertTrue(f.chest().getItem(0).getCount()==8 && f.state.transfers.isEmpty() && f.state.completion.equals(original),"Cached availability never authorizes stale-session effects or changes requirements");
            helper.succeed();
        } finally {TouhouAIFunConfig.AGENT_RUNTIME.set(enabled);}
    }
    @GameTest(batch="allowedTools",template="four_chests",templateNamespace="aifun_gui_test")
    public static void preparationOnlySelectionRetainsRealInventoryActionsWithoutReplanning(GameTestHelper helper) throws Exception {
        boolean enabled=TouhouAIFunConfig.AGENT_RUNTIME.get();TouhouAIFunConfig.AGENT_RUNTIME.set(true);
        try(var f=new Fixture(helper,5)) {
            f.state.completion=null;f.state.defineCompletion(JsonParser.parseString("{\"kind\":\"inventory\",\"dimension\":\"minecraft:overworld\",\"item\":\"minecraft:iron_ingot\",\"count\":5}").getAsJsonObject());
            f.open("preparation_hint");f.task.next(ignored->{});var contract=f.state.completion.deepCopy();
            f.task.selectTools(Set.of("open_gui","query_recipes"));
            helper.assertTrue(f.task.allowedTools()==null && com.wjx.touhou_aifun.compat.ai.openai.ToolContextSelector.selected(f.maid,f.task).stream().anyMatch(e->e.id().equals("gui_action")) && f.state.transfers.isEmpty(),"One-shot inventory work keeps the ordinary executable menu without changing the cache prefix for a new restriction");
            f.task.onFunctionCall(modelTool("operate_after_repaired_selection","gui_action",f.moveRequest(1)),ignored->{});
            helper.assertTrue(f.chest().getItem(0).getCount()==7 && f.state.transfers.values().stream().mapToLong(Long::longValue).sum()==1 && f.state.completion.equals(contract),"The advertised action executes once through native quantity/session guards without a schema-loading turn");
            helper.succeed();
        } finally {TouhouAIFunConfig.AGENT_RUNTIME.set(enabled);}
    }
    @GameTest(batch="allowedTools",template="four_chests",templateNamespace="aifun_gui_test")
    public static void inlineSelectionCannotExpandItsOwnResponseAndBroadListsKeepStageMenus(GameTestHelper helper) throws Exception {
        boolean enabled=TouhouAIFunConfig.AGENT_RUNTIME.get();TouhouAIFunConfig.AGENT_RUNTIME.set(true);
        try(var f=new Fixture(helper,5)) {
            f.state.steps=new ArrayList<>(List.of("取部分材料","继续取料"));f.open("frozen_hint");f.task.next(ignored->{});
            var broad=com.wjx.touhou_aifun.compat.ai.openai.ToolContextSelector.snapshot(f.maid,f.task).planningEntries().stream().map(e->e.id()).collect(java.util.stream.Collectors.toSet());
            f.task.selectTools(broad);helper.assertTrue(f.task.allowedTools()==null,"A healthy task ignores an oversized list which saves no schemas");
            var inactive=com.wjx.touhou_aifun.compat.ai.openai.ToolContextSelector.snapshot(f.maid,f.task).planningEntries();
            for(var entry:inactive)if(Set.of("open_gui","gui_action","gui_batch").contains(entry.id()))
                helper.assertTrue(entry.anthropicTool().getAsJsonObject("input_schema").getAsJsonObject("properties").has("next_allowed_tools")==entry.id().equals("open_gui"),"Inactive selections do not repeat selection syntax on every action");
            f.task.restrictTools(Set.of("gui_action"));
            var active=com.wjx.touhou_aifun.compat.ai.openai.ToolContextSelector.snapshot(f.maid,f.task).planningEntries().stream().filter(e->e.id().equals("gui_action")).findFirst().orElseThrow();
            helper.assertTrue(active.anthropicTool().getAsJsonObject("input_schema").getAsJsonObject("properties").has("next_allowed_tools"),"An active selection refreshes and restores its inline adjustment syntax");
            var request=f.moveRequest(1);request.addProperty("next_step",1);request.add("next_allowed_tools",JsonParser.parseString("[\"gui_batch\"]"));
            var response=modelTool("set_next_action_menu","gui_action",request);var batch=new JsonObject();var session=MaidGuiSessionManager.session(f.maid.getUUID());
            batch.addProperty("session_id",session.id.toString());batch.addProperty("expected_revision",session.projection.revision());
            var actions=new JsonArray();actions.add(f.moveRequest(1));batch.add("actions",actions);
            response.getToolCalls().add(modelTool("not_advertised_yet","gui_batch",batch).getToolCalls().get(0));f.task.onFunctionCall(response,ignored->{});
            helper.assertTrue(f.chest().getItem(0).getCount()==7 && f.state.verifiedCount==1 && f.state.currentStep==1,"Only the originally advertised transfer executes; its next-step metadata is not mutation permission");
            helper.assertTrue(f.task.getMessages().stream().anyMatch(m->m.role()==Role.TOOL && "not_advertised_yet".equals(m.toolCallId()) && m.message().startsWith("tool_not_in_allowed_tools")),"Inline selection cannot grant tools in the same response");
            var all=com.wjx.touhou_aifun.compat.ai.openai.ToolContextSelector.snapshot(f.maid,f.task).planningEntries().stream().map(e->e.id()).collect(java.util.stream.Collectors.toSet());
            f.task.selectTools(all);helper.assertTrue(f.task.allowedTools()==null && f.state.transfers.values().stream().mapToLong(Long::longValue).sum()==1,"A broad or recovering selection offers no schema gain and leaves normal stage menus");
            helper.succeed();
        } finally {TouhouAIFunConfig.AGENT_RUNTIME.set(enabled);}
    }
    @GameTest(batch="allowedTools",template="four_chests",templateNamespace="aifun_gui_test")
    public static void verifiedPhaseExpiresOldMenuAndOwnerAmendmentRestoresCompletionSyntax(GameTestHelper helper) throws Exception {
        boolean enabled=TouhouAIFunConfig.AGENT_RUNTIME.get();TouhouAIFunConfig.AGENT_RUNTIME.set(true);
        try(var f=new Fixture(helper,2)) {
            var sequence=new JsonObject();sequence.addProperty("kind","sequence");var phases=new JsonArray();
            phases.add(f.transfer(2));phases.add(f.transfer(1));sequence.add("phases",phases);
            f.state.completion=null;f.state.defineCompletion(sequence);
            f.open("phase_start");f.task.next(ignored->{});f.task.restrictTools(Set.of("gui_batch"));
            f.move("verified_phase_receipt",2);
            f.task.next(ignored->{});
            helper.assertTrue(f.state.completionStage==1 && f.state.currentStep==0 && f.task.allowedTools()==null,"Verified phase progress expires the old tool mask even when the model forgot its textual step");
            var checkpoint=JsonParser.parseString(f.checkpoint().substring(f.checkpoint().indexOf('{'))).getAsJsonObject();
            helper.assertTrue(checkpoint.get("completion_stage").getAsInt()==1 && checkpoint.getAsJsonObject("active_stage_completion").get("count").getAsInt()==1 && !checkpoint.has("allowed_tools"),"The projected checkpoint agrees with the newly verified phase and expired selection");
            var catalogue=com.wjx.touhou_aifun.compat.ai.openai.ToolContextSelector.snapshot(f.maid,f.task);
            var planner=catalogue.planningEntries().stream().filter(e->e.id().equals("update_task_plan")).findFirst().orElseThrow();
            helper.assertTrue(!planner.anthropicTool().getAsJsonObject("input_schema").getAsJsonObject("properties").has("completion"),"A registered contract no longer resends its full definition syntax");
            helper.assertTrue(f.checkpoint().contains("completion_conditions") && f.checkpoint().contains("completed_net_transfers"),"Full requirements and authoritative receipts remain in the checkpoint");
            f.state.amend("保留原要求，再补充一枚；禁止碰金锭。");f.task.next(ignored->{});
            var restored=com.wjx.touhou_aifun.compat.ai.openai.ToolContextSelector.snapshot(f.maid,f.task).planningEntries().stream().filter(e->e.id().equals("update_task_plan")).findFirst().orElseThrow();
            helper.assertTrue(restored.anthropicTool().getAsJsonObject("input_schema").getAsJsonObject("properties").has("completion") && f.state.transfers.values().stream().mapToLong(Long::longValue).sum()==2,"Owner amendment restores registration syntax and preserves already executed transfers");
            helper.succeed();
        } finally {TouhouAIFunConfig.AGENT_RUNTIME.set(enabled);}
    }
    @GameTest(batch="allowedTools",template="four_chests",templateNamespace="aifun_gui_test")
    public static void invalidAllowlistCannotPartiallyRewritePlanCompletionOrReceipts(GameTestHelper helper) throws Exception {
        boolean enabled=TouhouAIFunConfig.AGENT_RUNTIME.get();TouhouAIFunConfig.AGENT_RUNTIME.set(true);
        try(var f=new Fixture(helper,5)) {
            f.open("atomic_allowed_open");f.move("atomic_committed_two",2);f.task.next(ignored->{});
            f.task.onFunctionCall(modelTool("atomic_initial_plan","update_task_plan",restrictedPlan()),ignored->{});
            var steps=List.copyOf(f.state.steps);var completion=f.state.completion.deepCopy();var receipts=new LinkedHashMap<>(f.state.transfers);
            var invalid=restrictedPlan();invalid.add("steps",JsonParser.parseString("[\"must not replace the valid plan\"]"));
            invalid.add("allowed_tools",JsonParser.parseString("[\"not_a_registered_tool\"]"));invalid.add("completion",f.transfer(999));
            f.task.onFunctionCall(modelTool("atomic_invalid_plan","update_task_plan",invalid),ignored->{});
            helper.assertTrue(f.state.steps.equals(steps) && f.state.completion.equals(completion) && f.state.transfers.equals(receipts),"All allowlist validation happens before any plan/completion/fact update");
            helper.assertTrue(f.task.getMessages().stream().anyMatch(m->m.role()==Role.TOOL && "atomic_invalid_plan".equals(m.toolCallId()) && m.message().startsWith("invalid_task_plan: unknown_allowed_tool")),"Invalid names are explicitly rejected");
            var clear=restrictedPlan();clear.add("allowed_tools",new JsonArray());
            f.task.onFunctionCall(modelTool("atomic_clear_plan","update_task_plan",clear),ignored->{});
            helper.assertTrue(f.task.allowedTools()==null && com.wjx.touhou_aifun.compat.ai.openai.ToolContextSelector.selected(f.maid,f.task).size()==9,"An empty list restores normal stage schemas");helper.succeed();
        } finally {TouhouAIFunConfig.AGENT_RUNTIME.set(enabled);}
    }

    @GameTest(batch="allowedTools",template="four_chests",templateNamespace="aifun_gui_test")
    public static void stepAllowlistShrinksSerializedSchemasAndBlocksOutOfListTransfers(GameTestHelper helper) throws Exception {
        boolean enabled=TouhouAIFunConfig.AGENT_RUNTIME.get();TouhouAIFunConfig.AGENT_RUNTIME.set(true);
        try(var f=new Fixture(helper,5)) {
            f.open("allowed_open");f.task.next(ignored->{});
            var catalog=com.wjx.touhou_aifun.compat.ai.openai.ToolContextSelector.snapshot(f.maid,f.task);
            var before=catalog.selected(f.state.loadedTools);int beforeBudget=catalog.schemaBudget(f.state.loadedTools);
            int beforeTokens=before.stream().mapToInt(com.wjx.touhou_aifun.compat.ai.openai.ToolCatalogSnapshot.Entry::schemaTokens).sum();
            f.task.onFunctionCall(modelTool("allow_plan","update_task_plan",restrictedPlan()),ignored->{});
            helper.assertTrue(f.task.allowedTools()!=null,"The real planning dispatcher installs the optional allowlist");
            var after=catalog.selected(f.state.loadedTools);var expected=Set.of("gui_action","update_task_plan","load_tool_schema","read_task_result");
            helper.assertTrue(after.stream().map(com.wjx.touhou_aifun.compat.ai.openai.ToolCatalogSnapshot.Entry::id).collect(java.util.stream.Collectors.toSet()).equals(expected),"Four actual schemas are sent, with recovery capabilities");
            int afterTokens=after.stream().mapToInt(com.wjx.touhou_aifun.compat.ai.openai.ToolCatalogSnapshot.Entry::schemaTokens).sum();
            helper.assertTrue(afterTokens<beforeTokens && catalog.schemaBudget(f.state.loadedTools)<beforeBudget,"Both serialized schemas and the reserved context shrink");
            var anthropic=new com.wjx.touhou_aifun.compat.ai.openai.AnthropicCompatLLMClient(null,null);
            var anthropicBuild=anthropic.getClass().getDeclaredMethod("buildRequestBody",LLMCallback.class);anthropicBuild.setAccessible(true);
            var responses=new com.wjx.touhou_aifun.compat.ai.openai.OpenAIResponsesCompatLLMClient(null,null);
            var responseBuild=responses.getClass().getDeclaredMethod("buildRequest",LLMCallback.class,com.wjx.touhou_aifun.compat.ai.openai.ToolCatalogSnapshot.class);responseBuild.setAccessible(true);
            for(var body:List.of((JsonObject)anthropicBuild.invoke(anthropic,f.task),(JsonObject)responseBuild.invoke(responses,f.task,catalog)))
                helper.assertTrue(body.getAsJsonArray("tools").asList().stream().map(v->v.getAsJsonObject().get("name").getAsString()).collect(java.util.stream.Collectors.toSet()).equals(expected),"Anthropic and Responses serialize the same narrowed set");
            System.out.println("ALLOWED_TOOLS_METRICS {\"before_tools\":"+before.size()+",\"after_tools\":"+after.size()+",\"before_schema_tokens\":"+beforeTokens+",\"after_schema_tokens\":"+afterTokens+",\"before_reserved_tokens\":"+beforeBudget+",\"after_reserved_tokens\":"+catalog.schemaBudget(f.state.loadedTools)+"}");
            var session=MaidGuiSessionManager.session(f.maid.getUUID());
            JsonObject transfer=new JsonObject();transfer.addProperty("action","transfer");transfer.addProperty("slot",0);transfer.addProperty("to_slot",f.backpackSlot(session));transfer.addProperty("count",1);
            JsonObject batch=new JsonObject();batch.addProperty("session_id",session.id.toString());batch.addProperty("expected_revision",session.projection.revision());var actions=new JsonArray();actions.add(transfer);batch.add("actions",actions);
            f.task.onFunctionCall(modelTool("unadvertised_batch","gui_batch",batch),ignored->{});
            helper.assertTrue(f.chest().getItem(0).getCount()==8 && f.state.verifiedCount==0,"A valid transfer in a forbidden tool does not execute");
            helper.assertTrue(f.task.getMessages().stream().anyMatch(m->m.role()==Role.TOOL && "unadvertised_batch".equals(m.toolCallId()) && m.message().startsWith("tool_not_in_allowed_tools")),"Rejection is explicit and stays paired");
            helper.assertTrue(f.task.allowedTools()==null,"Failed dispatch restores normal recovery options");helper.succeed();
        } finally {TouhouAIFunConfig.AGENT_RUNTIME.set(enabled);}
    }
    @GameTest(batch="allowedTools",template="four_chests",templateNamespace="aifun_gui_test")
    public static void allowlistCanExpandAndExpiresWithoutChangingOwnerRequirementsOrReceipts(GameTestHelper helper) throws Exception {
        boolean enabled=TouhouAIFunConfig.AGENT_RUNTIME.get();TouhouAIFunConfig.AGENT_RUNTIME.set(true);
        try(var f=new Fixture(helper,5)) {
            f.open("expand_open");f.move("committed_two",2);f.task.next(ignored->{});
            f.task.onFunctionCall(modelTool("initial_allow_plan","update_task_plan",restrictedPlan()),ignored->{});
            var load=JsonParser.parseString("{\"tool_name\":\"inspect_gui\"}").getAsJsonObject();
            f.task.onFunctionCall(modelTool("expand_inspection","load_tool_schema",load),ignored->{});
            var selected=com.wjx.touhou_aifun.compat.ai.openai.ToolContextSelector.selected(f.maid,f.task).stream().map(com.wjx.touhou_aifun.compat.ai.openai.ToolCatalogSnapshot.Entry::id).toList();
            helper.assertTrue(selected.contains("inspect_gui") && !selected.contains("gui_batch") && !selected.contains("wait_gui"),"Loading expands only the exact missing tool");
            helper.assertTrue(f.state.verifiedCount==2 && f.state.goal.contains("禁止碰金锭"),"Tool policy changes leave goal and physical progress intact");
            var close=new JsonObject();close.addProperty("session_id",MaidGuiSessionManager.session(f.maid.getUUID()).id.toString());
            f.task.dispatchAutomatic("close_gui",close,ignored->{});
            helper.assertTrue(MaidGuiSessionManager.session(f.maid.getUUID())==null && f.state.verifiedCount==2,"Trusted native cleanup retains its own guards instead of being blocked by the LLM menu");
            f.state.currentStep=1;helper.assertTrue(f.task.allowedTools()==null,"Changing steps drops the old restriction");
            f.task.restrictTools(Set.of("gui_action"));f.state.steps.set(1,"改变本步骤的计划说明");helper.assertTrue(f.task.allowedTools()==null,"Rewriting a step at the same index also expires the allowlist");
            f.task.restrictTools(Set.of("gui_action"));f.state.amend("补充：不要碰金锭，已拿的两枚不许重拿。");f.task.next(ignored->{});
            helper.assertTrue(f.task.allowedTools()==null && f.state.constraints.stream().anyMatch(s->s.contains("不许重拿"))
                    && f.state.transfers.values().stream().mapToLong(Long::longValue).sum()==2,"Owner amendment restores tools without discarding requirements or committed transfer receipts");
            f.state.defineCompletion(f.transfer(5));helper.assertTrue(f.state.verifiedCount==2,"Re-establishing completion after an amendment reuses the already delivered two");
            f.task.restrictTools(Set.of("gui_action"));f.reload();helper.assertTrue(f.task.allowedTools()==null && f.state.verifiedCount==2,"New generations re-observe with normal tools and preserve committed facts");helper.succeed();
        } finally {TouhouAIFunConfig.AGENT_RUNTIME.set(enabled);}
    }

    @GameTest(batch="largeTaskBudget",template="four_chests",templateNamespace="aifun_gui_test")
    public static void protocolAdaptersDoNotApplyChatEvictionToTaskOwnerRequirements(GameTestHelper helper) throws Exception {
        int budget=TouhouAIFunConfig.CONTEXT_INPUT_BUDGET_TOKENS.get();TouhouAIFunConfig.CONTEXT_INPUT_BUDGET_TOKENS.set(8192);
        try(var f=new Fixture(helper,5)) {
            var owners=new ArrayList<LLMMessage>();
            for(int i=0;i<5;i++) {
                var owner=LLMMessage.userChat(f.maid,"补充要求 "+i+"：指定的箱子外一律不要动。");owners.add(owner);f.task.getMessages().add(owner);
                f.task.getMessages().add(new LLMMessage(Role.ASSISTANT,"Previous acknowledgement ".repeat(1600),0));
            }
            var site=new com.wjx.touhou_aifun.compat.ai.openai.ReasoningCompatOpenAISite("context_qa",new net.minecraft.resources.ResourceLocation("test","icon"),"http://127.0.0.1/never",true,"qa-unused",false,Map.of(),List.of(new com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.LLMOpenAISite.ModelEntry("mock")));
            List<LLMClient> clients=List.of(new com.wjx.touhou_aifun.compat.ai.openai.ReasoningCompatOpenAIClient(null,site),
                    new com.wjx.touhou_aifun.compat.ai.openai.AnthropicCompatLLMClient(null,site),
                    new com.wjx.touhou_aifun.compat.ai.openai.OpenAIResponsesCompatLLMClient(null,site),
                    new com.wjx.touhou_aifun.compat.ai.openai.ChatGPTResponsesClient(null,null));
            for(var client:clients) {
                // Stop at the absent transport/site, after the real chat entry point prepares its
                // request. No connection, credential lookup, paid API or model response is used.
                try {client.chat(f.task);throw new AssertionError("QA transport must be absent");}
                catch(NullPointerException expectedAtTransport) { }
                helper.assertTrue(f.task.getMessages().containsAll(owners),client.getClass().getSimpleName()+" must retain every owner amendment despite the 8K chat budget");
            }
            var manager=f.maid.getAiChatManager();var memory=com.wjx.touhou_aifun.chat.context.AIFunMemoryManager.state(manager);
            String api=manager.getLLMSite()==null?"":manager.getLLMSite().getApiType(), model=manager.getLLMModel()==null?"":manager.getLLMModel();
            int base=com.wjx.touhou_aifun.chat.context.ContextTokenEstimator.estimate(f.task.getMessages());
            memory.tokenCalibrations().clear();memory.tokenCalibrations().add(new com.wjx.touhou_aifun.chat.context.MaidMemoryState.TokenCalibration(api,model,2,0));
            helper.assertTrue(com.wjx.touhou_aifun.chat.context.AIFunMemoryManager.calibratedEstimate(manager,f.task.getMessages())==base,"Legacy calibration cannot inflate the new estimator");
            memory.tokenCalibrations().add(new com.wjx.touhou_aifun.chat.context.MaidMemoryState.TokenCalibration(api,model,1.5));
            helper.assertTrue(com.wjx.touhou_aifun.chat.context.AIFunMemoryManager.calibratedEstimate(manager,f.task.getMessages())==(int)Math.ceil(base*1.5),"Current provider calibration still applies");
            helper.succeed();
        } finally {TouhouAIFunConfig.CONTEXT_INPUT_BUDGET_TOKENS.set(budget);}
    }

    @GameTest(batch="largeTaskBudget",template="four_chests",templateNamespace="aifun_gui_test")
    public static void deduplicatedObservationsKeepOwnerTextAndRealTransferProgress(GameTestHelper helper) throws Exception {
        try(var f=new Fixture(helper,5)) {
            var opened=f.open("efficiency_open");recordForContext(f.task,"efficiency_open","open_gui",opened.toString());
            var moved=f.move("efficiency_first_two",2);recordForContext(f.task,"efficiency_first_two","gui_action",moved.toString());
            var owner=LLMMessage.userChat(f.maid,"补充要求：只使用这个箱子，已经拿的两枚也计入五枚总数，禁止碰金锭。");
            f.task.getMessages().add(owner);
            for(int i=0;i<12;i++) {
                var scan=com.wjx.touhou_aifun.vision.scan.VisionScanCache.scan(f.maid,com.wjx.touhou_aifun.vision.scan.EnvironmentScanRequest.defaults());
                recordForContext(f.task,"efficiency_scan_"+i,"scan_surroundings",scan.toJson());
                var request=new JsonObject();request.addProperty("session_id",MaidGuiSessionManager.session(f.maid.getUUID()).id.toString());
                var inspected=MaidGuiSessionManager.call(f.task,"inspect_gui","efficiency_inspect_"+i,request).join();
                helper.assertTrue(!inspected.has("error"),inspected.toString());recordForContext(f.task,"efficiency_inspect_"+i,"inspect_gui",inspected.toString());
            }
            int before=com.wjx.touhou_aifun.chat.context.ContextTokenEstimator.estimate(f.task.getMessages());
            helper.assertTrue(AgentContext.prepare(f.task),"Large-budget request is ready after exact deduplication");
            int after=com.wjx.touhou_aifun.chat.context.ContextTokenEstimator.estimate(f.task.getMessages());
            helper.assertTrue(after<before && f.task.getMessages().contains(owner),"Repeated observations shrink while owner text remains verbatim");
            helper.assertTrue(f.state.verifiedCount==2 && f.chest().getItem(0).getCount()==6,"Context projection performs no world mutation");
            final int[] requests={0};f.task.next(callback->{requests[0]++;helper.assertTrue(f.checkpoint().contains("completed_net_transfers") && f.checkpoint().contains("mutation_version"),"New checkpoint still exposes durable transfer progress");});
            helper.assertTrue(requests[0]==1,"Task continues after request preparation");
            System.out.println("CONTEXT_EFFICIENCY_WORLD {\"observations\":24,\"before_tokens\":"+before+",\"after_tokens\":"+after+"}");
            f.reload();f.open("efficiency_reload_open");
            helper.assertTrue(f.move("efficiency_repeat_full_five",5).has("error") && f.chest().getItem(0).getCount()==6,"Reload and a new call ID cannot repeat the already delivered two");
            helper.assertTrue(f.move("efficiency_remaining_three",3).get("moved_count").getAsInt()==3 && f.state.verifiedCount==5,"Only the remaining three are delivered");helper.succeed();
        }
    }
    private static void recordForContext(TaskCallback task,String id,String tool,String result) {
        task.getMessages().add(new LLMMessage(Role.ASSISTANT,"",0,List.of(new ToolCall(id,new FunctionToolCall(tool,"{}"))),null));
        task.addToolResult(result,id);
    }

    @GameTest(batch="largeTaskBudget",template="four_chests",templateNamespace="aifun_gui_test")
    public static void largeExecutionBudgetReachesRequestPreparationAndKeepsReloadedTransferProgress(GameTestHelper helper) throws Exception {
        int budget=TouhouAIFunConfig.AGENT_CONTEXT_INPUT_BUDGET_TOKENS.get();
        int chatBudget=TouhouAIFunConfig.CONTEXT_INPUT_BUDGET_TOKENS.get();
        TouhouAIFunConfig.AGENT_CONTEXT_INPUT_BUDGET_TOKENS.set(475000);
        TouhouAIFunConfig.CONTEXT_INPUT_BUDGET_TOKENS.set(8192);
        try(var f=new Fixture(helper,5)) {
            f.open("large_budget_open");helper.assertTrue(f.move("large_budget_first_two",2).get("moved_count").getAsInt()==2,"Initial two items actually move");
            var evidence=new LLMMessage(Role.SYSTEM,"## Archived tool evidence\n"+"x".repeat(160000),0);
            var owner=LLMMessage.userChat(f.maid,"补充要求：禁止碰金锭，已经取走的两枚也计入总数。");
            f.task.getMessages().add(evidence);f.task.getMessages().add(owner);
            helper.assertTrue(com.wjx.touhou_aifun.chat.context.ContextTokenEstimator.estimate(f.task.getMessages())>32768,"Request exceeds the former execution budget");
            helper.assertTrue(AgentContext.prepareTask(f.task,callback->{throw new AssertionError("475K must not summarize history that fits");}).join(),"Async preparation accepts the larger task budget");
            helper.assertTrue(AgentContext.prepare(f.task),"Protocol request preparation uses the same large execution budget");
            helper.assertTrue(f.task.getMessages().contains(evidence) && f.task.getMessages().contains(owner),"Evidence and latest owner requirement remain verbatim despite a smaller chat budget");
            f.reload();f.open("large_budget_reload_open");
            helper.assertTrue(f.move("large_budget_new_id_repeat",5).has("error") && f.chest().getItem(0).getCount()==6,"Reload and a new call ID cannot replay all five");
            helper.assertTrue(f.move("large_budget_remaining_three",3).get("moved_count").getAsInt()==3 && f.state.verifiedCount==5 && f.chest().getItem(0).getCount()==3,"Exactly the remaining three items move");
            helper.succeed();
        } finally {
            TouhouAIFunConfig.AGENT_CONTEXT_INPUT_BUDGET_TOKENS.set(budget);
            TouhouAIFunConfig.CONTEXT_INPUT_BUDGET_TOKENS.set(chatBudget);
        }
    }

    @GameTest(batch="longTaskContext",template="four_chests",templateNamespace="aifun_gui_test")
    public static void amendedSequenceRetainsCollectedMaterialsAndCannotReplayCompletedPhase(GameTestHelper helper) throws Exception {
        try(var f=new Fixture(helper,5)) {
            var phases=new JsonArray();phases.add(f.transfer(5));
            var future=new JsonObject();future.addProperty("kind","position");future.addProperty("dimension","minecraft:overworld");
            var pos=new JsonArray();var far=helper.absolutePos(new BlockPos(6,1,9));pos.add(far.getX());pos.add(far.getY());pos.add(far.getZ());future.add("position",pos);future.addProperty("radius",1);phases.add(future);
            var contract=new JsonObject();contract.addProperty("kind","sequence");contract.add("phases",phases);f.state.completion=contract;
            f.open("original_materials");helper.assertTrue(f.move("first_two",2).get("moved_count").getAsInt()==2,"Two actual materials received before owner amendment");
            f.state.amend("补充最终检查，所有旧要求保留，不要重取已到手的两枚");f.task.next(ignored->{});
            helper.assertTrue(f.checkpoint().contains("sequence_resume_stage") && f.checkpoint().contains("verified_sequence_prefix"),"Replanning receives the preserved sequence scope explicitly");
            f.state.defineCompletion(contract);
            f.compact();f.reload();helper.assertTrue(f.state.verifiedCount==2,"Sequence progress survives amendment, compression and reload");
            f.open("after_amendment");helper.assertTrue(f.move("replay_full_five",5).has("error") && f.chest().getItem(0).getCount()==6,"Only the remaining three may move");
            helper.assertTrue(f.move("remaining_three",3).get("moved_count").getAsInt()==3 && f.state.completionStage==1,"The real final three advance the original sequence");
            helper.assertTrue(f.move("stale_previous_phase",1).has("error") && f.chest().getItem(0).getCount()==3,"A stale call cannot repeat a transfer from a finished phase");
            helper.succeed();
        }
    }

    @GameTest(batch="longTaskContext",template="four_chests",templateNamespace="aifun_gui_test")
    public static void compressedReloadedTaskRejectsNewIdRepeatsAndOnlyMovesRemainingItems(GameTestHelper helper) throws Exception {
        try(var f=new Fixture(helper,5)) {
            f.open("first_open");helper.assertTrue(f.move("first_move",2).get("moved_count").getAsInt()==2,"First two items actually move");
            f.compact();f.reload();f.open("new_generation_open");
            var duplicate=f.move("different_call_id_same_full_quantity",5);
            helper.assertTrue(duplicate.has("error") && duplicate.get("error").getAsString().startsWith("task_transfer_exceeds_remaining"),duplicate.toString());
            helper.assertTrue(f.chest().getItem(0).getCount()==6 && f.state.verifiedCount==2,"Replay changes neither real chest nor durable progress");
            helper.assertTrue(f.move("remaining_three",3).get("moved_count").getAsInt()==3,"Only remaining three move");
            helper.assertTrue(f.state.verifiedCount==5 && f.chest().getItem(0).getCount()==3,"Exactly five actual items delivered");
            var session=MaidGuiSessionManager.session(f.maid.getUUID());JsonObject quick=new JsonObject();quick.addProperty("session_id",session.id.toString());quick.addProperty("action","quick_move");quick.addProperty("slot",0);
            var blocked=MaidGuiSessionManager.call(f.task,"gui_action","new_quick_move_id",quick).join();
            helper.assertTrue(blocked.has("error") && f.chest().getItem(0).getCount()==3,"Quick-move cannot bypass completed quantity");helper.succeed();
        }
    }

    @GameTest(batch="longTaskContext",template="four_chests",templateNamespace="aifun_gui_test",timeoutTicks=80)
    public static void consumedServingSurvivesCompressionReloadAndAppearsInCheckpoint(GameTestHelper helper) throws Exception {
        var f=new Fixture(helper,1);
        helper.setBlock(f.local.below(),net.minecraftforge.registries.ForgeRegistries.BLOCKS.getValue(new net.minecraft.resources.ResourceLocation("touhou_little_maid","snack_cabinet")));
        helper.setBlock(f.local,Blocks.CAKE);
        f.state.completion=JsonParser.parseString("{\"kind\":\"eat_food\",\"dimension\":\"minecraft:overworld\",\"food\":\"minecraft:cake\",\"count\":1}").getAsJsonObject();
        var first=MaidEatFoodActionManager.start(f.maid,"minecraft:cake",8,false,f.task);
        helper.runAfterDelay(3,()-> {
            try {
                helper.assertTrue(first.isDone() && first.join().success(),"First serving was really consumed");f.compact();f.reload();
                final int[] requests={0};f.task.next(callback-> {requests[0]++;helper.assertTrue(f.checkpoint().contains("\"completed_actions\":[{\"success\":true") && f.checkpoint().contains("\"servings_consumed\":1"),"Current checkpoint retains durable consumed serving");});
                helper.assertTrue(requests[0]==1,"Fresh model request is constructed after reload");
                var second=MaidEatFoodActionManager.start(f.maid,"minecraft:cake",8,false,f.task);
                helper.runAfterDelay(3,()-> {
                    try {
                        helper.assertTrue(second.isDone() && !second.join().success(),"New call cannot consume the recorded serving again");
                        helper.assertTrue(helper.getBlockState(f.local).getValue(CakeBlock.BITES)==1,"Exactly one real bite remains recorded after retry");helper.succeed();
                    } finally {f.close();}
                });
            } catch(Throwable failure) {f.close();helper.fail(failure.toString());}
        });
    }

    @GameTest(batch="longTaskSummaryRace",template="four_chests",templateNamespace="aifun_gui_test")
    public static void anInFlightSummaryCannotEraseNewOwnerText(GameTestHelper helper) throws Exception {
        int budget=TouhouAIFunConfig.AGENT_CONTEXT_INPUT_BUDGET_TOKENS.get();TouhouAIFunConfig.AGENT_CONTEXT_INPUT_BUDGET_TOKENS.set(16384);
        try(var f=new Fixture(helper,5)) {
            f.task.getMessages().add(new LLMMessage(Role.SYSTEM,"## Archived tool evidence\n"+"x".repeat(48000),0));
            LLMCallback[] pending={null};int[] calls={0};
            LLMClient summaries=callback->{if(calls[0]++==0)pending[0]=callback;else callback.onSuccess(new ResponseChat("Old inspection facts only."));};
            var prepared=AgentContext.prepareTask(f.task,summaries);helper.assertTrue(!prepared.isDone() && pending[0]!=null,"Summary request is actually pending");
            var latest=LLMMessage.userChat(f.maid,"临时要求：红箱里的东西全部不要动，只用蓝箱。");f.task.getMessages().add(latest);
            pending[0].onSuccess(new ResponseChat("Old facts before the new instruction."));
            helper.assertTrue(prepared.join() && f.task.getMessages().contains(latest),"Late summary never replaces newly appended owner requirements");helper.succeed();
        } finally {TouhouAIFunConfig.AGENT_CONTEXT_INPUT_BUDGET_TOKENS.set(budget);}
    }

    @GameTest(batch="longTaskContext",template="four_chests",templateNamespace="aifun_gui_test",timeoutTicks=80)
    public static void completedCraftCannotConsumeAnotherBatchOfIngredientsAfterReload(GameTestHelper helper) throws Exception {
        var f=new Fixture(helper,2);helper.setBlock(f.local,Blocks.CRAFTING_TABLE);
        f.state.completion.addProperty("item","aifun_gui_test:machine");
        f.maid.getMaidInv().setStackInSlot(0,new ItemStack(Items.OAK_PLANKS,4));f.maid.getMaidInv().setStackInSlot(1,new ItemStack(Items.IRON_INGOT,2));
        var open=new JsonObject();open.addProperty("target","minecraft:crafting_table");open.addProperty("x",f.target.getX());open.addProperty("y",f.target.getY());open.addProperty("z",f.target.getZ());
        helper.assertTrue(!MaidGuiSessionManager.call(f.task,"open_gui","craft_open",open).join().has("error"),"Workbench opens");
        var action=new JsonObject();action.addProperty("session_id",MaidGuiSessionManager.session(f.maid.getUUID()).id.toString());
        action.addProperty("action","craft_recipe");action.addProperty("recipe_id","aifun_gui_test:qa_machine_shaped");action.addProperty("count",2);
        var first=MaidGuiSessionManager.call(f.task,"gui_action","craft_first_batch",action);
        helper.runAfterDelay(4,()-> {
            try {
                helper.assertTrue(first.isDone() && !first.join().has("error") && f.state.verifiedCount==2,"Actual crafting produces the required pair");
                f.compact();f.reload();helper.assertTrue(!MaidGuiSessionManager.call(f.task,"open_gui","craft_reloaded_open",open).join().has("error"),"Workbench reopens after reload");
                action.addProperty("session_id",MaidGuiSessionManager.session(f.maid.getUUID()).id.toString());
                var repeated=MaidGuiSessionManager.call(f.task,"gui_action","new_craft_id",action).join();
                helper.assertTrue(repeated.has("error") && repeated.get("error").getAsString().startsWith("task_transfer_exceeds_remaining"),repeated.toString());
                int planks=0,iron=0,products=0;var bag=f.maid.getAvailableBackpackInv();
                for(int i=0;i<bag.getSlots();i++) {var stack=bag.getStackInSlot(i);if(stack.is(Items.OAK_PLANKS))planks+=stack.getCount();if(stack.is(Items.IRON_INGOT))iron+=stack.getCount();if(stack.is(GuiFixtureMod.MACHINE.get().asItem()))products+=stack.getCount();}
                helper.assertTrue(planks==2 && iron==1 && products==2,"No extra ingredients consumed and no duplicate products after reload");helper.succeed();
            } catch(Throwable failure) {helper.fail(failure.toString());} finally {f.close();}
        });
    }

    @GameTest(batch="longTaskSummaryRace",template="four_chests",templateNamespace="aifun_gui_test")
    public static void latePhysicalSettlementRebuildsCheckpointBeforeMainModelRequest(GameTestHelper helper) throws Exception {
        int budget=TouhouAIFunConfig.AGENT_CONTEXT_INPUT_BUDGET_TOKENS.get();TouhouAIFunConfig.AGENT_CONTEXT_INPUT_BUDGET_TOKENS.set(16384);
        try(var f=new Fixture(helper,5)) {
            f.open("late_open");f.task.getMessages().add(new LLMMessage(Role.SYSTEM,"## Archived tool evidence\n"+"late physical settlement ".repeat(2400),0));
            LLMCallback[] pending={null};int[] summaries={0},main={0};
            LLMClient client=callback->{
                if(AgentExecution.context(callback).purpose()==AgentExecution.Purpose.KNOWLEDGE_EXTRACTION) {
                    if(summaries[0]++==0)pending[0]=callback;else callback.onSuccess(new ResponseChat("Old inspection records."));
                } else {main[0]++;helper.assertTrue(f.checkpoint().contains("\"mutation_version\":1") && f.state.verifiedCount==1,"Main request sees the newly settled mutation");}
            };
            f.task.next(client);helper.assertTrue(pending[0]!=null && main[0]==0,"No main request while summary is pending");
            var session=MaidGuiSessionManager.session(f.maid.getUUID());
            helper.assertTrue(MaidGuiSessionManager.transfer(session,0,f.backpackSlot(session),1,true)==1,"Physical transfer settles while summary is in flight");
            pending[0].onSuccess(new ResponseChat("Stale summary must not replace the new checkpoint."));
            helper.assertTrue(main[0]==1 && f.chest().getItem(0).getCount()==7,"Only one main request uses the refreshed durable checkpoint");helper.succeed();
        } finally {TouhouAIFunConfig.AGENT_CONTEXT_INPUT_BUDGET_TOKENS.set(budget);}
    }
}
