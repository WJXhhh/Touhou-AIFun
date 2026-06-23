package com.wjx.touhou_aifun.compat.ai.tts;

import java.util.ArrayList;
import java.util.List;

public final class SentenceTextSplitter {
    private static final String SENTENCE_ENDINGS = "。！？!?；;.，,、\n";
    private static final String TRAILING_MARKS = "”’」』）)]】\"'";
    /**
     * Bracket pairs that may wrap an emotion marker (e.g. {@code (委屈，抽泣)}). While inside such a
     * bracket the punctuation in {@code SENTENCE_ENDINGS} (notably the comma) must NOT start a new
     * sentence, otherwise a combined marker would be split mid-token (into {@code (委屈，} and
     * {@code 抽泣)}) and its emotion lost. Depth is tracked so only top-level punctuation ends a sentence.
     */
    private static final String OPEN_BRACKETS = "(（";
    private static final String CLOSE_BRACKETS = ")）";

    private SentenceTextSplitter() {
    }

    public static List<String> split(String text, int maxCodePoints) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }

        int start = 0;
        int index = 0;
        int codePoints = 0;
        int depth = 0;
        while (index < text.length()) {
            int codePoint = text.codePointAt(index);
            index += Character.charCount(codePoint);
            codePoints++;

            depth = updateBracketDepth(depth, codePoint);
            boolean sentenceEnd = depth == 0 && codePoint <= Character.MAX_VALUE
                    && SENTENCE_ENDINGS.indexOf((char) codePoint) >= 0;
            if (sentenceEnd) {
                while (index < text.length() && TRAILING_MARKS.indexOf(text.charAt(index)) >= 0) {
                    index++;
                    codePoints++;
                }
            }
            if (sentenceEnd || codePoints >= maxCodePoints) {
                addNonBlank(chunks, text.substring(start, index));
                start = index;
                codePoints = 0;
            }
        }
        addNonBlank(chunks, text.substring(start));
        return chunks;
    }

    /**
     * Index up to which {@code text} consists of <em>completed</em> sentences: the end of the last
     * sentence-ending (with trailing marks absorbed) or forced max-length cut. Everything after this
     * index is a still-incomplete trailing sentence. Mirrors {@link #split}'s boundary logic exactly so
     * a streaming caller can consume only completed sentences by character offset, instead of indexing
     * into a chunk list that is recomputed as the text grows.
     */
    public static int completePrefixLength(String text, int maxCodePoints) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int lastBoundary = 0;
        int index = 0;
        int codePoints = 0;
        int depth = 0;
        while (index < text.length()) {
            int codePoint = text.codePointAt(index);
            index += Character.charCount(codePoint);
            codePoints++;

            depth = updateBracketDepth(depth, codePoint);
            boolean sentenceEnd = depth == 0 && codePoint <= Character.MAX_VALUE
                    && SENTENCE_ENDINGS.indexOf((char) codePoint) >= 0;
            if (sentenceEnd) {
                while (index < text.length() && TRAILING_MARKS.indexOf(text.charAt(index)) >= 0) {
                    index++;
                    codePoints++;
                }
            }
            if (sentenceEnd || codePoints >= maxCodePoints) {
                lastBoundary = index;
                codePoints = 0;
            }
        }
        return lastBoundary;
    }

    /**
     * Returns the bracket nesting depth after consuming {@code codePoint}: an opening marker bracket
     * raises it, a closing one lowers it (never below zero, so stray closers are tolerated). Sentence
     * endings only count at depth zero, keeping a combined marker like {@code (委屈，抽泣)} intact.
     */
    private static int updateBracketDepth(int depth, int codePoint) {
        if (codePoint > Character.MAX_VALUE) {
            return depth;
        }
        char c = (char) codePoint;
        if (OPEN_BRACKETS.indexOf(c) >= 0) {
            return depth + 1;
        }
        if (CLOSE_BRACKETS.indexOf(c) >= 0 && depth > 0) {
            return depth - 1;
        }
        return depth;
    }

    private static void addNonBlank(List<String> chunks, String value) {
        if (!value.isBlank()) {
            chunks.add(value);
        }
    }
}
