package com.ep.boardgame.dashboard;

/**
 * D-192: Win/loss/draw aggregate for a single game type.
 */
public record WinLossStats(
        String gameType,
        int wins,
        int losses,
        int draws,
        int total
) {}
