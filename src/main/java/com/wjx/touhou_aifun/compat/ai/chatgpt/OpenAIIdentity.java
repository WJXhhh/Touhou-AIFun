package com.wjx.touhou_aifun.compat.ai.chatgpt;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

/** OIDC verification using the issuer's JWKS, never untrusted token-supplied URLs. */
public final class OpenAIIdentity {
    private OpenAIIdentity() { }

    public static JsonObject verify(String jwt, JsonObject jwks, String clientId, String nonce, long now)
            throws Exception {
        String[] parts = jwt.split("\\.", -1);
        if (parts.length != 3) throw new SecurityException("Invalid ID token");
        Base64.Decoder decoder = Base64.getUrlDecoder();
        JsonObject header = JsonParser.parseString(new String(decoder.decode(parts[0]), StandardCharsets.UTF_8)).getAsJsonObject();
        if (!"RS256".equals(string(header, "alg"))) throw new SecurityException("Unsupported ID token signature");
        JsonObject key = null;
        for (JsonElement element : jwks.getAsJsonArray("keys")) {
            JsonObject candidate = element.getAsJsonObject();
            if (string(header, "kid").equals(string(candidate, "kid"))
                    && "RSA".equals(string(candidate, "kty"))
                    && (!candidate.has("use") || "sig".equals(string(candidate, "use")))
                    && (!candidate.has("alg") || "RS256".equals(string(candidate, "alg")))) {
                key = candidate;
                break;
            }
        }
        if (key == null) throw new SecurityException("Unknown ID token signing key");
        var publicKey = KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(
                new BigInteger(1, decoder.decode(string(key, "n"))),
                new BigInteger(1, decoder.decode(string(key, "e")))));
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initVerify(publicKey);
        signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        if (!signature.verify(decoder.decode(parts[2]))) throw new SecurityException("Invalid ID token signature");
        JsonObject claims = JsonParser.parseString(new String(decoder.decode(parts[1]), StandardCharsets.UTF_8)).getAsJsonObject();
        JsonElement audience = claims.get("aud");
        boolean matches = audience != null && (audience.isJsonArray()
                ? audience.getAsJsonArray().asList().stream().anyMatch(value -> clientId.equals(value.getAsString()))
                : clientId.equals(audience.getAsString()));
        if (!"https://auth.openai.com".equals(string(claims, "iss")) || !matches
                || !claims.has("exp") || claims.get("exp").getAsLong() <= now
                || string(claims, "sub").isBlank()
                || (nonce != null && !nonce.equals(string(claims, "nonce")))
                || (claims.has("nbf") && claims.get("nbf").getAsLong() > now + 30)
                || (claims.has("azp") && !clientId.equals(string(claims, "azp")))) {
            throw new SecurityException("ID token identity validation failed");
        }
        return claims;
    }

    public static String string(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }
}
