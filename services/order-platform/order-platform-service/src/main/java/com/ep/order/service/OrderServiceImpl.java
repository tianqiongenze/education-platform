package com.ep.order.service;

import com.ep.order.api.CreateOrderCmd;
import com.ep.order.api.InsufficientStockException;
import com.ep.order.api.OrderDto;
import com.ep.order.api.OrderService;
import com.ep.order.api.OrderStatus;
import com.ep.order.common.SnowflakeIdGenerator;
import com.ep.order.event.DomainEvent;
import com.ep.order.event.OutboxEntity;
import com.ep.order.event.OutboxRepository;
import com.ep.order.infrastructure.PulsarEventPublisher;
import com.ep.order.model.OrderModel;
import com.ep.order.repository.OrderRepository;
import com.ep.payment.api.PaymentChannel;
import com.ep.payment.api.PaymentRequest;
import com.ep.payment.api.PaymentResult;
import com.ep.payment.api.PaymentService;
import com.ep.payment.api.Refund;
import com.ep.cache.CacheService;
import com.ep.order.pricing.CoursePricingPort;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.seata.saga.engine.StateMachineEngine;
import org.apache.dubbo.config.annotation.DubboReference;
import org.apache.dubbo.config.annotation.DubboService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * core-business Order middle-platform application service.
 * <p>
 * Exposed over Dubbo via {@link DubboService @DubboService}. Implementing
 * {@link OrderService} (the thin RPC contract in order-platform-api).
 * <p>
 * Key differences from the legacy {@code com.ep.business.order.OrderServiceImpl}:
 * <ul>
 *   <li>The OPM {@link PaymentService} is now a <b>cross-service Dubbo RPC</b>
 *       reference ({@code @DubboReference(check=false, timeout=10000)}) rather
 *       than an in-process {@code @Autowired} bean.</li>
 *   <li>Each method converts {@link OrderModel} &harr; {@link OrderDto} so the
 *       JPA entity never crosses the Dubbo boundary.</li>
 *   <li>{@link #refundOrder(Long, String)} now <b>calls
 *       {@link PaymentService#refund}</b> (the original had a gap: it flipped
 *       the order status but never instructed Payment to refund).</li>
 * </ul>
 *
 * <h3>D-113: Seata AT -> Saga migration</h3>
 * The long-running order -> stock -> payment flow previously used Seata AT
 * ({@code @GlobalTransactional}). AT holds global locks for the entire flow,
 * which is unacceptable for long-running cross-service transactions, and there
 * is no live Seata TC server in dev/CI (the same gap that drove D-077 on grading-service).
 * <p>
 * The create-order flow is now driven by a Seata Saga state machine
 * ({@code create-order-saga.json}). Each forward step (reserveStock,
 * createPayment, confirmOrder) is a {@code @SagaCompensable} action with a
 * registered compensation (releaseStock, refundPayment, cancelOrder). The Saga
 * engine releases each branch's local lock as soon as the branch commits and
 * only retains the cheaper compensation contract.
 * <p>
 * <b>AT mode is retained for short transactions</b> — single-service writes
 * (e.g. plain {@link #createOrder}) still use a local {@link Transactional}
 * boundary where the global lock duration is negligible. See ADR
 * {@code docs/adr/D-113-seata-saga-migration.md} for the full decision record.
 */
@DubboService
public class OrderServiceImpl implements OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderServiceImpl.class);

    /** Saga state-machine definition name registered in {@code create-order-saga.json}. */
    private static final String CREATE_ORDER_SAGA = "createOrderSaga";
    /** Saga state-machine definition name registered in {@code cancel-order-saga.json}. */
    private static final String CANCEL_ORDER_SAGA = "cancelOrderSaga";

    // D-182: multi-level cache configuration.
    /** Cache name for order-by-id reads (300s, read-heavy). */
    private static final String CACHE_ORDER = "order:detail";
    /** Cache name for order-by-user-and-course reads (300s). */
    private static final String CACHE_ORDER_BY_USER_COURSE = "order:by-user-course";
    /** Order cache TTL in seconds. */
    private static final long ORDER_TTL_SEC = 300;

    private final OrderRepository orderRepository;
    private final PulsarEventPublisher eventPublisher;

    /**
     * D-217: Transactional-outbox repository. Domain events are persisted to
     * {@code ep_outbox} in the SAME JPA transaction as the aggregate state
     * change (see {@link #publishEvents(OrderModel)}), eliminating the dual-write
     * gap where a Pulsar publish could be lost after commit or sent after a
     * rollback. The {@code OutboxRelayService} drains the outbox asynchronously.
     */
    private final OutboxRepository outboxRepository;

    /**
     * D-113: Seata Saga state-machine engine. Drives the long-running
     * order -> stock -> payment flow via the JSON definitions under
     * {@code src/main/resources/saga/}. Injected (rather than field-autowired)
     * so the integration tests can swap in a stubbed engine.
     */
    private final StateMachineEngine sagaEngine;

    /**
     * D-182: Multi-level cache service (L1 Caffeine + L2 Redis).
     * Used to cache order details for read-heavy lookups.
     */
    private final CacheService cacheService;

    /**
     * Cross-service Dubbo RPC reference to the OPM Payment middle platform.
     * {@code check=false} so the order service boots even when the payment
     * provider is not yet registered (dev/CI); {@code timeout=10000ms} covers
     * the synchronous gateway round-trip on the pay path.
     */
    @DubboReference(check = false, timeout = 10000, url = "${dubbo.ref.payment.url:}")
    private PaymentService paymentService;

    /**
     * D-218: fail-closed authoritative course pricing. The client-supplied
     * amount is never trusted — COURSE orders resolve the price through this
     * port and override the client value.
     */
    private final CoursePricingPort coursePricingPort;

    /** D-218: pagination size cap (anti-scraping). */
    static final int MAX_PAGE_SIZE = 100;

    // D-232: two constructors exist (this primary + the legacy 5-arg test
    // compat below) — Spring needs @Autowired to pick this one instead of
    // falling back to a (nonexistent) default constructor.
    @org.springframework.beans.factory.annotation.Autowired
    public OrderServiceImpl(OrderRepository orderRepository,
                            PulsarEventPublisher eventPublisher,
                            StateMachineEngine sagaEngine,
                            CacheService cacheService,
                            OutboxRepository outboxRepository,
                            CoursePricingPort coursePricingPort) {
        this.orderRepository = orderRepository;
        this.eventPublisher = eventPublisher;
        this.sagaEngine = sagaEngine; // may be null when ep.saga.enabled=false (D-232 degraded mode)
        this.cacheService = cacheService;
        this.outboxRepository = outboxRepository;
        this.coursePricingPort = Objects.requireNonNull(coursePricingPort,
                "coursePricingPort (D-218 fail-closed pricing) must not be null");
    }

    /**
     * Legacy 5-arg constructor kept for existing unit tests that construct the
     * service directly without a pricing port. Orders for COURSE products will
     * be rejected fail-closed until a {@link CoursePricingPort} is provided.
     */
    public OrderServiceImpl(OrderRepository orderRepository,
                            PulsarEventPublisher eventPublisher,
                            StateMachineEngine sagaEngine,
                            CacheService cacheService,
                            OutboxRepository outboxRepository) {
        this(orderRepository, eventPublisher, sagaEngine, cacheService, outboxRepository,
                courseId -> {
                    throw new IllegalStateException(
                            "D-218 fail-closed: no pricing port configured — COURSE orders rejected");
                });
    }

    @Override
    @Transactional
    public OrderDto createOrder(CreateOrderCmd cmd) {
        Objects.requireNonNull(cmd, "cmd must not be null");
        Objects.requireNonNull(cmd.userId(), "userId must not be null");
        String productType = cmd.productType() == null ? OrderModel.PRODUCT_COURSE : cmd.productType();
        if (OrderModel.PRODUCT_COURSE.equals(productType) && cmd.courseId() == null) {
            throw new IllegalArgumentException("courseId/productId is required for COURSE orders");
        }
        Objects.requireNonNull(cmd.amount(), "amount must not be null");
        if (cmd.amount().signum() <= 0) {
            throw new IllegalArgumentException("amount must be > 0");
        }
        requireNonBlank(cmd.idempotencyKey(), "idempotencyKey");

        if (orderRepository.findByIdempotencyKey(cmd.idempotencyKey()).isPresent()) {
            throw new IllegalStateException("Duplicate idempotency key: " + cmd.idempotencyKey());
        }
        // D-218: server-side amount verification (fail-closed). The client
        // amount is never trusted: COURSE orders resolve the authoritative
        // price over RPC and override the client value. Any pricing failure
        // (provider down, course missing, price unset) rejects the order.
        BigDecimal amount = cmd.amount();
        if (OrderModel.PRODUCT_COURSE.equals(productType)) {
            BigDecimal authoritative = coursePricingPort.authoritativePrice(cmd.courseId());
            if (amount.compareTo(authoritative) != 0) {
                log.warn("D-218: client amount {} overridden by authoritative price {} for courseId={}",
                        amount, authoritative, cmd.courseId());
            }
            amount = authoritative;
        }
        long now = System.currentTimeMillis();
        OrderModel order = new OrderModel();
        order.setOrderId(SnowflakeIdGenerator.getInstance().nextId());
        order.setUserId(cmd.userId());
        order.setCourseId(cmd.courseId());
        order.setAmount(amount);
        order.setStatus(OrderStatus.PENDING.getCode());
        order.setPayMethod(null);
        order.setIdempotencyKey(cmd.idempotencyKey());
        order.setCreatedAt(now);
        order.setUpdatedAt(now);
        order.setTenantId(cmd.tenantId());
        order.setProductType(cmd.productType());
        OrderModel saved = orderRepository.save(order);
        // D-182: evict cache entries after write.
        cacheService.evict(CACHE_ORDER, String.valueOf(saved.getOrderId()));
        cacheService.evict(CACHE_ORDER_BY_USER_COURSE, saved.getUserId() + ":" + saved.getCourseId());
        return toDto(saved);
    }

    /**
     * D-113: Long-running order -> stock -> payment flow now driven by a Seata
     * Saga state machine instead of {@code @GlobalTransactional} (AT).
     * <p>
     * Forward: reserveStock -> createPayment -> confirmOrder.<br>
     * Compensate (LIFO on any failure): releaseStock -> refundPayment ->
     * cancelOrder.
     * <p>
     * The order row is first created in PENDING (local {@code @Transactional}
     * only — AT mode retained for short transactions); the Saga then drives the
     * cross-service steps and the final PENDING -> PAID transition.
     */
    @Override
    @Transactional
    public OrderDto createOrderWithGlobalTx(CreateOrderCmd cmd, int stockUnits) {
        Objects.requireNonNull(cmd, "cmd must not be null");
        Objects.requireNonNull(cmd.courseId(), "courseId must not be null");
        if (stockUnits < 0) {
            throw new IllegalArgumentException("stockUnits must be >= 0");
        }

        // Pre-step: create the PENDING order row (local transaction only — short
        // AT-style write, NOT part of the Saga global lock window).
        OrderDto order = createOrder(cmd);

        // Build the Saga context and start the state machine. The engine invokes
        // the @SagaCompensable actions (StockCompensableAction, etc.) declared in
        // create-order-saga.json. On any forward failure the engine replays the
        // registered compensations LIFO.
        Map<String, Object> sagaContext = new HashMap<>();
        sagaContext.put("orderId", order.orderId());
        sagaContext.put("courseId", cmd.courseId());
        sagaContext.put("stockUnits", stockUnits);
        sagaContext.put("amount", cmd.amount());
        sagaContext.put("payMethod", "MOCK");
        sagaContext.put("tenantId", cmd.tenantId());
        sagaContext.put("businessKey", cmd.idempotencyKey());
        sagaContext.put("reason", "D-113 saga createOrder failure");

        if (sagaEngine == null) {
            // D-232 degraded mode (ep.saga.enabled=false): local-transaction
            // order only; cross-service steps run via their direct Dubbo URLs
            // with compensation actions, orchestrated by the callers.
            log.warn("D-232: saga engine disabled (ep.saga.enabled=false) — createOrderWithGlobalTx degraded to local-transaction create for orderId={}",
                    order.orderId());
            return order;
        }
        log.info("D-113: starting createOrderSaga orderId={} courseId={} stockUnits={}",
                order.orderId(), cmd.courseId(), stockUnits);
        try {
            sagaEngine.startWithBusinessKey(
                    CREATE_ORDER_SAGA, null, cmd.idempotencyKey(), sagaContext);
        } catch (RuntimeException e) {
            // D-077/D-113 parity: when no live TC is available (dev/CI) the Saga
            // engine may surface a transport error. Surface it honestly rather
            // than silently masking (Iron Law 0).
            log.error("D-113: createOrderSaga failed for orderId={} (compensations replayed by engine): {}",
                    order.orderId(), e.getMessage(), e);
            throw e;
        }
        return order;
    }

    /**
     * D-113: Explicit order cancellation driven by the cancel-order Saga.
     * <p>
     * Forward: cancelOrder -> releaseStock -> refundPayment. The forward path
     * itself is the compensation of the original create-order saga, so no
     * further compensations are registered.
     *
     * @param orderId the order to cancel
     * @param reason  free-text cancellation reason
     */
    public void cancelOrderWithSaga(Long orderId, String reason) {
        Objects.requireNonNull(orderId, "orderId must not be null");
        requireNonBlank(reason, "reason");
        OrderModel order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalStateException("Order not found: " + orderId));

        Map<String, Object> sagaContext = new HashMap<>();
        sagaContext.put("orderId", orderId);
        sagaContext.put("courseId", order.getCourseId());
        sagaContext.put("stockUnits", 1);
        sagaContext.put("amount", order.getAmount() == null ? BigDecimal.ZERO : order.getAmount());
        sagaContext.put("reason", reason);

        if (sagaEngine == null) {
            log.warn("D-232: saga engine disabled — cancelOrderWithGlobalTx degraded for orderId={}: invoking refund + cancel locally",
                    orderId);
            refundOrder(orderId, reason);
            order.setStatus(com.ep.order.api.OrderStatus.CANCELED.getCode());
            order.setUpdatedAt(System.currentTimeMillis());
            orderRepository.save(order);
            return;
        }
        log.info("D-113: starting cancelOrderSaga orderId={} reason={}", orderId, reason);
        sagaEngine.startWithBusinessKey(
                CANCEL_ORDER_SAGA, null, "cancel-" + orderId, sagaContext);
    }

    /**
     * Inventory deduction wrapped with a Resilience4j circuit breaker. Public
     * (required for Resilience4j AOP proxying). Retained from the pre-Saga code
     * as the fallback contract for direct (non-Saga) inventory calls; the Saga
     * flow now wraps the same reservation in {@code StockCompensableAction}.
     */
    @CircuitBreaker(name = "inventoryService", fallbackMethod = "inventoryFallback")
    public void deductInventory(Long courseId, int amount) {
        log.info("D-066: inventory deduction simulated for course {} amount {} (in-process state removed)",
                courseId, amount);
        if (amount < 0) {
            throw new InsufficientStockException(courseId, amount, 0);
        }
    }

    /** Fallback when the inventory circuit breaker is OPEN. */
    private void inventoryFallback(Long courseId, int amount, Exception e) {
        log.warn("Circuit breaker OPEN for inventory service - course={}, amount={}, error={}",
                courseId, amount, e.getMessage());
        throw new IllegalStateException("Inventory service temporarily unavailable, please retry");
    }

    @Override
    @Transactional(readOnly = true)
    public OrderDto getOrderById(Long orderId) {
        Objects.requireNonNull(orderId, "orderId must not be null");
        // D-182: cache read-heavy order detail (300s, cacheEmpty=true).
        return cacheService.getOrLoad(CACHE_ORDER, String.valueOf(orderId),
                () -> orderRepository.findById(orderId).map(OrderServiceImpl::toDto).orElse(null),
                ORDER_TTL_SEC, true);
    }

    @Override
    @Transactional(readOnly = true)
    public OrderDto getOrderByUserAndCourse(Long userId, Long courseId) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(courseId, "courseId must not be null");
        // D-182: cache read-heavy order-by-user-and-course (300s, cacheEmpty=true).
        return cacheService.getOrLoad(CACHE_ORDER_BY_USER_COURSE, userId + ":" + courseId,
                () -> orderRepository.findByUserIdAndCourseId(userId, courseId)
                        .map(OrderServiceImpl::toDto).orElse(null),
                ORDER_TTL_SEC, true);
    }

    @Override
    @Transactional
    public void payOrder(Long orderId, String payMethod) {
        Objects.requireNonNull(orderId, "orderId must not be null");
        requireNonBlank(payMethod, "payMethod");
        OrderModel order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalStateException("Order not found: " + orderId));
        // state transition (PENDING -> PAID) recorded inside the aggregate
        order.pay(payMethod);
        orderRepository.save(order);
        // D-182: evict cache entries after write.
        cacheService.evict(CACHE_ORDER, String.valueOf(order.getOrderId()));
        cacheService.evict(CACHE_ORDER_BY_USER_COURSE, order.getUserId() + ":" + order.getCourseId());
        // Record a real Payment via cross-service RPC. Best-effort: a Payment
        // failure must NOT roll back the order status flip - the order is PAID
        // regardless; Payment is a downstream concern reconciled out-of-band.
        try {
            PaymentChannel channel = resolvePaymentChannel(payMethod);
            PaymentRequest request = new PaymentRequest(
                    String.valueOf(order.getOrderId()),
                    order.getTenantId() == null ? OrderModel.DEFAULT_TENANT : order.getTenantId(),
                    channel, order.getAmount() == null ? BigDecimal.ZERO : order.getAmount(),
                    "CNY");
            PaymentResult result = paymentService.createPayment(request);
            log.info("OPM payment recorded for orderId={}: paymentId={}, status={}, channelTxId={}",
                    orderId, result.paymentId(), result.status(), result.channelTxId());
        } catch (Exception e) {
            log.error("OPM payment creation failed for orderId={} (order stays PAID): {}",
                    orderId, e.getMessage(), e);
        }
        publishEvents(order);
    }

    @Override
    @Transactional
    public void refundOrder(Long orderId, String reason) {
        Objects.requireNonNull(orderId, "orderId must not be null");
        requireNonBlank(reason, "reason");
        OrderModel order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalStateException("Order not found: " + orderId));
        // state transition (PAID -> REFUNDED) recorded inside the aggregate
        order.refund();
        orderRepository.save(order);
        // D-182: evict cache entries after write.
        cacheService.evict(CACHE_ORDER, String.valueOf(order.getOrderId()));
        cacheService.evict(CACHE_ORDER_BY_USER_COURSE, order.getUserId() + ":" + order.getCourseId());
        // FIX (vs legacy core-business): now actually instruct Payment to refund.
        // Best-effort: a Payment refund failure is logged but does NOT roll back
        // the order status flip - reconciliation catches the gap out-of-band.
        try {
            Refund refund = paymentService.refund(
                    String.valueOf(order.getOrderId()),
                    order.getAmount() == null ? BigDecimal.ZERO : order.getAmount(),
                    reason);
            log.info("OPM refund recorded for orderId={}: refundId={}, status={}",
                    orderId, refund.getRefundId(), refund.getStatus());
        } catch (Exception e) {
            log.error("OPM refund failed for orderId={} (order stays REFUNDED): {}",
                    orderId, e.getMessage(), e);
        }
        publishEvents(order);
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrderDto> listUserOrders(Long userId, Integer page, Integer size) {
        Objects.requireNonNull(userId, "userId must not be null");
        int p = page == null || page < 1 ? 1 : page;
        // D-218: pagination size cap — an unbounded size would allow
        // full-table scraping with a single request (e.g. size=10^6).
        int s = Math.min(size == null || size < 1 ? 10 : size, MAX_PAGE_SIZE);
        Page<OrderModel> pageResult = orderRepository.findByUserIdOrderByOrderIdAsc(
                userId, PageRequest.of(p - 1, s));
        List<OrderDto> out = new ArrayList<>(pageResult.getNumberOfElements());
        for (OrderModel e : pageResult.getContent()) {
            out.add(toDto(e));
        }
        return out;
    }

    // --------------------- helpers ---------------------

    /**
     * D-217: Transactional-outbox write.
     * <p>
     * Domain events pulled from the aggregate are persisted to {@code ep_outbox}
     * <b>inside</b> the caller's {@code @Transactional} boundary (createOrder /
     * payOrder / refundOrder), so the event write is atomic with the business
     * write. The {@code OutboxRelayService} later drains the outbox and forwards
     * each row to Pulsar — this method does NOT publish directly, which closes
     * the dual-write gap (no event lost on commit, no phantom event on
     * rollback).
     */
    private void publishEvents(OrderModel order) {
        List<DomainEvent> events = order.pullEvents();
        for (DomainEvent event : events) {
            log.info("D-217: domain event saved to outbox: {}", event);
            OutboxEntity outbox = new OutboxEntity();
            outbox.setEventId(event.eventId());
            outbox.setAggregateType("Order");
            outbox.setAggregateId(String.valueOf(order.getOrderId()));
            outbox.setEventType(event.eventType());
            outbox.setPayload(event.toJson());
            outbox.setCreatedAt(Instant.now());
            outboxRepository.save(outbox);
        }
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    /**
     * Map the legacy free-text {@code payMethod} to a {@link PaymentChannel}.
     * Unknown / unparseable values fall back to {@link PaymentChannel#MOCK}
     * (EP_DEV_ONLY-safe). Matching is case-insensitive on the enum name.
     */
    private static PaymentChannel resolvePaymentChannel(String payMethod) {
        if (payMethod == null) {
            return PaymentChannel.MOCK;
        }
        String upper = payMethod.trim().toUpperCase();
        for (PaymentChannel ch : PaymentChannel.values()) {
            if (ch.name().equals(upper)) {
                return ch;
            }
        }
        if ("ALIPAY".equals(upper) || "ZFB".equals(upper)) return PaymentChannel.ALIPAY;
        if ("WECHAT".equals(upper) || "WX".equals(upper) || "WECHATPAY".equals(upper)) {
            return PaymentChannel.WECHAT;
        }
        return PaymentChannel.MOCK;
    }

    /** Entity -&gt; DTO (never expose the JPA entity over the wire). */
    static OrderDto toDto(OrderModel e) {
        return new OrderDto(
                e.getOrderId(),
                e.getUserId(),
                e.getCourseId(),
                e.getTenantId(),
                e.getProductType(),
                e.getAmount(),
                e.getStatus(),
                e.getPayMethod(),
                e.getIdempotencyKey(),
                e.getCreatedAt(),
                e.getUpdatedAt());
    }
}
