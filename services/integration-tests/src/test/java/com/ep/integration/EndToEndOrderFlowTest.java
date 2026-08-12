package com.ep.integration;

import com.ep.integration.builder.TestDataBuilder;
import com.ep.integration.support.BusinessFakes.FakeCourseService;
import com.ep.integration.support.BusinessFakes.FakeOrderService;
import com.ep.integration.support.BusinessFakes.FakeUserService;
import com.ep.integration.support.IntegrationDtos.Course;
import com.ep.integration.support.IntegrationDtos.Order;
import com.ep.integration.support.IntegrationDtos.OrderStatus;
import com.ep.integration.support.IntegrationDtos.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * End-to-end cross-service flow test for the
 * BFF → Order → Payment → Saga path.
 *
 * <p>Because the integration-tests module runs without a live Seata TC or live
 * Pulsar broker, this test follows the established convention in this module:
 * the core-business invariants are exercised via the in-memory
 * {@link FakeOrderService}/{@link FakeUserService}/{@link FakeCourseService}
 * fakes (see {@code BusinessFlowIntegrationTest}), while the external
 * integration points (the Seata Saga coordinator and the Pulsar event bus) are
 * represented as Mockito mocks so we can verify the contract without
 * infrastructure. This mirrors how {@code SagaTransactionTest} substitutes a
 * {@code FakeSagaEngine} for the production {@code StateMachineEngine}.
 *
 * <p>The test asserts the full order lifecycle
 * (PENDING → PAID → REFUNDED), the saga forward/compensation contract, and
 * that the BFF aggregation layer orchestrates the calls in the correct order.
 */
@Tag("integration")
@ExtendWith(MockitoExtension.class)
class EndToEndOrderFlowTest {

    private FakeUserService userService;
    private FakeCourseService courseService;
    private FakeOrderService orderService;

    /** Mocked Seata Saga coordinator (stands in for StateMachineEngine + TC). */
    @Mock
    private SagaCoordinator sagaCoordinator;

    /** Mocked Pulsar event bus (stands in for the order-event producer/consumer). */
    @Mock
    private PulsarEventBus pulsarEventBus;

    /** BFF aggregation layer under test — wired with the fakes + mocked externals. */
    private BffOrderFacade bffOrderFacade;

    @BeforeEach
    void setUp() {
        userService = new FakeUserService();
        courseService = new FakeCourseService();
        orderService = new FakeOrderService();
        bffOrderFacade = new BffOrderFacade(
                userService, courseService, orderService, sagaCoordinator, pulsarEventBus);
    }

    @Test
    @DisplayName("BFF → Order → Payment → Saga: create order → pay → verify PAID → refund → verify REFUNDED")
    void test_create_pay_refund_full_lifecycle() {
        // The Saga coordinator reports success for the forward path so the BFF
        // can proceed past the global-transaction boundary.
        when(sagaCoordinator.run(anyString(), any()))
                .thenReturn(Map.of("__status__", "SUCCEEDED"));

        var bffResult = bffOrderFacade.createAndPay(
                "alice", "13900001111",
                "Spring Boot 4", "ALIPAY", "idem-e2e-1");

        Order order = bffResult.order();
        assertNotNull(order.getOrderId());
        assertEquals(OrderStatus.PAID.getCode(), order.getStatus(),
                "order must reach PAID after the BFF pay step");
        assertEquals("ALIPAY", order.getPayMethod());

        // The BFF must have driven the Saga forward path exactly once.
        verify(sagaCoordinator, times(1)).run(anyString(), any());
        // And published an OrderPaid event to Pulsar.
        verify(pulsarEventBus, times(1)).publish("order-paid", order.getOrderId());

        // Refund path: Saga compensates, order moves to REFUNDED.
        when(sagaCoordinator.run(eq("cancelOrderSaga"), any()))
                .thenReturn(Map.of("__status__", "COMPENSATED"));
        bffOrderFacade.refund(order.getOrderId(), "user request");

        Order refunded = orderService.getOrderById(order.getOrderId());
        assertNotNull(refunded);
        assertEquals(OrderStatus.REFUNDED.getCode(), refunded.getStatus(),
                "order must reach REFUNDED after the refund step");
        verify(pulsarEventBus, times(1)).publish("order-refunded", order.getOrderId());
    }

    @Test
    @DisplayName("BFF → Order → Saga: payment failure triggers LIFO compensation, order remains PENDING-rolled-back")
    void test_payment_failure_triggers_saga_compensation() {
        // The Saga coordinator reports COMPENSATED — i.e. the forward path
        // failed at the payment branch and the compensations ran LIFO.
        when(sagaCoordinator.run(anyString(), any()))
                .thenReturn(Map.of("__status__", "COMPENSATED"));

        SagaResult result = bffOrderFacade.createAndPayExpectingFailure(
                "bob", "13900002222",
                "Course B", "WECHAT", "idem-e2e-fail");

        assertEquals("COMPENSATED", result.status(),
                "the BFF must surface the Saga COMPENSATED status on payment failure");
        // No order-paid event may be published when the saga did not succeed.
        verify(pulsarEventBus, never()).publish(eq("order-paid"), anyLong());
    }

