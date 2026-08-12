package com.ep.bff.auth;

import io.jsonwebtoken.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.security.spec.*;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

/**
 * JWT RS256 签发与验签 — 安全核心, 100% 覆盖率硬门.
 * 私钥 ≥2048bit, CI 校验 key 长度 (ADR-A02).
 *
 * <p>D-117: 密钥外化。生产环境密钥从 K8s Secret 挂载到 {@code /etc/jwt/}，
 * 通过环境变量 {@code JWT_PRIVATE_KEY_PATH} / {@code JWT_PUBLIC_KEY_PATH}
 * 指向文件路径；或直接以 base64 编码形式注入 {@code JWT_PRIVATE_KEY} /
 * {@code JWT_PUBLIC_KEY}。仓库内不再提交任何 .pem / .key 文件。
 *
 * <p>为了向后兼容既有 Spring 配置（{@code jwt.rsa.private-key-pem} /
 * {@code jwt.rsa.public-key-pem}，例如开发环境从 {@code jwt-rs256.env} 加载
 * 单行 PEM），构造器仍接受这两个可选属性；当且仅当环境变量路径与内联 PEM
 * 均未提供时，抛出带清晰提示的 {@link IllegalStateException}，绝不静默失败。
 */
@Component
public class JwtService {
    /** 默认私钥文件路径（K8s Secret 挂载点）。 */
    public static final String DEFAULT_PRIVATE_KEY_PATH = "/etc/jwt/private.pem";
    /** 默认公钥文件路径（K8s Secret 挂载点）。 */
    public static final String DEFAULT_PUBLIC_KEY_PATH = "/etc/jwt/public.pem";

    private final PrivateKey privateKey;
    private final PublicKey publicKey;

    // D-076: SB4/Spring 7 requires @Autowired to disambiguate when multiple constructors exist.
    //
    // D-117: 私钥/公钥加载优先级（从高到低）：
    //   1) JWT_PRIVATE_KEY_PATH / JWT_PUBLIC_KEY_PATH 指向的文件（默认 /etc/jwt/*.pem）
    //   2) JWT_PRIVATE_KEY / JWT_PUBLIC_KEY base64 编码的内联值
    //   3) jwt.rsa.private-key-pem / jwt.rsa.public-key-pem Spring 属性（dev 向后兼容）
    //   4) 全部缺失 → 抛 IllegalStateException（清晰报错，不静默失败）
    @Autowired
    public JwtService(@Value("${jwt.rsa.private-key-pem:}") String inlinePrivPem,
                      @Value("${jwt.rsa.public-key-pem:}") String inlinePubPem) {
        this.privateKey = loadPrivateKey(inlinePrivPem);
        this.publicKey = loadPublicKey(inlinePubPem);
    }

    /** Test constructor with raw keys */
    public JwtService(PrivateKey priv, PublicKey pub) {
        this.privateKey = priv;
        this.publicKey = pub;
    }

    /**
     * D-117 测试入口：允许测试显式注入路径与内联 PEM，绕过不可变的 {@link System#getenv()}。
     * 仅包可见，供 JwtServiceTest 覆盖文件路径 / base64 内联分支。
     */
    static JwtService fromSources(String privKeyPath, String privInlineB64,
                                  String pubKeyPath, String pubInlineB64,
                                  String springPrivPem, String springPubPem) {
        PrivateKey priv = parsePrivateKey(resolvePem(
            privKeyPath, DEFAULT_PRIVATE_KEY_PATH,
            privInlineB64, springPrivPem, "private key"));
        PublicKey pub = parsePublicKey(resolvePem(
            pubKeyPath, DEFAULT_PUBLIC_KEY_PATH,
            pubInlineB64, springPubPem, "public key"));
        return new JwtService(priv, pub);
    }

