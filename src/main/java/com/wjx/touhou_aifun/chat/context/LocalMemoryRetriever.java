package com.wjx.touhou_aifun.chat.context;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Deterministic local retrieval; it deliberately has no embedding/network dependency. */
public final class LocalMemoryRetriever {
    private LocalMemoryRetriever() {
    }

    public static List<MemoryEpisode> topEpisodes(String query, List<MemoryEpisode> episodes,
                                                  List<OpenLoop> loops, long currentTurn, int limit) {
        if (StringUtils.isBlank(query) || episodes.isEmpty()) return List.of();
        Map<MemoryEpisode, Double> scores = new HashMap<>();
        Map<String, Integer> documentFrequency = new HashMap<>();
        List<Set<String>> allTokens = episodes.stream().map(e -> tokens(e.summary() + " " + String.join(" ", e.keywords())))
                .toList();
        for (Set<String> tokens : allTokens) {
            tokens.forEach(token -> documentFrequency.merge(token, 1, Integer::sum));
        }
        Set<String> queryTokens = tokens(query);
        for (int i = 0; i < episodes.size(); i++) {
            MemoryEpisode episode = episodes.get(i);
            Set<String> candidate = allTokens.get(i);
            double overlap = 0;
            for (String token : queryTokens) {
                if (candidate.contains(token)) {
                    int df = Math.max(1, documentFrequency.getOrDefault(token, 1));
                    overlap += 1.0 + Math.log((episodes.size() + 1.0) / df);
                }
            }
            double normalized = queryTokens.isEmpty() ? 0 : overlap / queryTokens.size();
            double entityBonus = exactEntities(query, episode.summary());
            double loopBonus = loops.stream().anyMatch(loop -> !loop.closed()
                    && overlap(tokens(loop.text()), candidate) > 0) ? 1.5 : 0;
            double age = Math.max(0, currentTurn - maxTurn(episode));
            double recency = 1.0 / (1.0 + age / 16.0);
            double relevance = normalized * 4.0 + Math.min(6.0, entityBonus) + loopBonus;
            // A fresh but unrelated episode must not displace a genuinely matching old one.
            if (relevance <= 0) continue;
            double score = relevance + episode.importance() + recency;
            scores.put(episode, score);
        }
        return scores.entrySet().stream()
                .sorted(Map.Entry.<MemoryEpisode, Double>comparingByValue().reversed()
                        .thenComparingLong(e -> e.getKey().endGameTime())
                        .thenComparing(e -> e.getKey().id()))
                .limit(Math.max(0, limit))
                .map(Map.Entry::getKey)
                .toList();
    }

    public static Set<String> tokens(String text) {
        if (StringUtils.isBlank(text)) return Set.of();
        String normalized = text.toLowerCase(Locale.ROOT);
        Set<String> result = new HashSet<>();
        List<String> atoms = new ArrayList<>();
        StringBuilder cjkRun = new StringBuilder();
        StringBuilder latin = new StringBuilder();
        StringBuilder entity = new StringBuilder();
        for (int i = 0; i < normalized.length();) {
            int cp = normalized.codePointAt(i);
            i += Character.charCount(cp);
            if (cp < 128 && isEntityChar(cp)) {
                entity.appendCodePoint(cp);
                if (Character.isLetterOrDigit(cp)) {
                    latin.appendCodePoint(cp);
                } else {
                    flushLatin(latin, result);
                }
                flushCjk(cjkRun, atoms, result);
                continue;
            }
            if (isCjkLike(cp)) {
                flushEntity(entity, result);
                flushLatin(latin, result);
                cjkRun.appendCodePoint(cp);
                continue;
            }
            flushEntity(entity, result);
            flushLatin(latin, result);
            flushCjk(cjkRun, atoms, result);
            if (Character.isLetterOrDigit(cp) && cp < 128) {
                latin.appendCodePoint(cp);
            }
        }
        flushEntity(entity, result);
        flushLatin(latin, result);
        flushCjk(cjkRun, atoms, result);
        return result;
    }

    private static void flushLatin(StringBuilder builder, Set<String> result) {
        if (builder.length() > 0) {
            result.add(builder.toString());
            builder.setLength(0);
        }
    }

    private static void flushEntity(StringBuilder builder, Set<String> result) {
        if (builder.length() > 1) result.add(builder.toString());
        builder.setLength(0);
    }

    private static void flushCjk(StringBuilder builder, List<String> atoms, Set<String> result) {
        if (builder.isEmpty()) return;
        atoms.clear();
        for (int i = 0; i < builder.length();) {
            int cp = builder.codePointAt(i);
            i += Character.charCount(cp);
            String atom = new String(Character.toChars(cp));
            result.add(atom);
            atoms.add(atom);
        }
        for (int i = 0; i + 1 < atoms.size(); i++) result.add(atoms.get(i) + atoms.get(i + 1));
        for (int i = 0; i + 2 < atoms.size(); i++) {
            result.add(atoms.get(i) + atoms.get(i + 1) + atoms.get(i + 2));
        }
        builder.setLength(0);
    }

    private static boolean isEntityChar(int cp) {
        return Character.isLetterOrDigit(cp)
                || cp == '_' || cp == ':' || cp == '.' || cp == '-' || cp == '/' || cp == '#';
    }

    private static boolean isCjkLike(int cp) {
        return (cp >= 0x2E80 && cp <= 0x2FFF)
                || (cp >= 0x3000 && cp <= 0x30FF)
                || (cp >= 0x31A0 && cp <= 0x31BF)
                || (cp >= 0x3400 && cp <= 0x4DBF)
                || (cp >= 0x4E00 && cp <= 0x9FFF)
                || (cp >= 0xAC00 && cp <= 0xD7AF)
                || (cp >= 0xF900 && cp <= 0xFAFF)
                || (cp >= 0x1F000 && cp <= 0x1FAFF);
    }

    private static double overlap(Set<String> left, Set<String> right) {
        if (left.isEmpty()) return 0;
        long count = left.stream().filter(right::contains).count();
        return (double) count / left.size();
    }

    private static int exactEntities(String query, String text) {
        Set<String> entities = tokens(query).stream()
                .filter(t -> t.length() > 1 && t.chars().allMatch(c -> c < 128 && (Character.isLetterOrDigit(c)
                        || c == '_' || c == ':' || c == '.' || c == '-' || c == '/' || c == '#'))
                )
                .collect(Collectors.toSet());
        return (int) entities.stream().filter(text.toLowerCase(Locale.ROOT)::contains).count() * 2;
    }

    private static long maxTurn(MemoryEpisode episode) {
        return episode.sourceTurnIds().stream().mapToLong(Long::longValue).max().orElse(0);
    }
}
