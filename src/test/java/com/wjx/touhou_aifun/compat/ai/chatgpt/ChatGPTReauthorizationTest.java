package com.wjx.touhou_aifun.compat.ai.chatgpt;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraftforge.fml.loading.FMLPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the real login URL and credential lifecycle without contacting OpenAI. */
class ChatGPTReauthorizationTest {
    @TempDir Path temporary;
    private Field configPath;
    private Object previousConfigPath;

    @BeforeEach void isolateCredentials() throws Exception {
        configPath = FMLPaths.class.getDeclaredField("absolutePath");
        configPath.setAccessible(true);
        previousConfigPath = configPath.get(FMLPaths.CONFIGDIR);
        configPath.set(FMLPaths.CONFIGDIR, temporary);
        ChatGPTSession.shutdown();
    }

    @AfterEach void restorePaths() throws Exception {
        ChatGPTSession.shutdown();
        configPath.set(FMLPaths.CONFIGDIR, previousConfigPath);
    }

    @Test void repeatedLoginReusesRegistrationAndHostWithFreshTransactionSecrets() throws Exception {
        saveRegistration();
        Map<String, String> first = login();
        ChatGPTSession.cancelLogin();
        Map<String, String> second = login();
        assertReturningRegistration(first);
        assertReturningRegistration(second);
        assertEquals(first.get("ext_agent_host_id"), second.get("ext_agent_host_id"));
        for (String field : new String[] {"state", "nonce", "code_challenge"})
            assertNotEquals(first.get(field), second.get(field), field);
        assertFalse(second.containsKey("agent_name_hint"));
    }

    @Test void reopeningPendingFirstLoginReusesTheSameAuthorizationTransaction() throws Exception {
        String first = ChatGPTSession.login(0, false, ignored -> { });
        String reopened = ChatGPTSession.login(0, false, ignored -> { });
        assertEquals(first, reopened);
        assertTrue(ChatGPTSession.uiMetadata().get("pending").getAsBoolean());
        ChatGPTSession.cancelLogin();
        String afterCancellation = ChatGPTSession.login(0, false, ignored -> { });
        assertNotEquals(first, afterCancellation);
    }

    @Test void reopeningPendingReauthorizationReusesTheSameAuthorizationTransaction() throws Exception {
        saveRegistration();
        assertEquals(login(), login());
    }

    @Test void restartingSessionReusesPersistedRegistrationAndHost() throws Exception {
        saveRegistration();
        Map<String, String> first = login();
        ChatGPTSession.shutdown();
        Map<String, String> afterRestart = login();
        assertReturningRegistration(afterRestart);
        assertEquals(first.get("ext_agent_host_id"), afterRestart.get("ext_agent_host_id"));
    }

    @Test void signingOutRetainsRegistrationForNextLogin() throws Exception {
        saveRegistration(); // No refresh token: this test never makes a revocation request.
        Map<String, String> first = login();
        ChatGPTSession.logout();
        JsonObject saved = JsonParser.parseString(Files.readString(
                ChatGPTSession.directory().resolve("session.json"))).getAsJsonObject();
        assertEquals("oaiapp_saved_registration", saved.get("client_id").getAsString());
        assertFalse(saved.has("access_token"));
        Map<String, String> afterLogout = login();
        assertReturningRegistration(afterLogout);
        assertEquals(first.get("ext_agent_host_id"), afterLogout.get("ext_agent_host_id"));
    }

    @Test void missingLocalRegistrationStartsDynamicRegistration() throws Exception {
        Map<String, String> initial = login();
        assertEquals("dynamic_agent_client", initial.get("client_id"));
        assertEquals("Touhou AIFun", initial.get("agent_name_hint"));
        ChatGPTSession.cancelLogin();
        Map<String, String> retry = login();
        assertEquals("dynamic_agent_client", retry.get("client_id"));
        assertEquals(initial.get("ext_agent_host_id"), retry.get("ext_agent_host_id"));
    }

    @Test void anotherDeviceCanReuseRegistrationWithItsOwnHostAndNoCopiedTokens() throws Exception {
        saveRegistration();
        Map<String, String> original = login();
        String client = ChatGPTSession.uiMetadata().get("client_id").getAsString();
        ChatGPTSession.shutdown();
        configPath.set(FMLPaths.CONFIGDIR, temporary.resolve("other-device"));
        Map<String, String> imported = ChatGPTSession.parseQuery(URI.create(
                ChatGPTSession.loginWithRegistration(0, client, ignored -> { })).getRawQuery());
        assertReturningRegistration(imported);
        assertNotEquals(original.get("ext_agent_host_id"), imported.get("ext_agent_host_id"));
        assertFalse(ChatGPTSession.authenticationAvailable());
        assertFalse(Files.exists(ChatGPTSession.directory().resolve("session.json")));
        assertEquals(0, ChatGPTSession.uiMetadata().getAsJsonArray("accounts").size());
        assertEquals(imported, ChatGPTSession.parseQuery(URI.create(
                ChatGPTSession.loginWithRegistration(0, client, ignored -> { })).getRawQuery()));
        assertEquals(imported, login()); // A newly opened screen has no imported ID in local state.
    }

    @Test void cancelledCrossDeviceLoginDoesNotReplaceActiveAccount() throws Exception {
        saveRegistration();
        String original = Files.readString(ChatGPTSession.directory().resolve("session.json"));
        Map<String, String> params = ChatGPTSession.parseQuery(URI.create(
                ChatGPTSession.loginWithRegistration(0, "oaiapp_other_registration", ignored -> { })).getRawQuery());
        URI denied = URI.create(params.get("redirect_uri") + "?state=" + params.get("state") + "&error=access_denied");
        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(denied)
                .timeout(java.time.Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(400, response.statusCode());
        assertEquals(original, Files.readString(ChatGPTSession.directory().resolve("session.json")));
        assertEquals("oaiapp_saved_registration", ChatGPTSession.uiMetadata().get("client_id").getAsString());
    }

    @Test void invalidRegistrationCannotSilentlyFallBackToCreatingAnApp() throws Exception {
        for (String invalid : new String[] {"", " ", "dynamic_agent_client", "bad&id=other", "https://example.test", "a".repeat(257)})
            assertThrows(IllegalStateException.class, () -> ChatGPTSession.loginWithRegistration(0, invalid, ignored -> { }));
        assertFalse(ChatGPTSession.uiMetadata().get("pending").getAsBoolean());
        assertFalse(Files.exists(ChatGPTSession.directory().resolve("host.json")));
    }

    private void saveRegistration() throws Exception {
        ChatGPTSession.writeProtected(ChatGPTSession.directory().resolve("session.json"),
                "{\"client_id\":\"oaiapp_saved_registration\",\"subject\":\"test-user\","
                        + "\"email\":\"owner@example.test\",\"access_token\":\"test-access\"}");
    }

    private Map<String, String> login() throws Exception {
        return ChatGPTSession.parseQuery(URI.create(ChatGPTSession.login(0, false, ignored -> { })).getRawQuery());
    }

    private void assertReturningRegistration(Map<String, String> params) {
        assertEquals("oaiapp_saved_registration", params.get("client_id"));
        assertFalse(params.containsKey("agent_name_hint"));
    }
}
