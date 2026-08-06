package com.wjx.touhou_aifun.chat.context;

import java.util.ArrayList;
import java.util.List;

/** Mutable per-maid state owned by the addon and serialized independently from TLM history. */
public final class MaidMemoryState {
    public static final int SCHEMA_VERSION = 1;

    private int schemaVersion = SCHEMA_VERSION;
    private long nextTurnId;
    private long revision;
    private final List<ConversationTurn> turns = new ArrayList<>();
    private final List<MemoryFact> facts = new ArrayList<>();
    private final List<OpenLoop> openLoops = new ArrayList<>();
    private final List<MemoryEpisode> episodes = new ArrayList<>();
    private final List<TokenCalibration> tokenCalibrations = new ArrayList<>();
    private int extractionFailureStreak;
    private int extractionRetryAfterTurns;

    public MaidMemoryState() {
        // Gson constructor.
    }

    public int schemaVersion() { return schemaVersion; }
    public long revision() { return revision; }
    public List<ConversationTurn> turns() { return turns; }
    public List<MemoryFact> facts() { return facts; }
    public List<OpenLoop> openLoops() { return openLoops; }
    public List<MemoryEpisode> episodes() { return episodes; }
    public List<TokenCalibration> tokenCalibrations() { return tokenCalibrations; }
    public int extractionFailureStreak() { return extractionFailureStreak; }
    public int extractionRetryAfterTurns() { return extractionRetryAfterTurns; }

    public long nextTurnId() {
        return ++nextTurnId;
    }

    public void touch() {
        revision++;
    }

    public void markExtractionFailure() {
        extractionFailureStreak = Math.min(4, extractionFailureStreak + 1);
        extractionRetryAfterTurns = 1 << Math.max(0, extractionFailureStreak - 1);
    }

    public void markExtractionSuccess() {
        extractionFailureStreak = 0;
        extractionRetryAfterTurns = 0;
    }

    public void onCompletedTurn() {
        if (extractionRetryAfterTurns > 0) extractionRetryAfterTurns--;
    }

    public record TokenCalibration(String apiType, String model, double factor) {
        public TokenCalibration normalized() {
            double value = Math.max(0.75, Math.min(2.0, factor));
            return new TokenCalibration(apiType == null ? "" : apiType,
                    model == null ? "" : model, value);
        }
    }
}
