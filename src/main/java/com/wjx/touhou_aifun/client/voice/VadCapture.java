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
import java.util.concurrent.atomic.AtomicLong;

/**
 * Owns one 16 kHz microphone line for free chat and the inline level meter. Free chat must not use
 * TLM's static {@code MicrophoneManager.startRecord} session because that state is shared with the
 * push-to-talk key; this class only reuses the same device-selection rules.
 */
@OnlyIn(Dist.CLIENT)
public final class VadCapture {
    public interface Listener {
        void onSegment(byte[] wav, int speechMillis, int peak);

        void onFailure(String reason);
    }

    public static final AudioFormat FORMAT = new AudioFormat(16000.0F, 16, 1, true, false);
    private static final int DEFAULT_FRAME_BYTES = 640; // 20 ms, 16 kHz, mono, 16-bit

    private final Object lock = new Object();
    private final AtomicLong generation = new AtomicLong();
    private volatile boolean running;
    private volatile boolean monitorMode;
    private volatile int latestLevel;
    private volatile String status = "";
    private volatile int activeThreshold = 700;
    private volatile boolean ttsActive;
    private volatile boolean speechActive;
    private volatile TargetDataLine line;
    private String deviceName = "";
    private VadSegmenter segmenter;
    private Listener listener;

    public void start(String deviceName, VadSegmenter segmenter, Listener listener) {
        synchronized (lock) {
            stopLocked();
            this.deviceName = deviceName == null ? "" : deviceName;
            this.segmenter = segmenter;
            this.listener = listener;
            long capturedGeneration = this.generation.incrementAndGet();
            this.running = true;
            this.status = "";
            this.latestLevel = 0;
            this.speechActive = false;
            Thread worker = new Thread(() -> run(capturedGeneration), "AIFun-FreeChat-Mic");
            worker.setDaemon(true);
            worker.start();
        }
    }

    public void stop() {
        synchronized (lock) {
            stopLocked();
            status = "";
        }
    }

    private void stopLocked() {
        generation.incrementAndGet();
        running = false;
        TargetDataLine current = line;
        if (current != null) {
            try {
                current.stop();
            } catch (RuntimeException ignored) {
            }
            try {
                current.close();
            } catch (RuntimeException ignored) {
            }
        }
        line = null;
        speechActive = false;
    }

    public boolean isRunning() {
        return running;
    }

    public void setMonitorMode(boolean monitorMode) {
        this.monitorMode = monitorMode;
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

    private void run(long capturedGeneration) {
        TargetDataLine dataLine = null;
        try {
            dataLine = openDevice(deviceName);
            if (dataLine == null) {
                fail(capturedGeneration, "mic_missing");
                return;
            }
            if (generation.get() != capturedGeneration) {
                return;
            }
            line = dataLine;
            dataLine.open(FORMAT);
            if (generation.get() != capturedGeneration) {
                return;
            }
            dataLine.start();
            if (generation.get() == capturedGeneration) {
                status = "listening";
            }
            byte[] readBuffer = new byte[Math.max(1024, DEFAULT_FRAME_BYTES * 8)];
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
            if (dataLine != null) {
                try {
                    dataLine.stop();
                } catch (RuntimeException ignored) {
                }
                try {
                    dataLine.close();
                } catch (RuntimeException ignored) {
                }
            }
            if (line == dataLine) {
                line = null;
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
        speechActive = local.isSpeaking();
        if (segment == null) {
            return;
        }
        byte[] wav = PcmWav.encode(segment.pcm(), 16000, 1, 16);
        Listener target = listener;
        if (target == null || !running) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        try {
            minecraft.execute(() -> {
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
        Minecraft minecraft = Minecraft.getInstance();
        try {
            minecraft.execute(() -> {
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
