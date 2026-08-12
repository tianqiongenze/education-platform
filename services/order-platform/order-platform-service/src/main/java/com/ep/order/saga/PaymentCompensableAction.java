package com.ep.order.saga;

import com.ep.order.model.OrderModel;
import com.ep.payment.api.PaymentChannel;
import com.ep.payment.api.PaymentRequest;
import com.ep.payment.api.PaymentResult;
import com.ep.payment.api.PaymentService;
import com.ep.payment.api.Refund;
import io.seata.saga.engine.StateMachineEngine;
import org.apache.dubbo.config.annotation.DubboReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;

/**
 * D-113: Payment Saga compensable action.
 *
 * <p>This bean is invoked by the Seata Saga {@link StateMachineEngine} when the
 * {@code create-order-saga.json} state machine reaches the
 * {@code CreatePayment} (forward) or {@code RefundPayment} (compensation)
 * state.
 *
 * <h3>Saga pattern</h3>
 * The forward action {@code createPayment} instructs the OPM Payment middle
 * platform to record a Payment for the order via the cross-service Dubbo RPC
 * reference. Its compensation {@code compensateCreatePayment} instructs OPM to
 * refund. Because a Payment may legitimately fail (gateway decline, timeout)
 * the Saga engine maps the failure to {@code CompensationTrigger} so the
 * already-reserved stock is released.
 *
 * <h3>Best-effort semantics preserved</h3>
 * The pre-Saga {@code payOrder}/{@code refundOrder} methods in
 * {@code OrderServiceImpl} treated Payment as best-effort (an OPM failure was
 * logged but did not roll back the order status flip). The Saga keeps that
 * contract for the <em>refund</em> compensation: a refund failure is logged
 * and reconciled out-of-band, so the Saga can still complete the cancellation
 * flow rather than blocking on a transient OPM outage.
 */
@Component("paymentCompensableAction")
public class PaymentCompensableAction {

    private static final Logger log = LoggerFactory.getLogger(PaymentCompensableAction.class);

    /**
     * Cross-service Dubbo RPC reference to the OPM Payment middle platform.
     * {@code check=false} so the order service boots even when the payment
     * provider is not yet registered (dev/CI); {@code timeout=10000ms} covers
     * the synchronous gateway round-trip on the pay path.
     */
    @DubboReference(check = false, timeout = 10000)
    private PaymentService paymentService;

    /**
     * Forward action: instruct OPM to create a Payment for the order.
     *
     * @param context the Saga state machine context (orderId, amount,
     *                payMethod, tenantId)
     * @return a result map inserted back into the Saga context under
     *         {@code createPaymentResult}
     * @throws RuntimeException when the OPM RPC fails; the Saga engine maps
     *         this to {@code CompensationTrigger}
     */
    public Map<String, Object> createPayment(Map<String, Object> context) {
        Objects.requireNonNull(context, "saga context must not be null");
        String orderId = String.valueOf(context.get("orderId"));
        BigDecimal amount = asBigDecimal(context.get("amount"));
        String payMethod = (String) context.get("payMethod");
        String tenantId = (String) context.get("tenantId");
        if (amount == null) {
            amount = BigDecimal.ZERO;
        }

        PaymentChannel channel = resolvePaymentChannel(payMethod);
        PaymentRequest request = new PaymentRequest(
                orderId,
                tenantId == null ? OrderModel.DEFAULT_TENANT : tenantId,
                channel, amount, "CNY");

        try {
            PaymentResult result = paymentService.createPayment(request);
            log.info("D-113: saga createPayment recorded for orderId={}: paymentId={}, status={}",
                    orderId, result.paymentId(), result.status());
            return Map.of(
                    "orderId", orderId,
                    "paymentId", result.paymentId(),
                    "status", String.valueOf(result.status()),
                    "channelTxId", result.channelTxId() == null ? "" : result.channelTxId());
        } catch (RuntimeException e) {
            log.error("D-113: saga createPayment failed for orderId={} (will trigger compensation): {}",
                    orderId, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Compensation action: instruct OPM to refund the order's Payment.
     *
     * <p>Best-effort: a refund failure is logged but does NOT propagate — the
     * Saga must still be able to reach a consistent end state. Operators
     * reconcile the gap out-of-band (mirroring the pre-Saga {@code refundOrder}
     * semantics).
     *
     * @param context the Saga state machine context (orderId, amount)
     * @return a result map describing the refund
     */
    public Map<String, Object> compensateCreatePayment(Map<String, Object> context) {
        Objects.requireNonNull(context, "saga context must not be null");
        String orderId = String.valueOf(context.get("orderId"));
        BigDecimal amount = asBigDecimal(context.get("amount"));
        if (amount == null) {
            amount = BigDecimal.ZERO;
        }
        try {
            Refund refund = paymentService.refund(orderId, amount, "D-113 saga compensation");
            log.info("D-113: saga compensateCreatePayment refunded orderId={}: refundId={}, status={}",
                    orderId, refund.getRefundId(), refund.getStatus());
            return Map.of(
                    "orderId", orderId,
                    "refundId", refund.getRefundId(),
                    "status", String.valueOf(refund.getStatus()));
        } catch (RuntimeException e) {
            // Best-effort: do not propagate. Reconciliation catches the gap.
            log.error("D-113: saga compensateCreatePayment failed for orderId={} (stays best-effort): {}",
                    orderId, e.getMessage(), e);
            return Map.of(
                    "orderId", orderId,
                    "refundId", "",
                    "status", "REFUND_FAILED_BEST_EFFORT");
        }
    }

    /**
     * Coerce a Saga context value to {@link BigDecimal}. The JSON state
     * machine deserialises numbers as {@link Integer}/{@link Long}/{@link Double}
     * depending on magnitude, so normalise here.
     */
    private static BigDecimal asBigDecimal(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof BigDecimal bd) {
            return bd;
        }
        if (raw instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        return new BigDecimal(raw.toString());
    }

    /**
     * Map the legacy free-text {@code payMethod} to a {@link PaymentChannel}.
     * Mirrors {@code OrderServiceImpl.resolvePaymentChannel} so the Saga and the
     * non-Saga {@code payOrder} path produce identical channel selection.
     */
    static PaymentChannel resolvePaymentChannel(String payMethod) {
        if (payMethod == null) {
            return PaymentChannel.MOCK;
        }
        String upper = payMethod.trim().toUpperCase();
        for (PaymentChannel ch : PaymentChannel.values()) {
            if (ch.name().equals(upper)) {
                return ch;
            }
        }
        if ("ALIPAY".equals(upper) || "ZFB".equals(upper)) return PaymentChannel.ALIPAY;
        if ("WECHAT".equals(upper) || "WX".equals(upper) || "WECHATPAY".equals(upper)) {
            return PaymentChannel.WECHAT;
        }
        return PaymentChannel.MOCK;
    }
}
