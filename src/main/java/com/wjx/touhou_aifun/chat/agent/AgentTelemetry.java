package com.wjx.touhou_aifun.chat.agent;

import com.wjx.touhou_aifun.TouhouAIFun;
import com.wjx.touhou_aifun.config.TouhouAIFunConfig;

/** Never accepts arguments, model text, images or credentials. */
public final class AgentTelemetry {
    private static final com.google.gson.Gson JSON=new com.google.gson.Gson();
    private static final AgentTiming TIMING=new AgentTiming(java.util.UUID.randomUUID().toString(),System::nanoTime,
            event -> { if(enabled()) TouhouAIFun.LOGGER.info("AIFun trace {}",JSON.toJson(event)); });
    private static final java.util.Map<Object,String> TRACES=java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private static final java.util.Map<Object,AgentTiming.Span> MODELS=java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private static final java.util.Map<Object,AgentTiming.Span> OPERATIONS=java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private static final java.util.Map<Object,AgentTiming.Span> TASKS=java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private AgentTelemetry() { }
    private static boolean enabled() { return TouhouAIFunConfig.SPEC.isLoaded() && TouhouAIFunConfig.AGENT_DIAGNOSTICS.get(); }
    private static AgentTiming.Identity identity(Object callback,String callId) {
        var c=AgentExecution.context(callback);
        String trace=c.task()!=null?c.task():TRACES.computeIfAbsent(callback,key->java.util.UUID.randomUUID().toString());
        return new AgentTiming.Identity(trace,c.maid()==null?null:c.maid().toString(),c.task(),c.generation(),c.chatTurn(),c.purpose().name(),callId==null?null:AgentTiming.label(callId));
    }
    public static AgentTiming.Span start(Object callback,String stage) {
        AgentTiming.Span parent=OPERATIONS.get(callback);
        if(parent==null) parent=MODELS.get(callback);
        return parent==null?TIMING.start(identity(callback,null),stage):parent.child(stage);
    }
    public static AgentTiming.Span root(Object callback,String stage) {
        var parent=TASKS.get(callback);
        return parent==null?TIMING.start(identity(callback,null),stage):parent.child(stage);
    }
    public static void bindTask(Object callback,AgentTiming.Span span) { TASKS.put(callback,span); }
    public static AgentTiming.Span tool(Object callback,String callId,String stage) {
        var parent=MODELS.get(callback);var context=identity(callback,callId);
        var span=parent==null?TIMING.start(context,stage):parent.child(context,stage);
        OPERATIONS.put(callback,span);return span;
    }
    public static void bindOperation(Object callback,AgentTiming.Span span) { OPERATIONS.put(callback,span); }
    public static void clearOperation(Object callback,AgentTiming.Span span) { OPERATIONS.remove(callback,span); }
    public static ModelSpan model(Object callback) {
        var span=root(callback,"model_request");MODELS.put(callback,span);return new ModelSpan(span);
    }
    public static final class ModelSpan {
        private final AgentTiming.Span span;
        private ModelSpan(AgentTiming.Span span) { this.span=span; }
        public void headers(Throwable error,boolean streaming) {
            if(error!=null) finish(failureStatus(error));
            else { span.milestone(streaming?"response_headers":"response_body"); }
        }
        public void output(boolean effective) { if(effective) span.milestone("first_output"); }
        public void finish(String status) { span.finish(status,0); }
    }
    public static String failureStatus(Throwable error) {
        while(error instanceof java.util.concurrent.CompletionException && error.getCause()!=null) error=error.getCause();
        return error instanceof java.util.concurrent.CancellationException?"cancelled":error instanceof java.util.concurrent.TimeoutException?"timeout":"error";
    }
    public static void usage(Object callback, String protocol,
                             com.wjx.touhou_aifun.compat.ai.openai.response.TokenUsage usage) {
        if (!enabled() || usage == null) return;
        try {
            var model = MODELS.get(callback);
            var event = new UsageEvent(1, identity(callback,null), model == null ? null : model.id(),
                    AgentTiming.label(protocol), usage);
            TouhouAIFun.LOGGER.info("AIFun usage {}", JSON.toJson(event));
        } catch (RuntimeException ignored) { /* Diagnostics never interrupt an accepted operation. */ }
    }
    private record UsageEvent(int version, AgentTiming.Identity context, String model_span_id,
                              String protocol, com.wjx.touhou_aifun.compat.ai.openai.response.TokenUsage usage) { }
    public static void stage(String stage, long started, int chars) {
        if (enabled())
            TouhouAIFun.LOGGER.info("AIFun agent stage={} duration_ms={} result_chars={}", stage,
                    (System.nanoTime() - started) / 1_000_000, chars);
    }
}
