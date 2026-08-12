/**
 * TypeScript interfaces for the SP-05 IT Backend service.
 *
 * These types mirror the Java entities and DTOs defined in
 * services/sp05-itbackend/src/main/java/com/ep/itbackend.
 *
 * Notes:
 * - Entity IDs are numeric (`number`) because the backend uses Java `Long`.
 * - Timestamps are ISO-8601 strings produced by Jackson from `LocalDateTime`.
 */

// ---------------------------------------------------------------------------
// Assignment
// ---------------------------------------------------------------------------

export type AssignmentStatus = 'DRAFT' | 'PUBLISHED' | 'CLOSED' | 'ARCHIVED';

export interface Assignment {
  id: number;
  title: string;
  description: string;
  courseId: number;
  teacherId: number;
  maxScore: number;
  /** ISO-8601 datetime string, e.g. "2026-08-15T23:59:00". */
  deadline: string;
  status: AssignmentStatus;
  createdAt: string;
  updatedAt: string;
}

/**
 * Request payload for POST /api/v1/assignments.
 * Mirrors the Java record `AssignmentCommand`.
 */
export interface AssignmentCommand {
  title: string;
  description?: string | undefined;
  courseId: number;
  teacherId?: number | undefined;
  maxScore?: number | undefined;
  /** ISO-8601 datetime string. */
  deadline: string;
}

// ---------------------------------------------------------------------------
// Submission
// ---------------------------------------------------------------------------

export type SubmissionStatus =
  | 'SUBMITTED'
  | 'IN_REVIEW'
  | 'GRADED'
  | 'RETURNED'
  | 'LATE';

export interface Submission {
  id: number;
  assignmentId: number;
  studentId: number;
  content: string;
  status: SubmissionStatus;
  score: number | null;
  feedback: string | null;
  idempotencyKey: string | null;
  version: number | null;
  submittedAt: string;
  gradedAt: string | null;
}

/**
 * Request payload for POST /api/v1/submissions.
 * Mirrors the Java record `SubmissionCommand`.
 */
export interface SubmissionCommand {
  assignmentId: number;
  studentId: number;
  content: string;
  idempotencyKey: string;
}

/**
 * Request payload for POST /api/v1/submissions/{id}/grade.
 * Mirrors the Java record `GradeSubmissionCommand`.
 */
export interface GradeSubmissionCommand {
  submissionId: number;
  score: number;
  feedback?: string | undefined;
  graderId?: number | undefined;
  idempotencyKey: string;
}

// ---------------------------------------------------------------------------
// Plagiarism
// ---------------------------------------------------------------------------

export type PlagiarismStatus = 'PENDING' | 'CLEAN' | 'FLAGGED' | 'REVIEWING';

export interface PlagiarismCheck {
  id: number;
  submissionId: number;
  similarityScore: number | null;
  matchedSources: string | null;
  status: PlagiarismStatus;
  checkedAt: string | null;
}

// ---------------------------------------------------------------------------
// Grading Pipeline
// ---------------------------------------------------------------------------

export type GradePipelineStatus =
  | 'INITIATED'
  | 'RUNNING'
  | 'COMPLETED'
  | 'FAILED'
  | 'CANCELLED';

export type GradeStageStatus = 'PENDING' | 'RUNNING' | 'PASSED' | 'FAILED' | 'SKIPPED';

/** The three canonical pipeline stages, in execution order. */
export type GradeStageName = 'auto_check' | 'plagiarism_check' | 'manual_review';

export interface GradeStage {
  id: number;
  stageName: string;
  status: GradeStageStatus;
  stageScore: number | null;
  stageResult: string | null;
  startedAt: string | null;
  completedAt: string | null;
  errorMessage: string | null;
}

export interface GradePipeline {
  id: number;
  submissionId: number;
  status: GradePipelineStatus;
  stages: GradeStage[];
  totalScore: number | null;
  gradedBy: number | null;
  startedAt: string | null;
  completedAt: string | null;
  idempotencyKey: string | null;
}

/**
 * Request payload for POST /api/v1/grading/start.
 * Mirrors the Java record `StartGradingCommand`.
 */
export interface StartGradingCommand {
  submissionId: number;
  gradedBy?: number | undefined;
  idempotencyKey: string;
}
