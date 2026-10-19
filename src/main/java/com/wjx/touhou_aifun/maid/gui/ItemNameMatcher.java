package com.wjx.touhou_aifun.maid.gui;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Pure fuzzy matcher between an owner's item phrase and registered item ids/display names.
 *
 * <p>It exists because speech recognition and casual pack language rarely produce an exact
 * {@code namespace:path}. The matcher never touches the registry itself, so the ranking rules stay
 * unit-testable; {@link RegisteredItemSearch} feeds it live registry entries.</p>
 */
public final class ItemNameMatcher {
    public record Candidate(String id, String displayName) {
    }

    public record Match(Candidate candidate, int score) {
    }

    private ItemNameMatcher() {
    }

    public static List<Match> rank(List<Candidate> candidates, String query, int limit) {
        if (candidates == null || candidates.isEmpty() || limit <= 0) {
            return List.of();
        }
        String normalizedQuery = normalize(query);
        if (normalizedQuery.isEmpty()) {
            return List.of();
        }
        List<Match> matches = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (candidate == null || candidate.id() == null) {
                continue;
            }
            int score = score(normalizedQuery, candidate);
            if (score > 0) {
                matches.add(new Match(candidate, score));
            }
        }
        matches.sort((left, right) -> {
            int byScore = Integer.compare(right.score(), left.score());
            return byScore != 0 ? byScore : left.candidate().id().compareTo(right.candidate().id());
        });
        if (matches.size() > limit) {
            return List.copyOf(matches.subList(0, limit));
        }
        return List.copyOf(matches);
    }

    private static int score(String query, Candidate candidate) {
        String id = normalize(candidate.id());
        String path = normalize(path(candidate.id()));
        String name = normalize(candidate.displayName());
        if (query.equals(id) || query.equals(path)) {
            return 1000;
        }
        if (!name.isEmpty() && query.equals(name)) {
            return 950;
        }
        if (name.length() >= 2 && query.contains(name)) {
            return 800 + Math.min(50, name.length());
        }
        if (query.length() >= 2 && name.contains(query)) {
            return 780 + Math.min(50, query.length());
        }
        if (path.length() >= 3 && (query.contains(path) || path.contains(query))) {
            return 650 + Math.min(50, path.length());
        }
        double nameDice = dice(query, name);
        if (name.length() >= 2 && nameDice >= 0.55D) {
            return 500 + (int) Math.round(200.0D * nameDice);
        }
        double idDice = dice(query, path);
        if (path.length() >= 3 && idDice >= 0.6D) {
            return 400 + (int) Math.round(200.0D * idDice);
        }
        return 0;
    }

    private static String path(String id) {
        int colon = id == null ? -1 : id.indexOf(':');
        return colon >= 0 ? id.substring(colon + 1) : id;
    }

    /** Lowercase and drop separators/punctuation; CJK characters and alphanumerics remain. */
    static String normalize(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length());
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isLetterOrDigit(codePoint)) {
                out.appendCodePoint(Character.toLowerCase(codePoint));
            }
        }
        return out.toString().toLowerCase(Locale.ROOT);
    }

    /** Dice coefficient on character bigrams; short strings fall back to character sets. */
    static double dice(String left, String right) {
        if (left.isEmpty() || right.isEmpty()) {
            return 0.0D;
        }
        if (left.length() < 2 || right.length() < 2) {
            return left.equals(right) ? 1.0D : 0.0D;
        }
        Set<String> leftBigrams = bigrams(left);
        Set<String> rightBigrams = bigrams(right);
        if (leftBigrams.isEmpty() || rightBigrams.isEmpty()) {
            return 0.0D;
        }
        int common = 0;
        for (String bigram : leftBigrams) {
            if (rightBigrams.contains(bigram)) {
                common++;
            }
        }
        return 2.0D * common / (double) (leftBigrams.size() + rightBigrams.size());
    }

    private static Set<String> bigrams(String value) {
        Set<String> result = new LinkedHashSet<>();
        for (int i = 0; i + 1 < value.length(); i++) {
            result.add(value.substring(i, i + 2));
        }
        return result;
    }
}
