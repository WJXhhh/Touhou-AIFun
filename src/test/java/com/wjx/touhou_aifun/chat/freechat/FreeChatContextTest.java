package com.wjx.touhou_aifun.chat.freechat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FreeChatContextTest {
    @Test
    void ambientFactsAreInsertedInsideTheLatestContextBlock() {
        String attached = FreeChatContext.attach(
                "<context>speaker facts</context>\n你好", "ambient scene");
        assertTrue(attached.contains("speaker facts"));
        assertTrue(attached.contains("ambient scene"));
        assertTrue(attached.indexOf("ambient scene") < attached.indexOf("</context>"));
        assertTrue(attached.endsWith("你好"));
    }

    @Test
    void ambientFactsCreateAContextBlockWhenNoneExists() {
        String attached = FreeChatContext.attach("你好", "ambient scene");
        assertEquals("<context>\nambient scene</context>\n你好", attached);
    }

    @Test
    void ordinaryTextTurnsAreUnchanged() {
        assertEquals("你好", FreeChatContext.attach("你好", null));
        assertEquals("你好", FreeChatContext.attach("你好", ""));
        assertFalse(FreeChatContext.attach("你好", "  ").contains("ambient"));
    }
}
