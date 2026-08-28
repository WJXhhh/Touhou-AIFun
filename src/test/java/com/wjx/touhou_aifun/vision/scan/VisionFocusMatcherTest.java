package com.wjx.touhou_aifun.vision.scan;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisionFocusMatcherTest {
    @Test
    void matchesCommonChineseAndEnglishAliasesWithoutChangingUnrelatedIds() {
        assertTrue(VisionFocusMatcher.matches("看看附近的箱子", "minecraft:chest"));
        assertTrue(VisionFocusMatcher.matches("有没有苦力怕", "minecraft:creeper"));
        assertTrue(VisionFocusMatcher.matches("检查危险", "minecraft:lava"));
        assertTrue(VisionFocusMatcher.matches("find a portal", "minecraft:nether_portal"));
        assertTrue(VisionFocusMatcher.matches("吃掉零食箱上的蛋糕", "minecraft:cake"));
        assertTrue(VisionFocusMatcher.matches("看看零食柜", "touhou_little_maid:snack_cabinet"));
        assertFalse(VisionFocusMatcher.matches("找箱子", "minecraft:zombie"));
    }
}
