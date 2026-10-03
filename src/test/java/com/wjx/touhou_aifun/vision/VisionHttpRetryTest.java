package com.wjx.touhou_aifun.vision;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class VisionHttpRetryTest {
    @Test void successfulRetryCompletesItsParentAndDoesNotRepeatToolOrCaptureRequests() throws Exception {
        var attempts = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            int number = attempts.incrementAndGet();
            byte[] response = (number == 1 ? "{\"error\":\"busy\"}"
                    : "{\"choices\":[{\"message\":{\"content\":\"{\\\"scene_summary\\\":\\\"test scene\\\"}\"}}]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(number == 1 ? 429 : 200, response.length);
            exchange.getResponseBody().write(response); exchange.close();
        });
        server.start();
        try {
            var site = new VisionSite("fixture", "Fixture", "custom", "http://127.0.0.1:" + server.getAddress().getPort()
                    + "/v1/chat/completions", "fixture-model", "fixture-key", false);
            var result = new OpenAICompatibleVisionClient(site).observe(new VisionRequest(site, null, "test", "", Map.of(), -1))
                    .get(10, TimeUnit.SECONDS);
            assertEquals("ok", result.status()); assertEquals("test scene", result.sceneSummary());
            assertEquals(2, attempts.get());
        } finally { server.stop(0); }
    }
}
