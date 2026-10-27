package com.ep.payment.route;

import com.ep.payment.api.PaymentChannel;
import com.ep.payment.api.PaymentRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * D-237/A2: payment channel routing subsystem (「AI支付」文档 · 支付核心处理层 —
 * "路由子系统做通道信息、通道成本、分组规则、行业准入、规则链").
 *
 * <p>Routing pipeline (deterministic rule gate, AI suggestion as hint only):
 * <ol>
 *   <li>Collect candidate channels that support the request
 *       (currency/amount/enabled) — the deterministic filter.</li>
 *   <li>If a {@link RouteAdvisor} suggestion is present AND it is itself a
 *       valid candidate, prefer it — "模型给建议".</li>
 *   <li>Otherwise rank by health score desc, then cost (bps) asc, then
 *       priority asc — "规则做闸门".</li>
 * </ol>
 *
 * <p>Thread-safe: channels registered at boot (or via {@link #register}) into a
 * copy-on-write list; health scores are mutable atomics so callback-driven
 * updates never block routing.
 */
@Component
public class PaymentChannelRouter {

    private static final Logger log = LoggerFactory.getLogger(PaymentChannelRouter.class);

    /** Mutable health/cost runtime view wrapping an immutable descriptor. */
    public static final class ChannelRuntime {
        final ChannelDescriptor descriptor;
        final AtomicInteger healthScore;

        ChannelRuntime(ChannelDescriptor d) {
            this.descriptor = d;
            this.healthScore = new AtomicInteger(d.healthScore());
        }

        public ChannelDescriptor descriptor() { return descriptor; }
        public int health() { return healthScore.get(); }
        public void onResult(boolean success) {
            // EWMA-ish nudge: +2 on success, −8 on failure, clamped 0..100.
            healthScore.accumulateAndGet(success ? 2 : -8,
                    (prev, delta) -> Math.max(0, Math.min(100, prev + delta)));
        }
    }

    private final CopyOnWriteArrayList<ChannelRuntime> channels = new CopyOnWriteArrayList<>();
    private final List<RouteAdvisor> advisors;

    public PaymentChannelRouter(List<RouteAdvisor> advisors) {
        this(advisors, true);
    }

    /**
     * Framework-visible default constructor: no advisors (rules-only routing)
     * plus the built-in MOCK registration. Spring falls back to this when no
     * {@link RouteAdvisor} beans exist, so the router always boots.
     */
    public PaymentChannelRouter() {
        this(List.of(), true);
    }

    /** Test-visible overload: skip the built-in MOCK default registration. */
    PaymentChannelRouter(List<RouteAdvisor> advisors, boolean registerDefaults) {
        this.advisors = advisors == null ? List.of() : List.copyOf(advisors);
        if (registerDefaults) {
            registerDefaults();
        }
    }

    /** Boot-time registration. Real channels register via {@link #register}. */
    private void registerDefaults() {
        register(new ChannelDescriptor(
                PaymentChannel.MOCK.name(), "EP Dev Mock Channel",
                0, 100, 0, "CNY",
                new BigDecimal("0.000001"), new BigDecimal("1000000"), true,
                java.time.Instant.now()));
    }

    public void register(ChannelDescriptor descriptor) {
        channels.add(new ChannelRuntime(descriptor));
        log.info("[router] channel registered: {} (cost={}bps health={})",
                descriptor.channelId(), descriptor.costBps(), descriptor.healthScore());
    }

    public void onChannelResult(String channelId, boolean success) {
        channels.stream()
                .filter(c -> c.descriptor().channelId().equals(channelId))
                .findFirst()
                .ifPresent(c -> c.onResult(success));
    }

    /**
     * Route the request to the best channel.
     *
     * @throws IllegalStateException when no candidate supports the request —
     *                               callers translate to a 4xx/503, never a
     *                               silent fallback to a wrong channel.
     */
    public ChannelRuntime route(PaymentRequest request) {
        List<ChannelRuntime> candidates = channels.stream()
                .filter(c -> c.descriptor().supports(
                        request.currency() == null ? "CNY" : request.currency(),
                        request.amount()))
                .toList();
        if (candidates.isEmpty()) {
            throw new IllegalStateException(
                    "[router] no channel supports currency=" + request.currency()
                            + " amount=" + request.amount());
        }

        // Step 2: AI suggestion as hint (must itself pass the deterministic gate).
        for (RouteAdvisor advisor : advisors) {
            Optional<String> suggestion = advisor.suggestChannel(request);
            if (suggestion.isPresent()) {
                Optional<ChannelRuntime> hit = candidates.stream()
                        .filter(c -> c.descriptor().channelId().equals(suggestion.get()))
                        .findFirst();
                if (hit.isPresent()) {
                    log.info("[router] advisor suggestion accepted: {}", suggestion.get());
                    return hit.get();
                }
                log.warn("[router] advisor suggested non-candidate channel {} — ignored by rule gate",
                        suggestion.get());
            }
        }

        // Step 3: deterministic ranking — health desc, cost asc, priority asc.
        Comparator<ChannelRuntime> ranking = Comparator
                .comparingInt((ChannelRuntime c) -> c.health()).reversed()
                .thenComparingInt(c -> c.descriptor().costBps())
                .thenComparingInt(c -> c.descriptor().priority());
        ChannelRuntime best = candidates.stream().min(ranking).orElseThrow();
        log.debug("[router] routed to {} (health={} cost={}bps)",
                best.descriptor().channelId(), best.health(), best.descriptor().costBps());
        return best;
    }

    /** Read-only snapshot for ops/observability. */
    public Map<String, ChannelDescriptor> snapshot() {
        return channels.stream().collect(java.util.stream.Collectors.toMap(
                c -> c.descriptor().channelId(), ChannelRuntime::descriptor));
    }
}
