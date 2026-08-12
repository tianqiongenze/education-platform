package com.ep.boardgame.rule;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-191: {@link GoRuleEngine} unit tests.
 */
class GoRuleEngineTest {

    private GoRuleEngine engine;
    private int[][] board;

    @BeforeEach
    void setUp() {
        engine = new GoRuleEngine();
        board = engine.newBoard();
    }

    // --- Basic placement & applyMove -------------------------------------

    @Test
    void newBoard_isEmpty() {
        for (int r = 0; r < GoRuleEngine.SIZE; r++) {
            for (int c = 0; c < GoRuleEngine.SIZE; c++) {
                assertEquals(GoRuleEngine.EMPTY, board[r][c]);
            }
        }
    }

    @Test
    void applyMove_placesStone() {
        int[][] next = engine.applyMove(board, 3, 3, GoRuleEngine.BLACK);
        assertEquals(GoRuleEngine.BLACK, next[3][3]);
        // Original board not mutated.
        assertEquals(GoRuleEngine.EMPTY, board[3][3]);
    }

    @Test
    void applyMove_occupied_throws() {
        board[3][3] = GoRuleEngine.BLACK;
        assertThrows(IllegalArgumentException.class,
                () -> engine.applyMove(board, 3, 3, GoRuleEngine.WHITE));
    }

