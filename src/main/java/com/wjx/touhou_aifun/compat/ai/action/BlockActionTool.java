package com.wjx.touhou_aifun.compat.ai.action;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ITool;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.BoolParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.IntegerParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.ObjectParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.Parameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.StringParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMClient;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.JsonOps;
import com.wjx.touhou_aifun.chat.ChatFlowManager;
import com.wjx.touhou_aifun.chat.agent.AgentExecution;
import com.wjx.touhou_aifun.chat.agent.AgentRuntime;
import com.wjx.touhou_aifun.chat.agent.TaskCallback;
import com.wjx.touhou_aifun.maid.action.block.MaidBlockActionManager;
import com.wjx.touhou_aifun.maid.action.block.MaidBlockActionRequest;
import com.wjx.touhou_aifun.maid.action.block.MaidBlockActionResult;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.util.concurrent.CompletableFuture;

/**
 * Executor-only simulated block interaction tool. It deliberately has no foreground variant:
 * the main chat model reads build status but never mutates the world itself.
 */
public final class BlockActionTool implements ITool<JsonObject> {
    public static final String TOOL_ID = "block_action";

    @Override
    public String id() {
        return TOOL_ID;
    }

    @Override
    public boolean trigger(EntityMaid maid, com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.request.ChatCompletion request) {
        return AgentRuntime.enabled(maid);
    }

    @Override
    public String summary(EntityMaid maid) {
        return "Perform ONE server-simulated block action: interact (right-click a block, optionally with an item), "
                + "place (place one block item on a support face; TLM edible-block placement such as cake on a snack "
                + "cabinet is handled by the provider hook), or mine (walk to, break and collect one block with the "
                + "selected/best tool). Placement orientation is deterministic and does not follow the maid's wandering "
                + "brain rotation: list the desired state in expected (e.g. minecraft:oak_stairs[facing=north]) or pass "
                + "facing, and the tool solves the placement rotation/support face against the block's own placement logic. "
                + "Always re-reads the actual block state after the action. "
                + "Use build_queue for multi-block structures; use open_gui for container menus. "
                + "Never assume a failed action did nothing; actual_block/expected_block/orientation explain mismatches.";
    }

    @Override
    public Parameter parameters(ObjectParameter root, EntityMaid maid) {
        root.addProperties("action", StringParameter.create()
                .addEnumValues("interact", "place", "mine")
                .setDescription("interact = right-click; place = place the item on the chosen support side; mine = break and collect the block."), true);
        for (String axis : new String[]{"x", "y", "z"}) {
            root.addProperties(axis, IntegerParameter.create()
                    .setDescription("Integer target coordinate. Provide all three."), true);
        }
        root.addProperties("coordinate_space", StringParameter.create()
                .addEnumValues("world", "maid_relative")
                .setDescription("world (default) or maid_relative offsets from the maid's CURRENT block position.")
                .setDefaultValue("world"), false);
        root.addProperties("face", StringParameter.create()
                .addEnumValues("up", "down", "north", "south", "east", "west")
                .setDescription("Preferred support face for place; preferred hit face for interact. The tool may try another reachable face for placement."), false);
        root.addProperties("facing", StringParameter.create()
                .addEnumValues("north", "south", "east", "west", "up", "down")
                .setDescription("place: desired facing of the placed block (e.g. stairs facing north, piston facing up). "
                        + "It is merged into the expected state as the block's facing property, or its axis for axis blocks "
                        + "(up/down=>y). Blocks with neither property reject it with facing_not_supported. "
                        + "Prefer stating the full state in expected."), false);
        root.addProperties("item", StringParameter.create()
                .setDescription("Exact item id to hold/place/use. For place it defaults to a matching BlockItem already held or in the backpack. "
                        + "For wall variants (wall sign/torch/skull/coral) pass the standing item, e.g. minecraft:oak_sign or minecraft:torch."), false);
        root.addProperties("tool", StringParameter.create()
                .setDescription("Exact tool item id for mine. Omit to choose the best available tool."), false);
        root.addProperties("expected", StringParameter.create()
                .setDescription("Expected block state after the action, e.g. minecraft:oak_stairs[facing=north,half=bottom] or minecraft:air. "
                        + "For place, listed orientation properties (facing/axis/rotation/half) are placed deterministically and verified against "
                        + "the live block; unknown blocks/properties/values are rejected before any mutation. "
                        + "Omit for interact when the resulting state is unpredictable (lever/button/trapdoor): it is then a one-shot action. "
                        + "Place defaults to the placed BlockItem's block."), false);
        root.addProperties("strict", BoolParameter.create()
                .setDescription("Reserved compatibility flag. Listed expected properties are always required; unlisted properties are never compared in either mode."), false);
        root.addProperties("timeout_seconds", IntegerParameter.create()
                .setMinimum(1).setMaximum(120).setDefaultValue("20")
                .setDescription("Maximum walk+action time."), false);
        return root;
    }

