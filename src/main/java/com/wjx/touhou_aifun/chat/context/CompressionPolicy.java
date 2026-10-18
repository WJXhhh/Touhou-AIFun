package com.wjx.touhou_aifun.chat.context;

/**
 * Budget-driven compression timing for the layered memory.
 *
 * <p>The old design compressed after a fixed number of completed turns. On a 1M-class model that
 * throws away useful history far too early. Instead, the visible context may grow until the actual
 * context budget is under pressure:</p>
 *
 * <ul>
 *   <li>below 70%: never compress;</li>
 *   <li>at 70%: compress only when the current work is hard (unfinished task/goal) or the next
 *       turn would clearly not fit;</li>
 *   <li>at 90%: compress unconditionally;</li>
 *   <li>after a compression pass the caller keeps the visible memory below its target
 *       (default 15K estimated tokens).</li>
 * </ul>
 */
public final class CompressionPolicy {
    public static final double SOFT_RATIO = 0.70;
    public static final double HARD_RATIO = 0.90;
    public static final int DEFAULT_TARGET_TOKENS = 15_000;

    private CompressionPolicy() {
    }

    public enum Decision {
        NONE,
        COMPRESS,
        FORCE
    }

    public record Assessment(Decision decision, int estimatedTokens, int budgetTokens, double ratio,
                             int projectedNextTurnTokens, boolean taskHard, String reason) {
        public boolean shouldCompress() {
            return decision != Decision.NONE;
        }
    }

    /**
     * @param estimatedTokens         estimated visible memory (history + facts + episodes + loops)
     * @param budgetTokens            effective input budget after tool/image reserves
     * @param taskHard                unfinished task/goal, or a large tool-loop turn is in progress
     * @param projectedNextTurnTokens estimated size of a typical next turn
     */
    public static Assessment assess(int estimatedTokens, int budgetTokens, boolean taskHard,
                                    int projectedNextTurnTokens) {
        int budget = Math.max(1, budgetTokens);
        int estimated = Math.max(0, estimatedTokens);
        int projected = Math.max(0, projectedNextTurnTokens);
        double ratio = estimated / (double) budget;

        if (ratio >= HARD_RATIO) {
            return new Assessment(Decision.FORCE, estimated, budget, ratio, projected, taskHard,
                    "hard_limit");
        }
        if (ratio >= SOFT_RATIO) {
            boolean nextTurnWouldNotFit = estimated + projected >= budget * HARD_RATIO;
            if (taskHard || nextTurnWouldNotFit) {
                return new Assessment(Decision.COMPRESS, estimated, budget, ratio, projected, taskHard,
                        taskHard ? "soft_limit_task_hard" : "soft_limit_budget_insufficient");
            }
            return new Assessment(Decision.NONE, estimated, budget, ratio, projected, taskHard,
                    "soft_limit_not_urgent");
        }
        return new Assessment(Decision.NONE, estimated, budget, ratio, projected, taskHard,
                "below_soft_limit");
    }

    /** Post-compression target: configured, but never larger than half of the input budget. */
    public static int targetTokens(int configuredTarget, int budgetTokens) {
        int configured = configuredTarget <= 0 ? DEFAULT_TARGET_TOKENS : configuredTarget;
        int ceiling = Math.max(2048, budgetTokens / 2);
        return Math.max(2048, Math.min(configured, ceiling));
    }

    /** Budget available to the memory layer after the provider-side reserves. */
    public static int effectiveBudget(int inputBudgetTokens, int reserveTokens) {
        return Math.max(2048, Math.max(0, inputBudgetTokens) - Math.max(0, reserveTokens));
    }

    public static int compressionBatchLimit(int budgetTokens) {
        // A compression pass may summarize most of the visible history, but an extraction request
        // must still be bounded and must fit common 1M-class models with the system prompt attached.
        return Math.max(8192, Math.min(400_000, (int) (Math.max(1, budgetTokens) * 0.85)));
    }
}
