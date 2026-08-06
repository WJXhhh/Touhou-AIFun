package com.wjx.touhou_aifun.chat.context;

import java.util.ArrayList;
import java.util.List;

/** A promise, unresolved question, or user task that should survive chat compaction. */
public final class OpenLoop {
    private String id;
    private String text;
    private boolean closed;
    private int importance;
    private long updatedGameTime;
    private final List<Long> sourceTurnIds = new ArrayList<>();

    public OpenLoop() {
        // Gson constructor.
    }

    public OpenLoop(String id, String text, int importance, long gameTime, List<Long> sources) {
        this.id = id;
        this.text = text;
        this.importance = Math.max(0, Math.min(3, importance));
        this.updatedGameTime = gameTime;
        if (sources != null) this.sourceTurnIds.addAll(sources);
    }

    public String id() { return id == null ? "" : id; }
    public String text() { return text == null ? "" : text; }
    public boolean closed() { return closed; }
    public int importance() { return Math.max(0, Math.min(3, importance)); }
    public long updatedGameTime() { return updatedGameTime; }
    public List<Long> sourceTurnIds() { return sourceTurnIds; }

    public void replace(String text, int importance, long gameTime, List<Long> sources) {
        this.text = text;
        this.importance = Math.max(0, Math.min(3, importance));
        this.updatedGameTime = gameTime;
        this.closed = false;
        this.sourceTurnIds.clear();
        if (sources != null) this.sourceTurnIds.addAll(sources);
    }

    public void close(long gameTime) {
        this.closed = true;
        this.updatedGameTime = gameTime;
    }
}
