package com.wjx.touhou_aifun.vision;

public enum VisionCapabilityMode {
    AUTO, SUPPORTED, UNSUPPORTED;

    public VisionCapabilityMode next() {
        return values()[(ordinal() + 1) % values().length];
    }
}
