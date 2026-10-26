package com.ep.web.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.NoOpPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * P1-4: Actuator endpoint security auto-configuration.
 *
 * <p>Secures Spring Boot Actuator endpoints exposed under {@code /actuator/**} so
 * that sensitive operational endpoints (prometheus, metrics, circuitbreakers,
 * env, ...) are no longer accessible without authentication. Health and info
 * remain public so liveness/readiness probes and discovery continue to work.</p>
 *
 * <p>Behavior:
 * <ul>
 *   <li>{@code /actuator/health} (+ subpaths, for K8s probes) and {@code /actuator/info} — permitAll</li>
 *   <li>all other {@code /actuator/**} — require role {@code ACTUATOR}</li>
 *   <li>authentication via HTTP Basic</li>
 *   <li>stateless (no HTTP session created)</li>
 *   <li>non-actuator paths are NOT matched — individual services retain full
 *       control over their own security filter chains (lower priority).</li>
 * </ul>
 *
 * <p>The filter chain is ordered with the highest priority ({@link Ordered#HIGHEST_PRECEDENCE})
 * so it intercepts actuator requests before any service-defined chain can permit
 * them. It uses a dedicated {@link org.springframework.security.web.util.matcher.AntPathRequestMatcher}
 * scope, leaving service chains free to define their own security for the rest
 * of the application.</p>
 *
 * <p>Configuration properties:
 * <ul>
 *   <li>{@code ep.actuator.security.enabled} — toggle (default {@code true})</li>
 *   <li>{@code ep.actuator.security.password} — password for the {@code actuator}
 *       user (default {@code actuator-secret}, intended for dev-local; override
 *       via {@code EP_ACTUATOR_PASSWORD} in production)</li>
 * </ul>
 *
 * <p>This auto-configuration is registered via
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}.
 * It only activates when Spring Security is on the classpath (i.e. the consumer
 * depends on {@code spring-boot-starter-security}).</p>
 */
@AutoConfiguration
@ConditionalOnClass(name = "org.springframework.security.web.SecurityFilterChain")
@ConditionalOnProperty(name = "ep.actuator.security.enabled", havingValue = "true", matchIfMissing = true)
@EnableWebSecurity
public class ActuatorSecurityAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ActuatorSecurityAutoConfiguration.class);

    /** Username for the single in-memory actuator principal. */
    public static final String ACTUATOR_USERNAME = "actuator";

    /** Default password used in dev-local; overridable via property / env var. */
    public static final String DEFAULT_PASSWORD = "actuator-secret";

    /** Role required to invoke non-public actuator endpoints. */
    public static final String ACTUATOR_ROLE = "ACTUATOR";

    /**
     * Security filter chain dedicated to {@code /actuator/**} endpoints.
     *
     * <p>Ordered at {@link Ordered#HIGHEST_PRECEDENCE} so it wins over any
     * service-defined {@link SecurityFilterChain} that might otherwise permit
     * actuator paths. Only {@code /actuator/**} is secured here; the rest of
     * the application is untouched and remains the responsibility of the
     * consuming service.</p>
     *
     * @param http the {@link HttpSecurity} builder
     * @return the configured {@link SecurityFilterChain}
     * @throws Exception if the security configuration fails
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public SecurityFilterChain actuatorSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/actuator/**")
                .authorizeHttpRequests(authz -> authz
                        // D-232: health probes must stay anonymous — K8s
                        // startup/liveness/readiness probes cannot send Basic
                        // credentials; a 401 on the startup probe kills
                        // healthy pods in a boot loop.
                        .requestMatchers("/actuator/health", "/actuator/health/**",
                                "/actuator/info", "/actuator/info/**").permitAll()
                        .requestMatchers("/actuator/**").hasRole(ACTUATOR_ROLE)
                        .anyRequest().denyAll())
                .httpBasic(basic -> {
                })
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable());
        return http.build();
    }

    /**
     * In-memory {@link UserDetailsService} with a single {@code actuator} user.
     *
     * <p>Password is read from {@code ep.actuator.security.password} (default
     * {@value #DEFAULT_PASSWORD}). For production, set the property or the
     * {@code EP_ACTUATOR_PASSWORD} environment variable to a strong secret.</p>
     *
     * @param password the actuator user password
     * @return an {@link InMemoryUserDetailsManager} containing the actuator user
     */
    @Bean
    public UserDetailsService actuatorUserDetailsService(
            @Value("${ep.actuator.security.password:" + DEFAULT_PASSWORD + "}") String password) {
        log.info("P1-4: Actuator security enabled — user='{}', role='{}'", ACTUATOR_USERNAME, ACTUATOR_ROLE);
        UserDetails actuatorUser = User.withUsername(ACTUATOR_USERNAME)
                .password(password)
                .roles(ACTUATOR_ROLE)
                .build();
        return new InMemoryUserDetailsManager(actuatorUser);
    }

    /**
     * Password encoder for the in-memory actuator user.
     *
     * <p>Uses {@link NoOpPasswordEncoder} because the password is supplied
     * verbatim from configuration (it is not a hashed credential). This bean
     * is intentionally named to avoid colliding with any service-defined
     * {@code PasswordEncoder}; if the consumer defines its own, this one can
     * be overridden with {@code @Primary}.</p>
     *
     * @return a {@link PasswordEncoder} that compares raw passwords
     */
    @Bean
    public PasswordEncoder actuatorPasswordEncoder() {
        return NoOpPasswordEncoder.getInstance();
    }
}
