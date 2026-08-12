package com.ep.grading.homework.entity;

public enum SubmissionStatus {
    SUBMITTED,
    IN_REVIEW,
    GRADED,
    RETURNED,
    LATE;

    public static SubmissionStatus fromString(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Submission status value cannot be null");
        }
        try {
            return SubmissionStatus.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown submission status: " + value);
        }
    }
}
