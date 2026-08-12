package com.ep.integration.support;

import com.ep.integration.support.IntegrationDtos.AssignmentStatus;
import com.ep.integration.support.IntegrationDtos.CourseStatus;
import com.ep.integration.support.IntegrationDtos.OrderStatus;
import com.ep.integration.support.IntegrationDtos.UserStatus;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory fake implementations that faithfully reproduce the behaviour of the
 * core-business services (User / Course / Order). They keep the same invariants:
 *
 *  - idempotency key dedup for orders (production throws IllegalStateException
 *    on a duplicate key, so do the fakes — see {@link FakeOrderService#createOrder})
 *  - explicit state-transition guards (PENDING -> PAID -> REFUNDED)
 *  - optimistic-lock version tracking on Order to model ADR-T06 patterns
 *
 * <p>Behavioural alignment with the production service interfaces
 * ({@code com.ep.business.user.UserService}, {@code com.ep.business.course.CourseService},
 * {@code com.ep.business.order.OrderService}) is maintained at the signature and
 * invariant level: method names, parameter lists, return void-ness and thrown
 * exceptions mirror the production contracts. The fakes operate on
 * {@link IntegrationDtos} test POJOs rather than the JPA {@code @Entity} types
 * declared in the production interfaces, so they do not literally
 * {@code implements} those interfaces — this keeps the test module free of JPA
 * persistence concerns while still exercising the same business rules. Where a
 * production method returns an entity, the fake returns the corresponding
 * IntegrationDtos shape with the same fields.
 *
 * <p>The fakes are intentionally lightweight (no Spring/Dubbo wiring) so the
 * integration tests can run without booting an application context.
 */
public final class BusinessFakes {

    public static final class FakeUserService {
        private final Map<Long, IntegrationDtos.User> userById = new ConcurrentHashMap<>();
        private final Map<String, IntegrationDtos.User> userByPhone = new ConcurrentHashMap<>();
        private final AtomicLong idGen = new AtomicLong(0);

        public IntegrationDtos.User createUser(IntegrationDtos.CreateUserCmd cmd) {
            Objects.requireNonNull(cmd, "cmd");
            requireNonBlank(cmd.getUsername(), "username");
            requireNonBlank(cmd.getPhone(), "phone");
            requireNonBlank(cmd.getPassword(), "password");
            synchronized (userByPhone) {
                if (userByPhone.containsKey(cmd.getPhone())) {
                    throw new IllegalStateException("Duplicate phone: " + cmd.getPhone());
                }
                long id = idGen.incrementAndGet();
                long now = System.currentTimeMillis();
                IntegrationDtos.User u = new IntegrationDtos.User()
                        .setUserId(id)
                        .setUsername(cmd.getUsername())
                        .setPhone(cmd.getPhone())
                        .setEmail(cmd.getEmail())
                        .setNickname(cmd.getNickname() == null ? cmd.getUsername() : cmd.getNickname())
                        .setStatus(UserStatus.ACTIVE.getCode())
                        .setCreatedAt(now)
                        .setUpdatedAt(now);
                userById.put(id, u);
                userByPhone.put(cmd.getPhone(), u);
                return copy(u);
            }
        }

        public IntegrationDtos.User getUserById(Long userId) {
            IntegrationDtos.User u = userById.get(userId);
            return u == null ? null : copy(u);
        }

        public IntegrationDtos.User getUserByPhone(String phone) {
            requireNonBlank(phone, "phone");
            IntegrationDtos.User u = userByPhone.get(phone);
            return u == null ? null : copy(u);
        }

        /**
         * Mirrors {@code UserService.listUsers()}: returns all stored users in
         * insertion order (by ascending userId).
         */
        public List<IntegrationDtos.User> listUsers() {
            List<IntegrationDtos.User> all = new ArrayList<>(userById.values());
            all.sort(Comparator.comparingLong(IntegrationDtos.User::getUserId));
            List<IntegrationDtos.User> copies = new ArrayList<>(all.size());
            for (IntegrationDtos.User u : all) {
                copies.add(copy(u));
            }
            return copies;
        }

        /**
         * Mirrors {@code UserService.updateUser(UpdateUserCmd cmd)}: updates the
         * mutable profile fields (nickname / email) on the stored user.
         */
        public void updateUser(IntegrationDtos.UpdateUserCmd cmd) {
            Objects.requireNonNull(cmd, "cmd");
            Objects.requireNonNull(cmd.getUserId(), "userId");
            IntegrationDtos.User u = userById.get(cmd.getUserId());
            if (u == null) throw new IllegalStateException("User not found: " + cmd.getUserId());
            synchronized (u) {
                if (cmd.getNickname() != null) u.setNickname(cmd.getNickname());
                if (cmd.getEmail() != null) u.setEmail(cmd.getEmail());
                u.setUpdatedAt(System.currentTimeMillis());
            }
        }

        public void freezeUser(Long userId, String reason) {
            requireNonBlank(reason, "reason");
            IntegrationDtos.User u = userById.get(userId);
            if (u == null) throw new IllegalStateException("User not found: " + userId);
            synchronized (u) {
                u.setStatus(UserStatus.FROZEN.getCode());
                u.setUpdatedAt(System.currentTimeMillis());
            }
        }
    }

    public static final class FakeCourseService {
        private final Map<Long, IntegrationDtos.Course> store = new ConcurrentHashMap<>();
        private final AtomicLong idGen = new AtomicLong(0);

        public IntegrationDtos.Course createCourse(IntegrationDtos.CreateCourseCmd cmd) {
            Objects.requireNonNull(cmd, "cmd");
            requireNonBlank(cmd.getTitle(), "title");
            Objects.requireNonNull(cmd.getTeacherId(), "teacherId");
            long id = idGen.incrementAndGet();
            long now = System.currentTimeMillis();
            IntegrationDtos.Course c = new IntegrationDtos.Course()
                    .setCourseId(id)
                    .setTitle(cmd.getTitle())
                    .setTeacherId(cmd.getTeacherId())
                    .setPrice(cmd.getPrice() == null ? BigDecimal.ZERO : cmd.getPrice())
                    .setStatus(CourseStatus.DRAFT.getCode())
                    .setCreatedAt(now)
                    .setUpdatedAt(now);
            store.put(id, c);
            return c;
        }

        public IntegrationDtos.Course getCourseById(Long courseId) {
            IntegrationDtos.Course c = store.get(courseId);
            return c == null ? null : c;
        }

        public void publishCourse(Long courseId) {
            IntegrationDtos.Course c = store.get(courseId);
            if (c == null) throw new IllegalStateException("Course not found: " + courseId);
            synchronized (c) {
                c.setStatus(CourseStatus.PUBLISHED.getCode());
                c.setUpdatedAt(System.currentTimeMillis());
            }
        }

        public void archiveCourse(Long courseId) {
            IntegrationDtos.Course c = store.get(courseId);
            if (c == null) throw new IllegalStateException("Course not found: " + courseId);
            synchronized (c) {
                c.setStatus(CourseStatus.ARCHIVED.getCode());
                c.setUpdatedAt(System.currentTimeMillis());
            }
        }
    }

    public static final class FakeOrderService {
        private final Map<Long, IntegrationDtos.Order> orderById = new ConcurrentHashMap<>();
        private final Map<String, IntegrationDtos.Order> orderByKey = new ConcurrentHashMap<>();
        private final AtomicLong idGen = new AtomicLong(0);

        public IntegrationDtos.Order createOrder(IntegrationDtos.CreateOrderCmd cmd) {
            Objects.requireNonNull(cmd, "cmd");
            Objects.requireNonNull(cmd.getUserId(), "userId");
            Objects.requireNonNull(cmd.getCourseId(), "courseId");
            Objects.requireNonNull(cmd.getAmount(), "amount");
            requireNonBlank(cmd.getIdempotencyKey(), "idempotencyKey");
            synchronized (orderByKey) {
                // Mirrors OrderServiceImpl.createOrder which throws on a duplicate
                // idempotency key rather than returning the existing order.
                if (orderByKey.containsKey(cmd.getIdempotencyKey())) {
                    throw new IllegalStateException(
                            "Duplicate idempotency key: " + cmd.getIdempotencyKey());
                }
                long id = idGen.incrementAndGet();
                long now = System.currentTimeMillis();
                IntegrationDtos.Order o = new IntegrationDtos.Order()
                        .setOrderId(id)
                        .setUserId(cmd.getUserId())
                        .setCourseId(cmd.getCourseId())
                        .setAmount(cmd.getAmount())
                        .setStatus(OrderStatus.PENDING.getCode())
                        .setIdempotencyKey(cmd.getIdempotencyKey())
                        .setVersion(0)
                        .setCreatedAt(now)
                        .setUpdatedAt(now);
                orderById.put(id, o);
                orderByKey.put(cmd.getIdempotencyKey(), o);
                return copy(o);
            }
        }

        public IntegrationDtos.Order getOrderById(Long orderId) {
            IntegrationDtos.Order o = orderById.get(orderId);
            return o == null ? null : copy(o);
        }

        /** Compensating action used by Seata-rollback simulation tests. */
        public void remove(Long orderId) {
            if (orderId == null) return;
            IntegrationDtos.Order o = orderById.remove(orderId);
            if (o != null && o.getIdempotencyKey() != null) {
                orderByKey.remove(o.getIdempotencyKey());
            }
        }

        /**
         * Mirrors {@code OrderService.payOrder(Long orderId, String payMethod)}:
         * returns void, no {@code expectedVersion} parameter. The internal
         * optimistic-lock version is still bumped (so callers can observe it via
         * {@link #getOrderById(Long)}) to preserve the ADR-T06 lock semantics
         * modelled by the fake.
         */
        public void payOrder(Long orderId, String payMethod) {
            requireNonBlank(payMethod, "payMethod");
            IntegrationDtos.Order o = orderById.get(orderId);
            if (o == null) throw new IllegalStateException("Order not found: " + orderId);
            synchronized (o) {
                if (o.getStatus() != OrderStatus.PENDING.getCode()) {
                    throw new IllegalStateException(
                            "Order not in PENDING status, cannot pay. status=" + o.getStatus());
                }
                o.setStatus(OrderStatus.PAID.getCode());
                o.setPayMethod(payMethod);
                o.setVersion(o.getVersion() + 1);
                o.setUpdatedAt(System.currentTimeMillis());
            }
        }

        public void refundOrder(Long orderId, String reason) {
            requireNonBlank(reason, "reason");
            IntegrationDtos.Order o = orderById.get(orderId);
            if (o == null) throw new IllegalStateException("Order not found: " + orderId);
            synchronized (o) {
                if (o.getStatus() != OrderStatus.PAID.getCode()) {
                    throw new IllegalStateException(
                            "Order not in PAID status, cannot refund. status=" + o.getStatus());
                }
                o.setStatus(OrderStatus.REFUNDED.getCode());
                o.setUpdatedAt(System.currentTimeMillis());
            }
        }
    }

    // ---------- helpers ----------

    static IntegrationDtos.User copy(IntegrationDtos.User u) {
        return new IntegrationDtos.User()
                .setUserId(u.getUserId())
                .setUsername(u.getUsername())
                .setPhone(u.getPhone())
                .setEmail(u.getEmail())
                .setNickname(u.getNickname())
                .setStatus(u.getStatus())
                .setCreatedAt(u.getCreatedAt())
                .setUpdatedAt(u.getUpdatedAt());
    }

    static IntegrationDtos.Order copy(IntegrationDtos.Order o) {
        return new IntegrationDtos.Order()
                .setOrderId(o.getOrderId())
                .setUserId(o.getUserId())
                .setCourseId(o.getCourseId())
                .setAmount(o.getAmount())
                .setStatus(o.getStatus())
                .setPayMethod(o.getPayMethod())
                .setIdempotencyKey(o.getIdempotencyKey())
                .setVersion(o.getVersion())
                .setCreatedAt(o.getCreatedAt())
                .setUpdatedAt(o.getUpdatedAt());
    }

    static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
