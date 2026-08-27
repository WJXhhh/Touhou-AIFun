package com.wjx.touhou_aifun.chat.context;

import org.apache.commons.lang3.StringUtils;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Conservative, deterministic handling for explicit corrections and cancellations. It never
 * invents a new fact; it only hides stale extracted records until the normal background extractor
 * can learn the replacement from recent verbatim turns.
 */
public final class ImmediateMemoryReconciler {
    private static final Set<String> QUERY_STOP_TOKENS = Set.of(
            "用户", "玩家", "女仆", "我的", "我不", "不要", "不是", "其实", "改成", "更正", "纠正",
            "忘掉", "别记", "记住", "完成", "完了", "已经", "取消", "不用", "算了", "喜欢", "不喜",
            "这个", "那个", "事情", "东西", "这样", "那样", "是这", "是那", "而是",
            "player", "user", "maid", "my", "the", "this", "that", "please", "forget", "remember",
            "actually", "correction", "correct", "cancel", "done", "completed", "like", "dislike");

    private ImmediateMemoryReconciler() {
    }

    public record Result(int factsRemoved, int loopsClosed, int episodesRemoved) {
        public boolean changed() {
            return factsRemoved > 0 || loopsClosed > 0 || episodesRemoved > 0;
        }
    }

    public static Result reconcile(MaidMemoryState state, String userText, long gameTime) {
        if (state == null || StringUtils.isBlank(userText)) return new Result(0, 0, 0);
        String normalized = userText.toLowerCase(Locale.ROOT).replaceAll("<context>[\\s\\S]*?</context>", " ");
        boolean forget = isExplicitForget(normalized);
        boolean correction = isCorrection(normalized);
        boolean close = isExplicitClosure(normalized);
        if (!forget && !correction && !close) return new Result(0, 0, 0);

        Set<String> queryTokens = significantTokens(removeControlPhrases(normalized));
        int factsBefore = state.facts().size();
        int episodesBefore = state.episodes().size();

        if (forget || correction) {
            state.facts().removeIf(fact -> matches(queryTokens, fact.text()));
        }
        if (forget) {
            state.episodes().removeIf(episode -> matches(queryTokens,
                    episode.summary() + " " + String.join(" ", episode.keywords())));
        }

        int loopsClosed = 0;
        if (forget || close) {
            var open = state.openLoops().stream().filter(loop -> !loop.closed()).toList();
            for (OpenLoop loop : open) {
                if (matches(queryTokens, loop.text()) || (queryTokens.isEmpty() && open.size() == 1 && close)) {
                    loop.close(gameTime);
                    loopsClosed++;
                }
            }
        }

        Result result = new Result(factsBefore - state.facts().size(), loopsClosed,
                episodesBefore - state.episodes().size());
        if (result.changed()) state.touch();
        return result;
    }

    private static boolean isExplicitForget(String text) {
        if (text.contains("不要忘记") || text.contains("别忘记")
                || text.contains("don't forget") || text.contains("do not forget")) return false;
        return text.contains("忘掉") || text.contains("从记忆中删除") || text.contains("不要记住")
                || text.contains("别记住") || text.matches(".*\\bforget\\b.*");
    }

    private static boolean isCorrection(String text) {
        boolean explicitReplacement = text.matches("(?s).*不是.{1,80}(?:[，,、;； ]|而)是.*");
        return explicitReplacement || text.contains("改成") || text.contains("更正") || text.contains("纠正")
                || text.contains("其实") || text.contains("不再") || text.contains("不喜欢") || text.contains("不爱")
                || text.matches(".*\\bactually\\b.*") || text.matches(".*\\bcorrection\\b.*")
                || text.matches(".*\\bno longer\\b.*") || text.matches(".*\\bdon't like\\b.*")
                || text.matches(".*\\bdo not like\\b.*");
    }

    private static boolean isExplicitClosure(String text) {
        return text.contains("已经完成") || text.contains("做完了") || text.contains("完成了")
                || text.contains("不用了") || text.contains("取消") || text.contains("算了")
                || text.matches(".*\\b(done|completed|cancel|cancelled|no longer needed)\\b.*");
    }

    private static Set<String> significantTokens(String text) {
        Set<String> tokens = new HashSet<>();
        for (String token : LocalMemoryRetriever.tokens(text)) {
            int length = token.codePointCount(0, token.length());
            if (length < 2 || QUERY_STOP_TOKENS.contains(token)) continue;
            if (token.chars().allMatch(Character::isDigit)) continue;
            tokens.add(token);
        }
        return tokens;
    }

    private static String removeControlPhrases(String text) {
        return text.replace("已经完成了", " ").replace("已经完成", " ").replace("做完了", " ")
                .replace("完成了", " ").replace("不用了", " ").replace("取消", " ").replace("算了", " ")
                .replace("从记忆中删除", " ").replace("不要记住", " ").replace("别记住", " ").replace("忘掉", " ")
                .replace("其实", " ").replace("不是", " ").replace("改成", " ").replace("更正", " ")
                .replace("纠正", " ").replace("不再", " ").replace("不喜欢", " ").replace("不爱", " ")
                .replaceAll("\\b(no longer needed|no longer|do not like|don't like|actually|correction|correct|"
                        + "forget|completed|cancelled|cancel|done)\\b", " ");
    }

    private static boolean matches(Set<String> queryTokens, String candidateText) {
        if (queryTokens.isEmpty() || StringUtils.isBlank(candidateText)) return false;
        Set<String> candidate = LocalMemoryRetriever.tokens(candidateText);
        return queryTokens.stream().anyMatch(candidate::contains);
    }
}
