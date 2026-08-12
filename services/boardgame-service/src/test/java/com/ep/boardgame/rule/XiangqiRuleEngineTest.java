package com.ep.boardgame.rule;

import com.ep.boardgame.common.PlayerSide;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D-149: XiangqiRuleEngine tests — validates all 7 piece types, checkmate,
 * and FEN conversion.
 * <p>
 * Board orientation: Red at rows 0–4 (top, lowercase), Black at rows 5–9 (bottom, uppercase).
 * Red moves "forward" = increasing row (downward).
 * Black moves "forward" = decreasing row (upward).
 * FEN: lowercase = RED, uppercase = BLACK.
 * INITIAL_FEN: "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 0 1"
 *   Row 0: r n b a k a b n r  (RED pieces)
 *   Row 2: 1c5c1  (RED cannons at cols 1,7)
 *   Row 3: p1p1p1p1p (RED pawns)
 *   Row 6: P1P1P1P1P (BLACK pawns)
 *   Row 7: 1C5C1 (BLACK cannons)
 *   Row 9: R N B A K A B N R (BLACK pieces)
 */
class XiangqiRuleEngineTest {

    private XiangqiRuleEngine engine;

    @BeforeEach
    void setUp() {
        engine = new XiangqiRuleEngine();
    }

    // --- FEN conversion ---

    @Test
    void fenToBoard_initialPosition_hasCorrectPieceCount() {
        char[][] board = engine.fenToBoard(XiangqiRuleEngine.INITIAL_FEN);
        int pieces = 0;
        for (int r = 0; r < 10; r++) {
            for (int c = 0; c < 9; c++) {
                if (board[r][c] != '.') pieces++;
            }
        }
        assertEquals(32, pieces); // 16 per side
    }

    @Test
    void boardToFen_roundTrip_preservesPosition() {
        String fen = XiangqiRuleEngine.INITIAL_FEN;
        char[][] board = engine.fenToBoard(fen);
        String result = engine.boardToFen(board, PlayerSide.RED, 1);
        String originalBoard = fen.split(" ")[0];
        String resultBoard = result.split(" ")[0];
        assertEquals(originalBoard, resultBoard);
    }

    // --- King moves (Red king at row 0, col 4) ---

    @Test
    void kingMove_oneStepForward_isLegal() {
        // Red king at (0,4), move forward (down) to (1,4)
        Move move = new Move(0, 4, 1, 4, PlayerSide.RED, 1);
        assertTrue(engine.isLegalMove(XiangqiRuleEngine.INITIAL_FEN, move));
    }

    @Test
    void kingMove_twoSteps_isIllegal() {
        Move move = new Move(0, 4, 2, 4, PlayerSide.RED, 1);
        assertFalse(engine.isLegalMove(XiangqiRuleEngine.INITIAL_FEN, move));
    }

    @Test
    void kingMove_outOfPalace_isIllegal() {
        // King can't move to col 2 (outside palace cols 3-5)
        Move move = new Move(0, 4, 0, 2, PlayerSide.RED, 1);
        assertFalse(engine.isLegalMove(XiangqiRuleEngine.INITIAL_FEN, move));
    }

    // --- Horse moves (Red horse at row 0, col 1) ---

    @Test
    void horseMove_standardL_isLegal() {
        // Red horse at (0,1), move to (2,2)
        Move move = new Move(0, 1, 2, 2, PlayerSide.RED, 1);
        assertTrue(engine.isLegalMove(XiangqiRuleEngine.INITIAL_FEN, move));
    }

    @Test
    void horseMove_blockedLeg_isIllegal() {
        // Red horse at (0,1), leg for (0,1)→(2,2) is (1,1). Put a piece at (1,1) to block.
        // Standard FEN row 1 = "9" (empty). Replace with "1p7" → pawn at (1,1)
        String fen = "rnbakabnr/1p7/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 0 1";
        Move move = new Move(0, 1, 2, 2, PlayerSide.RED, 1);
        assertFalse(engine.isLegalMove(fen, move));
    }

    // --- Chariot moves (Red chariot at row 0, col 0) ---

    @Test
    void chariotMove_blockedByOwnPiece_isIllegal() {
        // Red chariot at (0,0), pawn at (3,0) blocks path to (4,0)
        Move move = new Move(0, 0, 4, 0, PlayerSide.RED, 1);
        assertFalse(engine.isLegalMove(XiangqiRuleEngine.INITIAL_FEN, move));
    }

