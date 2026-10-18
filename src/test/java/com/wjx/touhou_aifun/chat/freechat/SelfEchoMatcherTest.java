package com.wjx.touhou_aifun.chat.freechat;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelfEchoMatcherTest {
    @Test
    void fullReplyEchoIsDetected() {
        assertTrue(SelfEchoMatcher.matches("你好呀，今天过得怎么样？",
                List.of("你好呀，今天过得怎么样")));
    }

    @Test
    void longContainedFragmentIsDetected() {
        assertTrue(SelfEchoMatcher.matches("今天过得怎么样",
                List.of("你好呀，今天过得怎么样？要不要一起出去走走")));
    }

    @Test
    void unrelatedSpeechIsNotEcho() {
        assertFalse(SelfEchoMatcher.matches("帮我把箱子里的苹果拿出来",
                List.of("你好呀，今天过得怎么样")));
    }

    @Test
    void veryShortHeardTextIsNotJudgedAsEcho() {
        assertFalse(SelfEchoMatcher.matches("嗯", List.of("嗯")));
    }

    @Test
    void normalizationRemovesPunctuationWhitespaceAndCase() {
        assertEquals("helloworld", SelfEchoMatcher.normalize("Hello, World!"));
        assertEquals("灵梦你好", SelfEchoMatcher.normalize("灵梦，你好！"));
    }

    @Test
    void diceIsSymmetricAndBounded() {
        double score = SelfEchoMatcher.dice("abcdef", "abcxef");
        assertTrue(score > 0.0D && score < 1.0D);
        assertEquals(score, SelfEchoMatcher.dice("abcxef", "abcdef"), 0.0001D);
        assertEquals(1.0D, SelfEchoMatcher.dice("abc", "abc"), 0.0001D);
    }
}
