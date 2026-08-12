package com.ep.bff.dubbo;

import com.ep.order.api.CreateOrderCmd;
import com.ep.order.api.OrderDto;
import com.ep.order.api.OrderService;
import com.ep.order.api.OrderStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Standalone-setup MockMvc tests for {@link OrderDubboController}. The
 * {@code @DubboReference} field is injected via {@link ReflectionTestUtils}
 * with a plain Mockito mock — no Dubbo registry required.
 */
class OrderDubboControllerTest {

    private MockMvc mockMvc;
    private final ObjectMapper mapper = new ObjectMapper();
    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderService = mock(OrderService.class);
        OrderDubboController controller = new OrderDubboController();
        ReflectionTestUtils.setField(controller, "orderService", orderService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private CreateOrderCmd cmd() {
        return new CreateOrderCmd(1001L, 2001L, new BigDecimal("99.00"),
                "idem-001", "default", "COURSE");
    }

    private OrderDto dto(long orderId) {
        return new OrderDto(orderId, 1001L, 2001L, "default", "COURSE",
                new BigDecimal("99.00"), OrderStatus.PENDING.getCode(),
                null, "idem-001", System.currentTimeMillis(), System.currentTimeMillis());
    }

    @Test
    void create_endpoint() throws Exception {
        when(orderService.createOrder(any())).thenReturn(dto(5001L));

        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(cmd())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(5001));
    }

    @Test
    void getById_endpoint() throws Exception {
        when(orderService.getOrderById(5001L)).thenReturn(dto(5001L));

        mockMvc.perform(get("/api/v1/orders/{id}", 5001L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(5001));
    }

    @Test
    void getById_notFound() throws Exception {
        when(orderService.getOrderById(999L)).thenReturn(null);

        mockMvc.perform(get("/api/v1/orders/{id}", 999L))
                .andExpect(status().isNotFound());
    }

    @Test
    void getByUserAndCourse_endpoint() throws Exception {
        when(orderService.getOrderByUserAndCourse(1001L, 2001L)).thenReturn(dto(5001L));

        mockMvc.perform(get("/api/v1/orders/by-user-course")
                        .param("userId", "1001")
                        .param("courseId", "2001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(5001));
    }

    @Test
    void getByUserAndCourse_notFound() throws Exception {
        when(orderService.getOrderByUserAndCourse(anyLong(), anyLong())).thenReturn(null);

        mockMvc.perform(get("/api/v1/orders/by-user-course")
                        .param("userId", "1")
                        .param("courseId", "2"))
                .andExpect(status().isNotFound());
    }

    @Test
    void listUserOrders_endpoint() throws Exception {
        when(orderService.listUserOrders(eq(1001L), any(), any()))
                .thenReturn(List.of(dto(5001L), dto(5002L)));

        mockMvc.perform(get("/api/v1/orders/user/{userId}", 1001L)
                        .param("page", "1")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].orderId").value(5001))
                .andExpect(jsonPath("$[1].orderId").value(5002));
    }

    @Test
    void pay_endpoint() throws Exception {
        mockMvc.perform(post("/api/v1/orders/{id}/pay", 5001L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payMethod\":\"ALIPAY\"}"))
                .andExpect(status().isNoContent());

        verify(orderService).payOrder(5001L, "ALIPAY");
    }

    @Test
    void refund_endpoint() throws Exception {
        mockMvc.perform(post("/api/v1/orders/{id}/refund", 5001L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"test\"}"))
                .andExpect(status().isNoContent());

        verify(orderService).refundOrder(5001L, "test");
    }
}
