package com.wjx.touhou_aifun.client.sound;

import net.minecraft.Util;

/**
 * Client-side "the player is talking" gate for maid speech.
 * <p>
 * When the player starts a voice turn (holding the STT key) the maid must stop talking
 * <em>immediately</em>: waiting for the transcribed text to open a new chat turn leaves the maid
 * speaking over the microphone for the whole recording. Pressing the key therefore drops everything
 * already queued/streaming, and this gate keeps dropping TTS audio that is still in flight from the
 * old reply until the player releases the key.
 * <p>
 * The mute is bounded by {@link #MAX_HOLD_MILLIS} so a lost key-release event (for example because
 * the player opened a screen while holding the key) can never silence the maid permanently.
 */
public final class SpeechMute {
    /** Safety cap for a single hold; ordinary push-to-talk recordings are far shorter. */
    static final long MAX_HOLD_MILLIS = 60_000L;

    private static volatile long mutedUntil;

    private SpeechMute() {
    }

    /** Starts the listening mute and immediately stops all TTS playback. */
    public static void beginListening() {
        begin(Util.getMillis());
        QueuedTTSPlaybackManager.interrupt();
        AIFunStreamSoundManager.interrupt();
    }

    /** Ends the listening mute (the player released the STT key). */
    public static void endListening() {
        mutedUntil = 0L;
    }

    /** True while incoming TTS audio must be dropped because the player is recording. */
    public static boolean muted() {
        return muted(Util.getMillis());
    }

    static void begin(long nowMillis) {
        mutedUntil = nowMillis > Long.MAX_VALUE - MAX_HOLD_MILLIS
                ? Long.MAX_VALUE : nowMillis + MAX_HOLD_MILLIS;
    }

    static boolean muted(long nowMillis) {
        return mutedUntil > 0L && nowMillis < mutedUntil;
    }
}
