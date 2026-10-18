package com.wjx.touhou_aifun.chat.freechat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Deterministic guard against a maid's own TTS being picked up by the microphone and starting a
 * reply loop. Free chat intentionally keeps capturing while TTS plays, so the decision service must
 * never see "the maid repeating herself" as a fresh player utterance.
 */
public final class SelfEchoMatcher {
    private static final int MIN_COMPARABLE_CODE_POINTS = 6;
    private static final double MIN_DICE = 0.8D;

    private SelfEchoMatcher() {
    }

    public static boolean matches(String transcript, List<String> recentReplies) {
        String heard = normalize(transcript);
        if (heard.codePointCount(0, heard.length()) < MIN_COMPARABLE_CODE_POINTS
                || recentReplies == null || recentReplies.isEmpty()) {
            return false;
        }
        for (String reply : recentReplies) {
            String spoken = normalize(reply);
            if (spoken.codePointCount(0, spoken.length()) < MIN_COMPARABLE_CODE_POINTS) {
                continue;
            }
            if (spoken.contains(heard) || heard.contains(spoken)) {
                return true;
            }
            if (dice(heard, spoken) >= MIN_DICE) {
                return true;
            }
        }
        return false;
    }

    public static String normalize(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        StringBuilder result = new StringBuilder(text.length());
        for (int offset = 0; offset < text.length(); ) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isLetterOrDigit(codePoint)) {
                result.appendCodePoint(Character.toLowerCase(codePoint));
            }
        }
        return result.toString().toLowerCase(Locale.ROOT);
    }

    public static double dice(String left, String right) {
        if (left == null || right == null || left.length() < 2 || right.length() < 2) {
            return 0.0D;
        }
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < left.length() - 1; i++) {
            counts.merge(left.substring(i, i + 2), 1, Integer::sum);
        }
        int overlap = 0;
        int rightBigrams = right.length() - 1;
        for (int i = 0; i < right.length() - 1; i++) {
            String bigram = right.substring(i, i + 2);
            Integer count = counts.get(bigram);
            if (count != null && count > 0) {
                counts.put(bigram, count - 1);
                overlap++;
            }
        }
        int leftBigrams = left.length() - 1;
        return (2.0D * overlap) / (leftBigrams + rightBigrams);
    }

    /** Small helper for the gate; keeps the most recent non-blank replies first. */
    public static List<String> nonBlank(List<String> replies) {
        if (replies == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String reply : replies) {
            if (reply != null && !reply.isBlank()) {
                result.add(reply);
            }
        }
        return List.copyOf(result);
    }
}
