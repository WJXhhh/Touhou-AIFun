package com.wjx.aifun_gui_test;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.*;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.response.Message;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.init.InitEntities;
import com.google.gson.*;
import com.wjx.touhou_aifun.chat.ChatFlowManager;
import com.wjx.touhou_aifun.chat.agent.*;
import com.wjx.touhou_aifun.compat.ai.action.GuiTool;
import com.wjx.touhou_aifun.config.TouhouAIFunConfig;
import com.wjx.touhou_aifun.maid.gui.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraftforge.gametest.*;
import java.util.*;

@GameTestHolder("aifun_gui_test")
@PrefixGameTestTemplate(false)
public final class GuiTaskFinalizationGameTests {
    @GameTest(batch="isolated_storageRulePausesWithoutRepeatingSettledTransfers",template="empty",templateNamespace="aifun_gui_test",timeoutTicks=100)
    public static void storageRulePausesWithoutRepeatingSettledTransfers(GameTestHelper helper) throws Exception {
        var f=new Fixture(helper,0);
        try {
            var iron=new JsonObject();iron.addProperty("kind","transfer");iron.addProperty("item","minecraft:iron_ingot");iron.addProperty("count",2);iron.addProperty("to_maid",true);
            iron.addProperty("dimension",f.maid.level().dimension().location().toString());var position=new JsonArray();position.add(f.position.getX());position.add(f.position.getY());position.add(f.position.getZ());iron.add("position",position);
            var output=iron.deepCopy();output.addProperty("kind","inventory");output.addProperty("item","minecraft:shulker_box");output.addProperty("count",1);output.remove("position");output.remove("to_maid");
            var conditions=new JsonArray();conditions.add(iron);conditions.add(output);var all=new JsonObject();all.addProperty("kind","all");all.add("conditions",conditions);f.task.defineCompletion(all);
            helper.assertTrue(!f.open().has("error"),"Real chest opened");f.run("gui_action",f.action(),false);
            helper.assertTrue(f.chest().isEmpty() && f.state.transfers.values().stream().mapToLong(Long::longValue).sum()==2,"Two actual iron transfers settled before the later blocker");
            MaidGuiSessionManager.cancel(f.maid.getUUID(),"qa_change_station");helper.setBlock(f.local,Blocks.CRAFTING_TABLE);
            f.maid.getMaidInv().setStackInSlot(1,new ItemStack(Items.SHULKER_SHELL,2));f.maid.getMaidInv().setStackInSlot(2,new ItemStack(Items.CHEST));f.maid.setHomeModeEnable(true);f.maid.restrictTo(f.position,16);
            var open=f.target(f.position);open.addProperty("target","minecraft:crafting_table");var opened=MaidGuiSessionManager.call(f.task,"open_gui","blocked_workbench",open).join();
            helper.assertTrue(!opened.has("error"),opened.toString());
            var inventoryBefore=new ArrayList<ItemStack>();for(int i=0;i<f.maid.getAvailableBackpackInv().getSlots();i++)inventoryBefore.add(f.maid.getAvailableBackpackInv().getStackInSlot(i).copy());
            var handBefore=f.maid.getMainHandItem().copy();
            var action=new JsonObject();action.add("session_id",opened.get("session_id"));action.addProperty("action","craft_recipe");action.addProperty("recipe_id","minecraft:shulker_box");action.addProperty("count",1);
            var calls=new JsonArray();calls.add(call("rejected_craft","gui_action",action));calls.add(call("must_not_toggle","switch_follow_state",new JsonObject()));
            var response=new JsonObject();response.add("tool_calls",calls);int[] requests={0};f.task.onFunctionCall(new Gson().fromJson(response,Message.class),ignored->requests[0]++);
            helper.runAfterDelay(8,()->{
                try {
                    helper.assertTrue(f.state.status==AgentTaskState.Status.paused && f.state.outcome.contains("存放规则") && requests[0]==0,"Rule rejection pauses with its actual reason and no recovery model round");
                    helper.assertTrue(f.maid.isHomeModeEnable() && f.state.transfers.values().stream().mapToLong(Long::longValue).sum()==2,"Later actions are skipped and prior transfer receipts remain intact");
                    for(int i=0;i<inventoryBefore.size();i++)helper.assertTrue(ItemStack.matches(inventoryBefore.get(i),f.maid.getAvailableBackpackInv().getStackInSlot(i)),"Actual storage slot "+i+" is unchanged after rejection");
                    helper.assertTrue(ItemStack.matches(handBefore,f.maid.getMainHandItem()) && MaidGuiSessionManager.session(f.maid.getUUID())==null,"Hands remain unchanged and GUI settles safely");helper.succeed();
                }finally{f.close();}
            });
        }catch(Throwable error){f.close();throw error;}
    }
    @GameTest(batch="craftingTask",template="empty",templateNamespace="aifun_gui_test",timeoutTicks=100)
    public static void craftingToolCompletesRequestedModQuantityWithVerifiedBackpackDelivery(GameTestHelper helper) throws Exception {
        var f = new Fixture(helper, 0);
        try {
            f.state.goal = "Craft six compatibility machines using the maid backpack, return products and close the workbench";
            helper.setBlock(f.local, Blocks.CRAFTING_TABLE);
            f.maid.getMaidInv().setStackInSlot(0, new ItemStack(Items.OAK_PLANKS, 6));
            f.maid.getMaidInv().setStackInSlot(1, new ItemStack(Items.IRON_INGOT, 3));
            var query = new JsonObject(); query.addProperty("item", "aifun_gui_test:machine");
            var recipes = MaidGuiSessionManager.call(f.task, "query_recipes", "task_recipe", query).join();
            helper.assertTrue(recipes.getAsJsonArray("recipes").size() == 1, "Task sees actual loaded mod recipe");
            var open = f.target(f.position); open.addProperty("target", "minecraft:crafting_table");
            var opened = MaidGuiSessionManager.call(f.task, "open_gui", "task_workbench", open).join();
            helper.assertTrue(!opened.has("error"), opened.toString());
            var condition = new JsonObject(); condition.addProperty("kind", "transfer");
            condition.addProperty("item", "aifun_gui_test:machine"); condition.addProperty("count", 6); condition.addProperty("to_maid", true);
            condition.addProperty("dimension", f.maid.level().dimension().location().toString());
            var position = new JsonArray(); position.add(f.position.getX()); position.add(f.position.getY()); position.add(f.position.getZ()); condition.add("position", position);
            f.task.defineCompletion(condition);
            var action = new JsonObject(); action.add("session_id", opened.get("session_id")); action.addProperty("action", "craft_recipe");
            action.addProperty("recipe_id", "aifun_gui_test:qa_machine_shaped"); action.addProperty("count", 6); action.addProperty("complete_task", true);
            var calls = new JsonArray(); calls.add(call("craft_final", "gui_action", action)); var response = new JsonObject(); response.add("tool_calls", calls);
            int[] extraModel = {0}; f.task.onFunctionCall(new Gson().fromJson(response, Message.class), ignored -> extraModel[0]++);
            helper.runAfterDelay(8, () -> {
                try {
                    helper.assertTrue(f.state.status == AgentTaskState.Status.completed && f.state.verifiedCount == 6,
                            "Task completion verifies actual crafted transfer receipts: " + f.state.status + " / " + f.state.verifiedCount);
                    int count = 0; var backpack = f.maid.getAvailableBackpackInv();
                    for (int i = 0; i < backpack.getSlots(); i++) if (backpack.getStackInSlot(i).is(GuiFixtureMod.MACHINE.get().asItem())) count += backpack.getStackInSlot(i).getCount();
                    helper.assertTrue(count == 6 && MaidGuiSessionManager.session(f.maid.getUUID()) == null, "Products reach real backpack and final menu is settled");
                    helper.assertTrue(extraModel[0] == 0, "Verified final crafting needs no extra model completion round"); helper.succeed();
                } finally { f.close(); }
            });
        } catch (RuntimeException | Error failure) { f.close(); throw failure; }
    }

