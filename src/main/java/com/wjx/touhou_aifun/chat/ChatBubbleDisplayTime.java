package com.wjx.touhou_aifun.chat;

import com.github.tartaricacid.touhoulittlemaid.entity.chatbubble.IChatBubbleData;

/**
 * Computes how long a maid's reply chat bubble should stay up, scaled to the reply length so a long
 * reply stays on screen long enough to read.
 * <p>
 * The duration is a monotonically non-decreasing function of the text length ("only increases"): more
 * text is always given at least as much time, never less. It is also floored at the vanilla default so
 * short replies keep the familiar duration, and capped so an extreme reply cannot hang forever.
 */
public final class ChatBubbleDisplayTime {
    private static final int TICKS_PER_SECOND = 20;
    /** Roughly comfortable reading speed across mixed Chinese/English text. */
    private static final double CHARS_PER_SECOND = 5.0;
    /** Lead time so the reader notices the bubble before its text scrolls away. */
    private static final int PADDING_SECONDS = 3;
    /** Upper bound, so a very long reply does not linger indefinitely. */
    private static final int MAX_SECONDS = 120;

    private ChatBubbleDisplayTime() {
    }

    /**
     * @param text the rendered bubble text (display text, not the spoken TTS text)
     * @return the bubble lifetime in ticks, never below {@link IChatBubbleData#DEFAULT_EXIST_TICK}
     */
    public static int existTickFor(String text) {
        int length = text == null ? 0 : text.codePointCount(0, text.length());
        int seconds = (int) Math.ceil(length / CHARS_PER_SECOND) + PADDING_SECONDS;
        int ticks = seconds * TICKS_PER_SECOND;
        // Only-increase: clamp into [vanilla default, cap] so it grows with length yet never shrinks
        // below the familiar duration or past the upper bound.
        return Math.max(IChatBubbleData.DEFAULT_EXIST_TICK, Math.min(ticks, MAX_SECONDS * TICKS_PER_SECOND));
    }
}
