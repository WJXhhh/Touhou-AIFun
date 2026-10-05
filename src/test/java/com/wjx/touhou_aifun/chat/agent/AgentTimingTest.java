package com.wjx.touhou_aifun.chat.agent;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class AgentTimingTest {
    private final AtomicLong clock=new AtomicLong(100);
    private final List<AgentTiming.Event> events=Collections.synchronizedList(new ArrayList<>());
    private final AgentTiming timing=new AgentTiming("test-run",clock::get,events::add);
    private final AgentTiming.Identity context=new AgentTiming.Identity("trace","maid","task",3,7,"TASK_EXECUTION","call");
    @Test void headersAndFirstOutputDoNotEndTheFullStreamingRequest() {
        var span=timing.start(context,"model_request");clock.set(300);span.milestone("response_headers");
        clock.set(900);span.milestone("first_output");span.milestone("first_output");assertFalse(span.ended());
        clock.set(2100);span.finish("ok",0);
        assertEquals(List.of("start","response_headers","first_output","finish"),events.stream().map(AgentTiming.Event::event).toList());
        assertEquals(2000,events.get(3).duration_ns());assertEquals(200,events.get(1).duration_ns());
    }
    @Test void asyncChildrenKeepCapturedTaskGenerationAndCallIdentity() throws Exception {
        var parent=timing.start(context,"tool_total");clock.set(200);var child=parent.child("gui_navigation");
        CompletableFuture.runAsync(()->{clock.set(700);child.finish("arrived",12);}).get();parent.finish("ok",12);
        var completed=events.stream().filter(e->e.span_id().equals(child.id()) && e.event().equals("finish")).findFirst().orElseThrow();
        assertEquals(parent.id(),completed.parent_span_id());assertEquals(context,completed.context());assertEquals(500,completed.duration_ns());
    }
    @Test void cancellationAndLateCompletionEmitExactlyOneTerminalRecord() throws Exception {
        var span=timing.start(context,"model_request");span.finish("cancelled",0);
        CompletableFuture.allOf(java.util.stream.IntStream.range(0,20).mapToObj(i->CompletableFuture.runAsync(()->{
            span.milestone("first_output");span.finish("ok",999);
        })).toArray(CompletableFuture[]::new)).get();
        assertEquals(2,events.size());assertEquals("cancelled",events.get(1).status());assertEquals(0,events.get(1).result_chars());
    }
    @Test void simultaneousRequestsHaveDifferentSpanIdsEvenForSameCallbackContext() {
        var first=timing.start(context,"model_request");var second=timing.start(context,"model_request");
        first.finish("error",0);second.finish("ok",0);assertNotEquals(first.id(),second.id());
        assertEquals(context,events.get(3).context());
    }
    @Test void invalidLabelsCannotWriteFreeTextIntoTimingRecords() {
        var span=timing.start(context,"private conversation\nAPI header");span.finish("error: private data",-1);
        assertEquals("unknown",events.get(0).stage());assertEquals("unknown",events.get(1).status());assertEquals(0,events.get(1).result_chars());
    }
    @Test void diagnosticSinkFailureCannotBreakToolOrCancellationFlow() {
        var failing=new AgentTiming("run",clock::get,event->{throw new IllegalStateException("disk logger unavailable");});
        var span=failing.start(context,"tool_execution");assertDoesNotThrow(()->span.finish("cancelled",0));assertTrue(span.ended());
    }
}
