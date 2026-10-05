package com.wjx.touhou_aifun.chat;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ToolBatchProgressTest {
    @Test void allowsChangedStateForIdenticalCalls() {
        var progress = new ToolBatchProgress();
        assertFalse(progress.begin("move"));
        progress.result("{\"position\":1}");
        assertFalse(progress.begin("move"));
        progress.result("{\"position\":2}");
        assertTrue(progress.begin("move"));
        progress.result("{\"position\":3}");
        assertTrue(progress.begin("move"));
    }

    @Test void ignoresTimestampsAndJsonKeyOrderButKeepsRealState() {
        var progress = new ToolBatchProgress();
        progress.begin("scan");
        progress.result("{\"game_tick\":1,\"state\":{\"b\":2,\"a\":1},\"timestamp\":10}");
        progress.begin("scan");
        progress.result("{\"timestamp\":20,\"state\":{\"a\":1,\"b\":2},\"game_tick\":2}");
        assertFalse(progress.begin("scan"));
        progress.result("{\"state\":{\"a\":2,\"b\":2}}");
        assertTrue(progress.begin("scan"));
    }

    @Test void repeatedFailuresAndMissingResultsDoNotResetGuard() {
        var progress = new ToolBatchProgress();
        progress.begin("use");
        for (int i = 0; i < 20; i++) {
            progress.result("Cannot reach target");
            assertFalse(progress.begin("use"));
        }
        assertFalse(progress.begin("use"));
    }

    @Test void batchComparisonDoesNotConfuseDifferentToolsWithProgress() {
        var progress = new ToolBatchProgress();
        progress.begin("a+b");
        progress.result("ok"); progress.result("error");
        assertFalse(progress.begin("a+b"));
        progress.result("ok"); progress.result("error");
        assertFalse(progress.begin("a+b"));
        progress.result("ok"); progress.result("done");
        assertTrue(progress.begin("a+b"));
        assertFalse(progress.begin("other"));
    }
}
