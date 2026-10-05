package com.wjx.touhou_aifun.compat.ai.openai;

import com.google.gson.*;
import com.wjx.touhou_aifun.compat.ai.openai.request.ReasoningChatCompletion;
import com.wjx.touhou_aifun.compat.ai.openai.response.StreamAccumulator;
import com.wjx.touhou_aifun.config.LLMRuntimeBudget;
import com.wjx.touhou_aifun.config.TouhouAIFunConfig;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class LLMBudgetTest {
    @Test void longTaskDefaultsAreAvailableBeforeConfigLoad() {
        assertEquals(65536, LLMRuntimeBudget.outputTokens());
        assertEquals(256, LLMRuntimeBudget.toolRounds());
        assertEquals(8, LLMRuntimeBudget.repeatBatches());
        assertEquals(Duration.ofMinutes(10), LLMRuntimeBudget.timeout());
        assertEquals(98304, TouhouAIFunConfig.CONTEXT_INPUT_BUDGET_TOKENS.getDefault());
    }

    @Test void sendsProtocolAppropriateOutputLimitWithoutChangingThinkingMode() {
        Gson gson = new Gson();
        for (String model : new String[]{"deepseek-flash", "step-3.5-flash", "qwen3-max", "gpt-4o"}) {
            JsonObject body = gson.toJsonTree(ReasoningChatCompletion.create().model(model).outputBudget(65536)).getAsJsonObject();
            assertEquals(65536, body.get("max_tokens").getAsInt());
            assertFalse(body.has("max_completion_tokens"));
            assertFalse(body.has("thinking"));
        }
        for (String model : new String[]{"o3", "o4-mini", "gpt-5.4", "gpt-6.1"}) {
            JsonObject body = gson.toJsonTree(ReasoningChatCompletion.create().model(model).outputBudget(65536)).getAsJsonObject();
            assertEquals(65536, body.get("max_completion_tokens").getAsInt());
            assertFalse(body.has("max_tokens"));
        }
    }

    @Test void anthropicSseRetainsTruncationAndUsageEvenWithPartialTools() throws Exception {
        var client = new AnthropicCompatLLMClient(null, null);
        var accumulator = new StreamAccumulator();
        var accept = AnthropicCompatLLMClient.class.getDeclaredMethod("acceptStreamLine", String.class,
                java.util.Map.class, StreamAccumulator.class, StreamingTtsReply.class, StreamingDisplay.class);
        accept.setAccessible(true);
        var blocks = new HashMap<Integer, String>();
        accept.invoke(client, "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"c1\",\"name\":\"move\",\"input\":{}}}", blocks, accumulator, null, null);
        accept.invoke(client, "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"max_tokens\"},\"usage\":{\"output_tokens\":65536}}", blocks, accumulator, null, null);
        var response = accumulator.buildResponse();
        assertTrue(response.getFirstChoice().hasToolCall());
        assertEquals(65536, response.getUsage().getCompletionTokens());
        assertEquals("max_tokens", response.getFinishReason());
        assertNotNull(ResponseCompletionGuard.failure(response.getFinishReason(), true));
        assertEquals("max_tokens", ResponseCompletionGuard.reason(new Gson().toJsonTree(response).getAsJsonObject()));
        assertEquals("max_tokens", ResponseCompletionGuard.reason(JsonParser.parseString("{\"stop_reason\":\"max_tokens\"}").getAsJsonObject()));
    }

    @Test void incompleteResponsesRejectPartialCallsAndDoNotExposeReasoning() {
        var response = OpenAIResponsesCompatLLMClient.adaptResponse("""
                {"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},
                 "output":[{"type":"function_call","call_id":"c1","name":"move","arguments":"{"}]}
                """);
        assertEquals("length", response.getFinishReason());
        assertNotNull(ResponseCompletionGuard.failure(response.getFinishReason(), true));
        assertNull(ResponseCompletionGuard.failure("tool_calls", true));
        assertNull(ResponseCompletionGuard.failure("end_turn", true));
        String error = ResponseCompletionGuard.failure("", false);
        assertNotNull(error);
        assertFalse(error.contains("BASE64"));
    }

    @Test void subscriptionReportsItsOwnOutputLimit() {
        var events = new ChatGPTResponsesCodec.Events();
        var failure = assertThrows(IllegalStateException.class, () -> events.accept(JsonParser.parseString("""
                {"type":"response.incomplete","response":{"status":"incomplete",
                  "incomplete_details":{"reason":"max_output_tokens"}}}
                """).getAsJsonObject()));
        assertTrue(failure.getMessage().contains("订阅输出预算"));
        assertFalse(events.isCompleted());
    }

    @Test void watchdogClosesSilentStreamAtDeadline() throws Exception {
        CountDownLatch closed = new CountDownLatch(1);
        var stream = Stream.<String>empty().onClose(closed::countDown);
        try (var guard = new StreamReadGuard(stream, System.nanoTime() - 1, () -> false)) {
            assertTrue(closed.await(2, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, guard::check);
        }
    }

    @Test void watchdogClosesSupersededStreamWithoutTimeoutError() throws Exception {
        CountDownLatch closed = new CountDownLatch(1);
        var stream = Stream.<String>empty().onClose(closed::countDown);
        try (var guard = new StreamReadGuard(stream, System.nanoTime() + TimeUnit.MINUTES.toNanos(10), () -> true)) {
            assertTrue(closed.await(2, TimeUnit.SECONDS));
            assertDoesNotThrow(guard::check);
        }
    }

    @Test void watchdogActuallyInterruptsHttpClientWhenSseServerGoesSilent() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        CountDownLatch release = new CountDownLatch(1);
        server.createContext("/stream", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write("data: heartbeat\n\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            var response = java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(
                    java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/stream"))
                    .timeout(Duration.ofSeconds(3)).GET().build(), java.net.http.HttpResponse.BodyHandlers.ofLines());
            try (var guard = new StreamReadGuard(response.body(), System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(250), () -> false)) {
                var reader = java.util.concurrent.CompletableFuture.runAsync(() -> {
                    try (var lines = response.body()) { lines.forEach(line -> {}); }
                });
                try { reader.get(2, TimeUnit.SECONDS); }
                catch (java.util.concurrent.ExecutionException expectedOnClose) { /* ofLines may throw on cancellation. */ }
                assertThrows(IllegalStateException.class, guard::check);
            }
        } finally { release.countDown(); server.stop(0); }
    }
}
