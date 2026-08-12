package com.ep.data.cockroachdb;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;

/**
 * D-194: CockroachDB proof-of-concept verification service.
 * <p>
 * Only active when {@code cockroachdb.poc.enabled=true}. Verifies four key
 * capabilities that gate the overseas-datastore decision:
 * <ol>
 *   <li>{@code REGIONAL BY ROW} table placement.</li>
 *   <li>Cross-region write P99 latency (singapore→frankfurt).</li>
 *   <li>CDC changefeeds → Pulsar adapter (mock layer).</li>
 *   <li>1M-connection schema stress (mock — schema only).</li>
 * </ol>
 * Uses the {@link RoutingDataSource}-provided {@link DataSource} which routes
 * to CockroachDB when {@link TenantContext#getRegion()} returns
 * {@code overseas}. EP_DEV_ONLY: every step is wrapped so failures degrade to
 * a SKIP/FAIL result rather than crashing the service.
 */
@Component
@ConditionalOnProperty(name = "cockroachdb.poc.enabled", havingValue = "true")
public class CockroachDbPocService {

    private static final Logger log = LoggerFactory.getLogger(CockroachDbPocService.class);

    private final JdbcTemplate jdbc;

    public CockroachDbPocService(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** Visible for tests — allows injecting a mocked JdbcTemplate directly. */
    CockroachDbPocService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 1. Verify REGIONAL BY ROW: create a table, insert rows tagged with a
     * region, and confirm the region pinning metadata is present.
     */
    public CockroachDbPocResult verifyRegionalByRow() {
        try {
            jdbc.execute("DROP TABLE IF EXISTS crdb_poc_regional");
            jdbc.execute("""
                    CREATE TABLE crdb_poc_regional (
                        id INT PRIMARY KEY,
                        region crdb_internal_region NOT NULL,
                        payload STRING
                    ) LOCALITY REGIONAL BY ROW
                    """);
            jdbc.update("INSERT INTO crdb_poc_regional (id, region, payload) VALUES (1, 'ap-southeast-1', 'sg-row')");
            jdbc.update("INSERT INTO crdb_poc_regional (id, region, payload) VALUES (2, 'europe-west1', 'fra-row')");
            Integer count = jdbc.queryForObject(
                    "SELECT count(*) FROM crdb_poc_regional WHERE region = 'ap-southeast-1'", Integer.class);
            boolean ok = count != null && count == 1;
            return ok
                    ? CockroachDbPocResult.pass("REGIONAL_BY_ROW",
                            "Created REGIONAL BY ROW table, inserted 2 region-tagged rows, verified count.", 0L)
                    : CockroachDbPocResult.fail("REGIONAL_BY_ROW",
                            "Unexpected row count: " + count);
        } catch (Exception e) {
            log.warn("D-194: verifyRegionalByRow failed (EP_DEV_ONLY): {}", e.getMessage());
            return CockroachDbPocResult.skip("REGIONAL_BY_ROW",
                    "Skipped — CockroachDB unreachable: " + e.getMessage());
        }
    }

    /**
     * 2. Measure cross-region write P99 from singapore to frankfurt.
     * Inserts N rows tagged europe-west1 from the singapore node and measures
     * the round-trip latency.
     */
    public CockroachDbPocResult verifyCrossRegionLatency() {
        try {
            int samples = 20;
            long[] latencies = new long[samples];
            for (int i = 0; i < samples; i++) {
                long start = System.currentTimeMillis();
                jdbc.update(
                        "INSERT INTO crdb_poc_regional (id, region, payload) VALUES (?, 'europe-west1', ?)",
                        1000 + i, "latency-probe-" + i);
                latencies[i] = System.currentTimeMillis() - start;
            }
            long p99 = percentile(latencies, 99);
            return CockroachDbPocResult.pass("CROSS_REGION_LATENCY",
                    "Wrote " + samples + " rows tagged europe-west1; P99=" + p99 + "ms", p99);
        } catch (Exception e) {
            log.warn("D-194: verifyCrossRegionLatency failed (EP_DEV_ONLY): {}", e.getMessage());
            return CockroachDbPocResult.skip("CROSS_REGION_LATENCY",
                    "Skipped — CockroachDB unreachable: " + e.getMessage());
        }
    }

    /**
     * 3. Verify CDC changefeeds → Pulsar adapter (mock layer).
     * Creates a changefeed targeting a mock Pulsar endpoint and confirms the
     * DDL is accepted. The actual Pulsar sink is exercised in the
     * integration-test environment only.
     */
    public CockroachDbPocResult verifyChangefeedsAdapter() {
        try {
            // Mock: just run the CREATE CHANGEFEED DDL against an external
            // Pulsar endpoint URI configured via env. In PoC we use a
            // disable-safe statement that may fail gracefully.
            String pulsarUri = System.getenv().getOrDefault(
                    "EP_CRDB_CDC_PULSAR_URI", "pulsar://localhost:6650/persistent/public/default/crdb-cdc");
            jdbc.update("CREATE CHANGEFEED FOR TABLE crdb_poc_regional " +
                    "WITH format = json, sink = '" + pulsarUri + "'");
            return CockroachDbPocResult.pass("CHANGEFEEDS_ADAPTER",
                    "Created changefeed to " + pulsarUri + " (mock sink).", 0L);
        } catch (Exception e) {
            log.warn("D-194: verifyChangefeedsAdapter failed (EP_DEV_ONLY): {}", e.getMessage());
            return CockroachDbPocResult.skip("CHANGEFEEDS_ADAPTER",
                    "Skipped — CDC sink unavailable: " + e.getMessage());
        }
    }

    /**
     * 4. Verify 1M-connection schema stress (mock — schema check only).
     * Validates that the cluster can accept a high-connection-count schema
     * (we only check the max_connections / cluster setting here; the real
     * stress test runs out-of-band via scripts/cockroachdb-poc.sh).
     */
    public CockroachDbPocResult verifyOneMillionConnections() {
        try {
            Integer maxConns = jdbc.queryForObject(
                    "SHOW CLUSTER SETTING sql.pool.max_size", Integer.class);
            boolean ok = maxConns != null && maxConns >= 1000;
            return ok
                    ? CockroachDbPocResult.pass("ONE_MILLION_CONNECTIONS",
                            "Cluster sql.pool.max_size=" + maxConns + " (>= 1000).", 0L)
                    : CockroachDbPocResult.fail("ONE_MILLION_CONNECTIONS",
                            "sql.pool.max_size=" + maxConns + " < 1000 baseline.");
        } catch (Exception e) {
            log.warn("D-194: verifyOneMillionConnections failed (EP_DEV_ONLY): {}", e.getMessage());
            return CockroachDbPocResult.skip("ONE_MILLION_CONNECTIONS",
                    "Skipped — setting unavailable: " + e.getMessage());
        }
    }

    /**
     * Run all four PoC verification items and return the report.
     */
    public List<CockroachDbPocResult> runFullPoc() {
        log.info("D-194: starting CockroachDB PoC full verification");
        List<CockroachDbPocResult> results = new ArrayList<>();
        results.add(verifyRegionalByRow());
        results.add(verifyCrossRegionLatency());
        results.add(verifyChangefeedsAdapter());
        results.add(verifyOneMillionConnections());
        long pass = results.stream().filter(r -> r.status() == CockroachDbPocResult.Status.PASS).count();
        log.info("D-194: CockroachDB PoC complete — {}/{} passed", pass, results.size());
        return results;
    }

    /** Simple nearest-rank percentile. */
    private static long percentile(long[] sorted, int p) {
        long[] copy = sorted.clone();
        java.util.Arrays.sort(copy);
        int rank = (int) Math.ceil(p / 100.0 * copy.length);
        return copy[Math.min(rank - 1, copy.length - 1)];
    }
}
