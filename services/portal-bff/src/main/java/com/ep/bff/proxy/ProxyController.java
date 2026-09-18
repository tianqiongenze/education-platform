package com.ep.bff.proxy;

import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ep.bff.config.ProxyConfig;
import com.ep.cache.CacheService;
import com.ep.web.common.tenant.TenantContext;
import com.ep.web.common.tenant.TenantContextFilter;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * D-106: Unified API gateway proxy. Forwards requests from the single
 * entry point (api.edu.com) to backend services by path prefix.
 * <p>
 * Routes (configured via {@code ep.proxy.routes} in application.yml):
 * <ul>
 *   <li>/proxy/data/**      → data-platform (port 8082)</li>
 *   <li>/proxy/ai/**        → ai-platform (port 8086)</li>
 *   <li>/proxy/itbackend/** → grading-service (port 8085)</li>
 *   <li>/proxy/knowledge/** → knowledge-bff (port 8070)</li>
 *   <li>/proxy/order/**     → order-platform-service (port 8084)</li>
 *   <li>/proxy/payment/**   → payment-platform-service (port 8087)</li>
 * </ul>
 * The matched /proxy/&lt;name&gt; prefix is stripped before forwarding. Also
 * used by the aggregated Swagger UI (D-107) to fetch each service's
 * /v3/api-docs.
 * <p>
 * Design:
 * <ul>
 *   <li>EP_DEV_ONLY guard: when {@code ep.proxy.enabled=false} (dev default)
 *       returns 503 with a clear message instead of attempting to proxy.</li>
 *   <li>D-039: stateless per-request handling, no synchronized blocks.</li>
 *   <li>D-040: only JDK + Spring built-ins, GraalVM friendly.</li>
 *   <li>D-182: GET responses cached 120s via ep-cache SDK (L1+L2+Pub/Sub).</li>
 *   <li>Cache audit fix (BFF 聚合缓存串号): only whitelisted public read-only
 *       GET paths ({@code ep.bff.cache-paths}) are cached, and the cache key
 *       carries the tenant dimension — personalized responses are never
 *       cached.</li>
 * </ul>
 */
@RestController
public class ProxyController {

    private static final Logger log = LoggerFactory.getLogger(ProxyController.class);

    private static final String CACHE_AGGREGATION = "bff:aggregation";
    private static final long AGGREGATION_TTL_SEC = 120;

    /**
     * Default cache whitelist: public read-only GET endpoints (Swagger docs
     * for every route + course list/detail style resources). Override with
     * {@code ep.bff.cache-paths} (comma-separated ANT patterns); set it to
     * {@code none} to disable response caching entirely.
     */
    static final List<String> DEFAULT_CACHE_PATHS = List.of(
        "/proxy/*/v3/api-docs",
        "/proxy/*/api/courses/**",
        "/proxy/*/api/course/**");

    private static final String CACHE_PATHS_DISABLE = "none";

    /**
     * Hop-by-hop headers (RFC 7230 §6.1) that must not be forwarded by a
     * proxy. {@code Host}/{@code Content-Length} are also excluded because
     * RestClient recomputes them for the upstream URI.
     */
    private static final Set<String> HOP_BY_HOP = Set.of(
        "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
        "te", "trailers", "transfer-encoding", "upgrade",
        "host", "content-length", "expect"
    );

    private static final String PROXY_ROOT = "/proxy";

    private final RestClient proxyRestClient;
    private final boolean enabled;
    private final Map<String, String> routes;
    private final CacheService cacheService;
    private final List<String> cachePaths;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    @Autowired
    public ProxyController(RestClient proxyRestClient, ProxyConfig.ProxyProps props,
                           CacheService cacheService,
                           @Value("${ep.bff.cache-paths:}") List<String> cachePaths) {
        this.proxyRestClient = proxyRestClient;
        this.enabled = props.enabled();
        this.routes = props.routes() == null ? Map.of() : props.routes();
        this.cacheService = cacheService;
        this.cachePaths = normalizeCachePaths(cachePaths);
    }

