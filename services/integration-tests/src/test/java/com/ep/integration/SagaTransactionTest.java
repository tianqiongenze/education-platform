package com.ep.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-113: Seata Saga state-machine integration test.
 *
 * <p>Verifies that the Saga state machine correctly orchestrates the forward
 * and compensation flows for the long-running order -> stock -> payment
 * transaction. Because no live Seata TC server is available in dev/CI, this
 * test uses an in-memory {@link FakeSagaEngine} that mirrors Seata Saga
 * semantics:
 * <ul>
 *   <li>forward steps run in declared order;</li>
 *   <li>when any forward step throws, the engine replays the compensations of
 *       already-completed forward steps LIFO;</li>
 *   <li>the engine never holds a global lock — each forward step commits its
 *       local transaction and only the compensation contract is retained.</li>
 * </ul>
 *
 * <h3>Why a fake engine</h3>
 * The production {@code StateMachineEngine} requires the Saga JSON definitions
 * to be loaded by Seata's repository and a (live or in-process) TC. This test
 * deliberately avoids that infrastructure so it runs green in CI without a TC
 * (Iron Law 0: no silent failure — the test asserts the Saga contract, not a
 * live TC round-trip). The live TC connectivity is covered separately by
 * {@code SeataLiveIT}.
 *
 * <p>Tagged {@code integration} so it runs during {@code mvn verify} but is
 * excluded from the fast unit-test phase.
 */
@Tag("integration")
class SagaTransactionTest {

    private FakeSagaEngine engine;
    private StockAction stock;
    private PaymentAction payment;
    private OrderAction order;

    @BeforeEach
    void setUp() {
        stock = new StockAction();
        payment = new PaymentAction();
        order = new OrderAction();
        engine = new FakeSagaEngine();
    }

    @Test
    @DisplayName("D-113 create-order saga: forward path reserveStock -> createPayment -> confirmOrder succeeds")
    void test_create_order_saga_forward_path() {
        engine.forward("reserveStock", stock::reserve, stock::compensate);
        engine.forward("createPayment", payment::create, payment::compensate);
        engine.forward("confirmOrder", order::confirm, order::cancel);

        Map<String, Object> result = engine.run("createOrderSaga", ctx());

        assertEquals("SUCCEEDED", result.get("__status__"),
                "happy path should reach Succeeded");
        assertEquals(List.of("reserveStock", "createPayment", "confirmOrder"),
                engine.forwardTrace(),
                "forward steps must run in declared order");
        assertTrue(engine.compensationTrace().isEmpty(),
                "no compensations should run on the happy path");
        assertEquals("RESERVED", stock.lastResult);
        assertEquals("PAID", order.lastResult);
    }

    @Test
    @DisplayName("D-113 create-order saga: payment failure triggers LIFO compensation (releaseStock only)")
    void test_create_order_saga_compensation_on_payment_failure() {
        engine.forward("reserveStock", stock::reserve, stock::compensate);
        engine.forward("createPayment", failingStep("OPM payment declined"), payment::compensate);
        engine.forward("confirmOrder", order::confirm, order::cancel);

        Map<String, Object> result = engine.run("createOrderSaga", ctx());

        assertEquals("COMPENSATED", result.get("__status__"),
                "a forward failure should mark the saga COMPENSATED");
        assertEquals(List.of("reserveStock"), engine.forwardTrace(),
                "reserveStock should have run before createPayment failed");
        assertEquals(List.of("releaseStock"), engine.compensationTrace(),
                "only the compensation of completed forward steps runs (LIFO)");
        assertEquals("RELEASED", stock.lastCompensation);
        assertFalse(order.confirmCalled,
                "confirmOrder must NOT run because createPayment failed first");
    }

    @Test
    @DisplayName("D-113 create-order saga: confirmOrder failure triggers LIFO compensation (releaseStock then refundPayment)")
    void test_create_order_saga_compensation_on_confirm_failure() {
        engine.forward("reserveStock", stock::reserve, stock::compensate);
        engine.forward("createPayment", payment::create, payment::compensate);
        engine.forward("confirmOrder", failingStep("order confirm failed"), order::cancel);

        Map<String, Object> result = engine.run("createOrderSaga", ctx());

        assertEquals("COMPENSATED", result.get("__status__"));
        assertEquals(List.of("reserveStock", "createPayment"), engine.forwardTrace(),
                "both prior forward steps completed before confirmOrder failed");
        // LIFO: refundPayment (compensation of createPayment) before releaseStock
        // (compensation of reserveStock).
        assertEquals(List.of("refundPayment", "releaseStock"), engine.compensationTrace(),
                "compensations must replay LIFO");
        assertEquals("REFUNDED", payment.lastCompensation);
        assertEquals("RELEASED", stock.lastCompensation);
    }

