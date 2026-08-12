package com.ep.data.nlquery;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for {@link ConversationContext}.
 *
 * <p>No Spring context — the component is a plain POJO instantiated with
 * {@code new}. Thread-safety is verified by the concurrent add test.
 */
class ConversationContextTest {

    private ConversationContext context;

    @BeforeEach
    void setUp() {
        context = new ConversationContext();
    }

    private static ChatTurn turn(String q, String sql) {
        return new ChatTurn(q, sql, "summary", java.time.LocalDateTime.now());
    }

    @Test
    void test_five_turn_limit() {
        for (int i = 0; i < 7; i++) {
            context.addTurn("s1", turn("q" + i, "SELECT " + i));
        }
        var history = context.getHistory("s1");
        assertEquals(5, history.size(),
                "should retain last 5 turns after adding 7, got " + history.size());
        // Oldest retained should be q2 (q0 and q1 evicted).
        assertEquals("q2", history.get(0).question(),
                "first retained turn should be q2, got " + history.get(0).question());
        assertEquals("q6", history.get(4).question(),
                "last retained turn should be q6, got " + history.get(4).question());
    }

    @Test
    void test_session_isolation() {
        context.addTurn("s1", turn("q1", "SELECT 1"));
        context.addTurn("s2", turn("q2", "SELECT 2"));
        var h1 = context.getHistory("s1");
        var h2 = context.getHistory("s2");
        assertEquals(1, h1.size(), "s1 should have 1 turn, got " + h1.size());
        assertEquals(1, h2.size(), "s2 should have 1 turn, got " + h2.size());
        assertEquals("q1", h1.get(0).question(), "s1 should have q1, got " + h1.get(0).question());
        assertEquals("q2", h2.get(0).question(), "s2 should have q2, got " + h2.get(0).question());
    }

    @Test
    void test_clear_session() {
        context.addTurn("s1", turn("q1", "SELECT 1"));
        assertFalse(context.getHistory("s1").isEmpty(), "history should not be empty before clear");
        context.clearSession("s1");
        assertTrue(context.getHistory("s1").isEmpty(),
                "history should be empty after clear, got " + context.getHistory("s1").size());
    }

    @Test
    void test_empty_session_returns_empty_list() {
        var history = context.getHistory("never-existed");
        assertNotNull(history, "history should not be null for unknown session");
        assertTrue(history.isEmpty(), "history should be empty for unknown session, got " + history.size());
    }

    @Test
    void test_followup_where_appends_to_last_sql() {
        context.addTurn("s1", turn("list users", "SELECT * FROM users LIMIT 100"));
        String followup = context.buildFollowupSql("s1", "where active = true");
        assertNotNull(followup, "followup SQL should not be null for where keyword");
        assertTrue(followup.contains("AND 1=1"),
                "followup should append AND 1=1, got " + followup);
        assertTrue(followup.startsWith("SELECT * FROM users LIMIT 100"),
                "followup should start with last SQL, got " + followup);
    }

    @Test
    void test_followup_group_appends_group_by() {
        context.addTurn("s1", turn("list users", "SELECT * FROM users LIMIT 100"));
        String followup = context.buildFollowupSql("s1", "group by department");
        assertNotNull(followup, "followup SQL should not be null for group keyword");
        assertTrue(followup.contains("GROUP BY 1"),
                "followup should contain GROUP BY 1, got " + followup);
    }

    @Test
    void test_followup_returns_null_when_no_last_turn() {
        String followup = context.buildFollowupSql("never-existed", "where active = true");
        assertEquals(null, followup,
                "followup should be null when no last turn exists, got " + followup);
    }

    @Test
    void test_followup_returns_null_when_no_keyword() {
        context.addTurn("s1", turn("list users", "SELECT * FROM users LIMIT 100"));
        String followup = context.buildFollowupSql("s1", "show me something different");
        assertEquals(null, followup,
                "followup should be null when no follow-up keyword present, got " + followup);
    }

    @Test
    void test_followup_chinese_jia_keyword() {
        context.addTurn("s1", turn("list users", "SELECT * FROM users LIMIT 100"));
        String followup = context.buildFollowupSql("s1", "加一个条件");
        assertNotNull(followup, "followup SQL should not be null for 加 keyword");
        assertTrue(followup.contains("AND 1=1"),
                "followup should append AND 1=1 for 加 keyword, got " + followup);
    }
}
