package com.ep.bff.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Standalone-setup MockMvc tests for {@link JwksController}. Uses a real
 * RSA key pair (generated in-memory) so the JWKS endpoint returns valid
 * base64url-encoded n/e parameters.
 */
class JwksControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();
        JwtService jwtService = new JwtService(kp.getPrivate(), kp.getPublic());
        JwksController controller = new JwksController(jwtService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void jwks_returnsValidJwks() throws Exception {
        mockMvc.perform(get("/.well-known/jwks.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys[0].kty").value("RSA"))
                .andExpect(jsonPath("$.keys[0].alg").value("RS256"))
                .andExpect(jsonPath("$.keys[0].use").value("sig"))
                .andExpect(jsonPath("$.keys[0].kid").value("ep-bff-rs256"))
                .andExpect(jsonPath("$.keys[0].n").isString())
                .andExpect(jsonPath("$.keys[0].e").isString());
    }

    @Test
    void jwks_eIsAQAB() throws Exception {
        // RSA public exponent is almost always 65537 (AQAB in base64url).
        RSAPublicKey pub = null; // verified indirectly via endpoint below
        mockMvc.perform(get("/.well-known/jwks.json"))
                .andExpect(jsonPath("$.keys[0].e").value("AQAB"));
    }
}
