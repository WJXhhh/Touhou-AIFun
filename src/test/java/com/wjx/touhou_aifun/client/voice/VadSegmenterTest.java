package com.wjx.touhou_aifun.client.voice;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VadSegmenterTest {
    private static final int FRAME_BYTES = 640;

    private static byte[] frame(int amplitude) {
        byte[] data = new byte[FRAME_BYTES];
        for (int i = 0; i + 1 < data.length; i += 2) {
            data[i] = (byte) (amplitude & 0xFF);
            data[i + 1] = (byte) ((amplitude >> 8) & 0xFF);
        }
        return data;
    }

    private static VadSegmenter segmenter(int minSpeechFrames, int silenceFrames,
                                          int maxSpeechFrames, int prerollFrames) {
        return new VadSegmenter(new VadSegmenter.Config(16000, 20, 3, silenceFrames,
                minSpeechFrames, maxSpeechFrames, prerollFrames, 5));
    }

    @Test
    void silenceNeverEmits() {
        VadSegmenter segmenter = segmenter(5, 10, 100, 5);
        for (int i = 0; i < 100; i++) {
            assertNull(segmenter.accept(frame(0), 700));
        }
        assertFalse(segmenter.isSpeaking());
    }

    @Test
    void tooShortBurstIsDiscarded() {
        VadSegmenter segmenter = segmenter(5, 10, 100, 5);
        for (int i = 0; i < 3; i++) {
            assertNull(segmenter.accept(frame(2000), 700));
        }
        VadSegmenter.Segment result = null;
        for (int i = 0; i < 10; i++) {
            result = segmenter.accept(frame(0), 700);
        }
        assertNull(result);
        assertFalse(segmenter.isSpeaking());
    }

    @Test
    void speechFollowedBySilenceEmitsOneSegmentIncludingPreroll() {
        VadSegmenter segmenter = segmenter(5, 10, 100, 5);
        for (int i = 0; i < 5; i++) {
            assertNull(segmenter.accept(frame(0), 700));
        }
        for (int i = 0; i < 3; i++) {
            assertNull(segmenter.accept(frame(2000), 700));
        }
        assertTrue(segmenter.isSpeaking());
        for (int i = 0; i < 10; i++) {
            assertNull(segmenter.accept(frame(2000), 700));
        }
        VadSegmenter.Segment result = null;
        for (int i = 0; i < 10; i++) {
            result = segmenter.accept(frame(0), 700);
        }
        assertNotNull(result);
        assertTrue(result.speechMillis() >= 100);
        assertTrue(result.pcm().length > FRAME_BYTES);
        assertFalse(segmenter.isSpeaking());
    }

    @Test
    void shortPauseInsideSpeechDoesNotSplit() {
        VadSegmenter segmenter = segmenter(5, 10, 100, 5);
        for (int i = 0; i < 3; i++) {
            segmenter.accept(frame(2000), 700);
        }
        for (int i = 0; i < 5; i++) {
            segmenter.accept(frame(2000), 700);
        }
        for (int i = 0; i < 5; i++) {
            assertNull(segmenter.accept(frame(0), 700));
        }
        for (int i = 0; i < 5; i++) {
            assertNull(segmenter.accept(frame(2000), 700));
        }
        VadSegmenter.Segment result = null;
        for (int i = 0; i < 10; i++) {
            result = segmenter.accept(frame(0), 700);
        }
        assertNotNull(result);
        assertEquals(1, result.frames() > 0 ? 1 : 0);
    }

    @Test
    void maximumLengthForcesASegment() {
        VadSegmenter segmenter = segmenter(5, 1000, 20, 5);
        List<VadSegmenter.Segment> segments = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            VadSegmenter.Segment result = segmenter.accept(frame(2000), 700);
            if (result != null) {
                segments.add(result);
            }
        }
        assertTrue(segments.size() >= 1);
    }

    @Test
    void ttsThresholdCanSuppressQuietSpeech() {
        VadSegmenter normal = segmenter(5, 10, 100, 5);
        for (int i = 0; i < 3; i++) {
            normal.accept(frame(900), 700);
        }
        assertTrue(normal.isSpeaking());

        VadSegmenter tts = segmenter(5, 10, 100, 5);
        for (int i = 0; i < 3; i++) {
            tts.accept(frame(900), 1200);
        }
        assertFalse(tts.isSpeaking());
    }

    @Test
    void discardResetsTheCurrentUtterance() {
        VadSegmenter segmenter = segmenter(5, 10, 100, 5);
        for (int i = 0; i < 3; i++) {
            segmenter.accept(frame(2000), 700);
        }
        assertTrue(segmenter.isSpeaking());
        segmenter.discard();
        assertFalse(segmenter.isSpeaking());
        for (int i = 0; i < 10; i++) {
            assertNull(segmenter.accept(frame(0), 700));
        }
    }
}
