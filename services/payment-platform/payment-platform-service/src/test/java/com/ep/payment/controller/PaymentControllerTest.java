package com.ep.payment.controller;

import com.ep.payment.api.PaymentChannel;
import com.ep.payment.api.PaymentResult;
import com.ep.payment.api.PaymentService;
import com.ep.payment.api.PaymentStatus;
import com.ep.payment.api.Refund;
import com.ep.payment.api.RefundStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Standalone-setup MockMvc tests for {@link PaymentController}. Mocks the
 * {@link PaymentService} and exercises both the create (201) and refund (200)
 * REST endpoints that back the Spring Cloud Contract surface.
 */
class PaymentControllerTest {

    private MockMvc mockMvc;
    private final ObjectMapper mapper = new ObjectMapper();
    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        paymentService = mock(PaymentService.class);
        PaymentController controller = new PaymentController(paymentService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void create_endpoint_returns201() throws Exception {
        PaymentResult result = new PaymentResult(
                "pay-uuid-001", PaymentStatus.SUCCESS, "ch-tx-001", Instant.now());
        when(paymentService.createPayment(any())).thenReturn(result);

        var body = new PaymentController.CreatePaymentHttp(
                "order-1001", "default", "MOCK", "99.00", "CNY");

        mockMvc.perform(post("/api/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.paymentId").value("pay-uuid-001"))
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.channelTxId").value("ch-tx-001"))
                .andExpect(jsonPath("$.orderId").value("order-1001"));
    }

    @Test
    void create_nullChannel_defaultsToMock() throws Exception {
        PaymentResult result = new PaymentResult(
                "pay-uuid-002", PaymentStatus.SUCCESS, "ch-tx-002", Instant.now());
        when(paymentService.createPayment(any())).thenReturn(result);

        var body = new PaymentController.CreatePaymentHttp(
                "order-1002", null, null, "50.00", null);

        mockMvc.perform(post("/api/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.paymentId").value("pay-uuid-002"));
    }

    @Test
    void refund_endpoint_returns200() throws Exception {
        Refund refund = new Refund(
                "refund-001", "pay-uuid-001", "order-1001", "default",
                new BigDecimal("99.00"), RefundStatus.SUCCESS, "REST refund", Instant.now());

        when(paymentService.refund(eq("order-1001"), any(BigDecimal.class), any(String.class)))
                .thenReturn(refund);

        var body = new PaymentController.RefundHttp("99.00", "customer request");

        mockMvc.perform(post("/api/payments/{orderId}/refund", "order-1001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refundId").value("refund-001"))
                .andExpect(jsonPath("$.orderId").value("order-1001"))
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.amount").value("99.00"));

        verify(paymentService).refund(
                eq("order-1001"), eq(new BigDecimal("99.00")), eq("customer request"));
    }

    @Test
    void refund_nullReason_defaultsToRestRefund() throws Exception {
        Refund refund = new Refund(
                "refund-002", "pay-uuid-002", "order-1002", "default",
                new BigDecimal("50.00"), RefundStatus.PENDING, "REST refund", Instant.now());

        when(paymentService.refund(any(), any(), any())).thenReturn(refund);

        var body = new PaymentController.RefundHttp("50.00", null);

        mockMvc.perform(post("/api/payments/{orderId}/refund", "order-1002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refundId").value("refund-002"));

        verify(paymentService).refund(
                eq("order-1002"), eq(new BigDecimal("50.00")), eq("REST refund"));
    }
}
