package com.wjx.touhou_aifun.chat.context;

import java.util.ArrayList;
import java.util.List;

/** A small, attributed long-term fact extracted from conversation. */
public final class MemoryFact {
    private String id;
    private String kind;
    private String text;
    private int importance;
    private long lastConfirmedGameTime;
    private final List<Long> sourceTurnIds = new ArrayList<>();

    public MemoryFact() {
        // Gson constructor.
    }

    public MemoryFact(String id, String kind, String text, int importance, long gameTime, List<Long> sources) {
        this.id = id;
        this.kind = kind;
        this.text = text;
        this.importance = Math.max(0, Math.min(3, importance));
        this.lastConfirmedGameTime = gameTime;
        if (sources != null) {
            this.sourceTurnIds.addAll(sources);
        }
    }

    public String id() { return id == null ? "" : id; }
    public String kind() { return kind == null ? "fact" : kind; }
    public String text() { return text == null ? "" : text; }
    public int importance() { return Math.max(0, Math.min(3, importance)); }
    public long lastConfirmedGameTime() { return lastConfirmedGameTime; }
    public List<Long> sourceTurnIds() { return sourceTurnIds; }

    public void replace(String kind, String text, int importance, long gameTime, List<Long> sources) {
        this.kind = kind;
        this.text = text;
        this.importance = Math.max(0, Math.min(3, importance));
        this.lastConfirmedGameTime = gameTime;
        this.sourceTurnIds.clear();
        if (sources != null) this.sourceTurnIds.addAll(sources);
    }
}
