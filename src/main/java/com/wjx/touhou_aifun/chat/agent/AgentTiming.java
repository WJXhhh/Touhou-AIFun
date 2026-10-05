package com.wjx.touhou_aifun.chat.agent;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Explicit async spans. No thread-local context, world access or private payloads. */
public final class AgentTiming {
    public record Identity(String trace_id, String maid_id, String task_id, long generation,
                           long chat_turn, String purpose, String call_id) { }
    public record Event(int version, String run_id, Identity context, String span_id, String parent_span_id,
                        String stage, String event, String status, long start_ns, long duration_ns, int result_chars) { }
    private final LongSupplier clock;
    private final Consumer<Event> sink;
    private final String runId;
    private final long origin;
    private final AtomicLong sequence = new AtomicLong();
    public AgentTiming(String runId, LongSupplier clock, Consumer<Event> sink) {
        this.runId=runId; this.clock=clock; this.sink=sink; origin=clock.getAsLong();
    }
    public Span start(Identity identity, String stage) { return new Span(identity, stage, null, clock.getAsLong()); }
    public final class Span {
        private final Identity identity;
        private final String stage, parent, id;
        private final long started;
        private final AtomicBoolean ended=new AtomicBoolean();
        private final Set<String> milestones=ConcurrentHashMap.newKeySet();
        private Span(Identity identity, String stage, String parent, long started) {
            this.identity=identity; this.stage=label(stage); this.parent=parent; this.started=started;
            id=Long.toString(sequence.incrementAndGet()); emit("start","running",0,started);
        }
        public Span child(String stage) { return new Span(identity,stage,id,clock.getAsLong()); }
        public Span child(Identity identity,String stage) { return new Span(identity,stage,id,clock.getAsLong()); }
        public String id() { return id; }
        public boolean ended() { return ended.get(); }
        public synchronized void milestone(String event) {
            String name=label(event);
            if (!ended.get() && milestones.add(name)) emit(name,"running",0,clock.getAsLong());
        }
        public synchronized void finish(String status,int chars) {
            if(ended.compareAndSet(false,true)) emit("finish",label(status),Math.max(0,chars),clock.getAsLong());
        }
        private void emit(String event,String status,int chars,long now) {
            try { sink.accept(new Event(1,runId,identity,id,parent,stage,event,status,
                    Math.max(0,started-origin),Math.max(0,now-started),chars)); }
            catch(RuntimeException ignored) { /* Diagnostics must never break an accepted action. */ }
        }
    }
    public static String label(String value) {
        return value!=null && value.matches("[A-Za-z0-9_.:/-]{1,128}") ? value : "unknown";
    }
}
