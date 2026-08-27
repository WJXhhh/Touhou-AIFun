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
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic local BM25/entity retrieval with no embedding or network dependency. */
public final class LocalMemoryRetriever {
    private static final double BM25_K1 = 1.2;
    private static final double BM25_B = 0.75;
    private static final Pattern STRUCTURED_ENTITY = Pattern.compile(
            "(?iu)(?<![\\p{L}\\p{N}_])(?:[\\p{L}\\p{N}]+[:_./#-][\\p{L}\\p{N}_:./#-]*|\\d+(?:[.-]\\d+)*)(?![\\p{L}\\p{N}_])");
    private static final Pattern LOW_INFORMATION = Pattern.compile(
            "(?iu)^(?:继续|然后呢?|接着|那个|这个|刚才那个|上次那个|就它|继续说|continue|go on|then|that one|it)[。！？!?,.， ]*$");
    private static final Map<MaidMemoryState, CachedEpisodeIndex> INDEX_CACHE = new WeakHashMap<>();

    private record EpisodeIndex(List<Map<String, Integer>> documents,
                                Map<String, Integer> documentFrequency, double averageLength) {
    }

    private record CachedEpisodeIndex(long fingerprint, EpisodeIndex index) {
    }

    private LocalMemoryRetriever() {
    }

    public static List<MemoryEpisode> topEpisodes(String query, List<MemoryEpisode> episodes,
                                                   List<OpenLoop> loops, long currentTurn, int limit) {
        return topEpisodes(query, episodes, loops, List.of(), currentTurn, limit);
    }

    public static List<MemoryEpisode> topEpisodes(String query, List<MemoryEpisode> episodes,
                                                   List<OpenLoop> loops, List<MemoryFact> facts,
                                                   long currentTurn, int limit) {
        return rank(query, episodes, loops, facts, currentTurn, limit, buildIndex(episodes));
    }

    public static List<MemoryEpisode> topEpisodes(String query, MaidMemoryState state,
                                                   long currentTurn, int limit) {
        if (state == null) return List.of();
        List<MemoryEpisode> episodes = state.episodes();
        long fingerprint = episodeFingerprint(episodes);
        EpisodeIndex index;
        synchronized (INDEX_CACHE) {
            CachedEpisodeIndex cached = INDEX_CACHE.get(state);
            if (cached == null || cached.fingerprint() != fingerprint) {
                cached = new CachedEpisodeIndex(fingerprint, buildIndex(episodes));
                INDEX_CACHE.put(state, cached);
            }
            index = cached.index();
        }
        return rank(query, episodes, state.openLoops(), state.facts(), currentTurn, limit, index);
    }

    private static List<MemoryEpisode> rank(String query, List<MemoryEpisode> episodes,
                                             List<OpenLoop> loops, List<MemoryFact> facts,
                                             long currentTurn, int limit, EpisodeIndex index) {
        if (StringUtils.isBlank(query) || episodes.isEmpty() || limit <= 0) return List.of();

        String expandedQuery = expandWithRelatedFacts(query, facts);
        Map<String, Integer> queryTerms = featureCounts(expandedQuery);
        if (queryTerms.isEmpty()) return List.of();

        List<Map<String, Integer>> documents = index.documents();
        Map<String, Integer> documentFrequency = index.documentFrequency();
        double averageLength = index.averageLength();

        double[] rawBm25 = new double[episodes.size()];
        double maxBm25 = 0;
        for (int i = 0; i < episodes.size(); i++) {
            rawBm25[i] = bm25(queryTerms.keySet(), documents.get(i), documentFrequency,
                    episodes.size(), averageLength);
            maxBm25 = Math.max(maxBm25, rawBm25[i]);
        }

        Map<MemoryEpisode, Double> scores = new HashMap<>();
        for (int i = 0; i < episodes.size(); i++) {
            MemoryEpisode episode = episodes.get(i);
            Map<String, Integer> candidate = documents.get(i);
            double normalizedBm25 = maxBm25 <= 0 ? 0 : rawBm25[i] / maxBm25;
            double entityBonus = exactEntities(expandedQuery,
                    episode.summary() + " " + String.join(" ", episode.keywords()));
            double textRelevance = normalizedBm25 * 4.0 + Math.min(6.0, entityBonus);
            if (textRelevance <= 0) continue;
            boolean relatedLoop = loops.stream().anyMatch(loop -> !loop.closed()
                    && overlap(tokens(loop.text()), candidate.keySet()) > 0);
            double loopBonus = relatedLoop ? 1.5 : 0;
            double relevance = textRelevance + loopBonus;

            double ageTurns = Math.max(0, currentTurn - maxTurn(episode));
            double recency = 1.0 / (1.0 + ageTurns / 16.0);
            scores.put(episode, relevance + episode.importance() + recency);
        }

        return scores.entrySet().stream()
                .sorted(Map.Entry.<MemoryEpisode, Double>comparingByValue().reversed()
                        .thenComparing(Map.Entry.<MemoryEpisode, Double>comparingByKey(
                                Comparator.comparingLong(MemoryEpisode::endGameTime).reversed()))
                        .thenComparing(e -> e.getKey().id()))
                .limit(limit)
                .map(Map.Entry::getKey)
                .toList();
    }

