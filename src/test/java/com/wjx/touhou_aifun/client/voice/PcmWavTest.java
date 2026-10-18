package com.wjx.touhou_aifun.client.voice;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PcmWavTest {
    @Test
    void writesAValidMono16WavHeaderAndData() {
        byte[] pcm = new byte[]{1, 2, 3, 4, 5, 6};
        byte[] wav = PcmWav.encode(pcm, 16000, 1, 16);
        assertEquals(44 + pcm.length, wav.length);
        assertEquals("RIFF", new String(wav, 0, 4, StandardCharsets.US_ASCII));
        assertEquals(36 + pcm.length, littleInt(wav, 4));
        assertEquals("WAVE", new String(wav, 8, 4, StandardCharsets.US_ASCII));
        assertEquals("fmt ", new String(wav, 12, 4, StandardCharsets.US_ASCII));
        assertEquals(16, littleInt(wav, 16));
        assertEquals(1, littleShort(wav, 20));
        assertEquals(1, littleShort(wav, 22));
        assertEquals(16000, littleInt(wav, 24));
        assertEquals(32000, littleInt(wav, 28));
        assertEquals(2, littleShort(wav, 32));
        assertEquals(16, littleShort(wav, 34));
        assertEquals("data", new String(wav, 36, 4, StandardCharsets.US_ASCII));
        assertEquals(pcm.length, littleInt(wav, 40));
        for (int i = 0; i < pcm.length; i++) {
            assertEquals(pcm[i], wav[44 + i]);
        }
    }

    @Test
    void emptyPcmStaysAValidEmptyWav() {
        byte[] wav = PcmWav.encode(new byte[0], 16000, 1, 16);
        assertEquals(44, wav.length);
        assertTrue(littleInt(wav, 40) == 0);
    }

    private static int littleInt(byte[] data, int offset) {
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 3] & 0xFF) << 24);
    }

    private static int littleShort(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }
}
