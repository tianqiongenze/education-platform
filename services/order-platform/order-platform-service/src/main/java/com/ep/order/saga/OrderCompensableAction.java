package com.ep.order.saga;

import com.ep.order.api.OrderStatus;
import com.ep.order.event.DomainEvent;
import com.ep.order.infrastructure.PulsarEventPublisher;
import com.ep.order.model.OrderModel;
import com.ep.order.repository.OrderRepository;
import io.seata.saga.engine.StateMachineEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * D-113: Order Saga compensable action.
 *
 * <p>This bean is invoked by the Seata Saga {@link StateMachineEngine} for the
 * order-side forward and compensation steps of the {@code create-order-saga}
 * and the forward steps of the {@code cancel-order-saga} state machines.
 *
 * <h3>Saga pattern</h3>
 * The forward action {@code confirmOrder} is the final step of the create-order
 * saga: it flips the order row from PENDING to PAID and publishes the
 * {@code OrderPaidEvent}. Its compensation {@code cancelOrder} flips the order
 * to CANCELED and publishes a cancel event.
 *
 * <p>Because the order write is local to this service, the {@link OrderModel}
 * aggregate and its invariants (state machine, optimistic lock) are honoured
 * exactly as in the pre-Saga {@code OrderServiceImpl.payOrder}.
 *
 * <h3>AT mode retained for short transactions</h3>
 * Short single-service writes (e.g. {@code getOrderById}, plain
 * {@code createOrder}) still use a local {@link Transactional @Transactional}
 * boundary. AT mode is retained for short transactions where the global lock
 * duration is negligible; the Saga is only used for the long-running
 * order -> stock -> payment flow (see ADR D-113).
 */
@Component("orderCompensableAction")
public class OrderCompensableAction {

    private static final Logger log = LoggerFactory.getLogger(OrderCompensableAction.class);

    private final OrderRepository orderRepository;
    private final PulsarEventPublisher eventPublisher;

    public OrderCompensableAction(OrderRepository orderRepository,
                                  PulsarEventPublisher eventPublisher) {
        this.orderRepository = orderRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Forward action (final step of create-order saga): confirm the order by
     * flipping PENDING -> PAID and publishing the {@code OrderPaidEvent}.
     *
     * @param context the Saga state machine context (orderId, payMethod)
     * @return a result map inserted back into the Saga context under
     *         {@code confirmOrderResult}
     * @throws IllegalStateException if the order is not in PENDING status
     *         (mirrors {@link OrderModel#pay})
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> confirmOrder(Map<String, Object> context) {
        Objects.requireNonNull(context, "saga context must not be null");
        Long orderId = asLong(context.get("orderId"));
        String payMethod = (String) context.get("payMethod");
        OrderModel order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalStateException(
                        "D-113: saga confirmOrder order not found: " + orderId));
        order.pay(payMethod);
        orderRepository.save(order);
        publishEvents(order);
        log.info("D-113: saga confirmOrder orderId={} -> PAID via {}",
                orderId, payMethod);
        return Map.of(
                "orderId", orderId,
                "status", String.valueOf(OrderStatus.PAID.getCode()));
    }

    /**
     * Compensation action for {@link #confirmOrder(Map)} (and the first forward
     * step of the cancel-order saga): mark the order CANCELED.
     *
     * <p>Idempotent: if the order is already CANCELED this is a no-op. If the
     * order is PAID the refund is handled by the Payment compensation, so here
     * we only flip the status. PENDING orders are also flippable to CANCELED.
     *
     * @param context the Saga state machine context (orderId, reason)
     * @return a result map describing the cancellation
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> cancelOrder(Map<String, Object> context) {
        Objects.requireNonNull(context, "saga context must not be null");
        Long orderId = asLong(context.get("orderId"));
        String reason = (String) context.get("reason");
        OrderModel order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalStateException(
                        "D-113: saga cancelOrder order not found: " + orderId));
        int current = order.getStatus() == null ? -1 : order.getStatus();
        if (current == OrderStatus.CANCELED.getCode()) {
            log.info("D-113: saga cancelOrder orderId={} already CANCELED (idempotent)", orderId);
            return Map.of("orderId", orderId, "status", String.valueOf(OrderStatus.CANCELED.getCode()));
        }
        // Direct status flip (no aggregate method for the cancel transition; the
        // pre-Saga code only had pay/refund state transitions). Optimistic lock
        // column protects against concurrent writes.
        order.setStatus(OrderStatus.CANCELED.getCode());
        order.setUpdatedAt(System.currentTimeMillis());
        orderRepository.save(order);
        publishEvents(order);
        log.info("D-113: saga cancelOrder orderId={} -> CANCELED reason={}", orderId, reason);
        return Map.of(
                "orderId", orderId,
                "status", String.valueOf(OrderStatus.CANCELED.getCode()));
    }

    private void publishEvents(OrderModel order) {
        List<DomainEvent> events = order.pullEvents();
        for (DomainEvent event : events) {
            log.info("D-113: saga domain event recorded: {}", event);
            eventPublisher.publish(event);
        }
    }

    /** Coerce a Saga context value to {@link Long}. */
    private static Long asLong(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Long l) {
            return l;
        }
        if (raw instanceof Number n) {
            return n.longValue();
        }
        return Long.valueOf(raw.toString());
    }
}
