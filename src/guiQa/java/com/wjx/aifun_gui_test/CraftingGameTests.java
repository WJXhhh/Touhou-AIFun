package com.wjx.aifun_gui_test;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.Role;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.init.InitEntities;
import com.google.gson.*;
import com.wjx.touhou_aifun.compat.ai.action.GuiTool;
import com.wjx.touhou_aifun.maid.gui.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.*;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Full tool -> real menu -> live backpack tests. No ingredients are injected into the grid. */
@GameTestHolder("aifun_gui_test")
@PrefixGameTestTemplate(false)
public final class CraftingGameTests {
    private static final class Fixture {
        final GameTestHelper helper;
        final EntityMaid maid;
        final LLMCallback callback;
        final BlockPos target;
        Fixture(GameTestHelper helper) {
            this.helper = helper;
            for (BlockPos floor : BlockPos.betweenClosed(new BlockPos(0, 0, 0), new BlockPos(5, 0, 5))) helper.setBlock(floor, Blocks.STONE);
            maid = helper.spawn(InitEntities.MAID.get(), new BlockPos(2, 1, 2));
            maid.setNoAi(true); maid.setInvulnerable(true);
            callback = new LLMCallback(maid.getAiChatManager(), new ArrayList<>(), true);
            helper.setBlock(new BlockPos(1, 1, 1), Blocks.CRAFTING_TABLE);
            target = helper.absolutePos(new BlockPos(1, 1, 1));
        }
        void put(int slot, ItemStack stack) { maid.getMaidInv().setStackInSlot(slot, stack); }
        int count(Item item) {
            int total = 0; var inventory = maid.getAvailableBackpackInv();
            for (int i = 0; i < inventory.getSlots(); i++) if (inventory.getStackInSlot(i).is(item)) total += inventory.getStackInSlot(i).getCount();
            return total;
        }
        CompletableFuture<JsonObject> call(String id, String tool, JsonObject args) {
            return new GuiTool(tool).onCallAsync(id, args, callback, null).thenApply(ignored ->
                    JsonParser.parseString(callback.getMessages().stream().filter(message -> message.role() == Role.TOOL && id.equals(message.toolCallId()))
                            .reduce((left, right) -> right).orElseThrow().message()).getAsJsonObject());
        }
        JsonObject query(String item) {
            var args = new JsonObject(); args.addProperty("item", item);
            return call("query_" + item, "query_recipes", args).join();
        }
        void open() {
            var args = new JsonObject(); args.addProperty("target", "minecraft:crafting_table");
            args.addProperty("x", target.getX()); args.addProperty("y", target.getY()); args.addProperty("z", target.getZ()); args.addProperty("wait_policy", "AUTO");
            var result = call("open", "open_gui", args).join();
            helper.assertTrue(!result.has("error"), result.toString());
        }
        JsonObject action(String action, String recipe, int count, boolean close) {
            var args = new JsonObject(); args.addProperty("session_id", MaidGuiSessionManager.session(maid.getUUID()).id.toString());
            args.addProperty("action", action); args.addProperty("recipe_id", recipe); args.addProperty("count", count);
            args.addProperty("close_after", close); return args;
        }
        CompletableFuture<JsonObject> craft(String recipe, int count) { return call("craft", "gui_action", action("craft_recipe", recipe, count, true)); }
        void finish() { MaidGuiSessionManager.cancel(maid.getUUID(), "qa_complete"); }
    }

