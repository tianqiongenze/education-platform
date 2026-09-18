package com.ep.order.repository;

import com.ep.order.model.PaymentCompensationFailure;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * D-222: repository for failed Payment Saga refund compensations.
 * <p>
 * The reconciliation flow only ever needs the open failures (newest first),
 * so a single derived query covers it.
 */
@Repository
public interface PaymentCompensationFailureRepository
        extends JpaRepository<PaymentCompensationFailure, String> {

    List<PaymentCompensationFailure> findByResolvedFalseOrderByFailedAtDesc();
}