    @Test
    void chariotMove_oneStepForward_isLegal() {
        // Red chariot at (0,0), move to (1,0) — clear path
        Move move = new Move(0, 0, 1, 0, PlayerSide.RED, 1);
        assertTrue(engine.isLegalMove(XiangqiRuleEngine.INITIAL_FEN, move));
    }

    // --- Cannon moves (Red cannon at row 2, col 1) ---

    @Test
    void cannonMove_horizontalClear_isLegal() {
        // Red cannon at (2,1) → (2,0) — no screen, no capture = legal
        Move move = new Move(2, 1, 2, 0, PlayerSide.RED, 1);
        assertTrue(engine.isLegalMove(XiangqiRuleEngine.INITIAL_FEN, move));
    }

    @Test
    void cannonCapture_withScreen_isLegal() {
        // RED cannon 'c' at (2,1), screen at (2,3), BLACK target 'R' at (2,5)
        // Row 2: "1c1p1R3" → c at col1, p at col3 (screen), R at col5 (target)
        String fen = "4k4/9/1c1p1R3/9/9/9/9/9/9/4K4 w - - 0 1";
        Move move = new Move(2, 1, 2, 5, PlayerSide.RED, 1);
        assertTrue(engine.isLegalMove(fen, move));
    }

    @Test
    void cannonCapture_noScreen_isIllegal() {
        // No screen between cannon and target
        String fen = "4k4/9/1c5R1/9/9/9/9/9/9/4K4 w - - 0 1";
        Move move = new Move(2, 1, 2, 7, PlayerSide.RED, 1);
        assertFalse(engine.isLegalMove(fen, move));
    }

    // --- Pawn moves (Red pawn at row 3, col 0) ---

    @Test
    void pawnMove_forwardBeforeRiver_isLegal() {
        // Red pawn at (3,0) → (4,0) — forward (down), before river (rows 0-4)
        Move move = new Move(3, 0, 4, 0, PlayerSide.RED, 1);
        assertTrue(engine.isLegalMove(XiangqiRuleEngine.INITIAL_FEN, move));
    }

    @Test
    void pawnMove_sidewaysBeforeRiver_isIllegal() {
        Move move = new Move(3, 0, 3, 1, PlayerSide.RED, 1);
        assertFalse(engine.isLegalMove(XiangqiRuleEngine.INITIAL_FEN, move));
    }

    @Test
    void pawnMove_backward_isIllegal() {
        Move move = new Move(3, 0, 2, 0, PlayerSide.RED, 1);
        assertFalse(engine.isLegalMove(XiangqiRuleEngine.INITIAL_FEN, move));
    }

    @Test
    void pawnMove_sidewaysAfterRiver_isLegal() {
        // Red pawn crossed river to row 5, can move sideways
        String fen = "4k4/9/9/9/9/p8/9/9/9/4K4 w - - 0 1";
        Move move = new Move(5, 0, 5, 1, PlayerSide.RED, 1);
        assertTrue(engine.isLegalMove(fen, move));
    }

    // --- Advisor moves (Red advisor at row 0, col 3) ---

    @Test
    void advisorMove_diagonalInPalace_isLegal() {
        // Red advisor at (0,3) → (1,4)
        Move move = new Move(0, 3, 1, 4, PlayerSide.RED, 1);
        assertTrue(engine.isLegalMove(XiangqiRuleEngine.INITIAL_FEN, move));
    }

    @Test
    void advisorMove_outOfPalace_isIllegal() {
        // (1,2) is outside palace (cols 3-5)
        Move move = new Move(0, 3, 1, 2, PlayerSide.RED, 1);
        assertFalse(engine.isLegalMove(XiangqiRuleEngine.INITIAL_FEN, move));
    }

    // --- Elephant moves (Red elephant at row 0, col 2) ---

    @Test
    void elephantMove_diagonalTwoSteps_isLegal() {
        // Red elephant at (0,2) → (2,4)
        Move move = new Move(0, 2, 2, 4, PlayerSide.RED, 1);
        assertTrue(engine.isLegalMove(XiangqiRuleEngine.INITIAL_FEN, move));
    }

    @Test
    void elephantMove_crossRiver_isIllegal() {
        // Red elephant at (4,2) tries to cross river to (6,4) — illegal
        // Red must stay on rows 0-4. Row 6 is across the river.
        String fen = "4k4/9/9/9/2b6/9/9/9/9/4K4 w - - 0 1";
        Move move = new Move(4, 2, 6, 4, PlayerSide.RED, 1);
        assertFalse(engine.isLegalMove(fen, move));
    }

