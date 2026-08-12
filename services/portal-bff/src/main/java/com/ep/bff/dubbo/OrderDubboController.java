package com.ep.bff.dubbo;

import com.ep.order.api.CreateOrderCmd;
import com.ep.order.api.OrderDto;
import com.ep.order.api.OrderService;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * BFF 聚合控制器 — 订单域.
 * D-032: @DubboReference 调用 SP-03 OrderService.
 * 创单/支付/退款涉及状态机, 使用较长 timeout (5s) 防止抖动误判.
 *
 * D-070: Trace correlation — before each @DubboReference invocation, the MDC traceId
 * (populated by com.ep.bff.config.TraceIdFilter) must be propagated to the Dubbo RPC
 * context via com.ep.bff.config.TracePropagationUtil.propagateToRpc(), so downstream
 * SP-03 services can pick up the same traceId from RpcContext and bind it into their MDC.
 */
@RestController
@RequestMapping("/api/v1/orders")
public class OrderDubboController {

    @DubboReference(check = false, timeout = 10000)
    private OrderService orderService;

    @PostMapping
    public ResponseEntity<OrderDto> create(@RequestBody CreateOrderCmd cmd) {
        return ResponseEntity.ok(orderService.createOrder(cmd));
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrderDto> getById(@PathVariable Long id) {
        OrderDto order = orderService.getOrderById(id);
        return order == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(order);
    }

    @GetMapping("/by-user-course")
    public ResponseEntity<OrderDto> getByUserAndCourse(@RequestParam Long userId,
                                                    @RequestParam Long courseId) {
        OrderDto order = orderService.getOrderByUserAndCourse(userId, courseId);
        return order == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(order);
    }

    @GetMapping("/user/{userId}")
    public ResponseEntity<List<OrderDto>> listUserOrders(@PathVariable Long userId,
                                                      @RequestParam(required = false) Integer page,
                                                      @RequestParam(required = false) Integer size) {
        return ResponseEntity.ok(orderService.listUserOrders(userId, page, size));
    }

    @PostMapping("/{id}/pay")
    public ResponseEntity<Void> pay(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String payMethod = body == null ? null : body.get("payMethod");
        orderService.payOrder(id, payMethod);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/refund")
    public ResponseEntity<Void> refund(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String reason = body == null ? null : body.get("reason");
        orderService.refundOrder(id, reason);
        return ResponseEntity.noContent().build();
    }
}
