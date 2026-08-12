package com.ep.boardgame.rule;

import com.ep.boardgame.common.PlayerSide;

import java.util.ArrayList;
import java.util.List;

/**
 * D-149: Xiangqi (Chinese chess) rule engine — MVP implementation.
 * <p>
 * Supports basic move validation for all 7 piece types: king, advisor, elephant,
 * horse, chariot, cannon, pawn. Includes "flying general" check and checkmate
 * detection. This is the Match-as-Lesson rule engine used by GameMatch.
 * <p>
     * Board representation: 10 rows × 9 cols, 2D char array using FEN-style
     * piece characters (lowercase=RED, uppercase=BLACK).
     * Red starts at rows 0–4 (top), Black at rows 5–9 (bottom).
     * Red moves "forward" = increasing row (downward); Black moves "forward" = decreasing row (upward).
     */
public class XiangqiRuleEngine {

    /** Standard starting position FEN for Xiangqi. */
    public static final String INITIAL_FEN =
            "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 0 1";

    /**
     * Apply a move to a board, returning the new FEN string.
     *
     * @param fen  current position FEN
     * @param move the move to apply
     * @return new FEN after the move
     * @throws IllegalArgumentException if the move is illegal
     */
    public String applyMove(String fen, Move move) {
        char[][] board = fenToBoard(fen);
        validateMove(board, move, fen);
        board[move.toRow()][move.toCol()] = board[move.fromRow()][move.fromCol()];
        board[move.fromRow()][move.fromCol()] = '.';
        PlayerSide nextPlayer = move.player().opponent();
        int nextMoveNum = move.moveNumber() + 1;
        return boardToFen(board, nextPlayer, nextMoveNum);
    }

