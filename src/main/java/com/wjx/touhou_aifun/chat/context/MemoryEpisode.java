package com.wjx.touhou_aifun.chat.context;

import java.util.ArrayList;
import java.util.List;

/** A compact, searchable record of older conversation. */
public final class MemoryEpisode {
    private String id;
    private String summary;
    private final List<String> keywords = new ArrayList<>();
    private int importance;
    private long startGameTime;
    private long endGameTime;
    private final List<Long> sourceTurnIds = new ArrayList<>();

    public MemoryEpisode() {
        // Gson constructor.
    }

    public MemoryEpisode(String id, String summary, List<String> keywords, int importance,
                         long startGameTime, long endGameTime, List<Long> sources) {
        this.id = id;
        this.summary = summary;
        this.importance = Math.max(0, Math.min(3, importance));
        this.startGameTime = startGameTime;
        this.endGameTime = endGameTime;
        if (keywords != null) this.keywords.addAll(keywords);
        if (sources != null) this.sourceTurnIds.addAll(sources);
    }

    public String id() { return id == null ? "" : id; }
    public String summary() { return summary == null ? "" : summary; }
    public List<String> keywords() { return keywords; }
    public int importance() { return Math.max(0, Math.min(3, importance)); }
    public long startGameTime() { return startGameTime; }
    public long endGameTime() { return endGameTime; }
    public List<Long> sourceTurnIds() { return sourceTurnIds; }
}
