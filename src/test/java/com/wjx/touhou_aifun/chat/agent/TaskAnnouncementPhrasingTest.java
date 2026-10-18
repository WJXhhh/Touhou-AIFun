package com.wjx.touhou_aifun.chat.agent;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TaskAnnouncementPhrasingTest {
    @Test void completionPromptKeepsTheLineShortAndWithoutTechnicalFacts() {
        String[] prompt = TaskAnnouncementPhrasing.buildPrompt(true, "put five iron into the north chest", 2,
                "zh_cn", "zh_cn", "温柔的小女仆", "[\"Owner: 帮我放铁\",\"Maid: 好呀\"]");
        assertTrue(prompt[0].contains("ONE short line"), prompt[0]);
        assertTrue(prompt[0].contains("Output only the line"), prompt[0]);
        assertTrue(prompt[0].contains("Persona excerpt"), prompt[0]);
        assertTrue(prompt[0].contains("温柔的小女仆"), prompt[0]);
        assertTrue(prompt[0].contains("Recent conversation"), prompt[0]);
        assertTrue(prompt[0].contains("next natural"), prompt[0]);
        assertTrue(prompt[0].contains("Never mention task ids, queue positions, counts, model/provider errors"), prompt[0]);
        assertTrue(prompt[1].contains("completed"), prompt[1]);
        assertTrue(prompt[1].contains("put five iron into the north chest"), prompt[1]);
        assertTrue(prompt[1].contains("waiting_tasks"), prompt[1]);
    }

    @Test void differentChatAndTtsLanguagesAskForJsonTranslation() {
        String[] prompt = TaskAnnouncementPhrasing.buildPrompt(true, "sort chests", 0,
                "zh_cn", "en_us", "", "");
        assertTrue(prompt[0].contains("Return JSON only"), prompt[0]);
        assertTrue(prompt[0].contains("chat is the displayed line in zh_cn"), prompt[0]);
        assertTrue(prompt[0].contains("translated into en_us"), prompt[0]);
        assertFalse(prompt[0].contains("Persona excerpt"), prompt[0]);
    }

    @Test void failurePromptNeverReceivesTheTechnicalOutcome() {
        String[] prompt = TaskAnnouncementPhrasing.buildPrompt(false, "sort the labelled chests", 0,
                "en_us", "en_us", "", "");
        assertTrue(prompt[0].contains("failed: sound natural and light"), prompt[0]);
        assertTrue(prompt[0].contains("Never claim success"), prompt[0]);
        assertTrue(prompt[1].contains("failed"), prompt[1]);
        assertFalse(prompt[1].contains("outcome"), "Technical outcome stays in the log, not in the phrasing request");
        assertFalse(prompt[1].contains("reason"), "Failure reason is only stated when the owner asks");
        assertTrue(prompt[1].contains("sort the labelled chests"), prompt[1]);
    }

    @Test void answerSplitsIntoDisplayedAndSpokenText() {
        String[] same = TaskAnnouncementPhrasing.splitLanguages("{\"chat\":\"好耶，做完啦～\",\"tts\":\"ignored\"}",
                "fallback", "zh_cn", "zh_cn");
        assertEquals("好耶，做完啦～", same[0]);
        assertEquals("好耶，做完啦～", same[1], "Same language speaks the displayed line");
        String[] translated = TaskAnnouncementPhrasing.splitLanguages("{\"chat\":\"搞定啦\",\"tts\":\"All done~\"}",
                "fallback", "zh_cn", "en_us");
        assertEquals("搞定啦", translated[0]);
        assertEquals("All done~", translated[1]);
        String[] plain = TaskAnnouncementPhrasing.splitLanguages("搞定啦", "fallback", "zh_cn", "en_us");
        assertEquals("搞定啦", plain[0]);
        assertEquals("搞定啦", plain[1], "A plain answer is still spoken when no translation exists");
        String[] broken = TaskAnnouncementPhrasing.splitLanguages("{not json", "fallback", "zh_cn", "zh_cn");
        assertEquals("{not json", broken[0]);
        assertEquals("{not json", broken[1]);
    }

    @Test void modelAnswerIsCollapsedToASingleSafeLine() {
        assertEquals("好耶，做完啦～", TaskAnnouncementPhrasing.usableOrFallback("  \"好耶，做完啦～\"\n第二行", "fallback"));
        assertEquals("搞定啦", TaskAnnouncementPhrasing.usableOrFallback("**搞定啦**", "fallback"));
        assertEquals("fallback", TaskAnnouncementPhrasing.usableOrFallback("   \n\t", "fallback"));
        assertEquals("fallback", TaskAnnouncementPhrasing.usableOrFallback(null, "fallback"));
        String longAnswer = "x".repeat(400);
        assertEquals(120, TaskAnnouncementPhrasing.usableOrFallback(longAnswer, "fallback").length());
        String emoji = "好".repeat(119) + "😀";
        assertEquals(emoji, TaskAnnouncementPhrasing.usableOrFallback(emoji, "fallback"),
                "Bounding keeps whole code points");
    }

    @Test void languageRootsDecideWhetherTheLineNeedsTranslation() {
        assertTrue(TaskAnnouncementPhrasing.sameLanguage("zh_cn", "zh_tw"));
        assertTrue(TaskAnnouncementPhrasing.sameLanguage("en_us", "en_gb"));
        assertFalse(TaskAnnouncementPhrasing.sameLanguage("zh_cn", "en_us"));
    }
}
