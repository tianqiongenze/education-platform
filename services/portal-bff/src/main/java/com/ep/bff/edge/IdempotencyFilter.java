package com.ep.bff.edge;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Set;

/**
 * Idempotency filter (SDD §5.1, ADR-T06; v7 upgrade D-233 — Stripe-style
 * response replay).
 *
 * <p>Implements request de-duplication using the {@code Idempotency-Key}
 * header. When a mutating request carries this header the filter uses Redis
 * SETNX (SET if Not eXists) to reserve the key and, after the first request
 * completes, caches the <b>full response</b> (status + content-type + body)
 * so a retry — the exact scenario idempotency keys exist for — receives the
 * original response instead of a bare {@code 409}.</p>
 *
 * <h2>v7 upgrades over the previous {@code IdempotentInterceptor}</h2>
 * <ul>
 *   <li><b>Response replay</b>: duplicate requests after the first one
 *       completes get the cached original response replayed verbatim (IETF
 *       draft-ietf-httpapi-idempotency §4.1 semantics). Previously duplicates
 *       always received 409 and could never observe their result.</li>
 *   <li><b>PENDING processing timeout</b>: the PENDING reservation now carries
 *       a short TTL ({@value #PENDING_TTL_SECONDS}s, default) instead of the
 *       full 24h. If the server crashes mid-processing the key self-expires
 *       quickly and the client can retry without waiting up to a day.</li>
 *   <li><b>Atomic state transition</b>: PENDING → DONE is a Lua compare-and-set
 *       so a straggler request cannot overwrite an already-cached response.</li>
 *   <li><b>Filter instead of interceptor</b>: response capture requires
 *       {@link ContentCachingResponseWrapper}, which only a Filter can install
 *       (interceptors see the unwrapped response).</li>
 * </ul>
 *
 * <h2>State machine</h2>
 * <pre>
 *   SETNX PENDING (ttl=PENDING_TTL)          first request proceeds
 *   ├─ success (2xx) → CAS PENDING→DONE+response (ttl=DONE_TTL 24h)
 *   ├─ failure (4xx/5xx/exception) → DEL key (client may retry)
 *   └─ crash (PENDING ttl expires) → key gone, client retry succeeds
 *   duplicate arrives:
 *   ├─ state DONE  → replay cached response (same status/body)
 *   └─ state PENDING → 409 + Retry-After (still processing)
 * </pre>
 *
 * <p>Only intercepts POST/PUT/PATCH/DELETE (mutating operations).
 * GET/HEAD/OPTIONS are always passed through. Conditionally activated via
 * {@code ep.idempotent.enabled=true} (default true).</p>
 */
