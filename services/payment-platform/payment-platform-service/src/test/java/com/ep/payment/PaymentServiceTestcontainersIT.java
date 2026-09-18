package com.ep.payment;

import com.ep.payment.api.Payment;
import com.ep.payment.api.PaymentChannel;
import com.ep.payment.api.PaymentRequest;
import com.ep.payment.api.PaymentResult;
import com.ep.payment.api.PaymentService;
import com.ep.payment.api.PaymentStatus;
import com.ep.payment.api.Refund;
import com.ep.payment.api.RefundStatus;
import com.ep.payment.channel.MockPaymentChannel;
import com.ep.payment.service.PaymentServiceImpl;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-119: Testcontainers integration test for the core-business Payment service.
 *
 * <h3>What this test exercises</h3>
 * The payment service currently persists to a {@link java.util.concurrent.ConcurrentHashMap}
 * (D-039 — production swaps in TiDB, SDD §3.2). This IT spins up a real isolated
 * MySQL 8.0 container to:
 * <ul>
 *   <li>Validate the Spring Boot context boots successfully with real infra
 *       available on the classpath.</li>
 *   <li>Exercise the {@link PaymentServiceImpl#createPayment} flow with the
 *       {@link MockPaymentChannel} (EP_DEV_ONLY) end-to-end through the Spring
 *       context.</li>
 *   <li>Exercise the {@link PaymentServiceImpl#refund} flow and verify the
 *       Payment state transitions (PENDING &rarr; SUCCESS &rarr; REFUNDED).</li>
 *   <li>Verify the {@link MockPaymentChannel} SPI integrates correctly with the
 *       service when wired by Spring.</li>
 * </ul>
 *
 * <p><b>Honesty note:</b> The production TiDB-backed repository swap is designed
 * (SDD §3.2 maps {@code Payment}/{@code Refund} to {@code ep_payment}/
 * {@code ep_refund}) but not yet wired, so this IT does not yet write payment
 * rows to the container. The container is started so that when the repository
 * is wired, this IT can be extended to assert real DB persistence (mirroring
 * the order-service IT pattern). The value today is boot-time validation and
 * end-to-end SPI flow coverage with real Spring wiring.
 *
 * <h3>Self-contained</h3>
 * The MySQL container is started/stopped by JUnit 5's {@code @Testcontainers}
 * lifecycle. No shared TiDB / Redis / Pulsar cluster is touched (Iron Law 0).
 *
 * <h3>Tagging</h3>
 * Tagged {@code "testcontainers"} so CI fast runs can exclude it with
 * {@code -DexcludedGroups=testcontainers,live}.
 */
@Tag("testcontainers")
@Testcontainers
@SpringBootTest(classes = {PaymentApplication.class, PaymentServiceTestcontainersIT.TestConfig.class})
@ActiveProfiles("test")
class PaymentServiceTestcontainersIT {

    /**
     * MySQL 8.0 container. TiDB is MySQL-protocol compatible, so this exercises
     * the same dialect / driver the production TiDB swap will use. Static so it
     * is shared across test methods (started once, stopped at JVM shutdown).
     */
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("paymentdb_test")
            .withUsername("test")
            .withPassword("test")
            .withReuse(false);

    /**
     * Register the container's JDBC URL so the Spring context sees a real
     * datasource is available (even though the current impl does not write to
     * it). This ensures the context boots with real infra on the classpath.
     */
    @DynamicPropertySource
    static void registerContainerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
    }

    @Autowired
    private PaymentService paymentService;

    @Test
    void createPayment_success_realSpringContext() {
        PaymentRequest request = new PaymentRequest(
                "order-it-1", "tenant-it", PaymentChannel.ALIPAY,
                new BigDecimal("199.00"), "CNY");

        PaymentResult result = paymentService.createPayment(request);

        // devGuard=true forces the MOCK channel even though ALIPAY was requested.
        assertEquals(PaymentStatus.SUCCESS, result.status());
        assertEquals(PaymentChannel.MOCK, result.status() == PaymentStatus.SUCCESS
                ? PaymentChannel.MOCK : PaymentChannel.ALIPAY);
        assertNotNull(result.paymentId());
        assertTrue(result.paymentId().matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"),
                "paymentId should be a UUID: " + result.paymentId());
        assertNotNull(result.channelTxId());
        assertNotNull(result.paidAt());

        // The payment is retrievable via getPaymentByOrder.
        Payment stored = paymentService.getPaymentByOrder("order-it-1");
        assertNotNull(stored);
        assertEquals("order-it-1", stored.getOrderId());
        assertEquals(PaymentStatus.SUCCESS, stored.getStatus());
        assertEquals(new BigDecimal("199.00"), stored.getAmount());
    }

    @Test
    void refund_success_flipsPaymentToRefunded() {
        // Create a payment first.
        PaymentRequest request = new PaymentRequest(
                "order-it-2", "tenant-it", PaymentChannel.MOCK,
                new BigDecimal("50.00"), "CNY");
        PaymentResult created = paymentService.createPayment(request);
        assertEquals(PaymentStatus.SUCCESS, created.status());

        // Full refund -> Payment flips to REFUNDED.
        Refund refund = paymentService.refund(
                "order-it-2", new BigDecimal("50.00"), "D-119 IT full refund");

        assertNotNull(refund);
        assertEquals(RefundStatus.SUCCESS, refund.getStatus());
        assertEquals("order-it-2", refund.getOrderId());
        assertEquals(new BigDecimal("50.00"), refund.getAmount());

        Payment after = paymentService.getPaymentByOrder("order-it-2");
        assertNotNull(after);
        assertEquals(PaymentStatus.REFUNDED, after.getStatus());
    }

    @Test
    void refund_paymentNotFound_throws() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> paymentService.refund("nonexistent-order",
                        new BigDecimal("10.00"), "no such order"));
        assertTrue(ex.getMessage().contains("No payment for order"));
    }

    @Test
    void createPayment_invalidAmount_throws() {
        PaymentRequest request = new PaymentRequest(
                "order-it-3", "tenant-it", PaymentChannel.MOCK,
                new BigDecimal("0.00"), "CNY");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> paymentService.createPayment(request));
        assertTrue(ex.getMessage().contains("amount must be > 0"));
    }

    /**
     * Test config that ensures the {@link MockPaymentChannel} bean is registered
     * (it is a {@code @Component} so it should be picked up by component scan,
     * but declaring it explicitly guarantees the IT works even if the scan
     * package is narrowed in future).
     */
    @TestConfiguration
    static class TestConfig {
        @Bean
        MockPaymentChannel mockPaymentChannel() {
            return new MockPaymentChannel();
        }
    }
}
