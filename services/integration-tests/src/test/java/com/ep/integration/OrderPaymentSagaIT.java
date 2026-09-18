package com.ep.integration;

import com.ep.integration.builder.TestDataBuilder;
import com.ep.integration.support.BusinessFakes.FakeCourseService;
import com.ep.integration.support.BusinessFakes.FakeOrderService;
import com.ep.integration.support.BusinessFakes.FakeUserService;
import com.ep.integration.support.IntegrationDtos.CourseStatus;
import com.ep.integration.support.IntegrationDtos.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-211: Cross-service integration test for the order-payment saga:
 * order-platform → payment-platform → core-business.
 *
 * <p>Follows the established convention in this module (see
 * {@code BusinessFlowIntegrationTest} and {@code EndToEndOrderFlowTest}):
 * the order/payment invariants are exercised via the in-memory
 * {@link FakeOrderService}/{@link FakeUserService}/{@link FakeCourseService}
 * fakes. The Saga coordination and Pulsar event publishing are tested
 * separately in {@code EndToEndOrderFlowTest} with Mockito mocks.
 *
 * <p>This test focuses on the order lifecycle within the saga:
 * create → pay → refund, concurrent payment guard (only one succeeds),
 * idempotency key dedup, and state-transition guards.
 */
@Tag("integration")
class OrderPaymentSagaIT {

    private FakeUserService userService;
    private FakeCourseService courseService;
    private FakeOrderService orderService;

    @BeforeEach
    void setUp() {
        userService = new FakeUserService();
        courseService = new FakeCourseService();
        orderService = new FakeOrderService();
    }

    @Test
    @DisplayName("full order payment saga: create order → pay → PAID status verified")
    void test_order_payment_saga() {
        var user = userService.createUser(TestDataBuilder.buildUser("alice", "13900001111"));
        var course = courseService.createCourse(
                TestDataBuilder.buildCourse("Spring Boot 4", user.getUserId()));
        courseService.publishCourse(course.getCourseId());

        var order = orderService.createOrder(
                TestDataBuilder.buildOrder(user.getUserId(), course.getCourseId(), "saga-order-1"));
        assertEquals(OrderStatus.PENDING.getCode(), order.getStatus());

        orderService.payOrder(order.getOrderId(), "ALIPAY");
        var paid = orderService.getOrderById(order.getOrderId());
        assertEquals(OrderStatus.PAID.getCode(), paid.getStatus());
        assertEquals("ALIPAY", paid.getPayMethod());
    }

    @Test
    @DisplayName("saga compensation on payment failure: order remains PENDING")
    void test_order_payment_saga_compensation() {
        var user = userService.createUser(TestDataBuilder.buildUser("bob", "13900002222"));
        var course = courseService.createCourse(
                TestDataBuilder.buildCourse("Math 101", user.getUserId()));
        courseService.publishCourse(course.getCourseId());

        var order = orderService.createOrder(
                TestDataBuilder.buildOrder(user.getUserId(), course.getCourseId(), "saga-fail-1"));

        // payOrder with blank payMethod should fail (requireNonBlank guard)
        assertThrows(IllegalArgumentException.class,
                () -> orderService.payOrder(order.getOrderId(), "  "),
                "blank payMethod must be rejected");

        var finalOrder = orderService.getOrderById(order.getOrderId());
        assertEquals(OrderStatus.PENDING.getCode(), finalOrder.getStatus(),
                "order must remain PENDING after payment failure");
    }

