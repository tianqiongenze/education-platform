package com.ep.data.lineage;

/**
 * A single directed column-level lineage edge in the Data Fabric (§12.1.2).
 *
 * <p>Represents a data-flow relationship: {@code sourceTable.sourceColumn}
 * feeds into {@code targetTable.targetColumn}. Edges are produced by the
 * rule-based {@link LineageParser} (confidence=1.0, inferredBy="rule") or,
 * in future M4 phases, by the GNN-based layer (inferredBy="gnn", lower
 * confidence).
 *
 * <p>Implemented as a Java record per D-040 (GraalVM AOT compatible).
 *
 * @param sourceTable  upstream table name
 * @param sourceColumn upstream column name ({@code "*"} for wildcard)
 * @param targetTable  downstream table name
 * @param targetColumn downstream column name ({@code "*"} for wildcard)
 * @param confidence   0.0–1.0 confidence score
 * @param inferredBy   inference method ({@code "rule"} or {@code "gnn"})
 */
public record LineageEdge(
        String sourceTable,
        String sourceColumn,
        String targetTable,
        String targetColumn,
        double confidence,
        String inferredBy
) {
}
