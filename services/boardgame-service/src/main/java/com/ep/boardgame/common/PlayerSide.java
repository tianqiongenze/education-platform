package com.ep.boardgame.common;

/**
 * Player side in a board game.
 */
public enum PlayerSide {
    RED,
    BLACK;

    public PlayerSide opponent() {
        return this == RED ? BLACK : RED;
    }
}
