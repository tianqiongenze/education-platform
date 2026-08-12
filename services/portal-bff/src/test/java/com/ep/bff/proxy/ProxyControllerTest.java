package com.ep.bff.proxy;

import com.ep.bff.proxy.ProxyConfig.ProxyProps;
import com.ep.bff.proxy.ProxyController.Match;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/**
 * D-106 ProxyController tests — verifies routing logic, prefix stripping,
 * the EP_DEV_ONLY 503 guard, and that all HTTP methods are forwarded.
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

    /**
     * Holder for a controller wired against a MockRestServiceServer-backed
     * RestClient. The builder is what {@link MockRestServiceServer#bindTo}
     * expects (it intercepts the builder's request factory).
     */
    private static final class Wired {
        final ProxyController controller;
        final MockRestServiceServer server;
        private Wired(RestClient.Builder b) {
            this.server = MockRestServiceServer.bindTo(b).build();
            this.controller = new ProxyController(b.build(),
                new ProxyProps(true, 10000, ROUTES));
        }
        static Wired create() {
            return new Wired(RestClient.builder());
        }
    }

    private static MockHttpServletRequest req(String method, String uri) {
        return new MockHttpServletRequest(method, uri);
    }

    private static RestClient dummyClient() {
        return RestClient.builder().build();
    }

    @Test
    @DisplayName("matchRoute: /proxy/data/v3/api-docs → sp02 base + /v3/api-docs")
    void matchRoute_data() {
        ProxyController c = new ProxyController(dummyClient(),
            new ProxyProps(true, 10000, ROUTES));
        Match m = c.matchRoute("/proxy/data/v3/api-docs");
        assertNotNull(m);
        assertEquals("http://localhost:8082", m.baseUrl());
        assertEquals("/v3/api-docs", m.downstreamPath());
    }

    @Test
    @DisplayName("matchRoute: prefix boundary — /proxy/datax must NOT match /proxy/data")
    void matchRoute_prefixBoundary() {
        ProxyController c = new ProxyController(dummyClient(),
            new ProxyProps(true, 10000, ROUTES));
        assertNull(c.matchRoute("/proxy/datax"),
            "prefix match must stop at a '/' boundary, not be a substring match");
    }

    @Test
    @DisplayName("matchRoute: bare prefix /proxy/data → downstream /")
    void matchRoute_barePrefix() {
        ProxyController c = new ProxyController(dummyClient(),
            new ProxyProps(true, 10000, ROUTES));
        Match m = c.matchRoute("/proxy/data");
        assertNotNull(m);
        assertEquals("/", m.downstreamPath());
    }

    @Test
    @DisplayName("matchRoute: unknown route returns null (404)")
    void matchRoute_unknown() {
        ProxyController c = new ProxyController(dummyClient(),
            new ProxyProps(true, 10000, ROUTES));
        assertNull(c.matchRoute("/proxy/unknown/foo"));
    }

    @Test
    @DisplayName("proxy_disabled_returns503: ep.proxy.enabled=false short-circuits")
    void proxy_disabled_returns503() {
        //noinspection resource - RestClient is unused when disabled
        ProxyController c = new ProxyController(dummyClient(),
            new ProxyProps(false, 10000, ROUTES));
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
}
