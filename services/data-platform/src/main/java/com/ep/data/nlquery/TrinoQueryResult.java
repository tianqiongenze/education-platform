package com.ep.data.nlquery;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Result of executing a Trino SQL query (D-190).
 *
 * <p>When {@code success} is {@code true}, the result contains the column
 * names and row data. When {@code false}, {@code error} contains a
 * human-readable error message and {@code rows} is empty.
 *
 * <p>Implemented as a Java record per D-040 (GraalVM AOT compatible).
 *
 * @param success  whether the query executed successfully
 * @param columns  column labels (empty on failure)
 * @param rows     result rows as maps of column→value (empty on failure)
 * @param rowCount number of rows returned
 * @param error    error message (null on success)
 */
public record TrinoQueryResult(
        boolean success,
        List<String> columns,
        List<Map<String, Object>> rows,
        int rowCount,
        String error
) {

    /** Create an empty/failed result with an error message. */
    public static TrinoQueryResult empty(String error) {
        return new TrinoQueryResult(false, new ArrayList<>(), new ArrayList<>(), 0, error);
    }
}
