package com.wjx.touhou_aifun.compat.ai.chatgpt;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class OpenAIIdentityTest {
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    @Test void validatesSignedIdentityAndRejectsWrongNonceAudienceExpiryAndTampering() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        RSAPublicKey key = (RSAPublicKey) pair.getPublic();
        JsonObject jwk = new JsonObject();
        jwk.addProperty("kid", "test-key"); jwk.addProperty("kty", "RSA");
        jwk.addProperty("n", unsigned(key.getModulus().toByteArray()));
        jwk.addProperty("e", unsigned(key.getPublicExponent().toByteArray()));
        JsonArray keys = new JsonArray(); keys.add(jwk);
        JsonObject jwks = new JsonObject(); jwks.add("keys", keys);
        String payload = "{\"iss\":\"https://auth.openai.com\",\"aud\":\"issued-client\",\"sub\":\"user-1\",\"exp\":2000,\"nonce\":\"nonce-1\"}";
        String jwt = signed(pair, payload);
        assertEquals("user-1", OpenAIIdentity.verify(jwt, jwks, "issued-client", "nonce-1", 1000).get("sub").getAsString());
        assertThrows(SecurityException.class, () -> OpenAIIdentity.verify(jwt, jwks, "issued-client", "wrong", 1000));
        assertThrows(SecurityException.class, () -> OpenAIIdentity.verify(jwt, jwks, "wrong-client", "nonce-1", 1000));
        assertThrows(SecurityException.class, () -> OpenAIIdentity.verify(jwt, jwks, "issued-client", "nonce-1", 2000));
        String tampered = jwt.split("\\.")[0] + "." + encode(payload.replace("user-1", "attacker")) + "." + jwt.split("\\.")[2];
        assertThrows(SecurityException.class, () -> OpenAIIdentity.verify(tampered, jwks, "issued-client", "nonce-1", 1000));
    }

    private static String signed(KeyPair pair, String payload) throws Exception {
        String data = encode("{\"alg\":\"RS256\",\"kid\":\"test-key\"}") + "." + encode(payload);
        Signature signature = Signature.getInstance("SHA256withRSA"); signature.initSign(pair.getPrivate());
        signature.update(data.getBytes(StandardCharsets.US_ASCII));
        return data + "." + ENCODER.encodeToString(signature.sign());
    }
    private static String encode(String value) { return ENCODER.encodeToString(value.getBytes(StandardCharsets.UTF_8)); }
    private static String unsigned(byte[] bytes) { return ENCODER.encodeToString(bytes[0] == 0 ? java.util.Arrays.copyOfRange(bytes, 1, bytes.length) : bytes); }
}
