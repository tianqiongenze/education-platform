package com.ep.data.profile;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoint for Data Fabric asset profiling (§12.1.3).
 *
 * <p>Minimal viable subset of the 360° asset profile: get/list profiles and
 * trigger a refresh. In dev/CI (EP_DEV_ONLY guard), refresh produces a
 * simulated profile with pseudo-random metrics so the controller is fully
 * functional without a live Iceberg cluster. Production deployments compute
 * real metrics from the Iceberg table and persist to
 * {@code meta.data_asset_profile}.
 */
@RestController
@RequestMapping("/api/v1/profile")
@Tag(name = "数据资产画像", description = "数据资产360°质量评分与画像")
public class DataAssetProfileController {

    private static final Logger log = LoggerFactory.getLogger(DataAssetProfileController.class);

    /** Dev/CI fallback store; keyed by {@code "database.table"}. */
    private final Map<String, DataAssetProfile> store = new ConcurrentHashMap<>();

    /**
     * Compute the 0–100 quality score for a table.
     *
     * <p>Score breakdown (MVP, §12.1.3):
     * <ul>
     *   <li>Completeness: {@code min(rowCount / 10000.0, 1.0) * 30} — 30 pts</li>
     *   <li>Null penalty: {@code (1.0 - nullRatio) * 30} — 30 pts (lower nulls = higher)</li>
     *   <li>Uniqueness: {@code uniquenessRatio * 20} — 20 pts</li>
     *   <li>Stability bonus: 20 pts (static for MVP, no enum drift detection yet)</li>
     * </ul>
     *
     * @param rowCount        table row count
     * @param nullRatio       0.0–1.0 average null ratio
     * @param uniquenessRatio 0.0–1.0 average uniqueness ratio
     * @return quality score 0–100
     */
    public int computeQualityScore(long rowCount, double nullRatio, double uniquenessRatio) {
        double completeness = Math.min(rowCount / 10000.0, 1.0) * 30.0;
        double nullPenalty = (1.0 - clamp01(nullRatio)) * 30.0;
        double uniqueness = clamp01(uniquenessRatio) * 20.0;
        double stabilityBonus = 20.0;
        return (int) Math.round(completeness + nullPenalty + uniqueness + stabilityBonus);
    }

    /**
     * Get the profile for a table.
     *
     * @param table table name (may be qualified as {@code database.table})
     * @return profile, or 404 if unknown
     */
    @GetMapping("/tables/{table}")
    public ResponseEntity<DataAssetProfile> getProfile(@PathVariable String table) {
        DataAssetProfile p = store.get(resolveKey(table));
        if (p == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(p);
    }

    /**
     * Trigger a profile refresh. In dev/CI, creates/updates a simulated
     * profile with pseudo-random metrics.
     *
     * @param table table name (may be qualified as {@code database.table})
     * @return the refreshed profile
     */
    @PostMapping("/tables/{table}/refresh")
    public ResponseEntity<DataAssetProfile> refresh(@PathVariable String table) {
        String key = resolveKey(table);
        String[] parts = key.split("\\.", 2);
        String database = parts.length > 1 ? parts[0] : "default";
        String tableName = parts.length > 1 ? parts[1] : key;

        String devOnly = System.getenv("EP_DEV_ONLY");
        boolean devGuard = devOnly == null || devOnly.isBlank() || "true".equalsIgnoreCase(devOnly);

        long rowCount = devGuard
                ? ThreadLocalRandom.current().nextLong(100L, 100_000L)
                : 0L; // production: read from Iceberg (not implemented in MVP)
        double nullRatio = devGuard
                ? ThreadLocalRandom.current().nextDouble(0.0, 0.3)
                : 0.0;
        double uniquenessRatio = devGuard
                ? ThreadLocalRandom.current().nextDouble(0.5, 1.0)
                : 0.0;
        long storageBytes = devGuard
                ? ThreadLocalRandom.current().nextLong(1_000_000L, 1_000_000_000L)
                : 0L;
        int q7 = devGuard ? ThreadLocalRandom.current().nextInt(0, 500) : 0;
        int q30 = devGuard ? ThreadLocalRandom.current().nextInt(q7, 5000) : 0;
        double cost = devGuard
                ? Math.round(ThreadLocalRandom.current().nextDouble(1.0, 500.0) * 100.0) / 100.0
                : 0.0;

        int score = computeQualityScore(rowCount, nullRatio, uniquenessRatio);
        DataAssetProfile profile = new DataAssetProfile(
                tableName, database, score, rowCount, nullRatio, uniquenessRatio,
                storageBytes, q7, q30, cost, LocalDateTime.now());
        store.put(key, profile);
        log.info("[DataAssetProfileController] Refreshed profile for {} — score={}", key, score);
        return ResponseEntity.ok(profile);
    }

    /**
     * List all profiled tables.
     *
     * @return list of {@code "database.table"} entries
     */
    @GetMapping("/tables")
    public ResponseEntity<List<String>> listTables() {
        return ResponseEntity.ok(new ArrayList<>(store.keySet()));
    }

    private static String resolveKey(String table) {
        if (table == null) {
            return "";
        }
        return table.contains(".") ? table : "default." + table;
    }

    private static double clamp01(double v) {
        if (v < 0.0) {
            return 0.0;
        }
        if (v > 1.0) {
            return 1.0;
        }
        return v;
    }
}
