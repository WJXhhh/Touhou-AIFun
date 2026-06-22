package com.wjx.touhou_aifun.compat.ai.tts;

import com.github.tartaricacid.touhoulittlemaid.ai.service.Client;
import com.github.tartaricacid.touhoulittlemaid.util.http.MultipartBody;
import com.github.tartaricacid.touhoulittlemaid.util.http.MultipartBodyBuilder;
import com.google.common.net.HttpHeaders;
import com.google.common.net.MediaType;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

public final class CustomVoiceHttpUtil {
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private CustomVoiceHttpUtil() {
    }

    public static Path requireAudioFile(String filePath, String errorPrefix, String... extensions) throws IOException {
        Path path;
        try {
            path = Path.of(filePath);
        } catch (InvalidPathException e) {
            throw new IOException(errorPrefix + "路径无效");
        }
        if (!Files.isRegularFile(path)) {
            throw new IOException(errorPrefix + "文件不存在");
        }
        String lowerName = path.getFileName().toString().toLowerCase();
        for (String extension : extensions) {
            if (lowerName.endsWith(extension)) {
                return path;
            }
        }
        throw new IOException(errorPrefix + "仅支持 " + String.join(" / ", extensions) + " 文件");
    }

    public static HttpResponse<String> postJson(URI uri, String secretKey, Map<String, String> headers, JsonObject body)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.JSON_UTF_8.toString())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + secretKey)
                .POST(HttpRequest.BodyPublishers.ofString(Client.GSON.toJson(body), StandardCharsets.UTF_8))
                .timeout(Duration.ofSeconds(60));
        headers.forEach(builder::header);
        return HTTP_CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    public static HttpResponse<String> postMultipart(URI uri, String secretKey, Map<String, String> headers,
                                                     MultipartBodyBuilder bodyBuilder)
            throws IOException, InterruptedException {
        MultipartBody multipartBody = bodyBuilder.build();
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .header(HttpHeaders.CONTENT_TYPE, multipartBody.getContentType())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + secretKey)
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipartBody.getBody()))
                .timeout(Duration.ofSeconds(60));
        headers.forEach(builder::header);
        return HTTP_CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    public static MultipartBodyBuilder newFileUploadBody(String fieldName, Path path) {
        return new MultipartBodyBuilder()
                .addPart(fieldName, new File(path.toString()), MediaType.OCTET_STREAM.toString(), path.getFileName().toString());
    }

    public static JsonObject requireSuccessJson(HttpResponse<String> response, String action) throws IOException {
        if (response.statusCode() / 100 != 2) {
            throw new IOException(action + "失败: " + response.statusCode() + " " + response.body());
        }
        JsonObject json = Client.GSON.fromJson(response.body(), JsonObject.class);
        if (json == null) {
            throw new IOException(action + "失败: 返回内容不是有效 JSON");
        }
        return json;
    }

    public static String requireString(JsonObject json, String fieldName, String action) throws IOException {
        JsonElement element = json.get(fieldName);
        if (element == null || element.isJsonNull()) {
            throw new IOException(action + "后未返回 " + fieldName);
        }
        String value = StringUtils.trimToEmpty(element.getAsString());
        if (StringUtils.isBlank(value)) {
            throw new IOException(action + "后返回的 " + fieldName + " 为空");
        }
        return value;
    }

    public static String requireNestedString(JsonObject json, String action, String... path) throws IOException {
        JsonElement current = json;
        for (String segment : path) {
            if (current == null || !current.isJsonObject()) {
                throw new IOException(action + "后未返回 " + String.join(".", path));
            }
            current = current.getAsJsonObject().get(segment);
        }
        if (current == null || current.isJsonNull()) {
            throw new IOException(action + "后未返回 " + String.join(".", path));
        }
        String value = StringUtils.trimToEmpty(current.getAsString());
        if (StringUtils.isBlank(value)) {
            throw new IOException(action + "后返回的 " + String.join(".", path) + " 为空");
        }
        return value;
    }

    public static URI replacePath(String configuredUrl, String newPath) throws URISyntaxException {
        URI uri = URI.create(configuredUrl);
        return new URI(uri.getScheme(), uri.getUserInfo(), uri.getHost(), uri.getPort(), newPath, null, null);
    }

    public static String newManagedVoiceId(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
    }
}
