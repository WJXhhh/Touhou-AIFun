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