    /** Convenience/test constructor using the default cache whitelist. */
    public ProxyController(RestClient proxyRestClient, ProxyConfig.ProxyProps props,
                           CacheService cacheService) {
        this(proxyRestClient, props, cacheService, DEFAULT_CACHE_PATHS);
    }

    private static List<String> normalizeCachePaths(List<String> configured) {
        if (configured == null || configured.isEmpty()) {
            return DEFAULT_CACHE_PATHS;
        }
        List<String> trimmed = configured.stream()
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList();
        if (trimmed.isEmpty()) {
            return DEFAULT_CACHE_PATHS;
        }
        if (trimmed.size() == 1 && CACHE_PATHS_DISABLE.equalsIgnoreCase(trimmed.get(0))) {
            return List.of();
        }
        return trimmed;
    }

    /**
     * Cache audit fix: a GET is only cacheable when its request path matches
     * the configured whitelist. Non-GET methods are never cacheable.
     */
    public boolean shouldCache(HttpMethod method, String requestPath) {
        if (method != HttpMethod.GET || requestPath == null) {
            return false;
        }
        for (String pattern : cachePaths) {
            if (pathMatcher.match(pattern, requestPath)) {
                return true;
            }
        }
        return false;
    }

    /** Tenant for the cache key: X-Tenant-Id header first, then TenantContext. */
    static String currentTenant(HttpServletRequest request) {
        String header = request != null ? request.getHeader(TenantContextFilter.TENANT_ID_HEADER) : null;
        if (header != null && !header.isBlank()) {
            return header;
        }
        return TenantContext.getTenantIdOrDefault();
    }

    /**
     * Catch-all for every method and every path under /proxy/**.
     * NOTE: a leading {@code /} in the prefix keeps {@code getRequestURI}
     * consistent regardless of context path.
     */
    @RequestMapping("/proxy/**")
    @RateLimiter(name = "default")
    public ResponseEntity<byte[]> proxy(HttpServletRequest request) {
        if (!enabled) {
            // EP_DEV_ONLY guard — fail fast with a helpful message rather
            // than hanging for the full timeout on a missing backend.
            log.debug("proxy disabled (ep.proxy.enabled=false), rejecting {}",
                request.getRequestURI());
            return ResponseEntity.status(503)
                .contentType(MediaType.APPLICATION_JSON)
                .body(("{\"error\":\"proxy disabled\","
                    + "\"hint\":\"set EP_PROXY_ENABLED=true to enable forwarding\"}").getBytes());
        }

        String path = request.getRequestURI();
        Match match = matchRoute(path);
        if (match == null) {
            return ResponseEntity.status(404)
                .contentType(MediaType.APPLICATION_JSON)
                .body(("{\"error\":\"unknown proxy route\",\"path\":\""
                    + path + "\"}").getBytes());
        }

        URI target = URI.create(match.baseUrl() + match.downstreamPath());
        HttpMethod method = HttpMethod.valueOf(request.getMethod() != null ? request.getMethod() : "GET");

        // Cache audit fix: only whitelisted public read-only GETs are cached,
        // and the key carries the tenant dimension so one tenant can never be
        // served another tenant's aggregated response.
        if (shouldCache(method, path)) {
            String cacheKey = method.name() + ":" + target.toString();
            ResponseEntity<byte[]> cached = cacheService.getOrLoad(
                    CACHE_AGGREGATION, currentTenant(request), cacheKey,
                    () -> doForward(method, target, request),
                    AGGREGATION_TTL_SEC, false);
            if (cached != null) {
                return cached;
            }
        }

        return doForward(method, target, request);
    }

