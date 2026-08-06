package com.wjx.touhou_aifun.chat.context;

import java.util.ArrayList;
import java.util.List;

/** A durable, user-visible conversation turn owned by one maid. */
public final class ConversationTurn {
    public enum Status {
        PENDING,
        COMPLETED,
        INTERRUPTED
    }

    private long turnId;
    private String userText;
    private String assistantText = "";
    private Status status = Status.PENDING;
    private long gameTime;
    private final List<String> toolOutcomes = new ArrayList<>();

    public ConversationTurn() {
        // Gson constructor.
    }

    public ConversationTurn(long turnId, String userText, long gameTime) {
        this.turnId = turnId;
        this.userText = userText == null ? "" : userText;
        this.gameTime = gameTime;
    }

    public long turnId() {
        return turnId;
    }

    public String userText() {
        return userText == null ? "" : userText;
    }

    public String assistantText() {
        return assistantText == null ? "" : assistantText;
    }

    public Status status() {
        return status == null ? Status.INTERRUPTED : status;
    }

    public long gameTime() {
        return gameTime;
    }

    public List<String> toolOutcomes() {
        return toolOutcomes;
    }

    public void complete(String text) {
        this.assistantText = text == null ? "" : text;
        this.status = Status.COMPLETED;
    }

    public void interrupt() {
        if (this.status == Status.PENDING) {
            this.status = Status.INTERRUPTED;
        }
    }

    public void addToolOutcome(String outcome) {
        if (outcome == null || outcome.isBlank()) {
            return;
        }
        String normalized = outcome.length() <= 512
                ? outcome
                : outcome.substring(0, 256) + "\n...[tool result shortened]...\n"
                + outcome.substring(outcome.length() - 256);
        this.toolOutcomes.add(normalized);
        while (this.toolOutcomes.size() > 12) {
            this.toolOutcomes.remove(0);
        }
    }
}
