package com.ep.boardgame.dashboard;

import java.time.Instant;
import java.util.List;

/**
 * D-192: A single data point in a player's rating progress time series.
 */
public record RatingPoint(
        Instant timestamp,
        int rating,
        String gameType
) {}
