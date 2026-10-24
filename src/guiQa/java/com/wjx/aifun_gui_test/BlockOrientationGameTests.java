package com.wjx.aifun_gui_test;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.init.InitEntities;
import com.google.gson.JsonObject;
import com.wjx.touhou_aifun.maid.action.block.MaidBlockActionManager;
import com.wjx.touhou_aifun.maid.action.block.MaidBlockActionRequest;
import com.wjx.touhou_aifun.maid.action.block.MaidBlockActionResult;
import com.wjx.touhou_aifun.maid.gui.MaidGuiSessionManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Deterministic placement orientation: the placed block must follow the requested expected state
 * (or the facing argument), never the maid's live Brain body yaw.
 */
@GameTestHolder("aifun_build_test")
@PrefixGameTestTemplate(false)
public final class BlockOrientationGameTests {
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

    private static JsonObject args(BlockPos target, String item) {
        JsonObject args = new JsonObject();
        args.addProperty("action", "place");
        args.addProperty("x", target.getX());
        args.addProperty("y", target.getY());
        args.addProperty("z", target.getZ());
        args.addProperty("item", item);
        args.addProperty("timeout_seconds", 10);
        return args;
    }

    private static CompletableFuture<MaidBlockActionResult> place(EntityMaid maid, JsonObject args) {
        return MaidBlockActionManager.start(maid, callback(maid), MaidBlockActionRequest.parse(args, maid));
    }

