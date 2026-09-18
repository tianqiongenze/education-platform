package com.ep.order.controller;

import com.ep.order.model.PaymentCompensationFailure;
import com.ep.order.service.PaymentCompensationReconcileService;
import com.ep.web.common.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D-222: standalone MockMvc tests for the payment compensation reconciliation
 * endpoints (admin-gated, mirrors OrderControllerTest conventions).
 */
class PaymentCompensationControllerTest {

    private MockMvc mockMvc;
    private PaymentCompensationReconcileService reconcileService;

    @BeforeEach
    void setUp() {
        reconcileService = mock(PaymentCompensationReconcileService.class);
        PaymentCompensationController controller = new PaymentCompensationController(reconcileService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private PaymentCompensationFailure failure(String id) {
        return PaymentCompensationFailure.open(
                id, "order-1", "tenant-a", new BigDecimal("12.34"),
                "IllegalStateException: gateway timeout", Instant.now());
    }

    @Test
    @DisplayName("unresolved listing requires admin role")
    void listRequiresAdminRole() throws Exception {
        when(reconcileService.listUnresolved()).thenReturn(List.of(failure("f-1")));

        mockMvc.perform(get("/api/v1/payment-compensations/unresolved"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/payment-compensations/unresolved")
                        .header("X-Role", "student"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/payment-compensations/unresolved")
                        .header("X-Role", "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].failureId").value("f-1"))
                .andExpect(jsonPath("$[0].resolved").value(false));
    }

    @Test
    @DisplayName("resolve closes a row and requires admin role")
    void resolveClosesRow() throws Exception {
        PaymentCompensationFailure closed = failure("f-2");
        closed.resolve("refunded manually", Instant.now());
        when(reconcileService.resolve(org.mockito.ArgumentMatchers.eq("f-2"), anyNote()))
                .thenReturn(closed);

        mockMvc.perform(post("/api/v1/payment-compensations/f-2/resolve")
                        .header("X-Role", "admin")
                        .contentType("application/json")
                        .content("{\"note\":\"refunded manually\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failureId").value("f-2"))
                .andExpect(jsonPath("$.resolved").value(true))
                .andExpect(jsonPath("$.resolvedAt").isNotEmpty());
    }

    @Test
    @DisplayName("resolve without admin role is forbidden")
    void resolveForbiddenWithoutRole() throws Exception {
        mockMvc.perform(post("/api/v1/payment-compensations/f-3/resolve")
                        .header("X-Role", "user")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    private static String anyNote() {
        return org.mockito.ArgumentMatchers.any();
    }
}
