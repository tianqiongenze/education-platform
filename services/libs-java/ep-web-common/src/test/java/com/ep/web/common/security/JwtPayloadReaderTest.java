package com.ep.web.common.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * D-227: {@link JwtPayloadReader} — unverified payload claim extraction.
 */
class JwtPayloadReaderTest {

    private static String token(String payloadJson) {
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        return "eyJhbGciOiJSUzI1NiJ9." + payload + ".c2ln";
    }

    @Test
    @DisplayName("extracts string and numeric claims from a well-formed JWS")
    void extractsClaims() {
        String t = token("{\"sub\":\"42\",\"jti\":\"a1b2-uuid\",\"exp\":1893456000}");
        assertEquals("a1b2-uuid", JwtPayloadReader.stringClaim(t, "jti"));
        assertEquals(1893456000L, JwtPayloadReader.numericClaim(t, "exp"));
        assertEquals("42", JwtPayloadReader.stringClaim(t, "sub"));
    }

    @Test
    @DisplayName("absent claim returns null")
    void missingClaimIsNull() {
        String t = token("{\"sub\":\"42\"}");
        assertNull(JwtPayloadReader.stringClaim(t, "jti"));
        assertNull(JwtPayloadReader.numericClaim(t, "exp"));
    }

    @Test
    @DisplayName("malformed inputs return null instead of throwing (fail-open contract)")
    void malformedReturnsNull() {
        assertNull(JwtPayloadReader.stringClaim(null, "jti"));
        assertNull(JwtPayloadReader.stringClaim("", "jti"));
        assertNull(JwtPayloadReader.stringClaim("not-a-jwt", "jti"));
        assertNull(JwtPayloadReader.stringClaim("a.b", "jti"));
        assertNull(JwtPayloadReader.stringClaim(token("{\"jti\":\"x\"}") + ".extra-garbage?", "jti"));
    }

    @Test
    @DisplayName("both padded and unpadded base64url payloads decode")
    void base64PaddingTolerance() {
        String padded = Base64.getUrlEncoder()
                .encodeToString("{\"jti\":\"pad\"}".getBytes(StandardCharsets.UTF_8));
        String unpadded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"jti\":\"pad\"}".getBytes(StandardCharsets.UTF_8));
        assertEquals("pad", JwtPayloadReader.stringClaim("H." + padded + ".S", "jti"));
        assertEquals("pad", JwtPayloadReader.stringClaim("H." + unpadded + ".S", "jti"));
    }

    @Test
    @DisplayName("escaped JSON string values are unescaped")
    void unescapesValues() {
        String t = token("{\"jti\":\"a\\\"b\\\\c\"}");
        assertEquals("a\"b\\c", JwtPayloadReader.stringClaim(t, "jti"));
    }

    @Test
    @DisplayName("claims nested inside other JSON strings are not confused with separators")
    void survivesRealisticPayload() {
        String t = token("{\"iss\":\"https://auth.example.com\",\"sub\":\"1001\",\"aud\":\"ep-bff\","
                + "\"jti\":\"550e8400-e29b-41d4-a716-446655440000\",\"iat\":1758153600,\"exp\":1758157200}");
        assertEquals("550e8400-e29b-41d4-a716-446655440000", JwtPayloadReader.stringClaim(t, "jti"));
        assertEquals(1758157200L, JwtPayloadReader.numericClaim(t, "exp"));
    }
}
