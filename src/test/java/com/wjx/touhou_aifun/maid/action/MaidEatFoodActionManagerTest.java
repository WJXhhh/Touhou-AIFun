package com.wjx.touhou_aifun.maid.action;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaidEatFoodActionManagerTest {
    @Test
    void foodFilterAcceptsGenericExactShortAndChineseCakeNames() {
        assertTrue(MaidEatFoodActionManager.matchesRequestedFood("any", "minecraft:cake"));
        assertTrue(MaidEatFoodActionManager.matchesRequestedFood("minecraft:cake", "minecraft:cake"));
        assertTrue(MaidEatFoodActionManager.matchesRequestedFood("cake", "minecraft:cake"));
        assertTrue(MaidEatFoodActionManager.matchesRequestedFood("零食箱上面的蛋糕", "minecraft:cake"));
        assertFalse(MaidEatFoodActionManager.matchesRequestedFood("cake", "farmersdelight:roast_chicken_block"));
    }

    @Test
    void namespacedAndShortModFoodNamesRemainSelectable() {
        assertTrue(MaidEatFoodActionManager.matchesRequestedFood(
                "farmersdelight:roast_chicken_block", "farmersdelight:roast_chicken_block"));
        assertTrue(MaidEatFoodActionManager.matchesRequestedFood(
                "roast_chicken", "farmersdelight:roast_chicken_block"));
        assertFalse(MaidEatFoodActionManager.matchesRequestedFood(
                "minecraft:cake", "example:cake"));
    }

    @Test
    void finishedResultReportsBatchProgressToTheModel() {
        MaidEatFoodActionManager.Result result = MaidEatFoodActionManager.Result.success(
                "minecraft:cake", new BlockPos(1, 2, 3), 7, true);

        assertTrue(result.success());
        assertTrue(result.finished());
        assertEquals(7, result.servingsConsumed());
        assertTrue(result.toJson().contains("\"status\":\"finished\""));
        assertTrue(result.toJson().contains("\"servings_consumed\":7"));
        assertTrue(result.toJson().contains("\"finished\":true"));
    }
}
