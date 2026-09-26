package com.ep.web.common.security;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * D-227: minimal JWT payload reader that decodes a JWS payload segment
 * <b>without any signature verification</b>.
 *
 * <p>Deliberate design decision: this helper exists for edge concerns only —
 * reading the {@code jti} (token id) for the revocation blacklist and the
 * {@code exp} for TTL calculation. Signature verification is the responsibility
 * of the downstream authentication component (e.g. the BFF {@code JwtService}
 * verifies the RS256 signature). This class therefore never trusts a decoded
 * token as "authenticated"; callers must only use it for advisory/cutoff
 * decisions such as blacklist lookups, never as an identity proof.
 *
 * <p>Intentionally dependency-free (no Jackson/nimbus): ep-web-common is a thin
 * infrastructure JAR and must not force a JSON library onto consumers.
 */
public final class JwtPayloadReader {

    private JwtPayloadReader() {
    }

    /** Cache for the per-claim extraction patterns (claims are few and fixed). */
    private static final Map<String, Pattern> PATTERN_CACHE = new ConcurrentHashMap<>();

    /**
     * Extracts a string-valued claim from the JWT payload (no signature check).
     *
     * @param token raw JWT (header.payload.signature)
     * @param claim claim name, e.g. {@code "jti"}
     * @return the claim value, or {@code null} when the token is malformed or
     *         the claim is absent
     */
    public static String stringClaim(String token, String claim) {
        String payload = decodePayload(token);
        if (payload == null) {
            return null;
        }
        Matcher m = patternFor(claim).matcher(payload);
        return m.find() ? unescape(m.group(1)) : null;
    }

    /**
     * Extracts a numeric claim from the JWT payload (no signature check).
     *
     * @param token raw JWT (header.payload.signature)
     * @param claim claim name, e.g. {@code "exp"}
     * @return the claim value, or {@code null} when the token is malformed or
     *         the claim is absent
     */
    public static Long numericClaim(String token, String claim) {
        String payload = decodePayload(token);
        if (payload == null) {
            return null;
        }
        Pattern p = Pattern.compile("\"" + Pattern.quote(claim) + "\"\\s*:\\s*(-?\\d+)");
        Matcher m = p.matcher(payload);
        return m.find() ? Long.valueOf(m.group(1)) : null;
    }

    /**
     * Base64url-decodes the payload segment of a JWS. Returns {@code null} for
     * any input that is not structurally a JWT (fail-open by contract — the
     * filter simply skips the blacklist check for such tokens).
     */
    static String decodePayload(String token) {
        if (token == null) {
            return null;
        }
        String t = token.trim();
        int first = t.indexOf('.');
        int last = t.lastIndexOf('.');
        // Requires at least "header.payload." — base64url contains no '.', so
        // these two separators always bracket the payload of a well-formed JWS.
        if (first <= 0 || last <= first) {
            return null;
        }
        try {
            byte[] json = Base64.getUrlDecoder().decode(t.substring(first + 1, last));
            return new String(json, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Pattern patternFor(String claim) {
        return PATTERN_CACHE.computeIfAbsent(claim, c ->
                Pattern.compile("\"" + Pattern.quote(c) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\""));
    }

    /** Unescapes the JSON string escapes that can legitimately appear in claim values. */
    private static String unescape(String raw) {
        if (raw.indexOf('\\') < 0) {
            return raw;
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c != '\\' || i + 1 >= raw.length()) {
                sb.append(c);
                continue;
            }
            char next = raw.charAt(++i);
            switch (next) {
                case '"' -> sb.append('"');
                case '\\' -> sb.append('\\');
                case '/' -> sb.append('/');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'u' -> {
                    if (i + 4 < raw.length()) {
                        sb.append((char) Integer.parseInt(raw.substring(i + 1, i + 5), 16));
                        i += 4;
                    }
                }
                default -> sb.append(next);
            }
        }
        return sb.toString();
    }
}
