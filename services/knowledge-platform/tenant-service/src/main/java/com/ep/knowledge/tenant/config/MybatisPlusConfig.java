package com.ep.knowledge.tenant.config;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.ep.knowledge.tenant.context.TenantContext;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.StringValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Replaces the hand-rolled {@code TenantInterceptor} (which reflected into entity
 * fields and walked {@code QueryWrapper} maps) with MyBatis-Plus's built-in
 * {@link TenantLineInnerInterceptor}.
 *
 * <p>The interceptor auto-appends {@code tenant_id = ?} to SELECT/UPDATE/DELETE
 * statements and auto-fills {@code tenant_id} on INSERT for any table listed in
 * {@link TenantTables}. The current tenant id is sourced from
 * {@link TenantContext} via the {@link TenantLineHandler} below.
 */
@Configuration
public class MybatisPlusConfig {

    /**
     * Set of tables that should be transparently scoped by tenant_id.
     * Tables that genuinely have no tenant column (e.g. {@code tenant} itself,
     * which IS the tenant registry) must be excluded.
     */
    private static final java.util.Set<String> TENANT_TABLES = java.util.Set.of(
            "tenant_api_key",
            "agent",
            "agent_session",
            "category",
            "document"
    );

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(new TenantLineHandler() {
            @Override
            public Expression getTenantId() {
                String tenantId = TenantContext.getTenantId();
                if (tenantId == null || tenantId.isBlank()) {
                    // No tenant context: use a sentinel value that matches nothing,
                    // so tenant-scoped tables return no rows rather than leaking data.
                    return new StringValue("__NO_TENANT__");
                }
                return new StringValue(tenantId);
            }

            @Override
            public String getTenantIdColumn() {
                return "tenant_id";
            }

            @Override
            public boolean ignoreTable(String tableName) {
                if (tableName == null) {
                    return true;
                }
                // Normalise in case MyBatis-Plus passes a schema-qualified or backtick-quoted name.
                String normalised = tableName.replace("`", "");
                int dot = normalised.lastIndexOf('.');
                if (dot >= 0) {
                    normalised = normalised.substring(dot + 1);
                }
                return !TENANT_TABLES.contains(normalised.toLowerCase());
            }
        }));
        return interceptor;
    }
}
