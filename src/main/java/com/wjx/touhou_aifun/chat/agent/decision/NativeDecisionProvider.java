package com.wjx.touhou_aifun.chat.agent.decision;

import com.google.gson.JsonObject;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Non-streaming native endpoint; cancellation always owns the original sendAsync future. */
public final class NativeDecisionProvider implements DecisionProvider {
    public static final class HttpFailure extends RuntimeException {
        public final int status;
        public HttpFailure(int status) { super("decision_http_"+status);this.status=status; }
        public boolean permanent() { return status==400 || status==401 || status==403 || status==404 || status==405 || status==422; }
    }
    private static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final URI endpoint;
    private final String model,key;
    private final int timeoutMs;
    private final Capabilities caps;
    public NativeDecisionProvider(String provider,String endpoint,String model,String key,int timeoutMs) {
        this.endpoint=DecisionServiceSettings.validateEndpoint(endpoint);this.model=model;this.key=key;this.timeoutMs=timeoutMs;
        caps=new Capabilities(128,255,10,provider.equals("perplexity"));
    }
    @Override public Capabilities capabilities() { return caps; }
    @Override public CompletableFuture<Result> evaluate(JsonObject state,JsonObject questions,Consumer<CompletableFuture<?>> own) {
        JsonObject frozen=questions.deepCopy();
        String body=DecisionProtocol.request(model,state,frozen,caps).toString();
        var request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofMillis(timeoutMs))
                .header("Authorization","Bearer "+key).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        var raw=HTTP.sendAsync(request,HttpResponse.BodyHandlers.ofString());own.accept(raw);
        var result=new CompletableFuture<Result>();
        var settled=new java.util.concurrent.atomic.AtomicBoolean();
        var deadline=CompletableFuture.delayedExecutor(timeoutMs,TimeUnit.MILLISECONDS);
        deadline.execute(()-> {
            if(settled.compareAndSet(false,true)) {raw.cancel(true);result.completeExceptionally(new TimeoutException("decision_timeout"));}
        });
        result.whenComplete((v,e)-> {if(result.isCancelled()) {settled.set(true);raw.cancel(true);}});
        raw.whenComplete((response,error)-> {
            if(!settled.compareAndSet(false,true)) return;
            if(error!=null) {result.completeExceptionally(error);return;}
            if(response.statusCode()<200 || response.statusCode()>=300) {result.completeExceptionally(new HttpFailure(response.statusCode()));return;}
            try {result.complete(DecisionProtocol.response(response.body(),frozen,response.headers().firstValue("x-request-id").orElse("")));}
            catch(RuntimeException invalid) {result.completeExceptionally(invalid);}
        });
        return result;
    }
}
