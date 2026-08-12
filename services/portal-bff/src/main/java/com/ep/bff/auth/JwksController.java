package com.ep.bff.auth;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * JWKS 端点 — 供下游服务/网关获取 RS256 公钥验签.
 */
@RestController
public class JwksController {
    private final JwtService jwtService;

    public JwksController(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @GetMapping("/.well-known/jwks.json")
    public Map<String, Object> jwks() {
        RSAPublicKey rsa = (RSAPublicKey) jwtService.getPublicKey();
        return Map.of("keys", List.of(Map.of(
            "kty", "RSA",
            "alg", "RS256",
            "use", "sig",
            "kid", "ep-bff-rs256",
            "n", Base64.getUrlEncoder().withoutPadding().encodeToString(rsa.getModulus().toByteArray()),
            "e", Base64.getUrlEncoder().withoutPadding().encodeToString(rsa.getPublicExponent().toByteArray())
        )));
    }
}
