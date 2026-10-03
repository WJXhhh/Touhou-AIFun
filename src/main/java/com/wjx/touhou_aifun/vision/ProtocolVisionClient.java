package com.wjx.touhou_aifun.vision;

import com.google.gson.*;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMSite;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.LLMOpenAISite;
import com.wjx.touhou_aifun.compat.ai.chatgpt.ChatGPTSession;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.*;

/** Auxiliary inference with no tools, dispatched using the selected shared site's actual protocol/auth. */
final class ProtocolVisionClient implements VisionClient {
    private final VisionSite view;
    ProtocolVisionClient(VisionSite view) { this.view = view; }

    @Override public CompletableFuture<VisionObservation> observe(VisionRequest request) {
        LLMSite source = view.source();
        var protocol = UnifiedModelCatalog.protocol(source, view.model());
        java.util.UUID maidId = request.maid() == null ? null : request.maid().getUUID();
        long ticket = VisionHttpCancellation.ticket(maidId);
        CompletableFuture<String> credentials = protocol == UnifiedModelCatalog.VisualProtocol.SUBSCRIPTION
                ? CompletableFuture.supplyAsync(() -> {
                    try { return ChatGPTSession.accessToken(); }
                    catch (Exception error) { throw new CompletionException(error); }
                }) : CompletableFuture.completedFuture(((LLMOpenAISite) source).secretKey());
        return credentials.thenCompose(key -> {
            if (!VisionHttpCancellation.isCurrent(maidId, ticket)) return CompletableFuture.failedFuture(new CancellationException());
            JsonObject body = body(request, protocol);
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(view.endpoint())).timeout(Duration.ofSeconds(45))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString()));
            if (protocol == UnifiedModelCatalog.VisualProtocol.SUBSCRIPTION) builder.header("Accept", "text/event-stream");
            if (protocol == UnifiedModelCatalog.VisualProtocol.ANTHROPIC) {
                builder.header("x-api-key", key).header("anthropic-version", "2023-06-01");
            } else if (!key.isBlank()) builder.header("Authorization", "Bearer " + key);
            UnifiedModelCatalog.requestHeaders(view, maidId).forEach(builder::setHeader);
            CompletableFuture<HttpResponse<String>> transport = LLMSite.LLM_HTTP_CLIENT.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (maidId != null) VisionHttpCancellation.register(maidId, transport, ticket);
            return transport.orTimeout(50, TimeUnit.SECONDS).thenApply(response -> parse(response.statusCode(), response.body(), request, protocol));
        }).exceptionally(error -> VisionObservation.failed(view.id(), OpenAICompatibleVisionClient.safeMessage(error), request.scanTick()));
    }

    static JsonObject body(VisionRequest request, UnifiedModelCatalog.VisualProtocol protocol) {
        JsonObject body = new JsonObject();
        body.addProperty("model", request.site().model());
        JsonArray messages = new JsonArray();
        messages.add(MultimodalContent.user(protocol, OpenAICompatibleVisionClient.prompt(request), request.images()));
        if (protocol == UnifiedModelCatalog.VisualProtocol.ANTHROPIC) {
            body.addProperty("max_tokens", 4096);
            body.addProperty("stream", false);
            body.add("messages", messages);
        } else {
            body.addProperty("store", false);
            body.addProperty("stream", protocol == UnifiedModelCatalog.VisualProtocol.SUBSCRIPTION);
            body.add("input", messages);
            if (protocol != UnifiedModelCatalog.VisualProtocol.SUBSCRIPTION) body.addProperty("max_output_tokens", 4096);
        }
        return body;
    }

    private VisionObservation parse(int status, String value, VisionRequest request, UnifiedModelCatalog.VisualProtocol protocol) {
        if (status < 200 || status >= 300) {
            return VisionObservation.failed(view.id(), OpenAICompatibleVisionClient.httpError(status, value), request.scanTick());
        }
        try {
            String text;
            if (protocol == UnifiedModelCatalog.VisualProtocol.SUBSCRIPTION) text = subscriptionText(value);
            else {
                JsonObject body = JsonParser.parseString(value).getAsJsonObject();
                text = protocol == UnifiedModelCatalog.VisualProtocol.ANTHROPIC
                        ? blockText(body.getAsJsonArray("content"), "text", "text") : responsesText(body);
            }
            if (text.isBlank()) return VisionObservation.failed(view.id(), "empty visual reply", request.scanTick());
            return OpenAICompatibleVisionClient.observationFromText(view, text, request.imageTick(), request.scanTick());
        } catch (RuntimeException error) { return VisionObservation.failed(view.id(), "invalid visual reply", request.scanTick()); }
    }

    private static String blockText(JsonArray blocks, String type, String field) {
        StringBuilder text = new StringBuilder();
        if (blocks != null) for (var element : blocks) {
            if (!element.isJsonObject()) continue;
            JsonObject block = element.getAsJsonObject();
            if (block.has("type") && type.equals(block.get("type").getAsString()) && block.has(field)) text.append(block.get(field).getAsString());
        }
        return text.toString();
    }
    static String responsesText(JsonObject body) {
        if (body.has("output_text")) return body.get("output_text").getAsString();
        StringBuilder text = new StringBuilder();
        if (body.has("output")) for (var item : body.getAsJsonArray("output")) {
            JsonObject object = item.getAsJsonObject();
            if (object.has("content")) text.append(blockText(object.getAsJsonArray("content"), "output_text", "text"));
        }
        return text.toString();
    }
    static String subscriptionText(String sse) {
        StringBuilder deltas = new StringBuilder(), data = new StringBuilder();
        String finalText = "";
        boolean completed = false;
        for (String line : (sse + "\n\n").split("\\r?\\n", -1)) {
            if (line.startsWith("data:")) {
                if (!data.isEmpty()) data.append('\n');
                data.append(line.substring(5).stripLeading());
            } else if (line.isEmpty() && !data.isEmpty()) {
                String frame = data.toString(); data.setLength(0);
                if ("[DONE]".equals(frame)) continue;
                JsonObject event = JsonParser.parseString(frame).getAsJsonObject();
                String type = event.has("type") ? event.get("type").getAsString() : "";
                if ("response.output_text.delta".equals(type)) deltas.append(event.get("delta").getAsString());
                if ("response.output_text.done".equals(type) && deltas.isEmpty()) finalText = event.get("text").getAsString();
                if ("error".equals(type) || "response.failed".equals(type) || "response.incomplete".equals(type)) {
                    throw new IllegalStateException("Visual response failed");
                }
                if ("response.completed".equals(type)) {
                    JsonObject response = event.getAsJsonObject("response");
                    if (response != null && response.has("status") && !"completed".equals(response.get("status").getAsString())) {
                        throw new IllegalStateException("Incomplete visual stream");
                    }
                    completed = true;
                    String text = response == null ? "" : responsesText(response);
                    if (!text.isBlank()) finalText = text;
                }
            }
        }
        if (!completed) throw new IllegalStateException("Incomplete visual stream");
        return finalText.isBlank() ? deltas.toString() : finalText;
    }
}
