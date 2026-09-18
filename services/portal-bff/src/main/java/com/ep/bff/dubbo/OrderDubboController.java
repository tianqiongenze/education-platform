package com.ep.bff.dubbo;

import com.ep.order.api.CreateOrderCmd;
import com.ep.order.api.OrderDto;
import com.ep.order.api.OrderService;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * BFF 聚合控制器 — 订单域.
 * D-032: @DubboReference 调用 core-business OrderService.
 * 创单/支付/退款涉及状态机, 使用较长 timeout (5s) 防止抖动误判.
 *
 * D-070: Trace correlation — before each @DubboReference invocation, the MDC traceId
 * (populated by com.ep.bff.config.TraceIdFilter) must be propagated to the Dubbo RPC
 * context via com.ep.bff.config.TracePropagationUtil.propagateToRpc(), so downstream
 * core-business services can pick up the same traceId from RpcContext and bind it into their MDC.
 *
 * D-090: IDOR hardening — the trusted {@code X-User-Id} / {@code X-Role} headers
 * injected by {@link com.ep.bff.filter.AuthFilter} (derived from the verified JWT,
 * with any client-supplied forged values stripped) are now consulted to enforce
 * resource ownership on every read/write path. Admin ({@code X-Role=ADMIN}) bypasses
 * the owner check on reads and may issue refunds; non-admin callers receive 403 on
 * any resource they do not own.
 */
@RestController
@RequestMapping("/api/v1/orders")
public class OrderDubboController {

    /** Trusted request header populated by AuthFilter from the verified JWT subject. */
    static final String HDR_USER_ID = "X-User-Id";
    /** Trusted request header populated by AuthFilter from the verified JWT role claim. */
    static final String HDR_ROLE = "X-Role";
    /** Role value that bypasses ownership checks (admin). */
    static final String ROLE_ADMIN = "ADMIN";

    @DubboReference(check = false, timeout = 10000)
    private OrderService orderService;

    @PostMapping
    public ResponseEntity<OrderDto> create(@RequestBody CreateOrderCmd cmd) {
        return ResponseEntity.ok(orderService.createOrder(cmd));
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrderDto> getById(@PathVariable Long id, HttpServletRequest request) {
        OrderDto order = orderService.getOrderById(id);
        if (order == null) {
            return ResponseEntity.notFound().build();
        }
        if (!checkOwnership(order.userId(), request)) {
            return ResponseEntity.status(403).build();
        }
        return ResponseEntity.ok(order);
    }

    @GetMapping("/by-user-course")
    public ResponseEntity<OrderDto> getByUserAndCourse(@RequestParam Long userId,
                                                    @RequestParam Long courseId,
                                                    HttpServletRequest request) {
        if (!checkOwnership(userId, request)) {
            return ResponseEntity.status(403).build();
        }
        OrderDto order = orderService.getOrderByUserAndCourse(userId, courseId);
        return order == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(order);
    }

    @GetMapping("/user/{userId}")
    public ResponseEntity<List<OrderDto>> listUserOrders(@PathVariable Long userId,
                                                      @RequestParam(required = false) Integer page,
                                                      @RequestParam(required = false) Integer size,
                                                      HttpServletRequest request) {
        if (!checkOwnership(userId, request)) {
            return ResponseEntity.status(403).build();
        }
        return ResponseEntity.ok(orderService.listUserOrders(userId, page, size));
    }

    @PostMapping("/{id}/pay")
    public ResponseEntity<Void> pay(@PathVariable Long id, @RequestBody Map<String, String> body,
                                    HttpServletRequest request) {
        OrderDto order = orderService.getOrderById(id);
        if (order == null) {
            return ResponseEntity.notFound().build();
        }
        if (!checkOwnership(order.userId(), request)) {
            return ResponseEntity.status(403).build();
        }
        String payMethod = body == null ? null : body.get("payMethod");
        orderService.payOrder(id, payMethod);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/refund")
    public ResponseEntity<Void> refund(@PathVariable Long id, @RequestBody Map<String, String> body,
                                       HttpServletRequest request) {
        if (!isAdmin(request)) {
            return ResponseEntity.status(403).build();
        }
        String reason = body == null ? null : body.get("reason");
        orderService.refundOrder(id, reason);
        return ResponseEntity.noContent().build();
    }

    // --------------------- IDOR helpers (D-090) ---------------------

    /**
     * Verify the requesting user owns the resource, or is an admin.
     * <p>
     * Reads the trusted {@code X-User-Id} / {@code X-Role} headers that
     * {@link com.ep.bff.filter.AuthFilter} injected from the verified JWT. A
     * missing {@code X-User-Id} (unauthenticated request that slipped past the
     * filter) is treated as a denial.
     *
     * @param resourceUserId the owner of the resource being accessed
     * @param request        the HTTP request carrying the trusted headers
     * @return {@code true} if access is allowed (owner or admin), {@code false} otherwise
     */
    static boolean checkOwnership(Long resourceUserId, HttpServletRequest request) {
        if (resourceUserId == null) {
            return false;
        }
        String role = request.getHeader(HDR_ROLE);
        if (ROLE_ADMIN.equalsIgnoreCase(role)) {
            return true;
        }
        String userIdHeader = request.getHeader(HDR_USER_ID);
        if (userIdHeader == null || userIdHeader.isBlank()) {
            return false;
        }
        try {
            return resourceUserId.equals(Long.valueOf(userIdHeader.trim()));
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** Whether the requester has the ADMIN role. */
    static boolean isAdmin(HttpServletRequest request) {
        return ROLE_ADMIN.equalsIgnoreCase(request.getHeader(HDR_ROLE));
    }
}
