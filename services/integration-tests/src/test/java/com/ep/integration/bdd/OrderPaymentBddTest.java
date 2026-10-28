package com.ep.integration.bdd;

import com.ep.integration.builder.TestDataBuilder;
import com.ep.integration.support.BusinessFakes.FakeCourseService;
import com.ep.integration.support.BusinessFakes.FakeOrderService;
import com.ep.integration.support.BusinessFakes.FakeUserService;
import com.ep.integration.support.IntegrationDtos.Course;
import com.ep.integration.support.IntegrationDtos.CourseStatus;
import com.ep.integration.support.IntegrationDtos.Order;
import com.ep.integration.support.IntegrationDtos.OrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Feature-driven test for {@code order-payment.feature}. Step lambdas wire the
 * existing in-JVM fakes ({@link FakeUserService}/{@link FakeCourseService}/
 * {@link FakeOrderService}) and the shared {@link TestDataBuilder} builders.
 *
 * <p>Payment state in this module lives on the Order (status PAID + payMethod);
 * there is no separate Payment entity — the Then steps assert the real shape.
 * The negative scenario exercises the reachable money-safety guard: a refund
 * is only allowed from PAID, so a second refund on an already-REFUNDED order
 * must be rejected (double-refund / over-refund protection).
 */
@Tag("integration")
class OrderPaymentBddTest extends BddScenarioTest {

    private final FakeUserService userService = new FakeUserService();
    private final FakeCourseService courseService = new FakeCourseService();
    private final FakeOrderService orderService = new FakeOrderService();

    /** State handed between steps of the currently executing scenario. */
    private Course course;
    private Order order;
    private boolean secondRefundRejected;

    @Override
    protected Map<String, StepRegistry.Step> defineSteps() {
        Map<String, StepRegistry.Step> steps = new LinkedHashMap<>();

        steps.put("已上架课程 \"Java进阶\"", () -> {
            course = courseService.createCourse(TestDataBuilder.buildCourse("Java进阶", 1L));
            courseService.publishCourse(course.getCourseId());
            assertEquals(CourseStatus.PUBLISHED.getCode(),
                    courseService.getCourseById(course.getCourseId()).getStatus());
        });

        steps.put("学生 \"alice\" 下单并使用 \"ALIPAY\" 支付", () -> {
            var user = userService.createUser(TestDataBuilder.buildUser("alice", "13800009001"));
            order = orderService.createOrder(TestDataBuilder.buildOrder(
                    user.getUserId(), course.getCourseId(), "bdd-alice-pay-1"));
            orderService.payOrder(order.getOrderId(), "ALIPAY");
        });
        steps.put("订单状态变为 PAID", () -> {
            Order current = orderService.getOrderById(order.getOrderId());
            assertNotNull(current, "订单必须存在");
            assertEquals(OrderStatus.PAID.getCode(), current.getStatus(), "支付后订单必须为 PAID");
        });
        steps.put("支付方式 \"ALIPAY\" 已记录在订单上", () ->
                assertEquals("ALIPAY", orderService.getOrderById(order.getOrderId()).getPayMethod(),
                        "支付方式必须记录在订单上"));

        steps.put("学生 \"bob\" 已下单并支付成功", () -> {
            var user = userService.createUser(TestDataBuilder.buildUser("bob", "13800009002"));
            order = orderService.createOrder(TestDataBuilder.buildOrder(
                    user.getUserId(), course.getCourseId(), "bdd-bob-pay-1"));
            orderService.payOrder(order.getOrderId(), "ALIPAY");
        });
        steps.put("该订单已退款", () ->
                orderService.refundOrder(order.getOrderId(), "首次退款"));
        steps.put("再次对该订单发起退款", () -> {
            secondRefundRejected = false;
            try {
                orderService.refundOrder(order.getOrderId(), "重复退款");
            } catch (IllegalStateException expected) {
                secondRefundRejected = true;
            }
        });
        steps.put("退款被拒绝", () ->
                assertTrue(secondRefundRejected, "非 PAID 状态的退款请求必须被状态守卫拒绝"));
        steps.put("订单状态保持 REFUNDED", () ->
                assertEquals(OrderStatus.REFUNDED.getCode(),
                        orderService.getOrderById(order.getOrderId()).getStatus(),
                        "被拒绝的退款不得改变订单状态"));
        return steps;
    }

    @Test
    @DisplayName("Gherkin feature: 订单支付 — 学生下单并支付成功 + 重复退款被拒绝")
    void order_payment_feature() throws Exception {
        runFeature("/features/order-payment.feature");
    }
}
