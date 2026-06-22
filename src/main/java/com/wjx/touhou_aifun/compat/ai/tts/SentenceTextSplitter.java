package com.wjx.touhou_aifun.compat.ai.tts;

import java.util.ArrayList;
import java.util.List;

public final class SentenceTextSplitter {
    private static final String SENTENCE_ENDINGS = "。！？!?；;.\n";
    private static final String TRAILING_MARKS = "”’」』）)]】\"'";

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
        while (index < text.length()) {
            int codePoint = text.codePointAt(index);
            index += Character.charCount(codePoint);
            codePoints++;

            boolean sentenceEnd = codePoint <= Character.MAX_VALUE
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

    private static void addNonBlank(List<String> chunks, String value) {
        if (!value.isBlank()) {
            chunks.add(value);
        }
    }
}