    private static EpisodeIndex buildIndex(List<MemoryEpisode> episodes) {
        List<Map<String, Integer>> documents = episodes.stream()
                .map(e -> Map.copyOf(featureCounts(e.summary() + " " + String.join(" ", e.keywords()))))
                .toList();
        Map<String, Integer> documentFrequency = new HashMap<>();
        documents.forEach(document -> document.keySet()
                .forEach(token -> documentFrequency.merge(token, 1, Integer::sum)));
        double averageLength = documents.stream().mapToInt(LocalMemoryRetriever::documentLength)
                .average().orElse(1.0);
        return new EpisodeIndex(List.copyOf(documents), Map.copyOf(documentFrequency), averageLength);
    }

    private static long episodeFingerprint(List<MemoryEpisode> episodes) {
        long hash = 0xcbf29ce484222325L;
        for (MemoryEpisode episode : episodes) {
            hash = (hash ^ episode.id().hashCode()) * 0x100000001b3L;
            hash = (hash ^ episode.summary().hashCode()) * 0x100000001b3L;
            hash = (hash ^ episode.keywords().hashCode()) * 0x100000001b3L;
        }
        return (hash ^ episodes.size()) * 0x100000001b3L;
    }

    /** Adds only facts already related to the explicit query, avoiding unrelated profile pollution. */
    private static String expandWithRelatedFacts(String query, List<MemoryFact> facts) {
        Set<String> queryTokens = tokens(query);
        StringBuilder expanded = new StringBuilder(query);
        facts.stream().filter(fact -> overlap(queryTokens, tokens(fact.text())) > 0)
                .sorted(Comparator.comparingInt(MemoryFact::importance).reversed())
                .limit(4).forEach(fact -> expanded.append(' ').append(fact.text()));
        return expanded.toString();
    }

    public static boolean isLowInformationQuery(String text) {
        if (StringUtils.isBlank(text)) return true;
        String normalized = text.strip();
        return normalized.codePointCount(0, normalized.length()) <= 1
                || LOW_INFORMATION.matcher(normalized).matches();
    }

    /** CJK uses bigrams/trigrams; Latin uses lowercase words; structured ids remain whole tokens. */
    public static Set<String> tokens(String text) {
        return Set.copyOf(featureCounts(text).keySet());
    }

    private static Map<String, Integer> featureCounts(String text) {
        if (StringUtils.isBlank(text)) return Map.of();
        String normalized = text.toLowerCase(Locale.ROOT);
        Map<String, Integer> result = new HashMap<>();
        Matcher structured = STRUCTURED_ENTITY.matcher(normalized);
        while (structured.find()) add(result, structured.group());
        List<String> atoms = new ArrayList<>();
        StringBuilder cjkRun = new StringBuilder();
        StringBuilder latin = new StringBuilder();
        for (int i = 0; i < normalized.length();) {
            int cp = normalized.codePointAt(i);
            i += Character.charCount(cp);
            if (isCjkLike(cp)) {
                flushLatin(latin, result);
                cjkRun.appendCodePoint(cp);
            } else if (Character.isLetterOrDigit(cp) || cp == '_') {
                flushCjk(cjkRun, atoms, result);
                latin.appendCodePoint(cp);
            } else {
                flushLatin(latin, result);
                flushCjk(cjkRun, atoms, result);
            }
        }
        flushLatin(latin, result);
        flushCjk(cjkRun, atoms, result);
        return result;
    }

    private static void flushLatin(StringBuilder builder, Map<String, Integer> result) {
        if (!builder.isEmpty()) {
            add(result, builder.toString());
            builder.setLength(0);
        }
    }

    private static void flushCjk(StringBuilder builder, List<String> atoms, Map<String, Integer> result) {
        if (builder.isEmpty()) return;
        atoms.clear();
        for (int i = 0; i < builder.length();) {
            int cp = builder.codePointAt(i);
            i += Character.charCount(cp);
            atoms.add(new String(Character.toChars(cp)));
        }
        if (atoms.size() == 1) add(result, atoms.get(0));
        for (int i = 0; i + 1 < atoms.size(); i++) add(result, atoms.get(i) + atoms.get(i + 1));
        for (int i = 0; i + 2 < atoms.size(); i++) add(result, atoms.get(i) + atoms.get(i + 1) + atoms.get(i + 2));
        builder.setLength(0);
    }

    private static void add(Map<String, Integer> result, String token) {
        if (!token.isBlank()) result.merge(token, 1, Integer::sum);
    }

    private static double bm25(Set<String> queryTerms, Map<String, Integer> document,
                               Map<String, Integer> df, int documentCount, double averageLength) {
        int length = documentLength(document);
        double score = 0;
        for (String token : queryTerms) {
            int tf = document.getOrDefault(token, 0);
            if (tf <= 0) continue;
            int frequency = Math.max(1, df.getOrDefault(token, 1));
            double idf = Math.log(1.0 + (documentCount - frequency + 0.5) / (frequency + 0.5));
            double denominator = tf + BM25_K1 * (1.0 - BM25_B + BM25_B * length / Math.max(1.0, averageLength));
            score += idf * tf * (BM25_K1 + 1.0) / denominator;
        }
        return score;
    }

    private static int documentLength(Map<String, Integer> document) {
        return document.values().stream().mapToInt(Integer::intValue).sum();
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
        Set<String> entities = new HashSet<>();
        Matcher matcher = STRUCTURED_ENTITY.matcher(query.toLowerCase(Locale.ROOT));
        while (matcher.find()) entities.add(matcher.group());
        String candidate = text.toLowerCase(Locale.ROOT);
        return (int) entities.stream().filter(candidate::contains).count() * 2;
    }

    private static long maxTurn(MemoryEpisode episode) {
        return episode.sourceTurnIds().stream().mapToLong(Long::longValue).max().orElse(0);
    }
}
