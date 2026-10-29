package com.ep.business.auth;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

import io.jsonwebtoken.Jwts;

/**
 * D-227: RS256 JWT issuer for the {@code /api/auth} login loop.
 *
 * <p>Issues tokens carrying a {@code jti} (UUID) claim — the seam required by
 * the logout/revocation loop: {@code POST /api/auth/logout} blacklists the
 * {@code jti} via {@code JwtBlacklistService} (ep-web-common) and
 * {@code JwtRevocationFilter} rejects blacklisted tokens with 401.</p>
 *
 * <p><b>Key management (D-117 parity with the BFF JwtService):</b> the private
 * key is resolved from (highest first) {@code JWT_PRIVATE_KEY_PATH} (default
 * {@code /etc/jwt/private.pem}, K8s Secret mount), {@code JWT_PRIVATE_KEY}
 * (base64 inline), or the Spring property {@code jwt.rsa.private-key-pem}
 * (dev inline PEM). Resolution is lazy: a missing key does not break service
 * boot, but {@link #issue(long, String, String, String)} fails fast with a
 * clear {@link IllegalStateException} — never a silent mis-issue.</p>
 *
 * <p>Same stack as the BFF ({@code jjwt 0.12.6}, RS256) so issued tokens stay
 * cross-verifiable by the BFF's public key. Note the BFF's {@code JwtService}
 * does not yet emit {@code jti} on its own tokens; until it does, only tokens
 * issued here participate in the revocation loop (documented seam).</p>
 */
public class JwtTokenService {

    /** Default private key file path (K8s Secret mount point). */
    public static final String DEFAULT_PRIVATE_KEY_PATH = "/etc/jwt/private.pem";

    private final String privateKeyPem;
    private final long ttlSeconds;
    private volatile PrivateKey privateKey;

    /** Test/direct constructor. A blank {@code privateKeyPem} makes issuance fail fast. */
    public JwtTokenService(String privateKeyPem, long ttlSeconds) {
        if (ttlSeconds <= 0) {
            throw new IllegalArgumentException("ttlSeconds must be positive");
        }
        this.privateKeyPem = privateKeyPem;
        this.ttlSeconds = ttlSeconds;
    }

    /**
     * Factory used by {@code AuthConfiguration}: resolves the PEM from
     * env vars / file paths first (D-117), falling back to the Spring property.
     *
     * @param springPem  value of {@code jwt.rsa.private-key-pem} (may be blank)
     * @param ttlSeconds token lifetime in seconds
     */
    public static JwtTokenService fromDefaultSources(String springPem, long ttlSeconds) {
        return new JwtTokenService(resolvePem(springPem), ttlSeconds);
    }

    /**
     * Issues a signed token with {@code jti = UUID}.
     *
     * @return the issued token with its jti and lifetime
     * @throws IllegalStateException when no private key is configured
     */
    public IssuedToken issue(long userId, String username, String phone, String email, String role) {
        Instant now = Instant.now();
        String jti = UUID.randomUUID().toString();
        String token = Jwts.builder()
                .id(jti)
                .subject(String.valueOf(userId))
                .claim("username", username)
                .claim("phone", phone)
                .claim("email", email)
                // D-242: role comes from the User aggregate (P1-1); null
                // rows (pre-migration) default to "user" at the caller.
                .claim("role", role == null || role.isBlank() ? "user" : role)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ttlSeconds)))
                .signWith(privateKey(), Jwts.SIG.RS256)
                .compact();
        return new IssuedToken(token, jti, ttlSeconds);
    }

    private PrivateKey privateKey() {
        PrivateKey key = this.privateKey;
        if (key == null) {
            synchronized (this) {
                key = this.privateKey;
                if (key == null) {
                    key = this.privateKey = parsePrivateKey(privateKeyPem);
                }
            }
        }
        return key;
    }

    /** D-117 key resolution: env file path → env base64 inline → Spring property. */
    static String resolvePem(String springPem) {
        String path = System.getenv().getOrDefault("JWT_PRIVATE_KEY_PATH", DEFAULT_PRIVATE_KEY_PATH);
        try {
            java.nio.file.Path file = java.nio.file.Path.of(path);
            if (java.nio.file.Files.isRegularFile(file)) {
                String content = java.nio.file.Files.readString(file, StandardCharsets.UTF_8).trim();
                if (!content.isEmpty()) {
                    return content;
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read JWT private key from " + path
                    + ": " + e.getMessage(), e);
        }
        String inline = System.getenv("JWT_PRIVATE_KEY");
        if (inline != null && !inline.isBlank()) {
            try {
                return new String(Base64.getDecoder().decode(inline.trim()), StandardCharsets.UTF_8).trim();
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("JWT_PRIVATE_KEY is set but is not valid base64", e);
            }
        }
        return springPem == null ? "" : springPem.trim();
    }

    static PrivateKey parsePrivateKey(String pem) {
        if (pem == null || pem.isBlank()) {
            throw new IllegalStateException("JWT private key not configured. Set one of:\n"
                    + "  - JWT_PRIVATE_KEY_PATH pointing to a PEM file (default: " + DEFAULT_PRIVATE_KEY_PATH + ")\n"
                    + "  - JWT_PRIVATE_KEY base64-encoded inline env var\n"
                    + "  - jwt.rsa.private-key-pem Spring property (dev only)");
        }
        try {
            String body = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
            return KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)));
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Invalid JWT private key PEM: " + e.getMessage(), e);
        }
    }

    /**
     * @param token     signed compact JWS
     * @param jti       token id (UUID) — the revocation handle
     * @param expiresIn token lifetime in seconds
     */
    public record IssuedToken(String token, String jti, long expiresIn) {
    }
}
