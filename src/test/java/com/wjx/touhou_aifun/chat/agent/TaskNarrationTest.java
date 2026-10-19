package com.wjx.touhou_aifun.chat.agent;

import com.google.gson.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class TaskNarrationTest {
    @Test void archiveNarrationIsBoundedBlankIgnoredAndAdditiveForOldArchives() {
        var archive = new AgentTaskArchive("maid", "task");
        for (int i = 0; i < 30; i++) archive.appendNarration("line " + i);
        assertEquals(TaskNarration.MAX_ENTRIES, archive.narration.size());
        assertEquals("line 6", archive.narration.get(0));
        archive.appendNarration("   ");
        archive.appendNarration("x".repeat(5000));
        assertEquals(TaskNarration.MAX_ENTRY_CHARS, archive.narration.get(archive.narration.size() - 1).length());
        var old = new Gson().fromJson("{\"maidId\":\"m\",\"taskId\":\"t\"}", AgentTaskArchive.class);
        old.validateSize();
        assertNotNull(old.narration);
        assertTrue(old.narration.isEmpty());
        var restored = new Gson().fromJson(new Gson().toJson(archive), AgentTaskArchive.class);
        restored.validateSize();
        assertEquals(archive.narration, restored.narration);
    }

    @Test void tailKeepsOnlyTheNewestLinesAndBoundsThem() {
        assertEquals(List.of("b", "c"), TaskNarration.tail(List.of("a", "b", "c"), 2, 10));
        assertEquals(List.of("xxxxx"), TaskNarration.tail(List.of("x".repeat(20)), 2, 5));
        assertTrue(TaskNarration.tail(List.of(), 2, 10).isEmpty());
        assertTrue(TaskNarration.tail(null, 2, 10).isEmpty());
    }

    @Test void pageReadsTheNewestWindowInChronologicalOrder() {
        List<String> lines = IntStream.range(0, 10).mapToObj(i -> "line " + i).toList();
        JsonObject first = JsonParser.parseString(TaskNarration.page("task", lines, 0)).getAsJsonObject();
        assertEquals("ok", first.get("status").getAsString());
        assertEquals("task", first.get("task_id").getAsString());
        assertEquals(10, first.get("total").getAsInt());
        assertEquals(8, first.get("returned").getAsInt());
        assertEquals(8, first.get("next_offset").getAsInt());
        JsonArray rows = first.getAsJsonArray("entries");
        assertEquals(8, rows.size());
        assertEquals(2, rows.get(0).getAsJsonObject().get("index").getAsInt());
        assertEquals("line 2", rows.get(0).getAsJsonObject().get("text").getAsString());
        assertEquals(9, rows.get(7).getAsJsonObject().get("index").getAsInt());
        JsonObject older = JsonParser.parseString(TaskNarration.page("task", lines, first.get("next_offset").getAsInt())).getAsJsonObject();
        assertEquals(2, older.get("returned").getAsInt());
        assertEquals("line 0", older.getAsJsonArray("entries").get(0).getAsJsonObject().get("text").getAsString());
        assertTrue(older.get("next_offset").isJsonNull());
    }

    @Test void pageHandlesEmptyOutOfRangeAndCharacterBudgetWithoutDroppingTheNewestLine() {
        assertEquals("empty", JsonParser.parseString(TaskNarration.page("t", List.of(), 0)).getAsJsonObject().get("status").getAsString());
        var out = JsonParser.parseString(TaskNarration.page("t", List.of("a", "b"), 5)).getAsJsonObject();
        assertEquals("offset_out_of_range", out.get("status").getAsString());
        assertEquals(0, out.get("returned").getAsInt());

        List<String> heavy = new ArrayList<>();
        for (int i = 0; i < 8; i++) heavy.add(("entry " + i + " ") + "x".repeat(600));
        var page = JsonParser.parseString(TaskNarration.page("t", heavy, 0)).getAsJsonObject();
        assertEquals(6, page.get("returned").getAsInt());
        JsonArray rows = page.getAsJsonArray("entries");
        assertEquals(2, rows.get(0).getAsJsonObject().get("index").getAsInt());
        assertEquals(7, rows.get(rows.size() - 1).getAsJsonObject().get("index").getAsInt());
        assertEquals(6, page.get("next_offset").getAsInt());
        int chars = 0;
        for (var row : rows) chars += row.getAsJsonObject().get("text").getAsString().length();
        assertTrue(chars <= TaskNarration.MAX_PAGE_CHARS);
    }

    @Test void publishedStatusFactsCarryTheNewestNarrationWithoutShowingTheWholeHistory() {
        List<String> lines = IntStream.range(0, 5).mapToObj(i -> "line " + i).toList();
        var facts = new ForegroundTaskStatus("task", AgentTaskState.Status.running, "sort", 3, "",
                TaskNarration.tail(lines, 2, 200));
        assertEquals(List.of("line 3", "line 4"), facts.narration());
        String json = new Gson().toJson(facts);
        assertTrue(json.contains("\"narration\":[\"line 3\",\"line 4\"]"), json);
        assertEquals(List.of(), new ForegroundTaskStatus("task", AgentTaskState.Status.running, "sort", 0, "", null).narration());
    }
}
