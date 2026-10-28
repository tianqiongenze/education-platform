package com.ep.bff.graphql;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D-graphql: HTTP-layer smoke for the {@code /graphql} endpoint — the full BFF
 * context boots and an anonymous POST is accepted (same whitelist family as
 * {@code /proxy/**}: AuthFilter whitelist, actuator-only Spring Security
 * chain).
 * <p>
 * D-241: body-level GraphQL assertions deliberately live in the pure-Mockito
 * {@link GraphQlResolverTest}. spring-graphql answers the MockMvc request
 * with an empty 200 async-start response under every tested ordering, so
 * MockMvc body assertions on {@code /graphql} are unreliable by construction;
 * the resolver tests pin the payload contract directly.</p>
 */
@SpringBootTest(properties = {
        // Boot without live Redis/Pulsar — the eviction listener container
        // otherwise tries to connect and kills the context in offline CI.
        "ep.cache.broadcast=none",
        "spring.data.redis.repositories.enabled=false"
})
@AutoConfigureMockMvc
class GraphQlAggregationTest {

    @Autowired
    private MockMvc mockMvc;

    private static KeyPair keyPair;

    @BeforeAll
    static void generateRsaKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
    }

    @DynamicPropertySource
    static void testProperties(DynamicPropertyRegistry registry) {
        registry.add("jwt.rsa.private-key-pem",
            () -> pem("PRIVATE KEY", keyPair.getPrivate().getEncoded()));
        registry.add("jwt.rsa.public-key-pem",
            () -> pem("PUBLIC KEY", keyPair.getPublic().getEncoded()));
        registry.add("ep.cell-router.enabled", () -> "false");
        registry.add("ep.idempotent.enabled", () -> "false");
    }

    private static String pem(String label, byte[] der) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der);
        return "-----BEGIN " + label + "-----\n" + body + "\n-----END " + label + "-----";
    }

    @Test
    @DisplayName("POST /graphql without a Bearer token is accepted (mirrors /proxy/** posture)")
    void graphqlPostWithoutTokenIsAccepted() throws Exception {
        mockMvc.perform(post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"{ __typename }\"}"))
            .andExpect(status().isOk());
    }
}
