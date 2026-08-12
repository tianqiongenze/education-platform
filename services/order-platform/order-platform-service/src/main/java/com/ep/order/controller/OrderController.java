package com.ep.order.controller;

import com.ep.order.api.CreateOrderCmd;
import com.ep.order.api.OrderDto;
import com.ep.order.api.OrderService;
import com.ep.web.common.RoleChecker;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * REST fa\u00e7ade for the SP-03 Order middle platform.
 * <p>
 * Mirrors the legacy {@code /api/v1/orders} surface from core-business so the
 * BFF and existing clients keep working; delegates to the Dubbo-exposed
 * {@link OrderService}.
 */
@RestController
@RequestMapping("/api/v1/orders")
@Validated
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    public ResponseEntity<OrderDto> create(@Valid @RequestBody CreateOrderCmd cmd) {
        return ResponseEntity.ok(orderService.createOrder(cmd));
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<OrderDto> getById(@PathVariable Long orderId) {
        OrderDto order = orderService.getOrderById(orderId);
        return order == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(order);
    }

    @PostMapping("/{orderId}/pay")
    public ResponseEntity<Void> pay(@PathVariable Long orderId,
                                    @RequestBody(required = false) Map<String, String> body) {
        String payMethod = body == null ? null : body.get("payMethod");
        orderService.payOrder(orderId, payMethod);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{orderId}/refund")
    public ResponseEntity<Void> refund(@PathVariable Long orderId,
                                       @RequestBody(required = false) Map<String, String> body,
                                       HttpServletRequest request) {
        RoleChecker.requireRole(request, "admin", "super_admin");
        String reason = body == null ? null : body.get("reason");
        orderService.refundOrder(orderId, reason);
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    public ResponseEntity<List<OrderDto>> listUserOrders(@RequestParam Long userId,
                                                         @RequestParam(defaultValue = "1") Integer page,
                                                         @RequestParam(defaultValue = "10") Integer size) {
        Objects.requireNonNull(userId, "userId must not be null");
        return ResponseEntity.ok(orderService.listUserOrders(userId, page, size));
    }
}
