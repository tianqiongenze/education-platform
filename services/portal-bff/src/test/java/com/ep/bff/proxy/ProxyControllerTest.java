package com.ep.bff.proxy;

import com.ep.bff.config.ProxyConfig.ProxyProps;
import com.ep.bff.proxy.ProxyController.Match;
import com.ep.cache.CacheService;
import com.ep.web.common.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/**
 * D-106 ProxyController tests — verifies routing logic, prefix stripping,
 * the EP_DEV_ONLY 503 guard, and that all HTTP methods are forwarded.
 * <p>
 * D-182: Tests also verify GET responses are cached via ep-cache SDK (120s)
 * and non-GET methods bypass the cache.
 * <p>
 * Cache audit fix (BFF 聚合缓存串号): caching now applies ONLY to whitelisted
 * public read-only GET paths ({@code ep.bff.cache-paths}) and the cache key
 * carries the tenant dimension.
 * <p>
 * Uses {@link MockRestServiceServer} bound to the real RestClient.Builder
 * so the full forward path (header copy, body copy, URI build) is exercised
 * without real network calls. matchRoute is unit-tested directly too.
 */
class ProxyControllerTest {

    private static final Map<String, String> ROUTES = Map.of(
        "/proxy/data", "http://localhost:8082",
        "/proxy/ai", "http://localhost:8086",
        "/proxy/itbackend", "http://localhost:8085",
        "/proxy/knowledge", "http://localhost:8070",
        "/proxy/order", "http://localhost:8084",
        "/proxy/payment", "http://localhost:8087"
    );

