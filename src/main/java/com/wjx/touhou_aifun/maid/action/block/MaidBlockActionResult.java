package com.wjx.touhou_aifun.maid.action.block;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import javax.annotation.Nullable;
import java.util.List;

/** Result JSON for a single simulated block action. */
public record MaidBlockActionResult(boolean success, String status, String error, String detail,
                                    MaidBlockActionRequest.Action action, @Nullable BlockPos position,
                                    @Nullable Direction face, String expected, String actual,
                                    String previous, String item, int itemConsumed, int attempts,
                                    boolean decisionUsed, List<Drop> drops,
                                    @Nullable Integer snackCabinetType,
                                    @Nullable MaidPlacementOrientation.Result orientation) {
    public record Drop(String item, int count) {
    }

    public static MaidBlockActionResult failure(MaidBlockActionRequest.Action action, String error,
                                                String detail, @Nullable BlockPos position,
                                                String expected, String actual) {
        return new MaidBlockActionResult(false, "failed", error == null ? "block_action_failed" : error,
                detail == null ? "" : detail, action, position, null, expected == null ? "" : expected,
                actual == null ? "" : actual, "", "", 0, 1, false, List.of(), null, null);
    }

    public static MaidBlockActionResult success(MaidBlockActionRequest.Action action, String status,
                                                @Nullable BlockPos position, @Nullable Direction face,
                                                String expected, String actual, String previous,
                                                String item, int itemConsumed, boolean decisionUsed,
                                                List<Drop> drops, @Nullable Integer snackCabinetType) {
        return new MaidBlockActionResult(true, status, "", "", action, position, face,
                expected == null ? "" : expected, actual == null ? "" : actual,
                previous == null ? "" : previous, item == null ? "" : item, itemConsumed, 1,
                decisionUsed, drops == null ? List.of() : List.copyOf(drops), snackCabinetType, null);
    }

    public MaidBlockActionResult withAttempts(int value) {
        return new MaidBlockActionResult(success, status, error, detail, action, position, face, expected,
                actual, previous, item, itemConsumed, value, decisionUsed, drops, snackCabinetType, orientation);
    }

    public MaidBlockActionResult withDecision(boolean used) {
        return new MaidBlockActionResult(success, status, error, detail, action, position, face, expected,
                actual, previous, item, itemConsumed, attempts, used, drops, snackCabinetType, orientation);
    }

    public MaidBlockActionResult withOrientation(@Nullable MaidPlacementOrientation.Result value) {
        return new MaidBlockActionResult(success, status, error, detail, action, position, face, expected,
                actual, previous, item, itemConsumed, attempts, decisionUsed, drops, snackCabinetType, value);
    }

    public JsonObject toJson() {
        JsonObject out = new JsonObject();
        out.addProperty("success", success);
        out.addProperty("status", status);
        if (!error.isBlank()) out.addProperty("error", error);
        if (!detail.isBlank()) out.addProperty("detail", detail);
        if (action != null) out.addProperty("action", action.name().toLowerCase(java.util.Locale.ROOT));
        if (position != null) {
            JsonArray pos = new JsonArray();
            pos.add(position.getX());
            pos.add(position.getY());
            pos.add(position.getZ());
            out.add("position", pos);
        }
        if (face != null) out.addProperty("face", face.getName());
        if (!expected.isBlank()) out.addProperty("expected_block", expected);
        if (!actual.isBlank()) out.addProperty("actual_block", actual);
        if (!previous.isBlank()) out.addProperty("previous_block", previous);
        if (!item.isBlank()) out.addProperty("item", item);
        out.addProperty("item_consumed", itemConsumed);
        out.addProperty("attempts", attempts);
        out.addProperty("decision_used", decisionUsed);
        if (!drops.isEmpty()) {
            JsonArray array = new JsonArray();
            for (Drop drop : drops) {
                JsonObject row = new JsonObject();
                row.addProperty("item", drop.item());
                row.addProperty("count", drop.count());
                array.add(row);
            }
            out.add("drops", array);
        }
        if (snackCabinetType != null) out.addProperty("snack_cabinet_type", snackCabinetType);
        if (orientation != null) out.add("orientation", orientation.toJson());
        if (!success) out.addProperty("recovery_hint", "Re-observe the target before retrying; never assume a failed mutation did nothing.");
        return out;
    }
}
