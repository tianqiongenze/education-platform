package com.ep.data.profile;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link DataAssetProfileController}.
 */
class DataAssetProfileTest {

    private final DataAssetProfileController controller = new DataAssetProfileController();

    @Test
    void test_quality_score_perfect_data() {
        // rowCount >= 10000 → full 30 pts; nullRatio=0 → 30; uniqueness=1 → 20; +20 stability = 100
        int score = controller.computeQualityScore(50_000L, 0.0, 1.0);
        assertEquals(100, score, "perfect data should score 100, got " + score);
    }

    @Test
    void test_quality_score_high_null_ratio_low_score() {
        // nullRatio=0.8 → 6 pts; rowCount=0 → 0; uniqueness=0 → 0; +20 = 26
        int score = controller.computeQualityScore(0L, 0.8, 0.0);
        assertTrue(score < 30, "high null ratio should yield low score, got " + score);
    }

    @Test
    void test_profile_refresh_creates_and_updates_entry() {
        var r1 = controller.refresh("ods.users");
        assertTrue(r1.getStatusCode().is2xxSuccessful(), "refresh should succeed");
        DataAssetProfile p1 = r1.getBody();
        assertNotNull(p1);
        assertEquals("users", p1.tableName());
        assertEquals("ods", p1.database());

        // Second refresh should update (different timestamp possible)
        var r2 = controller.refresh("ods.users");
        assertTrue(r2.getStatusCode().is2xxSuccessful());
    }

    @Test
    void test_get_unknown_table_returns_404() {
        var resp = controller.getProfile("ods.nonexistent");
        assertTrue(resp.getStatusCode().is4xxClientError(),
                "unknown table should 404, got " + resp.getStatusCode());
        assertFalse(resp.hasBody());
    }

    @Test
    void test_list_all_profiled_tables() {
        controller.refresh("ods.t1");
        controller.refresh("ods.t2");
        var resp = controller.listTables();
        assertTrue(resp.getStatusCode().is2xxSuccessful());
        assertNotNull(resp.getBody());
        assertTrue(resp.getBody().size() >= 2, "should list at least 2 profiled tables");
        assertTrue(resp.getBody().contains("ods.t1"));
        assertTrue(resp.getBody().contains("ods.t2"));
    }
}