    @GameTest(batch="isolated_ownerDropReceiptsPreserveItemsAndPreventReplay",template="empty",templateNamespace="aifun_gui_test")
    public static void ownerDropReceiptsPreserveItemsAndPreventReplay(GameTestHelper helper) throws Exception {
        try(var f=new Fixture(helper,0)) {
            for(var pos:BlockPos.betweenClosed(new BlockPos(2,1,2),new BlockPos(5,3,2)))helper.setBlock(pos,Blocks.AIR);
            f.maid.getOwner().setPos(f.maid.getX()+2,f.maid.getY(),f.maid.getZ());
            var stack=new ItemStack(Items.GLOWSTONE,6);stack.getOrCreateTag().putString("identity","keep_nbt");f.maid.getMaidInv().setStackInSlot(0,stack);
            f.maid.getMaidInv().setStackInSlot(1,new ItemStack(Items.DIAMOND_BLOCK));
            var condition=new JsonObject();condition.addProperty("kind","drop_to_owner");condition.addProperty("dimension","minecraft:overworld");condition.addProperty("item","minecraft:glowstone");condition.addProperty("count",6);f.task.defineCompletion(condition);
            helper.assertTrue(!AgentCompletion.verify(f.maid,f.state,true),"Inventory and a promise cannot prove an owner drop");
            f.task.next(ignored->{});
            helper.assertTrue(com.wjx.touhou_aifun.compat.ai.openai.ToolContextSelector.selected(f.maid,f.task).stream().anyMatch(e->e.id().equals("drop_items_to_owner")),"The first execution menu after contract registration exposes the actual drop action without a load round");
            var request=new JsonObject();var items=new JsonArray();var item=new JsonObject();item.addProperty("item","minecraft:glowstone");item.addProperty("count",6);items.add(item);request.add("items",items);
            var operation=com.wjx.touhou_aifun.maid.action.MaidOwnerDropAction.start(f.task,request);
            helper.assertTrue(operation.isDone(),"Unobstructed nearby owner must not require movement");
            var dropped=operation.join();
            helper.assertTrue(!dropped.has("error") && AgentCompletion.verify(f.maid,f.state,true),dropped.toString());
            var receipt=dropped.getAsJsonArray("drop_receipts").get(0).getAsJsonObject();
            var entity=(net.minecraft.world.entity.item.ItemEntity)helper.getLevel().getEntity(UUID.fromString(receipt.get("entity_uuid").getAsString()));
            helper.assertTrue(entity!=null && entity.getItem().getCount()==6 && entity.getItem().getTag().getString("identity").equals("keep_nbt"),"Actual world entity preserves exact quantity and NBT");
            helper.assertTrue(entity.getDeltaMovement().dot(f.maid.getOwner().position().subtract(entity.position()))>0,"Velocity points toward live owner");
            entity.setNoPickUpDelay();helper.assertTrue(!f.maid.pickupItem(entity,false) && entity.isAlive() && entity.getItem().getCount()==6,"Native TLM pickup cannot immediately reclaim the owner handoff");
            helper.assertTrue(f.maid.getMaidInv().getStackInSlot(0).isEmpty() && f.maid.getMaidInv().getStackInSlot(1).is(Items.DIAMOND_BLOCK) && f.chest().getItem(0).getCount()==2,"Only authorized material is removed; no new ingredients acquired");
            var denied=com.wjx.touhou_aifun.maid.action.MaidOwnerDropAction.start(f.task,request).join();
            helper.assertTrue(denied.get("error").getAsString().contains("task_drop_exceeds_remaining") && f.state.actionFacts.size()==1,"Different calls cannot replay committed drops");
            var saved=AgentArchiveData.get(helper.getLevel()).save(new net.minecraft.nbt.CompoundTag());
            var restored=new AgentTaskState("restore drop receipts");AgentArchiveData.load(saved).task(f.maid.getUUID(),f.state.id).reconcile(restored);
            helper.assertTrue(restored.droppedToOwner("minecraft:overworld",f.maid.getOwnerUUID().toString(),"minecraft:glowstone")==6,"Durable archive reconciles actual drop receipts after context loss");
            entity.discard();helper.succeed();
        }
    }
    @GameTest(batch="isolated_ownerDropRejectsMissingContractAndRollsBackCancelledSpawn",template="empty",templateNamespace="aifun_gui_test")
    public static void ownerDropRejectsMissingContractAndRollsBackCancelledSpawn(GameTestHelper helper) throws Exception {
        try(var f=new Fixture(helper,0)) {
            for(var pos:BlockPos.betweenClosed(new BlockPos(2,1,2),new BlockPos(5,3,2)))helper.setBlock(pos,Blocks.AIR);
            f.maid.getOwner().setPos(f.maid.getX()+2,f.maid.getY(),f.maid.getZ());f.maid.getMaidInv().setStackInSlot(0,new ItemStack(Items.IRON_INGOT,2));
            var request=JsonParser.parseString("{\"items\":[{\"item\":\"minecraft:iron_ingot\",\"count\":2}]}").getAsJsonObject();
            helper.assertTrue(com.wjx.touhou_aifun.maid.action.MaidOwnerDropAction.start(f.task,request).join().has("error"),"No inventory-only or missing contract can authorize dropping");
            var condition=JsonParser.parseString("{\"kind\":\"drop_to_owner\",\"dimension\":\"minecraft:overworld\",\"item\":\"minecraft:iron_ingot\",\"count\":2}").getAsJsonObject();f.task.defineCompletion(condition);
            java.util.function.Consumer<net.minecraftforge.event.entity.EntityJoinLevelEvent> reject=event->{if(event.getEntity() instanceof net.minecraft.world.entity.item.ItemEntity item && item.getOwner()==f.maid)event.setCanceled(true);};
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(reject);
            try {
                var operation=com.wjx.touhou_aifun.maid.action.MaidOwnerDropAction.start(f.task,request);
                helper.assertTrue(operation.isDone(),"Nearby owner spawn rejection settles synchronously");var result=operation.join();
                helper.assertTrue(result.has("error") && f.maid.getMaidInv().getStackInSlot(0).getCount()==2 && f.state.actionFacts.isEmpty(),"Cancelled spawn restores real inventory and cannot create successful receipts");
            } finally {net.minecraftforge.common.MinecraftForge.EVENT_BUS.unregister(reject);}
            f.state.amend("keep the iron instead");
            helper.assertTrue(com.wjx.touhou_aifun.maid.action.MaidOwnerDropAction.start(f.task,request).join().has("error") && f.maid.getMaidInv().getStackInSlot(0).getCount()==2,"Pending amendment blocks item removal");helper.succeed();
        }
    }
    @GameTest(batch="isolated_finalReplyAndFailureApplyOwnerAmendmentsBeforePausing",template="empty",templateNamespace="aifun_gui_test")
    public static void finalReplyAndFailureApplyOwnerAmendmentsBeforePausing(GameTestHelper helper) throws Exception {
        try(var f=new Fixture(helper,3)) {
            f.open();var action=f.action();action.addProperty("close_after",true);f.run("gui_action",action,false);
            var receipts=new LinkedHashMap<>(f.state.transfers);int version=f.state.goalVersion;
            // Exhaust the original task's corrective attempt before the later owner supplement.
            f.task.onSuccess(new com.github.tartaricacid.touhoulittlemaid.ai.manager.response.ResponseChat("not done","not done"));
            f.state.amend("If glass is missing, smelt it in a furnace; retain the iron already received");
            f.task.onSuccess(new com.github.tartaricacid.touhoulittlemaid.ai.manager.response.ResponseChat("old blocked reply","old blocked reply"));
            helper.assertTrue(f.state.goalVersion==version+1 && f.state.pendingAmendment.isBlank() && f.state.status==AgentTaskState.Status.running && f.state.completion==null,"Late plain reply replans amended full contract before any pause");
            helper.assertTrue(f.state.transfers.equals(receipts) && f.chest().isEmpty() && f.task.getMessages().stream().anyMatch(m->m.message()!=null && m.message().contains("If glass is missing")),"Committed real transfers and exact owner text survive replanning");
            f.state.amend("Return the held iron instead; do not take more");f.task.onFailure(null,new IllegalStateException("old failure"),500);
            helper.assertTrue(f.state.goalVersion==version+2 && f.state.pendingAmendment.isBlank() && f.state.status==AgentTaskState.Status.running && f.state.transfers.equals(receipts),"Late failure also applies the new instruction without replay");helper.succeed();
        }
    }
    @GameTest(batch="isolated_ownerDropWalksTowardOwnerBeforeSpawning",template="empty",templateNamespace="aifun_gui_test",timeoutTicks=180)
    public static void ownerDropWalksTowardOwnerBeforeSpawning(GameTestHelper helper) throws Exception {
        var f=new Fixture(helper,0);
        try {
            for(var pos:BlockPos.betweenClosed(new BlockPos(0,0,0),new BlockPos(8,0,5)))helper.setBlock(pos,Blocks.STONE);
            for(var pos:BlockPos.betweenClosed(new BlockPos(2,1,1),new BlockPos(8,3,3)))helper.setBlock(pos,Blocks.AIR);
            f.maid.getOwner().setPos(f.maid.getX()+5,f.maid.getY(),f.maid.getZ());f.maid.setNoAi(false);
            f.maid.getMaidInv().setStackInSlot(0,new ItemStack(Items.IRON_INGOT,2));
            var condition=JsonParser.parseString("{\"kind\":\"drop_to_owner\",\"dimension\":\"minecraft:overworld\",\"item\":\"minecraft:iron_ingot\",\"count\":2}").getAsJsonObject();f.task.defineCompletion(condition);
            var request=JsonParser.parseString("{\"items\":[{\"item\":\"minecraft:iron_ingot\",\"count\":2}],\"complete_task\":true}").getAsJsonObject();
            int[] requests={0};var calls=new JsonArray();calls.add(call("final_owner_drop","drop_items_to_owner",request));
            var response=new JsonObject();response.add("tool_calls",calls);f.task.onFunctionCall(new Gson().fromJson(response,Message.class),ignored->requests[0]++);
            helper.assertTrue(!f.task.settlement().isDone() && f.maid.getMaidInv().getStackInSlot(0).getCount()==2,"Distant owner requires real movement before item removal");
            helper.runAfterDelay(100,()->{
                try {
                    helper.assertTrue(f.task.settlement().isDone() && f.state.status==AgentTaskState.Status.completed && requests[0]==0,"Actual tool delivery settles and verifies completion without another model request: "+f.state.status+" "+f.state.failures);
                    helper.assertTrue(f.state.actionFacts.get(0).get("owner_distance_at_drop").getAsDouble()<=3 && f.state.droppedToOwner("minecraft:overworld",f.maid.getOwnerUUID().toString(),"minecraft:iron_ingot")==2,"Real path reaches owner and commits exactly two items");
                    var entityId=f.state.actionFacts.get(0).get("entity_uuid").getAsString();
                    var entity=helper.getLevel().getEntity(UUID.fromString(entityId));if(entity!=null)entity.discard();helper.succeed();
                } finally {f.close();}
            });
        } catch(RuntimeException|Error failure){f.close();throw failure;}
    }
    @GameTest(batch="isolated_liveGlassRecipeFeedsRealFurnaceWithoutTakingMoreMaterials",template="empty",templateNamespace="aifun_gui_test",timeoutTicks=300)
    public static void liveGlassRecipeFeedsRealFurnaceWithoutTakingMoreMaterials(GameTestHelper helper) throws Exception {
        var f=new Fixture(helper,0);
        try {
            for(var pos:BlockPos.betweenClosed(new BlockPos(0,0,0),new BlockPos(5,0,5)))helper.setBlock(pos,Blocks.STONE);
            helper.setBlock(f.local,Blocks.FURNACE);f.maid.setHomeModeEnable(true);f.maid.restrictTo(f.position,16);
            f.maid.getOwner().setPos(f.maid.getX()+1,f.maid.getY(),f.maid.getZ());f.maid.setInvulnerable(true);f.maid.setNoGravity(true);f.maid.addTag("qa_stationary_furnace");
            f.maid.getMaidInv().setStackInSlot(0,new ItemStack(Items.SAND));f.maid.getMaidInv().setStackInSlot(1,new ItemStack(Items.COAL));
            var query=new JsonObject();query.addProperty("item","minecraft:glass");query.addProperty("recipe_type","minecraft:smelting");
            var recipes=com.wjx.touhou_aifun.maid.gui.GuiCraftingService.query(f.maid,query);
            helper.assertTrue(!recipes.getAsJsonArray("recipes").isEmpty(),"Smelting is discovered from live loaded recipes");
            f.task.defineCompletion(JsonParser.parseString("{\"kind\":\"inventory\",\"dimension\":\"minecraft:overworld\",\"item\":\"minecraft:glass\",\"count\":1}").getAsJsonObject());
            var open=f.target(f.position);open.addProperty("target","minecraft:furnace");open.addProperty("wait_policy","UNTIL_GOAL");
            var opened=MaidGuiSessionManager.call(f.task,"open_gui","glass_furnace",open).join();helper.assertTrue(!opened.has("error"),opened.toString());
            var session=MaidGuiSessionManager.session(f.maid.getUUID());
            for(var input:List.of(Items.SAND,Items.COAL)) {
                int source=-1;for(int i=3;i<session.menu().slots.size();i++)if(session.menu().getSlot(i).getItem().is(input)){source=i;break;}
                helper.assertTrue(source>=3,"Previously held input is bound into the real furnace menu");
                var transfer=new JsonObject();transfer.add("session_id",opened.get("session_id"));transfer.addProperty("action","transfer");transfer.addProperty("slot",source);transfer.addProperty("to_slot",input==Items.SAND?0:1);transfer.addProperty("count",1);
                var moved=MaidGuiSessionManager.call(f.task,"gui_action","feed_"+source,transfer).join();helper.assertTrue(!moved.has("error"),"feed: "+moved);
            }
            var wait=new JsonObject();wait.add("session_id",opened.get("session_id"));wait.addProperty("item","minecraft:glass");wait.addProperty("count",1);wait.addProperty("output_slot",2);
            var originalMenu=session.menu();
            var output=MaidGuiSessionManager.call(f.task,"wait_gui","real_glass",wait);
            helper.runAfterDelay(240,()->{
                try {
                    helper.assertTrue(output.isDone() && !output.join().has("error") && output.join().get("delivered_count").getAsInt()==1,(output.isDone()?output.join().toString():"Furnace did not finish")+" maid="+f.maid.position()+" actor="+session.actor.position()+" target="+f.position+" block="+helper.getLevel().getBlockState(f.position)+" valid="+originalMenu.stillValid(session.actor));
                    helper.assertTrue(AgentCompletion.verify(f.maid,f.state,true),"Actual collected glass satisfies live inventory requirement");
                    long collected=f.state.transfers.entrySet().stream().filter(e->e.getKey().endsWith(":minecraft:glass")).mapToLong(Map.Entry::getValue).sum();
                    helper.assertTrue(collected==1,"Exactly one actual glass output receipt; no repeated material acquisition");helper.succeed();
                } finally {f.close();}
            });
        } catch(RuntimeException|Error failure){f.close();throw failure;}
    }
    private static final class Fixture implements AutoCloseable {
        final boolean enabled=TouhouAIFunConfig.AGENT_RUNTIME.get();
        final EntityMaid maid;final AgentTaskState state;final TaskCallback task;final GameTestHelper helper;
        final BlockPos local=new BlockPos(1,1,3),position;
        Fixture(GameTestHelper helper,int required) throws Exception {
            this.helper=helper;TouhouAIFunConfig.AGENT_RUNTIME.set(true);var owner=helper.makeMockPlayer();
            maid=new EntityMaid(InitEntities.MAID.get(),helper.getLevel()) {
                @Override public net.minecraft.world.entity.LivingEntity getOwner(){return owner;}
                @Override public void setPos(double x,double y,double z) {
                    if(getTags()!=null && getTags().contains("qa_stationary_furnace")) {
                        // Stationary cooking fixture excludes entity repositioning; real navigation is tested separately.
                        return;
                    }
                    super.setPos(x,y,z);
                }
            };
            var start=helper.absolutePos(new BlockPos(2,1,2));maid.setPos(start.getX()+.5,start.getY(),start.getZ()+.5);maid.setOwnerUUID(owner.getUUID());maid.setNoAi(true);helper.getLevel().addFreshEntity(maid);
            helper.setBlock(local,Blocks.CHEST);position=helper.absolutePos(local);chest().setItem(0,new ItemStack(Items.IRON_INGOT,2));
            var method=AgentRuntime.class.getDeclaredMethod("entry",EntityMaid.class);method.setAccessible(true);var entry=method.invoke(null,maid);
            var field=entry.getClass().getDeclaredField("queue");field.setAccessible(true);var queue=(AgentTaskQueue)field.get(entry);
            state=queue.enqueue("Collect required iron, leave hands empty and close the menu");queue.next();
            task=new TaskCallback(maid.getAiChatManager(),new ArrayList<>(),state);state.loadedTools.addAll(List.of(GuiTool.IDS));
            var fresh=TaskCallback.class.getDeclaredField("freshWorld");fresh.setAccessible(true);fresh.setBoolean(task,true);
            var active=entry.getClass().getDeclaredField("callback");active.setAccessible(true);active.set(entry,task);
            if(required>0) {
                var condition=new JsonObject();condition.addProperty("kind","transfer");condition.addProperty("item","minecraft:iron_ingot");condition.addProperty("count",required);condition.addProperty("to_maid",true);condition.addProperty("dimension","minecraft:overworld");
                var pos=new JsonArray();pos.add(position.getX());pos.add(position.getY());pos.add(position.getZ());condition.add("position",pos);task.defineCompletion(condition);
            }
        }
        ChestBlockEntity chest(){return (ChestBlockEntity)helper.getBlockEntity(local);}
        JsonObject target(BlockPos position) {JsonObject target=new JsonObject();target.addProperty("x",position.getX());target.addProperty("y",position.getY());target.addProperty("z",position.getZ());return target;}
        JsonObject open() {var request=target(position);request.addProperty("target","minecraft:chest");return MaidGuiSessionManager.call(task,"open_gui","qa_open",request).join();}
        JsonObject action() {var result=new JsonObject();result.addProperty("session_id",MaidGuiSessionManager.session(maid.getUUID()).id.toString());result.addProperty("action","quick_move");result.addProperty("slot",0);return result;}
        int run(String name,JsonObject request,boolean late) {
            JsonArray calls=new JsonArray();calls.add(call("qa_action",name,request));if(late) calls.add(call("qa_late","gui_action",new JsonObject()));
            JsonObject response=new JsonObject();response.add("tool_calls",calls);int[] model={0};task.onFunctionCall(new Gson().fromJson(response,Message.class),ignored->model[0]++);return model[0];
        }
        @Override public void close(){AgentRuntime.stop(maid,true);ChatFlowManager.forgetMaid(maid.getUUID());maid.discard();TouhouAIFunConfig.AGENT_RUNTIME.set(enabled);}
    }
    private static JsonObject call(String id,String name,JsonObject request) {
        JsonObject call=new JsonObject();call.addProperty("id",id);JsonObject function=new JsonObject();function.addProperty("name",name);function.addProperty("arguments",request.toString());call.add("function",function);return call;
    }
    private static JsonObject roundtrip(Fixture f,int count) {
        var receive=new JsonObject();receive.addProperty("kind","transfer");receive.addProperty("dimension","minecraft:overworld");receive.addProperty("item","minecraft:iron_ingot");receive.addProperty("count",count);receive.addProperty("to_maid",true);
        var pos=new JsonArray();pos.add(f.position.getX());pos.add(f.position.getY());pos.add(f.position.getZ());receive.add("position",pos);
        var send=receive.deepCopy();send.addProperty("to_maid",false);
        var restored=new JsonObject();restored.addProperty("kind","container_unchanged");restored.addProperty("dimension","minecraft:overworld");restored.add("position",pos.deepCopy());
        var empty=new JsonObject();empty.addProperty("kind","backpack_empty");empty.addProperty("dimension","minecraft:overworld");
        var finalConditions=new JsonArray();finalConditions.add(send);finalConditions.add(restored);finalConditions.add(empty);
        var last=new JsonObject();last.addProperty("kind","all");last.add("conditions",finalConditions);
        var phases=new JsonArray();phases.add(receive);phases.add(last);var sequence=new JsonObject();sequence.addProperty("kind","sequence");sequence.add("phases",phases);return sequence;
    }
    @GameTest(batch="isolated_generalExecutionSkipsRemoteTemplateProbeAndLoadsDecisionSyntaxOnlyOnDemand",template="empty",templateNamespace="aifun_gui_test")
    public static void generalExecutionSkipsRemoteTemplateProbeAndLoadsDecisionSyntaxOnlyOnDemand(GameTestHelper helper) throws Exception {
        boolean service=TouhouAIFunConfig.DECISION_SERVICE_ENABLED.get(),templates=TouhouAIFunConfig.DECISION_STARTUP_TEMPLATES.get();
        TouhouAIFunConfig.DECISION_SERVICE_ENABLED.set(true);TouhouAIFunConfig.DECISION_STARTUP_TEMPLATES.set(false);
        try(var f=new Fixture(helper,0)) {
            var catalog=com.wjx.touhou_aifun.compat.ai.openai.ToolContextSelector.snapshot(f.maid,f.task);
            var lean=catalog.planningEntries().stream().filter(e->e.id().equals("update_task_plan")).findFirst().orElseThrow();
            helper.assertTrue(!lean.anthropicTool().getAsJsonObject("input_schema").getAsJsonObject("properties").has("action_steps") && !new Gson().toJson(lean.openAITool()).contains("action_steps"),"Both protocols omit optional full executable syntax");
            var router=new com.wjx.touhou_aifun.chat.agent.decision.TaskDecisionRouter(f.task);
            helper.assertTrue(!router.route(ignored->{throw new AssertionError("Unexpected model dispatch");},new JsonObject(),catalog,true,false),"A generic unplanned task proceeds immediately without speculative remote decision");
            helper.assertTrue(f.task.getMessages().stream().noneMatch(m->AgentTaskInstructions.DECISION_POLICY.equals(m.message())),"Normal requests carry only the short optional decision hint");
            new com.wjx.touhou_aifun.compat.ai.openai.LoadToolSchemaTool().onCall("load_optional_decisions","group:task_actions",f.task);
            var advanced=com.wjx.touhou_aifun.compat.ai.openai.ToolContextSelector.snapshot(f.maid,f.task).planningEntries().stream().filter(e->e.id().equals("update_task_plan")).findFirst().orElseThrow();
            helper.assertTrue(advanced.anthropicTool().getAsJsonObject("input_schema").getAsJsonObject("properties").has("action_steps") && new Gson().toJson(advanced.openAITool()).contains("action_steps"),"Explicit segment request refreshes both protocols' frozen schemas");
            helper.assertTrue(lean.schemaTokens()<advanced.schemaTokens()/2,"Default planner schema removes more than half the optional syntax cost");
            System.out.println("GENERAL_EXECUTION_SCHEMA_TOKENS lean="+lean.schemaTokens()+" advanced="+advanced.schemaTokens());helper.succeed();
        } finally {TouhouAIFunConfig.DECISION_SERVICE_ENABLED.set(service);TouhouAIFunConfig.DECISION_STARTUP_TEMPLATES.set(templates);}
    }
    private static JsonObject roundtripBatch(JsonObject opened,int targetSlot) {
        int backpack=-1;for(var row:opened.getAsJsonArray("slots")) {var slot=row.getAsJsonObject();if(slot.has("backpack_slot") && slot.get("backpack_slot").getAsInt()==0)backpack=slot.get("slot").getAsInt();}
        if(backpack<0)throw new AssertionError("No observed backpack slot");
        var take=new JsonObject();take.addProperty("action","transfer");take.addProperty("slot",0);take.addProperty("to_slot",backpack);take.addProperty("count",2);
        var give=take.deepCopy();give.addProperty("slot",backpack);give.addProperty("to_slot",targetSlot);
        var actions=new JsonArray();actions.add(take);actions.add(give);var request=new JsonObject();request.add("session_id",opened.get("session_id"));request.add("expected_revision",opened.get("revision"));request.add("actions",actions);request.addProperty("complete_task",true);return request;
    }
    @GameTest(batch="isolated_genericRoundtripCompletesInOneOrderedBatchWithoutTemplateOrExtraModel",template="empty",templateNamespace="aifun_gui_test")
    public static void genericRoundtripCompletesInOneOrderedBatchWithoutTemplateOrExtraModel(GameTestHelper helper) throws Exception {
        try(var f=new Fixture(helper,0)) {
            f.chest().getItem(0).getOrCreateTag().putString("qa_identity","original");var original=f.chest().getItem(0).copy();var opened=f.open();f.task.defineCompletion(roundtrip(f,2));
            var oldCheckpoint=new Gson().fromJson(new Gson().toJson(f.state),AgentTaskState.class);
            helper.assertTrue(f.state.actionSteps.isEmpty(),"Generic execution uses no executable template");
            helper.assertTrue(f.run("gui_batch",roundtripBatch(opened,0),false)==0 && f.state.status==AgentTaskState.Status.completed,"Both ordered phases finalize without another LLM request");
            helper.assertTrue(f.state.completionStage==1 && f.state.transfers.values().stream().allMatch(n->n==0) && f.state.verifiedCount==2,"Opposite legs use phase receipts, while total net remains zero");
            helper.assertTrue(ItemStack.matches(original,f.chest().getItem(0)) && MaidGuiSessionManager.session(f.maid.getUUID())==null,"Exact slot/count/NBT restored and menu settled");
            var archive=AgentArchiveData.get(helper.getLevel()).task(f.maid.getUUID(),f.state.id);
            new Gson().fromJson(new Gson().toJson(archive),AgentTaskArchive.class).reconcile(oldCheckpoint);
            helper.assertTrue(oldCheckpoint.completionStage==1 && AgentCompletion.verify(f.maid,oldCheckpoint,true),"Durable mutation archive restores phase receipts and original private container baseline");
            helper.succeed();
        }
    }
    @GameTest(batch="isolated_liveOnlyPhaseProgressIsPersistedWithoutInventingATransfer",template="empty",templateNamespace="aifun_gui_test")
    public static void liveOnlyPhaseProgressIsPersistedWithoutInventingATransfer(GameTestHelper helper) throws Exception {
        try(var f=new Fixture(helper,0)) {
            var position=new JsonObject();position.addProperty("kind","position");position.addProperty("dimension","minecraft:overworld");var xyz=new JsonArray();xyz.add(f.maid.blockPosition().getX());xyz.add(f.maid.blockPosition().getY());xyz.add(f.maid.blockPosition().getZ());position.add("position",xyz);
            var stock=new JsonObject();stock.addProperty("kind","inventory");stock.addProperty("dimension","minecraft:overworld");stock.addProperty("item","minecraft:iron_ingot");stock.addProperty("count",2);
            var phases=new JsonArray();phases.add(position);phases.add(stock);var condition=new JsonObject();condition.addProperty("kind","sequence");condition.add("phases",phases);f.task.defineCompletion(condition);
            helper.assertTrue(!AgentCompletion.verify(f.maid,f.state,true) && f.state.completionStage==1 && f.state.transfers.isEmpty(),"Verified arrival advances phase but does not claim final stock or fabricate movement");
            helper.assertTrue(AgentArchiveData.get(helper.getLevel()).task(f.maid.getUUID(),f.state.id).verifiedCompletionStage==1 && f.state.appliedMutationVersion>0,"Live-only stage progress is durably committed");helper.succeed();
        }
    }
    @GameTest(batch="isolated_genericRoundtripCannotClaimSuccessAfterOnlyPartialRequiredTake",template="empty",templateNamespace="aifun_gui_test")
    public static void genericRoundtripCannotClaimSuccessAfterOnlyPartialRequiredTake(GameTestHelper helper) throws Exception {
        try(var f=new Fixture(helper,0)) {
            var opened=f.open();f.task.defineCompletion(roundtrip(f,3));
            helper.assertTrue(f.run("gui_batch",roundtripBatch(opened,0),false)==1 && f.state.status==AgentTaskState.Status.running && f.state.completionStage==0,"A restored box cannot substitute for the required intermediate quantity");
            helper.assertTrue(!AgentCompletion.verify(f.maid,f.state,true) && f.chest().getItem(0).getCount()==2,"Actual work is retained, and insufficient receipt is reported");helper.succeed();
        }
    }
    @GameTest(batch="isolated_genericRoundtripRejectsWrongSlotAndCannotRedefineBaseline",template="empty",templateNamespace="aifun_gui_test")
    public static void genericRoundtripRejectsWrongSlotAndCannotRedefineBaseline(GameTestHelper helper) throws Exception {
        try(var f=new Fixture(helper,0)) {
            var opened=f.open();var contract=roundtrip(f,2);f.task.defineCompletion(contract);
            helper.assertTrue(f.run("gui_batch",roundtripBatch(opened,1),false)==1 && f.state.status==AgentTaskState.Status.running && f.state.completionStage==1,"Returning the quantity to a different slot fails restoration");
            f.open();f.task.defineCompletion(contract);
            helper.assertTrue(!AgentCompletion.verify(f.maid,f.state,true),"Repeated identical contract does not recapture a changed baseline");
            f.chest().setItem(0,f.chest().getItem(1));f.chest().setItem(1,ItemStack.EMPTY);helper.assertTrue(AgentCompletion.verify(f.maid,f.state,true),"Original arrangement restores the private baseline");
            f.chest().getItem(0).getOrCreateTag().putString("qa_identity","tampered");helper.assertTrue(!AgentCompletion.verify(f.maid,f.state,true),"Same item and count with different NBT cannot prove restoration");helper.succeed();
        }
    }
    @GameTest(batch="isolated_finalTransferClosesVerifiesAndPairsLateCallsWithoutAnotherModel",template="empty",templateNamespace="aifun_gui_test")
    public static void finalTransferClosesVerifiesAndPairsLateCallsWithoutAnotherModel(GameTestHelper helper) throws Exception {
        try(var f=new Fixture(helper,2)) {
            helper.assertTrue(!f.open().has("error"),"Observed chest opened");var action=f.action();action.addProperty("complete_task",true);
            helper.assertTrue(f.run("gui_action",action,true)==0 && f.state.status==AgentTaskState.Status.completed,"Final action completes after settlement with no subsequent model request");
            int count=0;for(int i=0;i<f.maid.getAvailableBackpackInv().getSlots();i++) {var stack=f.maid.getAvailableBackpackInv().getStackInSlot(i);if(stack.is(Items.IRON_INGOT)) count+=stack.getCount();}
            helper.assertTrue(f.chest().isEmpty() && count==2 && f.state.verifiedCount==2,"Actual quantity transferred exactly once");
            helper.assertTrue(MaidGuiSessionManager.session(f.maid.getUUID())==null && f.task.settlement().isDone(),"Cleanup and settlement precede task completion");
            helper.assertTrue(f.task.getMessages().stream().filter(m->m.role()==Role.TOOL).count()==2 && f.task.getMessages().stream().anyMatch(m->"qa_late".equals(m.toolCallId()) && m.message().contains("cancelled_before_start")),"Accepted late action is cancelled with its paired result");helper.succeed();
        }
    }
    @GameTest(batch="isolated_closeAfterIsCleanupAndNeverImplicitCompletion",template="empty",templateNamespace="aifun_gui_test")
    public static void closeAfterIsCleanupAndNeverImplicitCompletion(GameTestHelper helper) throws Exception {
        try(var f=new Fixture(helper,2)) {
            helper.assertTrue(!f.open().has("error"),"Observed chest opened");var action=f.action();action.addProperty("close_after",true);
            helper.assertTrue(f.run("gui_action",action,false)==1 && f.state.status==AgentTaskState.Status.running && AgentCompletion.verify(f.maid,f.state,true),"Even satisfied predicates require the executor's explicit final declaration");
            helper.assertTrue(f.chest().isEmpty() && MaidGuiSessionManager.session(f.maid.getUUID())==null,"Intermediate cleanup settles the real transfer without another close turn");helper.succeed();
        }
    }
    @GameTest(batch="isolated_invalidFinalizationFlagsCannotMoveItemsAndFullObservationIsAvailable",template="empty",templateNamespace="aifun_gui_test")
    public static void invalidFinalizationFlagsCannotMoveItemsAndFullObservationIsAvailable(GameTestHelper helper) throws Exception {
        try(var f=new Fixture(helper,2)) {
            var opened=f.open();helper.assertTrue(!opened.has("error") && !opened.getAsJsonArray("slots").get(0).getAsJsonObject().has("x"),"Default task view needs an actual capture for pixel geometry");
            var inspect=new JsonObject();inspect.add("session_id",opened.get("session_id"));inspect.addProperty("detail","full");
            var full=MaidGuiSessionManager.call(f.task,"inspect_gui","full",inspect).join();helper.assertTrue(full.getAsJsonArray("slots").get(0).getAsJsonObject().has("x"),"Explicit full view retains the complete original snapshot");
            var action=f.action();action.addProperty("complete_task","true");
            helper.assertTrue(f.run("gui_action",action,false)==1 && f.chest().getItem(0).getCount()==2 && f.state.transfers.isEmpty(),"Malformed finalization flag rejects the whole call before item mutation");
            helper.assertTrue(MaidGuiSessionManager.session(f.maid.getUUID())!=null && !f.state.failures.isEmpty(),"Rejected input retains the observed menu and precise failure");helper.succeed();
        }
    }
    @GameTest(batch="isolated_insufficientFinalTransferRetainsFactsAndReplans",template="empty",templateNamespace="aifun_gui_test")
    public static void insufficientFinalTransferRetainsFactsAndReplans(GameTestHelper helper) throws Exception {
        try(var f=new Fixture(helper,3)) {
            helper.assertTrue(!f.open().has("error"),"Observed chest opened");var action=f.action();action.addProperty("complete_task",true);
            helper.assertTrue(f.run("gui_action",action,false)==1 && f.state.status==AgentTaskState.Status.running,"Missing quantity cannot be masked by the final-action flag");
            helper.assertTrue(f.chest().isEmpty() && f.state.verifiedCount==2 && MaidGuiSessionManager.session(f.maid.getUUID())==null,"Two committed items survive the failed finalization and cleanup");
            helper.assertTrue(f.task.getMessages().stream().anyMatch(m->m.role()==Role.TOOL && m.message().contains("completion_evidence_unmet")) && !f.state.failures.isEmpty(),"Exact unmet evidence is returned and counted as a failure");helper.succeed();
        }
    }
    @GameTest(batch="isolated_partialGuiBatchNeverClosesOrDeclaresCompletion",template="empty",templateNamespace="aifun_gui_test")
    public static void partialGuiBatchNeverClosesOrDeclaresCompletion(GameTestHelper helper) throws Exception {
        try(var f=new Fixture(helper,2)) {
            var opened=f.open();helper.assertTrue(!opened.has("error"),"Observed chest opened");var request=new JsonObject();request.add("session_id",opened.get("session_id"));request.add("expected_revision",opened.get("revision"));request.addProperty("complete_task",true);
            var actions=new JsonArray();var first=f.action();first.remove("session_id");actions.add(first);var invalid=first.deepCopy();invalid.addProperty("slot",999);actions.add(invalid);request.add("actions",actions);
            helper.assertTrue(f.run("gui_batch",request,false)==1 && f.state.status==AgentTaskState.Status.running,"Partially failed batch replans even if the quantity predicate happens to pass");
            helper.assertTrue(f.chest().isEmpty() && f.state.verifiedCount==2 && MaidGuiSessionManager.session(f.maid.getUUID())!=null,"Earlier transfer remains committed and failed batch keeps its GUI inspectable");helper.succeed();
        }
    }
    @GameTest(batch="isolated_inspectMultipleChestsReturnsCountsAndOnlyLastCurrentSnapshot",template="empty",templateNamespace="aifun_gui_test")
    public static void inspectMultipleChestsReturnsCountsAndOnlyLastCurrentSnapshot(GameTestHelper helper) throws Exception {
        try(var f=new Fixture(helper,0)) {
            var second=new BlockPos(3,1,3);helper.setBlock(second,Blocks.CHEST);((ChestBlockEntity)helper.getBlockEntity(second)).setItem(0,new ItemStack(Items.GOLD_INGOT,3));
            JsonObject request=new JsonObject();JsonArray targets=new JsonArray();targets.add(f.target(f.position));targets.add(f.target(helper.absolutePos(second)));request.add("targets",targets);
            var result=GuiContainerInspection.inspect(f.task,"qa_inspect",request).join();helper.assertTrue(!result.has("error"),"Bounded ordinary-menu inspection succeeds");
            var containers=result.getAsJsonArray("containers");helper.assertTrue(containers.size()==2 && containers.get(0).getAsJsonObject().getAsJsonArray("items").get(0).getAsJsonObject().get("count").getAsInt()==2 && containers.get(1).getAsJsonObject().getAsJsonArray("items").get(0).getAsJsonObject().get("count").getAsInt()==3,"Both actual item counts are reported in target order");
            helper.assertTrue(result.getAsJsonObject("active_gui").has("slots") && result.getAsJsonObject("active_gui").get("session_id").getAsString().equals(MaidGuiSessionManager.session(f.maid.getUUID()).id.toString()),"Only the final open menu supplies current slot authority");
            helper.assertTrue(result.getAsJsonObject("active_gui").get("wait_policy").getAsString().equals("AUTO"),"Read-only inspection must not force future processing to NO_WAIT");
            helper.assertTrue(f.chest().getItem(0).getCount()==2 && ((ChestBlockEntity)helper.getBlockEntity(second)).getItem(0).getCount()==3 && f.state.transfers.isEmpty(),"Inspection does not collect items or invent transfer evidence");helper.succeed();
        }
    }
    @GameTest(batch="isolated_inspectionRejectsMalformedBatchAndPendingAmendmentBeforeOpening",template="empty",templateNamespace="aifun_gui_test")
    public static void inspectionRejectsMalformedBatchAndPendingAmendmentBeforeOpening(GameTestHelper helper) throws Exception {
        try(var f=new Fixture(helper,0)) {
            JsonObject request=new JsonObject();JsonArray targets=new JsonArray();targets.add(f.target(f.position));targets.add(f.target(f.position));request.add("targets",targets);
            helper.assertTrue(GuiContainerInspection.inspect(f.task,"duplicate",request).join().has("error") && MaidGuiSessionManager.session(f.maid.getUUID())==null,"Entire target list validates before first open");
            targets.remove(1);f.state.amend("Do something else first");var result=GuiContainerInspection.inspect(f.task,"changed",request).join();
            helper.assertTrue(result.has("error") && result.getAsJsonArray("containers").isEmpty() && MaidGuiSessionManager.session(f.maid.getUUID())==null && f.state.transfers.isEmpty(),"New owner conditions stop internal dispatch before any inspection");helper.succeed();
        }
    }
}
