package com.ep.data.nlquery;

/**
 * Configuration for Trino JDBC connections (D-190).
 *
 * <p>Holds the Trino coordinator URL, catalog, schema, user, and password
 * (optional). The {@link #fromEnv()} static factory reads from environment
 * variables with sensible defaults so the module is usable in dev/CI
 * without external configuration.
 *
 * <p>Implemented as a Java record per D-040 (GraalVM AOT compatible).
 *
 * <p>Environment variables:
 * <ul>
 *   <li>{@code TRINO_URL} — default {@value #DEFAULT_URL}</li>
 *   <li>{@code TRINO_CATALOG} — default {@value #DEFAULT_CATALOG}</li>
 *   <li>{@code TRINO_SCHEMA} — default {@value #DEFAULT_SCHEMA}</li>
 *   <li>{@code TRINO_USER} — default {@value #DEFAULT_USER}</li>
 *   <li>{@code TRINO_PASSWORD} — default empty (no auth)</li>
 *   <li>{@code TRINO_QUERY_TIMEOUT_SEC} — default {@value #DEFAULT_QUERY_TIMEOUT_SEC}</li>
 * </ul>
 *
 * @param url               Trino coordinator JDBC URL
 * @param catalog           default catalog (e.g. iceberg)
 * @param schema            default schema
 * @param user              Trino user
 * @param password          Trino password (empty for no-auth)
 * @param queryTimeoutSec   query timeout in seconds
 */
public record TrinoConfig(
        String url,
        String catalog,
        String schema,
        String user,
        String password,
        int queryTimeoutSec
) {

    /** Default Trino coordinator URL. */
    public static final String DEFAULT_URL = "jdbc:trino://localhost:8080";

    /** Default Trino catalog (Iceberg). */
    public static final String DEFAULT_CATALOG = "iceberg";

    /** Default schema. */
    public static final String DEFAULT_SCHEMA = "default";

    /** Default Trino user. */
    public static final String DEFAULT_USER = "ep-service";

    /** Default query timeout. */
    public static final int DEFAULT_QUERY_TIMEOUT_SEC = 30;

    /**
     * Build a {@link TrinoConfig} from environment variables, applying
     * defaults for missing/blank values.
     *
     * @return resolved configuration
     */
    public static TrinoConfig fromEnv() {
        String url = resolveEnv("TRINO_URL", DEFAULT_URL);
        String catalog = resolveEnv("TRINO_CATALOG", DEFAULT_CATALOG);
        String schema = resolveEnv("TRINO_SCHEMA", DEFAULT_SCHEMA);
        String user = resolveEnv("TRINO_USER", DEFAULT_USER);
        String password = resolveEnv("TRINO_PASSWORD", "");
        int timeoutSec = resolveIntEnv("TRINO_QUERY_TIMEOUT_SEC", DEFAULT_QUERY_TIMEOUT_SEC);
        return new TrinoConfig(url, catalog, schema, user, password, timeoutSec);
    }

    /**
     * Build the full JDBC URL with catalog and schema path.
     *
     * @return JDBC URL like {@code jdbc:trino://host:port/catalog/schema}
     */
    public String fullUrl() {
        String base = url;
        if (base == null || base.isBlank()) {
            base = DEFAULT_URL;
        }
        // Strip trailing slash if present.
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        // If the URL already contains catalog/schema, don't append again.
        String path = "/" + catalog + "/" + schema;
        if (base.contains(path)) {
            return base;
        }
        // Check if URL already has a path beyond host:port.
        String afterJdbc = base.replaceFirst("^jdbc:trino://", "");
        long slashCount = afterJdbc.chars().filter(c -> c == '/').count();
        if (slashCount >= 2) {
            // Already has catalog/schema in the URL.
            return base;
        }
        return base + path;
    }

    private static String resolveEnv(String key, String fallback) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) {
            return fallback;
        }
        return v.trim();
    }

    private static int resolveIntEnv(String key, int fallback) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
