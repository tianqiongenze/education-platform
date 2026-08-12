/**
 * Shared helpers for admin-web Playwright E2E tests.
 *
 * These helpers wrap the SP-03 business + SP-05 it-backend APIs so tests can
 * perform setup/teardown via `page.request` (fast) instead of clicking through
 * the UI. They also provide unique-value generators so parallel runs and
 * repeated local runs don't collide on backend uniqueness constraints
 * (e.g. phone numbers).
 *
 * Conventions:
 * - All helpers are best-effort on teardown: failures are swallowed so a
 *   leftover record in one test does not break the next.
 * - API paths are relative to the Vite dev server (port 3000), which proxies
 *   `/api/v1/users|courses|orders` to sp03 (8083) and
 *   `/api/v1/assignments|submissions|grading` to sp05 (8085).
 */
import type { Page, APIRequestContext } from '@playwright/test';

// ---------------------------------------------------------------------------
// API path constants — keep in sync with src/api/business.ts & it-backend.ts
// ---------------------------------------------------------------------------

export const API = {
  USERS: '/api/v1/users',
  userById: (id: number) => `/api/v1/users/${id}`,
  userByPhone: (phone: string) => `/api/v1/users/phone/${encodeURIComponent(phone)}`,
  freezeUser: (id: number) => `/api/v1/users/${id}/freeze`,

  COURSES: '/api/v1/courses',
  courseById: (id: number) => `/api/v1/courses/${id}`,
  courseSearch: '/api/v1/courses/search',
  publishCourse: (id: number) => `/api/v1/courses/${id}/publish`,
  archiveCourse: (id: number) => `/api/v1/courses/${id}/archive`,

  ORDERS: '/api/v1/orders',
  orderById: (id: number) => `/api/v1/orders/${id}`,
  payOrder: (id: number) => `/api/v1/orders/${id}/pay`,
  refundOrder: (id: number) => `/api/v1/orders/${id}/refund`,
  userOrders: (userId: number) => `/api/v1/orders/user/${userId}`,

  ASSIGNMENTS: '/api/v1/assignments',
  assignmentById: (id: number) => `/api/v1/assignments/${id}`,
  publishAssignment: (id: number) => `/api/v1/assignments/${id}/publish`,
  closeAssignment: (id: number) => `/api/v1/assignments/${id}/close`,

  SUBMISSIONS: '/api/v1/submissions',
  submissionById: (id: number) => `/api/v1/submissions/${id}`,
  gradeSubmission: (id: number) => `/api/v1/submissions/${id}/grade`,

  GRADING: '/api/v1/grading',
  gradingStart: '/api/v1/grading/start',
  gradingById: (id: number) => `/api/v1/grading/${id}`,
  gradingStages: (id: number) => `/api/v1/grading/${id}/stages`,
  gradingBySubmission: (submissionId: number) =>
    `/api/v1/grading?submissionId=${submissionId}`,
} as const;

// ---------------------------------------------------------------------------
// Route constants — keep in sync with src/routes/index.tsx
// ---------------------------------------------------------------------------

export const ROUTES = {
  login: '/login',
  dashboard: '/dashboard',
  users: '/business/users',
  userCreate: '/business/users/create',
  userDetail: (id: number) => `/business/users/${id}`,
  courses: '/business/courses',
  courseCreate: '/business/courses/create',
  courseDetail: (id: number) => `/business/courses/${id}`,
  orders: '/business/orders',
  orderDetail: (id: number) => `/business/orders/${id}`,
  assignments: '/it-backend/assignments',
  assignmentCreate: '/it-backend/assignments/create',
  assignmentDetail: (id: number) => `/it-backend/assignments/${id}`,
  submissions: '/it-backend/submissions',
  grading: (submissionId: number) => `/it-backend/grading/${submissionId}`,
  gradingGrade: (submissionId: number) =>
    `/it-backend/grading/${submissionId}/grade`,
} as const;

// ---------------------------------------------------------------------------
// Auth helper
// ---------------------------------------------------------------------------

/**
 * LocalStorage keys used by the BFF auth flow. Defined in
 * @ep/shared (STORAGE_KEYS). Inlined here so E2E tests don't need to import
 * from the monorepo.
 */
