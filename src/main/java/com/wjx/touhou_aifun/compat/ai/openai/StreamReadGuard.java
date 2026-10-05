package com.wjx.touhou_aifun.compat.ai.openai;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

/** HttpClient's ofLines future ends at response headers. Guard the remaining body too. */
final class StreamReadGuard implements AutoCloseable {
    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "AIFun-LLM-stream-timeout");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean expired = new AtomicBoolean();
    private final long deadline;
    private final ScheduledFuture<?> timer;

    StreamReadGuard(Stream<String> body, long deadline, BooleanSupplier stopped) {
        this.deadline = deadline;
        timer = WATCHDOG.scheduleAtFixedRate(() -> {
            boolean timeout = System.nanoTime() >= deadline;
            if (timeout) expired.set(true);
            if (timeout || stopped.getAsBoolean()) body.close();
        }, 100, 100, TimeUnit.MILLISECONDS);
    }

    boolean expired() { return expired.get() || System.nanoTime() >= deadline; }
    void check() {
        if (expired()) throw new IllegalStateException("模型请求超过配置的等待时间（llm.requestTimeoutSeconds），已停止读取响应。");
    }
    @Override public void close() { timer.cancel(false); }
}
