package com.ep.data.cdc;

import java.io.Serializable;
import java.util.Objects;

/**
 * Order row image carried inside a {@link CdcEnvelope} for the
 * {@code order_group} CDC stream (TiDB → TiCDC → Pulsar → Flink → Iceberg).
 */
public class OrderCDC implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long orderId;
    private Long userId;
    private Long courseId;
    private String amount;
    private Integer status;
    private String idempotencyKey;
    private Long createdAt;
    private Long updatedAt;

    public OrderCDC() {
    }

    public OrderCDC(Long orderId, Long userId, Long courseId, String amount,
                    Integer status, String idempotencyKey, Long createdAt, Long updatedAt) {
        this.orderId = orderId;
        this.userId = userId;
        this.courseId = courseId;
        this.amount = amount;
        this.status = status;
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

    public String getAmount() { return amount; }
    public void setAmount(String amount) { this.amount = amount; }

    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }

    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }

    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof OrderCDC that)) return false;
        return Objects.equals(orderId, that.orderId)
                && Objects.equals(userId, that.userId)
                && Objects.equals(courseId, that.courseId)
                && Objects.equals(amount, that.amount)
                && Objects.equals(status, that.status)
                && Objects.equals(idempotencyKey, that.idempotencyKey)
                && Objects.equals(createdAt, that.createdAt)
                && Objects.equals(updatedAt, that.updatedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(orderId, userId, courseId, amount, status, idempotencyKey, createdAt, updatedAt);
    }

    @Override
    public String toString() {
        return "OrderCDC{orderId=" + orderId + ", userId=" + userId + ", courseId=" + courseId
                + ", amount='" + amount + '\'' + ", status=" + status
                + ", idempotencyKey='" + idempotencyKey + '\''
                + ", createdAt=" + createdAt + ", updatedAt=" + updatedAt + '}';
    }
}
