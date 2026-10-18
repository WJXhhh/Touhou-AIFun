package com.wjx.touhou_aifun.client.voice;

import javax.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

/**
 * Energy-gated utterance segmenter. It deliberately does not decide whether speech is meaningful:
 * it only separates "there is sound above the gate" from silence, so the JEV/Perplexity decision
 * service remains the semantic filter for every non-empty transcript.
 */
public final class VadSegmenter {
    public record Config(int sampleRate, int frameMillis, int onsetFrames, int silenceFrames,
                         int minSpeechFrames, int maxSpeechFrames, int prerollFrames,
                         int maxTrailingFrames) {
        public static Config fromMillis(int sampleRate, int silenceMillis, int minSpeechMillis,
                                        int maxUtteranceMillis, int prerollMillis) {
            int frameMillis = 20;
            int onsetFrames = Math.max(1, 60 / frameMillis);
            int silenceFrames = Math.max(1, silenceMillis / frameMillis);
            int minSpeechFrames = Math.max(1, minSpeechMillis / frameMillis);
            int maxSpeechFrames = Math.max(minSpeechFrames + 1, maxUtteranceMillis / frameMillis);
            int prerollFrames = Math.max(0, prerollMillis / frameMillis);
            return new Config(sampleRate, frameMillis, onsetFrames, silenceFrames, minSpeechFrames,
                    maxSpeechFrames, prerollFrames, Math.max(0, 200 / frameMillis));
        }

        public int frameBytes() {
            return sampleRate * frameMillis / 1000 * 2;
        }
    }

    public record Segment(byte[] pcm, int speechMillis, int peak, int frames) {
    }

    private final Config config;
    private final Deque<byte[]> preroll = new ArrayDeque<>();
    private final int prerollCapacity;

    private ByteArrayOutputStream current;
    private boolean speaking;
    private int onsetCount;
    private int silenceCount;
    private int voicedFrames;
    private int totalFrames;
    private int peak;
    private int pendingPeak;

    public VadSegmenter(Config config) {
        this.config = config;
        this.prerollCapacity = Math.max(1, config.prerollFrames() + config.onsetFrames() + 2);
    }

    /**
     * Consumes exactly one frame. {@code startThreshold} may be raised while TTS is playing.
     *
     * @return a completed segment, or {@code null} when the frame is only noise/silence or the
     * utterance continues.
     */
    @Nullable
    public Segment accept(byte[] frame, int startThreshold) {
        if (frame == null || frame.length == 0) {
            return null;
        }
        int threshold = Math.max(1, startThreshold);
        int stopThreshold = Math.max(1, (int) Math.round(threshold * 0.65D));
        int level = rms(frame);
        pendingPeak = Math.max(pendingPeak, level);

        if (!speaking) {
            pushPreroll(frame);
            if (level >= threshold) {
                onsetCount++;
            } else {
                onsetCount = 0;
            }
            if (onsetCount < config.onsetFrames()) {
                return null;
            }
            begin();
            return null;
        }

        write(frame);
        totalFrames++;
        if (level >= stopThreshold) {
            silenceCount = 0;
            voicedFrames++;
        } else {
            silenceCount++;
        }
        peak = Math.max(peak, level);

        if (silenceCount >= config.silenceFrames() || totalFrames >= config.maxSpeechFrames()) {
            return finish();
        }
        return null;
    }

    public boolean isSpeaking() {
        return speaking;
    }

    public void discard() {
        reset();
    }

    private void begin() {
        speaking = true;
        current = new ByteArrayOutputStream(Math.max(config.frameBytes(), preroll.size() * config.frameBytes()));
        for (byte[] frame : preroll) {
            current.write(frame, 0, frame.length);
        }
        // The onset frames themselves are voiced; preroll only contributes the configured leading
        // context to the WAV, not to the minimum speech duration.
        voicedFrames = Math.min(config.onsetFrames(), preroll.size());
        totalFrames = preroll.size();
        peak = pendingPeak;
        preroll.clear();
        onsetCount = 0;
        silenceCount = 0;
        pendingPeak = 0;
    }

    @Nullable
    private Segment finish() {
        byte[] all = current.toByteArray();
        int dropFrames = Math.max(0, silenceCount - config.maxTrailingFrames());
        int keepBytes = Math.max(0, all.length - dropFrames * config.frameBytes());
        int measuredFrames = Math.max(voicedFrames, config.onsetFrames());
        int speechMillis = measuredFrames * config.frameMillis();
        boolean valid = speechMillis >= config.minSpeechFrames() * config.frameMillis();
        Segment result = valid
                ? new Segment(Arrays.copyOf(all, Math.min(all.length, keepBytes)), speechMillis, peak, totalFrames)
                : null;
        reset();
        return result;
    }

    private void reset() {
        speaking = false;
        current = null;
        preroll.clear();
        onsetCount = 0;
        silenceCount = 0;
        voicedFrames = 0;
        totalFrames = 0;
        peak = 0;
        pendingPeak = 0;
    }

    private void pushPreroll(byte[] frame) {
        preroll.addLast(Arrays.copyOf(frame, frame.length));
        while (preroll.size() > prerollCapacity) {
            preroll.removeFirst();
        }
    }

    private void write(byte[] frame) {
        if (current == null) {
            current = new ByteArrayOutputStream();
        }
        current.write(frame, 0, frame.length);
    }

    static int rms(byte[] frame) {
        int samples = frame.length / 2;
        if (samples <= 0) {
            return 0;
        }
        double sum = 0.0D;
        for (int i = 0; i + 1 < frame.length; i += 2) {
            int sample = (short) ((frame[i] & 0xFF) | ((frame[i + 1] & 0xFF) << 8));
            sum += (double) sample * sample;
        }
        return (int) Math.min(Integer.MAX_VALUE, Math.round(Math.sqrt(sum / samples)));
    }
}
