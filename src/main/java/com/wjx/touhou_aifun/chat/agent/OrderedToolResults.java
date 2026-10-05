package com.wjx.touhou_aifun.chat.agent;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Completion order never determines model history order. Dispatch owns cancellation. */
public final class OrderedToolResults {
    private OrderedToolResults() { }
    public static <T> CompletableFuture<Void> commit(List<CompletableFuture<T>> pending,
                                                     Executor server, Consumer<List<T>> consumer) {
        List<CompletableFuture<T>> accepted = List.copyOf(pending);
        return CompletableFuture.allOf(accepted.toArray(CompletableFuture[]::new))
                .thenRunAsync(() -> consumer.accept(accepted.stream().map(CompletableFuture::join).toList()), server);
    }
}
