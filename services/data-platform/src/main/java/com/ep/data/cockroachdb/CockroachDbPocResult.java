package com.ep.data.cockroachdb;

/**
 * D-194: Result of a single CockroachDB PoC verification step.
 *
 * @param verificationItem short name of the verification (e.g. "REGIONAL_BY_ROW")
 * @param status           PASS / FAIL / SKIP
 * @param details          human-readable details
 * @param latencyMs        measured latency where applicable, 0 otherwise
 */
public record CockroachDbPocResult(
        String verificationItem,
        Status status,
        String details,
        long latencyMs
) {
    public enum Status { PASS, FAIL, SKIP }

    public static CockroachDbPocResult pass(String item, String details, long latencyMs) {
        return new CockroachDbPocResult(item, Status.PASS, details, latencyMs);
    }

    public static CockroachDbPocResult fail(String item, String details) {
        return new CockroachDbPocResult(item, Status.FAIL, details, 0L);
    }

    public static CockroachDbPocResult skip(String item, String details) {
        return new CockroachDbPocResult(item, Status.SKIP, details, 0L);
    }
}
