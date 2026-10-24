package com.wjx.touhou_aifun.client.sound;

import net.minecraft.client.sounds.AudioStream;
import org.lwjgl.BufferUtils;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * PCM stream for StepFun's streaming TTS.
 *
 * <p>Minecraft calls {@link #read} from the <b>sound engine thread</b> (see
 * {@code Channel.pumpBuffers}). While the render thread calls {@code SoundEngine.play}, it blocks
 * in {@code ChannelAccess.createHandle(...).join()} and waits for that same single-threaded sound
 * engine, so a blocking read freezes the whole game window. Upstream data arrives in
 * sentence-sized bursts with real network gaps, therefore this stream never waits: a missing
 * chunk is filled with a short run of silence so OpenAL keeps running, and the real audio is
 * queued as soon as it arrives.</p>
 */
public final class AIFunPcmAudioStream implements AudioStream {
    /** How long the virtual stream may insert silence while the network is still producing. */
    private static final long SILENCE_LIMIT_MILLIS = 8000L;
    private static final int STARTUP_BUFFER_MILLIS = 400;
    private static final int PLAYBACK_BUFFER_MILLIS = 100;

    private final AudioFormat format;
    private final BlockingQueue<byte[]> chunks = new LinkedBlockingQueue<>();
    private final long silenceLimitBytes;
    private final long startupBufferBytes;
    private final int playbackBufferBytes;
    private final AtomicLong bufferedBytes = new AtomicLong();
    private volatile boolean finished;
    private volatile boolean closed;
    private byte[] currentChunk;
    private int currentOffset;
    private long silentBytes;

    public AIFunPcmAudioStream(int sampleRate) {
        this.format = new AudioFormat(sampleRate, 16, 1, true, false);
        // 16-bit mono: bytes per second = sampleRate * 2.
        this.silenceLimitBytes = Math.max(1, (long) sampleRate * 2L) * SILENCE_LIMIT_MILLIS / 1000L;
        this.startupBufferBytes = (long) sampleRate * 2L * STARTUP_BUFFER_MILLIS / 1000L;
        this.playbackBufferBytes = Math.max(2, sampleRate * PLAYBACK_BUFFER_MILLIS / 1000) * 2;
    }

    public void append(byte[] data) {
        if (!closed && !finished && data.length > 0) {
            bufferedBytes.addAndGet(data.length);
            chunks.offer(data);
        }
    }

    /** Called before attaching to OpenAL; short completed replies need no minimum buffer. */
    public boolean isReadyToPlay() {
        return !closed && (finished || bufferedBytes.get() >= startupBufferBytes);
    }

    public void finish() {
        finished = true;
    }

    @Override
    public AudioFormat getFormat() {
        return format;
    }

    @Override
    public ByteBuffer read(int size) throws IOException {
        if (closed) {
            return null;
        }
        // Channel requests one-second buffers and primes four of them. Returning a tiny first
        // delta followed by three full silent buffers puts seconds of silence inside the first
        // word. Keep every buffer short, including silence, and prebuffer before attaching.
        size = Math.min(size, playbackBufferBytes);
        ByteBuffer output = BufferUtils.createByteBuffer(size);
        while (output.hasRemaining()) {
            if (currentChunk == null || currentOffset >= currentChunk.length) {
                currentChunk = chunks.poll();
                currentOffset = 0;
                if (currentChunk == null || currentChunk.length == 0) {
                    currentChunk = null;
                    currentOffset = 0;
                    break;
                }
            }
            int length = Math.min(output.remaining(), currentChunk.length - currentOffset);
            output.put(currentChunk, currentOffset, length);
            currentOffset += length;
            bufferedBytes.addAndGet(-length);
        }
        if (output.position() > 0) {
            silentBytes = 0;
            output.flip();
            return output;
        }
        // Nothing buffered right now. Never block the sound engine thread; pad with silence while
        // the stream is still being produced so OpenAL does not stop between sentence chunks.
        if (finished || silentBytes >= silenceLimitBytes) {
            return null;
        }
        silentBytes += size;
        ByteBuffer silence = BufferUtils.createByteBuffer(size);
        silence.position(size);
        silence.flip();
        return silence;
    }

    @Override
    public void close() {
        closed = true;
        chunks.clear();
    }
}
