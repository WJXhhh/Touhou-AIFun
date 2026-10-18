package com.wjx.touhou_aifun.chat.context;

/**
 * A durable, structured world location the maid has actually used or observed. Unlike a free-form
 * {@link MemoryFact}, this survives the lexical extractor and keeps exact coordinates, so a furnace
 * or chest that was opened yesterday is not treated as an unknown block again today.
 */
public final class LandmarkPlace {
    private String dimension = "";
    private String blockId = "";
    private int x;
    private int y;
    private int z;
    private String label = "";
    private long lastSeenGameTime;
    private int hitCount;
    private String source = "";

    public LandmarkPlace() {
        // Gson constructor.
    }

    public LandmarkPlace(String dimension, String blockId, int x, int y, int z, String label,
                         long gameTime, String source) {
        this.dimension = dimension == null ? "" : dimension;
        this.blockId = blockId == null ? "" : blockId;
        this.x = x;
        this.y = y;
        this.z = z;
        this.label = label == null ? "" : label;
        this.lastSeenGameTime = gameTime;
        this.hitCount = 1;
        this.source = source == null ? "" : source;
    }

    public String dimension() { return dimension == null ? "" : dimension; }
    public String blockId() { return blockId == null ? "" : blockId; }
    public int x() { return x; }
    public int y() { return y; }
    public int z() { return z; }
    public String label() { return label == null ? "" : label; }
    public long lastSeenGameTime() { return lastSeenGameTime; }
    public int hitCount() { return Math.max(1, hitCount); }
    public String source() { return source == null ? "" : source; }

    public boolean hasIdentity() {
        return !dimension().isBlank() && !blockId().isBlank();
    }

    public boolean samePosition(String otherDimension, int otherX, int otherY, int otherZ) {
        return dimension().equals(otherDimension) && x == otherX && y == otherY && z == otherZ;
    }

    public void seen(String newBlockId, String newLabel, long gameTime, String newSource) {
        if (newBlockId != null && !newBlockId.isBlank()) blockId = newBlockId;
        if (newLabel != null && !newLabel.isBlank()) label = newLabel;
        if (newSource != null && !newSource.isBlank()) source = newSource;
        lastSeenGameTime = gameTime;
        hitCount = hitCount + 1;
    }

    /** Structural equality used by tests; Gson serializes the fields directly. */
    @Override
    public String toString() {
        return dimension() + " " + blockId() + " @ " + x + "," + y + "," + z;
    }
}
