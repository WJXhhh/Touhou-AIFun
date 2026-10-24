package com.wjx.aifun_gui_test;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.init.InitEntities;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wjx.touhou_aifun.chat.agent.AgentTaskState;
import com.wjx.touhou_aifun.chat.agent.AgentCompletion;
import com.wjx.touhou_aifun.chat.agent.TaskCallback;
import com.wjx.touhou_aifun.chat.agent.build.BuildQueueData;
import com.wjx.touhou_aifun.chat.agent.build.BuildQueueManager;
import com.wjx.touhou_aifun.chat.agent.build.BuildQueueRunner;
import com.wjx.touhou_aifun.chat.agent.build.MaterialFlow;
import com.wjx.touhou_aifun.maid.action.block.MaidBlockActionManager;
import com.wjx.touhou_aifun.maid.gui.MaidGuiSessionManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Real-server build queue design/run/verification. */
@GameTestHolder("aifun_build_test")
@PrefixGameTestTemplate(false)
public final class BuildQueueGameTests {
    private static EntityMaid maid(GameTestHelper helper, BlockPos pos) {
        FourChestNavigationGameTests.prepareFloor(helper);
        EntityMaid maid = helper.spawn(InitEntities.MAID.get(), pos);
        maid.setNoAi(true);
        maid.setInvulnerable(true);
        FourChestNavigationGameTests.prepareNavigationChunks(maid);
        return maid;
    }

    private static void await(GameTestHelper helper, CompletableFuture<JsonObject> future, int remaining,
                              Consumer<JsonObject> completed, Runnable onFailure) {
        if (future.isDone()) {
            try { completed.accept(future.join()); }
            catch (RuntimeException failed) { onFailure.run(); }
            return;
        }
        if (remaining <= 0) { onFailure.run(); return; }
        helper.runAfterDelay(1, () -> await(helper, future, remaining - 1, completed, onFailure));
    }

    private static void clean(EntityMaid maid, TaskCallback callback) {
        if (callback != null) {
            callback.operations.cancel();
            // The world-level archive is shared between QA batches; a never-terminal archive from
            // a test can eventually fill MAX_TASKS. Retire it as cancelled so it is evictable.
            try {
                var level = (net.minecraft.server.level.ServerLevel) maid.level();
                com.wjx.touhou_aifun.chat.agent.AgentArchiveData.get(level)
                        .terminal(maid.getUUID(), callback.task.id, "cancelled", level.getGameTime());
            } catch (RuntimeException ignored) {
            }
        }
        BuildQueueManager.cancelForMaid(maid.getUUID(), "qa_complete");
        BuildQueueData.forget(maid.getUUID());
        MaidGuiSessionManager.cancel(maid.getUUID(), "qa_complete");
        MaidBlockActionManager.cancelForMaid(maid.getUUID(), "qa_complete");
        maid.discard();
    }

    @GameTest(batch = "isolated_buildQueueRun", template = "four_chests", templateNamespace = "aifun_build_test", timeoutTicks = 600)
    public static void designRunPlacesStructure(GameTestHelper helper) {
        EntityMaid maid = maid(helper, new BlockPos(5, 1, 2));
        maid.getMaidInv().setStackInSlot(0, new ItemStack(Items.COBBLESTONE, 8));
        JsonObject design = new JsonObject();
        design.addProperty("action", "design");
        design.addProperty("structure_name", "qa_wall");
        design.addProperty("coordinate_space", "maid_relative");
        design.addProperty("order", "bottom_up");
        var steps = new com.google.gson.JsonArray();
        for (int dx : new int[]{-1, 1}) {
            JsonObject step = new JsonObject();
            step.addProperty("kind", "place");
            step.addProperty("x", dx);
            step.addProperty("y", 0);
            step.addProperty("z", 0);
            step.addProperty("block", "minecraft:cobblestone");
            step.addProperty("item", "minecraft:cobblestone");
            steps.add(step);
        }
        design.add("steps", steps);
        JsonObject designed = BuildQueueManager.design(maid, design);
        helper.assertTrue(designed.has("design_id"), "design saved: " + designed);
        String designId = designed.get("design_id").getAsString();
        AgentTaskState task = new AgentTaskState("build a small wall");
        task.completion = AgentTaskState.validateCompletion(JsonParser.parseString(
                "{\"kind\":\"structure\",\"dimension\":\"minecraft:overworld\",\"structure_id\":\""
                        + designId + "\"}").getAsJsonObject());
        TaskCallback callback;
        try {
            callback = new TaskCallback(maid.getAiChatManager(), new ArrayList<>(), task);
        } catch (RuntimeException failed) {
            helper.fail("task callback unavailable: " + failed);
            return;
        }
        JsonObject run = new JsonObject();
        run.addProperty("action", "run");
        run.addProperty("design_id", designId);
        run.addProperty("max_steps", 8);
        run.addProperty("max_seconds", 60);
        CompletableFuture<JsonObject> future = BuildQueueManager.run(maid, callback, run);
        await(helper, future, 300, result -> {
            helper.assertTrue(result.has("status") && "completed".equals(result.get("status").getAsString()),
                    "build run completes: " + result);
            helper.assertTrue(helper.getBlockState(new BlockPos(4, 1, 2)).is(Blocks.COBBLESTONE), "first block placed");
            helper.assertTrue(helper.getBlockState(new BlockPos(6, 1, 2)).is(Blocks.COBBLESTONE), "second block placed");
            helper.assertTrue(AgentCompletion.verify(maid, task, true), "structure completion verifies the live placed blocks");
            clean(maid, callback);
            helper.succeed();
        }, () -> {
            clean(maid, callback);
            helper.fail("build queue run timed out");
        });
    }

