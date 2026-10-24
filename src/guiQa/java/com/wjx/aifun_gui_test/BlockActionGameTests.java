package com.wjx.aifun_gui_test;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.block.BlockSnackCabinet;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.init.InitEntities;
import com.google.gson.JsonObject;
import com.wjx.touhou_aifun.maid.action.block.MaidBlockActionManager;
import com.wjx.touhou_aifun.maid.action.block.MaidBlockActionRequest;
import com.wjx.touhou_aifun.maid.action.block.MaidBlockActionResult;
import com.wjx.touhou_aifun.maid.gui.MaidGuiSessionManager;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Real-server simulated block action coverage: no direct setBlock is used by the action itself. */
@GameTestHolder("aifun_build_test")
@PrefixGameTestTemplate(false)
public final class BlockActionGameTests {
    private static EntityMaid maid(GameTestHelper helper, BlockPos pos) {
        FourChestNavigationGameTests.prepareFloor(helper);
        EntityMaid maid = helper.spawn(InitEntities.MAID.get(), pos);
        maid.setNoAi(true);
        maid.setInvulnerable(true);
        FourChestNavigationGameTests.prepareNavigationChunks(maid);
        return maid;
    }

    private static LLMCallback callback(EntityMaid maid) {
        return new LLMCallback(maid.getAiChatManager(), new ArrayList<>(), true);
    }

    private static CompletableFuture<MaidBlockActionResult> place(EntityMaid maid, BlockPos target, String item) {
        JsonObject args = new JsonObject();
        args.addProperty("action", "place");
        args.addProperty("x", target.getX());
        args.addProperty("y", target.getY());
        args.addProperty("z", target.getZ());
        args.addProperty("item", item);
        args.addProperty("timeout_seconds", 10);
        return MaidBlockActionManager.start(maid, callback(maid), MaidBlockActionRequest.parse(args, maid));
    }

    private static CompletableFuture<MaidBlockActionResult> mine(EntityMaid maid, BlockPos target, String tool) {
        JsonObject args = new JsonObject();
        args.addProperty("action", "mine");
        args.addProperty("x", target.getX());
        args.addProperty("y", target.getY());
        args.addProperty("z", target.getZ());
        if (tool != null) args.addProperty("tool", tool);
        args.addProperty("timeout_seconds", 10);
        return MaidBlockActionManager.start(maid, callback(maid), MaidBlockActionRequest.parse(args, maid));
    }

    private static <T> void await(GameTestHelper helper, CompletableFuture<T> future, int remaining,
                                  Consumer<T> completed, Runnable onFailure) {
        if (future.isDone()) {
            try { completed.accept(future.join()); }
            catch (RuntimeException failed) { onFailure.run(); }
            return;
        }
        if (remaining <= 0) {
            onFailure.run();
            return;
        }
        helper.runAfterDelay(1, () -> await(helper, future, remaining - 1, completed, onFailure));
    }

    private static void clean(EntityMaid maid) {
        MaidBlockActionManager.cancelForMaid(maid.getUUID(), "qa_complete");
        MaidGuiSessionManager.cancel(maid.getUUID(), "qa_complete");
        maid.discard();
    }

    @GameTest(batch = "isolated_blockActionPlace", template = "four_chests", templateNamespace = "aifun_build_test")
    public static void placeFromBackpackConsumesExactlyOne(GameTestHelper helper) {
        EntityMaid maid = maid(helper, new BlockPos(5, 1, 2));
        BlockPos target = helper.absolutePos(new BlockPos(4, 1, 2));
        helper.setBlock(new BlockPos(4, 0, 2), Blocks.STONE);
        maid.getMaidInv().setStackInSlot(0, new ItemStack(Items.COBBLESTONE, 4));
        CompletableFuture<MaidBlockActionResult> future = place(maid, target, "minecraft:cobblestone");
        await(helper, future, 60, result -> {
            helper.assertTrue(result.success(), "placement succeeds: " + result.toJson());
            helper.assertTrue(helper.getBlockState(new BlockPos(4, 1, 2)).is(Blocks.COBBLESTONE),
                    "actual world has the placed block");
            helper.assertTrue(maid.getMaidInv().getStackInSlot(0).getCount() == 3,
                    "exactly one cobblestone was consumed, got " + maid.getMaidInv().getStackInSlot(0).getCount());
            clean(maid);
            helper.succeed();
        }, () -> {
            clean(maid);
            helper.fail("placement timed out");
        });
    }

