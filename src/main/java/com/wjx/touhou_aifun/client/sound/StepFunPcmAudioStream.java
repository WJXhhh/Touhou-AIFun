package com.wjx.touhou_aifun.client.sound;

import net.minecraft.client.sounds.AudioStream;
import org.lwjgl.BufferUtils;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public final class StepFunPcmAudioStream implements AudioStream {
    private static final long CHUNK_TIMEOUT_SECONDS = 15;

    private final AudioFormat format;
    private final BlockingQueue<byte[]> chunks = new LinkedBlockingQueue<>();
    private volatile boolean finished;
    private volatile boolean closed;
    private byte[] currentChunk;
    private int currentOffset;

    public StepFunPcmAudioStream(int sampleRate) {
        this.format = new AudioFormat(sampleRate, 16, 1, true, false);
    }

    public void append(byte[] data) {
        if (!closed && !finished && data.length > 0) {
            chunks.offer(data);
        }
    }

    public void finish() {
        finished = true;
        chunks.offer(new byte[0]);
    }

    @Override
    public AudioFormat getFormat() {
        return format;
    }

    @Override
    public ByteBuffer read(int size) throws IOException {
        ByteBuffer output = BufferUtils.createByteBuffer(size);
        try {
            while (output.hasRemaining() && !closed) {
                if (currentChunk == null || currentOffset >= currentChunk.length) {
                    currentChunk = nextChunk(output.position() == 0);
                    currentOffset = 0;
                    if (currentChunk == null || currentChunk.length == 0) {
                        break;
                    }
                }
                int length = Math.min(output.remaining(), currentChunk.length - currentOffset);
                output.put(currentChunk, currentOffset, length);
                currentOffset += length;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for streamed TTS audio", e);
        }
        output.flip();
        return output;
    }

    private byte[] nextChunk(boolean waitForFirstChunk) throws InterruptedException {
        if (finished && chunks.isEmpty()) {
            return null;
        }
        if (waitForFirstChunk) {
            return chunks.poll(CHUNK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
        return chunks.poll();
    }

    @Override
    public void close() {
        closed = true;
        chunks.clear();
    }
}