export const STORAGE_KEYS = {
  ACCESS_TOKEN: 'ep_access_token',
  REFRESH_TOKEN: 'ep_refresh_token',
  USER_PROFILE: 'ep_user_profile',
} as const;

/**
 * Seed localStorage with a fake authenticated session so the AuthGuard lets
 * us reach admin pages without going through the real BFF login flow.
 *
 * The admin-web AuthGuard only checks `user` from UserContext (set from
 * localStorage on mount via UserProvider's initialUser). We inject a minimal
 * admin user profile + access token so the guard accepts the route. API calls
 * to /api/v1/* go through the Vite proxy to sp03/sp05 directly and do not
 * require a valid BFF token in this dev/E2E environment.
 */
export async function loginAsAdmin(page: Page) {
  const adminProfile = {
    userId: 1,
    username: 'e2e-admin',
    nickname: 'E2E Admin',
    role: 'super_admin',
    avatar: null,
  };
  await page.addInitScript((profile) => {
    try {
      window.localStorage.setItem('ep_access_token', 'e2e-fake-token');
      window.localStorage.setItem('ep_refresh_token', 'e2e-fake-refresh');
      window.localStorage.setItem('ep_user_profile', JSON.stringify(profile));
    } catch {
      /* ignore */
    }
  }, adminProfile);
}

// ---------------------------------------------------------------------------
// Unique-value generators
// ---------------------------------------------------------------------------

/** e.g. "user-1700000000000-1234" */
export function uniqueName(prefix: string): string {
  return `${prefix}-${Date.now()}-${Math.floor(Math.random() * 10000)}`;
}

/** Returns an 11-digit Chinese mobile number starting with 139. */
export function uniquePhone(): string {
  return `139${Date.now().toString().slice(-8)}`;
}

/** Returns a unique email suitable for test users. */
export function uniqueEmail(): string {
  return `e2e-${Date.now()}-${Math.floor(Math.random() * 10000)}@test.local`;
}

// ---------------------------------------------------------------------------
// API helpers — use page.request (or a shared APIRequestContext)
// ---------------------------------------------------------------------------

interface UserPayload {
  userId: number;
  username: string;
  phone: string;
  email: string;
  nickname: string | null;
  status: number;
  [k: string]: unknown;
}

interface CoursePayload {
  courseId: number;
  title: string;
  description: string | null;
  teacherId: number;
  status: number;
  [k: string]: unknown;
}

interface OrderPayload {
  orderId: number;
  userId: number;
  courseId: number;
  amount: string | null;
  status: number;
  [k: string]: unknown;
}

interface AssignmentPayload {
  id: number;
  title: string;
  courseId: number;
  teacherId: number;
  status: string;
  [k: string]: unknown;
}

interface SubmissionPayload {
  id: number;
  assignmentId: number;
  studentId: number;
  status: string;
  [k: string]: unknown;
}

/**
 * Create a user via the sp03 API. Returns the created user object.
 * Uses page.request so it shares the Vite proxy and runs in browser context.
 */
export async function createUserViaApi(
  req: APIRequestContext | Page,
  overrides: Partial<{
    username: string;
    phone: string;
    email: string;
    password: string;
    nickname: string;
  }> = {},
): Promise<UserPayload> {
  const body = {
    username: overrides.username ?? uniqueName('e2euser'),
    phone: overrides.phone ?? uniquePhone(),
    email: overrides.email ?? uniqueEmail(),
    password: overrides.password ?? 'Passw0rd!',
    nickname: overrides.nickname ?? null,
  };
  const res = await req.request.post(API.USERS, { data: body });
  if (!res.ok()) {
    throw new Error(`createUserViaApi failed: ${res.status()} ${await res.text()}`);
  }
  return (await res.json()) as UserPayload;
}

/**
 * Best-effort freeze of a user. The backend has no DELETE endpoint, so we
 * freeze to prevent the user from being acted on by subsequent tests.
 * Never throws.
 */
export async function cleanupUser(req: APIRequestContext | Page, userId: number): Promise<void> {
  try {
    await req.request.post(API.freezeUser(userId), {
      data: { reason: 'e2e-cleanup' },
    });
  } catch {
    /* best-effort */
  }
}

