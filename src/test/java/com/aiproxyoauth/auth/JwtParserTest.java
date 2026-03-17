package com.aiproxyoauth.auth;

import com.aiproxyoauth.util.JwtParser;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class JwtParserTest {

    private static String makeJwt(String payloadJson) {
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        return "header." + encoded + ".sig";
    }

    @Test void validJwt_claimsReturned() {
        JsonNode claims = JwtParser.parseClaims(makeJwt("{\"sub\":\"user123\",\"exp\":9999999999}"));
        assertNotNull(claims);
        assertEquals("user123", claims.path("sub").asText());
    }

    @Test void nullToken_returnsNull() {
        assertNull(JwtParser.parseClaims(null));
    }

    @Test void tokenWithNoDots_returnsNull() {
        assertNull(JwtParser.parseClaims("nodotsatall"));
    }

    @Test void tokenWithTwoParts_returnsNull() {
        assertNull(JwtParser.parseClaims("header.payload"));
    }

    @Test void tokenWithFourParts_returnsNull() {
        assertNull(JwtParser.parseClaims("a.b.c.d"));
    }

    @Test void emptyPayloadPart_returnsNull() {
        assertNull(JwtParser.parseClaims("header..sig"));
    }

    @Test void nonJsonPayload_returnsNull() {
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("not-json-at-all".getBytes(StandardCharsets.UTF_8));
        assertNull(JwtParser.parseClaims("header." + encoded + ".sig"));
    }

    @Test void nonObjectJsonPayload_returnsNull() {
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("[1,2,3]".getBytes(StandardCharsets.UTF_8));
        assertNull(JwtParser.parseClaims("header." + encoded + ".sig"));
    }

    @Test void base64Padding_mod4_0_decodesCorrectly() {
        // {"xyz":1} = 9 bytes -> 12 base64url chars -> 12 % 4 = 0 (no padding needed)
        JsonNode claims = JwtParser.parseClaims(makeJwt("{\"xyz\":1}"));
        assertNotNull(claims);
        assertEquals(1, claims.path("xyz").asInt());
    }

    @Test void base64Padding_mod4_2_decodesCorrectly() {
        // {"x":1} = 7 bytes -> 10 base64url chars -> 10 % 4 = 2 (needs "==" padding)
        JsonNode claims = JwtParser.parseClaims(makeJwt("{\"x\":1}"));
        assertNotNull(claims);
        assertEquals(1, claims.path("x").asInt());
    }

    @Test void base64Padding_mod4_3_decodesCorrectly() {
        // {"x":12} = 8 bytes -> 11 base64url chars -> 11 % 4 = 3 (needs "=" padding)
        JsonNode claims = JwtParser.parseClaims(makeJwt("{\"x\":12}"));
        assertNotNull(claims);
        assertEquals(12, claims.path("x").asInt());
    }

    @Test void base64Padding_mod4_1_returnsNull() {
        // Raw segment of length 1 mod 4 = 1 is structurally invalid base64.
        // JwtParser adds 3 "=" signs -> "a===" which the JDK decoder rejects.
        assertNull(JwtParser.parseClaims("header.a.sig"));
    }

    @Test void deriveAccountId_withNestedClaim_returnsValue() {
        String payload = "{\"https://api.openai.com/auth\":{\"chatgpt_account_id\":\"acct-xyz\"}}";
        String result = JwtParser.deriveAccountId(makeJwt(payload));
        assertEquals("acct-xyz", result);
    }

    @Test void deriveAccountId_missingAuthClaim_returnsNull() {
        String result = JwtParser.deriveAccountId(makeJwt("{\"sub\":\"user123\"}"));
        assertNull(result);
    }
}
