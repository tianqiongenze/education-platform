package com.ep.payment.channel;

import com.ep.payment.api.PaymentChannel;
import com.ep.payment.api.PaymentRequest;
import com.ep.payment.api.PaymentResult;
import com.ep.payment.api.PaymentStatus;
import com.ep.payment.api.RefundResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the {@link MockPaymentChannel} SPI implementation. These pin
 * the EP_DEV_ONLY contract that the mock always succeeds and supports refund.
 */
class MockPaymentChannelTest {

    private MockPaymentChannel channel;

    @BeforeEach
    void setUp() {
        channel = new MockPaymentChannel();
    }

    @Test
    void getChannel_returnsMock() {
        assertEquals(PaymentChannel.MOCK, channel.getChannel(),
                "mock channel must identify itself as MOCK");
    }

    @Test
    void pay_returnsSuccess() {
        PaymentRequest request = new PaymentRequest("order-1", "tenant-A",
                PaymentChannel.MOCK, new BigDecimal("99.00"), "CNY");
        PaymentResult result = channel.pay(request);
        assertEquals(PaymentStatus.SUCCESS, result.status());
        assertNotNull(result.channelTxId());
        assertTrue(result.channelTxId().startsWith("mock-tx-"),
                "mock channelTxId should be prefixed: " + result.channelTxId());
        assertNotNull(result.paidAt());
    }

    @Test
    void supportsRefund_true() {
        assertTrue(channel.supportsRefund(), "mock channel must advertise refund support");
    }

    @Test
    void refund_returnsSuccess() {
        Optional<RefundResult> result = channel.refund("mock-tx-abc",
                new BigDecimal("99.00"), "user cancelled");
        assertTrue(result.isPresent());
        assertTrue(result.get().success());
        assertNotNull(result.get().channelRefundId());
        assertTrue(result.get().channelRefundId().startsWith("mock-refund-"));
    }

    @Test
    void pay_nullRequest_throws() {
        assertThrows(NullPointerException.class, () -> channel.pay(null));
    }
}