    private static <T> void await(GameTestHelper helper, CompletableFuture<T> future, int remaining,
                                  Consumer<T> completed, Runnable onFailure) {
        if (future.isDone()) {
            try { completed.accept(future.join()); }
            catch (RuntimeException failed) {
                helper.fail("placement assertion: " + (failed.getMessage() == null ? failed : failed.getMessage()));
            }
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

    /** Runs assertions and always cleans the maid, then lets the batch succeed. */
    private static void finish(GameTestHelper helper, EntityMaid maid, Runnable assertions) {
        try {
            assertions.run();
        } finally {
            clean(maid);
        }
        helper.succeed();
    }

    private static void assertOrientation(GameTestHelper helper, JsonObject result) {
        helper.assertTrue(result.has("orientation"), "result exposes the chosen placement rotation: " + result);
        JsonObject orientation = result.getAsJsonObject("orientation");
        helper.assertTrue(orientation.get("satisfied").getAsBoolean(),
                "placement rotation satisfies the requested state: " + orientation);
    }

    @GameTest(batch = "isolated_orientationStairs", template = "four_chests", templateNamespace = "aifun_build_test")
    public static void stairsHonorExpectedFacingDespiteMaidRotation(GameTestHelper helper) {
        EntityMaid maid = maid(helper, new BlockPos(5, 1, 2));
        // The wandering Brain body yaw must not decide the block orientation any more.
        maid.setYRot(90.0F);
        maid.setYHeadRot(90.0F);
        maid.getMaidInv().setStackInSlot(0, new ItemStack(Items.OAK_STAIRS, 4));
        BlockPos target = helper.absolutePos(new BlockPos(4, 1, 2));
        JsonObject args = args(target, "minecraft:oak_stairs");
        args.addProperty("expected", "minecraft:oak_stairs[facing=north]");
        CompletableFuture<MaidBlockActionResult> future = place(maid, args);
        await(helper, future, 80, result -> finish(helper, maid, () -> {
            helper.assertTrue(result.success(), "stairs placement succeeds: " + result.toJson());
            var state = helper.getBlockState(new BlockPos(4, 1, 2));
            helper.assertTrue(state.is(Blocks.OAK_STAIRS), "oak stairs actually placed");
            helper.assertTrue(state.getValue(StairBlock.FACING) == Direction.NORTH,
                    "stairs face north regardless of maid yaw, got " + state.getValue(StairBlock.FACING));
            helper.assertTrue(result.toJson().get("expected_block").getAsString().contains("facing=north"),
                    "augmented expected state verifies the orientation: " + result.toJson());
            assertOrientation(helper, result.toJson());
        }), () -> {
            clean(maid);
            helper.fail("orientation stairs placement timed out");
        });
    }

    @GameTest(batch = "isolated_orientationFacingArg", template = "four_chests", templateNamespace = "aifun_build_test")
    public static void facingArgumentControlsOrientationWithoutExpectedState(GameTestHelper helper) {
        EntityMaid maid = maid(helper, new BlockPos(5, 1, 2));
        maid.setYRot(180.0F);
        maid.getMaidInv().setStackInSlot(0, new ItemStack(Items.OAK_STAIRS, 4));
        BlockPos target = helper.absolutePos(new BlockPos(6, 1, 2));
        JsonObject args = args(target, "minecraft:oak_stairs");
        args.addProperty("facing", "east");
        CompletableFuture<MaidBlockActionResult> future = place(maid, args);
        await(helper, future, 80, result -> finish(helper, maid, () -> {
            helper.assertTrue(result.success(), "facing-only placement succeeds: " + result.toJson());
            var state = helper.getBlockState(new BlockPos(6, 1, 2));
            helper.assertTrue(state.is(Blocks.OAK_STAIRS), "oak stairs actually placed");
            helper.assertTrue(state.getValue(StairBlock.FACING) == Direction.EAST,
                    "facing=east controls the placed stairs, got " + state.getValue(StairBlock.FACING));
            helper.assertTrue(result.toJson().getAsJsonObject("orientation").get("requested_facing").getAsString()
                    .equals("east"), "result records the requested facing");
            assertOrientation(helper, result.toJson());
        }), () -> {
            clean(maid);
            helper.fail("facing-argument placement timed out");
        });
    }

    @GameTest(batch = "isolated_orientationLogAxis", template = "four_chests", templateNamespace = "aifun_build_test")
    public static void expectedAxisSelectsSideSupportFace(GameTestHelper helper) {
        EntityMaid maid = maid(helper, new BlockPos(5, 1, 2));
        maid.getMaidInv().setStackInSlot(0, new ItemStack(Items.OAK_LOG, 4));
        BlockPos target = helper.absolutePos(new BlockPos(4, 1, 2));
        // A vertical support would make axis=y; the west wall is the only face that yields axis=x.
        helper.setBlock(new BlockPos(3, 1, 2), Blocks.STONE);
        JsonObject args = args(target, "minecraft:oak_log");
        args.addProperty("expected", "minecraft:oak_log[axis=x]");
        CompletableFuture<MaidBlockActionResult> future = place(maid, args);
        await(helper, future, 80, result -> finish(helper, maid, () -> {
            helper.assertTrue(result.success(), "log placement succeeds: " + result.toJson());
            var state = helper.getBlockState(new BlockPos(4, 1, 2));
            helper.assertTrue(state.is(Blocks.OAK_LOG), "oak log actually placed");
            helper.assertTrue(state.getValue(RotatedPillarBlock.AXIS) == Direction.Axis.X,
                    "axis=x selected the side support face, got " + state.getValue(RotatedPillarBlock.AXIS));
            helper.assertTrue("east".equals(result.toJson().get("face").getAsString()),
                    "chosen clicked face is the support's east face: " + result.toJson());
            assertOrientation(helper, result.toJson());
        }), () -> {
            clean(maid);
            helper.fail("log axis placement timed out");
        });
    }

    @GameTest(batch = "isolated_orientationHalfTop", template = "four_chests", templateNamespace = "aifun_build_test")
    public static void expectedHalfTopSelectsUpperSideClick(GameTestHelper helper) {
        EntityMaid maid = maid(helper, new BlockPos(5, 1, 2));
        maid.getMaidInv().setStackInSlot(0, new ItemStack(Items.OAK_STAIRS, 4));
        BlockPos target = helper.absolutePos(new BlockPos(4, 1, 2));
        // Clicking the floor gives half=bottom; the upper part of the side support face gives
        // half=top, so the planner must choose both that face and that click position.
        helper.setBlock(new BlockPos(3, 1, 2), Blocks.STONE);
        JsonObject args = args(target, "minecraft:oak_stairs");
        args.addProperty("expected", "minecraft:oak_stairs[facing=north,half=top]");
        CompletableFuture<MaidBlockActionResult> future = place(maid, args);
        await(helper, future, 80, result -> finish(helper, maid, () -> {
            helper.assertTrue(result.success(), "top-half stairs placement succeeds: " + result.toJson());
            var state = helper.getBlockState(new BlockPos(4, 1, 2));
            helper.assertTrue(state.is(Blocks.OAK_STAIRS), "oak stairs actually placed");
            helper.assertTrue(state.getValue(StairBlock.HALF) == Half.TOP,
                    "half=top was placed from the upper side click, got " + state.getValue(StairBlock.HALF));
            helper.assertTrue(state.getValue(StairBlock.FACING) == Direction.NORTH,
                    "facing=north together with half=top is honored");
            helper.assertTrue("east".equals(result.toJson().get("face").getAsString()),
                    "chosen clicked face is the side support: " + result.toJson());
            assertOrientation(helper, result.toJson());
        }), () -> {
            clean(maid);
            helper.fail("top-half stairs placement timed out");
        });
    }

    @GameTest(batch = "isolated_interactWithoutExpected", template = "four_chests", templateNamespace = "aifun_build_test")
    public static void interactWithoutExpectedSucceeds(GameTestHelper helper) {
        EntityMaid maid = maid(helper, new BlockPos(5, 1, 2));
        helper.setBlock(new BlockPos(4, 1, 2), Blocks.LEVER);
        BlockPos target = helper.absolutePos(new BlockPos(4, 1, 2));
        JsonObject args = new JsonObject();
        args.addProperty("action", "interact");
        args.addProperty("x", target.getX());
        args.addProperty("y", target.getY());
        args.addProperty("z", target.getZ());
        args.addProperty("timeout_seconds", 10);
        CompletableFuture<MaidBlockActionResult> future = place(maid, args);
        await(helper, future, 80, result -> finish(helper, maid, () -> {
            helper.assertTrue(result.success(), "interact without expected state succeeds: " + result.toJson());
            helper.assertTrue(helper.getBlockState(new BlockPos(4, 1, 2)).getValue(LeverBlock.POWERED),
                    "the lever actually toggled in the world");
            helper.assertTrue(result.toJson().get("expected_block") == null
                            || result.toJson().get("expected_block").getAsString().isBlank(),
                    "interact keeps the expected state blank instead of verifying air: " + result.toJson());
        }), () -> {
            clean(maid);
            helper.fail("interact placement timed out");
        });
    }

    @GameTest(batch = "isolated_wallVariant", template = "four_chests", templateNamespace = "aifun_build_test")
    public static void wallTorchVariantHonorsExpectedFacing(GameTestHelper helper) {
        EntityMaid maid = maid(helper, new BlockPos(5, 1, 2));
        maid.getMaidInv().setStackInSlot(0, new ItemStack(Items.TORCH, 4));
        helper.setBlock(new BlockPos(4, 1, 3), Blocks.STONE); // wall south of the target
        BlockPos target = helper.absolutePos(new BlockPos(4, 1, 2));
        JsonObject args = args(target, "minecraft:torch");
        args.addProperty("expected", "minecraft:wall_torch[facing=north]");
        CompletableFuture<MaidBlockActionResult> future = place(maid, args);
        await(helper, future, 80, result -> finish(helper, maid, () -> {
            helper.assertTrue(result.success(), "wall torch placement succeeds: " + result.toJson());
            var state = helper.getBlockState(new BlockPos(4, 1, 2));
            helper.assertTrue(state.is(Blocks.WALL_TORCH), "the item placed the wall variant, got " + state);
            helper.assertTrue(state.getValue(WallTorchBlock.FACING) == Direction.NORTH,
                    "wall torch faces north, got " + state.getValue(WallTorchBlock.FACING));
            assertOrientation(helper, result.toJson());
        }), () -> {
            clean(maid);
            helper.fail("wall torch placement timed out");
        });
    }

    @GameTest(batch = "isolated_nonBlockItem", template = "four_chests", templateNamespace = "aifun_build_test")
    public static void placeWithNonBlockItemIsRejected(GameTestHelper helper) {
        EntityMaid maid = maid(helper, new BlockPos(5, 1, 2));
        maid.getMaidInv().setStackInSlot(0, new ItemStack(Items.BONE_MEAL, 4));
        BlockPos target = helper.absolutePos(new BlockPos(4, 1, 2));
        CompletableFuture<MaidBlockActionResult> future = place(maid, args(target, "minecraft:bone_meal"));
        await(helper, future, 80, result -> finish(helper, maid, () -> {
            helper.assertTrue(!result.success(), "non-block item placement must fail: " + result.toJson());
            helper.assertTrue("item_is_not_placeable_block".equals(result.error()),
                    "clear non-placeable error, got " + result.error());
            helper.assertTrue(helper.getBlockState(new BlockPos(4, 1, 2)).isAir(), "target untouched");
        }), () -> {
            clean(maid);
            helper.fail("non-block item placement timed out");
        });
    }

    @GameTest(batch = "isolated_invalidExpected", template = "four_chests", templateNamespace = "aifun_build_test")
    public static void invalidExpectedStateIsRejectedBeforeMutation(GameTestHelper helper) {
        EntityMaid maid = maid(helper, new BlockPos(5, 1, 2));
        maid.getMaidInv().setStackInSlot(0, new ItemStack(Items.OAK_STAIRS, 4));
        BlockPos target = helper.absolutePos(new BlockPos(4, 1, 2));
        JsonObject args = args(target, "minecraft:oak_stairs");
        args.addProperty("expected", "minecraft:oak_stair[facing=north]"); // unknown block id
        CompletableFuture<MaidBlockActionResult> future = place(maid, args);
        await(helper, future, 80, result -> finish(helper, maid, () -> {
            helper.assertTrue(!result.success(), "unknown expected block is rejected: " + result.toJson());
            helper.assertTrue(result.error().contains("unknown_block"),
                    "unknown block id is detected, got " + result.error());
            helper.assertTrue(helper.getBlockState(new BlockPos(4, 1, 2)).isAir(), "target untouched");
        }), () -> {
            clean(maid);
            helper.fail("unknown expected block timed out");
        });
    }

    @GameTest(batch = "isolated_invalidProperty", template = "four_chests", templateNamespace = "aifun_build_test")
    public static void invalidExpectedPropertyIsRejectedBeforeMutation(GameTestHelper helper) {
        EntityMaid maid = maid(helper, new BlockPos(5, 1, 2));
        maid.getMaidInv().setStackInSlot(0, new ItemStack(Items.STONE, 4));
        BlockPos target = helper.absolutePos(new BlockPos(4, 1, 2));
        JsonObject args = args(target, "minecraft:stone");
        args.addProperty("expected", "minecraft:stone[facing=north]");
        CompletableFuture<MaidBlockActionResult> future = place(maid, args);
        await(helper, future, 80, result -> finish(helper, maid, () -> {
            helper.assertTrue(!result.success(), "unsupported property is rejected: " + result.toJson());
            helper.assertTrue(result.error().contains("unknown_block_property"),
                    "unknown property is detected, got " + result.error());
            helper.assertTrue(helper.getBlockState(new BlockPos(4, 1, 2)).isAir(), "target untouched");
        }), () -> {
            clean(maid);
            helper.fail("invalid expected property timed out");
        });
    }

    @GameTest(batch = "isolated_slabRedirect", template = "four_chests", templateNamespace = "aifun_build_test")
    public static void existingSlabRedirectIsNotMisusedAsSupport(GameTestHelper helper) {
        EntityMaid maid = maid(helper, new BlockPos(5, 1, 2));
        maid.getMaidInv().setStackInSlot(0, new ItemStack(Items.OAK_SLAB, 4));
        helper.setBlock(new BlockPos(4, 0, 2), Blocks.OAK_SLAB.defaultBlockState()
                .setValue(SlabBlock.TYPE, SlabType.BOTTOM));
        BlockPos target = helper.absolutePos(new BlockPos(4, 1, 2));
        JsonObject args = args(target, "minecraft:oak_slab");
        args.addProperty("expected", "minecraft:oak_slab[type=bottom]");
        CompletableFuture<MaidBlockActionResult> future = place(maid, args);
        await(helper, future, 80, result -> finish(helper, maid, () -> {
            helper.assertTrue(!result.success(), "clicking a matching slab must not be used as support: " + result.toJson());
            var support = helper.getBlockState(new BlockPos(4, 0, 2));
            helper.assertTrue(support.getValue(SlabBlock.TYPE) == SlabType.BOTTOM,
                    "the existing slab was not converted to a double slab, got " + support.getValue(SlabBlock.TYPE));
            helper.assertTrue(helper.getBlockState(new BlockPos(4, 1, 2)).isAir(), "target untouched");
        }), () -> {
            clean(maid);
            helper.fail("slab redirect placement timed out");
        });
    }

    @GameTest(batch = "isolated_facingUnsupported", template = "four_chests", templateNamespace = "aifun_build_test")
    public static void facingOnUnorientableBlockIsRejected(GameTestHelper helper) {
        EntityMaid maid = maid(helper, new BlockPos(5, 1, 2));
        maid.getMaidInv().setStackInSlot(0, new ItemStack(Items.COBBLESTONE, 4));
        BlockPos target = helper.absolutePos(new BlockPos(4, 1, 2));
        JsonObject args = args(target, "minecraft:cobblestone");
        args.addProperty("facing", "north");
        CompletableFuture<MaidBlockActionResult> future = place(maid, args);
        await(helper, future, 80, result -> finish(helper, maid, () -> {
            helper.assertTrue(!result.success(), "facing on a block without facing/axis fails: " + result.toJson());
            helper.assertTrue("facing_not_supported".equals(result.error()),
                    "clear facing error, got " + result.error());
            helper.assertTrue(helper.getBlockState(new BlockPos(4, 1, 2)).isAir(), "target untouched");
        }), () -> {
            clean(maid);
            helper.fail("unsupported facing timed out");
        });
    }
}
