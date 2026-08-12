package com.ep.data.profile;

import java.time.LocalDateTime;

/**
 * 360° quality profile for a Data Fabric table (§12.1.3).
 *
 * <p>Implemented as a Java record per D-040 (GraalVM AOT compatible). The
 * {@code qualityScore} is computed by
 * {@link DataAssetProfileController#computeQualityScore} from
 * {@code rowCount}, {@code nullRatio} and {@code uniquenessRatio}.
 *
 * @param tableName       table name (qualified as {@code database.table})
 * @param database        database (schema) name
 * @param qualityScore    0–100 quality score
 * @param rowCount        approximate row count
 * @param nullRatio       0.0–1.0 average null ratio
 * @param uniquenessRatio 0.0–1.0 average uniqueness ratio
 * @param storageBytes    on-disk storage size in bytes
 * @param queryCount7d    query count in the last 7 days
 * @param queryCount30d   query count in the last 30 days
 * @param costMonthly     estimated monthly cost
 * @param updatedAt       last refresh timestamp
 */
public record DataAssetProfile(
        String tableName,
        String database,
        int qualityScore,
        long rowCount,
        double nullRatio,
        double uniquenessRatio,
        long storageBytes,
        int queryCount7d,
        int queryCount30d,
        double costMonthly,
        LocalDateTime updatedAt
) {
}
