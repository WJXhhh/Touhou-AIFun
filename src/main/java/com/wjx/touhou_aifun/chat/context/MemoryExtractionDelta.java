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
    private final List<EpisodeChange> episodes = new ArrayList<>();
    private String environment = "";

    public List<FactChange> factUpserts() { return factUpserts; }
    public List<String> factDeletes() { return factDeletes; }
    public List<LoopChange> loopUpserts() { return loopUpserts; }
    public List<String> loopCloses() { return loopCloses; }
    public String episodeSummary() { return episodeSummary == null ? "" : episodeSummary; }
    public List<String> keywords() { return keywords; }
    public int importance() { return Math.max(0, Math.min(3, importance)); }
    /** Multi-episode digest for long batches; each episode covers one chronological/thematic chunk. */
    public List<EpisodeChange> episodes() { return episodes; }
    public String environment() { return environment == null ? "" : environment; }

    public boolean hasChanges() {
        return factUpserts.stream().anyMatch(change -> StringUtils.isNotBlank(change.text()))
                || factDeletes.stream().anyMatch(StringUtils::isNotBlank)
                || loopUpserts.stream().anyMatch(change -> StringUtils.isNotBlank(change.text()))
                || loopCloses.stream().anyMatch(StringUtils::isNotBlank)
                || StringUtils.isNotBlank(episodeSummary)
                || episodes.stream().anyMatch(change -> StringUtils.isNotBlank(change.summary()))
                || StringUtils.isNotBlank(environment);
    }

    public void addEpisode(String summary, List<String> keywords, int importance) {
        if (StringUtils.isBlank(summary)) return;
        List<String> copy = new ArrayList<>();
        if (keywords != null) copy.addAll(keywords);
        episodes.add(new EpisodeChange(summary, copy, Math.max(0, Math.min(3, importance))));
    }

    public void setEnvironment(String environment) {
        this.environment = environment == null ? "" : environment;
    }

    public void setEpisode(String summary, List<String> keywords, int importance) {
        this.episodeSummary = summary == null ? "" : summary;
        this.keywords.clear();
        if (keywords != null) this.keywords.addAll(keywords);
        this.importance = Math.max(0, Math.min(3, importance));
    }

    public record FactChange(String id, String kind, String text, int importance) { }
    public record LoopChange(String id, String text, int importance) { }
    public record EpisodeChange(String summary, List<String> keywords, int importance) { }
}
