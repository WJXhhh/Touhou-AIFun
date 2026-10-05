package com.wjx.touhou_aifun.maid.gui;

import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GuiBlockTargetTest {
    @Test void qualifiedTargetsUseExplicitCoordinatesWithoutNearestFallbackOrConflictingAliases() {
        var request=JsonParser.parseString("{\"target\":\"minecraft:chest@71,-59,45\"}").getAsJsonObject();
        assertEquals(new BlockPos(71,-59,45),GuiBlockTarget.coordinates(request,BlockPos.ZERO));assertEquals("minecraft:chest",GuiBlockTarget.qualifiedRegistry(request));
        assertTrue(GuiBlockTarget.matches("minecraft:chest@71,-59,45","minecraft:chest"));assertFalse(GuiBlockTarget.matches("minecraft:chest@71,-59,45","minecraft:stone"));
        request.addProperty("coordinate_space","maid_relative");assertEquals(new BlockPos(81,-59,45),GuiBlockTarget.coordinates(request,new BlockPos(10,0,0)));
        request.addProperty("x",72);request.addProperty("y",-59);request.addProperty("z",45);
        assertThrows(IllegalArgumentException.class,()->GuiBlockTarget.coordinates(request,BlockPos.ZERO));
        assertThrows(IllegalArgumentException.class,()->GuiBlockTarget.coordinates(JsonParser.parseString("{\"target\":\"minecraft:chest@nearest\"}").getAsJsonObject(),BlockPos.ZERO));
    }
    @Test void worldCoordinatesAreNeverSilentlyConverted() {
        var request = JsonParser.parseString("{\"x\":-2,\"y\":0,\"z\":-4}").getAsJsonObject();
        assertEquals(new BlockPos(-2, 0, -4), GuiBlockTarget.coordinates(request, new BlockPos(45, -60, -28)));
        request.addProperty("coordinate_space", "maid_relative");
        assertEquals(new BlockPos(43, -60, -32), GuiBlockTarget.coordinates(request, new BlockPos(45, -60, -28)));
    }
    @Test void nearestSearchDoesNotInventCoordinates() {
        assertNull(GuiBlockTarget.coordinates(JsonParser.parseString("{\"target\":\"minecraft:chest\"}").getAsJsonObject(), BlockPos.ZERO));
    }
    @Test void incompleteOrAmbiguousPositionsAreRejected() {
        for (String json : new String[]{"{\"x\":1}", "{\"coordinate_space\":\"relative\"}",
                "{\"coordinate_space\":\"maid_relative\"}", "{\"x\":1.5,\"y\":0,\"z\":0}",
                "{\"x\":2147483647,\"y\":0,\"z\":0,\"coordinate_space\":\"maid_relative\"}"})
            assertThrows(IllegalArgumentException.class, () -> GuiBlockTarget.coordinates(JsonParser.parseString(json).getAsJsonObject(), new BlockPos(10, 0, 0)));
    }
    @Test void registryIdsAreExactAndSimpleNamesRemainSearchable() {
        assertTrue(GuiBlockTarget.matches("chest", "minecraft:trapped_chest"));
        assertTrue(GuiBlockTarget.matches("minecraft:chest", "minecraft:chest"));
        assertFalse(GuiBlockTarget.matches("minecraft:chest", "othermod:minecraft:chest"));
        assertFalse(GuiBlockTarget.matches("minecraft:chest", "minecraft:chest_extension"));
        assertFalse(GuiBlockTarget.matches("minecraft:chest", "minecraft:stone"));
    }
}