    @BeforeEach
    void setUp() {
        TenantContext.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /**
     * Creates a CacheService mock that simulates a cache miss: it invokes the
     * loader Supplier so the actual forward happens (needed for MockRestServiceServer
     * to intercept the call). This lets existing forward-path tests work unchanged.
     * <p>
     * Cache audit fix: stubs the tenant-scoped 6-arg overload (loader at index 3).
     */
    @SuppressWarnings("unchecked")
    private static CacheService cacheMiss() {
        CacheService cs = mock(CacheService.class);
        // Cache miss: always invoke the loader so the real forward executes.
        when(cs.getOrLoad(anyString(), anyString(), anyString(), any(Supplier.class), anyLong(), anyBoolean()))
            .thenAnswer(inv -> {
                Supplier<ResponseEntity<byte[]>> loader = inv.getArgument(3);
                return loader.get();
            });
        return cs;
    }

    /**
     * Holder for a controller wired against a MockRestServiceServer-backed
     * RestClient. The builder is what {@link MockRestServiceServer#bindTo}
     * expects (it intercepts the builder's request factory).
     */
    private static final class Wired {
        final ProxyController controller;
        final MockRestServiceServer server;
        private Wired(RestClient.Builder b, CacheService cs) {
            this.server = MockRestServiceServer.bindTo(b).build();
            this.controller = new ProxyController(b.build(),
                new ProxyProps(true, 10000, ROUTES), cs);
        }
        static Wired create() {
            return new Wired(RestClient.builder(), cacheMiss());
        }
    }

    private static MockHttpServletRequest req(String method, String uri) {
        return new MockHttpServletRequest(method, uri);
    }

    private static MockHttpServletRequest reqWithTenant(String method, String uri, String tenantId) {
        MockHttpServletRequest r = new MockHttpServletRequest(method, uri);
        r.addHeader("X-Tenant-Id", tenantId);
        return r;
    }

    private static RestClient dummyClient() {
        return RestClient.builder().build();
    }

    @Test
    @DisplayName("matchRoute: /proxy/data/v3/api-docs → data-platform base + /v3/api-docs")
    void matchRoute_data() {
        ProxyController c = new ProxyController(dummyClient(),
            new ProxyProps(true, 10000, ROUTES), cacheMiss());
        Match m = c.matchRoute("/proxy/data/v3/api-docs");
        assertNotNull(m);
        assertEquals("http://localhost:8082", m.baseUrl());
        assertEquals("/v3/api-docs", m.downstreamPath());
    }

    @Test
    @DisplayName("matchRoute: prefix boundary — /proxy/datax must NOT match /proxy/data")
    void matchRoute_prefixBoundary() {
        ProxyController c = new ProxyController(dummyClient(),
            new ProxyProps(true, 10000, ROUTES), cacheMiss());
        assertNull(c.matchRoute("/proxy/datax"),
            "prefix match must stop at a '/' boundary, not be a substring match");
    }

    @Test
    @DisplayName("matchRoute: bare prefix /proxy/data → downstream /")
    void matchRoute_barePrefix() {
        ProxyController c = new ProxyController(dummyClient(),
            new ProxyProps(true, 10000, ROUTES), cacheMiss());
        Match m = c.matchRoute("/proxy/data");
        assertNotNull(m);
        assertEquals("/", m.downstreamPath());
    }

    @Test
    @DisplayName("matchRoute: unknown route returns null (404)")
    void matchRoute_unknown() {
        ProxyController c = new ProxyController(dummyClient(),
            new ProxyProps(true, 10000, ROUTES), cacheMiss());
        assertNull(c.matchRoute("/proxy/unknown/foo"));
    }

    @Test
    @DisplayName("proxy_disabled_returns503: ep.proxy.enabled=false short-circuits")
    void proxy_disabled_returns503() {
        //noinspection resource - RestClient is unused when disabled
        ProxyController c = new ProxyController(dummyClient(),
            new ProxyProps(false, 10000, ROUTES), cacheMiss());
        var resp = c.proxy(req("GET", "/proxy/data/v3/api-docs"));
        assertEquals(503, resp.getStatusCode().value());
    }

    @Test
    @DisplayName("proxy_unknownRoute_returns404: matched no backend")
    void proxy_unknownRoute_returns404() {
        Wired w = Wired.create();
        var resp = w.controller.proxy(req("GET", "/proxy/unknown/foo"));
        assertEquals(404, resp.getStatusCode().value());
    }

    @Test
    @DisplayName("proxy_dataRoute_forwardsCorrectly: GET /proxy/data/v3/api-docs")
    void proxy_dataRoute_forwardsCorrectly() {
        Wired w = Wired.create();
        w.server.expect(requestTo("http://localhost:8082/v3/api-docs"))
            .andRespond(withSuccess("{\"openapi\":\"3.0.1\"}".getBytes(), MediaType.APPLICATION_JSON));
        var resp = w.controller.proxy(req("GET", "/proxy/data/v3/api-docs"));
        assertEquals(200, resp.getStatusCode().value());
        w.server.verify();
    }

    @Test
    @DisplayName("proxy_aiRoute_forwardsCorrectly: GET /proxy/ai/v3/api-docs")
    void proxy_aiRoute_forwardsCorrectly() {
        Wired w = Wired.create();
        w.server.expect(requestTo("http://localhost:8086/v3/api-docs"))
            .andRespond(withSuccess("{}".getBytes(), MediaType.APPLICATION_JSON));
        var resp = w.controller.proxy(req("GET", "/proxy/ai/v3/api-docs"));
        assertEquals(200, resp.getStatusCode().value());
        w.server.verify();
    }

    @Test
    @DisplayName("proxy_orderRoute_forwardsCorrectly: GET /proxy/order/v3/api-docs")
    void proxy_orderRoute_forwardsCorrectly() {
        Wired w = Wired.create();
        w.server.expect(requestTo("http://localhost:8084/v3/api-docs"))
            .andRespond(withSuccess("{}".getBytes(), MediaType.APPLICATION_JSON));
        var resp = w.controller.proxy(req("GET", "/proxy/order/v3/api-docs"));
        assertEquals(200, resp.getStatusCode().value());
        w.server.verify();
    }

    @Test
    @DisplayName("proxy_stripsPrefix: /proxy/data/foo/bar → /foo/bar downstream")
    void proxy_stripsPrefix() {
        Wired w = Wired.create();
        w.server.expect(requestTo("http://localhost:8082/foo/bar"))
            .andRespond(withSuccess("{}".getBytes(), MediaType.APPLICATION_JSON));
        var resp = w.controller.proxy(req("GET", "/proxy/data/foo/bar"));
        assertEquals(200, resp.getStatusCode().value());
        w.server.verify();
    }

    @Test
    @DisplayName("proxy_forwardsAllMethods: GET, POST, PUT, DELETE")
    void proxy_forwardsAllMethods() {
        for (HttpMethod m : new HttpMethod[]{HttpMethod.GET, HttpMethod.POST,
            HttpMethod.PUT, HttpMethod.DELETE}) {
            Wired w = Wired.create();
            w.server.expect(requestTo("http://localhost:8082/items"))
                .andExpect(method(m))
                .andRespond(withSuccess("{}".getBytes(), MediaType.APPLICATION_JSON));
            var resp = w.controller.proxy(req(m.name(), "/proxy/data/items"));
            assertEquals(200, resp.getStatusCode().value(),
                "method " + m + " should be forwarded");
            w.server.verify();
        }
    }

    @Test
    @DisplayName("proxy_upstreamFailure_returns502: connection refused → 502, not 500")
    void proxy_upstreamFailure_returns502() {
        Wired w = Wired.create();
        w.server.expect(requestTo("http://localhost:8082/x"))
            .andRespond(withException(new java.io.IOException("connection refused")));
        var resp = w.controller.proxy(req("GET", "/proxy/data/x"));
        assertEquals(502, resp.getStatusCode().value());
    }

    @Test
    @DisplayName("D-182: whitelisted GET request invokes cache getOrLoad")
    void get_request_uses_cache() {
        CacheService cs = cacheMiss();
        Wired w = new Wired(RestClient.builder(), cs);
        w.server.expect(requestTo("http://localhost:8082/v3/api-docs"))
            .andRespond(withSuccess("{\"openapi\":\"3.0.1\"}".getBytes(), MediaType.APPLICATION_JSON));
        var resp = w.controller.proxy(req("GET", "/proxy/data/v3/api-docs"));
        assertEquals(200, resp.getStatusCode().value());
        // Cache audit fix: tenant dimension ("default" without X-Tenant-Id) in the key.
        verify(cs, times(1)).getOrLoad(
            eq("bff:aggregation"),
            eq("default"),
            eq("GET:http://localhost:8082/v3/api-docs"),
            any(Supplier.class),
            eq(120L),
            eq(false));
        w.server.verify();
    }

    @Test
    @DisplayName("Cache audit: default whitelist covers public course list/detail GETs")
    void course_list_path_is_cached_by_default() {
        CacheService cs = cacheMiss();
        Wired w = new Wired(RestClient.builder(), cs);
        w.server.expect(requestTo("http://localhost:8082/api/courses"))
            .andRespond(withSuccess("[]".getBytes(), MediaType.APPLICATION_JSON));
        var resp = w.controller.proxy(req("GET", "/proxy/data/api/courses"));
        assertEquals(200, resp.getStatusCode().value());
        verify(cs, times(1)).getOrLoad(
            eq("bff:aggregation"), eq("default"), eq("GET:http://localhost:8082/api/courses"),
            any(Supplier.class), eq(120L), eq(false));
        w.server.verify();
    }

    @Test
    @DisplayName("Cache audit: non-whitelisted GET bypasses the cache entirely")
    void non_whitelisted_get_bypasses_cache() {
        CacheService cs = cacheMiss();
        Wired w = new Wired(RestClient.builder(), cs);
        w.server.expect(requestTo("http://localhost:8082/users/me"))
            .andRespond(withSuccess("{}".getBytes(), MediaType.APPLICATION_JSON));
        var resp = w.controller.proxy(req("GET", "/proxy/data/users/me"));
        assertEquals(200, resp.getStatusCode().value());
        // Personalized responses must never be cached.
        verify(cs, never()).getOrLoad(
            anyString(), anyString(), anyString(), any(Supplier.class), anyLong(), anyBoolean());
        w.server.verify();
    }

    @Test
    @DisplayName("Cache audit: X-Tenant-Id header enters the cache key")
    void tenant_header_enters_cache_key() {
        CacheService cs = cacheMiss();
        Wired w = new Wired(RestClient.builder(), cs);
        w.server.expect(requestTo("http://localhost:8082/v3/api-docs"))
            .andRespond(withSuccess("{}".getBytes(), MediaType.APPLICATION_JSON));
        var resp = w.controller.proxy(reqWithTenant("GET", "/proxy/data/v3/api-docs", "tenant-9"));
        assertEquals(200, resp.getStatusCode().value());
        verify(cs, times(1)).getOrLoad(
            eq("bff:aggregation"), eq("tenant-9"),
            eq("GET:http://localhost:8082/v3/api-docs"),
            any(Supplier.class), eq(120L), eq(false));
        w.server.verify();
    }

    @Test
    @DisplayName("Cache audit: configurable whitelist (ep.bff.cache-paths) replaces the default")
    void custom_whitelist_replaces_default() {
        // Part 1: default path (/proxy/data/v3/api-docs) is NOT cached when a
        // custom whitelist is provided. Use a fresh RestClient + MockServer.
        {
            CacheService cs1 = cacheMiss();
            RestClient.Builder b1 = RestClient.builder();
            MockRestServiceServer server1 = MockRestServiceServer.bindTo(b1).build();
            ProxyController c1 = new ProxyController(b1.build(),
                new ProxyProps(true, 10000, ROUTES), cs1, List.of("/proxy/data/health"));

            server1.expect(requestTo("http://localhost:8082/v3/api-docs"))
                .andRespond(withSuccess("{}".getBytes(), MediaType.APPLICATION_JSON));
            c1.proxy(req("GET", "/proxy/data/v3/api-docs"));
            verify(cs1, never()).getOrLoad(
                anyString(), anyString(), anyString(), any(Supplier.class), anyLong(), anyBoolean());
            server1.verify();
        }

        // Part 2: custom path IS cached. Separate RestClient + MockServer so
        // MockRestServiceServer doesn't reject expectations after actual requests.
        {
            CacheService cs2 = cacheMiss();
            RestClient.Builder b2 = RestClient.builder();
            MockRestServiceServer server2 = MockRestServiceServer.bindTo(b2).build();
            ProxyController c2 = new ProxyController(b2.build(),
                new ProxyProps(true, 10000, ROUTES), cs2, List.of("/proxy/data/health"));

            server2.expect(requestTo("http://localhost:8082/health"))
                .andRespond(withSuccess("ok".getBytes(), MediaType.APPLICATION_JSON));
            c2.proxy(req("GET", "/proxy/data/health"));
            verify(cs2, times(1)).getOrLoad(
                eq("bff:aggregation"), eq("default"), eq("GET:http://localhost:8082/health"),
                any(Supplier.class), eq(120L), eq(false));
            server2.verify();
        }
    }

    @Test
    @DisplayName("Cache audit: ep.bff.cache-paths=none disables response caching")
    void none_whitelist_disables_caching() {
        CacheService cs = cacheMiss();
        RestClient.Builder b = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        ProxyController c = new ProxyController(b.build(),
            new ProxyProps(true, 10000, ROUTES), cs, List.of("none"));

        server.expect(requestTo("http://localhost:8082/v3/api-docs"))
            .andRespond(withSuccess("{}".getBytes(), MediaType.APPLICATION_JSON));
        var resp = c.proxy(req("GET", "/proxy/data/v3/api-docs"));
        assertEquals(200, resp.getStatusCode().value());
        verify(cs, never()).getOrLoad(
            anyString(), anyString(), anyString(), any(Supplier.class), anyLong(), anyBoolean());
        server.verify();
    }

    @Test
    @DisplayName("D-182: non-GET (POST) request bypasses cache entirely")
    void post_request_bypasses_cache() {
        CacheService cs = cacheMiss();
        Wired w = new Wired(RestClient.builder(), cs);
        w.server.expect(requestTo("http://localhost:8082/items"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess("{}".getBytes(), MediaType.APPLICATION_JSON));
        var resp = w.controller.proxy(req("POST", "/proxy/data/items"));
        assertEquals(200, resp.getStatusCode().value());
        // Cache must NOT be consulted for non-GET methods.
        verify(cs, never()).getOrLoad(
            anyString(), anyString(), anyString(), any(Supplier.class), anyLong(), anyBoolean());
        w.server.verify();
    }

    @Test
    @DisplayName("D-182: cache hit returns cached response without forwarding")
    void cache_hit_skips_forward() {
        CacheService cs = mock(CacheService.class);
        ResponseEntity<byte[]> cached = ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .body("{\"cached\":true}".getBytes());
        when(cs.getOrLoad(anyString(), anyString(), anyString(), any(Supplier.class), anyLong(), anyBoolean()))
            .thenReturn(cached);

        Wired w = new Wired(RestClient.builder(), cs);
        // No server.expect(...) — if a forward happens, MockRestServiceServer
        // will fail because there's no expected request.
        var resp = w.controller.proxy(req("GET", "/proxy/data/v3/api-docs"));
        assertEquals(200, resp.getStatusCode().value());
        assertArrayEquals("{\"cached\":true}".getBytes(), resp.getBody());
        // No upstream call should have been made.
        w.server.verify();
    }

    // ---- shouldCache unit tests ------------------------------------------

    @Test
    @DisplayName("shouldCache: never caches non-GET methods")
    void shouldCache_rejectsNonGet() {
        ProxyController c = new ProxyController(dummyClient(),
            new ProxyProps(true, 10000, ROUTES), cacheMiss());
        assertFalse(c.shouldCache(HttpMethod.POST, "/proxy/data/v3/api-docs"));
        assertFalse(c.shouldCache(HttpMethod.DELETE, "/proxy/data/api/courses/1"));
        assertFalse(c.shouldCache(HttpMethod.GET, null));
    }

    @Test
    @DisplayName("shouldCache: honours the default whitelist patterns")
    void shouldCache_defaultWhitelist() {
        ProxyController c = new ProxyController(dummyClient(),
            new ProxyProps(true, 10000, ROUTES), cacheMiss());
        assertTrue(c.shouldCache(HttpMethod.GET, "/proxy/data/v3/api-docs"));
        assertTrue(c.shouldCache(HttpMethod.GET, "/proxy/knowledge/api/courses"));
        assertTrue(c.shouldCache(HttpMethod.GET, "/proxy/knowledge/api/courses"));
        assertTrue(c.shouldCache(HttpMethod.GET, "/proxy/data/api/course/42"));
        assertFalse(c.shouldCache(HttpMethod.GET, "/proxy/data/users/me"));
    }
}
