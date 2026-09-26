package com.ep.data;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * D-230: activation condition implementing the "default-on CRDB" semantics.
 *
 * <p>Truth table:
 * <pre>
 * ep.data.enabled (explicit) | endpoint available | activated
 * ---------------------------+--------------------+-----------
 * true                       | (any)              | yes (fail-fast if endpoint is bad — resolveUrl throws)
 * false                      | (any)              | no  (explicit opt-out: dev-local H2, unit tests)
 * missing                    | yes (ep.data.write.url / ep.data.write.host /
 *                            |      spring.datasource.url) | yes — DEFAULT-ON CRDB
 * missing                    | no                 | no  (safety valve: bare test
 *                            |                    |  contexts stay inert)
 * </pre>
 *
 * <p>"Endpoint available" means one of: {@code ep.data.write.url},
 * {@code ep.data.write.host}, or the legacy {@code spring.datasource.url}
 * (inherited as the endpoint in {@link EpDataAutoConfiguration#resolveUrl}).
 * This keeps {@code matchIfMissing}-style default activation from bricking
 * zero-config Spring-Boot test slices (the ESP reference design uses plain
 * {@code matchIfMissing=true}, which forced every dev profile to opt out
 * explicitly; the valve makes the opt-out automatic when there is nothing to
 * route to).
 */
public class EpDataEnabledCondition implements Condition {

    static final String[] ENDPOINT_KEYS = {
            "ep.data.write.url", "ep.data.write.host", "spring.datasource.url"
    };

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        var env = context.getEnvironment();
        String explicit = env.getProperty("ep.data.enabled");
        if (explicit != null) {
            return Boolean.parseBoolean(explicit.trim());
        }
        // Default-on only when there is somewhere to connect.
        for (String key : ENDPOINT_KEYS) {
            String v = env.getProperty(key);
            if (v != null && !v.isBlank()) {
                return true;
            }
        }
        return false;
    }
}
