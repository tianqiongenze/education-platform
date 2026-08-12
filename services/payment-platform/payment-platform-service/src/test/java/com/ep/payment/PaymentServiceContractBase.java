package com.ep.payment;

import com.ep.payment.controller.PaymentController;
import io.restassured.module.mockmvc.RestAssuredMockMvc;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.context.WebApplicationContext;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;

import com.ep.payment.api.PaymentResult;
import com.ep.payment.api.PaymentStatus;
import com.ep.payment.api.Refund;
import com.ep.payment.api.RefundStatus;
import com.ep.payment.api.PaymentService;

/**
 * D-119: Base class for Spring Cloud Contract generated verifier tests.
 *
 * <p>The {@code spring-cloud-contract-maven-plugin} generates a test class per
 * Groovy contract file and extends this base class. This base sets up the
 * RestAssured MockMvc context with the {@link PaymentController} and a mocked
 * {@link PaymentService} so the generated tests can verify the HTTP contract
 * without starting a full Spring Boot server.
 *
 * <p>The mocked {@link PaymentService} returns deterministic results that match
 * the response shapes declared in the contract Groovy files.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
public abstract class PaymentServiceContractBase {

    @MockitoBean
    private PaymentService paymentService;

    @BeforeEach
    void setup(WebApplicationContext context) {
        // Wire the controller with a mocked PaymentService so the generated
        // contract verifier tests can assert the HTTP shape without a real
        // payment backend.
        when(paymentService.createPayment(any())).thenReturn(
                new PaymentResult("pay-contract-1", PaymentStatus.SUCCESS,
                        "tx-contract-1", Instant.parse("2026-08-09T10:00:00Z")));

        Refund refund = new Refund("refund-contract-1", "pay-contract-1",
                "order-contract-1", "default",
                new BigDecimal("100.00"), RefundStatus.SUCCESS,
                "contract test refund", Instant.parse("2026-08-09T10:05:00Z"));
        when(paymentService.refund(anyString(), any(BigDecimal.class), anyString()))
                .thenReturn(refund);

        RestAssuredMockMvc.webAppContextSetup(context);
    }
}
