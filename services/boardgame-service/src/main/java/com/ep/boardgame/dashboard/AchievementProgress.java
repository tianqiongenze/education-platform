package com.ep.boardgame.dashboard;

/**
 * D-192: Achievement / badge earned (or progress toward it).
 *
 * @param code        stable achievement code (e.g. "RATING_1500")
 * @param name        human-readable name
 * @param description human-readable description
 * @param unlocked    whether the achievement is unlocked
 * @param progress    0.0–1.0 progress toward unlocking
 */
public record AchievementProgress(
        String code,
        String name,
        String description,
        boolean unlocked,
        double progress
) {}
