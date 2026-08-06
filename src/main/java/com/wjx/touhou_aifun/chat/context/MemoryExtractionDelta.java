package com.wjx.touhou_aifun.chat.context;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;

/** Validated shape returned by the background memory extractor. */
public final class MemoryExtractionDelta {
    private final List<FactChange> factUpserts = new ArrayList<>();
    private final List<String> factDeletes = new ArrayList<>();
    private final List<LoopChange> loopUpserts = new ArrayList<>();
    private final List<String> loopCloses = new ArrayList<>();
    private String episodeSummary = "";
    private final List<String> keywords = new ArrayList<>();
    private int importance;

    public List<FactChange> factUpserts() { return factUpserts; }
    public List<String> factDeletes() { return factDeletes; }
    public List<LoopChange> loopUpserts() { return loopUpserts; }
    public List<String> loopCloses() { return loopCloses; }
    public String episodeSummary() { return episodeSummary == null ? "" : episodeSummary; }
    public List<String> keywords() { return keywords; }
    public int importance() { return Math.max(0, Math.min(3, importance)); }

    public boolean hasChanges() {
        return factUpserts.stream().anyMatch(change -> StringUtils.isNotBlank(change.text()))
                || factDeletes.stream().anyMatch(StringUtils::isNotBlank)
                || loopUpserts.stream().anyMatch(change -> StringUtils.isNotBlank(change.text()))
                || loopCloses.stream().anyMatch(StringUtils::isNotBlank)
                || StringUtils.isNotBlank(episodeSummary);
    }

    public void setEpisode(String summary, List<String> keywords, int importance) {
        this.episodeSummary = summary == null ? "" : summary;
        this.keywords.clear();
        if (keywords != null) this.keywords.addAll(keywords);
        this.importance = Math.max(0, Math.min(3, importance));
    }

    public record FactChange(String id, String kind, String text, int importance) { }
    public record LoopChange(String id, String text, int importance) { }
}
