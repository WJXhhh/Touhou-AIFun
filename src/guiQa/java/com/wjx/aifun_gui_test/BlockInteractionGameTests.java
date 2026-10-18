package com.wjx.aifun_gui_test;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.init.InitEntities;
import com.google.gson.JsonObject;
import com.wjx.touhou_aifun.config.TouhouAIFunConfig;
import com.wjx.touhou_aifun.maid.action.MaidBlockInteraction;
import com.wjx.touhou_aifun.maid.action.MaidEatFoodActionManager;
import com.wjx.touhou_aifun.maid.gui.MaidGuiSessionManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CakeBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;

@GameTestHolder("aifun_gui_test")
@PrefixGameTestTemplate(false)
public final class BlockInteractionGameTests {
    private static EntityMaid maid(GameTestHelper helper, BlockPos pos, boolean moving) {
        FourChestNavigationGameTests.prepareFloor(helper);
        EntityMaid maid = helper.spawn(InitEntities.MAID.get(), pos);
        maid.setNoAi(!moving);
        maid.setInvulnerable(true);
        return maid;
    }

    private static java.util.concurrent.CompletableFuture<JsonObject> open(EntityMaid maid, BlockPos target) {
        var callback = new LLMCallback(maid.getAiChatManager(), new ArrayList<>(), true);
        JsonObject request = new JsonObject();
        request.addProperty("target", "minecraft:chest");
        request.addProperty("x", target.getX()); request.addProperty("y", target.getY()); request.addProperty("z", target.getZ());
        return MaidGuiSessionManager.call(callback, "open_gui", "reach_test", request);
    }

    private static void clean(EntityMaid maid) {
        MaidGuiSessionManager.cancel(maid.getUUID(), "qa_complete");
        MaidEatFoodActionManager.cancelForMaid(maid.getUUID(), "qa_complete");
        maid.discard();
    }

    private static void cake(GameTestHelper helper, BlockPos pos) {
        helper.setBlock(pos.below(), net.minecraftforge.registries.ForgeRegistries.BLOCKS.getValue(
                new net.minecraft.resources.ResourceLocation("touhou_little_maid", "snack_cabinet")));
        helper.setBlock(pos, Blocks.CAKE);
    }

    @GameTest(batch="blockInteraction", template="four_chests", templateNamespace="aifun_gui_test")
    public static void raisedChestUsesVisibleSurfaceAndRealSide(GameTestHelper helper) {
        EntityMaid maid = maid(helper, new BlockPos(5, 1, 2), false);
        net.minecraft.world.phys.BlockHitResult[] observed = {null};
        java.util.function.Consumer<net.minecraftforge.event.entity.player.PlayerInteractEvent.RightClickBlock> listener = event -> {
            if (event.getEntity() instanceof com.wjx.touhou_aifun.maid.gui.MaidGuiActor actor && actor.maid() == maid)
                observed[0] = event.getHitVec();
        };
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(net.minecraftforge.eventbus.api.EventPriority.NORMAL,
                false, net.minecraftforge.event.entity.player.PlayerInteractEvent.RightClickBlock.class, listener);
        try {
            BlockPos target = helper.absolutePos(new BlockPos(2, 2, 2)); helper.setBlock(new BlockPos(2, 2, 2), Blocks.CHEST);
            var hit = MaidBlockInteraction.hit(maid, target);
            helper.assertTrue(maid.distanceToSqr(Vec3.atCenterOf(target)) > 2.25 * 2.25, "Old feet/centre rule rejected this chest");
            helper.assertTrue(hit != null && hit.getDirection() == Direction.EAST, "Real east face is visible within default reach");
            helper.assertTrue(open(maid, target).join().get("status").getAsString().equals("opened"), "Raised chest actually opens");
            helper.assertTrue(observed[0] != null && observed[0].getDirection() == Direction.EAST
                    && observed[0].getLocation().distanceToSqr(hit.getLocation()) < 1.0e-8,
                    "Forge receives the real ray position and face, not a synthetic upward centre hit");
            helper.succeed();
        } finally { net.minecraftforge.common.MinecraftForge.EVENT_BUS.unregister(listener); clean(maid); }
    }

    @GameTest(batch="blockInteraction", template="four_chests", templateNamespace="aifun_gui_test")
    public static void highChestNoLongerMeasuresFromFeet(GameTestHelper helper) {
        EntityMaid maid = maid(helper, new BlockPos(4, 1, 2), false);
        try {
            BlockPos target = helper.absolutePos(new BlockPos(2, 3, 2)); helper.setBlock(new BlockPos(2, 3, 2), Blocks.CHEST);
            helper.assertTrue(MaidBlockInteraction.hit(maid, target) != null, "High chest surface is within eye reach");
            helper.assertTrue(open(maid, target).join().get("status").getAsString().equals("opened"), "High chest opens without walking into its column");
            helper.succeed();
        } finally { clean(maid); }
    }

