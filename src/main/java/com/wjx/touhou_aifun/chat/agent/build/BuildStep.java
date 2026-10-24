package com.wjx.touhou_aifun.chat.agent.build;

/**
 * One persisted build instruction. Gson-friendly public fields: a build step is durable data, not a
 * live action object. Coordinates are absolute world coordinates after the design call converts
 * them from the requested origin/coordinate space.
 */
public class BuildStep {
    public int index;
    /** place | mine | interact */
    public String kind = "place";
    public int x;
    public int y;
    public int z;
    /** Full block state string, e.g. minecraft:oak_stairs[facing=north]; empty for mine means air. */
    public String block = "";
    /** Exact item id used for place/interact; empty means the block's BlockItem for place. */
    public String item = "";
    /** Exact tool item id for mine; empty means best available tool. */
    public String tool = "";
    /** Preferred support/hit face: up|down|north|south|east|west. */
    public String face = "";
    public boolean strict;
    public boolean required = true;
    public String note = "";

    public BuildStep() {
    }

    public BuildStep(int index, String kind, int x, int y, int z) {
        this.index = index;
        this.kind = kind == null || kind.isBlank() ? "place" : kind.trim();
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public String kindOrPlace() {
        return kind == null || kind.isBlank() ? "place" : kind.trim();
    }

    /** Stable key used when states are remapped across a reorder. */
    public String positionKey() {
        return kindOrPlace() + ":" + x + "," + y + "," + z;
    }
}
