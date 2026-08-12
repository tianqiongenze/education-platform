package com.ep.payment.repository;

import com.ep.payment.entity.RefundEntity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * D-132: Spring Data JPA repository for {@link RefundEntity} ({@code ep_refund}).
 * Replaces the {@code refundsById} {@link java.util.concurrent.ConcurrentHashMap}
 * in {@code PaymentServiceImpl}.
 */
@Repository
public interface RefundJpaRepository extends JpaRepository<RefundEntity, String> {
}
