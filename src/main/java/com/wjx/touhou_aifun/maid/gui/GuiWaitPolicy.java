package com.wjx.touhou_aifun.maid.gui;

import java.util.Locale;

/** Intent supplied by the model; explicit user intent always takes precedence. */
public enum GuiWaitPolicy {
    NO_WAIT, AUTO, UNTIL_GOAL;

    public static GuiWaitPolicy parse(String value) {
        return valueOf(value == null ? "AUTO" : value.toUpperCase(Locale.ROOT));
    }
}