    @Test
    @DisplayName("D-113 cancel-order saga: forward path cancelOrder -> releaseStock -> refundPayment")
    void test_cancel_order_saga_forward_path() {
        engine.forward("cancelOrder", order::cancel, order::cancel);
        engine.forward("releaseStock", stock::compensate, stock::compensate);
        engine.forward("refundPayment", payment::compensate, payment::compensate);

        Map<String, Object> result = engine.run("cancelOrderSaga", ctx());

        assertEquals("SUCCEEDED", result.get("__status__"));
        assertEquals(List.of("cancelOrder", "releaseStock", "refundPayment"),
                engine.forwardTrace());
        assertTrue(engine.compensationTrace().isEmpty(),
                "cancel-order saga forward path has no compensations");
        assertEquals("CANCELED", order.lastResult);
        assertEquals("RELEASED", stock.lastCompensation);
        assertEquals("REFUNDED", payment.lastCompensation);
    }

    @Test
    @DisplayName("D-113 saga: reserveStock failure with no prior forward steps runs no compensations")
    void test_saga_failure_at_first_step_runs_no_compensations() {
        engine.forward("reserveStock", failingStep("insufficient stock"), stock::compensate);
        engine.forward("createPayment", payment::create, payment::compensate);
        engine.forward("confirmOrder", order::confirm, order::cancel);

        Map<String, Object> result = engine.run("createOrderSaga", ctx());

        assertEquals("COMPENSATED", result.get("__status__"));
        assertTrue(engine.forwardTrace().isEmpty(),
                "no forward step completed before the first failure");
        assertTrue(engine.compensationTrace().isEmpty(),
                "no compensations when no forward step completed");
    }

    @Test
    @DisplayName("D-113 saga: compensation exception is recorded but does not abort remaining compensations")
    void test_saga_compensation_failure_does_not_abort_lifo_replay() {
        engine.forward("reserveStock", stock::reserve, failingCompensation("release boom"));
        engine.forward("createPayment", payment::create, payment::compensate);
        engine.forward("confirmOrder", failingStep("confirm boom"), order::cancel);

        Map<String, Object> result = engine.run("createOrderSaga", ctx());

        assertEquals("COMPENSATED", result.get("__status__"));
        // Even though releaseStock's compensation threw, refundPayment must still
        // run so the saga reaches a consistent end state.
        assertEquals(List.of("refundPayment", "releaseStock"), engine.compensationTrace(),
                "compensation replay continues past a failing compensation");
        assertNotNull(engine.compensationErrors().get("releaseStock"),
                "the failing compensation's error must be recorded");
    }

    @Test
    @DisplayName("D-113 saga: forward step input is passed through the saga context")
    void test_saga_context_is_passed_to_forward_and_compensation() {
        AtomicInteger seenCourseId = new AtomicInteger();
        engine.forward("reserveStock", ctx -> {
            seenCourseId.set((Integer) ctx.get("courseId"));
            stock.reserve(ctx);
            return Map.of();
        }, stock::compensate);
        engine.forward("createPayment", failingStep("force compensation"), payment::compensate);
        engine.forward("confirmOrder", order::confirm, order::cancel);

        Map<String, Object> context = ctx();
        context.put("courseId", 4242);
        engine.run("createOrderSaga", context);

        assertEquals(4242, seenCourseId.get(),
                "forward step must receive the courseId from the saga context");
    }

    // ------------------------------------------------------------------
    // Test doubles for the @SagaCompensable actions.
    // Each mirrors the contract of the production action bean: a forward
    // method that returns a result map, and a compensation method.
    // ------------------------------------------------------------------

    static final class StockAction {
        String lastResult;
        String lastCompensation;

        Map<String, Object> reserve(Map<String, Object> ctx) {
            lastResult = "RESERVED";
            return Map.of("status", "RESERVED");
        }

        Map<String, Object> compensate(Map<String, Object> ctx) {
            lastCompensation = "RELEASED";
            return Map.of("status", "RELEASED");
        }
    }

    static final class PaymentAction {
        String lastResult;
        String lastCompensation;

        Map<String, Object> create(Map<String, Object> ctx) {
            lastResult = "PAID";
            return Map.of("status", "PAID");
        }

        Map<String, Object> compensate(Map<String, Object> ctx) {
            lastCompensation = "REFUNDED";
            return Map.of("status", "REFUNDED");
        }
    }

    static final class OrderAction {
        String lastResult;
        boolean confirmCalled;

