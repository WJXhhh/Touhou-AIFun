package com.wjx.touhou_aifun.compat.ai.action;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ITool;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.ArrayParameter;
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
import com.wjx.touhou_aifun.chat.agent.build.BuildQueueManager;
import com.wjx.touhou_aifun.chat.agent.build.MaterialFlow;
import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Executor-only entry point for the multi-block build queue. It has no foreground mutation variant:
 * the foreground conversation reads build progress through runtime status, not through this tool.
 */
public final class BuildQueueTool implements ITool<JsonObject> {
    public static final String TOOL_ID = "build_queue";

    @Override
    public String id() {
        return TOOL_ID;
    }

    @Override
    public boolean trigger(EntityMaid maid,
                           com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.request.ChatCompletion request) {
        return AgentRuntime.enabled(maid);
    }

    @Override
    public String summary(EntityMaid maid) {
        return "Plan and execute multi-block structures with real physical actions. "
                + "action=design saves/extends a design (structure_name, dimension, optional origin and coordinate_space=world|maid_relative, "
                + "steps[] with kind=place|mine|interact, coordinates relative to origin, block/item/tool/face/strict/required/note; "
                + "order=bottom_up|as_given|layers) and returns design_id plus required/missing items. "
                + "For place, write the complete block state in block (e.g. minecraft:oak_stairs[facing=north,half=bottom], "
                + "minecraft:chest[facing=east], minecraft:oak_log[axis=y]); its orientation properties are placed deterministically "
                + "and verified regardless of the maid's body rotation. "
                + "action=status/verify/request_materials are read-only. reorder/skip/reset/clear/delete_design manage an idle design; "
                + "pause/resume control the queue. action=run executes the active (or design_id) design one validated block action at a time. "
                + "run requires the task completion contract to contain kind=structure with this structure_id; it pauses on missing materials "
                + "and never edits blocks directly. Register the structure completion BEFORE calling run.";
    }

