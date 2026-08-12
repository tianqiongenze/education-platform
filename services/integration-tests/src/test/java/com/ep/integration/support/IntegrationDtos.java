package com.ep.integration.support;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Self-contained DTOs and lightweight domain models used by the integration test
 * suite. These mirror the contracts exposed by core-business and grading-service.
 *
 * <p>Although the integration-tests module now declares test-scope Maven
 * dependencies on the production service modules (so the reflection-based
 * {@code CrossServiceContractTest} can verify real controller classes), the
 * fakes themselves still operate on these test-local DTOs rather than the
 * production JPA {@code @Entity} types. This keeps the in-memory fakes free of
 * persistence concerns (no {@code lockVersion}, {@code tenantId},
 * {@code passwordHash} etc.) while exercising the same business invariants.
 * Behavioural alignment with the production service interfaces is maintained at
 * the signature and invariant level — see {@code BusinessFakes} and
 * {@code ItBackendFakes} for details.
 */
public final class IntegrationDtos {

    private IntegrationDtos() {
    }

    // ---------- core-business contracts ----------

    public static final class CreateUserCmd implements Serializable {
        private static final long serialVersionUID = 1L;
        private String username;
        private String phone;
        private String email;
        private String password;
        private String nickname;

        public CreateUserCmd() {
        }

        public CreateUserCmd(String username, String phone, String email, String password, String nickname) {
            this.username = username;
            this.phone = phone;
            this.email = email;
            this.password = password;
            this.nickname = nickname;
        }

        public String getUsername() { return username; }
        public String getPhone() { return phone; }
        public String getEmail() { return email; }
        public String getPassword() { return password; }
        public String getNickname() { return nickname; }
    }

    /**
     * Mirrors {@code com.ep.business.user.UpdateUserCmd}: partial profile update
     * keyed by {@code userId} with optional nickname / avatar / email fields.
     */
    public static final class UpdateUserCmd implements Serializable {
        private static final long serialVersionUID = 1L;
        private Long userId;
        private String nickname;
        private String avatar;
        private String email;

        public UpdateUserCmd() {
        }

        public UpdateUserCmd(Long userId, String nickname, String avatar, String email) {
            this.userId = userId;
            this.nickname = nickname;
            this.avatar = avatar;
            this.email = email;
        }

        public Long getUserId() { return userId; }
        public String getNickname() { return nickname; }
        public String getAvatar() { return avatar; }
        public String getEmail() { return email; }
    }

    public static final class User implements Serializable {
        private static final long serialVersionUID = 1L;
        private Long userId;
        private String username;
        private String phone;
        private String email;
        private String nickname;
        private Integer status;
        private Long createdAt;
        private Long updatedAt;

        public User() {
        }

