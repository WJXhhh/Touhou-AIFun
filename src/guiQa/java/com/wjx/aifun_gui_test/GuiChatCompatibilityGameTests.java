package com.wjx.aifun_gui_test;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.Role;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.init.InitEntities;
import com.google.gson.*;
import com.wjx.touhou_aifun.chat.ChatFlowManager;
import com.wjx.touhou_aifun.chat.agent.*;
import com.wjx.touhou_aifun.compat.ai.action.GuiTool;
import com.wjx.touhou_aifun.compat.ai.openai.*;
import com.wjx.touhou_aifun.config.TouhouAIFunConfig;
import com.wjx.touhou_aifun.maid.gui.MaidGuiSessionManager;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraftforge.gametest.*;
import java.util.*;

@GameTestHolder("aifun_gui_test")
@PrefixGameTestTemplate(false)
public final class GuiChatCompatibilityGameTests {
    private static final Gson GSON = new Gson();
    private static final class Fixture implements AutoCloseable {
        final boolean enabled = TouhouAIFunConfig.AGENT_RUNTIME.get();
        final EntityMaid maid;
        final LLMCallback chat;
        final GameTestHelper helper;
        final BlockPos local = new BlockPos(1, 1, 3), position;
        Fixture(GameTestHelper helper) {
            this.helper = helper;
            TouhouAIFunConfig.AGENT_RUNTIME.set(false);
            var owner = helper.makeMockPlayer();
            maid = new EntityMaid(InitEntities.MAID.get(), helper.getLevel()) {
                @Override public net.minecraft.world.entity.LivingEntity getOwner() { return owner; }
            };
            var start = helper.absolutePos(new BlockPos(2, 1, 2));
            maid.setPos(start.getX() + .5, start.getY(), start.getZ() + .5);
            maid.setOwnerUUID(owner.getUUID()); maid.setNoAi(true);
            helper.getLevel().addFreshEntity(maid);
            helper.setBlock(local, Blocks.CHEST); position = helper.absolutePos(local);
            chat = new LLMCallback(maid.getAiChatManager(), new ArrayList<>(), true);
        }
        ChestBlockEntity chest() { return (ChestBlockEntity)helper.getBlockEntity(local); }
        JsonObject target(String block) {
            var result = new JsonObject(); result.addProperty("target", block);
            result.addProperty("x", position.getX()); result.addProperty("y", position.getY()); result.addProperty("z", position.getZ());
            result.addProperty("wait_policy", "AUTO"); return result;
        }
        JsonObject call(String id, String tool, JsonObject args) {
            new GuiTool(tool).onCallAsync(id, args, chat, null).join();
            return JsonParser.parseString(chat.getMessages().stream().filter(m -> m.role() == Role.TOOL && id.equals(m.toolCallId()))
                    .findFirst().orElseThrow().message()).getAsJsonObject();
        }
        @Override public void close() {
            MaidGuiSessionManager.cancel(maid.getUUID(), "qa_complete");
            ChatFlowManager.forgetMaid(maid.getUUID()); maid.discard();
            TouhouAIFunConfig.AGENT_RUNTIME.set(enabled);
        }
    }
    @GameTest(template = "empty", templateNamespace = "aifun_gui_test")
    public static void completionSchemaAndGuidanceBelongOnlyToTaskExecutor(GameTestHelper helper) {
        try (var f = new Fixture(helper)) {
            var state = new AgentTaskState("Deposit inventory"); state.status = AgentTaskState.Status.running;
            var task = new TaskCallback(f.maid.getAiChatManager(), new ArrayList<>(), state);
            try {
                var ordinary = ToolContextSelector.snapshot(f.maid, f.chat);
                var background = ToolContextSelector.snapshot(f.maid, task);
                for (String id : List.of("gui_action", "gui_batch")) {
                    var chatEntry = ordinary.planningEntries().stream().filter(e -> e.id().equals(id)).findFirst().orElseThrow();
                    var taskEntry = background.planningEntries().stream().filter(e -> e.id().equals(id)).findFirst().orElseThrow();
                    for (var schema : List.of(GSON.toJsonTree(chatEntry.openAITool()), chatEntry.anthropicTool()))
                        helper.assertTrue(!schema.toString().contains("complete_task") && schema.toString().contains("close_after"), "Ordinary protocol schemas expose cleanup without task completion");
                    for (var schema : List.of(GSON.toJsonTree(taskEntry.openAITool()), taskEntry.anthropicTool()))
                        helper.assertTrue(schema.toString().contains("complete_task"), "Both background protocol schemas retain verified finalization");
                    helper.assertTrue(!chatEntry.summary().contains("complete_task") && !chatEntry.summary().contains("update_task_plan"), "Ordinary directory never instructs executor-only parameters or planning");
                    helper.assertTrue(taskEntry.summary().contains("complete_task") && taskEntry.summary().contains("update_task_plan"), "Executor guidance preserves completion and contract requirements");
                    helper.assertTrue(!GSON.toJson(new GuiTool(id).parameters(com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.ObjectParameter.create(), f.maid)).contains("complete_task"), "Native clients use the conservative ordinary schema");
                }
                var opened = f.call("open", "open_gui", f.target("minecraft:chest"));
                var rejected = new JsonObject(); rejected.add("session_id", opened.get("session_id"));
                rejected.addProperty("action", "quick_move"); rejected.addProperty("slot", 0); rejected.addProperty("complete_task", true);
                f.chest().setItem(0, new ItemStack(Items.DIAMOND, 2));
                helper.assertTrue("task_execution_only".equals(f.call("reject", "gui_action", rejected).get("error").getAsString())
                        && f.chest().getItem(0).getCount() == 2, "Hallucinated or historical completion flag still rejects before mutation");
                helper.succeed();
            } finally { state.status = AgentTaskState.Status.cancelled; task.archiveState(); ToolContextSelector.clearSnapshot(task); }
        }
    }
    @GameTest(template = "empty", templateNamespace = "aifun_gui_test")
    public static void observedOpeningLoadsBatchAndDepositsFiveStacksWithoutSchemaRound(GameTestHelper helper) {
        try (var f = new Fixture(helper)) {
            Item[] items = {Items.OAK_LOG, Items.OAK_WOOD, Items.STRIPPED_OAK_WOOD, Items.STRIPPED_OAK_LOG, Items.OAK_PLANKS};
            for (int i = 0; i < items.length; i++) f.maid.getAvailableBackpackInv().setStackInSlot(i, new ItemStack(items[i], 64));
            f.chest().setItem(0, new ItemStack(Items.STICK));
            ChatFlowManager.requestToolSchema(f.maid.getUUID(), f.chat, "open_gui");
            var catalog = ToolContextSelector.snapshot(f.maid, f.chat);
            helper.assertTrue(ToolContextSelector.selected(f.maid, f.chat).stream().noneMatch(e -> e.id().equals("gui_batch")), "No batch schema before GUI observation");
            helper.assertTrue(f.call("wrong_target", "open_gui", f.target("minecraft:stone")).has("error")
                    && !ChatFlowManager.requestedToolIds(f.maid.getUUID(), f.chat).contains("gui_batch"), "Failed opening cannot load follow-up schemas");
            var opened = f.call("open", "open_gui", f.target("minecraft:chest"));
            helper.assertTrue(!opened.has("error") && ToolContextSelector.selected(f.maid, f.chat).stream().anyMatch(e -> e.id().equals("gui_batch")), "Successful opening makes batch directly callable in the next model request");
            helper.assertTrue(catalog == ToolContextSelector.snapshot(f.maid, f.chat), "Automatic selection reuses frozen definitions and budget");
            var actions = new JsonArray();
            for (var value : opened.getAsJsonArray("slots")) {
                var slot = value.getAsJsonObject();
                if (slot.has("backpack_slot") && slot.get("count").getAsInt() > 0) {
                    var action = new JsonObject(); action.addProperty("action", "quick_move"); action.add("slot", slot.get("slot")); actions.add(action);
                }
            }
            helper.assertTrue(actions.size() == 5, "Five source slots come from the real observation");
            var batch = new JsonObject(); batch.add("session_id", opened.get("session_id")); batch.add("expected_revision", opened.get("revision"));
            batch.add("actions", actions); batch.addProperty("close_after", true);
            var result = f.call("deposit", "gui_batch", batch);
            helper.assertTrue(!result.has("error") && result.get("executed_count").getAsInt() == 5
                    && "closed".equals(result.getAsJsonObject("cleanup").get("status").getAsString()), "One batch deposits and closes without completion-flag retry");
            for (int i = 0; i < items.length; i++) helper.assertTrue(f.chest().getItem(i + 1).is(items[i]) && f.chest().getItem(i + 1).getCount() == 64
                    && f.maid.getAvailableBackpackInv().getStackInSlot(i).isEmpty(), "Every stack is transferred exactly once");
            helper.assertTrue(f.chest().getItem(0).is(Items.STICK) && f.chest().getItem(0).getCount() == 1
                    && MaidGuiSessionManager.session(f.maid.getUUID()) == null && !result.has("completion_requested"), "Existing chest item is preserved and cleanup does not claim background completion");
            helper.succeed();
        }
    }
}
