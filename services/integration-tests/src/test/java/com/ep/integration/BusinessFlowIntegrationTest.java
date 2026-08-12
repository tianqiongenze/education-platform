package com.ep.integration;

import com.ep.integration.builder.TestDataBuilder;
import com.ep.integration.support.BusinessFakes.FakeCourseService;
import com.ep.integration.support.BusinessFakes.FakeOrderService;
import com.ep.integration.support.BusinessFakes.FakeUserService;
import com.ep.integration.support.IntegrationDtos.CourseStatus;
import com.ep.integration.support.IntegrationDtos.OrderStatus;
import com.ep.integration.support.IntegrationDtos.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
 * End-to-end business flow tests against the core-business contracts. These
 * tests exercise the real invariants (idempotency, state transitions,
 * optimistic locking) using in-memory fakes that share the same rules as the
 * deployed services.
 */
class BusinessFlowIntegrationTest {

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
    @DisplayName("user registration and freeze flow")
    void test_user_registration_and_login_flow() {
        var cmd = TestDataBuilder.buildUser("alice", "13900001111");
        var created = userService.createUser(cmd);

        assertNotNull(created.getUserId());
        assertEquals(UserStatus.ACTIVE.getCode(), created.getStatus());
        assertEquals("alice", created.getUsername());

        var fetched = userService.getUserById(created.getUserId());
        assertNotNull(fetched);
        assertEquals(created.getUserId(), fetched.getUserId());

        userService.freezeUser(created.getUserId(), "policy violation");
        var frozen = userService.getUserById(created.getUserId());
        assertNotNull(frozen);
        assertEquals(UserStatus.FROZEN.getCode(), frozen.getStatus());
    }

    @Test
    @DisplayName("course lifecycle: DRAFT -> PUBLISHED -> ARCHIVED")
    void test_course_lifecycle() {
        var course = courseService.createCourse(TestDataBuilder.buildCourse("Spring Boot 4", 1L));
        assertNotNull(course.getCourseId());
        assertEquals(CourseStatus.DRAFT.getCode(), course.getStatus());

        courseService.publishCourse(course.getCourseId());
        var published = courseService.getCourseById(course.getCourseId());
        assertNotNull(published);
        assertEquals(CourseStatus.PUBLISHED.getCode(), published.getStatus());

        courseService.archiveCourse(course.getCourseId());
        var archived = courseService.getCourseById(course.getCourseId());
        assertNotNull(archived);
        assertEquals(CourseStatus.ARCHIVED.getCode(), archived.getStatus());
    }

    @Test
    @DisplayName("order flow: PENDING -> PAID -> REFUNDED")
    void test_order_complete_flow() {
        var user = userService.createUser(TestDataBuilder.buildUser("bob", "13900002222"));
        var course = courseService.createCourse(TestDataBuilder.buildCourse("Course B", 1L));
        var order = orderService.createOrder(
                TestDataBuilder.buildOrder(user.getUserId(), course.getCourseId(), "idem-order-1"));

        assertNotNull(order.getOrderId());
        assertEquals(OrderStatus.PENDING.getCode(), order.getStatus());

        orderService.payOrder(order.getOrderId(), "ALIPAY");
        var paid = orderService.getOrderById(order.getOrderId());
        assertNotNull(paid);
        assertEquals(OrderStatus.PAID.getCode(), paid.getStatus());
        assertEquals("ALIPAY", paid.getPayMethod());

        orderService.refundOrder(order.getOrderId(), "user request");
        var refunded = orderService.getOrderById(order.getOrderId());
        assertNotNull(refunded);
        assertEquals(OrderStatus.REFUNDED.getCode(), refunded.getStatus());
    }

    @Test
    @DisplayName("order idempotency: duplicate key is rejected with IllegalStateException")
    void test_order_idempotency() {
        var user = userService.createUser(TestDataBuilder.buildUser("carol", "13900003333"));
        var course = courseService.createCourse(TestDataBuilder.buildCourse("Course C", 2L));
        String key = "idem-order-dup";
        var first = orderService.createOrder(
                TestDataBuilder.buildOrder(user.getUserId(), course.getCourseId(), key));
        assertNotNull(first);

        // Production OrderServiceImpl.createOrder throws on a duplicate
        // idempotency key; the fake mirrors that behaviour.
        assertThrows(IllegalStateException.class,
                () -> orderService.createOrder(
                        TestDataBuilder.buildOrder(user.getUserId(), course.getCourseId(), key)));
    }

    @Test
    @DisplayName("order optimistic lock: concurrent payment attempts, only one succeeds")
    void test_order_optimistic_lock() throws Exception {
        var user = userService.createUser(TestDataBuilder.buildUser("dave", "13900004444"));
        var course = courseService.createCourse(TestDataBuilder.buildCourse("Course D", 3L));
        var order = orderService.createOrder(
                TestDataBuilder.buildOrder(user.getUserId(), course.getCourseId(), "idem-concur"));

        int threads = 8;
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
                    // With payOrder now matching the production signature
                    // (no expectedVersion), losers are rejected by the
                    // PENDING-state guard rather than a version-mismatch check.
                    if (e.getMessage() != null && e.getMessage().contains("not in PENDING")) {
                        rejections.incrementAndGet();
                    } else {
                        firstError.compareAndSet(null, e);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Throwable t) {
                    firstError.compareAndSet(null, t);
                }
                return null;
            });
        }

        ready.await();
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        if (firstError.get() != null) {
            throw new AssertionError("unexpected error during concurrent payment", firstError.get());
        }
        assertEquals(1, successes.get(), "exactly one payment should succeed");
        assertEquals(threads - 1, rejections.get(), "all other attempts must be rejected");
        var finalOrder = orderService.getOrderById(order.getOrderId());
        assertNotNull(finalOrder);
        assertEquals(OrderStatus.PAID.getCode(), finalOrder.getStatus());
    }

    @Test
    @DisplayName("order cannot be refunded directly from PENDING")
    void test_order_state_transition_guard() {
        var order = orderService.createOrder(TestDataBuilder.buildOrder(1L, 1L, "idem-guard"));
        assertEquals(OrderStatus.PENDING.getCode(), order.getStatus());
        assertThrows(IllegalStateException.class,
                () -> orderService.refundOrder(order.getOrderId(), "skip pay"));
    }
}
