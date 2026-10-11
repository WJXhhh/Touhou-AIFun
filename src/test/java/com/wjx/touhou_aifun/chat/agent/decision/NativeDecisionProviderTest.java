package com.wjx.touhou_aifun.chat.agent.decision;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import com.wjx.touhou_aifun.chat.agent.AgentOperations;
import org.junit.jupiter.api.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class NativeDecisionProviderTest {
    private HttpServer server;
    private String url;
    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);url="http://127.0.0.1:"+server.getAddress().getPort()+"/v1/decisions";
    }
    @AfterEach void stop(){server.stop(0);}
    @Test void nativeRequestKeepsConfiguredPathAndBearerWithoutChatFields() throws Exception {
        var seen=new AtomicReference<JsonObject>();var auth=new AtomicReference<String>();var method=new AtomicReference<String>();
        server.createContext("/v1/decisions",exchange->{
            method.set(exchange.getRequestMethod());auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            seen.set(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject());
            var bytes=DecisionProtocolTest.response().toString().getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("x-request-id","mock-request");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        var raw=new AtomicReference<CompletableFuture<?>>();
        var result=new NativeDecisionProvider("perplexity",url,"pplx-decider-v1.1-27b","mock-key",3000)
                .evaluate(new JsonObject(),DecisionProtocolTest.questions(),raw::set).get(5,TimeUnit.SECONDS);
        assertEquals("POST",method.get());assertEquals("Bearer mock-key",auth.get());assertEquals(3,seen.get().size());
        assertFalse(seen.get().has("messages"));assertFalse(seen.get().has("stream"));assertEquals("mock-request",result.requestId());assertNotNull(raw.get());
    }
    @Test void nonJsonErrorsAreClassifiedByStatusAndNeverRetried() throws Exception {
        var hits=new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/v1/decisions",exchange->{hits.incrementAndGet();exchange.sendResponseHeaders(401,-1);exchange.close();});server.start();
        var request=new NativeDecisionProvider("jev",url,"jev-1.13.0","mock",3000).evaluate(new JsonObject(),DecisionProtocolTest.questions(),ignored->{});
        var error=assertThrows(ExecutionException.class,()->request.get(5,TimeUnit.SECONDS));
        assertInstanceOf(NativeDecisionProvider.HttpFailure.class,error.getCause());assertTrue(((NativeDecisionProvider.HttpFailure)error.getCause()).permanent());assertEquals(1,hits.get());
    }
    @Test void timeoutAndTaskCancellationCancelTheOriginalTransport() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        server.createContext("/v1/decisions",exchange->{entered.countDown();try{release.await(2,TimeUnit.SECONDS);}catch(InterruptedException ignored){}exchange.close();});server.start();
        try {
            var raw=new AtomicReference<CompletableFuture<?>>();
            var result=new NativeDecisionProvider("perplexity",url,"pplx-decider-v1.1-27b","mock",100).evaluate(new JsonObject(),DecisionProtocolTest.questions(),raw::set);
            assertThrows(ExecutionException.class,()->result.get(2,TimeUnit.SECONDS));
            assertTrue(raw.get().isCancelled() || raw.get().isCompletedExceptionally());
            var operations=new AgentOperations();var cancelledRaw=new AtomicReference<CompletableFuture<?>>();
            var cancelled=new NativeDecisionProvider("jev",url,"jev-1.13.0","mock",3000).evaluate(new JsonObject(),DecisionProtocolTest.questions(),future->{cancelledRaw.set(future);operations.add(future);});
            operations.cancel();assertTrue(cancelledRaw.get().isCancelled());assertThrows(Exception.class,()->cancelled.get(2,TimeUnit.SECONDS));
        } finally {release.countDown();}
    }
    @Test void rateGateIsSharedAndDoesNotQueueAdditionalMaids() {
        String provider="test_"+java.util.UUID.randomUUID();for(int i=0;i<10;i++) assertTrue(DecisionRequestGate.acquire(provider,1_000_000_000L));
        assertFalse(DecisionRequestGate.acquire(provider,1_000_000_000L));assertTrue(DecisionRequestGate.acquire(provider,2_000_000_000L));
        DecisionRequestGate.coolDown(provider,2_000_000_000L);assertFalse(DecisionRequestGate.acquire(provider,2_500_000_000L));
    }
}
