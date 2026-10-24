package com.wjx.touhou_aifun.client.voice;

import com.github.tartaricacid.touhoulittlemaid.client.sound.record.MicrophoneManager;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Owns one 16 kHz microphone line for free chat and the inline level meter. Free chat must not use
 * TLM's static {@code MicrophoneManager.startRecord} session because that state is shared with the
 * push-to-talk key; this class only reuses the same device-selection rules.
 *
 * <p>All JavaSound device work happens on the capture thread. Opening, closing and even
 * {@link AudioSystem#getMixerInfo()} can block for seconds on Windows audio drivers, and this class
 * is driven from the client tick thread: a blocking close there freezes the whole game window.
 * {@link #start} and {@link #stop} therefore only flip flags and hand the device over
 * asynchronously; {@link #retune} switches between meter-only and VAD mode without reopening the
 * line at all.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class VadCapture {
    public interface Listener {
        /** A new speech segment started; used to snapshot the player's view at onset. */
        default void onSpeechStart() {
        }

        void onSegment(byte[] wav, int speechMillis, int peak);

        void onFailure(String reason);
    }

    public static final AudioFormat FORMAT = new AudioFormat(16000.0F, 16, 1, true, false);
    private static final int DEFAULT_FRAME_BYTES = 640; // 20 ms, 16 kHz, mono, 16-bit
    private static final int CAPTURE_BUFFER_BYTES = DEFAULT_FRAME_BYTES * 4; // 80 ms

    private final Object lock = new Object();
    private final AtomicLong generation = new AtomicLong();
    private final Function<String, TargetDataLine> deviceOpener;
    private final Consumer<Runnable> clientDispatch;
    private volatile boolean running;
    private volatile boolean monitorMode;
    private volatile int latestLevel;
    private volatile String status = "";
    private volatile int activeThreshold = 700;
    private volatile boolean ttsActive;
    private volatile boolean speechActive;
    private volatile TargetDataLine line;
    private volatile String deviceName = "";
    private volatile VadSegmenter segmenter;
    private volatile Listener listener;
    private volatile boolean wasSpeaking;

    public VadCapture() {
        this(VadCapture::openDevice, task -> Minecraft.getInstance().execute(task));
    }

    VadCapture(Function<String, TargetDataLine> deviceOpener, Consumer<Runnable> clientDispatch) {
        this.deviceOpener = deviceOpener;
        this.clientDispatch = clientDispatch;
    }

    public void start(String deviceName, VadSegmenter segmenter, Listener listener) {
        TargetDataLine previous;
        long capturedGeneration;
        synchronized (lock) {
            previous = line;
            line = null;
            this.deviceName = deviceName == null ? "" : deviceName;
            this.segmenter = segmenter;
            this.listener = listener;
            capturedGeneration = generation.incrementAndGet();
            running = true;
            status = "";
            latestLevel = 0;
            speechActive = false;
            wasSpeaking = false;
        }
        Thread worker = new Thread(() -> run(capturedGeneration, previous), "AIFun-FreeChat-Mic");
        worker.setDaemon(true);
        worker.start();
    }

    public void stop() {
        TargetDataLine current;
        synchronized (lock) {
            generation.incrementAndGet();
            running = false;
            current = line;
            line = null;
            speechActive = false;
            status = "";
        }
        closeAsync(current);
    }

    public boolean isRunning() {
        return running;
    }

    public void setMonitorMode(boolean monitorMode) {
        this.monitorMode = monitorMode;
    }

    /**
     * Switches the already open line between the level meter and volume-gated VAD mode without
     * touching the audio device. This is what keeps opening/closing the STT settings screen (or a
     * quick GUI pause) from repeatedly re-opening the microphone on the render thread.
     *
     * @return false when the line is not running on this device and a real {@link #start} is needed
     */
    public boolean retune(String deviceName, VadSegmenter segmenter, Listener listener,
                          boolean monitorMode) {
        synchronized (lock) {
            String requested = deviceName == null ? "" : deviceName;
            if (!running || line == null || !Objects.equals(this.deviceName, requested)) {
                return false;
            }
            this.segmenter = segmenter;
            this.listener = listener;
            this.monitorMode = monitorMode;
            this.speechActive = false;
            this.wasSpeaking = false;
            this.status = "listening";
            return true;
        }
    }

    public void setActiveThreshold(int threshold, boolean ttsActive) {
        this.activeThreshold = Math.max(1, threshold);
        this.ttsActive = ttsActive;
    }

    public int latestLevel() {
        return latestLevel;
    }

    public boolean isSpeechActive() {
        return speechActive;
    }

    public String status() {
        return status;
    }

    private void run(long capturedGeneration, TargetDataLine previous) {
        // The previous line is closed here, on the capture thread, never on the caller thread.
        closeQuietly(previous);
        TargetDataLine dataLine = null;
        try {
            dataLine = deviceOpener.apply(deviceName);
            if (dataLine == null) {
                fail(capturedGeneration, "mic_missing");
                return;
            }
            synchronized (lock) {
                if (generation.get() != capturedGeneration) {
                    return;
                }
                line = dataLine;
            }
            // JavaSound's DirectAudioDevice polls at 1/4 of the device buffer duration. Its default
            // buffer can therefore hide multiple VAD frames even when read() requests only one.
            dataLine.open(FORMAT, CAPTURE_BUFFER_BYTES);
            if (generation.get() != capturedGeneration) {
                return;
            }
            dataLine.start();
            if (generation.get() != capturedGeneration) {
                return;
            }
            status = "listening";
            // TargetDataLine.read blocks until the requested bytes have arrived. Request only one
            // 20 ms frame: the old eight-frame read delayed every onset by up to 160 ms.
            byte[] readBuffer = new byte[DEFAULT_FRAME_BYTES];
            ByteArrayOutputStream pending = new ByteArrayOutputStream(readBuffer.length * 2);
            while (running && generation.get() == capturedGeneration) {
                int read = dataLine.read(readBuffer, 0, readBuffer.length);
                if (read <= 0) {
                    continue;
                }
                pending.write(readBuffer, 0, read);
                byte[] all = pending.toByteArray();
                int frameBytes = segmenter == null ? DEFAULT_FRAME_BYTES : segmenterFrameBytes();
                int offset = 0;
                while (all.length - offset >= frameBytes) {
                    byte[] frame = Arrays.copyOfRange(all, offset, offset + frameBytes);
                    process(capturedGeneration, frame);
                    offset += frameBytes;
                }
                pending.reset();
                if (offset < all.length) {
                    pending.write(all, offset, all.length - offset);
                }
            }
        } catch (LineUnavailableException | IllegalArgumentException unavailable) {
            fail(capturedGeneration, "mic_busy");
        } catch (RuntimeException error) {
            fail(capturedGeneration, "mic_error");
        } finally {
            closeQuietly(dataLine);
            wasSpeaking = false;
            synchronized (lock) {
                if (line == dataLine) {
                    line = null;
                }
            }
            if (generation.get() == capturedGeneration) {
                running = false;
                if (!"mic_missing".equals(status) && !"mic_busy".equals(status)
                        && !"mic_error".equals(status)) {
                    status = "";
                }
            }
        }
    }

    /** Device operations only ever run on a helper thread, never on the render thread. */
    private static void closeAsync(TargetDataLine target) {
        if (target == null) {
            return;
        }
        Thread closer = new Thread(() -> closeQuietly(target), "AIFun-FreeChat-Mic-Close");
        closer.setDaemon(true);
        closer.start();
    }

    private static void closeQuietly(TargetDataLine target) {
        if (target == null) {
            return;
        }
        try {
            target.stop();
        } catch (RuntimeException ignored) {
        }
        try {
            target.close();
        } catch (RuntimeException ignored) {
        }
    }

    private int segmenterFrameBytes() {
        // VadSegmenter.Config is immutable; deriving from the sample rate is enough for the frame
        // size and avoids exposing mutable state from the capture thread.
        return 16000 * 20 / 1000 * 2;
    }

    private void process(long capturedGeneration, byte[] frame) {
        if (generation.get() != capturedGeneration) {
            return;
        }
        int level = VadSegmenter.rms(frame);
        latestLevel = level;
        if (monitorMode) {
            speechActive = false;
            return;
        }
        VadSegmenter local = segmenter;
        if (local == null) {
            speechActive = false;
            return;
        }
        int threshold = GateMeterMath.activeThreshold(activeThreshold, ttsActive);
        VadSegmenter.Segment segment = local.accept(frame, threshold);
        boolean speakingNow = local.isSpeaking();
        speechActive = speakingNow;
        if (speakingNow && !wasSpeaking) {
            Listener starter = listener;
            if (starter != null && running) {
                try {
                    clientDispatch.accept(() -> {
                        if (running && generation.get() == capturedGeneration && listener == starter) {
                            // Deliver the captured edge even if later frames have already ended
                            // this utterance before the render thread executes this task.
                            starter.onSpeechStart();
                        }
                    });
                } catch (RuntimeException ignored) {
                }
            }
        }
        wasSpeaking = speakingNow;
        if (segment == null) {
            return;
        }
        byte[] wav = PcmWav.encode(segment.pcm(), 16000, 1, 16);
        Listener target = listener;
        if (target == null || !running) {
            return;
        }
        try {
            clientDispatch.accept(() -> {
                if (running && generation.get() == capturedGeneration && listener == target) {
                    target.onSegment(wav, segment.speechMillis(), segment.peak());
                }
            });
        } catch (RuntimeException ignored) {
        }
    }

    private void fail(long capturedGeneration, String reason) {
        if (generation.get() != capturedGeneration) {
            return;
        }
        status = reason;
        Listener target = listener;
        if (target == null) {
            return;
        }
        try {
            clientDispatch.accept(() -> {
                if (generation.get() == capturedGeneration) {
                    target.onFailure(reason);
                }
            });
        } catch (RuntimeException ignored) {
        }
    }

    private static TargetDataLine openDevice(String deviceName) {
        Mixer.Info fallbackInfo = null;
        DataLine.Info lineInfo = new DataLine.Info(TargetDataLine.class, FORMAT);
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            Mixer mixer = AudioSystem.getMixer(info);
            if (!mixer.isLineSupported(lineInfo)) {
                continue;
            }
            if (deviceName == null || deviceName.isBlank() || info.getName().equals(deviceName)) {
                TargetDataLine matching = openMixer(mixer, lineInfo);
                if (matching != null) {
                    return matching;
                }
            }
            if (fallbackInfo == null) {
                fallbackInfo = info;
            }
        }
        if (fallbackInfo == null) {
            return null;
        }
        return openMixer(AudioSystem.getMixer(fallbackInfo), lineInfo);
    }

    private static TargetDataLine openMixer(Mixer mixer, DataLine.Info lineInfo) {
        try {
            return (TargetDataLine) mixer.getLine(lineInfo);
        } catch (LineUnavailableException | IllegalArgumentException unavailable) {
            return null;
        }
    }

    public static boolean hasSupportedMicrophone() {
        try {
            return MicrophoneManager.getMicrophoneInfo(FORMAT) != null;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }
}
