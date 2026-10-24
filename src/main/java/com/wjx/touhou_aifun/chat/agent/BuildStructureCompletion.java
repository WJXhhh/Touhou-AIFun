package com.wjx.touhou_aifun.chat.agent;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wjx.touhou_aifun.chat.agent.build.BuildDesign;
import com.wjx.touhou_aifun.chat.agent.build.BuildQueueManager;
import com.wjx.touhou_aifun.chat.agent.build.BuildStep;
import com.wjx.touhou_aifun.maid.action.block.MaidBlockStateMatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/** Live-world verification for the atomic completion kind "structure". */
public final class BuildStructureCompletion {
    public static final int MAX_INLINE_BLOCKS = 64;

    private BuildStructureCompletion() {
    }

    public record Unmet(int x, int y, int z, String expected, String actual, String reason) {
    }

    public record Result(boolean verified, int total, int matched, String reason, List<Unmet> unmet) {
        public Result {
            unmet = unmet == null ? List.of() : List.copyOf(unmet);
        }
    }

    public static Result verify(EntityMaid maid, JsonObject condition) {
        if (condition == null) return new Result(false, 0, 0, "structure_condition_missing", List.of());
        if (!maid.level().dimension().location().toString().equals(condition.get("dimension").getAsString())) {
            return new Result(false, 0, 0, "dimension_mismatch", List.of());
        }
        if (condition.has("structure_id") && condition.get("structure_id").isJsonPrimitive()) {
            String id = condition.get("structure_id").getAsString();
            BuildDesign design = BuildQueueManager.design(maid, id);
            if (design == null) return new Result(false, 0, 0, "structure_design_missing", List.of());
            if (!design.dimension.equals(maid.level().dimension().location().toString())) {
                return new Result(false, 0, 0, "dimension_mismatch", List.of());
            }
            List<Unmet> unmet = new ArrayList<>();
            int total = 0, matched = 0;
            for (BuildStep step : design.steps) {
                if (!step.required) continue;
                if (step.kind.equals("interact")) continue;
                total++;
                String expected = step.kind.equals("mine") ? "minecraft:air" : step.block;
                Unmet failure = check(maid, step.x, step.y, step.z, expected);
                if (failure == null) matched++; else if (unmet.size() < 16) unmet.add(failure);
            }
            return new Result(total > 0 && matched == total, total, matched,
                    total == 0 ? "structure_has_no_required_steps" : "", unmet);
        }
        JsonArray blocks = condition.has("blocks") && condition.get("blocks").isJsonArray()
                ? condition.getAsJsonArray("blocks") : new JsonArray();
        if (blocks.isEmpty() || blocks.size() > MAX_INLINE_BLOCKS) {
            return new Result(false, 0, 0, "invalid_structure_blocks", List.of());
        }
        boolean globalStrict = condition.has("strict") && condition.get("strict").getAsBoolean();
        int total = 0, matched = 0;
        List<Unmet> unmet = new ArrayList<>();
        for (JsonElement element : blocks) {
            if (!element.isJsonObject()) continue;
            JsonObject row = element.getAsJsonObject();
            total++;
            int x = row.get("x").getAsInt(), y = row.get("y").getAsInt(), z = row.get("z").getAsInt();
            String expected = expected(row);
            BlockPos pos = new BlockPos(x, y, z);
            BlockState actual = maid.level().hasChunkAt(pos) ? maid.level().getBlockState(pos) : null;
            boolean ok;
            String actualText;
            if (actual == null) {
                ok = false;
                actualText = "unloaded";
            } else {
                actualText = MaidBlockStateMatcher.serialize(actual);
                try {
                    boolean strict = globalStrict || row.has("strict") && row.get("strict").getAsBoolean();
                    ok = MaidBlockStateMatcher.matches(actual, MaidBlockStateMatcher.parse(expected), strict);
                } catch (RuntimeException invalid) {
                    ok = false;
                }
            }
            if (ok) matched++;
            else if (unmet.size() < 16) unmet.add(new Unmet(x, y, z, expected, actualText,
                    actual == null ? "unloaded" : "block_mismatch"));
        }
        return new Result(total > 0 && matched == total, total, matched,
                total == 0 ? "invalid_structure_blocks" : "", unmet);
    }

    private static String expected(JsonObject row) {
        if (row.has("expect") && row.get("expect").isJsonPrimitive()
                && "air".equalsIgnoreCase(row.get("expect").getAsString())) {
            return "minecraft:air";
        }
        return row.has("block") && row.get("block").isJsonPrimitive() ? row.get("block").getAsString() : "";
    }

    private static Unmet check(EntityMaid maid, int x, int y, int z, String expected) {
        BlockPos pos = new BlockPos(x, y, z);
        if (!maid.level().hasChunkAt(pos)) {
            return new Unmet(x, y, z, expected, "unloaded", "unloaded");
        }
        BlockState actual = maid.level().getBlockState(pos);
        boolean ok;
        try {
            ok = MaidBlockStateMatcher.matches(actual, MaidBlockStateMatcher.parse(expected), false);
        } catch (RuntimeException invalid) {
            ok = false;
        }
        return ok ? null : new Unmet(x, y, z, expected, MaidBlockStateMatcher.serialize(actual), "block_mismatch");
    }

    public static JsonObject evidence(Result result, int gameTick) {
        JsonObject evidence = new JsonObject();
        evidence.addProperty("kind", "structure");
        evidence.addProperty("total", result.total());
        evidence.addProperty("matched", result.matched());
        evidence.addProperty("verified", result.verified());
        if (!result.reason().isBlank()) evidence.addProperty("reason", result.reason());
        if (!result.unmet().isEmpty()) {
            JsonArray rows = new JsonArray();
            for (Unmet unmet : result.unmet()) {
                JsonObject row = new JsonObject();
                row.addProperty("x", unmet.x());
                row.addProperty("y", unmet.y());
                row.addProperty("z", unmet.z());
                row.addProperty("expected", unmet.expected());
                row.addProperty("actual", unmet.actual());
                row.addProperty("reason", unmet.reason());
                rows.add(row);
            }
            evidence.add("unmet", rows);
        }
        evidence.addProperty("game_tick", gameTick);
        return evidence;
    }
}