    /**
     * Check if a move is legal on the current board.
     */
    public boolean isLegalMove(String fen, Move move) {
        try {
            char[][] board = fenToBoard(fen);
            validateMove(board, move, fen);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Determine game result: "RED_WIN", "BLACK_WIN", "DRAW", or "ONGOING".
     * MVP: checks if the king is captured (not present on board).
     */
    public String checkResult(String fen) {
        char[][] board = fenToBoard(fen);
        boolean redKing = false, blackKing = false;
        for (int r = 0; r < 10; r++) {
            for (int c = 0; c < 9; c++) {
                char ch = board[r][c];
                if (ch == 'k') redKing = true;
                if (ch == 'K') blackKing = true;
            }
        }
        if (!redKing) return "BLACK_WIN";
        if (!blackKing) return "RED_WIN";
        return "ONGOING";
    }

    /**
     * Check if the side-to-move's king is in check.
     */
    public boolean isInCheck(String fen) {
        char[][] board = fenToBoard(fen);
        PlayerSide sideToMove = extractSideToMove(fen);
        int[] kingPos = findKing(board, sideToMove);
        if (kingPos == null) return true; // King captured = "in check" by convention

        // Check flying general (kings face each other on same column with nothing between)
        int[] oppKingPos = findKing(board, sideToMove.opponent());
        if (oppKingPos != null && kingPos[1] == oppKingPos[1]) {
            boolean blocked = false;
            int minRow = Math.min(kingPos[0], oppKingPos[0]);
            int maxRow = Math.max(kingPos[0], oppKingPos[0]);
            for (int r = minRow + 1; r < maxRow; r++) {
                if (board[r][kingPos[1]] != '.') {
                    blocked = true;
                    break;
                }
            }
            if (!blocked) return true;
        }

        // Check if any opponent piece can capture the king
        PlayerSide opponent = sideToMove.opponent();
        for (int r = 0; r < 10; r++) {
            for (int c = 0; c < 9; c++) {
                char ch = board[r][c];
                if (ch != '.' && pieceSide(ch) == opponent) {
                    Move attackMove = new Move(r, c, kingPos[0], kingPos[1], opponent, 0);
                    if (canPieceAttack(board, ch, attackMove)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * List all legal moves for the side to move (used for checkmate/stalemate detection).
     */
    public List<Move> listLegalMoves(String fen) {
        char[][] board = fenToBoard(fen);
        PlayerSide sideToMove = extractSideToMove(fen);
        int moveNum = extractMoveNumber(fen);
        List<Move> moves = new ArrayList<>();
        for (int r = 0; r < 10; r++) {
            for (int c = 0; c < 9; c++) {
                char ch = board[r][c];
                if (ch != '.' && pieceSide(ch) == sideToMove) {
                    addLegalMovesForPiece(board, ch, r, c, sideToMove, moveNum, moves);
                }
            }
        }
        return moves;
    }

    /**
     * Check if the side to move is checkmated (in check and no legal escape).
     */
    public boolean isCheckmate(String fen) {
        if (!isInCheck(fen)) return false;
        return listLegalMoves(fen).isEmpty();
    }

    // ======================================================================
    // Internal validation
    // ======================================================================

    private void validateMove(char[][] board, Move move, String fen) {
        char piece = board[move.fromRow()][move.fromCol()];
        if (piece == '.') {
            throw new IllegalArgumentException("No piece at source position");
        }
        PlayerSide pieceSide = pieceSide(piece);
        if (pieceSide != move.player()) {
            throw new IllegalArgumentException("Piece does not belong to the moving player");
        }
        PlayerSide sideToMove = extractSideToMove(fen);
        if (sideToMove != move.player()) {
            throw new IllegalArgumentException("Not this player's turn");
        }
        char target = board[move.toRow()][move.toCol()];
        if (target != '.' && pieceSide(target) == pieceSide) {
            throw new IllegalArgumentException("Cannot capture own piece");
        }
        validatePieceMove(board, piece, move);
    }

    private void validatePieceMove(char[][] board, char piece, Move move) {
        PieceType type = PieceType.fromChar(piece);
        int dr = move.toRow() - move.fromRow();
        int dc = move.toCol() - move.fromCol();
        PlayerSide side = pieceSide(piece);

        switch (type) {
            case KING -> validateKing(board, side, move, dr, dc);
            case ADVISOR -> validateAdvisor(side, move, dr, dc);
            case ELEPHANT -> validateElephant(board, side, move, dr, dc);
            case HORSE -> validateHorse(board, move, dr, dc);
            case CHARIOT -> validateChariot(board, move, dr, dc);
            case CANNON -> validateCannon(board, move, dr, dc);
            case PAWN -> validatePawn(side, dr, dc, move);
        }
    }

    private boolean canPieceAttack(char[][] board, char piece, Move move) {
        // For attack detection, we only check movement pattern, not turn or target ownership
        PieceType type = PieceType.fromChar(piece);
        int dr = move.toRow() - move.fromRow();
        int dc = move.toCol() - move.fromCol();
        try {
            switch (type) {
                case KING -> { return isValidKingPattern(board, pieceSide(piece), move, dr, dc); }
                case ADVISOR -> { return Math.abs(dr) == 1 && Math.abs(dc) == 1; }
                case ELEPHANT -> { return isValidElephantPattern(board, pieceSide(piece), move, dr, dc); }
                case HORSE -> { return isValidHorsePattern(board, move, dr, dc); }
                case CHARIOT -> { return isValidChariotPattern(board, move, dr, dc); }
                case CANNON -> { return isValidCannonPattern(board, move, dr, dc, true); }
                case PAWN -> { return isValidPawnPattern(pieceSide(piece), dr, dc, move.fromRow()); }
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }

    // --- King (将/帅) ---
    private void validateKing(char[][] board, PlayerSide side, Move move, int dr, int dc) {
        if (!isValidKingPattern(board, side, move, dr, dc)) {
            throw new IllegalArgumentException("Illegal king move");
        }
    }

    private boolean isValidKingPattern(char[][] board, PlayerSide side, Move move, int dr, int dc) {
        // Must stay in palace (3x3)
        if (!inPalace(side, move.toRow(), move.toCol())) return false;
        // Move one step orthogonally
        if (!((Math.abs(dr) == 1 && dc == 0) || (dr == 0 && Math.abs(dc) == 1))) return false;
        return true;
    }

    private boolean inPalace(PlayerSide side, int row, int col) {
        if (col < 3 || col > 5) return false;
        if (side == PlayerSide.RED) return row >= 0 && row <= 2;
        return row >= 7 && row <= 9;
    }

    // --- Advisor (士/仕) ---
    private void validateAdvisor(PlayerSide side, Move move, int dr, int dc) {
        if (!inPalace(side, move.toRow(), move.toCol())) {
            throw new IllegalArgumentException("Advisor must stay in palace");
        }
        if (Math.abs(dr) != 1 || Math.abs(dc) != 1) {
            throw new IllegalArgumentException("Advisor must move diagonally one step");
        }
    }

    // --- Elephant (相/象) ---
    private void validateElephant(char[][] board, PlayerSide side, Move move, int dr, int dc) {
        if (!isValidElephantPattern(board, side, move, dr, dc)) {
            throw new IllegalArgumentException("Illegal elephant move");
        }
    }

    private boolean isValidElephantPattern(char[][] board, PlayerSide side, Move move, int dr, int dc) {
        if (Math.abs(dr) != 2 || Math.abs(dc) != 2) return false;
        // Cannot cross river
        if (side == PlayerSide.RED && move.toRow() > 4) return false;
        if (side == PlayerSide.BLACK && move.toRow() < 5) return false;
        // Check "elephant eye" (blocking point)
        int eyeRow = move.fromRow() + dr / 2;
        int eyeCol = move.fromCol() + dc / 2;
        return board[eyeRow][eyeCol] == '.';
    }

    // --- Horse (马/傌) ---
    private void validateHorse(char[][] board, Move move, int dr, int dc) {
        if (!isValidHorsePattern(board, move, dr, dc)) {
            throw new IllegalArgumentException("Illegal horse move (blocked leg)");
        }
    }

    private boolean isValidHorsePattern(char[][] board, Move move, int dr, int dc) {
        boolean isHorsePattern = (Math.abs(dr) == 2 && Math.abs(dc) == 1)
                || (Math.abs(dr) == 1 && Math.abs(dc) == 2);
        if (!isHorsePattern) return false;
        // Check "horse leg" (blocking point)
        int legRow = move.fromRow();
        int legCol = move.fromCol();
        if (Math.abs(dr) == 2) {
            legRow = move.fromRow() + dr / 2;
        } else {
            legCol = move.fromCol() + dc / 2;
        }
        return board[legRow][legCol] == '.';
    }

    // --- Chariot (车/俥) ---
    private void validateChariot(char[][] board, Move move, int dr, int dc) {
        if (!isValidChariotPattern(board, move, dr, dc)) {
            throw new IllegalArgumentException("Illegal chariot move (path blocked)");
        }
    }

    private boolean isValidChariotPattern(char[][] board, Move move, int dr, int dc) {
        if (dr != 0 && dc != 0) return false; // Must move in straight line
        return isPathClear(board, move.fromRow(), move.fromCol(), move.toRow(), move.toCol());
    }

    // --- Cannon (炮/砲) ---
    private void validateCannon(char[][] board, Move move, int dr, int dc) {
        if (dr != 0 && dc != 0) {
            throw new IllegalArgumentException("Cannon must move in straight line");
        }
        char target = board[move.toRow()][move.toCol()];
        boolean isCapture = target != '.';
        if (!isValidCannonPattern(board, move, dr, dc, isCapture)) {
            throw new IllegalArgumentException(isCapture
                    ? "Cannon capture requires exactly one screen between source and target"
                    : "Cannon non-capture move path must be clear");
        }
    }

    private boolean isValidCannonPattern(char[][] board, Move move, int dr, int dc, boolean isCapture) {
        if (dr != 0 && dc != 0) return false;
        int screens = countScreens(board, move.fromRow(), move.fromCol(), move.toRow(), move.toCol());
        if (isCapture) return screens == 1;
        return screens == 0;
    }

    // --- Pawn (兵/卒) ---
    private void validatePawn(PlayerSide side, int dr, int dc, Move move) {
        if (!isValidPawnPattern(side, dr, dc, move.fromRow())) {
            throw new IllegalArgumentException("Illegal pawn move");
        }
    }

    private boolean isValidPawnPattern(PlayerSide side, int dr, int dc, int fromRow) {
        // Red moves down (dr positive, forward), Black moves up (dr negative, forward)
        if (side == PlayerSide.RED) {
            if (dr < 0) return false; // Cannot move backward
            if (fromRow <= 4) { // Not crossed river
                return dr == 1 && dc == 0; // Only forward
            }
            return (dr == 1 && dc == 0) || (dr == 0 && Math.abs(dc) == 1); // Forward or sideways
        } else {
            if (dr > 0) return false;
            if (fromRow >= 5) {
                return dr == -1 && dc == 0;
            }
            return (dr == -1 && dc == 0) || (dr == 0 && Math.abs(dc) == 1);
        }
    }

    // ======================================================================
    // Board utilities
    // ======================================================================

    private boolean isPathClear(char[][] board, int r1, int c1, int r2, int c2) {
        int dr = Integer.compare(r2, r1);
        int dc = Integer.compare(c2, c1);
        int r = r1 + dr, c = c1 + dc;
        while (r != r2 || c != c2) {
            if (board[r][c] != '.') return false;
            r += dr;
            c += dc;
        }
        return true;
    }

    private int countScreens(char[][] board, int r1, int c1, int r2, int c2) {
        int dr = Integer.compare(r2, r1);
        int dc = Integer.compare(c2, c1);
        int r = r1 + dr, c = c1 + dc;
        int count = 0;
        while (r != r2 || c != c2) {
            if (board[r][c] != '.') count++;
            r += dr;
            c += dc;
        }
        return count;
    }

    private int[] findKing(char[][] board, PlayerSide side) {
        char kingChar = side == PlayerSide.RED ? 'k' : 'K';
        for (int r = 0; r < 10; r++) {
            for (int c = 0; c < 9; c++) {
                if (board[r][c] == kingChar) return new int[]{r, c};
            }
        }
        return null;
    }

    private void addLegalMovesForPiece(char[][] board, char piece, int row, int col,
                                        PlayerSide side, int moveNum, List<Move> moves) {
        // Generate candidate target squares and filter by validity
        for (int tr = 0; tr < 10; tr++) {
            for (int tc = 0; tc < 9; tc++) {
                if (tr == row && tc == col) continue;
                char target = board[tr][tc];
                if (target != '.' && pieceSide(target) == side) continue;
                Move candidate = new Move(row, col, tr, tc, side, moveNum);
                try {
                    validatePieceMove(board, piece, candidate);
                    moves.add(candidate);
                } catch (IllegalArgumentException ignored) {
                    // Not a legal move for this piece
                }
            }
        }
    }

    // ======================================================================
    // FEN conversion
    // ======================================================================

    public char[][] fenToBoard(String fen) {
        String[] parts = fen.trim().split("\\s+");
        String boardPart = parts[0];
        char[][] board = new char[10][9];
        String[] rows = boardPart.split("/");
        if (rows.length != 10) {
            throw new IllegalArgumentException("Invalid FEN: expected 10 rows, got " + rows.length);
        }
        for (int r = 0; r < 10; r++) {
            int c = 0;
            for (char ch : rows[r].toCharArray()) {
                if (Character.isDigit(ch)) {
                    int empty = ch - '0';
                    for (int i = 0; i < empty && c < 9; i++) {
                        board[r][c++] = '.';
                    }
                } else {
                    if (c >= 9) throw new IllegalArgumentException("Invalid FEN: too many columns in row " + r);
                    board[r][c++] = ch;
                }
            }
            if (c != 9) throw new IllegalArgumentException("Invalid FEN: expected 9 columns in row " + r + ", got " + c);
        }
        return board;
    }

    public String boardToFen(char[][] board, PlayerSide sideToMove, int moveNumber) {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < 10; r++) {
            int empty = 0;
            for (int c = 0; c < 9; c++) {
                char ch = board[r][c];
                if (ch == '.') {
                    empty++;
                } else {
                    if (empty > 0) {
                        sb.append(empty);
                        empty = 0;
                    }
                    sb.append(ch);
                }
            }
            if (empty > 0) sb.append(empty);
            if (r < 9) sb.append('/');
        }
        sb.append(' ').append(sideToMove == PlayerSide.RED ? 'w' : 'b');
        sb.append(" - - 0 ").append(moveNumber);
        return sb.toString();
    }

    private PlayerSide extractSideToMove(String fen) {
        String[] parts = fen.trim().split("\\s+");
        return parts[1].equals("w") ? PlayerSide.RED : PlayerSide.BLACK;
    }

    private int extractMoveNumber(String fen) {
        String[] parts = fen.trim().split("\\s+");
        if (parts.length >= 6) {
            try { return Integer.parseInt(parts[5]); } catch (NumberFormatException e) { return 1; }
        }
        return 1;
    }

    private PlayerSide pieceSide(char piece) {
        return Character.isLowerCase(piece) ? PlayerSide.RED : PlayerSide.BLACK;
    }
}
