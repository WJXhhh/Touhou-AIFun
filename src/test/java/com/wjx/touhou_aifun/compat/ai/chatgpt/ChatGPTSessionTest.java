package com.wjx.touhou_aifun.compat.ai.chatgpt;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ChatGPTSessionTest {
    @TempDir Path temporary;

    @Test void guiMetadataCannotExposeCredentials() {
        var saved = JsonParser.parseString("{\"client_id\":\"public-client\",\"email\":\"owner@example.test\",\"access_token\":\"access-secret\",\"refresh_token\":\"refresh-secret\",\"id_token\":\"id-secret\",\"scopes\":[\"chatgpt.tokens.use.direct\"]}").getAsJsonObject();
        var metadata = ChatGPTSession.publicProfile(saved);
        assertEquals("owner@example.test", metadata.get("email").getAsString());
        assertTrue(metadata.get("sharing").getAsBoolean());
        assertTrue(metadata.get("connected").getAsBoolean());
        assertFalse(metadata.toString().contains("secret"));
        assertEquals(java.util.Set.of("client_id", "email", "connected", "sharing"), metadata.keySet());
    }

    @Test void identityOnlyLoginDoesNotAuthorizeInference() {
        assertFalse(ChatGPTSession.permitted(JsonParser.parseString("{\"scopes\":[\"openid\",\"email\"]}").getAsJsonObject()));
        assertTrue(ChatGPTSession.permitted(JsonParser.parseString("{\"scopes\":[\"chatgpt.tokens.use.direct\"]}").getAsJsonObject()));
    }

    @Test void callbackRejectsAmbiguousDuplicateParameters() {
        assertThrows(IllegalArgumentException.class, () -> ChatGPTSession.parseQuery("state=valid&state=attacker"));
        assertThrows(IllegalArgumentException.class, () -> ChatGPTSession.parseQuery("%73tate=valid&state=attacker"));
        assertEquals("a+b", ChatGPTSession.parseQuery("code=a%2Bb&state=s").get("code"));
    }

    @Test void credentialFileIsReplacedAndProtected() throws Exception {
        Path file = temporary.resolve("auth.json");
        ChatGPTSession.writeProtected(file, "old-token");
        ChatGPTSession.writeProtected(file, "rotated-token");
        assertEquals("rotated-token", Files.readString(file));
        if (Files.getFileAttributeView(file, java.nio.file.attribute.AclFileAttributeView.class) != null) {
            var acl = Files.getFileAttributeView(file, java.nio.file.attribute.AclFileAttributeView.class).getAcl();
            assertEquals(1, acl.size()); assertEquals(Files.getOwner(file), acl.get(0).principal());
        } else {
            assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file));
        }
        try (var files = Files.list(temporary)) { assertEquals(1, files.count()); }
    }
}
