package com.ep.order;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.contract.stubrunner.spring.AutoConfigureStubRunner;
import org.springframework.cloud.contract.stubrunner.spring.StubRunnerProperties;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * D-119: Consumer-side CDC test for the SP-03 Order &rarr; Payment integration.
 *
 * <p>The order service ({@link com.ep.order.service.OrderServiceImpl}) calls
 * {@link PaymentService} over Dubbo RPC in production. This consumer-side
 * contract test verifies that the <em>HTTP contract</em> defined by the
 * producer (payment-platform-service) in {@code contracts/payment_create.groovy}
 * and {@code contracts/payment_refund.groovy} is honored by the WireMock stub
 * generated from those contracts.
 *
 * <h3>Why an HTTP contract test for a Dubbo integration?</h3>
 * The canonical integration path is Dubbo, but the CDC contract pins the HTTP
 * surface (the shape the payment service exposes to BFF/external clients and
 * the shape that mirrors the Dubbo DTOs). The consumer (order) verifies it can
 * correctly construct requests matching the contract and parse the responses.
 * If the producer changes the response shape in a breaking way, the stub (built
 * from the contract) will not match and this test fails before the producer
 * ships.
 *
 * <h3>How it works</h3>
 * <ol>
 *   <li>The {@code spring-cloud-contract-maven-plugin} in payment-platform-service
 *       generates a WireMock stub jar from the Groovy contracts.</li>
 *   <li>This test uses {@code @AutoConfigureStubRunner} with
 *       {@code stubsMode=LOCAL} to download that stub jar and start WireMock
 *       on a random port.</li>
 *   <li>The test sends HTTP requests matching the contract and asserts the
 *       responses match the agreed shape.</li>
 * </ol>
 *
 * <p>The stub runner needs the payment-service stubs jar to be available in the
 * local Maven repo ({@code mvn install} in payment-platform-service first). The
 * {@code workOffline=true} flag tells the stub runner to use the local repo
 * only (no remote fetch).
 *
 * <p><b>Note:</b> This test is NOT tagged {@code "testcontainers"} because it
 * uses WireMock (in-process), not Docker containers. It runs in the fast CI
 * path.
 */
@Tag("contract")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@AutoConfigureStubRunner(
        ids = "com.ep:payment-platform-service:+:stubs:8090",
        stubsMode = StubRunnerProperties.StubsMode.LOCAL
)
@ActiveProfiles("test")
class PaymentServiceContractTest {

    /**
     * The stub runner starts WireMock on port 8090 (fixed) serving the payment
     * contract stubs. This test sends real HTTP requests to that WireMock
     * instance and asserts the responses match the contract.
     */
    private static final String STUB_BASE_URL = "http://localhost:8090";

    @Test
    void paymentCreateContract_isHonoredByProducerStub() throws Exception {
        // Build the request body the consumer (order service) would send.
        String requestBody = """
                {
                  "orderId": "order-contract-1",
                  "tenantId": "default",
                  "channel": "MOCK",
                  "amount": "100.00",
                  "currency": "CNY"
                }
                """;

        java.net.http.HttpResponse<String> response = sendPost(
                STUB_BASE_URL + "/api/payments", requestBody);

        assertEquals(201, response.statusCode(),
                "payment create contract: expected 201, got " + response.statusCode()
                        + " body=" + response.body());

        // Assert the response body matches the contract shape.
        String body = response.body();
        assertNotNull(body);
        assertTrueContains(body, "pay-contract-1");
        assertTrueContains(body, "SUCCESS");
        assertTrueContains(body, "tx-contract-1");
        assertTrueContains(body, "order-contract-1");
    }

    @Test
    void paymentRefundContract_isHonoredByProducerStub() throws Exception {
        String requestBody = """
                {
                  "amount": "100.00",
                  "reason": "contract test refund"
                }
                """;

        java.net.http.HttpResponse<String> response = sendPost(
                STUB_BASE_URL + "/api/payments/order-contract-1/refund", requestBody);

        assertEquals(200, response.statusCode(),
                "payment refund contract: expected 200, got " + response.statusCode()
                        + " body=" + response.body());

        String body = response.body();
        assertNotNull(body);
        assertTrueContains(body, "refund-contract-1");
        assertTrueContains(body, "order-contract-1");
        assertTrueContains(body, "SUCCESS");
        assertTrueContains(body, "100.00");
    }

    // --------------------- helpers ---------------------

    private static java.net.http.HttpResponse<String> sendPost(String url, String body)
            throws Exception {
        java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(url))
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
    }

    private static void assertTrueContains(String haystack, String needle) {
        if (!haystack.contains(needle)) {
            throw new AssertionError(
                    "Expected response body to contain '" + needle + "' but got: " + haystack);
        }
    }
}
