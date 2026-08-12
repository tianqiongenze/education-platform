package com.ep.payment.channel;

import com.ep.payment.api.PaymentChannel;
import com.ep.payment.api.PaymentChannelSpi;
import com.ep.payment.api.PaymentRequest;
import com.ep.payment.api.PaymentResult;
import com.ep.payment.api.PaymentStatus;
import com.ep.payment.api.RefundResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * EP_DEV_ONLY in-process {@link PaymentChannelSpi}. The default dev/CI
 * implementation: it never touches a real gateway, mints a fake
 * {@code channelTxId} and resolves every charge to {@link PaymentStatus#SUCCESS}
 * synchronously, and every refund to success.
 *
 * <p>In production this bean is either excluded (profile guard) or shadowed by
 * real Alipay/WeChat beans; the {@code EP_DEV_ONLY} guard
 * ({@link com.ep.payment.config.DevGuardConfig}) refuses to start in non-dev
 * profiles when {@code EP_DEV_ONLY=true}, so a misconfigured prod instance
 * pointing at this mock is caught at boot.
 *
 * <p>D-040: plain {@code @Component} with no reflection — GraalVM AOT friendly.
 */
@Component
public class MockPaymentChannel implements PaymentChannelSpi {

    private static final Logger log = LoggerFactory.getLogger(MockPaymentChannel.class);

    @Override
    public PaymentChannel getChannel() {
        return PaymentChannel.MOCK;
    }

    /**
     * Synchronously "charge": mint a fake channel tx id and report SUCCESS.
     * No money moves. The Payment id on the result is taken from the request
     * (the caller — PaymentServiceImpl — already minted a UUID).
     */
    @Override
    public PaymentResult pay(PaymentRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        String channelTxId = "mock-tx-" + UUID.randomUUID();
        Instant paidAt = Instant.now();
        log.debug("EP_DEV_ONLY MockPaymentChannel.pay: orderId={}, amount={} {} -> channelTxId={}",
                request.orderId(), request.amount(), request.currency(), channelTxId);
        return new PaymentResult(null, PaymentStatus.SUCCESS, channelTxId, paidAt);
    }

    @Override
    public boolean supportsRefund() {
        return true;
    }

    @Override
    public Optional<RefundResult> refund(String channelTxId, BigDecimal amount, String reason) {
        String channelRefundId = "mock-refund-" + UUID.randomUUID();
        log.debug("EP_DEV_ONLY MockPaymentChannel.refund: channelTxId={}, amount={}, reason={} -> {}",
                channelTxId, amount, reason, channelRefundId);
        return Optional.of(new RefundResult(true, channelRefundId, "mock refund succeeded"));
    }
}