    @GameTest(batch="blockReachConfig", template="four_chests", templateNamespace="aifun_gui_test")
    public static void configuredReachChangesSurfaceLimit(GameTestHelper helper) {
        EntityMaid maid = maid(helper, new BlockPos(5, 1, 2), false);
        double previous = TouhouAIFunConfig.BLOCK_INTERACTION_REACH.get();
        try {
            BlockPos target = helper.absolutePos(new BlockPos(1, 1, 2)); helper.setBlock(new BlockPos(1, 1, 2), Blocks.CHEST);
            TouhouAIFunConfig.BLOCK_INTERACTION_REACH.set(3.0);
            helper.assertTrue(MaidBlockInteraction.hit(maid, target) == null, "Default rejects distant surface");
            TouhouAIFunConfig.BLOCK_INTERACTION_REACH.set(4.5);
            helper.assertTrue(MaidBlockInteraction.hit(maid, target) != null, "Larger configured reach accepts surface");
            TouhouAIFunConfig.BLOCK_INTERACTION_REACH.set(1.0);
            helper.assertTrue(MaidBlockInteraction.hit(maid, target) == null, "Smaller configured reach rejects surface");
            helper.succeed();
        } finally { TouhouAIFunConfig.BLOCK_INTERACTION_REACH.set(previous); clean(maid); }
    }

    @GameTest(batch="blockInteraction", template="four_chests", templateNamespace="aifun_gui_test")
    public static void visibleTopWorksWhenCentreRayIsBlocked(GameTestHelper helper) {
        EntityMaid maid = maid(helper, new BlockPos(4, 1, 2), false);
        try {
            BlockPos target = helper.absolutePos(new BlockPos(1, 1, 2)); helper.setBlock(new BlockPos(1, 1, 2), Blocks.CHEST);
            helper.setBlock(new BlockPos(3, 1, 2), Blocks.STONE);
            var centre = maid.level().clip(new ClipContext(maid.getEyePosition(), Vec3.atCenterOf(target), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, maid));
            helper.assertTrue(centre.getType() == HitResult.Type.BLOCK && !centre.getBlockPos().equals(target), "Centre-only ray is obstructed");
            helper.assertTrue(MaidBlockInteraction.hit(maid, target) != null, "Visible top remains clickable over the low obstacle");
            helper.assertTrue(open(maid, target).join().get("status").getAsString().equals("opened"), "Partially visible chest opens");
            helper.succeed();
        } finally { clean(maid); }
    }

    @GameTest(batch="blockInteraction", template="four_chests", templateNamespace="aifun_gui_test")
    public static void enclosedChestCannotOpenThroughStoneOrGlass(GameTestHelper helper) {
        EntityMaid maid = maid(helper, new BlockPos(4, 1, 2), false);
        try {
            BlockPos target = helper.absolutePos(new BlockPos(2, 1, 2)); helper.setBlock(new BlockPos(2, 1, 2), Blocks.CHEST);
            for (var wall : BlockPos.betweenClosed(new BlockPos(1,1,1), new BlockPos(3,4,3)))
                if (!wall.equals(new BlockPos(2,1,2))) helper.setBlock(wall, Blocks.STONE);
            helper.assertTrue(MaidBlockInteraction.hit(maid, target) == null, "Stone wall blocks every clickable surface");
            for (var wall : BlockPos.betweenClosed(new BlockPos(1,1,1), new BlockPos(3,4,3)))
                if (!wall.equals(new BlockPos(2,1,2))) helper.setBlock(wall, Blocks.GLASS);
            helper.assertTrue(MaidBlockInteraction.hit(maid, target) == null, "Transparent glass also blocks clicking");
            var result = open(maid, target).join();
            helper.assertTrue(result.has("error") && result.get("error").getAsString().equals("unreachable"), "Enclosed chest has no valid approach");
            helper.assertTrue(MaidGuiSessionManager.session(maid.getUUID()) == null, "Failed opening leaves no menu/session");
            helper.succeed();
        } finally { clean(maid); }
    }

