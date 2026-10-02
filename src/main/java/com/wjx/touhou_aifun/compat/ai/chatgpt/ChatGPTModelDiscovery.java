package com.wjx.touhou_aifun.compat.ai.chatgpt;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.wjx.touhou_aifun.compat.ai.chatgpt.OpenAIIdentity.string;

/** Catalog entries are preserved; unlisted candidates require completed inference before admission. */
final class ChatGPTModelDiscovery {
    private static final Map<String, String> CANDIDATES;
    static {
        Map<String, String> candidates = new LinkedHashMap<>();
        candidates.put("gpt-6.1-sol", "GPT-6.1-Sol");
        candidates.put("gpt-6-sol", "GPT-6-Sol");
        candidates.put("gpt-6-luna", "GPT-6-Luna");
        CANDIDATES = Collections.unmodifiableMap(candidates);
    }

    record Check(boolean usable, String detail) { }
    record Result(Map<String, String> models, Map<String, Check> checks) {
        Result {
            models = Collections.unmodifiableMap(new LinkedHashMap<>(models));
            checks = Collections.unmodifiableMap(new LinkedHashMap<>(checks));
        }
    }
    @FunctionalInterface interface Probe { Check run(String model) throws Exception; }

    static Result discover(JsonObject catalog, Probe probe) throws Exception {
        Map<String, String> models = new LinkedHashMap<>();
        Map<String, Check> checks = new LinkedHashMap<>();
        for (var item : catalog.getAsJsonArray("models")) {
            JsonObject model = item.getAsJsonObject();
            String slug = string(model, "slug");
            if ("list".equals(string(model, "visibility")) && !slug.isBlank()) {
                String label = string(model, "display_name");
                models.put(slug, label.isBlank() ? slug : label);
            }
        }
        for (var candidate : CANDIDATES.entrySet()) {
            if (models.containsKey(candidate.getKey())) continue;
            Check check;
            try { check = probe.run(candidate.getKey()); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw e; }
            catch (Exception e) { check = new Check(false, ChatGPTSession.safeError(e)); }
            checks.put(candidate.getKey(), check);
            if (check.usable()) models.put(candidate.getKey(), candidate.getValue());
        }
        return new Result(models, checks);
    }

    static Check probe(HttpClient http, String token, String model) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("model", model); body.addProperty("store", false); body.addProperty("stream", true);
        JsonObject message = new JsonObject();
        message.addProperty("role", "user"); message.addProperty("content", "Say exactly: OK");
        JsonArray input = new JsonArray(); input.add(message); body.add("input", input);
        HttpRequest request = HttpRequest.newBuilder(URI.create(ChatGPTLLMSite.ENDPOINT)).timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .header("Accept", "text/event-stream").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        // Await the complete body with a deadline, not just the initial HTTP headers.
        var pending = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        try {
            var response = pending.get(20, TimeUnit.SECONDS);
            return inspect(response.statusCode(), response.body());
        } catch (TimeoutException e) { return new Check(false, "验证超时，请稍后刷新重试"); }
        finally { if (!pending.isDone()) pending.cancel(true); }
    }

    static Check inspect(int httpStatus, String body) {
        if (httpStatus != 200) {
            String code = "request_failed";
            try { code = errorCode(JsonParser.parseString(body).getAsJsonObject().getAsJsonObject("error")); }
            catch (RuntimeException ignored) { }
            return new Check(false, "HTTP " + httpStatus + " (" + code + ")");
        }
        boolean textReceived = false;
        StringBuilder data = new StringBuilder();
        // Also flush the last event when the server omits its trailing blank line.
        for (String line : (body + "\n\n").split("\\r?\\n", -1)) {
            if (line.startsWith("data:")) {
                if (!data.isEmpty()) data.append('\n');
                data.append(line.substring(5).stripLeading());
            } else if (line.isEmpty() && !data.isEmpty()) {
                String frame = data.toString(); data.setLength(0);
                if ("[DONE]".equals(frame)) continue;
                JsonObject event = JsonParser.parseString(frame).getAsJsonObject();
                String type = string(event, "type");
                if ("response.output_text.delta".equals(type)) textReceived |= !string(event, "delta").isBlank();
                if ("response.output_text.done".equals(type)) textReceived |= !string(event, "text").isBlank();
                if ("response.failed".equals(type) || "error".equals(type)) {
                    JsonObject fault = event.has("response") ? event.getAsJsonObject("response").getAsJsonObject("error") : event;
                    return new Check(false, "验证失败 (" + errorCode(fault) + ")");
                }
                if ("response.incomplete".equals(type)) return new Check(false, "响应未完成，请稍后刷新重试");
                if ("response.completed".equals(type)) {
                    JsonObject response = event.getAsJsonObject("response");
                    if (response == null || !"completed".equals(string(response, "status"))) return new Check(false, "响应未完成");
                    textReceived |= !string(response, "output_text").isBlank();
                    if (response.has("output") && response.get("output").isJsonArray()) {
                        for (var item : response.getAsJsonArray("output")) {
                            JsonObject output = item.getAsJsonObject();
                            if (!"message".equals(string(output, "type")) || !output.has("content")) continue;
                            for (var content : output.getAsJsonArray("content")) {
                                JsonObject block = content.getAsJsonObject();
                                if ("output_text".equals(string(block, "type"))) textReceived |= !string(block, "text").isBlank();
                            }
                        }
                    }
                    return new Check(textReceived, textReceived ? "验证通过（目录未列出）" : "响应已完成但没有正文");
                }
            }
        }
        return new Check(false, "响应中断，未收到完成事件");
    }

    private static String errorCode(JsonObject error) {
        String code = error == null ? "request_failed" : string(error, "code");
        return code.matches("[a-zA-Z0-9_]{1,100}") ? code : "request_failed";
    }
}
