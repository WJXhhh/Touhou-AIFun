package com.wjx.touhou_aifun.client.voice;

import java.io.ByteArrayOutputStream;

/** Minimal PCM-to-WAV wrapper for buffered ASR requests. */
public final class PcmWav {
    private PcmWav() {
    }

    public static byte[] encode(byte[] pcm, int sampleRate, int channels, int bitsPerSample) {
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;
        ByteArrayOutputStream out = new ByteArrayOutputStream(44 + (pcm == null ? 0 : pcm.length));
        writeAscii(out, "RIFF");
        writeIntLE(out, 36 + (pcm == null ? 0 : pcm.length));
        writeAscii(out, "WAVE");
        writeAscii(out, "fmt ");
        writeIntLE(out, 16);
        writeShortLE(out, 1);
        writeShortLE(out, channels);
        writeIntLE(out, sampleRate);
        writeIntLE(out, byteRate);
        writeShortLE(out, blockAlign);
        writeShortLE(out, bitsPerSample);
        writeAscii(out, "data");
        writeIntLE(out, pcm == null ? 0 : pcm.length);
        if (pcm != null && pcm.length > 0) {
            out.write(pcm, 0, pcm.length);
        }
        return out.toByteArray();
    }

    private static void writeAscii(ByteArrayOutputStream out, String value) {
        for (int i = 0; i < value.length(); i++) {
            out.write(value.charAt(i) & 0xFF);
        }
    }

    private static void writeIntLE(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 24) & 0xFF);
    }

    private static void writeShortLE(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
    }
}
