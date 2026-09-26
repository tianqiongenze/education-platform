package com.ep.business.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.util.Base64;
import java.util.UUID;

import com.ep.web.common.security.JwtPayloadReader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * D-227: {@link JwtTokenService} — RS256 issuance with a fresh {@code jti}
 * (the revocation handle consumed by JwtBlacklistService / JwtRevocationFilter).
 */
class JwtTokenServiceTest {

    private static String privateKeyPem;
    private static long issuedAtSeconds;

    @BeforeAll
    static void generateKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        privateKeyPem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(pair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----";
        issuedAtSeconds = System.currentTimeMillis() / 1000;
    }

    private static JwtTokenService service() {
        return new JwtTokenService(privateKeyPem, 3600);
    }

    @Test
    @DisplayName("issued token is a three-segment JWS carrying a UUID jti and a 1h expiry")
    void issuesTokenWithJti() {
        JwtTokenService.IssuedToken issued = service().issue(42L, "demo", "13800000000", "demo@ep.com");

        assertNotNull(issued.token());
        String jti = issued.jti();
        assertDoesParseAsUuid(jti);

        String[] segments = issued.token().split("\\.");
        assertEquals(3, segments.length, "compact JWS must have header.payload.signature");

        assertEquals("42", JwtPayloadReader.stringClaim(issued.token(), "sub"));
        assertEquals(jti, JwtPayloadReader.stringClaim(issued.token(), "jti"),
                "the jti returned to the caller must be the one inside the token");
        assertEquals("demo", JwtPayloadReader.stringClaim(issued.token(), "username"));
        assertEquals("13800000000", JwtPayloadReader.stringClaim(issued.token(), "phone"));
        assertEquals("user", JwtPayloadReader.stringClaim(issued.token(), "role"));

        Long exp = JwtPayloadReader.numericClaim(issued.token(), "exp");
        assertNotNull(exp);
        long delta = exp - issuedAtSeconds;
        assertTrue(delta >= 3599 && delta <= 3610, "exp must sit ~1h in the future, was " + delta);
        assertEquals(3600, issued.expiresIn());
    }

    private static void assertDoesParseAsUuid(String jti) {
        UUID.fromString(jti); // throws on malformed
    }

    @Test
    @DisplayName("every issue produces a unique jti")
    void jtiIsUniquePerToken() {
        JwtTokenService svc = service();
        String first = svc.issue(1L, "a", "p", "e").jti();
        String second = svc.issue(1L, "a", "p", "e").jti();
        assertNotEquals(first, second);
    }

    @Test
    @DisplayName("blank PEM → clear IllegalStateException at issue time (not at construction/boot)")
    void missingKeyFailsFastAtIssue() {
        JwtTokenService unconfigured = new JwtTokenService(" ", 3600);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> unconfigured.issue(1L, "a", "p", "e"));
        assertTrue(ex.getMessage().contains("JWT private key not configured"));
    }

    @Test
    @DisplayName("non-positive TTL is rejected at construction")
    void invalidTtlRejected() {
        assertThrows(IllegalArgumentException.class, () -> new JwtTokenService(privateKeyPem, 0));
        assertThrows(IllegalArgumentException.class, () -> new JwtTokenService(privateKeyPem, -5));
    }

    @Test
    @DisplayName("invalid PEM body is rejected with a clear error")
    void invalidPemRejected() {
        JwtTokenService bad = new JwtTokenService("-----BEGIN PRIVATE KEY-----\nnot-base64!!\n-----END PRIVATE KEY-----", 3600);
        assertThrows(IllegalStateException.class, () -> bad.issue(1L, "a", "p", "e"));
    }

    @Test
    @DisplayName("parsePrivateKey round-trips a generated PKCS8 key")
    void parsePrivateKeyRoundTrip() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        PrivateKey expected = generator.generateKeyPair().getPrivate();
        String pem = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(expected.getEncoded());
        PrivateKey parsed = JwtTokenService.parsePrivateKey(pem);
        assertEquals(expected, parsed);
    }
}
