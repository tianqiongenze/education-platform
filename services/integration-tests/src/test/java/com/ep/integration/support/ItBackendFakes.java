package com.ep.integration.support;

import com.ep.integration.support.IntegrationDtos.AssignmentCommand;
import com.ep.integration.support.IntegrationDtos.AssignmentStatus;
import com.ep.integration.support.IntegrationDtos.GradePipelineStatus;
import com.ep.integration.support.IntegrationDtos.GradeStageStatus;
import com.ep.integration.support.IntegrationDtos.PlagiarismStatus;
import com.ep.integration.support.IntegrationDtos.StartGradingCommand;
import com.ep.integration.support.IntegrationDtos.SubmissionCommand;
import com.ep.integration.support.IntegrationDtos.SubmissionStatus;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory fake implementations reproducing the behaviour of grading-service
 * services (Assignment / Submission / Plagiarism / Grading). Behaviour mirrors
 * the real services including idempotency keys, optimistic-lock versions on
 * submission grading, the 3-stage grading pipeline (auto_check,
 * plagiarism_check, manual_review), and the content-length based plagiarism
 * scoring.
 *
 * <p>Behavioural alignment with the production service interfaces
 * ({@code com.ep.grading.homework.service.AssignmentService},
 * {@code com.ep.grading.homework.service.SubmissionService},
 * {@code com.ep.grading.homework.service.PlagiarismService},
 * {@code com.ep.grading.grading.service.GradingService}) is maintained at the
 * signature and invariant level: method names, parameter lists and return
 * void-ness mirror the production contracts. The fakes operate on test-local
 * POJOs (the inner {@code Assignment}, {@code Submission}, etc. classes below)
 * rather than the JPA {@code @Entity} types declared in the production
 * interfaces, so they do not literally {@code implements} those interfaces —
 * this keeps the test module free of JPA persistence concerns while still
 * exercising the same business rules. The fake method set is a superset of the
 * production interface (it also exposes {@code remove(...)} compensating
 * actions used by Seata-rollback simulation tests).
 *
 * <p><b>Intentional test brevity:</b> the inner entity-like classes
 * ({@link Assignment}, {@link Submission}, {@link PlagiarismCheck},
 * {@link GradeStage}, {@link GradePipeline}) use <b>public mutable fields</b>
 * rather than JPA-annotated private fields with getters/setters. This is
 * deliberate: it keeps the fakes compact and easy to assemble in tests, and
 * these types never cross a persistence or service boundary.
 */
public final class ItBackendFakes {

    public static final class Assignment {
        public Long id;
        public String title;
        public String description;
        public Long courseId;
        public Long teacherId;
        public Integer maxScore;
        public LocalDateTime deadline;
        public AssignmentStatus status;
        public LocalDateTime createdAt;
        public LocalDateTime updatedAt;
    }

    public static final class Submission {
        public Long id;
        public Long assignmentId;
        public Long studentId;
        public String content;
        public SubmissionStatus status;
        public Integer score;
        public String feedback;
        public String idempotencyKey;
        public Integer version;
        public LocalDateTime submittedAt;
        public LocalDateTime gradedAt;
    }

    public static final class PlagiarismCheck {
        public Long id;
        public Long submissionId;
        public double similarityScore;
        public String matchedSources;
        public PlagiarismStatus status;
        public LocalDateTime checkedAt;
    }

    public static final class GradeStage {
        public Long id;
        public String stageName;
        public GradeStageStatus status;
        public Integer stageScore;
        public String stageResult;
        public LocalDateTime startedAt;
        public LocalDateTime completedAt;
    }

    public static final class GradePipeline {
        public Long id;
        public Long submissionId;
        public Long gradedBy;
        public GradePipelineStatus status;
        public Integer totalScore;
        public String idempotencyKey;
        public LocalDateTime startedAt;
        public LocalDateTime completedAt;
        public List<GradeStage> stages = new ArrayList<>();
    }

    public static final class FakeAssignmentService {
        private final Map<Long, Assignment> store = new ConcurrentHashMap<>();
        private final AtomicLong idGen = new AtomicLong(0);

