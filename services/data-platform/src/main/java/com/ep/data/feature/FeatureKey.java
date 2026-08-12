package com.ep.data.feature;

/**
 * Utility for building Redis keys that store feature values.
 *
 * <p>Key layout: {@code feat:{group}:{userId}}. Pattern for SCAN:
 * {@code feat:{group}:*}.
 */
public final class FeatureKey {

    /** Redis key prefix for all feature values. */
    public static final String FEATURE_PREFIX = "feat";

    private FeatureKey() {
    }

    /**
     * Build the Redis key for a single user's feature vector in a group.
     *
     * @param group  feature group name
     * @param userId user id
     * @return key like {@code feat:{group}:{userId}}
     */
    public static String buildKey(String group, Long userId) {
        if (group == null || group.isBlank()) {
            throw new IllegalArgumentException("group must not be blank");
        }
        if (userId == null) {
            throw new IllegalArgumentException("userId must not be null");
        }
        return FEATURE_PREFIX + ":" + group + ":" + userId;
    }

    /**
     * Build a SCAN pattern matching all users in a feature group.
     *
     * @param group feature group name
     * @return pattern like {@code feat:{group}:*}
     */
    public static String buildPattern(String group) {
        if (group == null || group.isBlank()) {
            throw new IllegalArgumentException("group must not be blank");
        }
        return FEATURE_PREFIX + ":" + group + ":*";
    }
}