    /**
     * Performs the actual upstream forward via RestClient. Extracted so the
     * cache loader can invoke it without duplicating the exchange logic.
     */
    private ResponseEntity<byte[]> doForward(HttpMethod method, URI target, HttpServletRequest request) {
        byte[] body = readBody(request);
        HttpHeaders forwardHeaders = copyForwardableHeaders(request);

        log.debug("proxy {} {} → {}", method, request.getRequestURI(), target);
        try {
            return proxyRestClient.method(method)
                .uri(target)
                .headers(h -> h.addAll(forwardHeaders))
                .body(body)
                .exchange((req, resp) -> {
                    HttpHeaders respHeaders = new HttpHeaders();
                    resp.getHeaders().forEach((name, values) -> {
                        if (!HOP_BY_HOP.contains(name.toLowerCase())) {
                            respHeaders.addAll(name, values);
                        }
                    });
                    byte[] respBody = resp.getBody().readAllBytes();
                    return ResponseEntity.status(resp.getStatusCode())
                        .headers(respHeaders)
                        .body(respBody);
                });
        } catch (Exception e) {
            log.warn("proxy upstream call failed for {} {}: {}", method, target, e.toString());
            return ResponseEntity.status(502)
                .contentType(MediaType.APPLICATION_JSON)
                .body(("{\"error\":\"upstream unavailable\",\"target\":\""
                    + target + "\",\"detail\":\""
                    + escape(e.getMessage()) + "\"}").getBytes());
        }
    }

    /**
     * Find the longest configured prefix that matches the incoming path,
     * returning the backend base URL and the path with that prefix stripped.
     * <p>
     * Example: routes contain {@code "/proxy/data" → "http://localhost:8082"};
     * incoming {@code /proxy/data/v3/api-docs} matches and yields downstream
     * path {@code /v3/api-docs}.
     */
    Match matchRoute(String path) {
        if (path == null || !path.startsWith(PROXY_ROOT)) {
            return null;
        }
        // Match longest prefix first so that (hypothetical) nested routes
        // like /proxy/data/secure don't get shadowed by /proxy/data.
        List<String> sorted = routes.keySet().stream()
            .sorted((a, b) -> Integer.compare(b.length(), a.length()))
            .toList();
        for (String prefix : sorted) {
            // A prefix matches when the path equals it, or continues with '/'
            // at the boundary — this prevents "/proxy/datax" from matching
            // the "/proxy/data" route.
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                String downstream = path.substring(prefix.length());
                if (downstream.isEmpty()) {
                    downstream = "/";
                }
                String base = routes.get(prefix);
                if (base == null || base.isBlank()) {
                    continue;
                }
                // Normalize: never end base with '/' so the concatenation
                // "/v3/api-docs" yields exactly "/v3/api-docs" downstream.
                while (base.endsWith("/")) {
                    base = base.substring(0, base.length() - 1);
                }
                return new Match(prefix, base, downstream);
            }
        }
        return null;
    }

    private static HttpHeaders copyForwardableHeaders(HttpServletRequest request) {
        HttpHeaders out = new HttpHeaders();
        Enumeration<String> names = request.getHeaderNames();
        if (names == null) {
            return out;
        }
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            if (name == null || HOP_BY_HOP.contains(name.toLowerCase())) {
                continue;
            }
            Set<String> seen = new LinkedHashSet<>();
            Enumeration<String> values = request.getHeaders(name);
            while (values.hasMoreElements()) {
                String v = values.nextElement();
                if (v != null && seen.add(v)) {
                    out.add(name, v);
                }
            }
        }
        return out;
    }

    private static byte[] readBody(HttpServletRequest request) {
        try {
            int len = request.getContentLength();
            if (len <= 0) {
                // Fall back to full read for chunked / unknown length.
                return request.getInputStream().readAllBytes();
            }
            byte[] buf = new byte[len];
            int read = request.getInputStream().readNBytes(buf, 0, len);
            if (read == buf.length) {
                return buf;
            }
            byte[] exact = new byte[read];
            System.arraycopy(buf, 0, exact, 0, read);
            return exact;
        } catch (Exception e) {
            log.debug("could not read proxy request body: {}", e.toString());
            return new byte[0];
        }
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Result of a successful prefix match — exposed for unit testing. */
    record Match(String prefix, String baseUrl, String downstreamPath) {
    }
}
