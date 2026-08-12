package com.ep.payment.api;

/**
 * Concrete payment channels supported by the OPM domain (SDD §3.1 lists
 * Wechat / Alipay / Apple / Balance; CREDIT and MOCK are added for the
 * education-platform stub).
 *
 * <p>The channel is the discriminator for the {@link PaymentChannelSpi} strategy:
 * each {@code PaymentChannelSpi} bean declares exactly one channel via
 * {@link PaymentChannelSpi#getChannel()}.
 *
 * <p>D-040: plain enum — GraalVM AOT friendly.
 */
public enum PaymentChannel {
    /** Alipay (支付宝). Real gateway NOT wired in this stub — SPI only. */
    ALIPAY,
    /** WeChat Pay (微信支付). Real gateway NOT wired in this stub — SPI only. */
    WECHAT,
    /** User balance wallet (internal account). */
    BALANCE,
    /** Credit / IOU account (internal). */
    CREDIT,
    /**
     * EP_DEV_ONLY in-process channel. The default dev/CI implementation
     * ({@code MockPaymentChannel}) advertises this channel; it never touches a
     * real gateway and resolves payments to SUCCESS synchronously. In
     * production real Alipay/WeChat beans replace it.
     */
    MOCK
}
