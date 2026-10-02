package com.wjx.touhou_aifun.compat.ai.chatgpt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChatGPTReasoningSettingsTest {
    @Test void defaultsRequestSummaryWithoutOverridingModelEffort() {
        var options = ChatGPTReasoningSettings.DEFAULT.requestOptions("gpt-6.1-sol");
        assertEquals("auto", options.get("summary").getAsString());
        assertFalse(options.has("effort"));
        assertEquals(0, new ChatGPTReasoningSettings(false, "default").requestOptions("gpt-6.1-sol").size());
    }

    @Test void summaryAndEffortAreIndependentPreferences() {
        var options = new ChatGPTReasoningSettings(false, "high").requestOptions("gpt-6.1-sol");
        assertEquals("high", options.get("effort").getAsString());
        assertFalse(options.has("summary"));
    }

    @Test void disablingReasoningUsesLowestSupportedEffortForAstraAnd61Sol() {
        for (String model : new String[] { "gpt-6-astra", "gpt-6.1-sol", "gpt-6.1-sol-2026-09-29" }) {
            var options = new ChatGPTReasoningSettings(true, "none").requestOptions(model);
            assertEquals("low", options.get("effort").getAsString());
            assertEquals("auto", options.get("summary").getAsString());
        }
        var off = new ChatGPTReasoningSettings(true, "none").requestOptions("gpt-6-sol");
        assertEquals("none", off.get("effort").getAsString());
        assertFalse(off.has("summary"));
    }

    @Test void maximumEffortFollowsEachModelsSupportedRange() {
        for (String model : new String[] { "gpt-6.1-sol", "gpt-6-sol", "gpt-6-luna", "gpt-5.6-sol" })
            assertEquals("max", new ChatGPTReasoningSettings(true, "max").requestOptions(model).get("effort").getAsString());
        assertEquals("xhigh", new ChatGPTReasoningSettings(true, "max").requestOptions("gpt-5.5").get("effort").getAsString());
        assertEquals("high", new ChatGPTReasoningSettings(true, "max").requestOptions("gpt-5.1").get("effort").getAsString());
    }

    @Test void unknownStoredEffortFallsBackAndNonReasoningModelsOmitParameters() {
        assertEquals("default", new ChatGPTReasoningSettings(true, "not-valid").effort());
        assertEquals(0, new ChatGPTReasoningSettings(true, "high").requestOptions("gpt-4.1").size());
    }
}
