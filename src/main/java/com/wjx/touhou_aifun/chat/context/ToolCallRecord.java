package com.wjx.touhou_aifun.chat.context;

/**
 * One compact record of a tool call made by the maid. The full raw result stays in the callback /
 * archive; this is the queryable 100-character history used by {@code read_tool_history}.
 */
public final class ToolCallRecord {
    private String tool = "";
    private String summary = "";
    private String status = "";
    private long gameTime;

    public ToolCallRecord() {
        // Gson constructor.
    }

    public ToolCallRecord(String tool, String summary, String status, long gameTime) {
        this.tool = tool == null ? "" : tool;
        this.summary = summary == null ? "" : summary;
        this.status = status == null ? "" : status;
        this.gameTime = gameTime;
    }

    public String tool() { return tool == null ? "" : tool; }
    public String summary() { return summary == null ? "" : summary; }
    public String status() { return status == null ? "" : status; }
    public long gameTime() { return gameTime; }
}
