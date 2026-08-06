package com.wjx.touhou_aifun.chat.context;

import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;

import java.util.List;

/** Conservative, dependency-free token estimate used before provider tokenizers run. */
public final class ContextTokenEstimator {
    private ContextTokenEstimator() {
    }

    public static int estimate(String text) {
        if (text == null || text.isEmpty()) return 8;
        int cjk = 0;
        int asciiRun = 0;
        int asciiTokens = 0;
        int other = 0;
        for (int i = 0; i < text.length();) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isWhitespace(cp)) {
                asciiTokens += asciiRun == 0 ? 0 : (int) Math.ceil(asciiRun / 4.0);
                asciiRun = 0;
            } else if (isCjk(cp) || isEmoji(cp)) {
                asciiTokens += asciiRun == 0 ? 0 : (int) Math.ceil(asciiRun / 4.0);
                asciiRun = 0;
                cjk++;
            } else if (Character.isLetterOrDigit(cp) && cp < 128) {
                asciiRun++;
            } else {
                asciiTokens += asciiRun == 0 ? 0 : (int) Math.ceil(asciiRun / 4.0);
                asciiRun = 0;
                other++;
            }
        }
        asciiTokens += asciiRun == 0 ? 0 : (int) Math.ceil(asciiRun / 4.0);
        double raw = cjk * 1.5 + asciiTokens + other * 0.75 + 8;
        return Math.max(1, (int) Math.ceil(raw * 1.15));
    }

    public static int estimate(List<LLMMessage> messages) {
        int total = 0;
        for (LLMMessage message : messages) {
            total += estimate(message.message());
        }
        return total;
    }

    private static boolean isCjk(int cp) {
        return (cp >= 0x2E80 && cp <= 0x2FFF)
                || (cp >= 0x3000 && cp <= 0x30FF)
                || (cp >= 0x31A0 && cp <= 0x31BF)
                || (cp >= 0x3400 && cp <= 0x4DBF)
                || (cp >= 0x4E00 && cp <= 0x9FFF)
                || (cp >= 0xAC00 && cp <= 0xD7AF)
                || (cp >= 0xF900 && cp <= 0xFAFF);
    }

    private static boolean isEmoji(int cp) {
        return (cp >= 0x1F000 && cp <= 0x1FAFF)
                || (cp >= 0x2600 && cp <= 0x27BF);
    }
}
