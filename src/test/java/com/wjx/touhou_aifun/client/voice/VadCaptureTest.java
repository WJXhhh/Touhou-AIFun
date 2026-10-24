package com.wjx.touhou_aifun.client.voice;

import org.junit.jupiter.api.Test;

import javax.sound.sampled.TargetDataLine;
import java.lang.reflect.Proxy;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class VadCaptureTest {
    private static final int FRAME_BYTES = 640;

    @Test
    void onsetArrivesAfterThreeFramesWithoutWaitingForAnEightFrameBatch() throws Exception {
        FakeMicrophone mic = new FakeMicrophone(3, FRAME_BYTES);
        BlockingQueue<Runnable> clientTasks = new LinkedBlockingQueue<>();
        AtomicInteger onsets = new AtomicInteger();
        VadCapture capture = start(mic, clientTasks, onsets);
        try {
            mic.feed(2000, 3);
            Runnable onset = clientTasks.poll(3, TimeUnit.SECONDS);
            assertNotNull(onset, "60 ms of speech must trigger without another 100 ms of input");
            onset.run();
            assertEquals(1, onsets.get());
            assertTrue(capture.isSpeechActive());
            assertTrue(mic.largestRead <= FRAME_BYTES);
            assertEquals(FRAME_BYTES * 4, mic.openBufferBytes);
        } finally {
            capture.stop();
            assertTrue(mic.closed.await(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void shortBurstStillDeliversOnsetWhenClientThreadRunsAfterTheBurstEnds() throws Exception {
        // Exercise partial device reads as well: four reads form one 20 ms VAD frame.
        FakeMicrophone mic = new FakeMicrophone(5, FRAME_BYTES / 4);
        BlockingQueue<Runnable> clientTasks = new LinkedBlockingQueue<>();
        AtomicInteger onsets = new AtomicInteger();
        VadCapture capture = start(mic, clientTasks, onsets);
        try {
            mic.feed(2000, 3);
            mic.feed(0, 2);
            assertTrue(mic.inputProcessed.await(3, TimeUnit.SECONDS));
            assertFalse(capture.isSpeechActive());
            Runnable onset = clientTasks.poll(3, TimeUnit.SECONDS);
            assertNotNull(onset);
            onset.run();
            assertEquals(1, onsets.get(), "an onset is an event, not a live speech-state check");
            assertTrue(clientTasks.isEmpty(), "a too-short burst must not emit an ASR segment");
        } finally {
            capture.stop();
            assertTrue(mic.closed.await(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void stoppingCaptureInvalidatesQueuedOnset() throws Exception {
        FakeMicrophone mic = new FakeMicrophone(3, FRAME_BYTES);
        BlockingQueue<Runnable> clientTasks = new LinkedBlockingQueue<>();
        AtomicInteger onsets = new AtomicInteger();
        VadCapture capture = start(mic, clientTasks, onsets);
        try {
            mic.feed(2000, 3);
            Runnable onset = clientTasks.poll(3, TimeUnit.SECONDS);
            assertNotNull(onset);
            capture.stop();
            onset.run();
            assertEquals(0, onsets.get());
        } finally {
            capture.stop();
            assertTrue(mic.closed.await(3, TimeUnit.SECONDS));
        }
    }

    private static VadCapture start(FakeMicrophone mic, BlockingQueue<Runnable> clientTasks,
                                    AtomicInteger onsets) {
        VadCapture capture = new VadCapture(ignored -> mic.line, clientTasks::add);
        capture.start("test", new VadSegmenter(new VadSegmenter.Config(
                16000, 20, 3, 2, 5, 100, 0, 0)), new VadCapture.Listener() {
            @Override
            public void onSpeechStart() {
                onsets.incrementAndGet();
            }

            @Override
            public void onSegment(byte[] wav, int speechMillis, int peak) {
                fail("test burst is too short for ASR");
            }

            @Override
            public void onFailure(String reason) {
                fail(reason);
            }
        });
        return capture;
    }

    /** Blocks until the requested bytes arrive, matching TargetDataLine.read's batching behavior. */
    private static final class FakeMicrophone {
        private final BlockingQueue<Integer> input = new LinkedBlockingQueue<>();
        private final CountDownLatch inputProcessed = new CountDownLatch(1);
        private final CountDownLatch closed = new CountDownLatch(1);
        private final int expectedBytes;
        private final int maxRead;
        private int bytesRead;
        private volatile int largestRead;
        private volatile int openBufferBytes;
        private volatile boolean stopped;
        private final TargetDataLine line;

        private FakeMicrophone(int expectedFrames, int maxRead) {
            this.expectedBytes = expectedFrames * FRAME_BYTES;
            this.maxRead = maxRead;
            this.line = (TargetDataLine) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{TargetDataLine.class}, (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "open" -> {
                                openBufferBytes = args.length == 2 ? (int) args[1] : 0;
                                return null;
                            }
                            case "start" -> { return null; }
                            case "read" -> {
                                int length = (int) args[2];
                                largestRead = Math.max(largestRead, length);
                                if (bytesRead >= expectedBytes) inputProcessed.countDown();
                                byte[] target = (byte[]) args[0];
                                int offset = (int) args[1];
                                int count = 0;
                                while (count < Math.min(length, maxRead) && !stopped) {
                                    int value = input.take();
                                    if (value < 0) break;
                                    target[offset + count++] = (byte) value;
                                }
                                bytesRead += count;
                                return count;
                            }
                            case "stop", "close" -> {
                                stopped = true;
                                input.offer(-1);
                                if (method.getName().equals("close")) closed.countDown();
                                return null;
                            }
                            default -> throw new UnsupportedOperationException(method.getName());
                        }
                    });
        }

        private void feed(int amplitude, int frames) {
            for (int i = 0; i < frames * FRAME_BYTES / 2; i++) {
                input.add(amplitude & 0xFF);
                input.add((amplitude >> 8) & 0xFF);
            }
        }
    }
}
