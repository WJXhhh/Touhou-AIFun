package com.wjx.touhou_aifun.compat.ai.openai;

import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.Role;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChatGPTSearchSourceMemoryTest {
    @Test void sourcesAreRequestedExplicitlyAndCanBeDeclined() {
        for (String question : List.of("来源呢？", "出处是什么？", "在哪查的？", "给我看看引用", "联网查查天气并给出来源",
                "提供参考资料", "给我链接", "sources?", "Show me the sources", "Where did you find that?"))
            assertTrue(ChatGPTSearchSourceMemory.requested(question), question);
        for (String question : List.of("今天的天气怎么样？", "帮我找歌词然后唱出来", "能源来源于太阳", "不用给出处",
                "别发链接", "不要引用", "Answer without citations", "不要说明来源，直接回答"))
            assertFalse(ChatGPTSearchSourceMemory.requested(question), question);
        assertTrue(ChatGPTSearchSourceMemory.requested("不用长篇大论，给出来源"));
    }

    @Test void onlyTheLatestPlayerQuestionControlsVisibility() {
        var messages = List.of(new LLMMessage(Role.USER, "给出来源", 1),
                new LLMMessage(Role.ASSISTANT, "Sources?", 2),
                new LLMMessage(Role.USER, "<context>参考链接在哪里？</context>\n今天的天气怎么样？", 3),
                new LLMMessage(Role.TOOL, "Show me the sources", 4));
        String question = ChatGPTSearchSourceMemory.latestQuestion(messages);
        assertEquals("今天的天气怎么样？", question);
        assertFalse(ChatGPTSearchSourceMemory.requested(question));
        assertEquals("", ChatGPTSearchSourceMemory.latestQuestion(List.of()));
    }

    @Test void cacheIsPerMaidAndOldTurnsCannotReplaceNewEvidence() {
        var memory = new ChatGPTSearchSourceMemory();
        Object first = new Object(), second = new Object();
        var sources = new ArrayList<>(List.of(new ChatGPTSearchSources.Source("Forecast", "https://example.com/weather")));
        memory.remember(first, 2, "今天的天气怎么样？", sources);
        sources.clear();
        assertEquals(1, memory.sources(first).size());
        assertTrue(memory.sources(second).isEmpty());
        memory.remember(first, 1, "Old question", List.of());
        assertEquals(1, memory.sources(first).size());
        String reminder = memory.reminder(first, true);
        assertTrue(reminder.contains("https://example.com/weather"));
        assertTrue(reminder.contains("without searching again"));
        assertTrue(reminder.contains("attach the available source links separately"));
        assertEquals("", memory.reminder(second, true));
        // A new completed search with no evidence must not show an unrelated old source.
        memory.remember(first, 3, "Another search", List.of());
        assertTrue(memory.sources(first).isEmpty());
    }
}
