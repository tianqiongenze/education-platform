package com.ep.order.api;

import java.util.List;

/**
 * SP-03 Order middle-platform Dubbo RPC contract.
 * <p>
 * Implementations live in {@code order-platform-service} and are exposed over
 * Dubbo (port 20884). Consumers (BFF, other middle platforms) reference this
 * interface via {@code @DubboReference} and depend only on this thin API JAR.
 */
public interface OrderService {

    /** Create a single order row (branch 1 of the global-tx flow). */
    OrderDto createOrder(CreateOrderCmd cmd);

    /**
     * Two-branch flow wrapped in a single Seata global transaction:
     * branch 1 inserts the order row, branch 2 deducts inventory.
     * On any branch failure the whole XID rolls back via AT undo logs.
     */
    OrderDto createOrderWithGlobalTx(CreateOrderCmd cmd, int stockUnits);

    OrderDto getOrderById(Long orderId);

    OrderDto getOrderByUserAndCourse(Long userId, Long courseId);

    void payOrder(Long orderId, String payMethod);

    void refundOrder(Long orderId, String reason);

    List<OrderDto> listUserOrders(Long userId, Integer page, Integer size);
}
