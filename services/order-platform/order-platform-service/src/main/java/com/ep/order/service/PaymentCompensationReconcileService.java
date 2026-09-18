package com.ep.order.service;

import com.ep.order.model.PaymentCompensationFailure;
import com.ep.order.repository.PaymentCompensationFailureRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * D-222: durable record + reconciliation for failed Payment Saga refunds.
 * <p>
 * The Saga refund compensation stays best-effort (propagating the exception
 * would abort the LIFO compensation chain and strand the reserved stock), so
 * the failure gap is closed here: every failed refund is persisted as an open
 * {@link PaymentCompensationFailure} and announced with a structured ERROR
 * log (the offline-testable alert channel). Operators list open failures via
 * {@code PaymentCompensationController}, refund out-of-band in the payment
 * gateway console, then close the row with {@link #resolve}.
 */
@Service
public class PaymentCompensationReconcileService {

    private static final Logger log = LoggerFactory.getLogger(PaymentCompensationReconcileService.class);

    private final PaymentCompensationFailureRepository repository;

    public PaymentCompensationReconcileService(PaymentCompensationFailureRepository repository) {
        this.repository = repository;
    }

    /**
     * Persist an open failure record for a refund that could not be issued.
     * Idempotent per saga context: each failed compensation attempt gets its
     * own row (the Saga engine may re-run the compensation after recovery, and
     * the operator must see every attempt that failed).
     */
    @Transactional
    public PaymentCompensationFailure recordFailure(String orderId, String tenantId,
                                                    BigDecimal amount, String reason) {
        String reasonTruncated = reason == null ? "unknown" : truncate(reason, 512);
        PaymentCompensationFailure failure = PaymentCompensationFailure.open(
                UUID.randomUUID().toString(), orderId, tenantId, amount, reasonTruncated, Instant.now());
        PaymentCompensationFailure saved = repository.save(failure);
        log.error("[ALERT][PAYMENT_COMPENSATION_FAILED] orderId={} tenantId={} amount={} reason={}"
                        + " failureId={} — money owed to customer, manual refund required (see"
                        + " PaymentCompensationController reconciliation endpoint)",
                orderId, tenantId, amount, reasonTruncated, saved.getFailureId());
        return saved;
    }

    /** All open (unresolved) compensation failures, newest first. */
    @Transactional(readOnly = true)
    public List<PaymentCompensationFailure> listUnresolved() {
        return repository.findByResolvedFalseOrderByFailedAtDesc();
    }

    /**
     * Close a failure row after the operator confirmed the out-of-band refund.
     *
     * @throws IllegalArgumentException when the failure id is unknown
     * @throws IllegalStateException    when the row is already resolved
     */
    @Transactional
    public PaymentCompensationFailure resolve(String failureId, String note) {
        PaymentCompensationFailure failure = repository.findById(failureId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "unknown compensation failure: " + failureId));
        failure.resolve(note == null ? "" : truncate(note, 512), Instant.now());
        log.info("D-222: compensation failure {} resolved (orderId={}, note={})",
                failureId, failure.getOrderId(), note);
        return failure;
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