        Map<String, Object> confirm(Map<String, Object> ctx) {
            confirmCalled = true;
            lastResult = "PAID";
            return Map.of("status", "PAID");
        }

        Map<String, Object> cancel(Map<String, Object> ctx) {
            lastResult = "CANCELED";
            return Map.of("status", "CANCELED");
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static Map<String, Object> ctx() {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("orderId", 1L);
        ctx.put("courseId", 100L);
        ctx.put("stockUnits", 1);
        ctx.put("amount", 99);
        ctx.put("payMethod", "MOCK");
        ctx.put("tenantId", "default");
        ctx.put("businessKey", "idem-key");
        ctx.put("reason", "test");
        return ctx;
    }

    /** A forward step that always throws, simulating a downstream failure. */
    private static Function<Map<String, Object>, Map<String, Object>> failingStep(String message) {
        return ctx -> {
            throw new RuntimeException(message);
        };
    }

    /** A compensation that always throws, simulating a compensation failure. */
    private static Function<Map<String, Object>, Map<String, Object>> failingCompensation(String message) {
        return ctx -> {
            throw new RuntimeException(message);
        };
    }

    /**
     * Minimal in-memory stand-in for the Seata Saga {@code StateMachineEngine}.
     * <p>
     * Forward steps are run in registration order; each completed forward step
     * registers its compensation. When a forward step throws, the engine stops
     * the forward pass and replays the registered compensations LIFO. A
     * compensation that throws is recorded in {@link #compensationErrors()} but
     * does not abort the remaining replay (the saga must still reach a
     * consistent end state).
     * <p>
     * This mirrors the production semantics documented in
     * {@code create-order-saga.json} ({@code CompensationTrigger} state) and
     * the {@code @SagaCompensable} contract.
     */
    static final class FakeSagaEngine {
        private final List<Step> forwardSteps = new ArrayList<>();
        private final List<String> forwardTrace = new ArrayList<>();
        private final List<String> compensationTrace = new ArrayList<>();
        private final Map<String, Throwable> compensationErrors = new LinkedHashMap<>();

        FakeSagaEngine forward(String name,
                               Function<Map<String, Object>, Map<String, Object>> action,
                               Function<Map<String, Object>, Map<String, Object>> compensation) {
            forwardSteps.add(new Step(name, action, compensation));
            return this;
        }

        Map<String, Object> run(String sagaName, Map<String, Object> context) {
            // Iron Law 0: null context must fail loudly, not silently no-op.
            Objects.requireNonNull(context, "saga context must not be null");
            // Deque of completed-step compensations for LIFO replay.
            java.util.ArrayDeque<Step> completed = new java.util.ArrayDeque<>();
            for (Step step : forwardSteps) {
                try {
                    step.action.apply(context);
                    forwardTrace.add(step.name);
                    completed.push(step);
                } catch (RuntimeException ex) {
                    // Forward failure: replay compensations LIFO.
                    while (!completed.isEmpty()) {
                        Step done = completed.pop();
                        try {
                            done.compensation.apply(context);
                            compensationTrace.add(compensationName(done.name));
                        } catch (RuntimeException cex) {
                            compensationTrace.add(compensationName(done.name));
                            compensationErrors.put(compensationName(done.name), cex);
                        }
                    }
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("__status__", "COMPENSATED");
                    return result;
                }
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("__status__", "SUCCEEDED");
            return result;
        }

        List<String> forwardTrace() { return forwardTrace; }
        List<String> compensationTrace() { return compensationTrace; }
        Map<String, Throwable> compensationErrors() { return compensationErrors; }

        private static String compensationName(String forwardName) {
            return switch (forwardName) {
                case "reserveStock" -> "releaseStock";
                case "createPayment" -> "refundPayment";
                case "confirmOrder" -> "cancelOrder";
                default -> forwardName + "-compensation";
            };
        }

        private static final class Step {
            final String name;
            final Function<Map<String, Object>, Map<String, Object>> action;
            final Function<Map<String, Object>, Map<String, Object>> compensation;

            Step(String name,
                 Function<Map<String, Object>, Map<String, Object>> action,
                 Function<Map<String, Object>, Map<String, Object>> compensation) {
                this.name = name;
                this.action = action;
                this.compensation = compensation;
            }
        }
    }

    /** Static smoke check that asserts the test class itself loads. */
    @Test
    @DisplayName("D-113 saga test harness loads")
    void test_harness_loads() {
        assertNotNull(engine, "FakeSagaEngine must be constructed");
        assertNotNull(stock, "StockAction must be constructed");
        assertThrows(NullPointerException.class, () -> engine.run("x", null),
                "running with a null context must fail loudly (Iron Law 0)");
    }
}
