package com.ep.boardgame.rule;

import com.ep.boardgame.common.PlayerSide;

/**
 * D-149: Value object representing a single chess move.
 * <p>
 * Board coordinates: row 0–9 (top to bottom), col 0–8 (left to right).
 * Red starts at rows 0–4 (top), Black at rows 5–9 (bottom).
 */
public record Move(int fromRow, int fromCol, int toRow, int toCol, PlayerSide player, int moveNumber) {

    public Move {
        if (fromRow < 0 || fromRow > 9 || toRow < 0 || toRow > 9) {
            throw new IllegalArgumentException("Row out of range [0,9]");
        }
        if (fromCol < 0 || fromCol > 8 || toCol < 0 || toCol > 8) {
            throw new IllegalArgumentException("Col out of range [0,8]");
        }
        if (player == null) {
            throw new NullPointerException("player");
        }
        if (fromRow == toRow && fromCol == toCol) {
            throw new IllegalArgumentException("From and to positions are the same");
        }
        if (moveNumber < 0) {
            throw new IllegalArgumentException("moveNumber must be >= 0");
        }
    }

    /** UCI-style notation, e.g. "h2e2" for column-row → column-row. */
    public String toUci() {
        return "" + (char) ('a' + fromCol) + (9 - fromRow)
                + (char) ('a' + toCol) + (9 - toRow);
    }
}
