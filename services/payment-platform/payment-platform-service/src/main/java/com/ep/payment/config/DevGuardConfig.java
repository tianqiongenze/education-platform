package com.ep.payment.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * D-069: Iron Law 0 for Java — prevents dangerous operations in non-dev environments.
 * Mirrors the Python EP_DEV_ONLY guard pattern.
 * In prod profile, asserts that EP_DEV_ONLY is NOT set (or is false).
 */
@Configuration
@Profile("!dev")
public class DevGuardConfig {
    private static final Logger log = LoggerFactory.getLogger(DevGuardConfig.class);

    @Value("${EP_DEV_ONLY:false}")
    private String epDevOnly;

    @PostConstruct
    public void validate() {
        if ("true".equalsIgnoreCase(epDevOnly) || "1".equals(epDevOnly)) {
            log.error("FATAL: EP_DEV_ONLY=true detected in non-dev profile! Aborting startup to protect shared infrastructure.");
            throw new IllegalStateException("EP_DEV_ONLY must not be true in non-dev environments (Iron Law 0)");
        }
        log.info("Iron Law 0 guard active — EP_DEV_ONLY={}, profile=non-dev", epDevOnly);
    }
}
