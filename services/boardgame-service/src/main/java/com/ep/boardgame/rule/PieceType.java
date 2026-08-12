package com.ep.boardgame.rule;

/**
 * Piece type on a Xiangqi (Chinese chess) board.
 * Lowercase = RED side, uppercase = BLACK side (FEN convention).
 */
public enum PieceType {
    KING('k', 'K'),       // 将/帅
    ADVISOR('a', 'A'),    // 士/仕
    ELEPHANT('b', 'B'),   // 相/象
    HORSE('n', 'N'),      // 马/傌 (FEN uses 'n' for knight/horse)
    CHARIOT('r', 'R'),    // 车/俥
    CANNON('c', 'C'),     // 炮/砲
    PAWN('p', 'P');       // 兵/卒

    private final char redChar;
    private final char blackChar;

    PieceType(char redChar, char blackChar) {
        this.redChar = redChar;
        this.blackChar = blackChar;
    }

    public char getRedChar() { return redChar; }
    public char getBlackChar() { return blackChar; }

    /** Parse a FEN character into a piece type. */
    public static PieceType fromChar(char c) {
        char lower = Character.toLowerCase(c);
        return switch (lower) {
            case 'k' -> KING;
            case 'a' -> ADVISOR;
            case 'b' -> ELEPHANT;
            case 'n' -> HORSE;
            case 'r' -> CHARIOT;
            case 'c' -> CANNON;
            case 'p' -> PAWN;
            default -> throw new IllegalArgumentException("Unknown piece char: " + c);
        };
    }
}