        public Long getUserId() { return userId; }
        public User setUserId(Long userId) { this.userId = userId; return this; }
        public String getUsername() { return username; }
        public User setUsername(String username) { this.username = username; return this; }
        public String getPhone() { return phone; }
        public User setPhone(String phone) { this.phone = phone; return this; }
        public String getEmail() { return email; }
        public User setEmail(String email) { this.email = email; return this; }
        public String getNickname() { return nickname; }
        public User setNickname(String nickname) { this.nickname = nickname; return this; }
        public Integer getStatus() { return status; }
        public User setStatus(Integer status) { this.status = status; return this; }
        public Long getCreatedAt() { return createdAt; }
        public User setCreatedAt(Long createdAt) { this.createdAt = createdAt; return this; }
        public Long getUpdatedAt() { return updatedAt; }
        public User setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; return this; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof User u)) return false;
            return Objects.equals(userId, u.userId);
        }

        @Override
        public int hashCode() { return Objects.hash(userId); }
    }

    public enum UserStatus {
        DISABLED(0), ACTIVE(1), FROZEN(2);
        private final int code;
        UserStatus(int code) { this.code = code; }
        public int getCode() { return code; }
    }

    public static final class CreateCourseCmd implements Serializable {
        private static final long serialVersionUID = 1L;
        private String title;
        private String description;
        private Long teacherId;
        private Integer category;
        private Integer difficulty;
        private BigDecimal price;

        public CreateCourseCmd() {
        }

        public CreateCourseCmd(String title, String description, Long teacherId, Integer category,
                               Integer difficulty, BigDecimal price) {
            this.title = title;
            this.description = description;
            this.teacherId = teacherId;
            this.category = category;
            this.difficulty = difficulty;
            this.price = price;
        }

        public String getTitle() { return title; }
        public String getDescription() { return description; }
        public Long getTeacherId() { return teacherId; }
        public Integer getCategory() { return category; }
        public Integer getDifficulty() { return difficulty; }
        public BigDecimal getPrice() { return price; }
    }

    public static final class Course implements Serializable {
        private static final long serialVersionUID = 1L;
        private Long courseId;
        private String title;
        private Long teacherId;
        private BigDecimal price;
        private Integer status;
        private Long createdAt;
        private Long updatedAt;

        public Course() {
        }

        public Long getCourseId() { return courseId; }
        public Course setCourseId(Long courseId) { this.courseId = courseId; return this; }
        public String getTitle() { return title; }
        public Course setTitle(String title) { this.title = title; return this; }
        public Long getTeacherId() { return teacherId; }
        public Course setTeacherId(Long teacherId) { this.teacherId = teacherId; return this; }
        public BigDecimal getPrice() { return price; }
        public Course setPrice(BigDecimal price) { this.price = price; return this; }
        public Integer getStatus() { return status; }
        public Course setStatus(Integer status) { this.status = status; return this; }
        public Long getCreatedAt() { return createdAt; }
        public Course setCreatedAt(Long createdAt) { this.createdAt = createdAt; return this; }
        public Long getUpdatedAt() { return updatedAt; }
        public Course setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; return this; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Course c)) return false;
            return Objects.equals(courseId, c.courseId);
        }

        @Override
        public int hashCode() { return Objects.hash(courseId); }
    }

    public enum CourseStatus {
        DRAFT(0), PUBLISHED(1), ARCHIVED(2);
        private final int code;
        CourseStatus(int code) { this.code = code; }
        public int getCode() { return code; }
    }

    public static final class CreateOrderCmd implements Serializable {
        private static final long serialVersionUID = 1L;
        private Long userId;
        private Long courseId;
        private BigDecimal amount;
        private String idempotencyKey;

        public CreateOrderCmd() {
        }

        public CreateOrderCmd(Long userId, Long courseId, BigDecimal amount, String idempotencyKey) {
            this.userId = userId;
            this.courseId = courseId;
            this.amount = amount;
            this.idempotencyKey = idempotencyKey;
        }

        public Long getUserId() { return userId; }
        public Long getCourseId() { return courseId; }
        public BigDecimal getAmount() { return amount; }
        public String getIdempotencyKey() { return idempotencyKey; }
    }

    public static final class Order implements Serializable {
        private static final long serialVersionUID = 1L;
        private Long orderId;
        private Long userId;
        private Long courseId;
        private BigDecimal amount;
        private Integer status;
        private String payMethod;
        private String idempotencyKey;
        private Integer version;
        private Long createdAt;
        private Long updatedAt;

        public Order() {
        }

        public Long getOrderId() { return orderId; }
        public Order setOrderId(Long orderId) { this.orderId = orderId; return this; }
        public Long getUserId() { return userId; }
        public Order setUserId(Long userId) { this.userId = userId; return this; }
        public Long getCourseId() { return courseId; }
        public Order setCourseId(Long courseId) { this.courseId = courseId; return this; }
        public BigDecimal getAmount() { return amount; }
        public Order setAmount(BigDecimal amount) { this.amount = amount; return this; }
        public Integer getStatus() { return status; }
        public Order setStatus(Integer status) { this.status = status; return this; }
        public String getPayMethod() { return payMethod; }
        public Order setPayMethod(String payMethod) { this.payMethod = payMethod; return this; }
        public String getIdempotencyKey() { return idempotencyKey; }
        public Order setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; return this; }
        public Integer getVersion() { return version; }
        public Order setVersion(Integer version) { this.version = version; return this; }
        public Long getCreatedAt() { return createdAt; }
        public Order setCreatedAt(Long createdAt) { this.createdAt = createdAt; return this; }
        public Long getUpdatedAt() { return updatedAt; }
        public Order setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; return this; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Order o1)) return false;
            return Objects.equals(orderId, o1.orderId);
        }

        @Override
        public int hashCode() { return Objects.hash(orderId); }
    }

    public enum OrderStatus {
        PENDING(0), PAID(1), REFUNDED(2), CANCELLED(3);
        private final int code;
        OrderStatus(int code) { this.code = code; }
        public int getCode() { return code; }
    }

    // ---------- grading-service contracts ----------

    public record AssignmentCommand(String title, String description, Long courseId, Long teacherId,
                                    Integer maxScore, LocalDateTime deadline) {
    }

    public record SubmissionCommand(Long assignmentId, Long studentId, String content, String idempotencyKey) {
    }

    public record StartGradingCommand(Long submissionId, Long gradedBy, String idempotencyKey) {
    }

    public enum AssignmentStatus { DRAFT, PUBLISHED, CLOSED, ARCHIVED }
    public enum SubmissionStatus { SUBMITTED, IN_REVIEW, GRADED, RETURNED, LATE }
    public enum PlagiarismStatus { PENDING, CLEAN, FLAGGED, REVIEWING }
    public enum GradePipelineStatus { INITIATED, RUNNING, COMPLETED, FAILED, CANCELLED }
    public enum GradeStageStatus { PENDING, RUNNING, PASSED, FAILED, SKIPPED }
}