    @GameTest(batch = "isolated_buildQueueOrientation", template = "four_chests", templateNamespace = "aifun_build_test", timeoutTicks = 600)
    public static void designHonorsBlockStateOrientation(GameTestHelper helper) {
        EntityMaid maid = maid(helper, new BlockPos(5, 1, 2));
        // A west-facing body yaw would place both stairs facing east without deterministic orientation.
        maid.setYRot(90.0F);
        maid.setYHeadRot(90.0F);
        maid.getMaidInv().setStackInSlot(0, new ItemStack(Items.OAK_STAIRS, 8));
        JsonObject design = new JsonObject();
        design.addProperty("action", "design");
        design.addProperty("structure_name", "qa_oriented_stairs");
        design.addProperty("coordinate_space", "maid_relative");
        design.addProperty("order", "as_given");
        var steps = new com.google.gson.JsonArray();
        for (int dx : new int[]{-1, 1}) {
            JsonObject step = new JsonObject();
            step.addProperty("kind", "place");
            step.addProperty("x", dx);
            step.addProperty("y", 0);
            step.addProperty("z", 0);
            step.addProperty("block", dx < 0
                    ? "minecraft:oak_stairs[facing=east]"
                    : "minecraft:oak_stairs[facing=west]");
            steps.add(step);
        }
        design.add("steps", steps);
        JsonObject designed = BuildQueueManager.design(maid, design);
        helper.assertTrue(designed.has("design_id"), "oriented design saved: " + designed);
        String designId = designed.get("design_id").getAsString();
        AgentTaskState task = new AgentTaskState("build two oriented stairs");
        task.completion = AgentTaskState.validateCompletion(JsonParser.parseString(
                "{\"kind\":\"structure\",\"dimension\":\"minecraft:overworld\",\"structure_id\":\""
                        + designId + "\"}").getAsJsonObject());
        TaskCallback callback;
        try {
            callback = new TaskCallback(maid.getAiChatManager(), new ArrayList<>(), task);
        } catch (RuntimeException failed) {
            helper.fail("task callback unavailable: " + failed);
            return;
        }
        JsonObject run = new JsonObject();
        run.addProperty("action", "run");
        run.addProperty("design_id", designId);
        run.addProperty("max_steps", 8);
        run.addProperty("max_seconds", 60);
        CompletableFuture<JsonObject> future = BuildQueueManager.run(maid, callback, run);
        await(helper, future, 300, result -> {
            helper.assertTrue(result.has("status") && "completed".equals(result.get("status").getAsString()),
                    "oriented build run completes: " + result);
            var westStep = helper.getBlockState(new BlockPos(4, 1, 2));
            var eastStep = helper.getBlockState(new BlockPos(6, 1, 2));
            helper.assertTrue(westStep.is(Blocks.OAK_STAIRS) && westStep.getValue(StairBlock.FACING) == Direction.EAST,
                    "first stair honors facing=east, got " + southFacing(westStep));
            helper.assertTrue(eastStep.is(Blocks.OAK_STAIRS) && eastStep.getValue(StairBlock.FACING) == Direction.WEST,
                    "second stair honors facing=west, got " + southFacing(eastStep));
            helper.assertTrue(AgentCompletion.verify(maid, task, true),
                    "structure completion verifies the oriented live blocks");
            clean(maid, callback);
            helper.succeed();
        }, () -> {
            clean(maid, callback);
            helper.fail("oriented build queue run timed out");
        });
    }

    private static String southFacing(net.minecraft.world.level.block.state.BlockState state) {
        try {
            return state.getValue(StairBlock.FACING).getName();
        } catch (RuntimeException unavailable) {
            return state.toString();
        }
    }