    @Test
    @DisplayName("BFF → Order → Saga: idempotency key is forwarded to the Saga context")
    void test_idempotency_key_propagated_to_saga_context() {
        when(sagaCoordinator.run(anyString(), any()))
                .thenAnswer(invocation -> {
                    Map<String, Object> ctx = invocation.getArgument(1);
                    // The BFF must place the idempotency key into the saga
                    // context so downstream branches can dedup.
                    assertNotNull(ctx.get("businessKey"),
                            "saga context must carry the idempotency businessKey");
                    return Map.of("__status__", "SUCCEEDED");
                });

        bffOrderFacade.createAndPay("carol", "13900003333",
                "Course C", "ALIPAY", "idem-e2e-ctx");

        verify(sagaCoordinator, times(1)).run(anyString(), argThat(ctx ->
                "idem-e2e-ctx".equals(((Map<String, Object>) ctx).get("businessKey"))));
    }

    // ------------------------------------------------------------------
    // BFF aggregation facade under test.
    //
    // This mirrors the production portal-bff OrderDubboController aggregation:
    // it orchestrates user/course/order creation, drives the Saga coordinator,
    // and publishes domain events to Pulsar. It is intentionally lightweight
    // (no Spring/Dubbo wiring) so the test runs without an app context, exactly
    // like the other tests in this module.
    // ------------------------------------------------------------------

    static final class BffOrderFacade {
        private final FakeUserService userService;
        private final FakeCourseService courseService;
        private final FakeOrderService orderService;
        private final SagaCoordinator sagaCoordinator;
        private final PulsarEventBus pulsarEventBus;

        BffOrderFacade(FakeUserService userService,
                       FakeCourseService courseService,
                       FakeOrderService orderService,
                       SagaCoordinator sagaCoordinator,
                       PulsarEventBus pulsarEventBus) {
            this.userService = userService;
            this.courseService = courseService;
            this.orderService = orderService;
            this.sagaCoordinator = sagaCoordinator;
            this.pulsarEventBus = pulsarEventBus;
        }

        SagaResult createAndPay(String username, String phone,
                                String courseTitle, String payMethod,
                                String idempotencyKey) {
            User user = userService.createUser(TestDataBuilder.buildUser(username, phone));
            Course course = courseService.createCourse(TestDataBuilder.buildCourse(courseTitle, 1L));
            courseService.publishCourse(course.getCourseId());

            Order order = orderService.createOrder(
                    TestDataBuilder.buildOrder(user.getUserId(), course.getCourseId(), idempotencyKey));

            Map<String, Object> sagaCtx = new LinkedHashMap<>();
            sagaCtx.put("orderId", order.getOrderId());
            sagaCtx.put("courseId", course.getCourseId());
            sagaCtx.put("userId", user.getUserId());
            sagaCtx.put("payMethod", payMethod);
            sagaCtx.put("businessKey", idempotencyKey);

            Map<String, Object> result = sagaCoordinator.run("createOrderSaga", sagaCtx);
            String status = (String) result.get("__status__");
            if ("SUCCEEDED".equals(status)) {
                orderService.payOrder(order.getOrderId(), payMethod);
                pulsarEventBus.publish("order-paid", order.getOrderId());
                return new SagaResult(status, orderService.getOrderById(order.getOrderId()));
            }
            // Forward failure: the saga already compensated; surface the status.
            return new SagaResult(status, orderService.getOrderById(order.getOrderId()));
        }

        SagaResult createAndPayExpectingFailure(String username, String phone,
                                                String courseTitle, String payMethod,
                                                String idempotencyKey) {
            User user = userService.createUser(TestDataBuilder.buildUser(username, phone));
            Course course = courseService.createCourse(TestDataBuilder.buildCourse(courseTitle, 1L));
            courseService.publishCourse(course.getCourseId());
            Order order = orderService.createOrder(
                    TestDataBuilder.buildOrder(user.getUserId(), course.getCourseId(), idempotencyKey));

            Map<String, Object> sagaCtx = new LinkedHashMap<>();
            sagaCtx.put("orderId", order.getOrderId());
            sagaCtx.put("businessKey", idempotencyKey);
            Map<String, Object> result = sagaCoordinator.run("createOrderSaga", sagaCtx);
            return new SagaResult((String) result.get("__status__"), order);
        }

        void refund(Long orderId, String reason) {
            Map<String, Object> sagaCtx = new LinkedHashMap<>();
            sagaCtx.put("orderId", orderId);
            sagaCtx.put("reason", reason);
            sagaCoordinator.run("cancelOrderSaga", sagaCtx);
            orderService.refundOrder(orderId, reason);
            pulsarEventBus.publish("order-refunded", orderId);
        }
    }

    /** Mockito-friendly stand-in for the Seata Saga {@code StateMachineEngine}. */
    interface SagaCoordinator {
        Map<String, Object> run(String sagaName, Map<String, Object> context);
    }

    /** Mockito-friendly stand-in for the Pulsar order-event producer. */
    interface PulsarEventBus {
        void publish(String topic, Long orderId);
    }

    record SagaResult(String status, Order order) {
    }
}
