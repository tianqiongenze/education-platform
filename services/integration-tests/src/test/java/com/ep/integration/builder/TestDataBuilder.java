package com.ep.integration.builder;

import com.ep.integration.support.IntegrationDtos;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Centralised builder for test data used across the integration test suite.
 * All builders return fully-populated DTOs with sensible defaults so that
 * individual tests only need to override the fields they care about.
 */
public final class TestDataBuilder {

    public static final String DEFAULT_PHONE_PREFIX = "1380000";
    public static final BigDecimal DEFAULT_ORDER_AMOUNT = new BigDecimal("99.00");

    private TestDataBuilder() {
    }

    public static IntegrationDtos.CreateUserCmd buildUser(String username, String phone) {
        String safePhone = phone == null || phone.isBlank()
                ? DEFAULT_PHONE_PREFIX + System.nanoTime() % 100000
                : phone;
        String safeName = username == null || username.isBlank()
                ? "user_" + System.nanoTime()
                : username;
        return new IntegrationDtos.CreateUserCmd(
                safeName,
                safePhone,
                safeName + "@ep.com",
                "Passw0rd!",
                safeName);
    }

    public static IntegrationDtos.CreateCourseCmd buildCourse(String title, Long teacherId) {
        String safeTitle = title == null || title.isBlank()
                ? "Course-" + System.nanoTime()
                : title;
        Long safeTeacher = teacherId == null ? 1L : teacherId;
        return new IntegrationDtos.CreateCourseCmd(
                safeTitle,
                "Default course description",
                safeTeacher,
                4,
                1,
                DEFAULT_ORDER_AMOUNT);
    }

    public static IntegrationDtos.CreateOrderCmd buildOrder(Long userId, Long courseId, String idempotencyKey) {
        String key = idempotencyKey == null || idempotencyKey.isBlank()
                ? "idem-" + System.nanoTime()
                : idempotencyKey;
        return new IntegrationDtos.CreateOrderCmd(
                userId == null ? 1L : userId,
                courseId == null ? 1L : courseId,
                DEFAULT_ORDER_AMOUNT,
                key);
    }

    public static IntegrationDtos.AssignmentCommand buildAssignment(String title, Long courseId, Long teacherId) {
        String safeTitle = title == null || title.isBlank()
                ? "Assignment-" + System.nanoTime()
                : title;
        return new IntegrationDtos.AssignmentCommand(
                safeTitle,
                "Default assignment description",
                courseId == null ? 1L : courseId,
                teacherId == null ? 1L : teacherId,
                100,
                LocalDateTime.now().plusDays(7));
    }

    public static IntegrationDtos.SubmissionCommand buildSubmission(Long assignmentId, Long studentId, String idempotencyKey) {
        String key = idempotencyKey == null || idempotencyKey.isBlank()
                ? "sub-idem-" + System.nanoTime()
                : idempotencyKey;
        return new IntegrationDtos.SubmissionCommand(
                assignmentId == null ? 1L : assignmentId,
                studentId == null ? 1L : studentId,
                "This is a short submission for testing purposes.",
                key);
    }

    public static IntegrationDtos.StartGradingCommand buildGrading(Long submissionId, Long gradedBy, String idempotencyKey) {
        String key = idempotencyKey == null || idempotencyKey.isBlank()
                ? "grade-idem-" + System.nanoTime()
                : idempotencyKey;
        return new IntegrationDtos.StartGradingCommand(
                submissionId == null ? 1L : submissionId,
                gradedBy == null ? 1L : gradedBy,
                key);
    }
}
