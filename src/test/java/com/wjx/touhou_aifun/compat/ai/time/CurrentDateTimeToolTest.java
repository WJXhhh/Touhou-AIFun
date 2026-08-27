package com.wjx.touhou_aifun.compat.ai.time;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CurrentDateTimeToolTest {
    @Test
    void keepsMandatorySelectionRuleInsideSchemaSummaryLimit() {
        String summary = new CurrentDateTimeTool().summary(null);
        assertTrue(summary.startsWith("MUST call"));
        assertTrue(summary.contains("Never guess"));
        assertTrue(summary.codePointCount(0, summary.length()) <= 180);
    }

    @Test
    void acceptsAnEmptyFunctionArgumentObject() {
        assertTrue(new CurrentDateTimeTool().codec().parse(JsonOps.INSTANCE, new JsonObject())
                .result().isPresent());
    }

    @Test
    void formatsServerTimeWithExplicitZoneAndEpoch() {
        JsonObject result = JsonParser.parseString(CurrentDateTimeTool.format(
                Instant.parse("2026-08-27T06:30:45Z"), ZoneId.of("Asia/Shanghai"))).getAsJsonObject();

        assertEquals("2026-08-27", result.get("date").getAsString());
        assertEquals("14:30:45", result.get("time").getAsString());
        assertEquals("THURSDAY", result.get("day_of_week").getAsString());
        assertEquals("Asia/Shanghai", result.get("timezone").getAsString());
        assertEquals("+08:00", result.get("utc_offset").getAsString());
        assertEquals(1787812245L, result.get("unix_epoch_seconds").getAsLong());
    }
}
