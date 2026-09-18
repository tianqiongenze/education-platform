package com.ep.data.nlquery;

/**
 * Configuration for the NL Query Text-to-SQL module (ADR-D08, §12.4.1).
 *
 * <p>Holds the AI Gateway connection parameters and LLM generation
 * settings. The {@link #fromEnv()} static factory reads from environment
 * variables (with sensible defaults) so the module is usable in dev/CI
 * without any external configuration.
 *
 * <p>Implemented as a Java record per D-040 (GraalVM AOT compatible).
 *
 * <p>Note: {@code gatewayUrl} must be the base URL of the AI gateway
 * (e.g. {@code http://localhost:8000}); the {@link AiGatewayClient} appends
 * the {@code /v1/chat/completions} path at call time.
 *
 * @param gatewayUrl  base URL of the AI Gateway (no path)
 * @param model       LLM model id (e.g. {@code gpt-4o})
 * @param temperature sampling temperature (0.0 = deterministic)
 * @param timeoutSec  HTTP timeout in seconds
 * @param maxTokens   maximum tokens for LLM generation
 */
public record NlQueryConfig(
        String gatewayUrl,
        String model,
        double temperature,
        int timeoutSec,
        int maxTokens
) {

    /** Default AI gateway base URL. */
    public static final String DEFAULT_GATEWAY_URL = "http://localhost:8000";

    /** Default LLM model id. */
    public static final String DEFAULT_MODEL = "gpt-4o";

    /** Default sampling temperature — deterministic generation. */
    public static final double DEFAULT_TEMPERATURE = 0.0;

    /** Default HTTP timeout in seconds. */
    public static final int DEFAULT_TIMEOUT_SEC = 10;

    /** Default maximum token count for LLM generation. */
    public static final int DEFAULT_MAX_TOKENS = 4096;

    /**
     * Build a {@link NlQueryConfig} from environment variables, applying
     * defaults for any missing/blank values.
     *
     * <p>Environment variables:
     * <ul>
     *   <li>{@code EP_AI_GATEWAY_URL} — default {@value #DEFAULT_GATEWAY_URL}</li>
     *   <li>{@code EP_AI_LLM_MODEL} — default {@value #DEFAULT_MODEL}</li>
     *   <li>{@code EP_AI_LLM_TEMPERATURE} — default {@value #DEFAULT_TEMPERATURE}</li>
     *   <li>{@code EP_AI_LLM_TIMEOUT_SEC} — default {@value #DEFAULT_TIMEOUT_SEC}</li>
     *   <li>{@code EP_AI_LLM_MAX_TOKENS} — default {@value #DEFAULT_MAX_TOKENS}</li>
     * </ul>
     *
     * @return resolved configuration
     */
    public static NlQueryConfig fromEnv() {
        String gatewayUrl = resolveEnv("EP_AI_GATEWAY_URL", DEFAULT_GATEWAY_URL);
        String model = resolveEnv("EP_AI_LLM_MODEL", DEFAULT_MODEL);
        double temperature = resolveDoubleEnv("EP_AI_LLM_TEMPERATURE", DEFAULT_TEMPERATURE);
        int timeoutSec = resolveIntEnv("EP_AI_LLM_TIMEOUT_SEC", DEFAULT_TIMEOUT_SEC);
        int maxTokens = resolveIntEnv("EP_AI_LLM_MAX_TOKENS", DEFAULT_MAX_TOKENS);
        return new NlQueryConfig(gatewayUrl, model, temperature, timeoutSec, maxTokens);
    }

    private static String resolveEnv(String key, String fallback) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) {
            return fallback;
        }
        return v.trim();
    }

    private static double resolveDoubleEnv(String key, double fallback) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) {
            return fallback;
        }
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
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