/** Create a course via the sp03 API. Returns the created course. */
export async function createCourseViaApi(
  req: APIRequestContext | Page,
  overrides: Partial<{
    title: string;
    description: string | null;
    teacherId: number;
    category: number | null;
    difficulty: number | null;
    price: string | null;
  }> = {},
): Promise<CoursePayload> {
  const body = {
    title: overrides.title ?? uniqueName('E2E Course'),
    description: overrides.description ?? null,
    teacherId: overrides.teacherId ?? 2001,
    category: overrides.category ?? null,
    difficulty: overrides.difficulty ?? null,
    price: overrides.price ?? null,
  };
  const res = await req.request.post(API.COURSES, { data: body });
  if (!res.ok()) {
    throw new Error(`createCourseViaApi failed: ${res.status()} ${await res.text()}`);
  }
  return (await res.json()) as CoursePayload;
}

/** Publish a course via the sp03 API. Never throws (best-effort). */
export async function publishCourseViaApi(
  req: APIRequestContext | Page,
  courseId: number,
): Promise<void> {
  const res = await req.request.post(API.publishCourse(courseId));
  if (!res.ok()) {
    throw new Error(`publishCourseViaApi failed: ${res.status()} ${await res.text()}`);
  }
}

/** Archive a course via the sp03 API. Never throws (best-effort). */
export async function archiveCourseViaApi(
  req: APIRequestContext | Page,
  courseId: number,
): Promise<void> {
  try {
    await req.request.post(API.archiveCourse(courseId));
  } catch {
    /* best-effort */
  }
}

/** Create an order via the sp03 API. Returns the created order. */
export async function createOrderViaApi(
  req: APIRequestContext | Page,
  userId: number,
  courseId: number,
  overrides: Partial<{ amount: string | null; idempotencyKey: string }> = {},
): Promise<OrderPayload> {
  const body = {
    userId,
    courseId,
    amount: overrides.amount ?? null,
    idempotencyKey:
      overrides.idempotencyKey ?? `e2e-${Date.now()}-${Math.floor(Math.random() * 10000)}`,
  };
  const res = await req.request.post(API.ORDERS, { data: body });
  if (!res.ok()) {
    throw new Error(`createOrderViaApi failed: ${res.status()} ${await res.text()}`);
  }
  return (await res.json()) as OrderPayload;
}

/** Pay an order via the sp03 API. */
export async function payOrderViaApi(
  req: APIRequestContext | Page,
  orderId: number,
  payMethod = 'ALIPAY',
): Promise<void> {
  const res = await req.request.post(API.payOrder(orderId), { data: { payMethod } });
  if (!res.ok()) {
    throw new Error(`payOrderViaApi failed: ${res.status()} ${await res.text()}`);
  }
}

/** Refund an order via the sp03 API. Best-effort. */
export async function refundOrderViaApi(
  req: APIRequestContext | Page,
  orderId: number,
  reason = 'e2e-refund',
): Promise<void> {
  try {
    await req.request.post(API.refundOrder(orderId), { data: { reason } });
  } catch {
    /* best-effort */
  }
}

/**
 * Create an assignment via the sp05 API. Returns the created assignment.
 * `deadline` defaults to 7 days from now (ISO-8601).
 */
export async function createAssignmentViaApi(
  req: APIRequestContext | Page,
  overrides: Partial<{
    title: string;
    description: string;
    courseId: number;
    teacherId: number;
    maxScore: number;
    deadline: string;
  }> = {},
): Promise<AssignmentPayload> {
  const body = {
    title: overrides.title ?? uniqueName('E2E Assignment'),
    description: overrides.description ?? 'E2E test assignment',
    courseId: overrides.courseId ?? 1,
    teacherId: overrides.teacherId ?? 2001,
    maxScore: overrides.maxScore ?? 100,
    deadline: overrides.deadline ?? new Date(Date.now() + 7 * 24 * 3600 * 1000).toISOString(),
  };
  const res = await req.request.post(API.ASSIGNMENTS, { data: body });
  if (!res.ok()) {
    throw new Error(`createAssignmentViaApi failed: ${res.status()} ${await res.text()}`);
  }
  return (await res.json()) as AssignmentPayload;
}

