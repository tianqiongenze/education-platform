package com.ep.data.lineage;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Hand-written, rule-based SQL lineage parser (§12.1.2 rule-based layer).
 *
 * <p>Extracts column-level {@link LineageEdge}s from {@code INSERT INTO ...
 * SELECT ... FROM ...} statements. This is intentionally a lightweight regex
 * / scanning parser — no external SQL parser dependency — so it stays AOT
 * friendly (D-040) and unit-testable without Spring.
 *
 * <p>Supported shapes:
 * <ul>
 *   <li>{@code INSERT INTO t (c1, c2) SELECT a, b FROM s} — positional map</li>
 *   <li>{@code INSERT INTO t (c1, c2) SELECT * FROM s} — wildcard source</li>
 *   <li>{@code INSERT INTO t SELECT a, b FROM s} — positional, no target list</li>
 *   <li>{@code INSERT INTO t SELECT a, b FROM s1 JOIN s2 ON ...} — multi-source</li>
 *   <li>{@code WITH cte AS (...) INSERT INTO t SELECT ... FROM cte} — CTE header stripped</li>
 * </ul>
 *
 * <p>This method is pure (no IO) and returns an empty list for non-INSERT
 * SQL, INSERT without SELECT, or null/blank input.
 */
@Component
public final class LineageParser {

    private static final double RULE_CONFIDENCE = 1.0;
    private static final String RULE_INFERRED_BY = "rule";