    @GameTest(batch = "isolated_buildQueueJudge", template = "four_chests", templateNamespace = "aifun_build_test", timeoutTicks = 300)
    public static void judgeSatisfiedCompletesMismatchedStep(GameTestHelper helper) {
        EntityMaid maid = maid(helper, new BlockPos(5, 1, 2));
        maid.getMaidInv().setStackInSlot(0, new ItemStack(Items.COBBLESTONE, 2));
        // Occupy the target with dirt so the physical place action deterministically fails; the
        // fake native judge must be able to declare the observed state satisfactory and complete.
        helper.setBlock(new BlockPos(4, 1, 2), Blocks.DIRT);
        JsonObject design = new JsonObject();
        design.addProperty("action", "design");
        design.addProperty("structure_name", "qa_judge");
        design.addProperty("coordinate_space", "maid_relative");
        var steps = new com.google.gson.JsonArray();
        JsonObject step = new JsonObject();
        step.addProperty("kind", "place");
        step.addProperty("x", -1);
        step.addProperty("y", 0);
        step.addProperty("z", 0);
        step.addProperty("block", "minecraft:cobblestone");
        step.addProperty("item", "minecraft:cobblestone");
        steps.add(step);
        design.add("steps", steps);
        JsonObject designed = BuildQueueManager.design(maid, design);
        String designId = designed.get("design_id").getAsString();
        AgentTaskState task = new AgentTaskState("judge a blocked step");
        task.completion = AgentTaskState.validateCompletion(JsonParser.parseString(
                "{\"kind\":\"structure\",\"dimension\":\"minecraft:overworld\",\"structure_id\":\""
                        + designId + "\"}").getAsJsonObject());
        TaskCallback callback = new TaskCallback(maid.getAiChatManager(), new ArrayList<>(), task);
        var previousJudge = BuildQueueRunner.JUDGE;
        BuildQueueRunner.JUDGE = (maidArg, callbackArg, designArg, stepArg, actual, attempts) -> {
            JsonObject verdict = new JsonObject();
            verdict.addProperty("confident", true);
            verdict.addProperty("choice", "satisfied");
            verdict.addProperty("reason", "fake_judge");
            return CompletableFuture.completedFuture(verdict);
        };
        JsonObject run = new JsonObject();
        run.addProperty("action", "run");
        run.addProperty("design_id", designId);
        run.addProperty("max_steps", 4);
        run.addProperty("max_seconds", 30);
        CompletableFuture<JsonObject> future = BuildQueueManager.run(maid, callback, run);
        JsonObject[] seen = {null};
        await(helper, future, 120, result -> {
            seen[0] = result;
            try {
                helper.assertTrue(result.has("status") && "completed".equals(result.get("status").getAsString()),
                        "judge-approved mismatched step completes: " + result);
                helper.assertTrue(helper.getBlockState(new BlockPos(4, 1, 2)).is(Blocks.DIRT),
                        "the judge only judged state; it did not silently replace the block");
                clean(maid, callback);
                helper.succeed();
            } finally {
                BuildQueueRunner.JUDGE = previousJudge;
            }
        }, () -> {
            try {
                clean(maid, callback);
                helper.fail("judged build queue run did not complete: " + seen[0]);
            } finally {
                BuildQueueRunner.JUDGE = previousJudge;
            }
        });
    }

    @GameTest(batch = "isolated_buildMaterialGate", template = "four_chests", templateNamespace = "aifun_build_test", timeoutTicks = 120)
    public static void materialPolicyBlocksUnauthorizedSourcing(GameTestHelper helper) {
        EntityMaid maid = maid(helper, new BlockPos(5, 1, 2));
        JsonObject design = new JsonObject();
        design.addProperty("action", "design");
        design.addProperty("structure_name", "qa_material");
        design.addProperty("coordinate_space", "maid_relative");
        var steps = new com.google.gson.JsonArray();
        JsonObject step = new JsonObject();
        step.addProperty("kind", "place");
        step.addProperty("x", -1);
        step.addProperty("y", 0);
        step.addProperty("z", 0);
        step.addProperty("block", "minecraft:cobblestone");
        step.addProperty("item", "minecraft:cobblestone");
        steps.add(step);
        design.add("steps", steps);
        JsonObject designed = BuildQueueManager.design(maid, design);
        String designId = designed.get("design_id").getAsString();
        AgentTaskState task = new AgentTaskState("material gate");
        task.completion = AgentTaskState.validateCompletion(JsonParser.parseString(
                "{\"kind\":\"structure\",\"dimension\":\"minecraft:overworld\",\"structure_id\":\""
                        + designId + "\"}").getAsJsonObject());
        TaskCallback callback = new TaskCallback(maid.getAiChatManager(), new ArrayList<>(), task);
        JsonObject request = new JsonObject();
        request.addProperty("design_id", designId);
        try {
            task.setMaterialPolicy(AgentTaskState.MATERIAL_DENIED);
            JsonObject denied = MaterialFlow.preflight(maid, callback, request).join();
            helper.assertTrue("materials_denied".equals(denied.get("error").getAsString()),
                    "denied policy never authorizes sourcing: " + denied);
            helper.assertTrue(!task.hasOwnerQuestion() && !task.awaitingOwnerInput,
                    "denied policy does not ask the owner again");
            task.setMaterialPolicy(AgentTaskState.MATERIAL_SELF_GATHER);
            JsonObject missing = MaterialFlow.preflight(maid, callback, request).join();
            helper.assertTrue("missing_items".equals(missing.get("error").getAsString()),
                    "self_gather returns the bounded missing list to the executor: " + missing);
            helper.assertTrue(task.materialRounds == 1, "self-sourcing round was counted");
            clean(maid, callback);
            helper.succeed();
        } catch (RuntimeException failed) {
            clean(maid, callback);
            helper.fail("material gate failed: " + failed);
        }
    }
}
