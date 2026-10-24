package com.wjx.touhou_aifun.client.sound;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class AIFunPcmAudioStreamTest {
    private static final int BYTES_PER_SECOND = 24_000 * 2;

    @Test
    void tinyFirstDeltaWaitsUntilFourPlaybackBuffersAreAvailable() throws Exception {
        AIFunPcmAudioStream stream = new AIFunPcmAudioStream(24_000);
        stream.append(pcm(20));
        assertFalse(stream.isReadyToPlay());
        stream.append(pcm(380));
        assertTrue(stream.isReadyToPlay());

        // Model Channel.attachBufferStream's four synchronous one-second read requests.
        for (int i = 0; i < 4; i++) {
            byte[] buffer = bytes(stream.read(BYTES_PER_SECOND));
            assertEquals(BYTES_PER_SECOND / 10, buffer.length);
            assertArrayEquals(pcm(100), buffer);
        }
    }

    @Test
    void networkGapNeverQueuesSecondsOfSilenceOrBlocksSoundThread() {
        AIFunPcmAudioStream stream = new AIFunPcmAudioStream(24_000);
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
            for (int i = 0; i < 4; i++) {
                byte[] silence = bytes(stream.read(BYTES_PER_SECOND));
                assertEquals(BYTES_PER_SECOND / 10, silence.length);
                assertArrayEquals(new byte[silence.length], silence);
            }
            stream.append(pcm(100));
            assertArrayEquals(pcm(100), bytes(stream.read(BYTES_PER_SECOND)));
        });
    }

    @Test
    void completedShortReplyStartsAndPreservesEveryByteAcrossChunks() throws Exception {
        AIFunPcmAudioStream stream = new AIFunPcmAudioStream(24_000);
        byte[] expected = new byte[BYTES_PER_SECOND / 4];
        for (int i = 0; i < expected.length; i++) {
            expected[i] = (byte) i;
        }
        stream.append(Arrays.copyOfRange(expected, 0, 1234));
        stream.append(Arrays.copyOfRange(expected, 1234, expected.length));
        assertFalse(stream.isReadyToPlay());
        stream.finish();
        assertTrue(stream.isReadyToPlay());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteBuffer buffer;
        while ((buffer = stream.read(BYTES_PER_SECOND)) != null) {
            output.write(bytes(buffer));
        }
        assertArrayEquals(expected, output.toByteArray());
    }

    @Test
    void abandonedStreamEventuallyEndsAndClosedStreamCannotStart() throws Exception {
        AIFunPcmAudioStream stream = new AIFunPcmAudioStream(24_000);
        for (int i = 0; i < 80; i++) {
            assertNotNull(stream.read(BYTES_PER_SECOND));
        }
        assertNull(stream.read(BYTES_PER_SECOND));
        stream.close();
        stream.append(pcm(400));
        stream.finish();
        assertFalse(stream.isReadyToPlay());
        assertNull(stream.read(BYTES_PER_SECOND));
    }

    private static byte[] pcm(int millis) {
        byte[] pcm = new byte[BYTES_PER_SECOND * millis / 1000];
        Arrays.fill(pcm, (byte) 1);
        return pcm;
    }

    private static byte[] bytes(ByteBuffer buffer) {
        byte[] result = new byte[buffer.remaining()];
        buffer.get(result);
        return result;
    }
}
