/**
 * API client functions for the SP-05 IT Backend service.
 *
 * All endpoints are served under `/api/v1/...` on port 8085.
 * The shared `apiClient` (see `src/api/client.ts`) prepends `apiBaseUrl`
 * (from `VITE_API_BASE_URL` or `/api`), so we pass the full path here.
 *
 * Each function is thin: it serializes the request, delegates to the shared
 * client, and returns a typed result. Errors surface as `ApiError` instances
 * from `@ep/api-client`, which callers can inspect with `isUnauthorized()`,
 * `isNotFound()`, etc.
 */
import { apiClient } from './client';
import { ApiError } from '@ep/api-client';
import type {
  Assignment,
  AssignmentCommand,
  Submission,
  SubmissionCommand,
  GradeSubmissionCommand,
  GradePipeline,
  GradeStage,
  StartGradingCommand,
} from '../types/it-backend';

export { ApiError };

// ---------------------------------------------------------------------------
// Assignments — /api/v1/assignments
// ---------------------------------------------------------------------------

/** POST /api/v1/assignments — create a new assignment. */
export async function createAssignment(cmd: AssignmentCommand): Promise<Assignment> {
  return apiClient.post<Assignment>('/api/v1/assignments', cmd);
}

/** POST /api/v1/assignments/{id}/publish — publish a DRAFT assignment. */
export async function publishAssignment(id: number): Promise<Assignment> {
  return apiClient.post<Assignment>(`/api/v1/assignments/${id}/publish`);
}

/** POST /api/v1/assignments/{id}/close — close a PUBLISHED assignment. */
export async function closeAssignment(id: number): Promise<Assignment> {
  return apiClient.post<Assignment>(`/api/v1/assignments/${id}/close`);
}

/** GET /api/v1/assignments/{id} — fetch a single assignment. */
export async function getAssignment(id: number): Promise<Assignment> {
  return apiClient.get<Assignment>(`/api/v1/assignments/${id}`);
}

/** GET /api/v1/assignments?courseId={id} — list assignments for a course. */
export async function listAssignments(courseId: number): Promise<Assignment[]> {
  return apiClient.get<Assignment[]>('/api/v1/assignments', { courseId });
}

// ---------------------------------------------------------------------------
// Submissions — /api/v1/submissions
// ---------------------------------------------------------------------------

/** POST /api/v1/submissions — submit homework. */
export async function submitHomework(cmd: SubmissionCommand): Promise<Submission> {
  return apiClient.post<Submission>('/api/v1/submissions', cmd);
}

/** POST /api/v1/submissions/{id}/grade — grade a submission. */
export async function gradeSubmission(
  id: number,
  cmd: Omit<GradeSubmissionCommand, 'submissionId'>,
): Promise<Submission> {
  return apiClient.post<Submission>(`/api/v1/submissions/${id}/grade`, {
    submissionId: id,
    ...cmd,
  });
}

/** GET /api/v1/submissions/{id} — fetch a single submission. */
export async function getSubmission(id: number): Promise<Submission> {
  return apiClient.get<Submission>(`/api/v1/submissions/${id}`);
}

/** GET /api/v1/submissions?assignmentId={id} — list submissions for an assignment. */
export async function listSubmissions(assignmentId: number): Promise<Submission[]> {
  return apiClient.get<Submission[]>('/api/v1/submissions', { assignmentId });
}

// ---------------------------------------------------------------------------
// Grading — /api/v1/grading
// ---------------------------------------------------------------------------

/** POST /api/v1/grading/start — start the 3-stage grading pipeline. */
export async function startGrading(cmd: StartGradingCommand): Promise<GradePipeline> {
  return apiClient.post<GradePipeline>('/api/v1/grading/start', cmd);
}

/** GET /api/v1/grading/{id} — fetch a grading pipeline by ID. */
export async function getGradingPipeline(id: number): Promise<GradePipeline> {
  return apiClient.get<GradePipeline>(`/api/v1/grading/${id}`);
}

/** GET /api/v1/grading/{id}/stages — fetch the stages of a pipeline. */
export async function getGradingStages(id: number): Promise<GradeStage[]> {
  return apiClient.get<GradeStage[]>(`/api/v1/grading/${id}/stages`);
}

/** GET /api/v1/grading?submissionId={id} — find the pipeline for a submission. */
export async function findGradingBySubmission(submissionId: number): Promise<GradePipeline> {
  return apiClient.get<GradePipeline>('/api/v1/grading', { submissionId });
}
