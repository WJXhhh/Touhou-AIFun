package com.wjx.touhou_aifun.compat.ai.openai;

import net.minecraft.network.chat.Component;

/** Server-thread display state. The callback always owns the current waiting-bubble handle. */
final class StreamingBubbleDisplay {
    interface Bubbles {
        long waitingId();
        void refreshWaiting(Component text);
        void remove(long id);
        long addAnswer(String text);
        boolean updateAnswer(long id, String text);
        void selectWaiting(long id);
    }

    private final Bubbles bubbles;
    private long answerId = -1;

    StreamingBubbleDisplay(Bubbles bubbles) { this.bubbles = bubbles; }

    void activity(Component text) {
        if (answerId < 0) bubbles.refreshWaiting(text);
    }

    void answer(String text) {
        long current = bubbles.waitingId();
        if (answerId >= 0 && bubbles.updateAnswer(answerId, text)) {
            if (current != answerId) bubbles.remove(current);
        } else {
            bubbles.remove(current);
            if (answerId >= 0 && answerId != current) bubbles.remove(answerId);
            answerId = bubbles.addAnswer(text);
        }
        bubbles.selectWaiting(answerId);
    }
}
