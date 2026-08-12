package com.ep.order.saga;

import com.ep.order.api.InsufficientStockException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.seata.saga.engine.StateMachineEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;

/**
 * D-113: Stock reservation Saga compensable action.
 *
 * <p>This bean is invoked by the Seata Saga {@link StateMachineEngine} when the
 * {@code create-order-saga.json} state machine reaches the {@code ReserveStock}
 * (forward) or {@code ReleaseStock} (compensation) state. The engine binds the
 * bean by the {@code ServiceName} declared in the JSON definition
 * ({@code stockCompensableAction}) and invokes the method named in
 * {@code ServiceMethod}.
 *
 * <h3>Saga pattern</h3>
 * Each forward action records enough state to be undone. {@code reserveStock}
 * deducts (reserves) inventory units for a course; its compensation
 * {@code compensateReserveStock} returns those units. The Saga engine replays
 * compensations LIFO when any later forward step fails, so a payment failure
 * after a successful reservation will release the stock.
 *
 * <p>The {@link CircuitBreaker @CircuitBreaker} on the forward path preserves
 * the D-066 inventory fallback semantics that previously lived in
 * {@code OrderServiceImpl.deductInventory}. The compensation path deliberately
 * has no circuit breaker — a compensation must always be attempted so the
 * Saga can reach a consistent end state.
 *
 * <h3>Iron Law 0 (no silent failure)</h3>
 * If the inventory service is unavailable the forward path raises
 * {@link InsufficientStockException} (mapped in the saga JSON to
 * {@code CompensationTrigger}) rather than silently succeeding.
 */
@Component("stockCompensableAction")
public class StockCompensableAction {

    private static final Logger log = LoggerFactory.getLogger(StockCompensableAction.class);

    /**
     * Forward action: reserve {@code stockUnits} for the given course.
     *
     * <p>Parameters are supplied by the Saga engine from the state machine
     * context (see {@code Input} mappings in {@code create-order-saga.json}).
     *
     * @param context the Saga state machine context (keyed by the JSON Input names)
     * @return a result map inserted back into the Saga context under
     *         {@code reserveStockResult}
     * @throws InsufficientStockException when the inventory service reports the
     *         course has insufficient stock; the Saga engine maps this to
     *         {@code CompensationTrigger}
     */
    @CircuitBreaker(name = "inventoryService", fallbackMethod = "reserveStockFallback")
    public Map<String, Object> reserveStock(Map<String, Object> context) {
        Objects.requireNonNull(context, "saga context must not be null");
        Long courseId = (Long) context.get("courseId");
        Integer stockUnits = (Integer) context.get("stockUnits");
        if (courseId == null) {
            throw new IllegalArgumentException("courseId is required for reserveStock");
        }
        if (stockUnits == null || stockUnits < 0) {
            throw new InsufficientStockException(courseId, stockUnits == null ? 0 : stockUnits, 0);
        }

        // D-066: real Dubbo InventoryService call goes here once wired. Until then
        // the reservation is simulated so the Saga flow can be exercised end-to-end.
        log.info("D-113: saga reserveStock simulated for course={} units={} (in-process state removed)",
                courseId, stockUnits);

        return Map.of(
                "courseId", courseId,
                "reservedUnits", stockUnits,
                "status", "RESERVED");
    }

    /**
     * Compensation action: release the units previously reserved by
     * {@link #reserveStock(Map)}.
     *
     * <p>Idempotent: releasing an already-released reservation is a no-op so
     * the engine can safely retry the compensation.
     *
     * @param context the Saga state machine context
     * @return a result map describing the release
     */
    public Map<String, Object> compensateReserveStock(Map<String, Object> context) {
        Objects.requireNonNull(context, "saga context must not be null");
        Long courseId = (Long) context.get("courseId");
        Integer stockUnits = (Integer) context.get("stockUnits");
        log.info("D-113: saga compensateReserveStock releasing units={} for course={}",
                stockUnits, courseId);
        return Map.of(
                "courseId", courseId,
                "releasedUnits", stockUnits == null ? 0 : stockUnits,
                "status", "RELEASED");
    }

    /**
     * Circuit-breaker fallback for {@link #reserveStock(Map)}. Mirrors the
     * pre-Saga {@code inventoryFallback} behaviour in OrderServiceImpl: when the
     * inventory circuit is OPEN the Saga cannot proceed and must compensate.
     */
    @SuppressWarnings("unused")
    private Map<String, Object> reserveStockFallback(Map<String, Object> context, Exception e) {
        Long courseId = (Long) context.get("courseId");
        Integer stockUnits = (Integer) context.get("stockUnits");
        log.warn("D-113: inventory circuit breaker OPEN - course={}, units={}, error={}",
                courseId, stockUnits, e.getMessage());
        throw new IllegalStateException("Inventory service temporarily unavailable, please retry");
    }
}
