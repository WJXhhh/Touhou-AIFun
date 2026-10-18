package com.wjx.touhou_aifun.client.sound;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpeechMuteTest {
    @AfterEach
    void release() {
        SpeechMute.endListening();
    }

    @Test
    void muteStaysActiveForTheWholeHoldAndThenExpires() {
        SpeechMute.begin(1_000L);
        assertTrue(SpeechMute.muted(1_000L));
        assertTrue(SpeechMute.muted(1_000L + SpeechMute.MAX_HOLD_MILLIS - 1));
        assertFalse(SpeechMute.muted(1_000L + SpeechMute.MAX_HOLD_MILLIS));
    }

    @Test
    void releasingTheSttKeyUnmutesImmediately() {
        SpeechMute.begin(1_000L);
        SpeechMute.endListening();
        assertFalse(SpeechMute.muted(1_000L));
        assertFalse(SpeechMute.muted(1_000L + 1));
    }

    @Test
    void muteIsInactiveBeforeTheFirstHold() {
        assertFalse(SpeechMute.muted(0L));
        assertFalse(SpeechMute.muted(Long.MAX_VALUE));
    }

    @Test
    void safetyCapSaturatesInsteadOfWrappingIntoThePast() {
        SpeechMute.begin(Long.MAX_VALUE - 10L);
        assertTrue(SpeechMute.muted(Long.MAX_VALUE - 1L));
    }
}
