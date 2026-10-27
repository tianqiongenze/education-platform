package com.ep.payment.controller;

import com.ep.payment.api.AgentPaymentRequest;
import com.ep.payment.api.Payment;
import com.ep.payment.api.PaymentChannel;
import com.ep.payment.api.PaymentRequest;
import com.ep.payment.api.PaymentResult;
import com.ep.payment.api.PaymentService;
import com.ep.payment.api.Refund;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;

/**
 * D-119: REST fa&ccedil;ade for the core-business Payment service, exposing the
 * {@link PaymentService} over HTTP so that Spring Cloud Contract can define
 * consumer-driven contracts against a real HTTP surface.
 *
 * <p>The primary integration path for order-platform is Dubbo RPC
 * ({@code @DubboReference PaymentService}); this REST controller is added so
 * that the CDC contracts (Groovy DSL under {@code src/test/resources/contracts/})
 * have a standard HTTP endpoint to verify against. In production the Dubbo path
 * remains canonical; the REST surface is a thin adapter for contract testing
 * and potential BFF/external clients.
 *
 * <p>Endpoints (mirroring the contract files):
 * <ul>
 *   <li>{@code POST /api/payments} — create a payment (orderId, amount, channel).</li>
 *   <li>{@code POST /api/payments/{orderId}/refund} — refund an order's payment.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/payments")
@Validated
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    /**
     * Create a payment. Returns 201 with the payment result.
     *
     * @param body the create-payment request (orderId, amount, channel, tenantId, currency)
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody CreatePaymentHttp request) {
        PaymentChannel channel = parseChannel(request.channel());
        PaymentRequest pr = new PaymentRequest(
                request.orderId(),
                request.tenantId() == null ? "default" : request.tenantId(),
                channel,
                new BigDecimal(request.amount()),
                request.currency() == null ? "CNY" : request.currency());
        PaymentResult result = paymentService.createPayment(pr);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "paymentId", result.paymentId(),
                "status", result.status().name(),
                "channelTxId", result.channelTxId() == null ? "" : result.channelTxId(),
                "orderId", request.orderId()));
    }

    /**
     * Refund an order's payment. Returns 200 with the refund result.
     *
     * @param orderId the order whose payment should be refunded
     * @param body    the refund request (amount, reason)
     */
    @PostMapping("/{orderId}/refund")
    public ResponseEntity<Map<String, Object>> refund(@PathVariable String orderId,
                                                       @Valid @RequestBody RefundHttp request) {
        Refund refund = paymentService.refund(
                orderId,
                new BigDecimal(request.amount()),
                request.reason() == null ? "REST refund" : request.reason());
        return ResponseEntity.ok(Map.of(
                "refundId", refund.getRefundId(),
                "orderId", orderId,
                "status", refund.getStatus().name(),
                "amount", request.amount()));
    }

    /**
     * D-240/B: create a payment on behalf of an AI agent (「AI支付」· L2 控制层).
     * The service verifies the authorization envelope (KYA agent, ACTIVE
     * authorization, per-tx/daily limits, one-time confirmation token for
     * CONFIRM_REQUIRED) before dispatching to the channel — fail-closed.
     * Rejections surface as 403 with a stable {@code code} via
     * {@link AgentApiExceptionHandler}.
     *
     * @param body the agent create-payment request
     */
    @PostMapping("/agent")
    public ResponseEntity<Map<String, Object>> createAgent(@Valid @RequestBody CreateAgentPaymentHttp request) {
        PaymentChannel channel = parseChannel(request.channel());
        AgentPaymentRequest apr = new AgentPaymentRequest(
                request.orderId(),
                request.tenantId() == null ? "default" : request.tenantId(),
                channel,
                new BigDecimal(request.amount()),
                request.currency() == null ? "CNY" : request.currency(),
                request.agentId(),
                request.authorizationId(),
                request.paymentToken());
        PaymentResult result = paymentService.createAgentPayment(apr);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "paymentId", result.paymentId(),
                "status", result.status().name(),
                "channelTxId", result.channelTxId() == null ? "" : result.channelTxId(),
                "orderId", request.orderId(),
                "agentId", request.agentId(),
                "authorizationId", request.authorizationId()));
    }

    private static PaymentChannel parseChannel(String channel) {
        if (channel == null || channel.isBlank()) {
            return PaymentChannel.MOCK;
        }
        String upper = channel.trim().toUpperCase();
        for (PaymentChannel ch : PaymentChannel.values()) {
            if (ch.name().equals(upper)) {
                return ch;
            }
        }
        return PaymentChannel.MOCK;
    }

    /** HTTP request body for POST /api/payments. */
    public record CreatePaymentHttp(
            @NotBlank String orderId,
            String tenantId,
            String channel,
            @NotBlank String amount,
            String currency
    ) implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
    }

    /** HTTP request body for POST /api/payments/agent (D-240/B). */
    public record CreateAgentPaymentHttp(
            @NotBlank String orderId,
            String tenantId,
            String channel,
            @NotBlank String amount,
            String currency,
            @NotBlank String agentId,
            @NotBlank String authorizationId,
            String paymentToken
    ) implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
    }

    /** HTTP request body for POST /api/payments/{orderId}/refund. */
    public record RefundHttp(
            @NotBlank String amount,
            String reason
    ) implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
    }
}
