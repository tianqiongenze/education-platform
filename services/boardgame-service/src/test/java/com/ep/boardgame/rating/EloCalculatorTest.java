package com.ep.boardgame.rating;

import com.ep.boardgame.tournament.Tournament.GameType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-191 (ADR-BG09): {@link EloCalculator} unit tests.
 */
class EloCalculatorTest {

    @Test
    void kFactor_tiers() {
        assertEquals(32, EloCalculator.kFactor(1200));
        assertEquals(32, EloCalculator.kFactor(2099));
        assertEquals(24, EloCalculator.kFactor(2100));
        assertEquals(24, EloCalculator.kFactor(2400));
        assertEquals(16, EloCalculator.kFactor(2401));
        assertEquals(16, EloCalculator.kFactor(2800));
    }

    @Test
    void expectedScore_equalRatings_isHalf() {
        assertEquals(0.5, EloCalculator.expectedScore(1500, 1500), 1e-9);
    }

    @Test
    void expectedScore_higherRatedPlayer_favoured() {
        double expected = EloCalculator.expectedScore(1900, 1500);
        assertTrue(expected > 0.9, "expected=" + expected);
        double expectedLow = EloCalculator.expectedScore(1500, 1900);
        assertTrue(expectedLow < 0.1, "expected=" + expectedLow);
    }

    @Test
    void newRating_win_increases() {
        int before = 1500;
        int after = EloCalculator.newRating(before, 1500, 1.0);
        assertTrue(after > before, "after=" + after);
    }

    @Test
    void newRating_loss_decreases() {
        int before = 1500;
        int after = EloCalculator.newRating(before, 1500, 0.0);
        assertTrue(after < before, "after=" + after);
    }

    @Test
    void newRating_draw_againstEqual_isUnchanged() {
        // Equal ratings, draw → expected 0.5, actual 0.5 → delta 0.
        int after = EloCalculator.newRating(1500, 1500, 0.5);
        assertEquals(1500, after);
    }

    @Test
    void newRating_winAgainstStronger_givesMoreGain() {
        int gainVsWeak = EloCalculator.newRating(1500, 1400, 1.0) - 1500;
        int gainVsStrong = EloCalculator.newRating(1500, 1800, 1.0) - 1500;
        assertTrue(gainVsStrong > gainVsWeak,
                "gainVsStrong=" + gainVsStrong + " gainVsWeak=" + gainVsWeak);
    }

    @Test
    void newRating_kFactorReducesSwingAtHighRating() {
        // At 2200 (K=24) the swing should be smaller than at 1500 (K=32) for the same opponents.
        int swingLow = Math.abs(EloCalculator.newRating(1500, 1500, 1.0) - 1500);
        int swingHigh = Math.abs(EloCalculator.newRating(2200, 2200, 1.0) - 2200);
        assertTrue(swingHigh < swingLow,
                "swingHigh=" + swingHigh + " swingLow=" + swingLow);
    }

    @Test
    void newRating_clampsToZero() {
        // Extreme loss from a very low rating against a much stronger opponent
        // must not go negative. Use rating 0 so the result clamps to 0.
        int after = EloCalculator.newRating(0, 3000, 0.0);
        assertEquals(0, after);
    }

    @Test
    void newScore_invalidScore_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> EloCalculator.newRating(1500, 1500, 1.5));
        assertThrows(IllegalArgumentException.class,
                () -> EloCalculator.newRating(1500, 1500, -0.1));
    }

    @Test
    void newRating_symmetry_zeroSum() {
        // For equal ratings, win/loss should be symmetric (zero-sum).
        int winnerAfter = EloCalculator.newRating(1600, 1600, 1.0);
        int loserAfter = EloCalculator.newRating(1600, 1600, 0.0);
        // Both started at 1600; the winner's gain equals the loser's loss.
        assertEquals(winnerAfter - 1600, 1600 - loserAfter);
    }

    @Test
    void gameType_usedByRating() {
        // Sanity check the enum values the rating package depends on.
        assertEquals(GameType.XIANGQI, GameType.valueOf("XIANGQI"));
        assertEquals(GameType.GO, GameType.valueOf("GO"));
    }
}
