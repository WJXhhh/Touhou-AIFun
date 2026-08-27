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
        String combined = this.toolOutcomes.isEmpty()
                ? outcome.trim()
                : String.join("\n---\n", this.toolOutcomes) + "\n---\n" + outcome.trim();
        String normalized = shortenCodePoints(combined, 512);
        this.toolOutcomes.clear();
        this.toolOutcomes.add(normalized);
    }

    private static String shortenCodePoints(String value, int maxCodePoints) {
        int count = value.codePointCount(0, value.length());
        if (count <= maxCodePoints) return value;
        int half = Math.max(1, (maxCodePoints - 36) / 2);
        int headEnd = value.offsetByCodePoints(0, half);
        int tailStart = value.offsetByCodePoints(0, count - half);
        return value.substring(0, headEnd) + "\n...[tool outcomes shortened]...\n" + value.substring(tailStart);
    }
}
