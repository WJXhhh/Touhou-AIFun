package com.wjx.touhou_aifun.vision;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisionSiteSnapshotTest {
    @AfterEach
    void clearSnapshot() {
        ClientVisionSitesSnapshot.clear();
    }

    @Test
    void secretFreeSiteRetainsOnlyPresenceFlag() {
        VisionSite site = VisionSite.fromJson(JsonParser.parseString("""
                {"id":"remote","display_name":"Remote","provider":"custom",
                 "endpoint":"https://example.invalid","model":"vision","api_key_present":true}
                """).getAsJsonObject());

        assertTrue(site.apiKey().isEmpty());
        assertTrue(site.apiKeyPresent());
        assertTrue(site.toJson(false).get("api_key_present").getAsBoolean());
    }

    @Test
    void clientSnapshotParsesWithoutInventingASecret() {
        ClientVisionSitesSnapshot.replaceFromJson("""
                [{"id":"remote","display_name":"Remote","provider":"custom",
                  "endpoint":"https://example.invalid","model":"vision","api_key_present":true}]
                """);

        assertEquals(1, ClientVisionSitesSnapshot.all().size());
        assertTrue(ClientVisionSitesSnapshot.all().get(0).apiKey().isEmpty());
        assertTrue(ClientVisionSitesSnapshot.all().get(0).apiKeyPresent());
    }

    @Test
    void endpointValidationRejectsNonHttpAndMalformedValues() {
        VisionSite valid = new VisionSite("valid", "Valid", "custom",
                "https://example.com/v1/chat/completions", "vision", "key", false);
        VisionSite file = new VisionSite("file", "File", "custom",
                "file:///tmp/secret", "vision", "key", false);
        VisionSite malformed = new VisionSite("bad", "Bad", "custom",
                "not a uri", "vision", "key", false);

        assertTrue(valid.hasValidHttpEndpoint());
        assertTrue(!file.hasValidHttpEndpoint());
        assertTrue(!malformed.hasValidHttpEndpoint());
    }

    @Test
    void secretFreeSerializationAlsoRedactsCustomAuthenticationHeaders() {
        VisionSite site = new VisionSite("remote", "Remote", "custom",
                "https://example.com/v1", "vision", "key", false);
        site.setHeader("Authorization", "Bearer secret-header-token");
        site.setHeader("X-API-Key", "another-secret");

        var clientJson = site.toJson(false);
        assertTrue(!clientJson.toString().contains("secret-header-token"));
        assertTrue(!clientJson.toString().contains("another-secret"));
        assertEquals(0, clientJson.getAsJsonObject("headers").size());
    }
}
