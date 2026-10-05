package com.wjx.aifun_gui_test;

import com.wjx.touhou_aifun.maid.gui.*;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.init.InitEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraftforge.gametest.*;
import com.google.gson.JsonObject;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import java.util.ArrayList;

@GameTestHolder("aifun_gui_test")
@PrefixGameTestTemplate(false)
public final class GuiGameTests {
    @GameTest(template="empty",templateNamespace="aifun_gui_test")
    public static void cursorTransfersRecordActualDeliveryAndReverseTransfers(GameTestHelper helper) {
        helper.setBlock(new BlockPos(1,1,1),Blocks.CHEST);var chest=(ChestBlockEntity)helper.getBlockEntity(new BlockPos(1,1,1));chest.setItem(0,new ItemStack(Items.IRON_INGOT,4));
        var seed=session(helper);var state=new com.wjx.touhou_aifun.chat.agent.AgentTaskState("Receive four iron");state.status=com.wjx.touhou_aifun.chat.agent.AgentTaskState.Status.running;
        var pos=helper.absolutePos(new BlockPos(1,1,1));var spec=new JsonObject();spec.addProperty("item","minecraft:iron_ingot");spec.addProperty("count",4);spec.addProperty("dimension","minecraft:overworld");spec.addProperty("to_maid",true);
        var xyz=new com.google.gson.JsonArray();xyz.add(pos.getX());xyz.add(pos.getY());xyz.add(pos.getZ());spec.add("position",xyz);state.completion=spec;
        var callback=new com.wjx.touhou_aifun.chat.agent.TaskCallback(seed.maid.getAiChatManager(),new ArrayList<>(),state);
        JsonObject open=new JsonObject();open.addProperty("x",pos.getX());open.addProperty("y",pos.getY());open.addProperty("z",pos.getZ());open.addProperty("wait_policy","AUTO");MaidGuiSessionManager.call(callback,"open_gui","open_cursor",open).join();
        var current=MaidGuiSessionManager.session(seed.maid.getUUID());JsonObject action=args(current);action.addProperty("action","click_slot");action.addProperty("slot",0);
        MaidGuiSessionManager.call(callback,"gui_action","pick",action).join();helper.assertTrue(state.verifiedCount==0,"Picking up a cursor stack does not claim completed delivery");
        action=args(current);action.addProperty("action","cursor_to_backpack");action.addProperty("backpack_slot",0);action.addProperty("count",4);MaidGuiSessionManager.call(callback,"gui_action","deposit",action).join();
        helper.assertTrue(state.verifiedCount==4 && seed.maid.getAvailableBackpackInv().getStackInSlot(0).getCount()==4,"Actual cursor delivery registers source location and quantity");
        action=args(current);action.addProperty("action","backpack_to_cursor");action.addProperty("backpack_slot",0);action.addProperty("count",2);MaidGuiSessionManager.call(callback,"gui_action","withdraw",action).join();
        action=args(current);action.addProperty("action","click_slot");action.addProperty("slot",0);MaidGuiSessionManager.call(callback,"gui_action","return",action).join();
        helper.assertTrue(state.verifiedCount==2 && chest.getItem(0).getCount()==2,"Reverse cursor transfer subtracts verified net delivery");
        MaidGuiSessionManager.cancel(seed.maid.getUUID(),"fixture_complete");state.status=com.wjx.touhou_aifun.chat.agent.AgentTaskState.Status.cancelled;callback.archiveState();helper.succeed();
    }
    @GameTest(template="empty",templateNamespace="aifun_gui_test",timeoutTicks=600)
    public static void missingFuelCanBeRepairedWithoutRechargingBudgetOrDuplicatingDelivery(GameTestHelper helper) {
        helper.setBlock(new BlockPos(1,1,1),Blocks.FURNACE);
        var callback=managed(helper,"UNTIL_GOAL");var session=MaidGuiSessionManager.session(callback.getMaid().getUUID());
        session.menu().getSlot(0).set(new ItemStack(Items.IRON_ORE,2));
        JsonObject request=args(session);request.addProperty("item","minecraft:iron_ingot");request.addProperty("count",2);request.addProperty("max_wait_seconds",30);
        var first=MaidGuiSessionManager.call(callback,"wait_gui","fuel_wait",request);
        final java.util.concurrent.CompletableFuture<JsonObject>[] resumed=new java.util.concurrent.CompletableFuture[1];
        helper.runAfterDelay(100,()-> {
            helper.assertTrue(first.isDone() && first.join().get("status").getAsString().equals("blocked_missing_fuel"),"Missing fuel produces an actionable blocker");
            long spent=session.waitBudget.consumed();helper.assertTrue(spent>=60,"Blocked wait consumed a real task budget");
            session.menu().getSlot(1).set(new ItemStack(Items.COAL));
            resumed[0]=MaidGuiSessionManager.call(callback,"wait_gui","fuel_repaired",request);
            helper.assertTrue(session.waitBudget.consumed()==spent,"Repair does not reset cumulative waiting time");
        });
        helper.runAfterDelay(540,()-> {
            helper.assertTrue(resumed[0]!=null && resumed[0].isDone() && resumed[0].join().get("status").getAsString().equals("completed"),"Repair completes the remaining two recipes");
            helper.assertTrue(resumed[0].join().get("delivered_count").getAsInt()==2,"Exactly two products delivered across both attempts");
            int received=0;for(int i=0;i<session.maid.getAvailableBackpackInv().getSlots();i++) {var stack=session.maid.getAvailableBackpackInv().getStackInSlot(i);if(stack.is(Items.IRON_INGOT)) received+=stack.getCount();}
            helper.assertTrue(received==2,"Physical backpack quantity agrees with delivery evidence");helper.succeed();
        });
    }
    @GameTest(template="empty",templateNamespace="aifun_gui_test")
    public static void initialSnapshotIdentifiesBackpackAndHandsWithoutGuessingMenuOrder(GameTestHelper helper) {
        helper.setBlock(new BlockPos(1,1,1),Blocks.CHEST);
        var callback=managed(helper,"AUTO");var session=MaidGuiSessionManager.session(callback.getMaid().getUUID());
        var snapshot=session.snapshot("ok");int backpack=0,hands=0;
        for(var element:snapshot.getAsJsonArray("slots")) {
            var slot=element.getAsJsonObject();if(!slot.get("maid_inventory").getAsBoolean()) continue;
            int inventoryIndex=session.menu().getSlot(slot.get("slot").getAsInt()).getContainerSlot();
            helper.assertTrue(slot.get("inventory_slot").getAsInt()==inventoryIndex,"Observed menu slots retain actual inventory mapping");
            if(slot.get("maid_storage").getAsString().equals("backpack")) {
                backpack++;helper.assertTrue(slot.get("backpack_slot").getAsInt()==inventoryIndex-1 && slot.get("enabled").getAsBoolean(),"Backpack index agrees with live binding");
            } else if(slot.get("maid_storage").getAsString().equals("main_hand")) hands++;
        }
        helper.assertTrue(backpack==session.maid.getAvailableBackpackInv().getSlots() && hands==1,"Complete structure distinguishes all usable backpack slots from main hand");
        MaidGuiSessionManager.cancel(session.maid.getUUID(),"fixture_complete");helper.succeed();
    }
    @GameTest(template = "empty", templateNamespace = "aifun_gui_test")
    public static void batchPreservesPartialTransferAndRejectsStaleRevision(GameTestHelper helper) {
        helper.setBlock(new BlockPos(1,1,1), Blocks.CHEST);
        ChestBlockEntity chest = (ChestBlockEntity) helper.getBlockEntity(new BlockPos(1,1,1));
        chest.setItem(0, new ItemStack(Items.IRON_INGOT, 10));
        var callback = managed(helper, "AUTO");
        var session = MaidGuiSessionManager.session(callback.getMaid().getUUID());
        JsonObject request = args(session); request.addProperty("expected_revision", session.projection.revision());
        com.google.gson.JsonArray actions = new com.google.gson.JsonArray();
        JsonObject transfer = new JsonObject(); transfer.addProperty("action", "transfer");
        transfer.addProperty("from_slot",0); transfer.addProperty("to_slot",backpackSlot(session)); transfer.addProperty("count",3);
        actions.add(transfer);
        JsonObject fail = new JsonObject(); fail.addProperty("action", "rename"); fail.addProperty("text", "not an anvil"); actions.add(fail);
        actions.add(transfer.deepCopy()); request.add("actions", actions);
        var result = MaidGuiSessionManager.call(callback,"gui_batch","batch",request).join();
        helper.assertTrue(result.get("executed_count").getAsInt()==1, result.toString());
        helper.assertTrue(chest.getItem(0).getCount()==7,"Only first transfer committed; no rollback or third action");
        JsonObject conflict=args(session);conflict.addProperty("action","transfer");conflict.addProperty("slot",0);conflict.addProperty("from_slot",1);conflict.addProperty("to_slot",backpackSlot(session));conflict.addProperty("count",1);
        helper.assertTrue(MaidGuiSessionManager.call(callback,"gui_action","conflict",conflict).join().has("error") && chest.getItem(0).getCount()==7,"Conflicting source aliases cannot dispatch a mutation");
        conflict.remove("from_slot");conflict.addProperty("count",1.5);
        helper.assertTrue(MaidGuiSessionManager.call(callback,"gui_action","fractional",conflict).join().has("error") && chest.getItem(0).getCount()==7,"Fractional quantities are rejected without rounding or a partial action");
        var stale = MaidGuiSessionManager.call(callback,"gui_batch","stale",request).join();
        helper.assertTrue(stale.get("error").getAsString().equals("stale_revision_reinspect"), stale.toString());
        helper.assertTrue(chest.getItem(0).getCount()==7,"Stale batch never mutates");
        MaidGuiSessionManager.cancel(session.maid.getUUID(),"qa_complete"); helper.succeed();
    }
    @GameTest(template = "empty", templateNamespace = "aifun_gui_test")
    public static void taskRecordsTransferBeforeCancellationAndResultDelivery(GameTestHelper helper) {
        helper.setBlock(new BlockPos(1,1,1),Blocks.CHEST);
        ChestBlockEntity chest=(ChestBlockEntity)helper.getBlockEntity(new BlockPos(1,1,1));
        chest.setItem(0,new ItemStack(Items.IRON_INGOT,10));
        var seed=session(helper); var pos=helper.absolutePos(new BlockPos(1,1,1));
        var state=new com.wjx.touhou_aifun.chat.agent.AgentTaskState("receive three iron");
        state.status=com.wjx.touhou_aifun.chat.agent.AgentTaskState.Status.running;
        JsonObject spec=new JsonObject(); spec.addProperty("item","minecraft:iron_ingot");spec.addProperty("count",3);
        spec.addProperty("dimension",seed.maid.level().dimension().location().toString());spec.addProperty("to_maid",true);
        var coordinates=new com.google.gson.JsonArray();coordinates.add(pos.getX());coordinates.add(pos.getY());coordinates.add(pos.getZ());spec.add("position",coordinates);
        state.completion=spec;
        var callback=new com.wjx.touhou_aifun.chat.agent.TaskCallback(seed.maid.getAiChatManager(),new ArrayList<>(),state);
        JsonObject request=new JsonObject();request.addProperty("x",pos.getX());request.addProperty("y",pos.getY());request.addProperty("z",pos.getZ());request.addProperty("wait_policy","AUTO");
        MaidGuiSessionManager.call(callback,"open_gui","open_task",request).join();
        var session=MaidGuiSessionManager.session(seed.maid.getUUID());
        MaidGuiSessionManager.transfer(session,0,backpackSlot(session),3,true);
        callback.operations.cancel();
        helper.assertTrue(state.verifiedCount==3 && state.completionSatisfied(),"Actual transfer is recorded before tool-response delivery and survives cancellation");
        helper.assertTrue(!state.transfers.isEmpty(),"Durable net transfer checkpoint exists");
        MaidGuiSessionManager.cancel(seed.maid.getUUID(),"qa_complete");helper.succeed();
    }
    private static LLMCallback managed(GameTestHelper helper, String policy) {
        MaidGuiSession seed = session(helper);
        var pos = helper.absolutePos(new BlockPos(1, 1, 1));
        LLMCallback callback = new LLMCallback(seed.maid.getAiChatManager(), new ArrayList<>(), true);
        JsonObject request = new JsonObject(); request.addProperty("x", pos.getX()); request.addProperty("y", pos.getY()); request.addProperty("z", pos.getZ()); request.addProperty("wait_policy", policy);
        JsonObject result = MaidGuiSessionManager.call(callback, "open_gui", "open", request).join();
        helper.assertTrue(result.get("status").getAsString().equals("opened"), result.toString());
        return callback;
    }
    private static JsonObject args(MaidGuiSession session) { JsonObject request = new JsonObject(); request.addProperty("session_id", session.id.toString()); return request; }
    private static MaidGuiSession session(GameTestHelper helper) {
        for (BlockPos floor : BlockPos.betweenClosed(new BlockPos(0, 0, 0), new BlockPos(5, 0, 5))) helper.setBlock(floor, Blocks.STONE);
        EntityMaid maid = helper.spawn(InitEntities.MAID.get(), new BlockPos(2, 1, 2));
        maid.setNoAi(true); maid.setInvulnerable(true);
        return new MaidGuiSession(maid, new Object(), GuiWaitPolicy.AUTO, new GuiWaitBudget());
    }
    private static int backpackSlot(MaidGuiSession session) {
        for (int i = 0; i < session.menu().slots.size(); i++) {
            Slot slot = session.menu().getSlot(i);
            if (slot.container == session.actor.getInventory() && slot.getContainerSlot() == 1) return i;
        }
        throw new IllegalStateException("No backpack slot");
    }
    @GameTest(template = "empty", templateNamespace = "aifun_gui_test")
    public static void chestOpensWithExplicitRelativeCoordinates(GameTestHelper helper) {
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.CHEST);
        var seed = session(helper); var target = helper.absolutePos(new BlockPos(1, 1, 1));
        var callback = new LLMCallback(seed.maid.getAiChatManager(), new ArrayList<>(), true);
        JsonObject request = new JsonObject(); request.addProperty("target", "minecraft:chest");
        BlockPos offset = target.subtract(seed.maid.blockPosition());
        request.addProperty("x", offset.getX()); request.addProperty("y", offset.getY()); request.addProperty("z", offset.getZ());
        request.addProperty("coordinate_space", "maid_relative"); request.addProperty("wait_policy", "AUTO");
        var result = MaidGuiSessionManager.call(callback, "open_gui", "relative", request).join();
        helper.assertTrue(result.get("status").getAsString().equals("opened"), result.toString());
        helper.assertTrue(MaidGuiSessionManager.session(seed.maid.getUUID()).targetPosition.equals(target), "Resolved actual chest position");
        MaidGuiSessionManager.cancel(seed.maid.getUUID(), "qa_complete");
        JsonObject qualified = new JsonObject();
        qualified.addProperty("target", "minecraft:chest@" + target.getX() + "," + target.getY() + "," + target.getZ());
        qualified.addProperty("coordinate_space", "world"); qualified.addProperty("wait_policy", "AUTO");
        var reopened = MaidGuiSessionManager.call(callback, "open_gui", "qualified", qualified).join();
        helper.assertTrue(reopened.get("status").getAsString().equals("opened"), reopened.toString());
        helper.assertTrue(MaidGuiSessionManager.session(seed.maid.getUUID()).targetPosition.equals(target), "Qualified target uses the exact chest");
        MaidGuiSessionManager.cancel(seed.maid.getUUID(), "qa_complete"); helper.succeed();
    }
    @GameTest(template = "empty", templateNamespace = "aifun_gui_test", timeoutTicks = 120)
    public static void openGuiWalksFromBlockEdgeInsteadOfWaitingForTimeout(GameTestHelper helper) {
        for (var floor : BlockPos.betweenClosed(new BlockPos(0, 0, 0), new BlockPos(5, 0, 5))) helper.setBlock(floor, Blocks.STONE);
        helper.setBlock(new BlockPos(1, 1, 2), Blocks.CHEST);
        var maid = helper.spawn(InitEntities.MAID.get(), new BlockPos(3, 1, 2));
        var start = helper.absolutePos(new BlockPos(3, 1, 2));
        maid.setPos(start.getX() + .70, start.getY(), start.getZ() + .5);
        maid.setNoAi(false); maid.setInvulnerable(true);
        var target = helper.absolutePos(new BlockPos(1, 1, 2));
        var callback = new LLMCallback(maid.getAiChatManager(), new ArrayList<>(), true);
        JsonObject request = new JsonObject(); request.addProperty("target", "minecraft:chest");
        request.addProperty("x", target.getX()); request.addProperty("y", target.getY()); request.addProperty("z", target.getZ());
        request.addProperty("wait_policy", "AUTO");
        var future = MaidGuiSessionManager.call(callback, "open_gui", "edge_walk", request);
        helper.runAfterDelay(80, () -> {
            try {
                helper.assertTrue(future.isDone(), "Opening stalled outside interaction range; maid=" + maid.position() + ", target=" + target);
                var result = future.join();
                helper.assertTrue(!result.has("error") && result.get("status").getAsString().equals("opened"), result.toString());
                helper.succeed();
            } finally { MaidGuiSessionManager.cancel(maid.getUUID(), "qa_complete"); }
        });
    }
    @GameTest(template = "empty", templateNamespace = "aifun_gui_test", timeoutTicks = 450)
    public static void navigationTimeoutReportsTheRouteBeforeReleasingIt(GameTestHelper helper) {
        for (var floor : BlockPos.betweenClosed(new BlockPos(0, 0, 0), new BlockPos(5, 0, 5))) helper.setBlock(floor, Blocks.STONE);
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.CHEST);
        var maid = helper.spawn(InitEntities.MAID.get(), new BlockPos(4, 1, 4));
        maid.setNoAi(true); maid.setInvulnerable(true);
        var callback = new LLMCallback(maid.getAiChatManager(), new ArrayList<>(), true);
        var target = helper.absolutePos(new BlockPos(1, 1, 1));
        JsonObject request = new JsonObject(); request.addProperty("target", "minecraft:chest");
        request.addProperty("x", target.getX()); request.addProperty("y", target.getY()); request.addProperty("z", target.getZ());
        request.addProperty("wait_policy", "NO_WAIT");
        var future = MaidGuiSessionManager.call(callback, "open_gui", "disabled_walk", request);
        helper.runAfterDelay(410, () -> {
            helper.assertTrue(future.isDone(), "Navigation has a bounded deadline");
            var result = future.join();
            helper.assertTrue(result.get("error").getAsString().equals("unreachable_timeout") && result.get("phase").getAsString().equals("navigation"), result.toString());
            helper.assertTrue(result.get("elapsed_ticks").getAsLong() >= 400 && result.get("movement_disabled").getAsBoolean(), "Actual pre-cleanup navigation facts are returned");
            helper.assertTrue(result.has("target_position") && result.has("maid_exact_position") && result.has("approach_position") && result.has("navigation_state"), "Timeout retains the missing route evidence");
            helper.assertTrue(MaidGuiSessionManager.session(maid.getUUID()) == null, "Failed opening releases its session");
            helper.succeed();
        });
    }
    @GameTest(template = "empty", templateNamespace = "aifun_gui_test")
    public static void wrongChestCoordinatesReportActualBlockWithoutInteraction(GameTestHelper helper) {
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.STONE);
        var seed = session(helper); var target = helper.absolutePos(new BlockPos(1, 1, 1));
        var callback = new LLMCallback(seed.maid.getAiChatManager(), new ArrayList<>(), true);
        JsonObject request = new JsonObject(); request.addProperty("target", "minecraft:chest");
        request.addProperty("x", target.getX()); request.addProperty("y", target.getY()); request.addProperty("z", target.getZ());
        request.addProperty("wait_policy", "AUTO");
        var result = MaidGuiSessionManager.call(callback, "open_gui", "wrong", request).join();
        helper.assertTrue(result.get("error").getAsString().equals("target_block_mismatch"), result.toString());
        helper.assertTrue(result.get("actual_block").getAsString().equals("minecraft:stone"), "Actual block explained to model");
        helper.assertTrue(MaidGuiSessionManager.session(seed.maid.getUUID()) == null, "Failed opening cleaned up");
        helper.succeed();
    }
    @GameTest(template = "empty", templateNamespace = "aifun_gui_test")
    public static void blockedChestExplainsWhyNoMenuWasCreated(GameTestHelper helper) {
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.CHEST); helper.setBlock(new BlockPos(1, 2, 1), Blocks.STONE);
        var seed = session(helper); var target = helper.absolutePos(new BlockPos(1, 1, 1));
        var callback = new LLMCallback(seed.maid.getAiChatManager(), new ArrayList<>(), true);
        JsonObject request = new JsonObject(); request.addProperty("target", "minecraft:chest");
        request.addProperty("x", target.getX()); request.addProperty("y", target.getY()); request.addProperty("z", target.getZ());
        request.addProperty("wait_policy", "AUTO");
        var result = MaidGuiSessionManager.call(callback, "open_gui", "blocked", request).join();
        helper.assertTrue(result.get("error").getAsString().equals("no_server_menu"), result.toString());
        helper.assertTrue(result.get("actual_block").getAsString().equals("minecraft:chest")
                && !result.get("menu_provider_present").getAsBoolean(), "Chest exists but vanilla blocks opening");
        helper.assertTrue(MaidGuiSessionManager.session(seed.maid.getUUID()) == null, "Failed opening cleaned up");
        helper.succeed();
    }
    @GameTest(template = "empty", templateNamespace = "aifun_gui_test")
    public static void chestNbtTransferAndStaleObservation(GameTestHelper helper) {
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.CHEST);
        MaidGuiSession session = session(helper);
        ChestBlockEntity chest = (ChestBlockEntity) helper.getBlockEntity(new BlockPos(1, 1, 1));
        ItemStack diamonds = new ItemStack(Items.DIAMOND, 5); diamonds.getOrCreateTag().putString("marker", "keep_nbt");
        chest.setItem(0, diamonds);
        session.actor.openMenu(chest);
        session.maid.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
        session.snapshot("ok"); int to = backpackSlot(session);
        helper.assertTrue(MaidGuiSessionManager.transfer(session, 0, to, 2, true) == 2, "Exact count");
        helper.assertTrue(chest.getItem(0).getCount() == 3, "Source remainder");
        helper.assertTrue(session.maid.getMaidInv().getStackInSlot(0).getCount() == 2, "Live backpack binding");
        helper.assertTrue("keep_nbt".equals(session.maid.getMaidInv().getStackInSlot(0).getTag().getString("marker")), "NBT retained");
        helper.assertTrue(session.maid.getMainHandItem().is(Items.IRON_SWORD), "Hand preserved");
        session.snapshot("ok"); chest.getItem(0).shrink(1);
        boolean stale = false;
        try { MaidGuiSessionManager.transfer(session, 0, to, 1, true); } catch (IllegalArgumentException expected) { stale = true; }
        helper.assertTrue(stale, "Concurrent slot changes rejected");
        session.actor.closeContainer(); helper.succeed();
    }
    @GameTest(template = "empty", templateNamespace = "aifun_gui_test")
    public static void craftingConsumesInputsAndReturnsCursor(GameTestHelper helper) {
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.CRAFTING_TABLE);
        MaidGuiSession session = session(helper);
        var pos = helper.absolutePos(new BlockPos(1, 1, 1));
        session.actor.openMenu(helper.getLevel().getBlockState(pos).getMenuProvider(helper.getLevel(), pos));
        session.menu().getSlot(1).set(new ItemStack(Items.OAK_PLANKS)); session.menu().getSlot(4).set(new ItemStack(Items.OAK_PLANKS));
        session.snapshot("ok"); int to = backpackSlot(session);
        helper.assertTrue(MaidGuiSessionManager.transfer(session, 0, to, 4, true) == 4, "Recipe output delivered");
        helper.assertTrue(session.menu().getSlot(1).getItem().isEmpty() && session.menu().getSlot(4).getItem().isEmpty(), "Recipe consumed inputs");
        session.menu().setCarried(new ItemStack(Items.GOLD_INGOT, 3)); session.actor.closeContainer();
        int gold = 0;
        for (int i = 0; i < session.maid.getAvailableBackpackInv().getSlots(); i++) {
            ItemStack stack = session.maid.getAvailableBackpackInv().getStackInSlot(i); if (stack.is(Items.GOLD_INGOT)) gold += stack.getCount();
        }
        helper.assertTrue(gold == 3, "Close returns cursor exactly once"); helper.succeed();
    }
    @GameTest(template = "empty", templateNamespace = "aifun_gui_test", timeoutTicks = 300)
    public static void furnaceReadsRealProgressAndFuelCountdownIsNotProgress(GameTestHelper helper) {
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.FURNACE);
        MaidGuiSession session = session(helper);
        var pos = helper.absolutePos(new BlockPos(1, 1, 1));
        session.actor.openMenu(helper.getLevel().getBlockState(pos).getMenuProvider(helper.getLevel(), pos));
        session.menu().getSlot(0).set(new ItemStack(Items.IRON_ORE, 3)); session.menu().getSlot(1).set(new ItemStack(Items.COAL));
        helper.runAfterDelay(5, () -> {
            GuiProcessState state = new FurnaceProcessAdapter().observe(session, "minecraft:iron_ingot", 3);
            helper.assertTrue(state.status() == GuiProcessState.Status.RUNNING, "Actual furnace running");
            helper.assertTrue(state.total() == 200 && state.remainingTicks() > 400, "Batch ETA uses all three recipes");
        });
        helper.runAfterDelay(225, () -> {
            helper.assertTrue(session.menu().getSlot(2).getItem().is(Items.IRON_INGOT), "Actual product after cooking");
            GuiProcessState state = new FurnaceProcessAdapter().observe(session, "minecraft:iron_ingot", 3);
            helper.assertTrue(state.remainingTicks() < 400, "ETA decreases as batch processes");
            session.actor.closeContainer(); helper.succeed();
        });
    }
    @GameTest(template = "empty", templateNamespace = "aifun_gui_test", timeoutTicks = 700)
    public static void automaticWaitCollectsConsecutiveRecipes(GameTestHelper helper) {
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.FURNACE);
        var callback = managed(helper, "AUTO"); var session = MaidGuiSessionManager.session(callback.getMaid().getUUID());
        session.menu().getSlot(0).set(new ItemStack(Items.IRON_ORE, 3)); session.menu().getSlot(1).set(new ItemStack(Items.COAL));
        JsonObject request = args(session); request.addProperty("item", "minecraft:iron_ingot"); request.addProperty("count", 3);
        var future = MaidGuiSessionManager.call(callback, "wait_gui", "wait", request);
        helper.runAfterDelay(660, () -> {
            helper.assertTrue(future.isDone(), "Entire task finished");
            helper.assertTrue(future.join().get("status").getAsString().equals("completed"), future.join().toString());
            helper.assertTrue(future.join().get("delivered_count").getAsInt() == 3, "Consecutive recipe completions counted before collection");
            helper.succeed();
        });
    }
    @GameTest(template = "empty", templateNamespace = "aifun_gui_test")
    public static void noWaitConfirmsStartupAndLeavesMaterials(GameTestHelper helper) {
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.FURNACE);
        var callback = managed(helper, "NO_WAIT"); var session = MaidGuiSessionManager.session(callback.getMaid().getUUID());
        session.menu().getSlot(0).set(new ItemStack(Items.IRON_ORE, 3)); session.menu().getSlot(1).set(new ItemStack(Items.COAL));
        JsonObject request = args(session); request.addProperty("item", "minecraft:iron_ingot"); request.addProperty("count", 3);
        var future = MaidGuiSessionManager.call(callback, "wait_gui", "wait", request);
        helper.runAfterDelay(40, () -> {
            helper.assertTrue(future.isDone() && future.join().get("status").getAsString().equals("started_no_wait"), future.isDone() ? future.join().toString() : "Still waiting");
            helper.assertTrue(session.menu() == session.actor.inventoryMenu, "Menu closed after startup");
            var furnace = (net.minecraft.world.level.block.entity.FurnaceBlockEntity) helper.getBlockEntity(new BlockPos(1, 1, 1));
            helper.assertTrue(furnace.getItem(0).getCount() == 3 && furnace.getBlockState().getValue(net.minecraft.world.level.block.AbstractFurnaceBlock.LIT), "Machine materials retained and processing continues"); helper.succeed();
        });
    }
    @GameTest(template = "empty", templateNamespace = "aifun_gui_test")
    public static void furnaceExactOutputAndFullBackpackClose(GameTestHelper helper) {
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.FURNACE);
        MaidGuiSession session = session(helper); var pos = helper.absolutePos(new BlockPos(1, 1, 1));
        session.actor.openMenu(helper.getLevel().getBlockState(pos).getMenuProvider(helper.getLevel(), pos));
        session.menu().getSlot(2).set(new ItemStack(Items.IRON_INGOT, 5)); session.snapshot("ok");
        helper.assertTrue(MaidGuiSessionManager.transfer(session, 2, backpackSlot(session), 2, true) == 2, "Exact divisible furnace output");
        helper.assertTrue(session.menu().getSlot(2).getItem().getCount() == 3, "Remaining output retained");
        for (int i = 0; i < session.maid.getAvailableBackpackInv().getSlots(); i++) session.maid.getMaidInv().setStackInSlot(i, new ItemStack(Items.COBBLESTONE, 64));
        session.maid.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
        helper.assertTrue(session.actor.getInventory().getFreeSlot() == -1, "Disabled inventory cannot swallow leftovers");
        session.menu().setCarried(new ItemStack(Items.GOLD_INGOT, 3)); session.actor.closeContainer();
        helper.assertTrue(session.actor.droppedCount == 3, "Overflow returned at maid and reported"); helper.succeed();
    }
    @GameTest(template = "empty", templateNamespace = "aifun_gui_test")
    public static void transientInputsSaveAndReloadWithoutResultDuplication(GameTestHelper helper) {
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.CRAFTING_TABLE);
        var callback = managed(helper, "AUTO"); var session = MaidGuiSessionManager.session(callback.getMaid().getUUID());
        session.menu().getSlot(1).set(new ItemStack(Items.OAK_PLANKS)); session.menu().getSlot(4).set(new ItemStack(Items.OAK_PLANKS));
        session.menu().setCarried(new ItemStack(Items.DIAMOND, 2));
        var saved = new net.minecraft.nbt.CompoundTag(); session.maid.saveWithoutId(saved);
        var transientItems = saved.getList("TouhouAIFunGuiItems", 10);
        helper.assertTrue(transientItems.size() == 3, "Only two inputs and cursor saved; result excluded");
        EntityMaid restored = InitEntities.MAID.get().create(helper.getLevel()); restored.load(saved);
        int planks = 0, diamonds = 0, sticks = 0;
        for (int i = 0; i < restored.getAvailableBackpackInv().getSlots(); i++) {
            ItemStack stack = restored.getMaidInv().getStackInSlot(i);
            if (stack.is(Items.OAK_PLANKS)) planks += stack.getCount(); if (stack.is(Items.DIAMOND)) diamonds += stack.getCount(); if (stack.is(Items.STICK)) sticks += stack.getCount();
        }
        helper.assertTrue(planks == 2 && diamonds == 2 && sticks == 0, "Reload returns transient inputs exactly once");
        MaidGuiSessionManager.cancel(session.maid.getUUID(), "qa_complete"); helper.succeed();
    }
    @GameTest(template = "empty", templateNamespace = "aifun_gui_test")
    public static void heldTerminalRestoresHandAndBackpack(GameTestHelper helper) {
        var seed = session(helper); var maid = seed.maid;
        maid.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
        ItemStack terminal = new ItemStack(GuiFixtureMod.TERMINAL.get()); terminal.getOrCreateTag().putString("marker", "terminal_nbt");
        maid.getMaidInv().setStackInSlot(0, terminal);
        var callback = new LLMCallback(maid.getAiChatManager(), new ArrayList<>(), true);
        JsonObject open = new JsonObject(); open.addProperty("item_slot", 1); open.addProperty("wait_policy", "AUTO");
        var result = MaidGuiSessionManager.call(callback, "open_gui", "open", open).join();
        helper.assertTrue(result.get("status").getAsString().equals("opened"), result.toString());
        var active = MaidGuiSessionManager.session(maid.getUUID());
        helper.assertTrue(maid.getMainHandItem().is(GuiFixtureMod.TERMINAL.get()), "Terminal held while menu is active");
        var closed = MaidGuiSessionManager.call(callback, "close_gui", "close", args(active)).join();
        helper.assertTrue(closed.get("status").getAsString().equals("closed"), closed.toString());
        helper.assertTrue(maid.getMainHandItem().is(Items.IRON_SWORD) && maid.getMaidInv().getStackInSlot(0).is(GuiFixtureMod.TERMINAL.get()), "Both hand and backpack restored");
        helper.assertTrue("terminal_nbt".equals(maid.getMaidInv().getStackInSlot(0).getTag().getString("marker")), "Held item NBT retained"); helper.succeed();
    }
    @GameTest(template = "empty", templateNamespace = "aifun_gui_test", timeoutTicks = 200)
    public static void navigationYieldsNormalBrainAndReachesTarget(GameTestHelper helper) {
        helper.setBlock(new BlockPos(5, 1, 4), Blocks.CHEST);
        var seed = session(helper); var maid = seed.maid; maid.setNoAi(false);
        var pos = helper.absolutePos(new BlockPos(5, 1, 4));
        var callback = new LLMCallback(maid.getAiChatManager(), new ArrayList<>(), true);
        JsonObject open = new JsonObject(); open.addProperty("x", pos.getX()); open.addProperty("y", pos.getY()); open.addProperty("z", pos.getZ()); open.addProperty("wait_policy", "AUTO");
        var future = MaidGuiSessionManager.call(callback, "open_gui", "open", open);
        helper.runAfterDelay(150, () -> {
            helper.assertTrue(future.isDone(), "Navigation reached GUI");
            helper.assertTrue(future.join().get("status").getAsString().equals("opened"), future.join().toString());
            helper.assertTrue(maid.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(pos)) <= 2.25 * 2.25, "GUI opened within interaction range");
            MaidGuiSessionManager.cancel(maid.getUUID(), "qa_complete"); helper.succeed();
        });
    }
    @GameTest(template = "empty", templateNamespace = "aifun_gui_test")
    public static void anvilRenameReportsUnavailablePlayerExperience(GameTestHelper helper) {
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.ANVIL);
        var callback = managed(helper, "AUTO"); var active = MaidGuiSessionManager.session(callback.getMaid().getUUID());
        active.menu().getSlot(0).set(new ItemStack(Items.IRON_SWORD));
        JsonObject request = args(active); request.addProperty("action", "rename"); request.addProperty("text", "Maid Sword");
        var result = MaidGuiSessionManager.call(callback, "gui_action", "rename", request).join();
        helper.assertTrue(result.get("status").getAsString().equals("ok"), result.toString());
        helper.assertTrue(result.get("unavailable_reason").getAsString().equals("player_experience_not_bridged"), "Player XP cost explicitly unavailable");
        helper.assertTrue(!active.menu().getSlot(2).mayPickup(active.actor), "No experience bypass");
        MaidGuiSessionManager.cancel(active.maid.getUUID(), "qa_complete");
        helper.assertTrue(active.maid.getMaidInv().getStackInSlot(0).is(Items.IRON_SWORD), "Unconsumed anvil input returned"); helper.succeed();
    }
    @GameTest(template = "empty", templateNamespace = "aifun_gui_test")
    public static void villagerTradeConsumesPaymentAndCallsOfferCallback(GameTestHelper helper) {
        var seed = session(helper); var maid = seed.maid;
        var villager = helper.spawn(net.minecraft.world.entity.EntityType.VILLAGER, new BlockPos(1, 1, 1)); villager.setNoAi(true); villager.setInvulnerable(true);
        var offers = new net.minecraft.world.item.trading.MerchantOffers();
        offers.add(new net.minecraft.world.item.trading.MerchantOffer(new ItemStack(Items.EMERALD, 2), new ItemStack(Items.DIAMOND), 5, 1, 0));
        villager.setOffers(offers);
        maid.getMaidInv().setStackInSlot(0, new ItemStack(Items.EMERALD, 2));
        var callback = new LLMCallback(maid.getAiChatManager(), new ArrayList<>(), true);
        JsonObject open = new JsonObject(); open.addProperty("entity_uuid", villager.getUUID().toString()); open.addProperty("wait_policy", "AUTO");
        var opened = MaidGuiSessionManager.call(callback, "open_gui", "open", open).join();
        helper.assertTrue(opened.get("status").getAsString().equals("opened"), opened.toString());
        var active = MaidGuiSessionManager.session(maid.getUUID());
        JsonObject trade = args(active); trade.addProperty("action", "trade"); trade.addProperty("trade_index", 0);
        helper.assertTrue(MaidGuiSessionManager.call(callback, "gui_action", "trade", trade).join().get("status").getAsString().equals("ok"), "Trade selected");
        helper.assertTrue(MaidGuiSessionManager.transfer(active, 2, backpackSlot(active), 1, true) == 1, "Trade result delivered");
        helper.assertTrue(offers.get(0).getUses() == 1 && active.menu().getSlot(0).getItem().isEmpty(), "Payment consumed and trade callback executed");
        MaidGuiSessionManager.cancel(maid.getUUID(), "qa_complete"); helper.succeed();
    }
}