    @GameTest(batch="crafting",template="empty",templateNamespace="aifun_gui_test",timeoutTicks=100)
    public static void queriesFillsAndCraftsRequestedModOutputFromBackpackThroughPublicTools(GameTestHelper helper) {
        var f = new Fixture(helper);
        f.put(0, new ItemStack(Items.OAK_PLANKS, 3)); f.put(1, new ItemStack(Items.BIRCH_PLANKS, 3)); f.put(2, new ItemStack(Items.IRON_INGOT, 3));
        ItemStack hand = new ItemStack(Items.IRON_SWORD); hand.getOrCreateTag().putString("qa_hand", "preserve");
        f.maid.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, hand.copy());
        var query = f.query("aifun_gui_test:machine");
        helper.assertTrue(query.get("source").getAsString().equals("live_server_recipe_manager"), "Recipe comes from loaded server data");
        helper.assertTrue(query.getAsJsonArray("recipes").get(0).getAsJsonObject().get("can_fill_one_craft").getAsBoolean(), "Live backpack availability includes tag alternatives");
        f.open();
        var fill = f.call("fill", "gui_action", f.action("fill_recipe", "aifun_gui_test:qa_machine_shaped", 1, false)).join();
        helper.assertTrue(!fill.has("error"), fill.toString());
        var menu = MaidGuiSessionManager.session(f.maid.getUUID()).menu();
        helper.assertTrue(menu.getSlot(1).getItem().is(Items.OAK_PLANKS) && menu.getSlot(2).getItem().is(Items.IRON_INGOT)
                && !menu.getSlot(5).getItem().isEmpty(), "2x2 shaped layout is placed in the correct 3x3 positions");
        helper.assertTrue(f.count(GuiFixtureMod.MACHINE.get().asItem()) == 0, "Fill alone does not take output");
        var future = f.craft("aifun_gui_test:qa_machine_shaped", 6);
        helper.succeedWhen(() -> {
            helper.assertTrue(future.isDone(), "Requested batch finished"); var result = future.join();
            helper.assertTrue(!result.has("error") && result.get("crafted_count").getAsInt() == 6, result.toString());
            helper.assertTrue(f.count(GuiFixtureMod.MACHINE.get().asItem()) == 6 && f.count(Items.IRON_INGOT) == 0
                    && f.count(Items.OAK_PLANKS) + f.count(Items.BIRCH_PLANKS) == 0, "Exactly six mod products replace the actual backpack materials");
            helper.assertTrue(ItemStack.matches(hand, f.maid.getMainHandItem()), "Crafting does not use or change the maid's held item");
            helper.assertTrue(MaidGuiSessionManager.session(f.maid.getUUID()) == null, "close_after settles the real workbench");
        });
    }

    @GameTest(batch="crafting",template="empty",templateNamespace="aifun_gui_test",timeoutTicks=100)
    public static void craftsShapelessRecipeWithModIngredientAndModOutput(GameTestHelper helper) {
        var f = new Fixture(helper); f.put(0, new ItemStack(GuiFixtureMod.MACHINE.get().asItem(), 2)); f.put(1, new ItemStack(Items.REDSTONE, 2));
        f.open(); var future = f.craft("aifun_gui_test:qa_terminal_shapeless", 2);
        helper.succeedWhen(() -> {
            helper.assertTrue(future.isDone(), "Shapeless batch completed");
            helper.assertTrue(!future.join().has("error"), future.join().toString());
            helper.assertTrue(f.count(GuiFixtureMod.TERMINAL.get()) == 2 && f.count(Items.REDSTONE) == 0
                    && f.count(GuiFixtureMod.MACHINE.get().asItem()) == 0, "Unstackable mod outputs and mod ingredients use real inventory");
        });
    }

    @GameTest(batch="crafting",template="empty",templateNamespace="aifun_gui_test",timeoutTicks=100)
    public static void matchesOverlappingTagsWithoutStealingTheSpecificIngredient(GameTestHelper helper) {
        var f = new Fixture(helper); f.put(0, new ItemStack(Items.OAK_LOG)); f.put(1, new ItemStack(Items.BIRCH_LOG));
        f.open(); var future = f.craft("aifun_gui_test:qa_overlap", 1);
        helper.succeedWhen(() -> {
            helper.assertTrue(future.isDone() && !future.join().has("error"), future.isDone() ? future.join().toString() : "Craft pending");
            helper.assertTrue(f.count(Items.PAPER) == 1 && f.count(Items.OAK_LOG) + f.count(Items.BIRCH_LOG) == 0,
                    "Specific oak ingredient gets oak while broad log tag gets birch");
        });
    }

    @GameTest(batch="crafting",template="empty",templateNamespace="aifun_gui_test",timeoutTicks=100)
    public static void respectsForgeNbtIngredientInsteadOfMatchingOnlyItemId(GameTestHelper helper) {
        var f = new Fixture(helper); f.put(0, new ItemStack(Items.PAPER));
        ItemStack special = new ItemStack(Items.PAPER); special.getOrCreateTag().putInt("aifun_qa", 1); f.put(1, special);
        f.open(); var future = f.craft("aifun_gui_test:qa_nbt", 1);
        helper.succeedWhen(() -> {
            helper.assertTrue(future.isDone() && !future.join().has("error"), future.isDone() ? future.join().toString() : "Craft pending");
            helper.assertTrue(f.count(Items.EMERALD) == 1 && f.count(Items.PAPER) == 1
                    && !f.maid.getAvailableBackpackInv().getStackInSlot(0).hasTag(), "Only the qualifying NBT stack is consumed");
        });
    }

    @GameTest(batch="crafting",template="empty",templateNamespace="aifun_gui_test",timeoutTicks=100)
    public static void nativeCraftingReturnsMilkBucketsWithoutDroppingOrDuplicating(GameTestHelper helper) {
        var f = new Fixture(helper);
        f.put(0, new ItemStack(Items.MILK_BUCKET)); f.put(1, new ItemStack(Items.MILK_BUCKET)); f.put(2, new ItemStack(Items.MILK_BUCKET));
        f.put(3, new ItemStack(Items.SUGAR, 2)); f.put(4, new ItemStack(Items.EGG)); f.put(5, new ItemStack(Items.WHEAT, 3));
        f.open(); var future = f.craft("minecraft:cake", 1);
        helper.succeedWhen(() -> {
            helper.assertTrue(future.isDone() && !future.join().has("error"), future.isDone() ? future.join().toString() : "Craft pending");
            helper.assertTrue(f.count(Items.CAKE) == 1 && f.count(Items.BUCKET) == 3 && f.count(Items.MILK_BUCKET) == 0, "Native remainder buckets survive menu cleanup exactly once");
            helper.assertTrue(future.join().getAsJsonObject("cleanup").get("dropped_count").getAsInt() == 0, "No output or remainder dropped");
        });
    }

    @GameTest(batch="crafting",template="empty",templateNamespace="aifun_gui_test",timeoutTicks=100)
    public static void reportsNativeBatchSurplusAndPartialProgressWithoutReplayingDuplicateCall(GameTestHelper helper) {
        var f = new Fixture(helper); f.put(0, new ItemStack(Items.OAK_PLANKS, 4)); f.open();
        var args = f.action("craft_recipe", "minecraft:stick", 12, false);
        var future = f.call("partial", "gui_action", args);
        helper.succeedWhen(() -> {
            helper.assertTrue(future.isDone(), "Material exhaustion returned"); var result = future.join();
            helper.assertTrue(result.get("crafted_count").getAsInt() == 8 && result.get("crafts_executed").getAsInt() == 2
                    && result.get("error").getAsString().equals("missing_recipe_ingredients"), result.toString());
            var duplicate = MaidGuiSessionManager.call(f.callback, "gui_action", "partial", args).join();
            helper.assertTrue(duplicate.get("crafted_count").getAsInt() == 8 && f.count(Items.STICK) == 8, "Same action id cannot replay completed crafting");
            f.finish();
        });
    }

    @GameTest(batch="crafting",template="empty",templateNamespace="aifun_gui_test",timeoutTicks=100)
    public static void roundsTargetUpOnlyToNativeBatchAndReportsActualSurplus(GameTestHelper helper) {
        var f = new Fixture(helper); f.put(0, new ItemStack(Items.OAK_PLANKS, 4)); f.open();
        var future = f.craft("minecraft:stick", 5);
        helper.succeedWhen(() -> {
            helper.assertTrue(future.isDone() && !future.join().has("error"), future.isDone() ? future.join().toString() : "Craft pending");
            helper.assertTrue(future.join().get("crafted_count").getAsInt() == 8 && future.join().get("surplus_count").getAsInt() == 3
                    && f.count(Items.STICK) == 8, "Whole recipe batches are accounted for honestly");
        });
    }

    @GameTest(batch="crafting",template="empty",templateNamespace="aifun_gui_test")
    public static void missingMaterialsAndFullBackpackDoNotConsumeOrDropAnything(GameTestHelper helper) {
        var f = new Fixture(helper); f.put(0, new ItemStack(Items.OAK_PLANKS)); f.put(1, new ItemStack(Items.IRON_INGOT)); f.open();
        var missing = f.call("missing", "gui_action", f.action("fill_recipe", "aifun_gui_test:qa_machine_shaped", 1, false)).join();
        helper.assertTrue(missing.get("error").getAsString().equals("missing_recipe_ingredients") && f.count(Items.OAK_PLANKS) == 1
                && f.count(Items.IRON_INGOT) == 1, "Failed ingredient allocation does not mutate inventory");
        for (int i = 0; i < f.maid.getAvailableBackpackInv().getSlots(); i++) f.put(i, new ItemStack(i == 0 ? Items.OAK_PLANKS : Items.COBBLESTONE, 64));
        MaidGuiSessionManager.session(f.maid.getUUID()).snapshot("ok");
        var future = f.craft("minecraft:stick", 4);
        helper.succeedWhen(() -> {
            helper.assertTrue(future.isDone(), "Full backpack returned"); var result = future.join();
            helper.assertTrue(result.get("error").getAsString().equals("backpack_full_for_crafting_output")
                    && result.get("crafted_count").getAsInt() == 0 && f.count(Items.OAK_PLANKS) == 64 && f.count(Items.STICK) == 0,
                    "Output capacity checked before consuming any input"); f.finish();
        });
    }

    @GameTest(batch="crafting",template="empty",templateNamespace="aifun_gui_test",timeoutTicks=100)
    public static void cancellationPreservesOnlyActualDeliveredCountAndReturnsGridMaterials(GameTestHelper helper) {
        var f = new Fixture(helper); f.put(0, new ItemStack(Items.OAK_PLANKS, 64)); f.open();
        var future = f.call("cancelled", "gui_action", f.action("craft_recipe", "minecraft:stick", 100, false));
        helper.runAfterDelay(3, () -> {
            MaidGuiSessionManager.cancel(f.maid.getUUID(), "qa_cancel");
            helper.assertTrue(future.isDone(), "Cancel settles crafting future"); var result = future.join();
            int made = result.get("crafted_count").getAsInt();
            helper.assertTrue(made > 0 && made < 100 && made == f.count(Items.STICK), "Only delivered output is reported after cancellation");
            helper.assertTrue(f.count(Items.OAK_PLANKS) == 64 - made / 2, "Unconsumed material is returned once"); helper.succeed();
        });
    }

    @GameTest(batch="craftingReload",template="empty",templateNamespace="aifun_gui_test",timeoutTicks=100)
    public static void usesRuntimeReplacementRecipeInsteadOfRememberedVanillaIngredients(GameTestHelper helper) {
        var f = new Fixture(helper); var manager = helper.getLevel().getRecipeManager();
        List<Recipe<?>> originals = new ArrayList<>(manager.getRecipes());
        var replacement = new ShapelessRecipe(new ResourceLocation("minecraft:stick"), "", CraftingBookCategory.MISC,
                new ItemStack(Items.STICK, 7), NonNullList.of(Ingredient.EMPTY, Ingredient.of(GuiFixtureMod.TERMINAL.get())));
        var loaded = new ArrayList<Recipe<?>>(originals.stream().filter(recipe -> !recipe.getId().equals(replacement.getId())).toList()); loaded.add(replacement);
        manager.replaceRecipes(loaded);
        try {
            f.put(0, new ItemStack(GuiFixtureMod.TERMINAL.get())); f.put(1, new ItemStack(GuiFixtureMod.TERMINAL.get()));
            var query = f.query("minecraft:stick").getAsJsonArray("recipes").get(0).getAsJsonObject();
            helper.assertTrue(query.getAsJsonObject("output").get("count").getAsInt() == 7, "Query sees replaced output quantity");
            f.open(); var future = f.craft("minecraft:stick", 14);
            helper.runAfterDelay(6, () -> {
                try {
                    helper.assertTrue(future.isDone() && !future.join().has("error"), future.isDone() ? future.join().toString() : "Replacement craft pending");
                    helper.assertTrue(f.count(Items.STICK) == 14 && f.count(GuiFixtureMod.TERMINAL.get()) == 0, "Actual replacement ingredients, counts and server matching drive crafting"); helper.succeed();
                } finally { manager.replaceRecipes(originals); f.finish(); }
            });
        } catch (RuntimeException | Error failure) { manager.replaceRecipes(originals); throw failure; }
    }
}