        public Assignment create(AssignmentCommand cmd) {
            Objects.requireNonNull(cmd, "cmd");
            if (cmd.title() == null || cmd.title().isBlank()) {
                throw new IllegalArgumentException("title cannot be blank");
            }
            long id = idGen.incrementAndGet();
            LocalDateTime now = LocalDateTime.now();
            Assignment a = new Assignment();
            a.id = id;
            a.title = cmd.title();
            a.description = cmd.description();
            a.courseId = cmd.courseId();
            a.teacherId = cmd.teacherId();
            a.maxScore = cmd.maxScore() == null ? 100 : cmd.maxScore();
            a.deadline = cmd.deadline();
            a.status = AssignmentStatus.DRAFT;
            a.createdAt = now;
            a.updatedAt = now;
            store.put(id, a);
            return a;
        }

        public Assignment publish(Long id) {
            Assignment a = store.get(id);
            if (a == null) throw new IllegalArgumentException("Assignment not found: " + id);
            if (a.status != AssignmentStatus.DRAFT) {
                throw new IllegalStateException("Assignment must be DRAFT to publish, current: " + a.status);
            }
            a.status = AssignmentStatus.PUBLISHED;
            a.updatedAt = LocalDateTime.now();
            return a;
        }

        /** Mirrors {@code AssignmentService.close(Long id)}: PUBLISHED -> CLOSED. */
        public Assignment close(Long id) {
            Assignment a = store.get(id);
            if (a == null) throw new IllegalArgumentException("Assignment not found: " + id);
            if (a.status != AssignmentStatus.PUBLISHED) {
                throw new IllegalStateException(
                        "Assignment must be PUBLISHED to close, current: " + a.status);
            }
            a.status = AssignmentStatus.CLOSED;
            a.updatedAt = LocalDateTime.now();
            return a;
        }

        public Assignment findById(Long id) {
            return store.get(id);
        }

        /** Mirrors {@code AssignmentService.findByCourseId(Long courseId)}. */
        public List<Assignment> findByCourseId(Long courseId) {
            List<Assignment> out = new ArrayList<>();
            for (Assignment a : store.values()) {
                if (Objects.equals(a.courseId, courseId)) out.add(a);
            }
            return out;
        }
    }

    public static final class FakeSubmissionService {
        private final Map<Long, Submission> store = new ConcurrentHashMap<>();
        private final Map<String, Long> idempotencyIndex = new ConcurrentHashMap<>();
        private final Map<Long, Integer> expectedVersion = new ConcurrentHashMap<>();
        private final AtomicLong idGen = new AtomicLong(0);

        public Submission submit(SubmissionCommand cmd) {
            Objects.requireNonNull(cmd, "cmd");
            String key = cmd.idempotencyKey();
            if (key != null && !key.isBlank()) {
                Long existing = idempotencyIndex.get(key);
                if (existing != null) return store.get(existing);
            }
            long id = idGen.incrementAndGet();
            Submission s = new Submission();
            s.id = id;
            s.assignmentId = cmd.assignmentId();
            s.studentId = cmd.studentId();
            s.content = cmd.content();
            s.status = SubmissionStatus.SUBMITTED;
            s.version = 0;
            s.submittedAt = LocalDateTime.now();
            s.idempotencyKey = key;
            store.put(id, s);
            expectedVersion.put(id, 0);
            if (key != null && !key.isBlank()) idempotencyIndex.put(key, id);
            return s;
        }

