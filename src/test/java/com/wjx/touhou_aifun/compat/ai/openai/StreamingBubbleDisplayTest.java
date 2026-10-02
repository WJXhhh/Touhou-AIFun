package com.wjx.touhou_aifun.compat.ai.openai;

import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StreamingBubbleDisplayTest {
    @Test void searchThenAnswerThenFinalizationLeavesOneFinalBubble() {
        FakeBubbles bubbles = new FakeBubbles();
        var display = new StreamingBubbleDisplay(bubbles);
        display.activity(Component.literal("Reasoning"));
        display.activity(Component.literal("Searching"));
        long searchId = bubbles.waiting;
        display.answer("Partial answer");
        assertFalse(bubbles.visible.containsKey(searchId));
        display.answer("Complete answer");
        assertEquals(1, bubbles.visible.size());
        bubbles.finish("Final answer");
        assertEquals(Map.of(bubbles.waiting, "Final answer"), bubbles.visible);
    }

    @Test void externallyReplacedWaitingBubbleCannotBeOrphanedByFirstStreamedAnswer() {
        FakeBubbles bubbles = new FakeBubbles();
        var display = new StreamingBubbleDisplay(bubbles);
        // This is the reported regression: another path refreshes the callback after display creation.
        bubbles.refreshWaiting(Component.literal("Searching"));
        long searchId = bubbles.waiting;
        display.answer("Answer");
        assertFalse(bubbles.visible.containsKey(searchId));
        assertEquals(Map.of(bubbles.waiting, "Answer"), bubbles.visible);
    }

    @Test void lateProgressCannotReplaceAnAnswerAndExternalRefreshIsRemovedOnNextUpdate() {
        FakeBubbles bubbles = new FakeBubbles();
        var display = new StreamingBubbleDisplay(bubbles);
        display.answer("First");
        display.activity(Component.literal("Late search event"));
        assertEquals(Map.of(bubbles.waiting, "First"), bubbles.visible);
        bubbles.refreshWaiting(Component.literal("External tool progress"));
        display.answer("Second");
        assertEquals(Map.of(bubbles.waiting, "Second"), bubbles.visible);
    }

    private static final class FakeBubbles implements StreamingBubbleDisplay.Bubbles {
        final Map<Long, String> visible = new LinkedHashMap<>();
        long waiting, next;
        FakeBubbles() { refreshWaiting(Component.literal("Waiting")); }
        @Override public long waitingId() { return waiting; }
        @Override public void refreshWaiting(Component text) { remove(waiting); waiting = ++next; visible.put(waiting, text.getString()); }
        @Override public void remove(long id) { visible.remove(id); }
        @Override public long addAnswer(String text) { long id = ++next; visible.put(id, text); return id; }
        @Override public boolean updateAnswer(long id, String text) {
            if (!visible.containsKey(id)) return false; visible.put(id, text); return true;
        }
        @Override public void selectWaiting(long id) { waiting = id; }
        void finish(String text) { remove(waiting); waiting = addAnswer(text); }
    }
}