    // --- Black piece moves (verify black side works too) ---

    @Test
    void blackKingMove_oneStepForward_isLegal() {
        // Black king 'K' at (9,4), move forward (up) to (8,4)
        // Change side to move to 'b' (BLACK)
        String fen = "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR b - - 0 1";
        Move move = new Move(9, 4, 8, 4, PlayerSide.BLACK, 1);
        assertTrue(engine.isLegalMove(fen, move));
    }

    @Test
    void blackPawnMove_forwardBeforeRiver_isLegal() {
        // Black pawn 'P' at (6,0) → (5,0) — forward (up), before river (rows 5-9)
        String fen = "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR b - - 0 1";
        Move move = new Move(6, 0, 5, 0, PlayerSide.BLACK, 1);
        assertTrue(engine.isLegalMove(fen, move));
    }

    // --- Turn validation ---

    @Test
    void move_wrongTurn_isIllegal() {
        // It's RED's turn (w), but we try to move a BLACK piece (uppercase)
        Move move = new Move(9, 0, 8, 0, PlayerSide.BLACK, 1);
        assertFalse(engine.isLegalMove(XiangqiRuleEngine.INITIAL_FEN, move));
    }

    @Test
    void move_emptySource_isIllegal() {
        Move move = new Move(4, 0, 5, 0, PlayerSide.RED, 1);
        assertFalse(engine.isLegalMove(XiangqiRuleEngine.INITIAL_FEN, move));
    }

    @Test
    void move_captureOwnPiece_isIllegal() {
        // Red pawn at (3,0), Red pawn at (3,2) — can't capture own
        Move move = new Move(3, 0, 3, 2, PlayerSide.RED, 1);
        assertFalse(engine.isLegalMove(XiangqiRuleEngine.INITIAL_FEN, move));
    }

    // --- applyMove ---

    @Test
    void applyMove_legalMove_returnsNewFen() {
        Move move = new Move(0, 1, 2, 2, PlayerSide.RED, 1); // Horse
        String newFen = engine.applyMove(XiangqiRuleEngine.INITIAL_FEN, move);
        assertNotNull(newFen);
        // After the move, it should be BLACK's turn ('b')
        assertTrue(newFen.contains(" b "));
    }

    @Test
    void applyMove_illegalMove_throws() {
        Move move = new Move(0, 0, 4, 0, PlayerSide.RED, 1); // Blocked chariot
        assertThrows(IllegalArgumentException.class,
                () -> engine.applyMove(XiangqiRuleEngine.INITIAL_FEN, move));
    }

    // --- checkResult ---

    @Test
    void checkResult_bothKingsPresent_ongoing() {
        assertEquals("ONGOING", engine.checkResult(XiangqiRuleEngine.INITIAL_FEN));
    }

    @Test
    void checkResult_noRedKing_blackWin() {
        // Red king 'k' removed from row 0 (was "rnbakabnr" → "rnba1abnr")
        String fen = "rnba1abnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 0 1";
        assertEquals("BLACK_WIN", engine.checkResult(fen));
    }

    @Test
    void checkResult_noBlackKing_redWin() {
        // Black king 'K' removed from row 9 (was "RNBAKABNR" → "RNBA1ABNR")
        String fen = "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBA1ABNR w - - 0 1";
        assertEquals("RED_WIN", engine.checkResult(fen));
    }

    // --- Move value object ---

    @Test
    void move_toUci_correctFormat() {
        // (2,1) → col 'b', row (9-2)=7 → "b7"
        // (4,2) → col 'c', row (9-4)=5 → "c5"
        Move move = new Move(2, 1, 4, 2, PlayerSide.RED, 3);
        assertEquals("b7c5", move.toUci());
    }

    @Test
    void move_samePosition_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> new Move(5, 3, 5, 3, PlayerSide.RED, 1));
    }

    @Test
    void move_outOfRange_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> new Move(10, 0, 5, 0, PlayerSide.RED, 1));
    }

    // --- PlayerSide ---

    @Test
    void playerSide_opponent_correct() {
        assertEquals(PlayerSide.BLACK, PlayerSide.RED.opponent());
        assertEquals(PlayerSide.RED, PlayerSide.BLACK.opponent());
    }
}