    @Override
    public Parameter parameters(ObjectParameter root, EntityMaid maid) {
        root.addProperties("action", StringParameter.create()
                .addEnumValues("design", "status", "verify", "request_materials", "reorder", "skip", "reset",
                        "pause", "resume", "run", "clear", "delete_design")
                .setDescription("design = save a new design or append steps; status = read progress; verify = live-world check; "
                        + "request_materials = list missing items; run = execute the stored design."), true);
        root.addProperties("design_id", StringParameter.create().setMaxLength(128)
                .setDescription("Saved design id from action=design. Omit to use the active design."), false);
        root.addProperties("structure_name", StringParameter.create().setMaxLength(128)
                .setDescription("design: human-readable structure name."), false);
        root.addProperties("dimension", StringParameter.create().setMaxLength(128)
                .setDescription("design: target dimension id; defaults to the current dimension."), false);
        root.addProperties("coordinate_space", StringParameter.create()
                .addEnumValues("world", "maid_relative").setDefaultValue("world")
                .setDescription("design: world = origin is absolute; maid_relative = origin is an offset from the maid's current block position. "
                        + "Step x/y/z are always relative to origin."), false);
        ObjectParameter origin = ObjectParameter.create();
        for (String axis : new String[]{"x", "y", "z"}) {
            origin.addProperties(axis, IntegerParameter.create()
                    .setDescription("Origin coordinate; all three required when origin is supplied."), false);
        }
        root.addProperties("origin", origin, false);
        ObjectParameter step = ObjectParameter.create();
        step.addProperties("kind", StringParameter.create().addEnumValues("place", "mine", "interact")
                .setDescription("place places one block item; mine breaks the block into the maid inventory; interact right-clicks."), true);
        for (String axis : new String[]{"x", "y", "z"}) {
            step.addProperties(axis, IntegerParameter.create()
                    .setDescription("Offset from the design origin. Provide all three."), true);
        }
        step.addProperties("block", StringParameter.create().setMaxLength(256)
                .setDescription("Expected block state, e.g. minecraft:oak_stairs[facing=north,half=bottom] or minecraft:oak_log[axis=y]. "
                        + "Required for place; empty for mine means air. Unknown blocks/properties/values are rejected when the design is saved. "
                        + "Listed facing/axis/rotation/half properties are honored deterministically by the placement planner "
                        + "(it may pick a support face/click position that produces that state)."), false);
        step.addProperties("item", StringParameter.create().setMaxLength(128)
                .setDescription("Exact item id to use; for place it defaults to the expected block's BlockItem. "
                        + "Wall variants (wall sign/torch/skull/coral) have no item of their own: pass the standing item, "
                        + "e.g. item=minecraft:oak_sign with block=minecraft:oak_wall_sign[facing=north]."), false);
        step.addProperties("tool", StringParameter.create().setMaxLength(128)
                .setDescription("Exact tool item id for mine; omit to use the best available tool."), false);
        step.addProperties("face", StringParameter.create()
                .addEnumValues("up", "down", "north", "south", "east", "west")
                .setDescription("Preferred support/hit face. The planner may use another reachable face when the expected "
                        + "state needs it (e.g. wall-mounted blocks, log axis or half=top)."), false);
        step.addProperties("strict", BoolParameter.create()
                .setDescription("Reserved compatibility flag. Listed expected properties are always required; unlisted properties are never compared."), false);
        step.addProperties("required", BoolParameter.create()
                .setDescription("Default true. Optional steps do not fail the structure contract."), false);
        step.addProperties("note", StringParameter.create().setMaxLength(256)
                .setDescription("Optional human note for this step."), false);
        root.addProperties("steps", ArrayParameter.create().setItems(step).setRange(1, 256)
                .setDescription("design: the blocks to change. Accept at most 256 steps per call."), false);
        root.addProperties("append", BoolParameter.create()
                .setDescription("design: append these steps to design_id instead of creating a new design."), false);
        root.addProperties("order", StringParameter.create()
                .addEnumValues("bottom_up", "as_given", "layers").setDefaultValue("bottom_up")
                .setDescription("bottom_up = lowest y first then nearest to origin; layers = y ascending; as_given = keep order."), false);
        root.addProperties("reason", StringParameter.create().setMaxLength(128)
                .setDescription("pause: optional reason recorded for the owner."), false);
        root.addProperties("step", IntegerParameter.create().setMinimum(0)
                .setDescription("verify/skip/reset: step index from status."), false);
        root.addProperties("offset", IntegerParameter.create().setMinimum(0)
                .setDescription("status: step page offset."), false);
        root.addProperties("limit", IntegerParameter.create().setMinimum(1).setMaximum(64)
                .setDescription("status: steps per page."), false);
        root.addProperties("include_done", BoolParameter.create()
                .setDescription("status: include completed steps."), false);
        root.addProperties("full_scan", BoolParameter.create()
                .setDescription("verify: include neighbor/block-entity diagnostics."), false);
        root.addProperties("failed_only", BoolParameter.create()
                .setDescription("reset: reset failed/blocked steps only."), false);
        root.addProperties("all_failed", BoolParameter.create()
                .setDescription("skip: mark every failed/blocked step skipped."), false);
        root.addProperties("max_steps", IntegerParameter.create().setMinimum(1).setMaximum(256).setDefaultValue("64")
                .setDescription("run: maximum physical block actions for this call."), false);
        root.addProperties("max_seconds", IntegerParameter.create().setMinimum(1).setMaximum(600).setDefaultValue("600")
                .setDescription("run: wall-clock budget for this call."), false);
        root.addProperties("retry_failed", BoolParameter.create()
                .setDescription("run/resume: retry previously failed steps."), false);
        root.addProperties("repair", BoolParameter.create()
                .setDescription("run: re-verify completed steps and reset ones that no longer match the live world."), false);
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
    public LLMCallback onCall(String callId, JsonObject request, LLMCallback callback) {
        String action = action(request);
        if ("run".equals(action) || "request_materials".equals(action)) {
            return callback.addToolResult("{\"success\":false,\"status\":\"async_dispatch_required\","
                    + "\"detail\":\"Building/material checks must use the asynchronous tool dispatcher.\"}", callId);
        }
        JsonObject boundary = executorBoundary(callback);
        if (boundary != null) return callback.addToolResult(boundary.toString(), callId);
        JsonObject response;
        try {
            response = dispatchSync(callback, action, request == null ? new JsonObject() : request);
        } catch (RuntimeException failed) {
            response = failed(failed.getMessage() == null ? "invalid_arguments" : failed.getMessage());
        }
        return callback.addToolResult(response.toString(), callId);
    }

    @Override
    public CompletableFuture<LLMCallback> onCallAsync(String callId, JsonObject request, LLMCallback callback,
                                                       LLMClient client) {
        String action = action(request);
        if (!"run".equals(action) && !"request_materials".equals(action)) {
            return CompletableFuture.completedFuture(onCall(callId, request, callback));
        }
        JsonObject boundary = executorBoundary(callback);
        if (boundary != null) return CompletableFuture.completedFuture(callback.addToolResult(boundary.toString(), callId));
        TaskCallback task = (TaskCallback) callback;
        CompletableFuture<LLMCallback> result = new CompletableFuture<>();
        Runnable start = () -> {
            if (ChatFlowManager.isSuperseded(callback.getMaid().getUUID(), callback)) {
                result.complete(callback);
                return;
            }
            CompletableFuture<JsonObject> gate;
            try {
                gate = MaterialFlow.preflight(callback.getMaid(), task, request);
            } catch (RuntimeException failed) {
                result.complete(callback.addToolResult(failed(failed.getMessage() == null
                        ? "invalid_arguments" : failed.getMessage()).toString(), callId));
                return;
            }
            ChatFlowManager.setInFlight(callback.getMaid().getUUID(), callback, gate);
            gate.whenComplete((preflight, gateFailure) -> {
                Runnable afterPreflight = () -> {
                    if (ChatFlowManager.isSuperseded(callback.getMaid().getUUID(), callback)) {
                        result.complete(callback);
                        return;
                    }
                    if (gateFailure != null || preflight == null) {
                        result.complete(callback.addToolResult(failed("materials_check_failed").toString(), callId));
                        return;
                    }
                    if (!"nothing_missing".equals(string(preflight, "status"))) {
                        result.complete(callback.addToolResult(preflight.toString(), callId));
                        return;
                    }
                    if ("request_materials".equals(action)) {
                        result.complete(callback.addToolResult(preflight.toString(), callId));
                        return;
                    }
                    CompletableFuture<JsonObject> run;
                    try {
                        run = BuildQueueManager.run(callback.getMaid(), callback, request);
                    } catch (RuntimeException failed) {
                        result.complete(callback.addToolResult(failed(failed.getMessage() == null
                                ? "invalid_arguments" : failed.getMessage()).toString(), callId));
                        return;
                    }
                    ChatFlowManager.setInFlight(callback.getMaid().getUUID(), callback, run);
                    run.whenComplete((value, failure) -> {
                        Runnable complete = () -> {
                            if (ChatFlowManager.isSuperseded(callback.getMaid().getUUID(), callback)) {
                                result.complete(callback);
                                return;
                            }
                            JsonObject payload = value;
                            if (failure != null) payload = failed("build_run_failed");
                            else if (payload == null) payload = failed("build_run_no_result");
                            try { payload = MaterialFlow.postRun(callback.getMaid(), task, payload); }
                            catch (RuntimeException materialFailure) {
                                payload = failed("materials_postcheck_failed");
                            }
                            result.complete(callback.addToolResult(payload.toString(), callId));
                        };
                        if (callback.isOnServerThread()) complete.run();
                        else callback.runOnServerThread(complete);
                    });
                };
                if (callback.isOnServerThread()) afterPreflight.run();
                else callback.runOnServerThread(afterPreflight);
            });
        };
        if (callback.isOnServerThread()) start.run();
        else callback.runOnServerThread(start);
        return result;
    }

    private static JsonObject dispatchSync(LLMCallback callback, String action, JsonObject request) {
        EntityMaid maid = callback.getMaid();
        JsonObject response = switch (action) {
            case "design" -> BuildQueueManager.design(maid, request);
            case "status" -> BuildQueueManager.status(maid, request, AgentExecution.foreground(callback));
            case "verify" -> BuildQueueManager.verify(maid, request);
            case "request_materials" -> BuildQueueManager.requestMaterials(maid, request);
            case "reorder" -> BuildQueueManager.reorder(maid, request);
            case "skip" -> BuildQueueManager.skip(maid, request);
            case "reset" -> BuildQueueManager.reset(maid, request);
            case "pause" -> BuildQueueManager.pause(maid, stringArg(request, "reason", "paused_by_owner"));
            case "resume" -> BuildQueueManager.resume(maid, request);
            case "clear" -> BuildQueueManager.clear(maid, request);
            case "delete_design" -> BuildQueueManager.deleteDesign(maid, request);
            case "run" -> failed("async_dispatch_required");
            default -> failed("unknown_action");
        };
        if (callback instanceof TaskCallback task && response != null) {
            response.addProperty("material_policy", task.task.materialPolicy);
            if (task.task.hasOwnerQuestion() && !response.has("pending_owner_question"))
                response.addProperty("pending_owner_question", task.task.pendingOwnerQuestion);
            try {
                String designId = string(response, "design_id");
                if (designId.isBlank()) {
                    var buildState = BuildQueueManager.state(maid);
                    designId = buildState == null ? "" : buildState.activeDesignId;
                }
                var design = designId.isBlank() ? null : BuildQueueManager.design(maid, designId);
                if (design != null) {
                    var state = BuildQueueManager.state(maid);
                    var missing = com.wjx.touhou_aifun.chat.agent.build.BuildMaterialPolicy.missing(maid, design, state);
                    if (missing.isEmpty()) response.remove("missing_items");
                    else response.add("missing_items",
                            com.wjx.touhou_aifun.chat.agent.build.BuildMaterialPolicy.missingJson(missing));
                }
            } catch (RuntimeException ignored) {
            }
        }
        return response;
    }

    private static JsonObject executorBoundary(LLMCallback callback) {
        if (AgentExecution.foreground(callback)) return failed("foreground_action_boundary");
        if (!(callback instanceof TaskCallback)) return failed("task_execution_only");
        return null;
    }

    private static String action(JsonObject request) {
        if (request == null || !request.has("action") || !request.get("action").isJsonPrimitive()) return "";
        return request.get("action").getAsString().trim().toLowerCase(Locale.ROOT);
    }

    private static String stringArg(JsonObject request, String key, String fallback) {
        if (request == null || !request.has(key) || request.get(key).isJsonNull()
                || !request.get(key).isJsonPrimitive()) return fallback;
        return request.get(key).getAsString();
    }

    private static String string(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()
                || !object.get(key).isJsonPrimitive()) return "";
        return object.get(key).getAsString();
    }

    private static JsonObject failed(String error) {
        JsonObject out = new JsonObject();
        out.addProperty("success", false);
        out.addProperty("status", "failed");
        out.addProperty("error", error == null || error.isBlank() ? "build_queue_failed" : error);
        return out;
    }

    @Override
    public Component invocationSummaryComponent(JsonObject request) {
        return Component.translatable("tool.touhou_aifun.build_queue");
    }
}
