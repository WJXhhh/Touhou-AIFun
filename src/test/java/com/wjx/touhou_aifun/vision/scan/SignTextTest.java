package com.wjx.touhou_aifun.vision.scan;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SignTextTest {
    private static final List<String> EMPTY = List.of("", "", "", "");

    @Test
    void preservesBothFacesEmptyLinesAndEscapedTextAsImmutableData() {
        List<String> front = new ArrayList<>(List.of("入口", "", "\"引用\"\\路径\n换行", "末行"));
        ScannedSign sign = new ScannedSign("minecraft:oak_sign", 2, 0, -3, 3.6, "front",
                front, List.of("背面", "", "", "出口"), false);
        front.set(0, "changed");
        JsonObject json = JsonParser.parseString(result(List.of(sign), ScanMode.BLOCKS, "minecraft:overworld")
                .toJson()).getAsJsonObject().getAsJsonArray("sign_texts").get(0).getAsJsonObject();
        assertEquals("入口", json.getAsJsonArray("front_lines").get(0).getAsString());
        assertEquals("", json.getAsJsonArray("front_lines").get(1).getAsString());
        assertEquals("\"引用\"\\路径\n换行", json.getAsJsonArray("front_lines").get(2).getAsString());
        assertEquals("出口", json.getAsJsonArray("back_lines").get(3).getAsString());
        assertEquals(4, json.getAsJsonArray("back_lines").size());
        assertFalse(json.get("text_truncated").getAsBoolean());
        assertThrows(UnsupportedOperationException.class, () -> sign.frontLines().set(0, "edit"));
    }

    @Test
    void lineLimitCountsUnicodeCodePointsWithoutBreakingSurrogatePairs() {
        String line = "😀".repeat(257);
        ScannedSign sign = sign(1, List.of(line, "", "", ""));
        assertEquals("😀".repeat(256), sign.frontLines().get(0));
        assertTrue(sign.textTruncated());
        ScannedSign exact = sign(1, List.of("😀".repeat(256), "", "", ""));
        assertFalse(exact.textTruncated());
        JsonObject json = JsonParser.parseString(result(List.of(sign), ScanMode.BLOCKS, "minecraft:overworld")
                .toJson()).getAsJsonObject();
        assertTrue(json.get("truncated").getAsBoolean());
        assertTrue(json.getAsJsonArray("truncation_reasons").toString().contains("sign_line_limit"));
    }

    @Test
    void countLimitKeepsFocusMatchesThenNearestSignsRegardlessOfArrivalOrder() {
        SignTextCollector collector = new SignTextCollector("birch");
        for (int i = 20; i >= 1; i--) collector.add(sign(i, EMPTY));
        collector.add(new ScannedSign("minecraft:birch_sign", 21, 0, 0, 21, "right", EMPTY, EMPTY, false));
        SignTextCollector.Snapshot snapshot = collector.finish();
        assertEquals(16, snapshot.signs().size());
        assertEquals(5, snapshot.omitted());
        assertEquals("minecraft:birch_sign", snapshot.signs().get(0).registryId());
        assertEquals(1, snapshot.signs().get(1).distance());
        assertEquals(15, snapshot.signs().get(15).distance());
        assertTrue(snapshot.truncationReasons().contains("sign_count_limit"));
        assertEquals(snapshot, collector.finish());
    }

    @Test
    void byteBudgetAccountsForUnicodeAndJsonEscapesAndOmitsWholeSigns() {
        List<String> lines = List.of("中".repeat(64), "\"\\\n".repeat(60), "", "");
        List<ScannedSign> signs = new ArrayList<>();
        for (int i = 6; i >= 1; i--) signs.add(sign(i, lines));
        JsonObject full = JsonParser.parseString(result(signs, ScanMode.BLOCKS, "minecraft:overworld")
                .toJson()).getAsJsonObject();
        JsonArray retained = full.getAsJsonArray("sign_texts");
        assertTrue(retained.size() > 0 && retained.size() < 6);
        assertEquals(6 - retained.size(), full.get("omitted_sign_texts").getAsInt());
        assertTrue(bytes(new GsonBuilder().disableHtmlEscaping().create().toJson(retained)) <= 4 * 1024);
        assertTrue(full.getAsJsonArray("truncation_reasons").toString().contains("sign_text_byte_limit"));
        for (int i = 0; i < retained.size(); i++) {
            JsonObject sign = retained.get(i).getAsJsonObject();
            assertEquals(i + 1, sign.get("distance").getAsDouble());
            assertEquals(4, sign.getAsJsonArray("front_lines").size());
            assertEquals(lines.get(1), sign.getAsJsonArray("front_lines").get(1).getAsString());
        }
    }

    @Test
    void aSingleOversizedSignIsExplicitlyOmittedInsteadOfSplittingItsFaces() {
        List<String> lines = List.of("中".repeat(256), "中".repeat(256), "中".repeat(256), "中".repeat(256));
        ScannedSign sign = new ScannedSign("minecraft:oak_sign", 1, 0, 0, 1, "front", lines, lines, false);
        JsonObject json = JsonParser.parseString(result(List.of(sign), ScanMode.BLOCKS, "minecraft:overworld")
                .toJson()).getAsJsonObject();
        assertTrue(json.getAsJsonArray("sign_texts").isEmpty());
        assertEquals(1, json.get("omitted_sign_texts").getAsInt());
        assertTrue(json.get("truncated").getAsBoolean());
    }

    @Test
    void completeCompactAndEmergencyEnvelopesKeepTheSameSignText() {
        List<ScannedSign> signs = List.of(sign(1, List.of("欢迎", "", "", "")));
        EnvironmentScanResult regular = result(signs, ScanMode.BOTH, "minecraft:overworld");
        JsonObject full = JsonParser.parseString(regular.toJson()).getAsJsonObject();
        JsonObject compact = JsonParser.parseString(regular.toCompactJson()).getAsJsonObject();
        EnvironmentScanResult huge = result(signs, ScanMode.BLOCKS, "超长维度".repeat(10_000));
        String emergency = huge.toJson();
        JsonObject bounded = JsonParser.parseString(emergency).getAsJsonObject();
        assertEquals(full.get("sign_texts"), compact.get("sign_texts"));
        assertEquals(full.get("sign_texts"), bounded.get("sign_texts"));
        assertTrue(bytes(emergency) <= 16 * 1024);
        assertTrue(bounded.get("truncated").getAsBoolean());
        // Signs remain useful even when the independent important-block list has no room.
        assertTrue(full.getAsJsonArray("important_blocks").isEmpty());
        assertEquals(1, regular.signTexts().size());
    }

    @Test
    void entityOnlyAndNoneModesDoNotExposeSignText() {
        for (ScanMode mode : List.of(ScanMode.ENTITIES, ScanMode.NONE)) {
            EnvironmentScanResult result = result(List.of(sign(1, EMPTY)), mode, "minecraft:overworld");
            assertTrue(result.signTexts().isEmpty());
            assertTrue(JsonParser.parseString(result.toJson()).getAsJsonObject().getAsJsonArray("sign_texts").isEmpty());
        }
    }

    private static ScannedSign sign(int distance, List<String> front) {
        return new ScannedSign("minecraft:oak_sign", distance, 0, 0, distance, "right", front, EMPTY, false);
    }

    private static int bytes(String json) { return json.getBytes(StandardCharsets.UTF_8).length; }

    private static EnvironmentScanResult result(List<ScannedSign> signs, ScanMode mode, String dimension) {
        return new EnvironmentScanResult(42, dimension, new EnvironmentScanRequest(mode, ScanDirection.ALL, 20, ""),
                10, 100, 0, List.of(), List.of(), List.of(), 0, 0, 0, Map.of(), 0, 0, Map.of(),
                new int[0], Map.of(), new int[0], Map.of(), new double[0], false, List.of(), signs, 0);
    }
}
