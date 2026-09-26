package com.ep.business.config;

import com.ep.business.auth.CaptchaService;
import com.ep.business.auth.JwtTokenService;
import com.ep.web.common.risk.LoginAttemptLimiter;
import com.ep.web.common.security.JwtBlacklistService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * D-227: beans for the /api/auth login closed loop.
 *
 * <ul>
 *   <li>{@link LoginAttemptLimiter} — D-219 lockout component, first wired to a
 *       real login endpoint here (5 failures / 15 min by default; Redis-backed
 *       with in-memory degradation when Redis is absent)</li>
 *   <li>{@link CaptchaService} — math captcha, Redis-backed with in-memory
 *       degradation</li>
 *   <li>{@link JwtTokenService} — RS256 issuer with jti; key resolution is lazy
 *       (missing key fails at first issue, not at boot)</li>
 * </ul>
 *
 * <p>The JWT revocation blacklist ({@code JwtBlacklistService}) and the
 * {@code JwtRevocationFilter} are auto-configured by ep-web-common
 * ({@code JwtRevocationAutoConfiguration}), not here.</p>
 */
@Configuration
public class AuthConfiguration {

    /** D-219 defaults: 5 failed attempts → 15-minute lockout. */
    @Bean
    @ConditionalOnMissingBean
    public LoginAttemptLimiter loginAttemptLimiter(
            ObjectProvider<StringRedisTemplate> redisProvider,
            @Value("${ep.auth.lockout.max-attempts:5}") int maxAttempts,
            @Value("${ep.auth.lockout.window-seconds:900}") long windowSeconds) {
        return new LoginAttemptLimiter(maxAttempts, windowSeconds * 1000, redisProvider.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    public CaptchaService captchaService(ObjectProvider<StringRedisTemplate> redisProvider) {
        return new CaptchaService(redisProvider.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    public JwtTokenService jwtTokenService(
            @Value("${jwt.rsa.private-key-pem:}") String privatePem,
            @Value("${ep.auth.jwt.ttl-seconds:3600}") long ttlSeconds) {
        return JwtTokenService.fromDefaultSources(privatePem, ttlSeconds);
    }

    /**
     * Backstop so AuthController's logout dependency always resolves in this
     * service regardless of ep-web-common auto-configuration ordering. The
     * auto-configured {@code JwtBlacklistService} (JwtRevocationAutoConfiguration)
     * takes precedence via {@code @ConditionalOnMissingBean} when present.
     */
    @Bean
    @ConditionalOnMissingBean
    public JwtBlacklistService jwtBlacklistService(ObjectProvider<StringRedisTemplate> redisProvider) {
        return new JwtBlacklistService(redisProvider.getIfAvailable());
    }
}
