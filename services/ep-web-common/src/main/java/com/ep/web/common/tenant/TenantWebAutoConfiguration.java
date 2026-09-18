package com.ep.web.common.tenant;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/**
 * Auto-configuration that registers the shared {@link TenantContextFilter}
 * for every service that has {@code ep-web-common} on its classpath.
 *
 * <p>Replaces the per-service {@code @Component TenantContextFilter} copies
 * that were duplicated across grading-service, core-business,
 * boardgame-service, payment-platform, senior-care, order-platform,
 * mcp-server, and tenant-service. Because this auto-configuration defaults to
 * enabled ({@code ep.tenant.filter.enabled} with
 * {@code matchIfMissing = true}), services that previously relied on the
 * component scan picking up their local {@code @Component TenantContextFilter}
 * continue to get the filter registered without any code change.
 *
 * <p>The filter is registered via a {@link FilterRegistrationBean} (rather
 * than relying solely on {@link TenantContextFilter @Component}) so that the
 * auto-configuration can:
 * <ul>
 *   <li>be toggled off with {@code ep.tenant.filter.enabled=false}, and</li>
 *   <li>set an explicit order ({@code HIGHEST_PRECEDENCE + 1}) that survives
 *       even when the consuming service does not component-scan this package.</li>
 * </ul>
 *
 * <p>Registered through
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}.
 */
@AutoConfiguration
@ConditionalOnClass(name = "jakarta.servlet.Filter")
@ConditionalOnProperty(name = "ep.tenant.filter.enabled", havingValue = "true", matchIfMissing = true)
public class TenantWebAutoConfiguration {

    /**
     * Registers {@link TenantContextFilter} at
     * {@code HIGHEST_PRECEDENCE + 1} so it runs immediately after trace-id
     * filters and before any business handler reads the tenant context.
     *
     * @return a {@link FilterRegistrationBean} wrapping the shared filter
     */
    @Bean
    public FilterRegistrationBean<TenantContextFilter> tenantContextFilterRegistration() {
        FilterRegistrationBean<TenantContextFilter> registration = new FilterRegistrationBean<>(
                new TenantContextFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
