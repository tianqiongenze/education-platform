package com.ep.order.controller;

import com.ep.order.api.CreateOrderCmd;
import com.ep.order.api.OrderDto;
import com.ep.order.api.OrderService;
import com.ep.order.api.OrderStatus;
import com.ep.web.common.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
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
 * Standalone-setup MockMvc tests for {@link OrderController}. Mirrors the
 * canonical {@code UserControllerTest} pattern: mock {@link OrderService},
 * wire {@link GlobalExceptionHandler} for 403 mapping.
 */
class OrderControllerTest {

    private MockMvc mockMvc;
    private final ObjectMapper mapper = new ObjectMapper();
    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderService = mock(OrderService.class);
        OrderController controller = new OrderController(orderService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private CreateOrderCmd cmd() {
        return new CreateOrderCmd(
                1001L, 2001L, new BigDecimal("99.00"),
                "idem-key-001", "default", "COURSE");
    }

    private OrderDto dto(long orderId) {
        return new OrderDto(
                orderId, 1001L, 2001L, "default", "COURSE",
                new BigDecimal("99.00"), OrderStatus.PENDING.getCode(),
                null, "idem-key-001", System.currentTimeMillis(), System.currentTimeMillis());
    }

    @Test
    void create_endpoint() throws Exception {
        when(orderService.createOrder(any(CreateOrderCmd.class)))
                .thenReturn(dto(5001L));

        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(cmd())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(5001))
                .andExpect(jsonPath("$.userId").value(1001))
                .andExpect(jsonPath("$.courseId").value(2001))
                .andExpect(jsonPath("$.status").value(OrderStatus.PENDING.getCode()));
    }

    @Test
    void getById_endpoint() throws Exception {
        when(orderService.getOrderById(5001L)).thenReturn(dto(5001L));

        mockMvc.perform(get("/api/v1/orders/{orderId}", 5001L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(5001));
    }

    @Test
    void getById_notFound() throws Exception {
        when(orderService.getOrderById(404L)).thenReturn(null);

        mockMvc.perform(get("/api/v1/orders/{orderId}", 404L))
                .andExpect(status().isNotFound());
    }

    @Test
    void pay_endpoint() throws Exception {
        mockMvc.perform(post("/api/v1/orders/{orderId}/pay", 5001L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payMethod\":\"ALIPAY\"}"))
                .andExpect(status().isNoContent());

        verify(orderService).payOrder(5001L, "ALIPAY");
    }

    @Test
    void refund_endpoint() throws Exception {
        mockMvc.perform(post("/api/v1/orders/{orderId}/refund", 5001L)
                        .header("X-Role", "admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"customer request\"}"))
                .andExpect(status().isNoContent());

        verify(orderService).refundOrder(5001L, "customer request");
    }

    @Test
    void refund_forbiddenWithoutRole() throws Exception {
        mockMvc.perform(post("/api/v1/orders/{orderId}/refund", 5001L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"test\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void listUserOrders_endpoint() throws Exception {
        when(orderService.listUserOrders(eq(1001L), anyInt(), anyInt()))
                .thenReturn(List.of(dto(5001L), dto(5002L)));

        mockMvc.perform(get("/api/v1/orders")
                        .param("userId", "1001")
                        .param("page", "1")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].orderId").value(5001))
                .andExpect(jsonPath("$[1].orderId").value(5002));
    }
}
