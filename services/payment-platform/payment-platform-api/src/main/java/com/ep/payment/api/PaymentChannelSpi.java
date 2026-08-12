package com.ep.payment.api;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Strategy / SPI for concrete payment channels (SDD §3.1 lists Wechat / Alipay
 * / Apple / Balance). Each channel is a Spring bean implementing this
 * interface; the service's {@code PaymentServiceImpl} collects every bean and
 * dispatches by {@link #getChannel()}.
 *
 * <p>This is the classic <b>Strategy + Factory</b> combination used industry-wide
 * for multi-channel payment: the Factory (PaymentServiceImpl) looks the channel
 * up by enum, the Strategy (this interface) encapsulates one channel's wire
 * protocol. Adding a new channel is a new bean — no edits to the service.
 *
 * <p>EP_DEV_ONLY: {@code MockPaymentChannel} is the dev/CI default. Production
 * replaces it with real Alipay/WeChat beans. No real gateway SDK is wired in
 * this stub (by design — see task constraints).
 *
 * <p>NOTE: {@link PaymentRequest}, {@link PaymentResult} and {@link RefundResult}
 * are all in this same {@code com.ep.payment.api} package.
 */
public interface PaymentChannelSpi {

    /** Which {@link PaymentChannel} this bean handles. */
    PaymentChannel getChannel();

    /**
     * Drive a charge through this channel.
     * <p>
     * Implementations decide whether to block (synchronous mock) or return an
     * intermediate {@link PaymentStatus#PROCESSING} result and rely on a
     * callback to finish. The mock channel is synchronous.
     *
     * @param request the charge request
     * @return the (possibly intermediate) result; {@link PaymentResult#paymentId()}
     *         must echo the id the caller assigned, or one the channel minted.
     */
    PaymentResult pay(PaymentRequest request);

    /** Whether this channel can issue refunds. Balance/Mock → true; some gateways → false. */
    boolean supportsRefund();

    /**
     * Issue a refund for a previously successful charge.
     *
     * @param channelTxId the gateway transaction id returned by {@link #pay}
     * @param amount      how much to refund (may be a partial amount)
     * @param reason      free-text reason recorded on the gateway side
     * @return {@link Optional#empty()} if {@link #supportsRefund()} is false;
     *         otherwise the refund result
     */
    Optional<RefundResult> refund(String channelTxId, BigDecimal amount, String reason);
}
