package com.wjx.touhou_aifun.maid.gui;

/** Task-scoped cumulative budget, retained across menu reopen and successive wait calls. */
public final class GuiWaitBudget {
    private long consumed;
    private long limit = Long.MAX_VALUE;
    private long explicitLimit = Long.MAX_VALUE;
    private boolean configured;
    private boolean estimateFixed;
    public void configure(GuiWaitPolicy policy, long estimate, long serverLimit, long playerLimit) {
        if (playerLimit > 0) explicitLimit = Math.min(explicitLimit, playerLimit);
        long selected = switch (policy) {
            case NO_WAIT -> 0;
            case UNTIL_GOAL -> serverLimit;
            case AUTO -> estimate < 0 ? 600 : estimate > 1200 ? 0
                    : Math.max(300, Math.min(1800, estimate + estimate / 2 + 100));
        };
        // Repairs/reopens cannot recharge this budget. Explicitly changing AUTO to UNTIL_GOAL
        // is permitted only in a new user turn, which owns a new budget.
        if (!configured || policy == GuiWaitPolicy.AUTO && !estimateFixed && estimate >= 0) {
            limit = Math.min(selected, explicitLimit);
            configured = true;
            estimateFixed = estimate >= 0;
        } else limit = Math.min(limit, Math.min(serverLimit, explicitLimit));
    }
    public void advance(long ticks) { consumed += Math.max(0, ticks); }
    public long consumed() { return consumed; }
    public long remaining() { return Math.max(0, limit - consumed); }
    public boolean exhausted() { return remaining() == 0; }
}
