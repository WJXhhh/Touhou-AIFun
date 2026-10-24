package com.wjx.touhou_aifun.chat.agent.build;

import com.github.tartaricacid.touhoulittlemaid.block.BlockSnackCabinet;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.wjx.touhou_aifun.maid.action.block.MaidBlockStateMatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic live-world verification for build steps. It never loads a chunk: a step in an
 * unloaded chunk is reported as unverifiable instead of forcing world generation.
 */
public final class BuildStepVerifier {
    private BuildStepVerifier() {
    }

    public static JsonObject verify(EntityMaid maid, BuildDesign design, BuildStep step, boolean forceFullScan) {
        JsonObject out = new JsonObject();
        out.addProperty("verified", false);
        String expectedText = expectedText(step);
        out.addProperty("expected", expectedText);
        out.addProperty("actual", "");
        out.addProperty("block_entity", "");
        out.addProperty("reason", "");
        out.add("position", position(step));
        if (step == null) {
            out.addProperty("reason", "missing_step");
            return out;
        }
        if (!(maid.level() instanceof ServerLevel level) || maid.isRemoved()) {
            out.addProperty("reason", "maid_unavailable");
            return out;
        }
        BlockPos target = new BlockPos(step.x, step.y, step.z);
        if (!level.hasChunkAt(target)) {
            out.addProperty("reason", "chunk_not_loaded");
            return out;
        }
        BlockState actual = level.getBlockState(target);
        out.addProperty("actual", MaidBlockStateMatcher.serialize(actual));
        out.addProperty("block_entity", blockEntityId(level, target));
        if (forceFullScan) {
            out.addProperty("full_scan", true);
            out.add("neighbors", neighborStates(level, target));
        }
        String kind = step.kindOrPlace();
        boolean verified;
        String reason = "";
        try {
            switch (kind) {
                case "mine" -> {
                    verified = actual.isAir();
                    if (!verified) reason = "expected_air";
                }
                case "interact" -> {
                    // Interact steps are optional unless the design names the state they should produce.
                    if (step.block == null || step.block.isBlank()) {
                        verified = true;
                    } else {
                        verified = MaidBlockStateMatcher.matches(actual,
                                MaidBlockStateMatcher.parse(step.block), step.strict);
                        if (!verified) reason = "block_state_mismatch";
                    }
                }
                case "place" -> {
                    if (step.block == null || step.block.isBlank()) {
                        verified = false;
                        reason = "missing_expected_block";
                    } else {
                        verified = MaidBlockStateMatcher.matches(actual,
                                MaidBlockStateMatcher.parse(step.block), step.strict);
                        if (!verified) reason = "block_state_mismatch";
                    }
                }
                default -> {
                    verified = false;
                    reason = "unknown_step_kind";
                }
            }
        } catch (RuntimeException invalid) {
            verified = false;
            reason = "invalid_expected_block";
        }
        out.addProperty("verified", verified);
        out.addProperty("reason", verified ? "" : reason);
        snackCabinetType(level, target, actual, out);
        return out;
    }

    /**
     * Returns the unsatisfied steps of one design. Interact steps are included only for an explicit
     * request, matching their optional verification semantics.
     */
    public static JsonArray mismatches(EntityMaid maid, BuildDesign design, boolean includeInteract) {
        JsonArray rows = new JsonArray();
        if (design == null || design.steps == null) return rows;
        for (BuildStep step : design.steps) {
            if (step == null) continue;
            String kind = step.kindOrPlace();
            if ("interact".equals(kind) && (!includeInteract || step.block == null || step.block.isBlank())) continue;
            JsonObject result = verify(maid, design, step, false);
            if (!result.get("verified").getAsBoolean()) {
                result.addProperty("index", step.index);
                rows.add(result);
            }
        }
        return rows;
    }

    public static List<String> mismatchMessages(EntityMaid maid, BuildDesign design, boolean includeInteract) {
        List<String> messages = new ArrayList<>();
        JsonArray rows = mismatches(maid, design, includeInteract);
        for (var row : rows) {
            if (!row.isJsonObject()) continue;
            JsonObject value = row.getAsJsonObject();
            messages.add("step " + value.get("index").getAsInt() + " expected " + value.get("expected")
                    .getAsString() + " but found " + value.get("actual").getAsString()
                    + " (" + value.get("reason").getAsString() + ")");
        }
        return messages;
    }

    private static String expectedText(@Nullable BuildStep step) {
        if (step == null) return "";
        if ("mine".equals(step.kindOrPlace())) return "minecraft:air";
        return step.block == null ? "" : step.block;
    }

    private static JsonArray position(BuildStep step) {
        JsonArray array = new JsonArray();
        if (step != null) {
            array.add(step.x);
            array.add(step.y);
            array.add(step.z);
        }
        return array;
    }

    private static String blockEntityId(ServerLevel level, BlockPos pos) {
        try {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity == null) return "";
            ResourceLocation id = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType());
            return id == null ? "" : id.toString();
        } catch (RuntimeException unavailable) {
            return "";
        }
    }

    private static JsonObject neighborStates(ServerLevel level, BlockPos target) {
        JsonObject out = new JsonObject();
        for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.values()) {
            BlockPos neighbor = target.relative(direction);
            if (!level.hasChunkAt(neighbor)) continue;
            out.addProperty(direction.getName(), MaidBlockStateMatcher.serialize(level.getBlockState(neighbor)));
        }
        return out;
    }

    /**
     * TLM's snack cabinet stores its edible state on the support block. A cake placed on top keeps
     * the cabinet type; expose it as read-only evidence, never as a mutation.
     */
    private static void snackCabinetType(ServerLevel level, BlockPos target, BlockState actual, JsonObject out) {
        boolean cake = actual != null && actual.is(Blocks.CAKE);
        if (!cake) return;
        BlockPos support = target.below();
        if (!level.hasChunkAt(support)) return;
        BlockState supportState = level.getBlockState(support);
        if (!(supportState.getBlock() instanceof BlockSnackCabinet)) return;
        try {
            out.addProperty("snack_cabinet_type", supportState.getValue(BlockSnackCabinet.TYPE));
        } catch (RuntimeException unavailable) {
            // Property unavailable on this TLM build; omit it rather than failing verification.
        }
    }
}
