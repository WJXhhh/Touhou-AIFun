package com.wjx.touhou_aifun.vision.scan;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisionScanTagCoverageTest {
    @Test
    void importantTagContainsRequiredResourceTravelAndHazardBlocks() throws Exception {
        Set<String> entries = readValues("data/touhou_aifun/tags/blocks/vision_scan_important.json");
        for (String required : Set.of(
                "#forge:ores",
                "minecraft:ancient_debris",
                "minecraft:ladder",
                "minecraft:nether_portal",
                "minecraft:end_portal",
                "minecraft:end_gateway",
                "minecraft:lava",
                "minecraft:fire",
                "minecraft:soul_fire",
                "minecraft:tnt",
                "minecraft:magma_block",
                "minecraft:cactus",
                "minecraft:powder_snow")) {
            assertTrue(entries.contains(required), () -> "important scan tag is missing " + required);
        }
    }

    @Test
    void opacityTagsContainTheDocumentedRepresentativeBlocks() throws Exception {
        assertContains("low_transparency.json", "#minecraft:leaves");
        assertContains("medium_transparency.json", "minecraft:red_stained_glass", "minecraft:ice");
        assertContains("high_transparency.json", "minecraft:glass", "minecraft:water");
        assertContains("thin.json", "#minecraft:flowers", "minecraft:vine", "minecraft:cave_vines",
                "minecraft:grass", "minecraft:torch");
    }

    private void assertContains(String file, String... required) throws Exception {
        Set<String> entries = readValues("data/touhou_aifun/tags/blocks/vision_scan/" + file);
        for (String value : required) {
            assertTrue(entries.contains(value), () -> file + " is missing " + value);
        }
    }

    private Set<String> readValues(String path) throws Exception {
        try (var stream = getClass().getClassLoader().getResourceAsStream(path)) {
            assertNotNull(stream, "missing " + path);
            JsonObject root = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            JsonArray values = root.getAsJsonArray("values");
            Set<String> entries = new HashSet<>();
            values.forEach(value -> entries.add(value.getAsString()));
            return entries;
        }
    }
}