    @GameTest(batch="blockInteraction", template="four_chests", templateNamespace="aifun_gui_test", timeoutTicks=180)
    public static void navigationWalksAroundWallBeforeOpening(GameTestHelper helper) {
        EntityMaid maid = maid(helper, new BlockPos(4, 1, 2), true);
        FourChestNavigationGameTests.prepareNavigationChunks(maid);
        BlockPos target = helper.absolutePos(new BlockPos(2, 1, 2)); helper.setBlock(new BlockPos(2, 1, 2), Blocks.CHEST);
        for (var wall : BlockPos.betweenClosed(new BlockPos(3,1,0), new BlockPos(3,4,3))) helper.setBlock(wall, Blocks.STONE);
        helper.assertTrue(MaidBlockInteraction.hit(maid, target) == null, "Nearby chest starts behind wall");
        var future = open(maid, target);
        helper.assertTrue(!future.isDone(), "No immediate through-wall opening");
        boolean[] visibleAtOpening = {false};
        future.thenAccept(result -> visibleAtOpening[0] = MaidBlockInteraction.hit(maid, target) != null);
        class Poll { void run() {
            try {
                if (!future.isDone()) { helper.assertTrue(helper.getTick() < 160, "Maid must find a visible approach around wall"); helper.runAfterDelay(1, this::run); return; }
                var result = future.join();
                helper.assertTrue(!result.has("error"), result.toString());
                helper.assertTrue(visibleAtOpening[0], "Actual opening obeys planned visibility and reach");
                clean(maid); helper.succeed();
            } catch (Throwable failure) { clean(maid); helper.fail(failure.toString()); }
        } }
        helper.runAfterDelay(1, new Poll()::run);
    }

    @GameTest(batch="blockInteraction", template="four_chests", templateNamespace="aifun_gui_test")
    public static void slabStandingHeightMatchesActualEyes(GameTestHelper helper) {
        EntityMaid maid = maid(helper, new BlockPos(4, 2, 2), false);
        try {
            helper.setBlock(new BlockPos(4,1,2), Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM));
            BlockPos target = helper.absolutePos(new BlockPos(2,2,2)); helper.setBlock(new BlockPos(2,2,2), Blocks.CHEST);
            BlockPos standing = helper.absolutePos(new BlockPos(4,2,2));
            maid.setPos(standing.getX()+.5, standing.getY()-.5, standing.getZ()+.5);
            helper.assertTrue(MaidBlockInteraction.canInteractFrom(maid, target, standing) && MaidBlockInteraction.hit(maid, target) != null,
                    "Planner and execution agree on half-slab floor height");
            helper.succeed();
        } finally { clean(maid); }
    }

    @GameTest(batch="blockInteraction", template="four_chests", templateNamespace="aifun_gui_test")
    public static void cakeBehindWallIsNotConsumed(GameTestHelper helper) {
        EntityMaid maid = maid(helper, new BlockPos(4,1,2), false);
        try {
            BlockPos cake = new BlockPos(2,1,2); cake(helper, cake);
            for (var wall : BlockPos.betweenClosed(new BlockPos(1,1,1),new BlockPos(3,4,3)))
                if (!wall.equals(cake)) helper.setBlock(wall, Blocks.STONE);
            helper.assertTrue(MaidBlockInteraction.hit(maid, helper.absolutePos(cake)) == null, "Cake is not visible through wall");
            var result = MaidEatFoodActionManager.start(maid, "minecraft:cake", 8, false, new Object()).join();
            helper.assertTrue(!result.success() && result.toJson().contains("unreachable"), "Valid edible target is unreachable behind the wall");
            helper.assertTrue(helper.getBlockState(cake).is(Blocks.CAKE), "Actual cake remains intact");
            helper.succeed();
        } finally { clean(maid); }
    }

    @GameTest(batch="blockInteraction", template="four_chests", templateNamespace="aifun_gui_test", timeoutTicks=80)
    public static void visibleCakeUsesSharedReachAndActuallyConsumes(GameTestHelper helper) {
        EntityMaid maid = maid(helper, new BlockPos(5,1,2), false);
        BlockPos cake = new BlockPos(2,1,2); cake(helper, cake);
        helper.assertTrue(MaidBlockInteraction.hit(maid, helper.absolutePos(cake)) != null, "Cake surface is reachable beyond old limit");
        var callback = new LLMCallback(maid.getAiChatManager(), new ArrayList<>(), true);
        var future = MaidEatFoodActionManager.start(maid, "minecraft:cake", 8, false, callback);
        helper.runAfterDelay(5, () -> {
            try {
                helper.assertTrue(future.isDone() && future.join().success(), "Visible edible action completes");
                helper.assertTrue(helper.getBlockState(cake).getValue(CakeBlock.BITES) == 1, "Exactly one serving was consumed in the world");
                helper.succeed();
            } finally { clean(maid); }
        });
    }
}
