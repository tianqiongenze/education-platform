package com.ep.web.common.audit;

import org.aspectj.lang.annotation.Aspect;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Risk-control P1-8: registers the {@link AuditLogAspect} (annotation-driven
 * audit logging, see {@link AuditLog}) for every service with ep-web-common on
 * the classpath. AspectJ weaving requires {@code aspectjweaver} on the classpath
 * (already present via sentinel/resilience4j in this platform); without it the
 * aspect bean is skipped harmlessly.
 *
 * <p>Switchable via {@code ep.audit.enabled} (default ON).</p>
 */
@AutoConfiguration
@ConditionalOnClass(Aspect.class)
@ConditionalOnProperty(name = "ep.audit.enabled", havingValue = "true", matchIfMissing = true)
public class AuditLogAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public AuditLogAspect auditLogAspect() {
        return new AuditLogAspect();
    }

    @Bean
    @ConditionalOnMissingBean
    public AuditLogger auditLogger() {
        return new AuditLogger();
    }
}
