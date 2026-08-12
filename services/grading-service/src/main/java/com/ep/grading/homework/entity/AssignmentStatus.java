package com.ep.grading.homework.entity;

public enum AssignmentStatus {
    DRAFT,
    PUBLISHED,
    CLOSED,
    ARCHIVED;

    public static AssignmentStatus fromString(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Assignment status value cannot be null");
        }
        try {
            return AssignmentStatus.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown assignment status: " + value);
        }
    }
}
