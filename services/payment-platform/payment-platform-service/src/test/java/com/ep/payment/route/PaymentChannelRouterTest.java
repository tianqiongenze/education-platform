package com.ep.payment.route;

import com.ep.payment.api.PaymentChannel;
import com.ep.payment.api.PaymentRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-237/A2: router unit tests — rule gate determinism, AI-hint acceptance and
 * rejection, health-driven ranking.
 */
class PaymentChannelRouterTest {

    private ChannelDescriptor ch(String id, int cost, int health, String cur, String min, String max) {
        return new ChannelDescriptor(id, id, cost, health, 0, cur,
                new BigDecimal(min), new BigDecimal(max), true, java.time.Instant.now());
    }

    private PaymentRequest req(String cur, String amount) {
        return new PaymentRequest("o1", "default", PaymentChannel.MOCK,
                new BigDecimal(amount), cur);
    }

    @Test
    @DisplayName("routes to the only supporting channel")
    void singleCandidate() {
        PaymentChannelRouter r = new PaymentChannelRouter(List.of(), false);
        r.register(ch("A", 60, 100, "CNY", "0.01", "10000"));
        assertEquals("A", r.route(req("CNY", "10")).descriptor().channelId());
    }

    @Test
    @DisplayName("health desc then cost asc ranking")
    void rankingOrder() {
        PaymentChannelRouter r = new PaymentChannelRouter(List.of(), false);
        r.register(ch("cheap-sick", 10, 40, "CNY", "0.01", "10000"));
        r.register(ch("pricey-healthy", 90, 99, "CNY", "0.01", "10000"));
        // health dominates cost: pricey-healthy wins
        assertEquals("pricey-healthy", r.route(req("CNY", "10")).descriptor().channelId());
    }

    @Test
    @DisplayName("no supporting channel → IllegalStateException (never silent fallback)")
    void noCandidateThrows() {
        PaymentChannelRouter r = new PaymentChannelRouter(List.of(), false);
        r.register(ch("A", 60, 100, "USD", "0.01", "10000"));
        assertThrows(IllegalStateException.class, () -> r.route(req("CNY", "10")));
    }

    @Test
    @DisplayName("amount outside channel min/max is filtered")
    void amountBounds() {
        PaymentChannelRouter r = new PaymentChannelRouter(List.of(), false);
        r.register(ch("micro", 5, 100, "CNY", "0.000001", "1"));
        assertThrows(IllegalStateException.class, () -> r.route(req("CNY", "500")));
        assertEquals("micro", r.route(req("CNY", "0.5")).descriptor().channelId());
    }

    @Test
    @DisplayName("AI suggestion accepted only when it passes the rule gate")
    void advisorHintGated() {
        RouteAdvisor advisor = req2 -> Optional.of("cheap-sick");
        PaymentChannelRouter r = new PaymentChannelRouter(List.of(advisor), false);
        r.register(ch("cheap-sick", 10, 40, "CNY", "0.01", "10000"));
        r.register(ch("pricey-healthy", 90, 99, "CNY", "0.01", "10000"));
        // hint is a valid candidate → accepted even though ranking prefers the other
        assertEquals("cheap-sick", r.route(req("CNY", "10")).descriptor().channelId());
    }

    @Test
    @DisplayName("AI hint for a non-candidate (unsupported currency) ignored by gate")
    void advisorHintRejectedByGate() {
        RouteAdvisor advisor = req2 -> Optional.of("usd-only");
        PaymentChannelRouter r = new PaymentChannelRouter(List.of(advisor), false);
        r.register(ch("usd-only", 10, 100, "USD", "0.01", "10000"));
        r.register(ch("cny-ok", 90, 90, "CNY", "0.01", "10000"));
        assertEquals("cny-ok", r.route(req("CNY", "10")).descriptor().channelId());
    }

    @Test
    @DisplayName("health updates on result feedback")
    void healthFeedback() {
        PaymentChannelRouter r = new PaymentChannelRouter(List.of(), false);
        r.register(ch("A", 60, 50, "CNY", "0.01", "10000"));
        r.onChannelResult("A", false);
        r.onChannelResult("A", false);
        assertEquals(34, r.route(req("CNY", "10")).health());
    }

    @Test
    @DisplayName("descriptor validation")
    void descriptorValidation() {
        assertThrows(IllegalArgumentException.class,
                () -> ch("bad", -1, 100, "CNY", "0.01", "10"));
        assertThrows(IllegalArgumentException.class,
                () -> ch("bad", 60, 200, "CNY", "0.01", "10"));
    }
}
