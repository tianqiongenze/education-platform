package com.ep.payment.controller;

import com.ep.payment.agent.AgentAuthorizationException;
import com.ep.payment.api.AgentPaymentRejectedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * D-240/B: HTTP mapping for the agent authorization envelope. Authorization
 * boundary violations are always 403 (fail-closed, never 200-with-error-flag);
 * malformed input is 400. The stable {@code code} from
 * {@link AgentAuthorizationException} is surfaced so clients branch on the
 * failure class (LIMIT_PER_TX vs TOKEN_REPLAYED vs KYA_*) without parsing text.
 */
@RestControllerAdvice
public class AgentApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(AgentApiExceptionHandler.class);

    @ExceptionHandler(AgentAuthorizationException.class)
    public ResponseEntity<Map<String, Object>> authorizationDenied(AgentAuthorizationException e) {
        log.warn("agent API 403: code={} msg={}", e.getCode(), e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                "code", e.getCode(),
                "message", e.getMessage(),
                "rejected", true));
    }

    @ExceptionHandler(AgentPaymentRejectedException.class)
    public ResponseEntity<Map<String, Object>> agentPaymentRejected(AgentPaymentRejectedException e) {
        log.warn("agent payment 403: code={} msg={}", e.getCode(), e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                "code", e.getCode(),
                "message", e.getMessage(),
                "rejected", true));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "code", "BAD_REQUEST",
                "message", String.valueOf(e.getMessage()),
                "rejected", true));
    }
}