    public String issue(long userId, String email, String name, String role) {
        Instant now = Instant.now();
        return Jwts.builder()
            .subject(String.valueOf(userId))
            .claim("email", email)
            .claim("name", name)
            .claim("role", role)
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plusSeconds(3600)))
            .signWith(privateKey, Jwts.SIG.RS256)
            .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser()
            .verifyWith(publicKey)
            .build()
            .parseSignedClaims(token)
            .getPayload();
    }

    public PublicKey getPublicKey() {
        return publicKey;
    }

    // ---------------------------------------------------------------------------
    // 密钥加载 — D-117
    // ---------------------------------------------------------------------------

    /**
     * 加载 RSA 私钥。优先级见类注释。
     *
     * @param inlinePem 来自 Spring 属性 jwt.rsa.private-key-pem 的内联 PEM（可为空，dev 兼容）
     * @return 解析后的 PrivateKey
     * @throws IllegalStateException 当所有来源均不可用且无法解析时
     */
    private static PrivateKey loadPrivateKey(String inlinePem) {
        String pem = resolvePemFromEnv(
            "JWT_PRIVATE_KEY_PATH", DEFAULT_PRIVATE_KEY_PATH,
            "JWT_PRIVATE_KEY", inlinePem,
            "private key");
        return parsePrivateKey(pem);
    }

    /**
     * 加载 RSA 公钥。优先级见类注释。
     *
     * @param inlinePem 来自 Spring 属性 jwt.rsa.public-key-pem 的内联 PEM（可为空，dev 兼容）
     * @return 解析后的 PublicKey
     * @throws IllegalStateException 当所有来源均不可用且无法解析时
     */
    private static PublicKey loadPublicKey(String inlinePem) {
        String pem = resolvePemFromEnv(
            "JWT_PUBLIC_KEY_PATH", DEFAULT_PUBLIC_KEY_PATH,
            "JWT_PUBLIC_KEY", inlinePem,
            "public key");
        return parsePublicKey(pem);
    }

    /**
     * D-117 生产路径：从 {@link System#getenv()} 读取路径与 base64 内联变量，
     * 再委托 {@link #resolvePem} 完成文件读取 / base64 解码 / Spring 兜底。
     */
    private static String resolvePemFromEnv(String pathEnv, String defaultPath,
                                            String inlineEnv, String springPem,
                                            String label) {
        String path = System.getenv().getOrDefault(pathEnv, defaultPath);
        String inline = System.getenv(inlineEnv);
        return resolvePem(path, defaultPath, inline, springPem, label);
    }

    /**
     * D-117 解析 PEM 字符串，优先级：
     *   1) {@code path} 指向的文件（生产为 env 默认值，测试为显式临时文件路径）
     *   2) {@code inlineB64} base64 内联（生产为 env 值，测试为显式 base64）
     *   3) {@code springPem} Spring 属性（dev 兼容，例如 jwt-rs256.env 单行 PEM）
     *
     * @param path        PEM 文件路径（null 时回退到 defaultPath）
     * @param defaultPath 默认路径（K8s Secret 挂载点）
     * @param inlineB64   base64 编码的内联 PEM（可为 null）
     * @param springPem   Spring 属性注入的内联 PEM（可为空）
     * @param label       报错时使用的可读名称（"private key" / "public key"）
     * @return PEM 文本
     * @throws IllegalStateException 当无任何来源可用时
     */
    static String resolvePem(String path, String defaultPath,
                             String inlineB64, String springPem,
                             String label) {
        // 1) 文件路径
        String resolvedPath = (path != null && !path.isBlank()) ? path : defaultPath;
        Path file = Path.of(resolvedPath);
        if (Files.isRegularFile(file)) {
            try {
                String content = Files.readString(file, StandardCharsets.UTF_8).trim();
                if (!content.isEmpty()) {
                    return content;
                }
            } catch (IOException e) {
                throw new IllegalStateException(
                    "Failed to read " + label + " from " + resolvedPath + ": " + e.getMessage(), e);
            }
        }

        // 2) base64 内联
        if (inlineB64 != null && !inlineB64.isBlank()) {
            try {
                byte[] decoded = Base64.getDecoder().decode(inlineB64.trim());
                return new String(decoded, StandardCharsets.UTF_8).trim();
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(
                    "Inline " + label + " value is set but is not valid base64: " + e.getMessage(), e);
            }
        }

        // 3) Spring 属性（dev 向后兼容，例如 jwt-rs256.env 中的单行 PEM）
        if (springPem != null && !springPem.isBlank()) {
            return springPem;
        }

        // 4) 全部缺失 — 清晰报错，绝不静默失败
        throw new IllegalStateException(
            "JWT " + label + " not found. Configure one of:\n"
            + "  - JWT_*_KEY_PATH pointing to a PEM file (default: " + defaultPath + ", "
            + "typically mounted from a K8s Secret at /etc/jwt/)\n"
            + "  - JWT_*_KEY base64-encoded inline env var\n"
            + "  - jwt.rsa.private-key-pem/public-key-pem Spring property (dev only)\n"
            + "See D-117 / docs/adr/D-117-jwt-key-externalization.md for the migration path.");
    }

    public static PrivateKey parsePrivateKey(String pem) {
        try {
            String body = stripPem(pem);
            PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(Base64.getDecoder().decode(body));
            return KeyFactory.getInstance("RSA").generatePrivate(spec);
        } catch (Exception e) {
            throw new IllegalArgumentException("bad private key pem", e);
        }
    }

    public static PublicKey parsePublicKey(String pem) {
        try {
            String body = stripPem(pem);
            X509EncodedKeySpec spec = new X509EncodedKeySpec(Base64.getDecoder().decode(body));
            return KeyFactory.getInstance("RSA").generatePublic(spec);
        } catch (Exception e) {
            throw new IllegalArgumentException("bad public key pem", e);
        }
    }

    public static String stripPem(String pem) {
        return pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
    }
}
