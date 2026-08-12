package com.ep.payment.repository;

import com.ep.payment.entity.PaymentEntity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * D-132: Spring Data JPA repository for {@link PaymentEntity} ({@code ep_payment}).
 * Replaces the {@code paymentsById} / {@code paymentsByOrder}
 * {@link java.util.concurrent.ConcurrentHashMap}s in {@code PaymentServiceImpl}.
 */
@Repository
public interface PaymentJpaRepository extends JpaRepository<PaymentEntity, String> {

    /** Lookup the (latest) payment for an order — backs {@code getPaymentByOrder}. */
    Optional<PaymentEntity> findFirstByOrderId(String orderId);
}