    @GameTest(batch = "isolated_blockActionCake", template = "four_chests", templateNamespace = "aifun_build_test")
    public static void cakeOnSnackCabinetUpdatesCabinetState(GameTestHelper helper) {
        EntityMaid maid = maid(helper, new BlockPos(5, 1, 2));
        BlockPos cabinet = helper.absolutePos(new BlockPos(4, 1, 2));
        helper.setBlock(new BlockPos(4, 0, 2), Blocks.STONE);
        helper.setBlock(new BlockPos(4, 1, 2), ForgeRegistries.BLOCKS.getValue(
                new net.minecraft.resources.ResourceLocation("touhou_little_maid", "snack_cabinet")));
        BlockPos target = cabinet.above();
        maid.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Items.CAKE, 1));
        CompletableFuture<MaidBlockActionResult> future = place(maid, target, "minecraft:cake");
        await(helper, future, 60, result -> {
            helper.assertTrue(result.success(), "cake placement succeeds: " + result.toJson());
            helper.assertTrue(helper.getBlockState(new BlockPos(4, 2, 2)).is(Blocks.CAKE), "cake block exists above the cabinet");
            var cabinetState = helper.getBlockState(new BlockPos(4, 1, 2));
            helper.assertTrue(cabinetState.getBlock() instanceof BlockSnackCabinet, "cabinet block remains");
            helper.assertTrue(cabinetState.getValue(BlockSnackCabinet.TYPE) != BlockSnackCabinet.TYPE_NONE,
                    "cabinet TYPE updated from the placed cake; type=" + cabinetState.getValue(BlockSnackCabinet.TYPE));
            helper.assertTrue(maid.getMainHandItem().isEmpty(), "cake item consumed");
            clean(maid);
            helper.succeed();
        }, () -> {
            clean(maid);
            helper.fail("cake placement timed out");
        });
    }

    @GameTest(batch = "isolated_blockActionMine", template = "four_chests", templateNamespace = "aifun_build_test")
    public static void mineWithToolDropsAndDamages(GameTestHelper helper) {
        EntityMaid maid = maid(helper, new BlockPos(5, 1, 2));
        BlockPos target = helper.absolutePos(new BlockPos(4, 1, 2));
        helper.setBlock(new BlockPos(4, 1, 2), Blocks.STONE);
        ItemStack pickaxe = new ItemStack(Items.IRON_PICKAXE);
        maid.getMaidInv().setStackInSlot(0, pickaxe);
        int before = pickaxe.getDamageValue();
        CompletableFuture<MaidBlockActionResult> future = mine(maid, target, "minecraft:iron_pickaxe");
        await(helper, future, 60, result -> {
            helper.assertTrue(result.success(), "mining succeeds: " + result.toJson());
            helper.assertTrue(helper.getBlockState(new BlockPos(4, 1, 2)).isAir(), "block is air after mining");
            int cobble = 0;
            for (int i = 0; i < maid.getMaidInv().getSlots(); i++)
                if (maid.getMaidInv().getStackInSlot(i).is(Items.COBBLESTONE))
                    cobble += maid.getMaidInv().getStackInSlot(i).getCount();
            helper.assertTrue(cobble >= 1, "cobblestone dropped into the maid inventory");
            helper.assertTrue(maid.getMaidInv().getStackInSlot(0).getDamageValue() > before,
                    "tool durability decreased");
            clean(maid);
            helper.succeed();
        }, () -> {
            clean(maid);
            helper.fail("mining timed out");
        });
    }
}
