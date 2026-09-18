package com.ep.order.controller;

import com.ep.order.api.PaymentCompensationView;
import com.ep.order.model.PaymentCompensationFailure;
import com.ep.order.service.PaymentCompensationReconcileService;
import com.ep.web.common.RoleChecker;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * D-222: reconciliation surface for failed Payment Saga refunds.
 * <p>
 * Read-only listing plus an explicit close action. Every endpoint requires an
 * admin role (same convention as {@code OrderController.refund}) because each
 * open row represents money the platform owes a customer. Entities are mapped
 * to {@link PaymentCompensationView} here (model must not depend on api and
 * api must not depend on model — see ArchitectureTest no-cycles rule).
 */
@RestController
@RequestMapping("/api/v1/payment-compensations")
public class PaymentCompensationController {

    private final PaymentCompensationReconcileService reconcileService;

    public PaymentCompensationController(PaymentCompensationReconcileService reconcileService) {
        this.reconcileService = reconcileService;
    }

    @GetMapping("/unresolved")
    public ResponseEntity<List<PaymentCompensationView>> listUnresolved(HttpServletRequest request) {
        RoleChecker.requireRole(request, "admin", "super_admin");
        return ResponseEntity.ok(reconcileService.listUnresolved().stream()
                .map(PaymentCompensationController::toView)
                .toList());
    }

    @PostMapping("/{failureId}/resolve")
    public ResponseEntity<PaymentCompensationView> resolve(
            @PathVariable String failureId,
            @RequestBody(required = false) Map<String, String> body,
            HttpServletRequest request) {
        RoleChecker.requireRole(request, "admin", "super_admin");
        String note = body == null ? null : body.get("note");
        return ResponseEntity.ok(toView(reconcileService.resolve(failureId, note)));
    }

    private static PaymentCompensationView toView(PaymentCompensationFailure f) {
        return new PaymentCompensationView(
                f.getFailureId(),
                f.getOrderId(),
                f.getTenantId(),
                f.getAmount(),
                f.getReason(),
                f.getFailedAt() == null ? null : f.getFailedAt().toString(),
                f.isResolved(),
                f.getResolvedNote(),
                f.getResolvedAt() == null ? null : f.getResolvedAt().toString());
    }
}