        /**
         * Mirrors {@code SubmissionService.grade(GradeSubmissionCommand cmd)}.
         * The production interface takes a single {@code GradeSubmissionCommand}
         * record; this fake accepts the equivalent individual parameters to keep
         * test call-sites readable while preserving the same shape
         * (submissionId, score, feedback, idempotencyKey). Idempotency and
         * optimistic-lock semantics match production.
         */
        public Submission grade(Long submissionId, Integer score, String feedback, String idempotencyKey) {
            String key = idempotencyKey;
            if (key != null && !key.isBlank()) {
                Long existing = idempotencyIndex.get(key);
                if (existing != null) return store.get(existing);
            }
            Submission s = store.get(submissionId);
            if (s == null) throw new IllegalArgumentException("Submission not found: " + submissionId);
            int currentVersion = s.version == null ? 0 : s.version;
            Integer expected = expectedVersion.get(submissionId);
            if (expected == null) expected = currentVersion;
            if (currentVersion != expected) {
                throw new IllegalStateException(
                        "Optimistic lock version mismatch: expected " + expected + " but found " + currentVersion);
            }
            s.score = score;
            s.feedback = feedback;
            s.status = SubmissionStatus.GRADED;
            s.version = currentVersion + 1;
            s.gradedAt = LocalDateTime.now();
            expectedVersion.put(submissionId, s.version);
            if (key != null && !key.isBlank()) idempotencyIndex.put(key, submissionId);
            return s;
        }

        public Submission findById(Long id) { return store.get(id); }

        /** Mirrors {@code SubmissionService.findByAssignmentId(Long assignmentId)}. */
        public List<Submission> findByAssignmentId(Long assignmentId) {
            List<Submission> out = new ArrayList<>();
            for (Submission s : store.values()) {
                if (Objects.equals(s.assignmentId, assignmentId)) out.add(s);
            }
            return out;
        }
    }

    public static final class FakePlagiarismService {
        private final Map<Long, PlagiarismCheck> store = new ConcurrentHashMap<>();
        private final Map<Long, PlagiarismCheck> bySubmission = new ConcurrentHashMap<>();
        private final AtomicLong idGen = new AtomicLong(0);
        private final FakeSubmissionService submissionService;

        public FakePlagiarismService(FakeSubmissionService submissionService) {
            this.submissionService = submissionService;
        }

        public PlagiarismCheck check(Long submissionId) {
            Objects.requireNonNull(submissionId, "submissionId");
            int contentLength = 0;
            if (submissionService != null) {
                Submission s = submissionService.findById(submissionId);
                if (s != null && s.content != null) contentLength = s.content.length();
            }
            if (contentLength == 0) contentLength = (int) (Math.abs(submissionId) * 17);
            double similarity = (contentLength % 100) / 100.0;
            PlagiarismStatus status;
            if (similarity < 0.3) status = PlagiarismStatus.CLEAN;
            else if (similarity >= 0.7) status = PlagiarismStatus.FLAGGED;
            else status = PlagiarismStatus.REVIEWING;

            long id = idGen.incrementAndGet();
            PlagiarismCheck c = new PlagiarismCheck();
            c.id = id;
            c.submissionId = submissionId;
            c.similarityScore = similarity;
            c.matchedSources = "[]";
            c.status = status;
            c.checkedAt = LocalDateTime.now();
            store.put(id, c);
            // Also index by submissionId so findBySubmissionId can return the
            // latest check, matching PlagiarismService.findBySubmissionId.
            bySubmission.put(submissionId, c);
            return c;
        }

        /** Mirrors {@code PlagiarismService.findBySubmissionId(Long submissionId)}. */
        public PlagiarismCheck findBySubmissionId(Long submissionId) {
            return bySubmission.get(submissionId);
        }
    }

    public static final class FakeGradingService {
        private final Map<Long, GradePipeline> pipelineStore = new ConcurrentHashMap<>();
        private final Map<Long, List<GradeStage>> stageStore = new ConcurrentHashMap<>();
        private final Map<String, Long> idempotencyIndex = new ConcurrentHashMap<>();
        private final AtomicLong pipelineIdGen = new AtomicLong(0);
        private final AtomicLong stageIdGen = new AtomicLong(0);
        private final FakePlagiarismService plagiarismService;

        public FakeGradingService(FakePlagiarismService plagiarismService) {
            this.plagiarismService = plagiarismService;
        }

