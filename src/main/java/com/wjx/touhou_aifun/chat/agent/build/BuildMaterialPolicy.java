package com.wjx.touhou_aifun.chat.agent.build;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.wjx.touhou_aifun.chat.agent.AgentTaskState;
import com.wjx.touhou_aifun.maid.action.block.MaidItemSelection;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.BlockItem;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Aggregates build material gaps and enforces the owner-authorized withdrawal boundary. */
public final class BuildMaterialPolicy {
    public static final int MAX_MISSING_ROWS = 16;

    private BuildMaterialPolicy() {
    }

    public record Missing(String item, String displayName, int required, int have, int forStep) {
        public JsonObject toJson() {
            JsonObject out = new JsonObject();
            out.addProperty("item", item);
            out.addProperty("display_name", displayName);
            out.addProperty("required", required);
            out.addProperty("have", have);
            out.addProperty("missing", Math.max(0, required - have));
            out.addProperty("for_step", forStep);
            return out;
        }
    }

    /** All place/interact item requirements for required steps, grouped by item id. */
    public static Map<String, Integer> requiredCounts(BuildDesign design) {
        return requiredCounts(design, null);
    }

    /** Requirements for required steps that are still pending/failed/blocked (not done/skipped). */
    public static Map<String, Integer> requiredCounts(BuildDesign design, BuildQueueState state) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (design == null) return counts;
        Map<String, Integer> toolFirstStep = new LinkedHashMap<>();
        for (BuildStep step : design.steps) {
            if (!step.required || completedStep(state, step)) continue;
            String item = requiredItem(step);
            if (!item.isBlank()) counts.merge(item, 1, Integer::sum);
            if ("mine".equals(step.kind) && step.tool != null && !step.tool.isBlank())
                toolFirstStep.putIfAbsent(step.tool, step.index);
        }
        for (String tool : toolFirstStep.keySet()) counts.putIfAbsent(tool, 1);
        return counts;
    }

    public static List<Missing> missing(EntityMaid maid, BuildDesign design, int fromStepIndex) {
        return missing(maid, design, null, fromStepIndex);
    }

    public static List<Missing> missing(EntityMaid maid, BuildDesign design, BuildQueueState state) {
        return missing(maid, design, state, 0);
    }

    private static List<Missing> missing(EntityMaid maid, BuildDesign design, BuildQueueState state, int fromStepIndex) {
        Map<String, Integer> required = new LinkedHashMap<>();
        Map<String, Integer> firstStep = new LinkedHashMap<>();
        Map<String, Integer> toolFirstStep = new LinkedHashMap<>();
        if (design != null) for (BuildStep step : design.steps) {
            if (!step.required || step.index < fromStepIndex || completedStep(state, step)) continue;
            String item = requiredItem(step);
            if (!item.isBlank()) {
                required.merge(item, 1, Integer::sum);
                firstStep.putIfAbsent(item, step.index);
            }
            if ("mine".equals(step.kind) && step.tool != null && !step.tool.isBlank())
                toolFirstStep.putIfAbsent(step.tool, step.index);
        }
        List<Missing> out = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : required.entrySet()) {
            int have = MaidItemSelection.count(maid, entry.getKey());
            if (have >= entry.getValue()) continue;
            out.add(new Missing(entry.getKey(), displayName(entry.getKey()), entry.getValue(), have,
                    firstStep.getOrDefault(entry.getKey(), -1)));
            if (out.size() >= MAX_MISSING_ROWS) return out;
        }
        for (Map.Entry<String, Integer> entry : toolFirstStep.entrySet()) {
            if (required.containsKey(entry.getKey())) continue;
            int have = MaidItemSelection.count(maid, entry.getKey());
            if (have > 0) continue;
            out.add(new Missing(entry.getKey(), displayName(entry.getKey()), 1, 0, entry.getValue()));
            if (out.size() >= MAX_MISSING_ROWS) return out;
        }
        return out;
    }

    private static boolean completedStep(BuildQueueState state, BuildStep step) {
        if (state == null || step == null) return false;
        BuildStepState stepState = state.stepState(step.index);
        return stepState != null && (BuildStepState.DONE.equals(stepState.status)
                || BuildStepState.SKIPPED.equals(stepState.status));
    }

    public static JsonArray missingJson(List<Missing> missing) {
        JsonArray array = new JsonArray();
        if (missing != null) for (Missing row : missing) array.add(row.toJson());
        return array;
    }

    public static String requiredItem(BuildStep step) {
        if (step == null) return "";
        if (!step.item.isBlank()) return step.item;
        if (!step.block.isBlank()) {
            try {
                var expected = com.wjx.touhou_aifun.maid.action.block.MaidBlockStateMatcher.parse(step.block);
                Item item = expected.block().asItem();
                ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
                if (key != null && item != net.minecraft.world.item.Items.AIR) return key.toString();
            } catch (RuntimeException ignored) {
            }
        }
        return "";
    }

    public static String displayName(String itemId) {
        ResourceLocation key = ResourceLocation.tryParse(itemId);
        Item item = key == null ? null : BuiltInRegistries.ITEM.get(key);
        return item == null ? itemId : new ItemStack(item).getHoverName().getString();
    }

    /**
     * True when an automatic GUI transfer into the maid is bounded by the current build gap and the
     * owner allowed source withdrawals. Used by TaskCallback before any transfer is dispatched.
     */
    public static boolean withdrawalAllowed(EntityMaid maid, AgentTaskState task,
                                             String itemId, long amount) {
        if (task == null || !AgentTaskState.MATERIAL_SELF_SOURCE.equals(task.materialPolicy)) return false;
        if (amount <= 0) return false;
        BuildQueueState state = BuildQueueManager.state(maid);
        BuildDesign design = state == null ? null : BuildQueueManager.design(maid, state.activeDesignId);
        if (design == null) return false;
        Map<String, Integer> required = requiredCounts(design, state);
        int need = required.getOrDefault(itemId, 0);
        int have = MaidItemSelection.count(maid, itemId);
        return need > 0 && have < need && amount <= need - have;
    }

    /** Item ids currently missing for the active build; empty when there is no active design. */
    public static java.util.Set<String> missingItems(EntityMaid maid) {
        BuildQueueState state = BuildQueueManager.state(maid);
        BuildDesign design = state == null ? null : BuildQueueManager.design(maid, state.activeDesignId);
        java.util.Set<String> result = new java.util.LinkedHashSet<>();
        for (Missing row : missing(maid, design, state)) result.add(row.item());
        return result;
    }

    public static boolean isPlaceableItem(String itemId) {
        ResourceLocation key = ResourceLocation.tryParse(itemId);
        return key != null && BuiltInRegistries.ITEM.get(key) instanceof BlockItem;
    }
}