/** Publish an assignment via the sp05 API. */
export async function publishAssignmentViaApi(
  req: APIRequestContext | Page,
  assignmentId: number,
): Promise<void> {
  const res = await req.request.post(API.publishAssignment(assignmentId));
  if (!res.ok()) {
    throw new Error(`publishAssignmentViaApi failed: ${res.status()} ${await res.text()}`);
  }
}

/** Submit homework via the sp05 API. Returns the created submission. */
export async function submitHomeworkViaApi(
  req: APIRequestContext | Page,
  assignmentId: number,
  studentId: number,
  content = 'E2E submission content',
): Promise<SubmissionPayload> {
  const body = {
    assignmentId,
    studentId,
    content,
    idempotencyKey: `e2e-sub-${Date.now()}-${Math.floor(Math.random() * 10000)}`,
  };
  const res = await req.request.post(API.SUBMISSIONS, { data: body });
  if (!res.ok()) {
    throw new Error(`submitHomeworkViaApi failed: ${res.status()} ${await res.text()}`);
  }
  return (await res.json()) as SubmissionPayload;
}

/** Start the 3-stage grading pipeline via the sp05 API. Returns the pipeline. */
export async function startGradingViaApi(
  req: APIRequestContext | Page,
  submissionId: number,
  gradedBy = 2001,
): Promise<{ id: number; submissionId: number; status: string; stages: unknown[] }> {
  const body = {
    submissionId,
    gradedBy,
    idempotencyKey: `e2e-grade-${submissionId}-${Date.now()}`,
  };
  const res = await req.request.post(API.gradingStart, { data: body });
  if (!res.ok()) {
    throw new Error(`startGradingViaApi failed: ${res.status()} ${await res.text()}`);
  }
  return (await res.json()) as { id: number; submissionId: number; status: string; stages: unknown[] };
}

/**
 * Poll the grading pipeline until it reaches a terminal status
 * (COMPLETED / FAILED / CANCELLED) or times out.
 */
export async function waitForGradingTerminal(
  req: APIRequestContext | Page,
  pipelineId: number,
  timeoutMs = 30_000,
  intervalMs = 1500,
): Promise<{ id: number; status: string; stages: unknown[] }> {
  const deadline = Date.now() + timeoutMs;
  let last: { id: number; status: string; stages: unknown[] } | undefined;
  while (Date.now() < deadline) {
    const res = await req.request.get(API.gradingById(pipelineId));
    if (res.ok()) {
      last = (await res.json()) as { id: number; status: string; stages: unknown[] };
      if (
        last.status === 'COMPLETED' ||
        last.status === 'FAILED' ||
        last.status === 'CANCELLED'
      ) {
        return last;
      }
    }
    await new Promise((r) => setTimeout(r, intervalMs));
  }
  if (last) return last;
  throw new Error(`waitForGradingTerminal timed out for pipeline #${pipelineId}`);
}

// ---------------------------------------------------------------------------
// UI helpers
// ---------------------------------------------------------------------------

/**
 * Navigate to a route after ensuring the auth seed is in place. Waits for the
 * page to be ready and for the AntD layout content to render.
 */
export async function goTo(page: Page, path: string): Promise<void> {
  await page.goto(path);
  // Wait for either the admin content area or the login card to appear so
  // we know React has mounted and routed.
  await page.waitForLoadState('networkidle');
}

/**
 * Wait for an AntD table row containing the given text to appear.
 * AntD tables render rows as <tr class="ant-table-row">.
 */
export async function waitForTableRow(page: Page, text: string, timeoutMs = 15_000): Promise<void> {
  await page
    .locator(`tr.ant-table-row`, { hasText: text })
    .first()
    .waitFor({ state: 'visible', timeout: timeoutMs });
}

/**
 * Click a button inside an AntD table row that matches `rowText`, where the
 * button's visible text matches `buttonText`. Useful for the "操作" column
 * (查看 / 发布 / 归档 / 冻结 / 退款 / 支付).
 */
export async function clickRowAction(
  page: Page,
  rowText: string,
  buttonText: string,
): Promise<void> {
  const row = page.locator('tr.ant-table-row', { hasText: rowText }).first();
  await row.waitFor({ state: 'visible' });
  await row.getByRole('button', { name: buttonText }).first().click();
}
