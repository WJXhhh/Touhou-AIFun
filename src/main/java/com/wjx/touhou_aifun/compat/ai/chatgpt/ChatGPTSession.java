package com.wjx.touhou_aifun.compat.ai.chatgpt;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import net.minecraftforge.fml.loading.FMLPaths;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static com.wjx.touhou_aifun.compat.ai.chatgpt.OpenAIIdentity.string;

/** Server-owned OAuth store, deliberately separate from the base mod's synced sites. */
public final class ChatGPTSession {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private static final String AUTH = "https://auth.openai.com/api/accounts/authorize";
    private static final String TOKEN = "https://auth.openai.com/api/accounts/oauth/token";
    private static final String RESOURCE = "https://api.openai.com/v1";
    private static final ScheduledExecutorService WORKER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "AIFun-ChatGPT-Auth");
        thread.setDaemon(true);
        return thread;
    });
    private static final SecureRandom RANDOM = new SecureRandom();
    private static JsonObject session;
    private static HttpServer listener;
    private static long authEpoch;
    private static long modelEpoch;
    private static ChatGPTModelDiscovery.Result modelDiscovery;

    private ChatGPTSession() { }

    public static Path directory() {
        return FMLPaths.CONFIGDIR.get().resolve("touhou_aifun").resolve("chatgpt");
    }

    private static synchronized JsonObject session() throws Exception {
        if (session == null) {
            Path file = directory().resolve("session.json");
            session = Files.exists(file) ? JsonParser.parseString(Files.readString(file)).getAsJsonObject() : new JsonObject();
        }
        return session;
    }

    public static synchronized void reload() throws Exception {
        cancelLogin();
        clearModelDiscovery();
        session = null;
        session();
    }

    public static synchronized String status() throws Exception {
        JsonObject saved = session();
        if (string(saved, "access_token").isBlank()) return "未登录；/aifun chatgpt login";
        return "已连接 " + string(saved, "email") + (permitted(saved) ? "，使用 ChatGPT 订阅" : "，未授权订阅使用");
    }

    public static boolean permitted(JsonObject saved) {
        if (!saved.has("scopes") || !saved.get("scopes").isJsonArray()) return false;
        for (var scope : saved.getAsJsonArray("scopes")) {
            if ("chatgpt.tokens.use.direct".equals(scope.getAsString())) return true;
        }
        return false;
    }

    /** Explicit allowlist for GUI metadata; credentials must never be serialized to the client. */
    static JsonObject publicProfile(JsonObject saved) {
        JsonObject metadata = new JsonObject();
        metadata.addProperty("client_id", string(saved, "client_id"));
        metadata.addProperty("email", string(saved, "email"));
        metadata.addProperty("connected", !string(saved, "access_token").isBlank());
        metadata.addProperty("sharing", permitted(saved));
        return metadata;
    }

    public static synchronized JsonObject uiMetadata() throws Exception {
        JsonObject current = session();
        JsonObject metadata = publicProfile(current);
        metadata.addProperty("pending", listener != null);
        JsonObject checks = new JsonObject();
        if (modelDiscovery != null) modelDiscovery.checks().forEach((model, check) -> {
            JsonObject result = new JsonObject();
            result.addProperty("usable", check.usable()); result.addProperty("detail", check.detail());
            checks.add(model, result);
        });
        metadata.add("model_checks", checks);
        JsonArray accounts = new JsonArray();
        if (!string(current, "client_id").isBlank()) accounts.add(publicProfile(current));
        Path folder = directory().resolve("profiles");
        if (Files.isDirectory(folder)) try (var files = Files.list(folder)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".json")).limit(32).toList()) {
                JsonObject profile = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                if (!string(profile, "client_id").equals(string(current, "client_id"))) accounts.add(publicProfile(profile));
            }
        }
        metadata.add("accounts", accounts);
        return metadata;
    }

    public static synchronized String accessToken() throws Exception {
        JsonObject saved = session();
        if (!permitted(saved) || string(saved, "access_token").isBlank()) {
            throw new IllegalStateException("服主尚未授权 ChatGPT 订阅；请运行 /aifun chatgpt login");
        }
        long expires = saved.has("expires_at") ? saved.get("expires_at").getAsLong() : 0;
        if (Instant.now().getEpochSecond() + 90 >= expires) {
            if (string(saved, "refresh_token").isBlank()) throw new IllegalStateException("ChatGPT 登录已过期，请重新登录");
            JsonObject tokens = postForm(TOKEN, Map.of("grant_type", "refresh_token",
                    "client_id", string(saved, "client_id"), "refresh_token", string(saved, "refresh_token"),
                    "resource", RESOURCE));
            JsonObject replacement = saved.deepCopy();
            applyTokens(replacement, tokens);
            persist(replacement);
            saved = replacement;
        }
        if (!permitted(saved)) throw new IllegalStateException("当前 ChatGPT 登录未授权订阅使用，请重新登录");
        return string(saved, "access_token");
    }

    public static CompletableFuture<Map<String, String>> models() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String token;
                long epoch;
                synchronized (ChatGPTSession.class) {
                    token = accessToken(); epoch = modelEpoch;
                }
                JsonObject catalog = getJson(RESOURCE + "/models", token);
                var result = ChatGPTModelDiscovery.discover(catalog, model -> ChatGPTModelDiscovery.probe(HTTP, token, model));
                synchronized (ChatGPTSession.class) {
                    if (epoch != modelEpoch) throw new IllegalStateException("账号已变更，请重新刷新模型");
                    modelDiscovery = result;
                }
                if (result.models().isEmpty()) throw new IllegalStateException("当前账号没有可用模型，详情见验证结果");
                return result.models();
            } catch (Exception e) { throw new java.util.concurrent.CompletionException(e); }
        }, WORKER);
    }

    public static synchronized String modelSummary() {
        if (modelDiscovery == null) return "可用模型已刷新";
        long added = modelDiscovery.checks().values().stream().filter(ChatGPTModelDiscovery.Check::usable).count();
        long failed = modelDiscovery.checks().size() - added;
        return "已刷新 " + modelDiscovery.models().size() + " 个模型，补充 " + added + " 个验证通过的模型"
                + (failed == 0 ? "" : "；" + failed + " 个未通过，悬停模型列表查看原因");
    }

    private static void clearModelDiscovery() { ++modelEpoch; modelDiscovery = null; }

    /** Only loopback is bound. Remote operators forward this port through SSH. */
    public static synchronized String login(int port, boolean newAccount, Consumer<String> finished) throws Exception {
        cancelLogin();
        JsonObject selected = session().deepCopy();
        String client = newAccount ? "" : string(selected, "client_id");
        String host = hostId();
        String state = random(), nonce = random(), verifier = random();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.setExecutor(WORKER);
        String redirect = "http://127.0.0.1:" + server.getAddress().getPort() + "/auth/callback";
        long epoch = ++authEpoch;
        server.createContext("/auth/callback", exchange -> {
            String answer;
            int status;
            Map<String, String> query;
            try {
                query = parseQuery(exchange.getRequestURI().getRawQuery());
                if (!"GET".equals(exchange.getRequestMethod()) || !"/auth/callback".equals(exchange.getRequestURI().getPath())
                        || !state.equals(query.get("state"))) throw new SecurityException("Invalid OAuth callback state");
            } catch (Exception e) {
                byte[] bytes = "Invalid OAuth callback".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(400, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
                return; // Invalid probes must not consume a valid pending login.
            }
            try {
                synchronized (ChatGPTSession.class) {
                    if (epoch != authEpoch || listener != server) throw new SecurityException("Login attempt expired");
                    // Consume this transaction exactly once, even on an exchange failure.
                    ++authEpoch;
                    if (query.containsKey("error")) throw new IllegalStateException("用户未完成 ChatGPT 授权");
                    String issued = query.getOrDefault("client_id", client);
                    if (issued.isBlank() || "dynamic_agent_client".equals(issued)
                            || (!client.isBlank() && !client.equals(issued))) throw new SecurityException("Invalid OAuth client registration");
                    if (!query.containsKey("code") || query.get("code").isBlank()) throw new SecurityException("Missing OAuth code");
                    if (!newAccount) {
                        selected.addProperty("client_id", issued);
                        persist(selected); // Retain issued registration if exchange fails.
                    }
                    JsonObject tokens = postForm(TOKEN, Map.of("grant_type", "authorization_code", "client_id", issued,
                            "code", query.get("code"), "code_verifier", verifier, "redirect_uri", redirect, "resource", RESOURCE));
                    JsonObject discovery = getJson("https://auth.openai.com/.well-known/openid-configuration", null);
                    String jwksUri = string(discovery, "jwks_uri");
                    if (!jwksUri.startsWith("https://auth.openai.com/")) throw new SecurityException("Unexpected JWKS endpoint");
                    JsonObject identity = OpenAIIdentity.verify(string(tokens, "id_token"), getJson(jwksUri, null),
                            issued, nonce, Instant.now().getEpochSecond());
                    if (!newAccount && !string(selected, "subject").isBlank()
                            && !string(selected, "subject").equals(string(identity, "sub"))) throw new SecurityException("ChatGPT account changed");
                    JsonObject replacement = new JsonObject();
                    replacement.addProperty("client_id", issued);
                    replacement.addProperty("subject", string(identity, "sub"));
                    replacement.addProperty("issuer", string(identity, "iss"));
                    replacement.addProperty("email", string(identity, "email"));
                    applyTokens(replacement, tokens);
                    persist(replacement);
                    answer = permitted(replacement) ? "ChatGPT connected. Return to Minecraft." : "Signed in, but ChatGPT plan usage was not granted.";
                    status = 200;
                }
                finished.accept(status());
            } catch (Exception e) {
                answer = "ChatGPT login failed. Start a new login from Minecraft.";
                status = 400;
                // Do not expose callback codes, token replies or ID tokens in diagnostics.
                finished.accept("ChatGPT 授权失败：" + safeError(e));
            }
            byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
            WORKER.execute(() -> { synchronized (ChatGPTSession.class) { if (listener == server) cancelLogin(); } });
        });
        listener = server;
        server.start();
        WORKER.schedule(() -> {
            boolean expired;
            synchronized (ChatGPTSession.class) { expired = listener == server; if (expired) cancelLogin(); }
            if (expired) finished.accept("授权链接已过期，请重新点击登录");
        }, 5, TimeUnit.MINUTES);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("client_id", client.isBlank() ? "dynamic_agent_client" : client);
        if (client.isBlank()) params.put("agent_name_hint", "Touhou AIFun");
        params.put("ext_agent_host_id", host);
        params.put("response_type", "code");
        params.put("redirect_uri", redirect);
        params.put("scope", "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct");
        params.put("resource", RESOURCE);
        params.put("state", state);
        params.put("nonce", nonce);
        params.put("code_challenge_method", "S256");
        params.put("code_challenge", Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII))));
        // No id_token_hint: command URLs may be persisted in console logs.
        return AUTH + "?" + form(params);
    }

    public static synchronized void cancelLogin() {
        ++authEpoch;
        if (listener != null) { listener.stop(0); listener = null; }
    }

    public static synchronized String logout() throws Exception {
        cancelLogin();
        JsonObject saved = session().deepCopy();
        boolean revoked = string(saved, "refresh_token").isBlank();
        if (!revoked) {
            try {
                String endpoint = string(getJson("https://auth.openai.com/.well-known/openid-configuration", null), "revocation_endpoint");
                if (!endpoint.startsWith("https://auth.openai.com/")) throw new SecurityException("Invalid revocation endpoint");
                for (int attempt = 0; attempt < 3 && !revoked; attempt++) {
                    HttpResponse<String> response = HTTP.send(formRequest(endpoint, Map.of("token", string(saved, "refresh_token"),
                            "token_type_hint", "refresh_token", "client_id", string(saved, "client_id"))), HttpResponse.BodyHandlers.ofString());
                    revoked = response.statusCode() == 200;
                    if (response.statusCode() < 500) break;
                    Thread.sleep(300L << attempt);
                }
            } catch (Exception ignored) { /* Local sign-out still proceeds. */ }
        }
        saved.remove("access_token"); saved.remove("refresh_token"); saved.remove("id_token");
        saved.remove("scopes"); saved.remove("expires_at");
        persist(saved);
        clearModelDiscovery();
        return revoked ? "已退出 ChatGPT 登录" : "已在本地退出；远程撤销未确认，请在 ChatGPT 设置中断开应用";
    }

    private static void applyTokens(JsonObject saved, JsonObject tokens) {
        if (string(tokens, "access_token").isBlank() || !"Bearer".equalsIgnoreCase(string(tokens, "token_type"))
                || !tokens.has("expires_in") || tokens.get("expires_in").getAsLong() <= 0) {
            throw new SecurityException("Invalid OAuth token response");
        }
        for (String field : List.of("access_token", "refresh_token", "id_token", "token_type")) {
            if (!string(tokens, field).isBlank()) saved.addProperty(field, string(tokens, field));
        }
        if (tokens.has("scope")) {
            JsonArray scopes = new JsonArray();
            for (String scope : string(tokens, "scope").split("\\s+")) if (!scope.isBlank()) scopes.add(scope);
            saved.add("scopes", scopes);
        }
        saved.addProperty("expires_at", Instant.now().getEpochSecond() + tokens.get("expires_in").getAsLong());
    }

    private static synchronized String hostId() throws Exception {
        Path file = directory().resolve("host.json");
        if (Files.exists(file)) return string(JsonParser.parseString(Files.readString(file)).getAsJsonObject(), "ext_agent_host_id");
        JsonObject host = new JsonObject();
        host.addProperty("ext_agent_host_id", "urn:uuid:" + UUID.randomUUID());
        writeProtected(file, GSON.toJson(host));
        return string(host, "ext_agent_host_id");
    }

    private static void persist(JsonObject replacement) throws Exception {
        // Keep distinct registrations when the owner adds another account/workspace.
        if (session != null && !string(session, "client_id").isBlank()
                && !string(session, "client_id").equals(string(replacement, "client_id"))) {
            writeProtected(profileFile(string(session, "client_id")), GSON.toJson(session));
        }
        writeProtected(directory().resolve("session.json"), GSON.toJson(replacement));
        if (session == null || !string(session, "client_id").equals(string(replacement, "client_id"))
                || !string(session, "subject").equals(string(replacement, "subject"))) clearModelDiscovery();
        session = replacement;
    }

    private static Path profileFile(String clientId) throws Exception {
        String hash = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(clientId.getBytes(StandardCharsets.UTF_8)));
        return directory().resolve("profiles").resolve(hash + ".json");
    }

    public static synchronized String profiles() throws Exception {
        StringBuilder result = new StringBuilder("当前：").append(string(session(), "client_id"));
        Path folder = directory().resolve("profiles");
        if (Files.isDirectory(folder)) try (var files = Files.list(folder)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".json")).toList()) {
                JsonObject profile = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                if (!string(profile, "client_id").equals(string(session(), "client_id")))
                    result.append("\n").append(string(profile, "client_id")).append(" — ").append(string(profile, "email"));
            }
        }
        return result.toString();
    }

    public static synchronized String select(String clientId) throws Exception {
        session();
        if (clientId.equals(string(session, "client_id"))) return status();
        Path file = profileFile(clientId);
        if (!Files.isRegularFile(file)) throw new IllegalStateException("未找到该 ChatGPT 注册");
        JsonObject selected = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        if (!clientId.equals(string(selected, "client_id"))) throw new SecurityException("Invalid saved registration");
        cancelLogin();
        persist(selected);
        return status();
    }

    public static synchronized void shutdown() {
        cancelLogin();
        clearModelDiscovery();
        session = null;
    }

    static void writeProtected(Path file, String content) throws Exception {
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), ".chatgpt-", ".tmp");
        try {
            protect(temporary);
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException e) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
            protect(file);
        } finally { Files.deleteIfExists(temporary); }
    }

    private static void protect(Path file) throws Exception {
        if (Files.getFileAttributeView(file, java.nio.file.attribute.PosixFileAttributeView.class) != null) {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } else {
            AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
            if (acl == null) throw new IllegalStateException("Credential storage does not support protected permissions");
            acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(Files.getOwner(file))
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
        }
    }

    private static JsonObject getJson(String uri, String bearer) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(uri)).timeout(Duration.ofSeconds(30)).GET();
        if (bearer != null) builder.header("Authorization", "Bearer " + bearer);
        return checked(HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString()));
    }

    private static JsonObject postForm(String uri, Map<String, String> params) throws Exception {
        return checked(HTTP.send(formRequest(uri, params), HttpResponse.BodyHandlers.ofString()));
    }

    private static HttpRequest formRequest(String uri, Map<String, String> params) {
        return HttpRequest.newBuilder(URI.create(uri)).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(params))).build();
    }

    private static JsonObject checked(HttpResponse<String> response) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String code = "request_failed";
            try {
                JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
                var error = body.get("error");
                code = error.isJsonObject() ? string(error.getAsJsonObject(), "code") : error.getAsString();
                if (!code.matches("[a-zA-Z0-9_]{1,100}")) code = "request_failed";
            } catch (RuntimeException ignored) { }
            throw new IllegalStateException("OpenAI HTTP " + response.statusCode() + " (" + code + ")");
        }
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    static Map<String, String> parseQuery(String raw) {
        Map<String, String> query = new LinkedHashMap<>();
        if (raw == null || raw.length() > 16384) throw new IllegalArgumentException("Invalid callback query");
        for (String part : raw.split("&")) {
            String[] pair = part.split("=", 2);
            String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
            if (query.putIfAbsent(key, pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "") != null)
                throw new IllegalArgumentException("Duplicate callback parameter");
        }
        return query;
    }

    private static String form(Map<String, String> values) {
        return values.entrySet().stream().map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8)).collect(Collectors.joining("&"));
    }

    private static String random() { byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }

    public static String safeError(Throwable error) {
        while (error.getCause() != null) error = error.getCause();
        return error instanceof IllegalStateException || error instanceof SecurityException
                ? error.getMessage() : error.getClass().getSimpleName();
    }
}
