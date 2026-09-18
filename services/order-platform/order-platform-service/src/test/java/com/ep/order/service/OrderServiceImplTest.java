package com.ep.order.service;

import com.ep.order.api.CreateOrderCmd;
import com.ep.order.api.OrderDto;
import com.ep.order.api.OrderStatus;
import com.ep.order.model.OrderModel;
import com.ep.order.event.OutboxEntity;
import com.ep.order.event.OutboxRepository;
import com.ep.order.infrastructure.PulsarEventPublisher;
import com.ep.order.repository.OrderRepository;
import com.ep.cache.CacheService;
import com.ep.payment.api.PaymentChannel;
import com.ep.payment.api.PaymentRequest;
import com.ep.payment.api.PaymentResult;
import com.ep.payment.api.PaymentService;
import com.ep.payment.api.PaymentStatus;
import com.ep.payment.api.Refund;
import com.ep.payment.api.RefundStatus;
import io.seata.saga.engine.StateMachineEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit tests for {@link OrderServiceImpl}. Covers the Entity&harr;DTO
 * conversion, the order state machine integration, idempotency, pagination, and
 * - critically - the Dubbo {@link PaymentService} integration on both pay
 * (createPayment) and refund (refund) paths.
 * <p>
 * The {@link PaymentService} Dubbo reference is field-injected by the container
 * in production; here we set it via {@link ReflectionTestUtils} so the test
 * does not need a Spring context.
 * <p>
 * D-182: also verifies {@link CacheService} getOrLoad/evict integration.
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceImplTest {

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private PulsarEventPublisher eventPublisher;
    @Mock
    private PaymentService paymentService;
    @Mock
    private StateMachineEngine sagaEngine;
    @Mock
    private OutboxRepository outboxRepository;
    /** D-218: fail-closed authoritative pricing port (stubbed per test). */
    @Mock
    private com.ep.order.pricing.CoursePricingPort coursePricingPort;

    private CacheService cacheService;

    @InjectMocks
    private OrderServiceImpl orderService;

    private CreateOrderCmd sampleCmd() {
        return new CreateOrderCmd(100L, 200L, new BigDecimal("99.00"),
                "idem-" + System.nanoTime(), null, null);
    }

    /** Simulate JPA assigning the id on save. */
    private OrderModel captureAndAssignId(long assignedId) {
        ArgumentCaptor<OrderModel> captor = ArgumentCaptor.forClass(OrderModel.class);
        when(orderRepository.save(captor.capture())).thenAnswer(inv -> {
            OrderModel e = inv.getArgument(0);
            e.setOrderId(assignedId);
            return e;
        });
        return null;
    }

    /**
     * D-182: Create a CacheService mock that simulates a cache miss by invoking
     * the supplied loader. This keeps existing logic intact while allowing
     * cache-hit tests to override the stub.
     */
    private static CacheService cacheMiss() {
        CacheService cs = org.mockito.Mockito.mock(CacheService.class);
        // lenient: not every test exercises getOrLoad (create/pay/refund only evict).
        lenient().when(cs.getOrLoad(anyString(), anyString(), any(Supplier.class), anyLong(), anyBoolean()))
                .thenAnswer(inv -> {
                    Supplier<?> loader = inv.getArgument(2);
                    return loader.get();
                });
        return cs;
    }

    @BeforeEach
    void wireDubboReference() {
        // D-182: inject CacheService mock (cache-miss default) before each test.
        cacheService = cacheMiss();
        // @DubboReference field - inject the mock directly.
        ReflectionTestUtils.setField(orderService, "paymentService", paymentService);
        ReflectionTestUtils.setField(orderService, "cacheService", cacheService);
        // D-218: default stub — authoritative price matches the client amount
        // so existing amount assertions hold; individual tests override.
        lenient().when(coursePricingPort.authoritativePrice(org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(new BigDecimal("99.00"));
    }

    // ---------------- D-218: server-side amount verification ----------------

    @Test
    void createOrder_clientAmountOverriddenByAuthoritativePrice() {
        CreateOrderCmd cmd = sampleCmd(); // client claims 99.00
        when(coursePricingPort.authoritativePrice(200L)).thenReturn(new BigDecimal("59.00"));
        when(orderRepository.findByIdempotencyKey(cmd.idempotencyKey())).thenReturn(Optional.empty());
        captureAndAssignId(21L);

        OrderDto result = orderService.createOrder(cmd);

        assertEquals(new BigDecimal("59.00"), result.amount(),
                "order amount must be the server-resolved price, never the client value");
    }

    @Test
    void createOrder_pricingLookupFails_rejectedFailClosed() {
        CreateOrderCmd cmd = sampleCmd();
        when(coursePricingPort.authoritativePrice(200L))
                .thenThrow(new IllegalStateException("D-218 fail-closed: pricing lookup failed"));

        assertThrows(IllegalStateException.class, () -> orderService.createOrder(cmd));
        verify(orderRepository, never()).save(any());
    }

    @Test
    void listUserOrders_pageSizeCappedAt100() {
        // size=10^6 would scrape the full table; the cap must clamp it to 100.
        when(orderRepository.findByUserIdOrderByOrderIdAsc(
                org.mockito.ArgumentMatchers.eq(100L),
                org.mockito.ArgumentMatchers.any(org.springframework.data.domain.PageRequest.class)))
                .thenReturn(org.springframework.data.domain.Page.empty());
        orderService.listUserOrders(100L, 1, 1_000_000);
        org.mockito.ArgumentCaptor<org.springframework.data.domain.PageRequest> captor =
                org.mockito.ArgumentCaptor.forClass(org.springframework.data.domain.PageRequest.class);
        org.mockito.Mockito.verify(orderRepository)
                .findByUserIdOrderByOrderIdAsc(org.mockito.ArgumentMatchers.eq(100L), captor.capture());
        assertEquals(100, captor.getValue().getPageSize(), "page size must be capped at 100");
    }

    // ---------------- createOrder ----------------

    @Test
    void createOrder_success() {
        CreateOrderCmd cmd = sampleCmd();
        when(orderRepository.findByIdempotencyKey(cmd.idempotencyKey())).thenReturn(Optional.empty());
        captureAndAssignId(7L);

        OrderDto result = orderService.createOrder(cmd);

        assertNotNull(result);
        assertEquals(7L, result.orderId());
        assertEquals(100L, result.userId());
        assertEquals(200L, result.courseId());
        assertEquals(new BigDecimal("99.00"), result.amount());
        assertEquals(OrderStatus.PENDING.getCode(), result.status());
        assertEquals("COURSE", result.productType());
        assertEquals("default", result.tenantId());
        verify(orderRepository, times(1)).save(any(OrderModel.class));
        // D-182: create should evict the order cache entries.
        verify(cacheService).evict("order:detail", "7");
        verify(cacheService).evict("order:by-user-course", "100:200");
    }

    @Test
    void createOrder_nullUserId_throws() {
        CreateOrderCmd cmd = new CreateOrderCmd(null, 200L, new BigDecimal("99.00"),
                "idem-x", null, null);
        NullPointerException ex = assertThrows(NullPointerException.class,
                () -> orderService.createOrder(cmd));
        assertTrue(ex.getMessage().contains("userId"));
        verify(orderRepository, never()).save(any());
        verify(cacheService, never()).evict(anyString(), anyString());
    }

    @Test
    void createOrder_duplicateIdempotencyKey_throws() {
        CreateOrderCmd cmd = sampleCmd();
        OrderModel existing = new OrderModel();
        existing.setIdempotencyKey(cmd.idempotencyKey());
        when(orderRepository.findByIdempotencyKey(cmd.idempotencyKey())).thenReturn(Optional.of(existing));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> orderService.createOrder(cmd));
        assertTrue(ex.getMessage().contains("Duplicate idempotency key"));
        verify(orderRepository, never()).save(any());
        verify(cacheService, never()).evict(anyString(), anyString());
    }

    @Test
    void createOrderWithGlobalTx_success() {
        CreateOrderCmd cmd = sampleCmd();
        when(orderRepository.findByIdempotencyKey(cmd.idempotencyKey())).thenReturn(Optional.empty());
        captureAndAssignId(9L);

        OrderDto result = orderService.createOrderWithGlobalTx(cmd, 5);

        assertNotNull(result);
        assertEquals(9L, result.orderId());
        assertEquals(OrderStatus.PENDING.getCode(), result.status());
        verify(orderRepository, times(1)).save(any(OrderModel.class));
    }

    // ---------------- payOrder ----------------

    @Test
    void payOrder_success_withPaymentCall() {
        OrderModel order = new OrderModel();
        order.setOrderId(5L);
        order.setUserId(100L);
        order.setCourseId(200L);
        order.setAmount(new BigDecimal("99.00"));
        order.setStatus(OrderStatus.PENDING.getCode());
        order.setTenantId("default");
        order.setProductType("COURSE");
        when(orderRepository.findById(5L)).thenReturn(Optional.of(order));
        when(orderRepository.save(any(OrderModel.class))).thenAnswer(inv -> inv.getArgument(0));
        when(paymentService.createPayment(any(PaymentRequest.class)))
                .thenReturn(new PaymentResult("pay-1", PaymentStatus.SUCCESS, "ctx-1", Instant.now()));

        orderService.payOrder(5L, "WECHAT");

        assertEquals(OrderStatus.PAID.getCode(), order.getStatus());
        assertEquals("WECHAT", order.getPayMethod());
        // verify the Dubbo PaymentService was invoked with the resolved channel + amount
        ArgumentCaptor<PaymentRequest> captor = ArgumentCaptor.forClass(PaymentRequest.class);
        verify(paymentService, times(1)).createPayment(captor.capture());
        PaymentRequest req = captor.getValue();
        assertEquals("5", req.orderId());
        assertEquals(PaymentChannel.WECHAT, req.channel());
        assertEquals(new BigDecimal("99.00"), req.amount());
        // D-217: domain event saved to outbox (not published directly).
        verify(eventPublisher, never()).publish(any());
        ArgumentCaptor<OutboxEntity> outboxCaptor = ArgumentCaptor.forClass(OutboxEntity.class);
        verify(outboxRepository, times(1)).save(outboxCaptor.capture());
        OutboxEntity saved = outboxCaptor.getValue();
        assertEquals("Order", saved.getAggregateType());
        assertEquals("5", saved.getAggregateId());
        assertEquals("OrderPaidEvent", saved.getEventType());
        assertNotNull(saved.getEventId());
        assertNotNull(saved.getPayload());
        assertNotNull(saved.getCreatedAt());
        // D-182: pay should evict the order cache entries.
        verify(cacheService).evict("order:detail", "5");
        verify(cacheService).evict("order:by-user-course", "100:200");
    }

    @Test
    void payOrder_orderNotFound_throws() {
        when(orderRepository.findById(404L)).thenReturn(Optional.empty());

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> orderService.payOrder(404L, "WECHAT"));
        assertTrue(ex.getMessage().contains("Order not found"));
        verify(paymentService, never()).createPayment(any());
        verify(cacheService, never()).evict(anyString(), anyString());
    }

    @Test
    void payOrder_alreadyPaid_throws() {
        OrderModel order = new OrderModel();
        order.setOrderId(6L);
        order.setStatus(OrderStatus.PAID.getCode()); // already paid
        when(orderRepository.findById(6L)).thenReturn(Optional.of(order));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> orderService.payOrder(6L, "ALIPAY"));
        assertTrue(ex.getMessage().contains("PENDING"));
        verify(paymentService, never()).createPayment(any());
        verify(cacheService, never()).evict(anyString(), anyString());
    }

    // ---------------- refundOrder ----------------

    @Test
    void refundOrder_success_withPaymentRefund() {
        // FIX under test: refundOrder must invoke paymentService.refund()
        OrderModel order = new OrderModel();
        order.setOrderId(8L);
        order.setUserId(100L);
        order.setCourseId(200L);
        order.setAmount(new BigDecimal("50.00"));
        order.setStatus(OrderStatus.PAID.getCode());
        order.setTenantId("default");
        order.setProductType("COURSE");
        when(orderRepository.findById(8L)).thenReturn(Optional.of(order));
        when(orderRepository.save(any(OrderModel.class))).thenAnswer(inv -> inv.getArgument(0));
        Refund refund = new Refund("r-1", "pay-1", "8", "default",
                new BigDecimal("50.00"), RefundStatus.SUCCESS, "user request", Instant.now());
        when(paymentService.refund(eq("8"), any(BigDecimal.class), anyString())).thenReturn(refund);

        orderService.refundOrder(8L, "user request");

        assertEquals(OrderStatus.REFUNDED.getCode(), order.getStatus());
        // THE FIX: payment refund was actually called
        verify(paymentService, times(1)).refund(eq("8"), eq(new BigDecimal("50.00")), eq("user request"));
        // D-217: domain event saved to outbox (not published directly).
        verify(eventPublisher, never()).publish(any());
        verify(outboxRepository, times(1)).save(any(OutboxEntity.class));
        // D-182: refund should evict the order cache entries.
        verify(cacheService).evict("order:detail", "8");
        verify(cacheService).evict("order:by-user-course", "100:200");
    }

    @Test
    void refundOrder_notPaid_throws() {
        OrderModel order = new OrderModel();
        order.setOrderId(9L);
        order.setStatus(OrderStatus.PENDING.getCode()); // never paid
        when(orderRepository.findById(9L)).thenReturn(Optional.of(order));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> orderService.refundOrder(9L, "mistake"));
        assertTrue(ex.getMessage().contains("PAID"));
        verify(paymentService, never()).refund(anyString(), any(BigDecimal.class), anyString());
        verify(cacheService, never()).evict(anyString(), anyString());
    }

    // ---------------- getOrderById ----------------

    @Test
    void getOrderById_found() {
        OrderModel order = new OrderModel();
        order.setOrderId(11L);
        order.setUserId(100L);
        order.setCourseId(200L);
        order.setAmount(new BigDecimal("10.00"));
        order.setStatus(OrderStatus.PAID.getCode());
        order.setIdempotencyKey("idem-11");
        order.setCreatedAt(1L);
        order.setUpdatedAt(2L);
        when(orderRepository.findById(11L)).thenReturn(Optional.of(order));

        OrderDto result = orderService.getOrderById(11L);

        assertNotNull(result);
        assertEquals(11L, result.orderId());
        assertEquals(100L, result.userId());
        assertEquals(new BigDecimal("10.00"), result.amount());
        assertEquals(OrderStatus.PAID.getCode(), result.status());
        // D-182: getOrderById goes through cache with cacheEmpty=true (TTL 300s).
        verify(cacheService).getOrLoad(eq("order:detail"), eq("11"), any(Supplier.class), eq(300L), eq(true));
    }

    @Test
    void getOrderById_notFound_returnsNull() {
        when(orderRepository.findById(99L)).thenReturn(Optional.empty());

        OrderDto result = orderService.getOrderById(99L);

        assertNull(result);
        verify(cacheService).getOrLoad(eq("order:detail"), eq("99"), any(Supplier.class), eq(300L), eq(true));
    }

    // D-182: cache hit skips the repository lookup.
    @Test
    void getOrderById_cacheHit_skipsRepository() {
        // Simulate a cache hit: getOrLoad returns the order without invoking the loader.
        OrderDto cached = new OrderDto(11L, 100L, 200L, "default", "COURSE",
                new BigDecimal("10.00"), OrderStatus.PAID.getCode(), null,
                "idem-11", 1L, 2L);
        when(cacheService.getOrLoad(eq("order:detail"), eq("11"), any(Supplier.class), eq(300L), eq(true)))
                .thenReturn(cached);

        OrderDto result = orderService.getOrderById(11L);

        assertNotNull(result);
        assertEquals(11L, result.orderId());
        // Repository must NOT be called on a cache hit.
        verify(orderRepository, never()).findById(anyLong());
    }

    // ---------------- listUserOrders ----------------

    @Test
    void listUserOrders_pagination() {
        OrderModel a = new OrderModel();
        a.setOrderId(1L);
        a.setUserId(100L);
        a.setCourseId(200L);
        a.setAmount(new BigDecimal("1.00"));
        a.setStatus(OrderStatus.PENDING.getCode());
        a.setIdempotencyKey("k1");
        a.setCreatedAt(1L);
        a.setUpdatedAt(1L);
        OrderModel b = new OrderModel();
        b.setOrderId(2L);
        b.setUserId(100L);
        b.setCourseId(201L);
        b.setAmount(new BigDecimal("2.00"));
        b.setStatus(OrderStatus.PAID.getCode());
        b.setIdempotencyKey("k2");
        b.setCreatedAt(2L);
        b.setUpdatedAt(2L);
        org.springframework.data.domain.Page<OrderModel> page =
                new org.springframework.data.domain.PageImpl<>(List.of(a, b));
        when(orderRepository.findByUserIdOrderByOrderIdAsc(eq(100L), any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(page);

        List<OrderDto> result = orderService.listUserOrders(100L, 1, 10);

        assertEquals(2, result.size());
        assertEquals(1L, result.get(0).orderId());
        assertEquals(2L, result.get(1).orderId());
        assertEquals(OrderStatus.PENDING.getCode(), result.get(0).status());
        assertEquals(OrderStatus.PAID.getCode(), result.get(1).status());
    }
}