public class IdempotencyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyFilter.class);

    /** Header name for the idempotency key (client-provided UUID). */
    public static final String HEADER = "Idempotency-Key";

    /** Redis key prefix for idempotency entries. */
    private static final String REDIS_PREFIX = "idem:";

    /** TTL for completed (DONE) entries — 24 hours. */
    private static final Duration DONE_TTL = Duration.ofHours(24);

    /** TTL for the PENDING reservation — processing timeout, 5 minutes. */
    static final long PENDING_TTL_SECONDS = 300;

    /** Marker for entries still being processed. */
    static final String STATE_PENDING = "PENDING";

    /** Record prefix for completed entries: {@code DONE|status|contentType|base64body}. */
    static final String STATE_DONE = "DONE";

    /** Lua CAS: only transition PENDING → DONE when the current value is PENDING. */
    private static final DefaultRedisScript<Long> CAS_DONE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then "
                    + "redis.call('SET', KEYS[1], ARGV[2], 'EX', ARGV[3]) return 1 "
                    + "else return 0 end",
            Long.class);

    /** HTTP methods that require idempotency checking. */
    private static final Set<String> MUTATING_METHODS =
            Set.of("POST", "PUT", "PATCH", "DELETE");

    private final StringRedisTemplate redisTemplate;

    public IdempotencyFilter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String method = request.getMethod();
        if (method == null || !MUTATING_METHODS.contains(method.toUpperCase())) {
            chain.doFilter(request, response);
            return;
        }

        String key = request.getHeader(HEADER);
        if (key == null || key.isBlank()) {
            // No idempotency key provided — dedup not requested for this call.
            chain.doFilter(request, response);
            return;
        }

        String redisKey = REDIS_PREFIX + key;
        Boolean reserved = redisTemplate.opsForValue()
                .setIfAbsent(redisKey, STATE_PENDING, Duration.ofSeconds(PENDING_TTL_SECONDS));

        if (Boolean.FALSE.equals(reserved)) {
            String existing = redisTemplate.opsForValue().get(redisKey);
            if (existing != null && existing.startsWith(STATE_DONE)) {
                replayCachedResponse(response, key, existing);
                return;
            }
            // PENDING — the first request is still in flight (or its TTL has
            // not yet expired after a crash). Ask the client to retry shortly.
            long retryAfter = redisTemplate.getExpire(redisKey) != null
                    && redisTemplate.getExpire(redisKey) > 0
                    ? redisTemplate.getExpire(redisKey) : PENDING_TTL_SECONDS;
            log.warn("[IdempotencyFilter] Duplicate in-flight request — key={}, retryAfter={}s", key, retryAfter);
            response.setStatus(409);
            response.setHeader("Retry-After", String.valueOf(Math.max(1, retryAfter)));
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":\"request is being processed\",\"idempotencyKey\":\""
                    + key + "\",\"retryAfterSeconds\":" + Math.max(1, retryAfter) + "}");
            return;
        }

        // First request — proceed with a response-capturing wrapper.
        ContentCachingResponseWrapper responseWrapper = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(request, responseWrapper);
        } catch (ServletException | IOException | RuntimeException e) {
            // Processing failed — release the key so the client can retry.
            redisTemplate.delete(redisKey);
            log.warn("[IdempotencyFilter] Request failed — key released for retry, key={}", key);
            throw e;
        }

        int status = responseWrapper.getStatus();
        if (status >= 200 && status < 300) {
            String body = new String(responseWrapper.getContentAsByteArray(), StandardCharsets.UTF_8);
            String contentType = responseWrapper.getContentType() == null
                    ? MediaType.APPLICATION_JSON_VALUE : responseWrapper.getContentType();
            String record = STATE_DONE + "|" + status + "|" + contentType + "|"
                    + Base64.getEncoder().encodeToString(body.getBytes(StandardCharsets.UTF_8));
            redisTemplate.execute(CAS_DONE_SCRIPT, List.of(redisKey), STATE_PENDING, record,
                    String.valueOf(DONE_TTL.toSeconds()));
            log.debug("[IdempotencyFilter] First request completed — key={}, status={}, response cached", key, status);
        } else {
            // 4xx client error / 5xx server error: not a completed business
            // outcome — release the key so a corrected retry can proceed.
            redisTemplate.delete(redisKey);
            log.debug("[IdempotencyFilter] Non-2xx ({}), key released for retry, key={}", status, key);
        }

        responseWrapper.copyBodyToResponse();
    }

    private void replayCachedResponse(HttpServletResponse response, String key, String record) throws IOException {
        // record format: DONE|status|contentType|base64Body
        String[] parts = record.split("\\|", 4);
        if (parts.length < 4) {
            // Corrupted record — treat as duplicate conflict, conservative.
            log.warn("[IdempotencyFilter] Corrupted cached record — key={}", key);
            response.setStatus(409);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":\"duplicate request\",\"idempotencyKey\":\"" + key + "\"}");
            return;
        }
        int status = Integer.parseInt(parts[1]);
        String contentType = parts[2];
        byte[] body = Base64.getDecoder().decode(parts[3]);
        log.info("[IdempotencyFilter] Duplicate request — replaying cached response, key={}, status={}", key, status);
        response.setStatus(status);
        response.setContentType(contentType);
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }
}
