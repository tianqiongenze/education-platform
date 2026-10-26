package com.ep.payment.repository;

import com.ep.payment.entity.RefundEntity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;

/**
 * D-132: Spring Data JPA repository for {@link RefundEntity} ({@code ep_refund}).
 * Replaces the {@code refundsById} {@link java.util.concurrent.ConcurrentHashMap}
 * in {@code PaymentServiceImpl}.
 *
 * <p>D-236 (money-safety): refund amount accounting runs on the DB, not in the
 * JVM — the cumulative active-refund sum is the guard against over-refunding a
 * payment across multiple partial refunds.</p>
 */
@Repository
public interface RefundJpaRepository extends JpaRepository<RefundEntity, String> {

    /**
     * D-236: cumulative amount of all refunds for the order that are NOT
     * {@code FAILED} (a failed refund consumed no funds). {@code COALESCE}
     * keeps the first refund of a fresh payment cheap and null-free.
     */
    @Query("SELECT COALESCE(SUM(r.amount), 0) FROM RefundEntity r "
            + "WHERE r.orderId = :orderId AND r.status <> com.ep.payment.api.RefundStatus.FAILED")
    BigDecimal sumActiveRefundAmountByOrderId(@Param("orderId") String orderId);
}
