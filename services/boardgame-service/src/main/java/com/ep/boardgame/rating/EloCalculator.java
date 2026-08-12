package com.ep.boardgame.rating;

/**
 * D-191 (ADR-BG09): ELO rating calculator.
 * <p>
 * Standard ELO formula:
 * <pre>
 *   expectedScore = 1 / (1 + 10^((opponentRating - playerRating) / 400))
 *   newRating     = playerRating + K * (actualScore - expectedScore)
 * </pre>
 * K-factor tiers (per FIDE-style rules):
 * <ul>
 *   <li>K = 32 for ratings &lt; 2100</li>
 *   <li>K = 24 for 2100 ≤ rating ≤ 2400</li>
 *   <li>K = 16 for ratings &gt; 2400</li>
 * </ul>
 */
public final class EloCalculator {

    private EloCalculator() {
    }

    /**
     * Compute the K-factor for a player based on their current rating.
     */
    public static int kFactor(int rating) {
        if (rating < 2100) return 32;
        if (rating <= 2400) return 24;
        return 16;
    }

    /**
     * Expected score for a player against an opponent.
     *
     * @param playerRating   the player's current rating
     * @param opponentRating the opponent's current rating
     * @return expected score in [0, 1]
     */
    public static double expectedScore(int playerRating, int opponentRating) {
        return 1.0 / (1.0 + Math.pow(10.0, (opponentRating - playerRating) / 400.0));
    }

    /**
     * Compute the player's new rating after a game.
     *
     * @param playerRating   the player's current rating
     * @param opponentRating the opponent's current rating
     * @param actualScore    1.0 = win, 0.0 = loss, 0.5 = draw
     * @return the new rating (rounded to int, clamped to >= 0)
     */
    public static int newRating(int playerRating, int opponentRating, double actualScore) {
        if (actualScore < 0.0 || actualScore > 1.0) {
            throw new IllegalArgumentException("actualScore must be in [0, 1]");
        }
        int k = kFactor(playerRating);
        double expected = expectedScore(playerRating, opponentRating);
        int updated = (int) Math.round(playerRating + k * (actualScore - expected));
        return Math.max(0, updated);
    }
}