        public GradePipeline startGrading(StartGradingCommand cmd) {
            Objects.requireNonNull(cmd, "cmd");
            String key = cmd.idempotencyKey();
            if (key != null && !key.isBlank()) {
                Long existing = idempotencyIndex.get(key);
                if (existing != null) return pipelineStore.get(existing);
            }
            long pipelineId = pipelineIdGen.incrementAndGet();
            LocalDateTime now = LocalDateTime.now();
            GradePipeline pipeline = new GradePipeline();
            pipeline.id = pipelineId;
            pipeline.submissionId = cmd.submissionId();
            pipeline.gradedBy = cmd.gradedBy();
            pipeline.status = GradePipelineStatus.RUNNING;
            pipeline.startedAt = now;
            pipeline.idempotencyKey = key;

            List<GradeStage> stages = new ArrayList<>();
            // Stage 1: auto_check
            GradeStage auto = newStage("auto_check");
            auto.status = GradeStageStatus.PASSED;
            auto.startedAt = now;
            auto.completedAt = now;
            auto.stageScore = 40;
            auto.stageResult = "Auto checks passed";
            stages.add(auto);
            // Stage 2: plagiarism_check
            GradeStage plag = newStage("plagiarism_check");
            plag.startedAt = now;
            int plagScore = 0;
            boolean plagPassed = true;
            try {
                PlagiarismCheck check = plagiarismService != null ? plagiarismService.check(cmd.submissionId()) : null;
                if (check != null) {
                    plagPassed = check.status == PlagiarismStatus.CLEAN || check.status == PlagiarismStatus.REVIEWING;
                    plag.stageResult = "similarity=" + check.similarityScore;
                    if (check.status == PlagiarismStatus.CLEAN) plagScore = 30;
                } else {
                    plag.stageResult = "No content to check";
                    plagScore = 30;
                }
            } catch (Exception e) {
                plagPassed = false;
                plag.stageResult = e.getMessage();
            }
            plag.status = plagPassed ? GradeStageStatus.PASSED : GradeStageStatus.FAILED;
            plag.stageScore = plagScore;
            plag.completedAt = now;
            stages.add(plag);
            // Stage 3: manual_review
            GradeStage manual = newStage("manual_review");
            manual.status = GradeStageStatus.PENDING;
            stages.add(manual);

            pipeline.stages = stages;
            pipeline.totalScore = stages.stream()
                    .map(s -> s.stageScore)
                    .filter(Objects::nonNull)
                    .mapToInt(Integer::intValue)
                    .sum();

            boolean firstTwoDone = stages.get(0).status == GradeStageStatus.PASSED
                    && stages.get(1).status == GradeStageStatus.PASSED;
            if (firstTwoDone) {
                pipeline.status = GradePipelineStatus.COMPLETED;
                pipeline.completedAt = now;
            } else if (stages.get(1).status == GradeStageStatus.FAILED) {
                pipeline.status = GradePipelineStatus.FAILED;
                pipeline.completedAt = now;
            }

            pipelineStore.put(pipelineId, pipeline);
            stageStore.put(pipelineId, stages);
            if (key != null && !key.isBlank()) idempotencyIndex.put(key, pipelineId);
            return pipeline;
        }

        public GradePipeline findById(Long id) { return pipelineStore.get(id); }

        /** Mirrors {@code GradingService.findBySubmissionId(Long submissionId)}. */
        public GradePipeline findBySubmissionId(Long submissionId) {
            for (GradePipeline p : pipelineStore.values()) {
                if (Objects.equals(p.submissionId, submissionId)) return p;
            }
            return null;
        }

        /** Compensating action used by Seata-rollback simulation tests. */
        public void remove(Long pipelineId) {
            if (pipelineId == null) return;
            pipelineStore.remove(pipelineId);
            stageStore.remove(pipelineId);
        }

        public List<GradeStage> getStages(Long pipelineId) {
            List<GradeStage> s = stageStore.get(pipelineId);
            return s == null ? List.of() : new ArrayList<>(s);
        }

        private GradeStage newStage(String name) {
            GradeStage s = new GradeStage();
            s.id = stageIdGen.incrementAndGet();
            s.stageName = name;
            return s;
        }
    }
}
