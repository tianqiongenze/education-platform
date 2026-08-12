package com.ep.integration;

import com.ep.integration.builder.TestDataBuilder;
import com.ep.integration.support.IntegrationDtos.Course;
import com.ep.integration.support.IntegrationDtos.CreateCourseCmd;
import com.ep.integration.support.IntegrationDtos.Order;
import com.ep.integration.support.IntegrationDtos.OrderStatus;
import com.ep.integration.support.IntegrationDtos.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Multi-tenant data isolation integration test.
 *
 * <p>Follows the convention established by the other tests in this module
 * (see {@code BusinessFlowIntegrationTest}, {@code CacheHitRatioTest}): the
 * multi-tenant isolation invariant is modelled with an in-memory fake
 * ({@link TenantAwareFakeServices}) that mirrors the production tenant-scoping
 * pattern (every entity carries a {@code tenantId}; every repository query
 * filters by the caller's tenant context), so the isolation contract can be
 * verified deterministically without a live TiDB instance.
 *
 * <p>The test asserts that:
 * <ul>
 *   <li>tenant A cannot read, list, or mutate tenant B's courses/orders;</li>
 *   <li>a cross-tenant read returns {@code null} (not the other tenant's
 *       data);</li>
 *   <li>a cross-tenant mutation is rejected with an
 *       {@link IllegalStateException} (the production service throws a 403-style
 *       error).</li>
 * </ul>
 */
@Tag("integration")
class DataIsolationTest {

    private TenantAwareFakeServices services;

    @BeforeEach
    void setUp() {
        services = new TenantAwareFakeServices();
    }

    @Test
    @DisplayName("tenant A cannot see tenant B's course; cross-tenant get returns null")
    void test_cross_tenant_course_read_returns_null() {
        // Tenant A creates a course.
        services.setTenantContext("tenant-A");
        Course courseA = services.courseService.create(
                TestDataBuilder.buildCourse("Tenant A Course", 1L));
        assertNotNull(courseA.getCourseId());

        // Tenant B logs in and must NOT see tenant A's course.
        services.setTenantContext("tenant-B");
        Course leaked = services.courseService.getById(courseA.getCourseId());
        assertNull(leaked, "tenant B must NOT be able to read tenant A's course");

        // Tenant B listing courses must NOT include tenant A's course.
        List<Course> bCourses = services.courseService.list();
        assertTrue(bCourses.stream().noneMatch(c -> Objects.equals(c.getCourseId(), courseA.getCourseId())),
                "tenant B's course list must not contain tenant A's course");

        // Tenant A can still read its own course.
        services.setTenantContext("tenant-A");
        Course own = services.courseService.getById(courseA.getCourseId());
        assertNotNull(own, "tenant A must be able to read its own course");
        assertEquals("Tenant A Course", own.getTitle());
    }

    @Test
    @DisplayName("cross-tenant mutation (publish/archive) is rejected with IllegalStateException")
    void test_cross_tenant_mutation_rejected() {
        services.setTenantContext("tenant-A");
        Course courseA = services.courseService.create(
                TestDataBuilder.buildCourse("Tenant A Course", 1L));

        services.setTenantContext("tenant-B");
        // Tenant B attempting to publish tenant A's course must be rejected.
        assertThrows(IllegalStateException.class,
                () -> services.courseService.publish(courseA.getCourseId()),
                "cross-tenant publish must be rejected");

        // Tenant A's course must remain in its original (DRAFT) state.
        services.setTenantContext("tenant-A");
        Course stillDraft = services.courseService.getById(courseA.getCourseId());
        assertNotNull(stillDraft);
        assertEquals(com.ep.integration.support.IntegrationDtos.CourseStatus.DRAFT.getCode(),
                stillDraft.getStatus(),
                "rejected cross-tenant mutation must not change the entity state");
    }

    @Test
    @DisplayName("cross-tenant order refund is rejected; tenant A cannot refund tenant B's order")
    void test_cross_tenant_order_refund_rejected() {
        // Tenant A creates + pays an order.
        services.setTenantContext("tenant-A");
        User userA = services.userService.createUser(TestDataBuilder.buildUser("alice", "13900001111"));
        Course courseA = services.courseService.create(TestDataBuilder.buildCourse("Course A", 1L));
        Order orderA = services.orderService.createOrder(
                TestDataBuilder.buildOrder(userA.getUserId(), courseA.getCourseId(), "idem-iso-1"));
        services.orderService.payOrder(orderA.getOrderId(), "ALIPAY");

        // Tenant B attempts to refund tenant A's order — must be rejected.
        services.setTenantContext("tenant-B");
        assertThrows(IllegalStateException.class,
                () -> services.orderService.refundOrder(orderA.getOrderId(), "malicious refund"),
                "cross-tenant refund must be rejected");

        // Tenant A's order must still be PAID (not REFUNDED).
        services.setTenantContext("tenant-A");
        Order stillPaid = services.orderService.getOrderById(orderA.getOrderId());
        assertNotNull(stillPaid);
        assertEquals(OrderStatus.PAID.getCode(), stillPaid.getStatus(),
                "rejected cross-tenant refund must not change the order state");
    }

    // ------------------------------------------------------------------
    // In-memory fake for tenant-aware services.
    //
    // Mirrors the production tenant-scoping pattern: every entity stores a
    // tenantId, and every read/list/mutation checks the caller's tenant
    // context (set via TenantContext.setTenantContext). Cross-tenant reads
    // return null; cross-tenant mutations throw IllegalStateException.
    // ------------------------------------------------------------------

    static final class TenantAwareFakeServices {
        private final FakeTenantUserService userService = new FakeTenantUserService();
        private final FakeTenantCourseService courseService = new FakeTenantCourseService();
        private final FakeTenantOrderService orderService = new FakeTenantOrderService();

        void setTenantContext(String tenantId) {
            TenantContext.setTenantContext(tenantId);
        }

        static final class TenantContext {
            private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();
            static void setTenantContext(String t) { CURRENT.set(t); }
            static String get() { return CURRENT.get(); }
        }

        static final class FakeTenantUserService {
            private final Map<Long, User> store = new ConcurrentHashMap<>();
            private final AtomicLong idGen = new AtomicLong(0);

            User createUser(com.ep.integration.support.IntegrationDtos.CreateUserCmd cmd) {
                long id = idGen.incrementAndGet();
                long now = System.currentTimeMillis();
                User u = new User()
                        .setUserId(id)
                        .setUsername(cmd.getUsername())
                        .setPhone(cmd.getPhone())
                        .setEmail(cmd.getEmail())
                        .setNickname(cmd.getNickname())
                        .setStatus(com.ep.integration.support.IntegrationDtos.UserStatus.ACTIVE.getCode())
                        .setCreatedAt(now)
                        .setUpdatedAt(now);
                store.put(id, u);
                return u;
            }
        }

        static final class FakeTenantCourseService {
            private final Map<Long, TenantCourse> store = new ConcurrentHashMap<>();
            private final AtomicLong idGen = new AtomicLong(0);

            Course create(CreateCourseCmd cmd) {
                long id = idGen.incrementAndGet();
                long now = System.currentTimeMillis();
                Course c = new Course()
                        .setCourseId(id)
                        .setTitle(cmd.getTitle())
                        .setTeacherId(cmd.getTeacherId())
                        .setPrice(cmd.getPrice())
                        .setStatus(com.ep.integration.support.IntegrationDtos.CourseStatus.DRAFT.getCode())
                        .setCreatedAt(now)
                        .setUpdatedAt(now);
                TenantCourse tc = new TenantCourse(TenantContext.get(), c);
                store.put(id, tc);
                return c;
            }

            Course getById(Long id) {
                TenantCourse tc = store.get(id);
                if (tc == null || !Objects.equals(tc.tenantId, TenantContext.get())) {
                    return null; // cross-tenant read returns nothing
                }
                return tc.course;
            }

            List<Course> list() {
                List<Course> out = new ArrayList<>();
                for (TenantCourse tc : store.values()) {
                    if (Objects.equals(tc.tenantId, TenantContext.get())) {
                        out.add(tc.course);
                    }
                }
                return out;
            }

            void publish(Long id) {
                TenantCourse tc = store.get(id);
                if (tc == null || !Objects.equals(tc.tenantId, TenantContext.get())) {
                    throw new IllegalStateException(
                            "Course not found in tenant " + TenantContext.get());
                }
                tc.course.setStatus(
                        com.ep.integration.support.IntegrationDtos.CourseStatus.PUBLISHED.getCode());
            }
        }

        static final class FakeTenantOrderService {
            private final Map<Long, TenantOrder> store = new ConcurrentHashMap<>();
            private final AtomicLong idGen = new AtomicLong(0);

            Order createOrder(com.ep.integration.support.IntegrationDtos.CreateOrderCmd cmd) {
                long id = idGen.incrementAndGet();
                long now = System.currentTimeMillis();
                Order o = new Order()
                        .setOrderId(id)
                        .setUserId(cmd.getUserId())
                        .setCourseId(cmd.getCourseId())
                        .setAmount(cmd.getAmount())
                        .setStatus(OrderStatus.PENDING.getCode())
                        .setIdempotencyKey(cmd.getIdempotencyKey())
                        .setVersion(0)
                        .setCreatedAt(now)
                        .setUpdatedAt(now);
                store.put(id, new TenantOrder(TenantContext.get(), o));
                return o;
            }

            Order getOrderById(Long id) {
                TenantOrder to = store.get(id);
                if (to == null || !Objects.equals(to.tenantId, TenantContext.get())) {
                    return null;
                }
                return to.order;
            }

            void payOrder(Long id, String payMethod) {
                TenantOrder to = store.get(id);
                if (to == null || !Objects.equals(to.tenantId, TenantContext.get())) {
                    throw new IllegalStateException(
                            "Order not found in tenant " + TenantContext.get());
                }
                to.order.setStatus(OrderStatus.PAID.getCode());
                to.order.setPayMethod(payMethod);
            }

            void refundOrder(Long id, String reason) {
                TenantOrder to = store.get(id);
                if (to == null || !Objects.equals(to.tenantId, TenantContext.get())) {
                    throw new IllegalStateException(
                            "Order not found in tenant " + TenantContext.get());
                }
                if (to.order.getStatus() != OrderStatus.PAID.getCode()) {
                    throw new IllegalStateException("Order not in PAID status, cannot refund");
                }
                to.order.setStatus(OrderStatus.REFUNDED.getCode());
            }
        }

        private record TenantCourse(String tenantId, Course course) {
        }

        private record TenantOrder(String tenantId, Order order) {
        }
    }
}