    @Test
    void applyMove_outOfBounds_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> engine.applyMove(board, -1, 0, GoRuleEngine.BLACK));
        assertThrows(IllegalArgumentException.class,
                () -> engine.applyMove(board, 0, GoRuleEngine.SIZE, GoRuleEngine.BLACK));
    }

    @Test
    void applyMove_invalidStone_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> engine.applyMove(board, 0, 0, 5));
    }

    // --- Group & liberties ------------------------------------------------

    @Test
    void getGroup_singleStone() {
        board[3][3] = GoRuleEngine.BLACK;
        Set<Long> group = engine.getGroup(board, 3, 3);
        assertEquals(1, group.size());
    }

    @Test
    void getGroup_connectedStones() {
        board[3][3] = GoRuleEngine.BLACK;
        board[3][4] = GoRuleEngine.BLACK;
        board[3][5] = GoRuleEngine.BLACK;
        Set<Long> group = engine.getGroup(board, 3, 3);
        assertEquals(3, group.size());
    }

    @Test
    void getGroup_emptyIntersection_returnsEmpty() {
        Set<Long> group = engine.getGroup(board, 5, 5);
        assertTrue(group.isEmpty());
    }

    @Test
    void getLiberties_singleStone_center() {
        board[9][9] = GoRuleEngine.BLACK;
        Set<Long> group = engine.getGroup(board, 9, 9);
        assertEquals(4, engine.getLiberties(board, group));
    }

    @Test
    void getLiberties_cornerStone() {
        board[0][0] = GoRuleEngine.WHITE;
        Set<Long> group = engine.getGroup(board, 0, 0);
        assertEquals(2, engine.getLiberties(board, group));
    }

    @Test
    void getLiberties_edgeStone() {
        board[0][9] = GoRuleEngine.BLACK;
        Set<Long> group = engine.getGroup(board, 0, 9);
        assertEquals(3, engine.getLiberties(board, group));
    }

    // --- Captures ---------------------------------------------------------

    @Test
    void applyMove_capturesOpponentGroupWithNoLiberties() {
        // White stone at (0,0), Black surrounds it at (0,1) and (1,0).
        // Corner stone has 2 liberties; placing black at both removes them.
        board[0][0] = GoRuleEngine.WHITE;
        board[0][1] = GoRuleEngine.BLACK;
        // Place the final surrounding stone at (1,0).
        int[][] next = engine.applyMove(board, 1, 0, GoRuleEngine.BLACK);
        assertEquals(GoRuleEngine.EMPTY, next[0][0], "White stone should be captured");
    }

    @Test
    void applyMove_capturesMultipleStones() {
        // White group of 2 at (0,0),(0,1); Black surrounds all but one liberty,
        // then plays the final capturing stone at (1,0).
        // White group liberties: (0,2)=B, (1,1)=B, so only (1,0) remains.
        // Playing B at (1,0) removes the last liberty → captures both White stones.
        board[0][0] = GoRuleEngine.WHITE;
        board[0][1] = GoRuleEngine.WHITE;
        board[0][2] = GoRuleEngine.BLACK;
        board[1][1] = GoRuleEngine.BLACK;
        int[][] next = engine.applyMove(board, 1, 0, GoRuleEngine.BLACK);
        assertEquals(GoRuleEngine.EMPTY, next[0][0]);
        assertEquals(GoRuleEngine.EMPTY, next[0][1]);
        assertEquals(GoRuleEngine.BLACK, next[1][0]);
    }

    @Test
    void captureGroup_removesStones() {
        board[5][5] = GoRuleEngine.WHITE;
        board[5][6] = GoRuleEngine.WHITE;
        Set<Long> group = engine.getGroup(board, 5, 5);
        int captured = engine.captureGroup(board, group);
        assertEquals(2, captured);
        assertEquals(GoRuleEngine.EMPTY, board[5][5]);
        assertEquals(GoRuleEngine.EMPTY, board[5][6]);
    }

    // --- Suicide rule -----------------------------------------------------

    @Test
    void applyMove_suicide_throws() {
        // White at (0,1) and (1,0); Black tries to play (0,0) with no capture.
        board[0][1] = GoRuleEngine.WHITE;
        board[1][0] = GoRuleEngine.WHITE;
        assertThrows(IllegalArgumentException.class,
                () -> engine.applyMove(board, 0, 0, GoRuleEngine.BLACK));
    }

    @Test
    void applyMove_suicideThatCaptures_isLegal() {
        // Mutual capture scenario: if placing a stone "suicides" but also
        // captures an opponent group, it is legal.
        // Setup: Black (0,1),(1,0); White (0,0). White has 1 liberty at...
        // Actually (0,0) white has liberties (0,1)=B,(1,0)=B → 0 liberties already.
        // Let's do a proper capturing-suicide:
        // White stones at (0,0),(0,2),(1,1) surrounding black at (0,1)?
        // Black at (0,1): liberties are (0,0)=W,(0,2)=W,(1,1)=W → 0 libs.
        // White at (0,0): libs (1,0) only. Play B at (1,0) — this captures white (0,0)
        // and gives black (0,1) a liberty.
        board[0][1] = GoRuleEngine.BLACK;
        board[0][0] = GoRuleEngine.WHITE;
        board[0][2] = GoRuleEngine.WHITE;
        board[1][1] = GoRuleEngine.WHITE;
        // White (0,0) liberty: (1,0). Black plays (1,0): captures W(0,0), B(0,1) gets liberty.
        int[][] next = engine.applyMove(board, 1, 0, GoRuleEngine.BLACK);
        assertEquals(GoRuleEngine.EMPTY, next[0][0], "White (0,0) captured");
        assertEquals(GoRuleEngine.BLACK, next[1][0]);
    }

    @Test
    void isLegalMove_suicide_returnsFalse() {
        board[0][1] = GoRuleEngine.WHITE;
        board[1][0] = GoRuleEngine.WHITE;
        assertFalse(engine.isLegalMove(board, 0, 0, GoRuleEngine.BLACK));
    }

    @Test
    void isLegalMove_occupied_returnsFalse() {
        board[3][3] = GoRuleEngine.BLACK;
        assertFalse(engine.isLegalMove(board, 3, 3, GoRuleEngine.WHITE));
    }

    @Test
    void isLegalMove_validPlacement_returnsTrue() {
        assertTrue(engine.isLegalMove(board, 3, 3, GoRuleEngine.BLACK));
    }

    // --- Ko rule ----------------------------------------------------------

    @Test
    void isLegalMove_ko_reproducesPrevious_returnsFalse() {
        // Simple ko: setup where playing a move would recreate the previous position.
        // Standard ko shape:
        //   . B W .
        //   B W . W
        //   . B W .
        // Black captures the center white stone, then White cannot immediately recapture.
        // Build the pre-capture position:
        int[][] b = engine.newBoard();
        b[0][1] = GoRuleEngine.BLACK; b[0][2] = GoRuleEngine.WHITE;
        b[1][0] = GoRuleEngine.BLACK; b[1][1] = GoRuleEngine.WHITE; b[1][2] = GoRuleEngine.EMPTY; b[1][3] = GoRuleEngine.WHITE;
        b[2][1] = GoRuleEngine.BLACK; b[2][2] = GoRuleEngine.WHITE;
        // White stone at (1,1) has 1 liberty at (1,2). Black plays (1,2) to capture.
        int[][] afterCapture = engine.applyMove(b, 1, 2, GoRuleEngine.BLACK);
        assertEquals(GoRuleEngine.EMPTY, afterCapture[1][1], "White center captured");
        // Now the previous board is `b`. White playing (1,1) would recapture
        // black at (1,2), reproducing a mirror — but more precisely, it reproduces
        // a board where (1,1)=W and (1,2)=EMPTY, which is the original `b` position.
        // So isLegalMove(afterCapture, b, 1,1, WHITE) should be false (ko).
        assertFalse(engine.isLegalMove(afterCapture, b, 1, 1, GoRuleEngine.WHITE));
    }

    @Test
    void isLegalMove_noKo_returnsTrue() {
        assertTrue(engine.isLegalMove(board, null, 3, 3, GoRuleEngine.BLACK));
    }

    // --- Scoring (Chinese) ------------------------------------------------

    @Test
    void determineResult_emptyBoard_whiteWinsByKomi() {
        // Empty board: Black 0, White 0 + 7.5 komi → White wins.
        assertEquals("WHITE_WIN", engine.determineResult(engine.newBoard()));
    }

    @Test
    void determineResult_blackTerritoryOnly_blackWins() {
        // Black surrounds the entire top-left corner with a wall.
        int[][] b = engine.newBoard();
        // Wall at row 3 (cols 0-3) and col 3 (rows 0-3).
        for (int c = 0; c <= 3; c++) b[3][c] = GoRuleEngine.BLACK;
        for (int r = 0; r <= 3; r++) b[r][3] = GoRuleEngine.BLACK;
        // Territory: 9 empty intersections (rows 0-2, cols 0-2) + 7 black stones = 16.
        // White: 0 + 7.5 = 7.5. Black wins.
        assertEquals("BLACK_WIN", engine.determineResult(b));
    }

    @Test
    void determineResult_contestedTerritory_neutral() {
        // An empty intersection bordered by both colors is neutral (dame).
        // Build: Black at (0,1), White at (1,0). (0,0) is bordered by both → neutral.
        int[][] b = engine.newBoard();
        b[0][1] = GoRuleEngine.BLACK;
        b[1][0] = GoRuleEngine.WHITE;
        // Black: 1 stone, no exclusive territory. White: 1 stone + 7.5 komi = 8.5.
        assertEquals("WHITE_WIN", engine.determineResult(b));
    }

    // --- SGF serialization ------------------------------------------------

    @Test
    void toSgf_singleMove() {
        List<int[]> moves = List.of(new int[]{3, 3, GoRuleEngine.BLACK});
        assertEquals(";B[dd]", engine.toSgf(moves));
    }

    @Test
    void toSgf_multipleMoves() {
        List<int[]> moves = List.of(
                new int[]{3, 3, GoRuleEngine.BLACK},
                new int[]{15, 15, GoRuleEngine.WHITE},
                new int[]{3, 4, GoRuleEngine.BLACK}
        );
        assertEquals(";B[dd];W[pp];B[ed]", engine.toSgf(moves));
    }

    @Test
    void parseSgf_singleMove() {
        List<int[]> moves = engine.parseSgf(";B[dd]");
        assertEquals(1, moves.size());
        assertEquals(3, moves.get(0)[0]);
        assertEquals(3, moves.get(0)[1]);
        assertEquals(GoRuleEngine.BLACK, moves.get(0)[2]);
    }

    @Test
    void parseSgf_multipleMoves() {
        List<int[]> moves = engine.parseSgf(";B[dd];W[pp];B[ed]");
        assertEquals(3, moves.size());
        assertEquals(GoRuleEngine.WHITE, moves.get(1)[2]);
        assertEquals(4, moves.get(2)[1]);
    }

    @Test
    void parseSgf_empty_returnsEmptyList() {
        assertTrue(engine.parseSgf("").isEmpty());
        assertTrue(engine.parseSgf(null).isEmpty());
    }

    @Test
    void parseSgf_invalid_throws() {
        assertThrows(IllegalArgumentException.class, () -> engine.parseSgf(";B["));
        assertThrows(IllegalArgumentException.class, () -> engine.parseSgf(";B[zz]"));
    }

    @Test
    void toSgf_parseSgf_roundTrip() {
        List<int[]> original = List.of(
                new int[]{0, 0, GoRuleEngine.BLACK},
                new int[]{18, 18, GoRuleEngine.WHITE},
                new int[]{9, 9, GoRuleEngine.BLACK}
        );
        String sgf = engine.toSgf(original);
        List<int[]> parsed = engine.parseSgf(sgf);
        assertEquals(original.size(), parsed.size());
        for (int i = 0; i < original.size(); i++) {
            assertEquals(original.get(i)[0], parsed.get(i)[0]);
            assertEquals(original.get(i)[1], parsed.get(i)[1]);
            assertEquals(original.get(i)[2], parsed.get(i)[2]);
        }
    }

    // --- stoneOf helper ---------------------------------------------------

    @Test
    void stoneOf_redMapsToBlack() {
        assertEquals(GoRuleEngine.BLACK, GoRuleEngine.stoneOf(com.ep.boardgame.common.PlayerSide.RED));
        assertEquals(GoRuleEngine.WHITE, GoRuleEngine.stoneOf(com.ep.boardgame.common.PlayerSide.BLACK));
    }
}
