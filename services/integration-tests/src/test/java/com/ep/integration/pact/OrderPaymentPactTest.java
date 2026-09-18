package com.ep.integration.pact;

import au.com.dius.pact.consumer.dsl.PactBuilder;
import au.com.dius.pact.consumer.dsl.PactDslJsonBody;
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt;
import au.com.dius.pact.consumer.junit5.PactTestFor;
import au.com.dius.pact.core.model.V4Pact;
import au.com.dius.pact.core.model.annotations.Pact;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-197+: Pact consumer-driven contract test — order-platform (consumer)
 * calling payment-platform (provider) over REST.
 *
 * <p>Per portal-bff-CT-001 the M0 stage uses Pact in file-exchange mode (no Pact
 * Broker). Pact spins up an in-process mock server that stands in for the
 * payment-platform provider; the test verifies that the order-platform's
 * HTTP client call matches the pact. No real payment-platform service needs
 * to be running — the mock server is the provider stand-in.
 *
 * <p>Contract under test:
 * <ul>
 *   <li>Consumer: order-platform</li>
 *   <li>Provider: payment-platform</li>
 *   <li>Interaction: "upon receiving a request to create a payment for an order"</li>
 *   <li>Provider state: "an order with ID 123 exists"</li>
 *   <li>Request: POST /api/payments { "orderId": 123, "amount": 99.00, "currency": "CNY" }</li>
 *   <li>Response: 201 { "paymentId": 456, "status": "PENDING" }</li>
 * </ul>
 *
 * <p>The consumer side mirrors the real {@code PaymentController} REST surface
 * ({@code POST /api/payments}) but is exercised through a plain JDK
 * {@link HttpClient} so the test is fully self-contained and does not depend
 * on any production service module being on the classpath at test time.
 */
@Tag("integration")
@DisplayName("Pact: order-platform → payment-platform create-payment contract")
@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = "payment-platform", port = "8080")
class OrderPaymentPactTest {

    private static final String PROVIDER_STATE = "an order with ID 123 exists";

    /**
     * Defines the consumer-provider pact for the create-payment interaction.
     * Uses the V4 {@link PactBuilder} DSL (required by Pact JVM 4.6.x for
     * JUnit 5 tests that return a pact from the {@code @Pact} method).
     *
     * @param builder the V4 Pact builder seeded by the extension
     * @return the assembled {@link V4Pact}
     */
    @Pact(provider = "payment-platform", consumer = "order-platform")
    public V4Pact createPaymentPact(PactBuilder builder) {
        return builder
                .expectsToReceiveHttpInteraction("a request to create a payment for an order", http -> http
                        .withRequest(req -> req
                                .path("/api/payments")
                                .method("POST")
                                .headers(java.util.Map.of("Content-Type", "application/json"))
                                .body(new PactDslJsonBody()
                                        .integerType("orderId", 123)
                                        .decimalType("amount", 99.00)
                                        .stringValue("currency", "CNY").toString()))
                        .willRespondWith(res -> res
                                .status(201)
                                .headers(java.util.Map.of("Content-Type", "application/json"))
                                .body(new PactDslJsonBody()
                                        .integerType("paymentId", 456)
                                        .stringValue("status", "PENDING").toString())))
                .given(PROVIDER_STATE)
                .toPact();
    }

    /**
     * Consumer-side verification: issues the real HTTP call that
     * order-platform would make against payment-platform and asserts that the
     * response matches the pact. The Pact extension has already started the
     * mock provider on port 8080.
     */
    @Test
    @DisplayName("order-platform create-payment call matches the payment-platform pact")
    void testCreatePaymentContract() throws IOException, InterruptedException {
        String requestBody = """
                {"orderId":123,"amount":99.00,"currency":"CNY"}
                """.strip();

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:8080/api/payments"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        // --- Assertions against the pact response ---
        assertEquals(201, response.statusCode(),
                "payment-platform should return 201 Created for a valid payment request");
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        assertTrue(contentType.contains("application/json"),
                "Content-Type should be application/json, was: " + contentType);
        String body = response.body();
        assertTrue(body.contains("\"paymentId\""),
                "response body should contain paymentId: " + body);
        assertTrue(body.contains("\"status\""),
                "response body should contain status: " + body);
        assertTrue(body.contains("PENDING"),
                "payment status should be PENDING: " + body);
    }
}
