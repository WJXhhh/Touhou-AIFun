package com.wjx.touhou_aifun.vision;

import com.mojang.logging.LogUtils;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;

/** Adapter for SenseNova Token's OpenAI-compatible multimodal endpoint. */
final class SenseNovaVisionClient implements VisionClient {
    private static final Logger LOGGER = LogUtils.getLogger();
    private final VisionSite site;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    SenseNovaVisionClient(VisionSite site) {
        this.site = site;
    }

    @Override
    public CompletableFuture<VisionObservation> observe(VisionRequest request) {
        if (site.apiKey().isBlank()) {
            return CompletableFuture.completedFuture(VisionObservation.failed(
                    site.id(), "visual site has no API key", request.scanTick()));
        }
        if (site.endpoint().isBlank() || site.model().isBlank()) {
            return CompletableFuture.completedFuture(VisionObservation.failed(
                    site.id(), "visual site endpoint/model is empty", request.scanTick()));
        }
        long ticket = VisionHttpCancellation.ticket(request.maid() == null ? null : request.maid().getUUID());
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(site.endpoint()))
                .timeout(Duration.ofSeconds(45))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + site.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(requestBody(request).toString(), StandardCharsets.UTF_8));
        site.headers().forEach(builder::setHeader);
        CompletableFuture<HttpResponse<String>> transport = httpClient.sendAsync(builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (request.maid() != null) VisionHttpCancellation.register(request.maid().getUUID(), transport, ticket);
        return transport
                .thenApply(response -> parse(response.statusCode(), response.body(),
                        request.imageTick(), request.scanTick()))
                .exceptionally(throwable -> VisionObservation.failed(
                        site.id(), OpenAICompatibleVisionClient.safeMessage(throwable), request.scanTick()));
    }

    JsonObject requestBody(VisionRequest request) {
        JsonObject body = new JsonObject();
        body.addProperty("model", site.model());
        body.add("messages", messages(request));
        body.addProperty("temperature", 0.1);
        body.addProperty("n", 1);
        body.addProperty("stream", false);
        body.addProperty("reasoning_effort", "none");
        return body;
    }

    private JsonArray messages(VisionRequest request) {
        JsonArray messages = new JsonArray();
        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        JsonArray content = new JsonArray();

        JsonObject instruction = new JsonObject();
        instruction.addProperty("type", "text");
        instruction.addProperty("text", OpenAICompatibleVisionClient.prompt(request));
        content.add(instruction);

        for (String face : MultimodalContent.IMAGE_LABELS) {
            String image = request.images().get(face);
            if (image == null || image.isBlank()) continue;
            JsonObject label = new JsonObject();
            label.addProperty("type", "text");
            label.addProperty("text", face.equals("gui") ? "GUI screenshot" : "Cubemap face: " + face);
            content.add(label);

            JsonObject imagePart = new JsonObject();
            imagePart.addProperty("type", "image_url");
            JsonObject imageUrl = new JsonObject();
            imageUrl.addProperty("url", image);
            imagePart.add("image_url", imageUrl);
            content.add(imagePart);
        }
        message.add("content", content);
        messages.add(message);
        return messages;
    }

    VisionObservation parse(int status, String body, long imageTick, long scanTick) {
        if (status < 200 || status >= 300) {
            String error = OpenAICompatibleVisionClient.httpError(status, body);
            LOGGER.warn("SenseNova visual request failed: {} (site={}, model={}, endpoint={})",
                    error, site.id(), site.model(), site.endpoint());
            return VisionObservation.failed(site.id(), error, scanTick);
        }
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            if (!root.has("choices") || !root.get("choices").isJsonArray()
                    || root.getAsJsonArray("choices").isEmpty()) {
                return VisionObservation.failed(site.id(), "invalid provider response", scanTick);
            }
            JsonObject choice = root.getAsJsonArray("choices").get(0).getAsJsonObject();
            String text = "";
            if (choice.has("message")) {
                var message = choice.get("message");
                if (message.isJsonPrimitive()) {
                    text = message.getAsString();
                } else if (message.isJsonObject() && message.getAsJsonObject().has("content")) {
                    text = OpenAICompatibleVisionClient.contentText(
                            message.getAsJsonObject().get("content"));
                }
            }
            return OpenAICompatibleVisionClient.observationFromText(site, text, imageTick, scanTick);
        } catch (Exception exception) {
            return VisionObservation.failed(site.id(), "invalid provider response", scanTick);
        }
    }

}
