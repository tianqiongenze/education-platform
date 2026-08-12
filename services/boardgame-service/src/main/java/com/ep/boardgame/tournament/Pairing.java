package com.ep.boardgame.tournament;

/**
 * D-192: A single pairing produced by {@link SwissPairing}.
 * <p>
 * {@code player2Id == null} indicates a BYE (player1 gets a free win).
 */
public record Pairing(
        Long player1Id,
        Long player2Id,
        String player1Color, // "RED" or "BLACK" or null for a bye
        String player2Color  // "RED" or "BLACK" or null for a bye
) {
    /**
     * @return true when this pairing is a BYE (player2 absent).
     */
    public boolean isBye() {
        return player2Id == null;
    }
}
