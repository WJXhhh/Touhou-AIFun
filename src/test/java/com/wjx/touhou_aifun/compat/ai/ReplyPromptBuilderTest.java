package com.wjx.touhou_aifun.compat.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReplyPromptBuilderTest {
    @Test
    void hiddenCrossLanguageMarksOnlyTheSpokenTranslationInBothPrompts() {
        String contract = ReplyPromptBuilder.contract("zh_cn", "en_us", true, false, true);
        String reminder = ReplyPromptBuilder.reminder("zh_cn", "en_us", true, false);
        for (String prompt : new String[]{contract, reminder}) {
            assertTrue(prompt.contains("exactly TWO sections"));
            assertTrue(prompt.contains("Part 1 has NO emotion markers"));
            assertTrue(prompt.contains("English (United States)"));
            assertFalse(prompt.contains("Both sections start"));
        }
    }

    @Test
    void sameLanguageHiddenModeStillProducesOneMarkedBody() {
        for (String prompt : new String[]{
                ReplyPromptBuilder.contract("zh_cn", "zh_cn", true, false, true),
                ReplyPromptBuilder.reminder("zh_cn", "zh_cn", true, false)}) {
            assertTrue(prompt.contains("ONE reply"));
            assertTrue(prompt.contains("hides markers from display automatically"));
            assertFalse(prompt.contains("Part 2 is"));
        }
    }

    @Test
    void disabledEmotionStillRemindsAboutCurrentLanguageFormat() {
        String prompt = ReplyPromptBuilder.reminder("zh_cn", "en_us", false, false);
        assertTrue(prompt.contains("exactly TWO sections"));
        assertTrue(prompt.contains("No TTS emotion markers"));
        assertFalse(prompt.contains("starts with an ASCII"));
    }

    @Test
    void stepAudio3GuidanceDoesNotLeakIntoOlderProviders() {
        assertTrue(ReplyPromptBuilder.contract("zh_cn", "zh_cn", true, true, true)
                .contains("StepAudio 3 delivery"));
        assertFalse(ReplyPromptBuilder.contract("zh_cn", "zh_cn", true, true, false)
                .contains("StepAudio 3 delivery"));
        assertFalse(ReplyPromptBuilder.contract("zh_cn", "zh_cn", false, true, true)
                .contains("StepAudio 3 delivery"));
    }
}