    @Override
    public Codec<JsonObject> codec() {
        return Codec.PASSTHROUGH.comapFlatMap(value -> {
            var json = value.convert(JsonOps.INSTANCE).getValue();
            return json.isJsonObject() ? DataResult.success(json.getAsJsonObject())
                    : DataResult.error(() -> "object_required");
        }, value -> new Dynamic<>(JsonOps.INSTANCE, value));
    }

    @Override
    public LLMCallback onCall(String toolCallId, JsonObject request, LLMCallback callback) {
        return callback.addToolResult("{\"success\":false,\"status\":\"async_dispatch_required\","
                + "\"detail\":\"This physical action must use the asynchronous tool dispatcher.\"}", toolCallId);
    }

    @Override
    public CompletableFuture<LLMCallback> onCallAsync(String toolCallId, JsonObject request,
                                                       LLMCallback callback, LLMClient client) {
        if (callback instanceof LLMCallback && AgentExecution.foreground(callback)) {
            return CompletableFuture.completedFuture(callback.addToolResult(
                    "{\"success\":false,\"status\":\"failed\",\"error\":\"foreground_action_boundary\"}", toolCallId));
        }
        CompletableFuture<LLMCallback> result = new CompletableFuture<>();
        Runnable start = () -> {
            MaidBlockActionRequest parsed;
            try {
                parsed = MaidBlockActionRequest.parse(request, callback.getMaid());
            } catch (RuntimeException invalid) {
                String message = invalid.getMessage() == null ? "invalid_arguments" : invalid.getMessage();
                result.complete(callback.addToolResult("{\"success\":false,\"status\":\"failed\",\"error\":\""
                        + escape(message) + "\"}", toolCallId));
                return;
            }
            if (ChatFlowManager.isSuperseded(callback.getMaid().getUUID(), callback)) {
                result.complete(callback);
                return;
            }
            CompletableFuture<MaidBlockActionResult> action = MaidBlockActionManager.start(
                    callback.getMaid(), callback, parsed);
            ChatFlowManager.setInFlight(callback.getMaid().getUUID(), callback, action);
            action.whenComplete((outcome, throwable) -> {
                if (ChatFlowManager.isSuperseded(callback.getMaid().getUUID(), callback)) {
                    result.complete(callback);
                } else if (throwable != null || outcome == null) {
                    result.complete(callback.addToolResult(
                            "{\"success\":false,\"status\":\"failed\",\"error\":\"cancelled\"}", toolCallId));
                } else {
                    if (outcome.success() && callback instanceof TaskCallback task) {
                        JsonObject fact = new JsonObject();
                        fact.addProperty("kind", "block_action");
                        fact.addProperty("action", outcome.action() == null ? "" : outcome.action().name().toLowerCase(java.util.Locale.ROOT));
                        fact.addProperty("success", true);
                        fact.addProperty("dimension", callback.getMaid().level().dimension().location().toString());
                        if (outcome.position() != null) {
                            JsonObject position = new JsonObject();
                            position.addProperty("x", outcome.position().getX());
                            position.addProperty("y", outcome.position().getY());
                            position.addProperty("z", outcome.position().getZ());
                            fact.add("position", position);
                        }
                        task.recordMutation(fact);
                    }
                    result.complete(callback.addToolResult(outcome.toJson().toString(), toolCallId));
                }
            });
        };
        if (callback.isOnServerThread()) start.run(); else callback.runOnServerThread(start);
        return result;
    }

    @Override
    public Component invocationSummaryComponent(JsonObject request) {
        return Component.translatable("tool.touhou_aifun.block_action").withStyle(ChatFormatting.GRAY);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
