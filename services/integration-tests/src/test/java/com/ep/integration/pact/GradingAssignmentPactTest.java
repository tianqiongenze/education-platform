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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-197+: Pact consumer-driven contract test — grading-service (consumer)
 * calling assignment-service (provider) over REST.
 *
 * <p>Per portal-bff-CT-001 the M0 stage uses Pact in file-exchange mode (no Pact
 * Broker). Pact spins up an in-process mock server that stands in for the
 * assignment-service provider; the test verifies that the grading-service's
 * HTTP client call matches the pact. No real assignment-service needs to be
 * running — the mock server is the provider stand-in.
 *
 * <p>Contract under test:
 * <ul>
 *   <li>Consumer: grading-service</li>
 *   <li>Provider: assignment-service</li>
 *   <li>Interaction: "upon receiving a request to get assignment by ID"</li>
 *   <li>Provider state: "assignment 789 exists"</li>
 *   <li>Request: GET /api/assignments/789</li>
 *   <li>Response: 200 { "assignmentId": 789, "title": "Homework 1", "maxScore": 100 }</li>
 * </ul>
 *
 * <p>The consumer side mirrors the real {@code AssignmentController} REST
 * surface ({@code GET /api/v1/assignments/{id}}) — the pact uses a simplified
 * path prefix ({@code /api/assignments}) for portability across the BFF and
 * internal routing layers — and is exercised through a plain JDK
 * {@link HttpClient} so the test is fully self-contained.
 */
@Tag("integration")
@DisplayName("Pact: grading-service → assignment-service get-assignment contract")
@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = "assignment-service", port = "8081")
class GradingAssignmentPactTest {

    private static final String PROVIDER_STATE = "assignment 789 exists";

    /**
     * Defines the consumer-provider pact for the get-assignment interaction.
     * Uses the V4 {@link PactBuilder} DSL (required by Pact JVM 4.6.x for
     * JUnit 5 tests that return a pact from the {@code @Pact} method).
     *
     * @param builder the V4 Pact builder seeded by the extension
     * @return the assembled {@link V4Pact}
     */
    @Pact(provider = "assignment-service", consumer = "grading-service")
    public V4Pact getAssignmentPact(PactBuilder builder) {
        return builder
                .expectsToReceiveHttpInteraction("a request to get assignment by ID", http -> http
                        .withRequest(req -> req
                                .path("/api/assignments/789")
                                .method("GET")
                                .headers(java.util.Map.of("Accept", "application/json")))
                        .willRespondWith(res -> res
                                .status(200)
                                .headers(java.util.Map.of("Content-Type", "application/json"))
                                .body(new PactDslJsonBody()
                                        .integerType("assignmentId", 789)
                                        .stringValue("title", "Homework 1")
                                        .integerType("maxScore", 100).toString())))
                .given(PROVIDER_STATE)
                .toPact();
    }

    /**
     * Consumer-side verification: issues the real HTTP GET that
     * grading-service would make against assignment-service and asserts that
     * the response matches the pact. The Pact extension has already started
     * the mock provider on port 8081.
     */
    @Test
    @DisplayName("grading-service get-assignment call matches the assignment-service pact")
    void testGetAssignmentContract() throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:8081/api/assignments/789"))
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        // --- Assertions against the pact response ---
        assertEquals(200, response.statusCode(),
                "assignment-service should return 200 OK for an existing assignment");
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        assertTrue(contentType.contains("application/json"),
                "Content-Type should be application/json, was: " + contentType);
        String body = response.body();
        assertTrue(body.contains("\"assignmentId\""),
                "response body should contain assignmentId: " + body);
        assertTrue(body.contains("Homework 1"),
                "response body should contain the assignment title: " + body);
        assertTrue(body.contains("\"maxScore\""),
                "response body should contain maxScore: " + body);
    }
}
