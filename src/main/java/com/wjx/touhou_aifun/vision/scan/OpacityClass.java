package com.wjx.touhou_aifun.vision.scan;

/** Conservative transparency classes used by the weighted visibility budget. */
public enum OpacityClass {
    OPAQUE(16),
    LOW(8),
    MEDIUM(4),
    HIGH(2),
    THIN(1);

    private final int cost;

    OpacityClass(int cost) {
        this.cost = cost;
    }

    public int cost() {
        return cost;
    }
}
