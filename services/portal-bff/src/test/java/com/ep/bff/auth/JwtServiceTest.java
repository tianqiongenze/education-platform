package com.ep.bff.auth;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JwtService 100% 覆盖率硬门 — 安全核心.
 *
 * <p>D-117: 新增对 env-var / file path 密钥加载路径的覆盖。
 */
class JwtServiceTest {
    private JwtService svc;

    @BeforeEach
    void setup() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();
        svc = new JwtService(kp.getPrivate(), kp.getPublic());
    }

    @AfterEach
    void cleanupEnv() {
        // D-117: 隔离环境变量，避免测试间相互污染。
        System.clearProperty("JWT_PRIVATE_KEY_PATH");
        System.clearProperty("JWT_PUBLIC_KEY_PATH");
        // System.getenv 无法清除（JDK 不可变），但本类测试只对未设置的情况触发，
        // CI 上若已设置 JWT_PRIVATE_KEY_PATH 等会绕过文件路径分支——
        // 因此测试通过设置一个不存在的临时路径 + 写临时文件来覆盖文件加载分支。
    }

    @Test
    @DisplayName("issue → parse round trip preserves userId and role")
    void issueThenParseRoundTrip() {
        String token = svc.issue(1001L, "user@test.com", "TestUser", "student");
        Claims claims = svc.parse(token);
        assertEquals("1001", claims.getSubject());
        assertEquals("user@test.com", claims.get("email", String.class));
        assertEquals("TestUser", claims.get("name", String.class));
        assertEquals("student", claims.get("role", String.class));
    }

    @Test
    @DisplayName("parse throws on tampered token")
    void parseThrowsOnTampered() {
        String token = svc.issue(1L, "a", "b", "c");
        assertThrows(Exception.class, () -> svc.parse(token + "x"));
    }

    @Test
    @DisplayName("parse throws on null/empty token")
    void parseThrowsOnEmpty() {
        assertThrows(Exception.class, () -> svc.parse(""));
        assertThrows(Exception.class, () -> svc.parse(null));
    }

    @Test
    @DisplayName("getPublicKey returns the RSA public key")
    void getPublicKeyNotNull() {
        assertNotNull(svc.getPublicKey());
        assertEquals("RSA", svc.getPublicKey().getAlgorithm());
    }

    @Test
    @DisplayName("parsePrivateKey/parsePublicKey from PEM round trip")
    void parseFromPemRoundTrip() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();

        String privPem = toPem(kp.getPrivate(), "PRIVATE KEY");
        String pubPem = toPem(kp.getPublic(), "PUBLIC KEY");

        PrivateKey parsedPriv = JwtService.parsePrivateKey(privPem);
        PublicKey parsedPub = JwtService.parsePublicKey(pubPem);

        assertEquals(kp.getPrivate(), parsedPriv);
        assertEquals(kp.getPublic(), parsedPub);
    }

    @Test
    @DisplayName("stripPem removes headers and whitespace")
    void stripPemWorks() {
        String pem = "-----BEGIN PRIVATE KEY-----\nABCD1234\n-----END PRIVATE KEY-----";
        String stripped = JwtService.stripPem(pem);
        assertEquals("ABCD1234", stripped);
    }

    @Test
    @DisplayName("parsePrivateKey throws on invalid PEM")
    void parsePrivateKeyThrowsOnBadPem() {
        assertThrows(IllegalArgumentException.class, () -> JwtService.parsePrivateKey("garbage"));
    }

    @Test
    @DisplayName("parsePublicKey throws on invalid PEM")
    void parsePublicKeyThrowsOnBadPem() {
        assertThrows(IllegalArgumentException.class, () -> JwtService.parsePublicKey("garbage"));
    }

    private static String toPem(java.security.Key key, String type) {
        Base64.Encoder enc = Base64.getMimeEncoder(64, "\n".getBytes());
        return "-----BEGIN " + type + "-----\n" + new String(enc.encode(key.getEncoded())) + "\n-----END " + type + "-----\n";
    }

    // -------------------------------------------------------------------------
    // D-117: env-var / file-path key loading
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("D-117: keys loaded from PEM files via file paths")
    void loadsKeysFromFiles() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();

        Path tmpDir = Files.createTempDirectory("jwt-d117-test");
        Path privFile = tmpDir.resolve("private.pem");
        Path pubFile = tmpDir.resolve("public.pem");
        Files.writeString(privFile, toPem(kp.getPrivate(), "PRIVATE KEY"));
        Files.writeString(pubFile, toPem(kp.getPublic(), "PUBLIC KEY"));

        try {
            JwtService svc2 = JwtService.fromSources(
                privFile.toString(), null,
                pubFile.toString(), null,
                null, null);
            String token = svc2.issue(42L, "u@e.com", "n", "teacher");
            Claims claims = svc2.parse(token);
            assertEquals("42", claims.getSubject());
            assertEquals("teacher", claims.get("role", String.class));
        } finally {
            Files.deleteIfExists(privFile);
            Files.deleteIfExists(pubFile);
            Files.deleteIfExists(tmpDir);
        }
    }

    @Test
    @DisplayName("D-117: keys loaded from base64 inline env var values")
    void loadsKeysFromBase64Inline() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();

        String privPem = toPem(kp.getPrivate(), "PRIVATE KEY");
        String pubPem = toPem(kp.getPublic(), "PUBLIC KEY");
        // 指向不存在的路径，强制走 base64 内联分支
        String privB64 = Base64.getEncoder().encodeToString(privPem.getBytes(StandardCharsets.UTF_8));
        String pubB64 = Base64.getEncoder().encodeToString(pubPem.getBytes(StandardCharsets.UTF_8));

        JwtService svc2 = JwtService.fromSources(
            "/nonexistent/d117-priv.pem", privB64,
            "/nonexistent/d117-pub.pem", pubB64,
            null, null);
        String token = svc2.issue(7L, "a@b.com", "x", "student");
        assertEquals("7", svc2.parse(token).getSubject());
    }

    @Test
    @DisplayName("D-117: Spring inline PEM still works (dev backwards-compat)")
    void loadsKeysFromSpringInlinePem() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();
        String privPem = toPem(kp.getPrivate(), "PRIVATE KEY");
        String pubPem = toPem(kp.getPublic(), "PUBLIC KEY");

        JwtService svc2 = JwtService.fromSources(
            "/nonexistent/spring-priv.pem", null,
            "/nonexistent/spring-pub.pem", null,
            privPem, pubPem);
        String token = svc2.issue(99L, "a@b.com", "x", "admin");
        assertEquals("99", svc2.parse(token).getSubject());
    }

    @Test
    @DisplayName("D-117: missing keys produce clear IllegalStateException, not silent failure")
    void missingKeysThrowsClearError() {
        // 文件不存在 + base64 为空 + Spring PEM 为空 → 必须清晰报错
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
            JwtService.fromSources(
                "/nonexistent/missing-priv.pem", null,
                "/nonexistent/missing-pub.pem", null,
                null, null));
        // 报错信息应引导用户去看 D-117 迁移文档
        assertTrue(ex.getMessage().contains("D-117"),
            "error message should reference D-117 migration doc");
    }

    @Test
    @DisplayName("D-117: invalid base64 inline produces clear error")
    void invalidBase64InlineThrows() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
            JwtService.fromSources(
                "/nonexistent/x.pem", "!!!not-base64!!!",
                "/nonexistent/y.pem", null,
                null, null));
        assertTrue(ex.getMessage().contains("base64"),
            "error message should mention base64 decode failure");
    }
}
