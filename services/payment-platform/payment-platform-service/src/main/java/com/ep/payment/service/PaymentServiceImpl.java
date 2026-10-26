package com.ep.payment.service;

import com.ep.payment.api.Payment;
import com.ep.payment.api.PaymentChannel;
import com.ep.payment.api.PaymentChannelSpi;
import com.ep.payment.api.PaymentRequest;
import com.ep.payment.api.PaymentResult;
import com.ep.payment.api.PaymentService;
import com.ep.payment.api.PaymentStatus;
import com.ep.payment.api.Refund;
import com.ep.payment.api.RefundResult;
import com.ep.payment.api.RefundStatus;
import com.ep.payment.channel.MockPaymentChannel;
import com.ep.payment.entity.PaymentEntity;
import com.ep.payment.entity.RefundEntity;
import com.ep.payment.repository.PaymentJpaRepository;
import com.ep.payment.repository.RefundJpaRepository;
import com.ep.cache.CacheService;
import org.apache.dubbo.config.annotation.DubboService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link PaymentService} implementation. Wires the OPM Payment/Refund domain
 * to the {@link PaymentChannelSpi} strategy set (SDD §3.3).
 *
 * <p><b>D-132: persistence.</b> Payment and Refund records are now persisted to
 * TiDB via {@link PaymentJpaRepository} / {@link RefundJpaRepository} (tables
 * {@code ep_payment} / {@code ep_refund}), so they survive process restarts.
 * The repositories are injected through {@link ObjectProvider} so the pure-Mockito
 * unit tests (which construct this service with {@code new} and no Spring context)
 * keep working — when the repositories are absent the service transparently falls
 * back to the legacy in-memory {@link ConcurrentHashMap} store. In every Spring
 * context (dev/prod/test-context) the JPA repositories are present and the
 * in-memory store is unused.
 *
 * <p>Channel selection: collects every {@link PaymentChannelSpi} bean Spring
 * finds and dispatches by {@link PaymentChannelSpi#getChannel()}.
 * EP_DEV_ONLY: when {@code ep.payment.dev-guard=true} (the default), the
 * requested channel is overridden to {@link PaymentChannel#MOCK} so dev/CI
 * never accidentally hits a real gateway even if a real-channel bean were
 * present. The {@link com.ep.payment.config.DevGuardConfig} startup guard
 * additionally refuses to boot if {@code EP_DEV_ONLY=true} in a non-dev
 * profile.
 *
 * <p>Idempotency: {@code createPayment} deduplicates by {@code orderId} — a
 * second create request for an order that already has a payment returns the
 * existing payment (same {@code paymentId}) instead of minting a new one. The
 * dedup is enforced both in the application layer ({@code existsByOrderId} /
 * the in-memory {@code paymentsByOrder} map) and at the DB level via the
 * UNIQUE constraint on {@code ep_payment.order_id} (D-090), so the guard holds
 * even under a concurrent race. {@code paymentId} itself is a UUID minted per
 * create call.
 */
@DubboService
public class PaymentServiceImpl implements PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentServiceImpl.class);

    // D-182: multi-level cache configuration (read-only 120s).
    /** Cache name for payment-by-id reads. */
    private static final String CACHE_PAYMENT = "payment:detail";
    /** Cache name for payment-by-order reads. */
    private static final String CACHE_PAYMENT_BY_ORDER = "payment:by-order";
    /** Payment cache TTL in seconds (read-only). */
    private static final long PAYMENT_TTL_SEC = 120;

    /**
     * D-132: legacy in-memory fallback. Used only when the JPA repositories are
     * not available — i.e. the pure-Mockito unit tests that construct this service
     * directly. In any Spring context the repositories are wired and these maps
     * stay empty. D-039: ConcurrentHashMap, no synchronized.
     */
    private final Map<String, Payment> paymentsById = new ConcurrentHashMap<>();
    private final Map<String, Payment> paymentsByOrder = new ConcurrentHashMap<>();
    private final Map<String, Refund> refundsById = new ConcurrentHashMap<>();

    private final Map<PaymentChannel, PaymentChannelSpi> channels;
    private final PaymentChannelSpi defaultChannel;
    private final boolean devGuard;

    /**
     * D-132: JPA repositories (optional via {@link ObjectProvider}). Present in
     * every Spring context; absent in pure-Mockito unit tests, where the in-memory
     * fallback above is used instead.
     */
    private final PaymentJpaRepository paymentRepository;
    private final RefundJpaRepository refundRepository;

    /**
     * D-182: Multi-level cache service (L1 Caffeine + L2 Redis).
     * Read-only caching for PaymentEntity; evicted on refund (status change).
     */
    private final CacheService cacheService;

    /**
     * Spring-managed constructor. The repositories are injected via
     * {@link ObjectProvider} so they are optional — when both are resolved the
     * service persists to TiDB; when neither is available (pure unit test) the
     * in-memory maps are used.
     *
     * @param channelSpis             every {@link PaymentChannelSpi} bean Spring discovers
     *                                (may be empty in some test contexts — defaults are used)
     * @param paymentRepositoryProvider JPA repository for {@code ep_payment} (optional)
     * @param refundRepositoryProvider  JPA repository for {@code ep_refund} (optional)
     * @param devGuard                value of {@code ep.payment.dev-guard}; when true the
     *                                requested channel is forced to MOCK
     */
    @Autowired
    public PaymentServiceImpl(List<PaymentChannelSpi> channelSpis,
                              ObjectProvider<PaymentJpaRepository> paymentRepositoryProvider,
                              ObjectProvider<RefundJpaRepository> refundRepositoryProvider,
                              @Value("${ep.payment.dev-guard:true}") boolean devGuard,
                              CacheService cacheService) {
        this.devGuard = devGuard;
        this.paymentRepository = paymentRepositoryProvider == null ? null : paymentRepositoryProvider.getIfAvailable();
        this.refundRepository = refundRepositoryProvider == null ? null : refundRepositoryProvider.getIfAvailable();
        this.cacheService = cacheService;
        ConcurrentHashMap<PaymentChannel, PaymentChannelSpi> map = new ConcurrentHashMap<>();
        PaymentChannelSpi fallback = null;
        if (channelSpis != null) {
            for (PaymentChannelSpi spi : channelSpis) {
                PaymentChannel existing = spi.getChannel();
                if (existing == null) {
                    // SPI that cannot identify itself — skip it but log.
                    log.warn("PaymentChannelSpi without a channel, ignoring: {}", spi.getClass());
                    continue;
                }
                map.putIfAbsent(existing, spi);
                if (existing == PaymentChannel.MOCK) {
                    fallback = spi;
                }
            }
        }
        if (fallback == null) {
            // No MOCK bean discovered (e.g. plain unit test). Synthesize one so the
            // service always has a working default channel.
            fallback = new MockPaymentChannel();
        }
        this.channels = map;
        this.defaultChannel = fallback;
        log.info("PaymentServiceImpl initialised: channels={}, devGuard={}, defaultChannel={}, persistence={}",
                map.keySet(), devGuard, defaultChannel.getChannel(),
                paymentRepository != null ? "JPA" : "in-memory");
    }

    /**
     * Back-compat constructor for the pure-Mockito unit tests (no Spring context).
     * Equivalent to the Spring constructor with {@code null} repository providers,
     * so the service falls back to the in-memory store.
     */
    public PaymentServiceImpl(List<PaymentChannelSpi> channelSpis, boolean devGuard) {
        this(channelSpis, null, null, devGuard, null);
    }

    /**
     * D-182: Test constructor that accepts a CacheService for cache integration
     * tests. Passes null for repository providers so in-memory store is used.
     */
    public PaymentServiceImpl(List<PaymentChannelSpi> channelSpis, boolean devGuard,
                              CacheService cacheService) {
        this(channelSpis, null, null, devGuard, cacheService);
    }

    private boolean isJpa() {
        return paymentRepository != null && refundRepository != null;
    }

    /** D-182: test-only accessor for verifying cache interactions. */
    CacheService getCacheServiceForTest() {
        return cacheService;
    }

    @Override
    @Transactional
    public PaymentResult createPayment(PaymentRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        requireNonBlank(request.orderId(), "orderId");
        requireNonBlank(request.tenantId(), "tenantId");
        Objects.requireNonNull(request.amount(), "amount must not be null");
        if (request.amount().signum() <= 0) {
            throw new IllegalArgumentException("amount must be > 0");
        }
        if (request.channel() == null) {
            throw new IllegalArgumentException("channel must not be null");
        }

        // D-090: idempotency — a payment already exists for this order. Return
        // the existing payment (same paymentId) instead of minting a duplicate.
        // Both the JPA path (existsByOrderId, backed by the UNIQUE order_id
        // constraint) and the in-memory fallback (paymentsByOrder) are covered.
        Payment existing = isJpa()
                ? (paymentRepository.existsByOrderId(request.orderId())
                        ? getPaymentByOrderDirect(request.orderId())
                        : null)
                : paymentsByOrder.get(request.orderId());
        if (existing != null) {
            log.info("createPayment: idempotent reuse for orderId={}, paymentId={}",
                    request.orderId(), existing.getPaymentId());
            return new PaymentResult(existing.getPaymentId(), existing.getStatus(),
                    existing.getChannelTxId(), existing.getPaidAt());
        }

        PaymentChannel effective = devGuard ? PaymentChannel.MOCK : request.channel();
        PaymentChannelSpi spi = resolveChannel(effective);

        String paymentId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        Payment payment = new Payment(paymentId, request.orderId(), request.tenantId(),
                request.amount(), request.currency() == null ? "CNY" : request.currency(),
                spi.getChannel(), PaymentStatus.PENDING, now);

        // Drive the channel. The mock is synchronous and returns SUCCESS; a real
        // channel may return PROCESSING and complete via callback.
        PaymentResult raw = spi.pay(new PaymentRequest(request.orderId(), request.tenantId(),
                spi.getChannel(), request.amount(), request.currency()));

        if (raw.status() == PaymentStatus.SUCCESS) {
            payment.markSuccess(raw.channelTxId(), raw.paidAt());
        } else if (raw.status() == PaymentStatus.PROCESSING) {
            payment.beginProcessing();
        } else if (raw.status() == PaymentStatus.FAILED) {
            payment.markFailed();
        }

        if (isJpa()) {
            PaymentEntity entity = toEntity(payment);
            paymentRepository.save(entity);
        } else {
            paymentsById.put(paymentId, payment);
            paymentsByOrder.put(request.orderId(), payment);
        }

        // D-182: evict potentially cached null/empty entry for this paymentId/orderId.
        if (cacheService != null) {
            cacheService.evict(CACHE_PAYMENT, paymentId);
            cacheService.evict(CACHE_PAYMENT_BY_ORDER, request.orderId());
        }

        log.info("createPayment: orderId={}, channel={}, status={}, paymentId={}, channelTxId={}",
                request.orderId(), spi.getChannel(), payment.getStatus(), paymentId,
                payment.getChannelTxId());

        return new PaymentResult(paymentId, payment.getStatus(), payment.getChannelTxId(),
                payment.getPaidAt());
    }

    @Override
    @Transactional(readOnly = true)
    public Payment getPayment(String paymentId) {
        requireNonBlank(paymentId, "paymentId");
        // D-182: cache read-only payment (120s, cacheEmpty=true).
        if (cacheService == null) {
            return getPaymentDirect(paymentId);
        }
        return cacheService.getOrLoad(CACHE_PAYMENT, paymentId,
                () -> getPaymentDirect(paymentId), PAYMENT_TTL_SEC, true);
    }

    private Payment getPaymentDirect(String paymentId) {
        if (isJpa()) {
            return paymentRepository.findById(paymentId).map(PaymentServiceImpl::toDomain).orElse(null);
        }
        return paymentsById.get(paymentId);
    }

    @Override
    @Transactional(readOnly = true)
    public Payment getPaymentByOrder(String orderId) {
        requireNonBlank(orderId, "orderId");
        // D-182: cache read-only payment-by-order (120s, cacheEmpty=true).
        if (cacheService == null) {
            return getPaymentByOrderDirect(orderId);
        }
        return cacheService.getOrLoad(CACHE_PAYMENT_BY_ORDER, orderId,
                () -> getPaymentByOrderDirect(orderId), PAYMENT_TTL_SEC, true);
    }

    private Payment getPaymentByOrderDirect(String orderId) {
        if (isJpa()) {
            return paymentRepository.findFirstByOrderId(orderId)
                    .map(PaymentServiceImpl::toDomain).orElse(null);
        }
        return paymentsByOrder.get(orderId);
    }

    @Override
    @Transactional
    public Refund refund(String orderId, BigDecimal amount, String reason) {
        requireNonBlank(orderId, "orderId");
        Objects.requireNonNull(amount, "amount must not be null");
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be > 0");
        }
        Payment payment;
        PaymentEntity paymentEntity = null;
        if (isJpa()) {
            paymentEntity = paymentRepository.findFirstByOrderId(orderId).orElse(null);
            payment = paymentEntity == null ? null : toDomain(paymentEntity);
        } else {
            payment = paymentsByOrder.get(orderId);
        }
        if (payment == null) {
            throw new IllegalStateException("No payment for order: " + orderId);
        }
        if (!payment.canRefund()) {
            throw new IllegalStateException(
                    "Payment not refundable (status=" + payment.getStatus()
                            + "), paymentId=" + payment.getPaymentId());
        }

        // D-236 (money-safety): cumulative refund cap — the sum of all active
        // (non-FAILED) refunds of this payment plus the new request must never
        // exceed the payment amount. Before this guard, N partial refunds
        // could each pass the per-call check and jointly over-refund the
        // payment (a classic funds-loss defect). The SDD spec ("the Payment
        // only transitions to REFUNDED when the sum of successful Refunds
        // equals the Payment amount") is now enforced literally.
        BigDecimal alreadyRefunded = isJpa()
                ? refundRepository.sumActiveRefundAmountByOrderId(orderId)
                : refundsById.values().stream()
                        .filter(r -> orderId.equals(r.getOrderId()) && r.getStatus() != RefundStatus.FAILED)
                        .map(Refund::getAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal cumulativeAfter = alreadyRefunded.add(amount);
        if (cumulativeAfter.compareTo(payment.getAmount()) > 0) {
            throw new IllegalArgumentException(
                    "Refund overdraw rejected: payment=" + payment.getAmount()
                            + ", alreadyRefunded=" + alreadyRefunded
                            + ", requested=" + amount
                            + ", paymentId=" + payment.getPaymentId());
        }

        PaymentChannelSpi spi = resolveChannel(payment.getChannel());
        if (!spi.supportsRefund()) {
            throw new IllegalStateException(
                    "Channel " + spi.getChannel() + " does not support refund");
        }
        Optional<RefundResult> result = spi.refund(payment.getChannelTxId(), amount, reason);
        if (result.isEmpty() || !result.get().success()) {
            Refund failed = new Refund(UUID.randomUUID().toString(), payment.getPaymentId(),
                    orderId, payment.getTenantId(), amount, RefundStatus.FAILED, reason, Instant.now());
            if (isJpa()) {
                refundRepository.save(toEntity(failed));
            } else {
                refundsById.put(failed.getRefundId(), failed);
            }
            log.warn("refund failed: orderId={}, paymentId={}, amount={}", orderId,
                    payment.getPaymentId(), amount);
            return failed;
        }

        Refund refund = new Refund(UUID.randomUUID().toString(), payment.getPaymentId(),
                orderId, payment.getTenantId(), amount, RefundStatus.PENDING, reason, Instant.now());
        refund.markSuccess(Instant.now());
        if (isJpa()) {
            refundRepository.save(toEntity(refund));
        } else {
            refundsById.put(refund.getRefundId(), refund);
        }

        // D-236: full-refund flip uses the cumulative ledger, not the single
        // call's amount — a 60/40 split across two partial refunds must leave
        // the payment REFUNDED after the second one.
        if (cumulativeAfter.compareTo(payment.getAmount()) >= 0) {
            payment.markRefunded();
        }

        if (isJpa()) {
            // Persist the post-refund payment state (status may have flipped).
            assert paymentEntity != null;
            paymentEntity.setStatus(payment.getStatus());
            paymentRepository.save(paymentEntity);
        }

        // D-182: evict cache entries after refund (payment status changed).
        if (cacheService != null) {
            cacheService.evict(CACHE_PAYMENT, payment.getPaymentId());
            cacheService.evict(CACHE_PAYMENT_BY_ORDER, orderId);
        }

        log.info("refund ok: orderId={}, paymentId={}, refundId={}, amount={}, paymentStatus={}",
                orderId, payment.getPaymentId(), refund.getRefundId(), amount, payment.getStatus());
        return refund;
    }

    /**
     * Resolve the SPI for a channel. Falls back to the default (MOCK) when the
     * channel has no registered bean, so the service keeps working in dev/CI
     * even if only the mock bean is present.
     */
    private PaymentChannelSpi resolveChannel(PaymentChannel channel) {
        PaymentChannelSpi spi = channels.get(channel);
        return spi != null ? spi : defaultChannel;
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    // --------------------- domain ↔ entity mapping (D-132) ---------------------

    private static PaymentEntity toEntity(Payment p) {
        PaymentEntity e = new PaymentEntity();
        e.setPaymentId(p.getPaymentId());
        e.setOrderId(p.getOrderId());
        e.setTenantId(p.getTenantId());
        e.setAmount(p.getAmount());
        e.setCurrency(p.getCurrency());
        e.setChannel(p.getChannel());
        e.setStatus(p.getStatus());
        e.setChannelTxId(p.getChannelTxId());
        e.setCreatedAt(p.getCreatedAt());
        e.setPaidAt(p.getPaidAt());
        e.setLockVersion(p.getLockVersion() == null ? 0 : p.getLockVersion());
        return e;
    }

    private static Payment toDomain(PaymentEntity e) {
        Payment p = new Payment(e.getPaymentId(), e.getOrderId(), e.getTenantId(),
                e.getAmount(), e.getCurrency(), e.getChannel(), e.getStatus(), e.getCreatedAt());
        p.setChannelTxId(e.getChannelTxId());
        p.setPaidAt(e.getPaidAt());
        p.setLockVersion(e.getLockVersion());
        return p;
    }

    private static RefundEntity toEntity(Refund r) {
        RefundEntity e = new RefundEntity();
        e.setRefundId(r.getRefundId());
        e.setPaymentId(r.getPaymentId());
        e.setOrderId(r.getOrderId());
        e.setTenantId(r.getTenantId());
        e.setAmount(r.getAmount());
        e.setStatus(r.getStatus());
        e.setReason(r.getReason());
        e.setCreatedAt(r.getCreatedAt());
        e.setRefundedAt(r.getRefundedAt());
        e.setLockVersion(0);
        return e;
    }
}
