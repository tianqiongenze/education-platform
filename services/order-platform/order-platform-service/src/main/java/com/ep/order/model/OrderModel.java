package com.ep.order.model;

import com.ep.order.api.OrderStatus;
import com.ep.order.event.DomainEvent;
import com.ep.order.event.OrderPaidEvent;
import com.ep.order.event.OrderRefundedEvent;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * JPA aggregate root for {@code ep_order}.
 * <p>
 * Adapted from {@code com.ep.business.order.Order} for the independent
 * SP-03 Order middle platform. Preserves the schema ({@code ep_order} table,
 * identical column names) and the domain behaviour (pay/refund state machine
 * + {@code pullEvents()}). The API-facing DTO is {@link com.ep.order.api.OrderDto};
 * this entity never crosses the Dubbo boundary.
 * <p>
 * D-039: the {@code events} list is accessed only from the owning thread of a
 * transaction (record inside pay/refund, pulled post-commit by the application
 * service), so no synchronization is required. D-040: plain POJO + record-based
 * events — GraalVM AOT friendly.
 */
@Entity
@Table(name = "ep_order")
public class OrderModel implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Default tenant id when none is supplied (single-tenant back-compat). */
    public static final String DEFAULT_TENANT = "default";
    /** Product type for course orders (the legacy/only kind before generalisation). */
    public static final String PRODUCT_COURSE = "COURSE";

    // D-132: TiDB write hotspot fix — IDENTITY (monotonic) → application-assigned Snowflake ID.
    // PingCAP best practice: avoid AUTO_INCREMENT on TiDB to prevent single-Region write hotspot.
    // ID is assigned by the service layer before persist (see OrderServiceImpl). H2 ddl-auto=create-drop
    // generates the column as bigint, compatible with both H2 (dev-local) and TiDB (prod).
    @Id
    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /**
     * Generalised product id — the product this order is for. For COURSE orders
     * this holds the course id; for other product types it holds the
     * corresponding product reference. Nullable for non-inventory products
     * (e.g., memberships, donations). Column name kept as {@code course_id}
     * for backward compatibility with the existing schema.
     */
    @Column(name = "course_id")
    private Long courseId;

    /** D-044 multi-tenant scope. Defaults to {@value #DEFAULT_TENANT}. */
    @Column(name = "tenant_id", length = 64)
    private String tenantId = DEFAULT_TENANT;

    /** Product kind this order is for (COURSE, MEMBERSHIP, ...). */
    @Column(name = "product_type", length = 32)
    private String productType = PRODUCT_COURSE;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false)
    private Integer status;

    @Column(name = "pay_method", length = 32)
    private String payMethod;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 64)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;

    @Version
    @Column(name = "lock_version")
    private Long lockVersion;

    /**
     * Domain events recorded by aggregate behaviour methods. Transient (not
     * persisted): the application service pulls them after a successful
     * transaction via {@link #pullEvents()} and publishes to the event bus.
     */
    private final transient List<DomainEvent> events = new ArrayList<>();

    public OrderModel() {
    }

    public OrderModel(Long orderId, Long userId, Long courseId, BigDecimal amount, Integer status,
                     String payMethod, String idempotencyKey, Long createdAt, Long updatedAt) {
        this.orderId = orderId;
        this.userId = userId;
        this.courseId = courseId;
        this.amount = amount;
        this.status = status;
        this.payMethod = payMethod;
        this.idempotencyKey = idempotencyKey;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getCourseId() { return courseId; }
    public void setCourseId(Long courseId) { this.courseId = courseId; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId == null ? DEFAULT_TENANT : tenantId; }
    public String getProductType() { return productType; }
    public void setProductType(String productType) { this.productType = productType == null ? PRODUCT_COURSE : productType; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public String getPayMethod() { return payMethod; }
    public void setPayMethod(String payMethod) { this.payMethod = payMethod; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }
    public Long getLockVersion() { return lockVersion; }
    public void setLockVersion(Long lockVersion) { this.lockVersion = lockVersion; }

    // --------------------- Domain behaviour (state machine) ---------------------

    /**
     * Transition PENDING -&gt; PAID. Records an {@link OrderPaidEvent}.
     *
     * @throws IllegalStateException if the order is not in PENDING status.
     */
    public void pay(String payMethod) {
        if (getStatus() == null || getStatus() != OrderStatus.PENDING.getCode()) {
            throw new IllegalStateException(
                    "Order not in PENDING status, cannot pay. orderId=" + orderId
                            + " status=" + status);
        }
        this.status = OrderStatus.PAID.getCode();
        this.payMethod = payMethod;
        this.updatedAt = System.currentTimeMillis();
        recordEvent(new OrderPaidEvent(
                orderId == null ? 0L : orderId,
                orderId == null ? 0L : orderId,
                userId, courseId, productType, payMethod, Instant.now()));
    }

    /**
     * Transition PAID -&gt; REFUNDED. Records an {@link OrderRefundedEvent}.
     *
     * @throws IllegalStateException if the order is not in PAID status.
     */
    public void refund() {
        if (getStatus() == null || getStatus() != OrderStatus.PAID.getCode()) {
            throw new IllegalStateException(
                    "Order not in PAID status, cannot refund. orderId=" + orderId
                            + " status=" + status);
        }
        this.status = OrderStatus.REFUNDED.getCode();
        this.updatedAt = System.currentTimeMillis();
        recordEvent(new OrderRefundedEvent(
                orderId == null ? 0L : orderId,
                orderId == null ? 0L : orderId,
                userId, courseId, productType, Instant.now()));
    }

    /**
     * Pull and clear recorded domain events. The application service calls
     * this after the JPA transaction commits so the events can be published
     * (post-commit) to the event bus.
     */
    public List<DomainEvent> pullEvents() {
        List<DomainEvent> snapshot = new ArrayList<>(events);
        events.clear();
        return snapshot;
    }

    private void recordEvent(DomainEvent event) {
        events.add(event);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof OrderModel order)) return false;
        // Entity identity: orderId only. Transient fields (events) and optimistic
        // lock version deliberately excluded from the equals contract.
        return Objects.equals(orderId, order.orderId);
    }

    @Override
    public int hashCode() { return Objects.hash(orderId); }

    @Override
    public String toString() {
        return "OrderModel{orderId=" + orderId + ", userId=" + userId + ", courseId=" + courseId
                + ", tenantId='" + tenantId + "', productType='" + productType
                + "', amount=" + amount + ", status=" + status
                + ", payMethod='" + payMethod + "'}";
    }
}