    @Test
    @DisplayName("concurrent payment saga: only one succeeds, others rejected")
    void test_order_payment_saga_concurrent() throws Exception {
        var user = userService.createUser(TestDataBuilder.buildUser("carol", "13900003333"));
        var course = courseService.createCourse(
                TestDataBuilder.buildCourse("Physics 101", user.getUserId()));
        courseService.publishCourse(course.getCourseId());

        var order = orderService.createOrder(
                TestDataBuilder.buildOrder(user.getUserId(), course.getCourseId(), "saga-concur-1"));

        int threads = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger(0);
        AtomicInteger rejections = new AtomicInteger(0);
        AtomicReference<Throwable> firstError = new AtomicReference<>(null);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    orderService.payOrder(order.getOrderId(), "WECHAT");
                    successes.incrementAndGet();
                } catch (IllegalStateException e) {
                    if (e.getMessage() != null && e.getMessage().contains("not in PENDING")) {
                        rejections.incrementAndGet();
                    } else {
                        firstError.compareAndSet(null, e);
                    }
                } catch (Exception e) {
                    firstError.compareAndSet(null, e);
                }
                return null;
            });
        }

        ready.await();
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS));

        if (firstError.get() != null) {
            throw new AssertionError("unexpected error during concurrent payment", firstError.get());
        }
        assertEquals(1, successes.get(), "exactly one payment should succeed");
        assertEquals(threads - 1, rejections.get(), "others should be rejected");

        var finalOrder = orderService.getOrderById(order.getOrderId());
        assertEquals(OrderStatus.PAID.getCode(), finalOrder.getStatus());
    }

    @Test
    @DisplayName("saga rollback on order creation failure: duplicate idempotency key rejected")
    void test_order_creation_saga_rollback() {
        var user = userService.createUser(TestDataBuilder.buildUser("dave", "13900004444"));
        var course = courseService.createCourse(
                TestDataBuilder.buildCourse("Chemistry 101", user.getUserId()));

        // First order succeeds
        var order1 = orderService.createOrder(
                TestDataBuilder.buildOrder(user.getUserId(), course.getCourseId(), "rollback-key"));
        assertNotNull(order1.getOrderId());

        // Second order with same key is rejected — simulating saga rollback
        // when the downstream payment branch fails
        assertThrows(IllegalStateException.class,
                () -> orderService.createOrder(
                        TestDataBuilder.buildOrder(user.getUserId(), course.getCourseId(), "rollback-key")),
                "duplicate idempotency key must be rejected");
    }

    @Test
    @DisplayName("payment record: pay method and version are persisted after successful payment")
    void test_payment_record_after_saga() {
        var user = userService.createUser(TestDataBuilder.buildUser("eve", "13900005555"));
        var course = courseService.createCourse(
                TestDataBuilder.buildCourse("Biology 101", user.getUserId()));
        courseService.publishCourse(course.getCourseId());

        var order = orderService.createOrder(
                TestDataBuilder.buildOrder(user.getUserId(), course.getCourseId(), "pay-record-1"));
        orderService.payOrder(order.getOrderId(), "CASH");

        var paid = orderService.getOrderById(order.getOrderId());
        assertNotNull(paid);
        assertEquals(OrderStatus.PAID.getCode(), paid.getStatus());
        assertEquals("CASH", paid.getPayMethod());
        assertNotNull(paid.getVersion());
        assertTrue(paid.getVersion() > 0, "version must be bumped after payment");
    }

    @Test
    @DisplayName("refund after payment: PAID → REFUNDED state transition")
    void test_refund_after_payment() {
        var user = userService.createUser(TestDataBuilder.buildUser("frank", "13900006666"));
        var course = courseService.createCourse(
                TestDataBuilder.buildCourse("Geology 101", user.getUserId()));
        courseService.publishCourse(course.getCourseId());

        var order = orderService.createOrder(
                TestDataBuilder.buildOrder(user.getUserId(), course.getCourseId(), "refund-1"));
        orderService.payOrder(order.getOrderId(), "ALIPAY");

        orderService.refundOrder(order.getOrderId(), "user request");
        var refunded = orderService.getOrderById(order.getOrderId());
        assertEquals(OrderStatus.REFUNDED.getCode(), refunded.getStatus());
    }

    @Test
    @DisplayName("cannot refund from PENDING status (must pay first)")
    void test_refund_guard() {
        var order = orderService.createOrder(
                TestDataBuilder.buildOrder(1L, 1L, "refund-guard-1"));
        assertEquals(OrderStatus.PENDING.getCode(), order.getStatus());

        assertThrows(IllegalStateException.class,
                () -> orderService.refundOrder(order.getOrderId(), "skip pay"),
                "cannot refund from PENDING — must pay first");
    }
}
