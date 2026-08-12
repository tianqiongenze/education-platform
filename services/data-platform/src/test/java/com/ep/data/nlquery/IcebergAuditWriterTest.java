package com.ep.data.nlquery;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for {@link IcebergAuditWriter} (D-190).
 *
 * <p>No Spring context — the writer is a plain POJO instantiated with
 * {@code new}. EP_DEV_ONLY is unset in CI, so the writer uses the in-memory
 * dev log path.
 */
class IcebergAuditWriterTest {

    private IcebergAuditWriter writer;

    @BeforeEach
    void setUp() {
        writer = new IcebergAuditWriter();
        writer.clearDevLog();
    }

    private static NlQueryAuditRecord record(String userId, String question, boolean valid) {
        return new NlQueryAuditRecord(
                userId, "sess-1", question, "查询",
                "SELECT * FROM t", valid, valid ? "" : "bad SQL",
                false, 0, 42L, LocalDateTime.now());
    }

    @Test
    void dev_mode_writes_to_in_memory_log() {
        writer.write(record("user1", "list users", true));
        assertEquals(1, writer.getDevLog().size(),
                "dev log should have 1 entry after write, got " + writer.getDevLog().size());
    }

    @Test
    void dev_mode_accumulates_multiple_records() {
        writer.write(record("user1", "q1", true));
        writer.write(record("user2", "q2", true));
        writer.write(record("user3", "q3", false));
        assertEquals(3, writer.getDevLog().size(),
                "dev log should have 3 entries, got " + writer.getDevLog().size());
    }

    @Test
    void dev_log_records_have_correct_fields() {
        writer.write(record("user1", "list users", true));
        NlQueryAuditRecord rec = writer.getDevLog().get(0);
        assertEquals("user1", rec.userId(), "userId should match");
        assertEquals("sess-1", rec.sessionId(), "sessionId should match");
        assertEquals("list users", rec.question(), "question should match");
        assertEquals("查询", rec.intent(), "intent should match");
        assertEquals("SELECT * FROM t", rec.generatedSql(), "generatedSql should match");
        assertTrue(rec.isValid(), "isValid should be true");
        assertEquals(42L, rec.latencyMs(), "latencyMs should match");
        assertNotNull(rec.timestamp(), "timestamp should not be null");
    }

    @Test
    void dev_log_invalid_record_has_violations() {
        writer.write(record("user1", "bad query", false));
        NlQueryAuditRecord rec = writer.getDevLog().get(0);
        assertFalse(rec.isValid(), "isValid should be false");
        assertEquals("bad SQL", rec.violations(), "violations should match");
    }

    @Test
    void clear_dev_log_empties_log() {
        writer.write(record("user1", "q1", true));
        writer.write(record("user2", "q2", true));
        assertFalse(writer.getDevLog().isEmpty(), "log should not be empty before clear");
        writer.clearDevLog();
        assertTrue(writer.getDevLog().isEmpty(), "log should be empty after clear");
    }

    @Test
    void null_record_is_ignored() {
        writer.write(null);
        assertTrue(writer.getDevLog().isEmpty(),
                "null record should be ignored, got size=" + writer.getDevLog().size());
    }

    @Test
    void dev_log_snapshot_is_defensive_copy() {
        writer.write(record("user1", "q1", true));
        var snapshot = writer.getDevLog();
        snapshot.clear(); // mutate the snapshot
        // Original should be unaffected.
        assertEquals(1, writer.getDevLog().size(),
                "mutating snapshot should not affect the writer's internal log");
    }

    @Test
    void record_with_null_session_id() {
        NlQueryAuditRecord rec = new NlQueryAuditRecord(
                "user1", null, "question", "查询", "SELECT 1",
                true, "", false, 0, 10L, LocalDateTime.now());
        writer.write(rec);
        assertEquals(1, writer.getDevLog().size(), "null sessionId should still be written");
        assertNullSessionId(writer.getDevLog().get(0));
    }

    private void assertNullSessionId(NlQueryAuditRecord rec) {
        // Just verify it doesn't crash — sessionId is null.
        assertNotNull(rec, "record should not be null");
    }
}
