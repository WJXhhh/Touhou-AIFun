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
    private final List<LandmarkPlace> landmarks = new ArrayList<>();
    private final List<ToolCallRecord> toolCalls = new ArrayList<>();
    private final List<TokenCalibration> tokenCalibrations = new ArrayList<>();
    private String environmentSummary = "";
    private String environmentDimension = "";
    private long environmentGameTime;
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
    /** Durable block positions (containers, workstations) outside the free-text fact pipeline. */
    public List<LandmarkPlace> landmarks() { return landmarks; }
    /** Bounded newest-first history of tool calls with a <=100 code point operation summary. */
    public List<ToolCallRecord> toolCalls() { return toolCalls; }
    public String environmentSummary() { return environmentSummary == null ? "" : environmentSummary; }
    public String environmentDimension() { return environmentDimension == null ? "" : environmentDimension; }
    public long environmentGameTime() { return environmentGameTime; }
    public List<TokenCalibration> tokenCalibrations() { return tokenCalibrations; }
    public int extractionFailureStreak() { return extractionFailureStreak; }
    public int extractionRetryAfterTurns() { return extractionRetryAfterTurns; }

    public long nextTurnId() {
        return ++nextTurnId;
    }

    public void touch() {
        revision++;
    }

    /** Environment summary is a single rolling note, not an append-only list. */
    public void setEnvironment(String dimension, String summary, long gameTime) {
        String value = summary == null ? "" : summary.trim();
        this.environmentSummary = value;
        this.environmentDimension = dimension == null ? "" : dimension;
        this.environmentGameTime = gameTime;
        touch();
    }

    public void appendToolCall(ToolCallRecord record) {
        ToolCallHistory.append(toolCalls, record);
        touch();
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

    public record TokenCalibration(String apiType, String model, double factor, int estimatorVersion) {
        public TokenCalibration(String apiType,String model,double factor) {
            this(apiType,model,factor,ContextTokenEstimator.VERSION);
        }
        public TokenCalibration normalized() {
            double value = Math.max(0.75, Math.min(2.0, factor));
            return new TokenCalibration(apiType == null ? "" : apiType,
                    model == null ? "" : model, value, estimatorVersion);
        }
    }
}