    /** Matches: INSERT INTO [db.]target [(c1, c2, ...)] <rest>. */
    private static final Pattern INSERT_HEAD = Pattern.compile(
            "INSERT\\s+INTO\\s+([`\\w.]+)" +          // 1: target table (optional db.)
            "(?:\\s*\\(([^)]*)\\))?" +                 // 2: optional target column list
            "\\s+(.*)",                                // 3: rest (SELECT ...)
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** Strips a leading WITH ... AS (...) preamble so INSERT becomes the head. */
    private static final Pattern CTE_PREFIX = Pattern.compile(
            "^\\s*WITH\\b.*?\\bINSERT\\s+INTO",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** Matches: FROM source [alias] [JOIN source2 [alias] ON ...] ... */
    private static final Pattern FROM_CLAUSE = Pattern.compile(
            "\\bFROM\\s+(.*?)(?=\\bWHERE\\b|\\bGROUP\\b|\\bORDER\\b|\\bLIMIT\\b|\\bHAVING\\b|$)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** Matches: SELECT <projections> FROM ... (captures projection list). */
    private static final Pattern SELECT_TO_FROM = Pattern.compile(
            "SELECT\\s+(.*?)\\bFROM\\b",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** Matches one table reference (backtick/qualified/alias) following FROM or JOIN. */
    private static final Pattern TABLE_REF = Pattern.compile(
            "\\b(?:FROM|JOIN)\\s+([`\\w.]+)",
            Pattern.CASE_INSENSITIVE);

    /**
     * Parse an SQL string into lineage edges.
     *
     * @param sql the SQL statement (may be null/blank)
     * @return extracted lineage edges (never null; empty when not parseable)
     */
    public List<LineageEdge> parseSql(String sql) {
        List<LineageEdge> edges = new ArrayList<>();
        if (sql == null || sql.isBlank()) {
            return edges;
        }

        String normalized = stripCteHeader(sql.trim());
        Matcher head = INSERT_HEAD.matcher(normalized);
        if (!head.matches()) {
            return edges;
        }

        String target = unquote(head.group(1));
        List<String> targetCols = parseColumnList(head.group(2));
        String rest = head.group(3) == null ? "" : head.group(3).trim();
        if (rest.isEmpty() || !rest.toUpperCase().startsWith("SELECT")) {
            return edges;
        }

        List<String> sourceTables = extractSourceTables(rest);
        if (sourceTables.isEmpty()) {
            return edges;
        }

        String selectProjection = extractSelectProjection(rest);
        List<String> selectItems = splitTopLevelCommas(selectProjection);
        // Trim each select item down to the projection expression (before any
        // alias) and strip surrounding backticks so identifiers are comparable.
        List<String> projections = new ArrayList<>(selectItems.size());
        for (String item : selectItems) {
            projections.add(unquote(stripAlias(item.trim())));
        }

        boolean wildcard = projections.stream().anyMatch(p -> p.equals("*") || p.endsWith(".*"));

        if (wildcard) {
            // SELECT * — cannot map individual columns; emit one wildcard edge per source.
            for (String src : sourceTables) {
                edges.add(new LineageEdge(src, "*", target, "*", RULE_CONFIDENCE, RULE_INFERRED_BY));
            }
            return edges;
        }

        if (targetCols.isEmpty()) {
            // No target column list — map each projection by position to "*".
            for (String proj : projections) {
                for (String src : sourceTables) {
                    edges.add(new LineageEdge(src, proj, target, "*", RULE_CONFIDENCE, RULE_INFERRED_BY));
                }
            }
            return edges;
        }

        // Positional mapping: targetCols[i] <- projections[i].
        int n = Math.min(targetCols.size(), projections.size());
        for (int i = 0; i < n; i++) {
            String proj = projections.get(i);
            for (String src : sourceTables) {
                edges.add(new LineageEdge(src, proj, target, targetCols.get(i),
                        RULE_CONFIDENCE, RULE_INFERRED_BY));
            }
        }
        return edges;
    }

    /** Strip a leading {@code WITH ... AS (...)} block so INSERT becomes head of the string. */
    private static String stripCteHeader(String sql) {
        if (CTE_PREFIX.matcher(sql).find()) {
            int insertIdx = locateInsert(sql);
            if (insertIdx >= 0) {
                return sql.substring(insertIdx).trim();
            }
        }
        return sql;
    }

    private static int locateInsert(String sql) {
        Matcher m = Pattern.compile("INSERT\\s+INTO", Pattern.CASE_INSENSITIVE).matcher(sql);
        return m.find() ? m.start() : -1;
    }

    /** Split a comma-separated column list like {@code "c1, c2, c3"} into trimmed names. */
    private static List<String> parseColumnList(String raw) {
        List<String> cols = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return cols;
        }
        for (String p : raw.split(",")) {
            String trimmed = p.trim();
            if (!trimmed.isEmpty()) {
                cols.add(unquote(trimmed));
            }
        }
        return cols;
    }

    /** Pull all source table names from the FROM/JOIN clauses of a SELECT body. */
    private static List<String> extractSourceTables(String selectSql) {
        // Apply TABLE_REF to the whole SELECT body so it can match the leading
        // FROM plus any JOIN clauses. (FROM_CLAUSE is only used to bound the
        // search region in callers that need it.)
        Matcher tm = TABLE_REF.matcher(selectSql);
        List<String> tables = new ArrayList<>();
        while (tm.find()) {
            tables.add(unquote(tm.group(1)));
        }
        return tables;
    }

    /** Extract the projection list (text between SELECT and FROM). */
    private static String extractSelectProjection(String selectSql) {
        Matcher sm = SELECT_TO_FROM.matcher(selectSql);
        if (sm.find()) {
            return sm.group(1).trim();
        }
        return "";
    }

    /** Remove surrounding backticks from an identifier. */
    private static String unquote(String ident) {
        if (ident == null) {
            return null;
        }
        String s = ident.trim();
        if (s.startsWith("`") && s.endsWith("`") && s.length() >= 2) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    /** Drop a trailing alias like {@code a AS x} or {@code a x} from a projection expression. */
    private static String stripAlias(String projection) {
        // Keep it simple: take everything before " AS " or a bare alias on simple names.
        int asIdx = indexOfKeyword(projection, " AS ");
        if (asIdx > 0) {
            return projection.substring(0, asIdx).trim();
        }
        return projection;
    }

    private static int indexOfKeyword(String s, String keyword) {
        String upper = s.toUpperCase();
        return upper.indexOf(keyword);
    }

    /** Split on top-level commas (commas not nested inside parentheses). */
    private static List<String> splitTopLevelCommas(String s) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '(') {
                depth++;
                cur.append(ch);
            } else if (ch == ')') {
                depth = Math.max(0, depth - 1);
                cur.append(ch);
            } else if (ch == ',' && depth == 0) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        if (!cur.isEmpty()) {
            out.add(cur.toString());
        }
        return out;
    }
}
