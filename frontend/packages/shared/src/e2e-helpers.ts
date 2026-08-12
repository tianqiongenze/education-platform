/**
 * Shared E2E helpers for the education-platform Playwright suites.
 *
 * These helpers are app-agnostic: they cover API calls (login, createOrder,
 * createCourse, etc.), test-data generators, and common UI selectors used
 * across admin-web, portal-web, and student-h5. App-specific helpers (e.g.
 * admin-web's `loginAsAdmin` localStorage seed) continue to live in each
 * app's own `e2e/helpers.ts`, which re-uses these building blocks.
 *
 * To keep the shared package free of a hard dev-dependency on
 * `@playwright/test`, we declare minimal structural types for `Page` and
 * `APIRequestContext` that match the Playwright surface these helpers use.
 * Each consuming app has `@playwright/test` installed and passes the real
 * objects, so the structural types are assignment-compatible at runtime.
 */

// ---------------------------------------------------------------------------
// Minimal structural types — compatible with @playwright/test's Page and
// APIRequestContext. Declared locally so the shared package type-checks
// without `@playwright/test` installed.
// ---------------------------------------------------------------------------

export interface E2EApiResponse {
  ok(): boolean;
  status(): number;
  text(): Promise<string>;
  json(): Promise<unknown>;
}

export interface E2EAPIRequestContext {
  request: {
    post(url: string, options?: { data?: unknown }): Promise<E2EApiResponse>;
    get(url: string): Promise<E2EApiResponse>;
  };
}

export interface E2EPageRouteOptions {
  status?: number;
  contentType?: string;
  body?: string;
}

export interface E2ERoute {
  request(): {
    method(): string;
    url(): string;
    postDataJSON(): unknown;
  };
  fulfill(options: E2EPageRouteOptions): Promise<void>;
  continue(): Promise<void>;
}

export interface E2EPage {
  request: E2EAPIRequestContext['request'];
  goto(url: string): Promise<unknown>;
  waitForLoadState(state?: string): Promise<unknown>;
  waitForResponse(
    predicate: (r: { url(): string; request(): { method(): string } }) => boolean,
    options?: { timeout?: number },
  ): Promise<E2EApiResponse>;
  addInitScript(script: string | ((...args: any[]) => void), ...args: any[]): Promise<void>;
  route(url: string | RegExp, handler: (route: E2ERoute) => Promise<void>): Promise<void>;
  reload(): Promise<unknown>;
  evaluate<T>(fn: () => T): Promise<T>;
  // The real Playwright Locator type is rich; we type it loosely here so the
  // shared package compiles without @playwright/test. Consuming apps get the
  // full Locator type from their own @playwright/test install when they pass
  // a real Page into these helpers.
  locator(selector: string, options?: { hasText?: string }): any;
  getByText(text: string): any;
  getByPlaceholder(text: string | RegExp): any;
  getByRole(role: string, options?: { name?: string | RegExp }): any;
  getByLabel(text: string | RegExp): any;
}

/** Accept either a real Playwright Page or APIRequestContext. */
type ApiOrPage = E2EAPIRequestContext | E2EPage;

// ---------------------------------------------------------------------------
// API path constants — mirror the Vite proxy config of each app.
// admin-web proxies /api/v1/users|courses|orders -> 8083 (sp03-business)
// and /api/v1/assignments|submissions|grading  -> 8085 (sp05-itbackend).
// portal-web proxies /api -> 8090 (bff-java).
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

  // Auth (bff-java) — used by portal-web.
  LOGIN: '/api/v1/auth/login',
  REGISTER: '/api/v1/auth/register',
  REFRESH: '/api/v1/auth/refresh',
} as const;

// ---------------------------------------------------------------------------
// Common UI selectors — AntD-based apps share these class names.
// ---------------------------------------------------------------------------

export const SELECTORS = {
  tableRow: 'tr.ant-table-row',
  modal: '.ant-modal',
  modalConfirm: '.ant-modal-confirm',
  popconfirm: '.ant-popconfirm',
  message: '.ant-message-notice',
  formItem: '.ant-form-item',
  errorText: '.ant-typography-danger',
  card: '.ant-card',
  empty: '.ant-empty',
  pagination: '.ant-pagination',
  // portal-web / student-h5 hero section test id.
  heroSection: '[data-testid="hero-section"]',
  heroCoursesBtn: '[data-testid="hero-courses-btn"]',
  heroLoginBtn: '[data-testid="hero-login-btn"]',
  courseCard: '.portal-course-card',
} as const;

// ---------------------------------------------------------------------------
// LocalStorage keys — mirror @ep/shared STORAGE_KEYS.
// ---------------------------------------------------------------------------

export const STORAGE_KEYS = {
  ACCESS_TOKEN: 'ep_access_token',
  REFRESH_TOKEN: 'ep_refresh_token',
  USER_PROFILE: 'ep_user_profile',
} as const;

// ---------------------------------------------------------------------------
// Test-data generators — unique per run so parallel / repeated runs don't
// collide on backend uniqueness constraints (phone, username, email).
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

/** Returns a unique idempotency key for order/submission/grading calls. */
export function uniqueIdempotencyKey(prefix = 'e2e'): string {
  return `${prefix}-${Date.now()}-${Math.floor(Math.random() * 10000)}`;
}

// ---------------------------------------------------------------------------
// Auth helpers
// ---------------------------------------------------------------------------

/**
 * Seed localStorage with a fake authenticated session so an AuthGuard accepts
 * the route without going through the real BFF login flow. The admin-web /
 * student-h5 AuthGuard only checks `user` from UserContext (set from
 * localStorage on mount). API calls go through the Vite proxy directly and do
 * not require a valid BFF token in dev/E2E.
 */
export async function seedAuth(
  page: E2EPage,
  profile: Record<string, unknown> = {
    userId: 1,
    username: 'e2e-admin',
    nickname: 'E2E Admin',
    role: 'super_admin',
    avatar: null,
  },
  token = 'e2e-fake-token',
): Promise<void> {
  await page.addInitScript(
    ([p, t]) => {
      try {
        window.localStorage.setItem('ep_access_token', t);
        window.localStorage.setItem('ep_refresh_token', `${t}-refresh`);
        window.localStorage.setItem('ep_user_profile', p);
      } catch {
        /* ignore */
      }
    },
    [JSON.stringify(profile), token] as const,
  );
}

/**
 * Perform a real login against the BFF login endpoint and return the parsed
 * response. Used by portal-web auth specs when the BFF is available. Falls
 * back to throwing on failure so callers can switch to mock mode.
 */
export async function loginViaApi(
  req: ApiOrPage,
  username: string,
  password: string,
): Promise<{ accessToken: string; refreshToken?: string; user?: unknown }> {
  const res = await req.request.post(API.LOGIN, {
    data: { username, password },
  });
  if (!res.ok()) {
    throw new Error(`loginViaApi failed: ${res.status()} ${await res.text()}`);
  }
  return (await res.json()) as { accessToken: string; refreshToken?: string; user?: unknown };
}

// ---------------------------------------------------------------------------
// CRUD API helpers — thin wrappers over page.request so tests can perform
// setup/teardown fast without clicking through the UI.
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

/** Create a user via the sp03 API. Returns the created user object. */
export async function createUserViaApi(
  req: ApiOrPage,
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

/** Best-effort freeze of a user (no DELETE on the backend). Never throws. */
export async function cleanupUser(
  req: ApiOrPage,
  userId: number,
): Promise<void> {
  try {
    await req.request.post(API.freezeUser(userId), { data: { reason: 'e2e-cleanup' } });
  } catch {
    /* best-effort */
  }
}

/** Create a course via the sp03 API. Returns the created course. */
export async function createCourseViaApi(
  req: ApiOrPage,
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

/** Publish a course via the sp03 API. */
export async function publishCourseViaApi(
  req: ApiOrPage,
  courseId: number,
): Promise<void> {
  const res = await req.request.post(API.publishCourse(courseId));
  if (!res.ok()) {
    throw new Error(`publishCourseViaApi failed: ${res.status()} ${await res.text()}`);
  }
}

/** Archive a course via the sp03 API. Best-effort. */
export async function archiveCourseViaApi(
  req: ApiOrPage,
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
  req: ApiOrPage,
  userId: number,
  courseId: number,
  overrides: Partial<{ amount: string | null; idempotencyKey: string }> = {},
): Promise<OrderPayload> {
  const body = {
    userId,
    courseId,
    amount: overrides.amount ?? null,
    idempotencyKey: overrides.idempotencyKey ?? uniqueIdempotencyKey('order'),
  };
  const res = await req.request.post(API.ORDERS, { data: body });
  if (!res.ok()) {
    throw new Error(`createOrderViaApi failed: ${res.status()} ${await res.text()}`);
  }
  return (await res.json()) as OrderPayload;
}

/** Pay an order via the sp03 API. */
export async function payOrderViaApi(
  req: ApiOrPage,
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
  req: ApiOrPage,
  orderId: number,
  reason = 'e2e-refund',
): Promise<void> {
  try {
    await req.request.post(API.refundOrder(orderId), { data: { reason } });
  } catch {
    /* best-effort */
  }
}

/** Create an assignment via the sp05 API. Returns the created assignment. */
export async function createAssignmentViaApi(
  req: ApiOrPage,
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
  req: ApiOrPage,
  assignmentId: number,
): Promise<void> {
  const res = await req.request.post(API.publishAssignment(assignmentId));
  if (!res.ok()) {
    throw new Error(`publishAssignmentViaApi failed: ${res.status()} ${await res.text()}`);
  }
}

/** Submit homework via the sp05 API. Returns the created submission. */
export async function submitHomeworkViaApi(
  req: ApiOrPage,
  assignmentId: number,
  studentId: number,
  content = 'E2E submission content',
): Promise<SubmissionPayload> {
  const body = {
    assignmentId,
    studentId,
    content,
    idempotencyKey: uniqueIdempotencyKey('sub'),
  };
  const res = await req.request.post(API.SUBMISSIONS, { data: body });
  if (!res.ok()) {
    throw new Error(`submitHomeworkViaApi failed: ${res.status()} ${await res.text()}`);
  }
  return (await res.json()) as SubmissionPayload;
}

/** Start the 3-stage grading pipeline via the sp05 API. Returns the pipeline. */
export async function startGradingViaApi(
  req: ApiOrPage,
  submissionId: number,
  gradedBy = 2001,
): Promise<{ id: number; submissionId: number; status: string; stages: unknown[] }> {
  const body = {
    submissionId,
    gradedBy,
    idempotencyKey: uniqueIdempotencyKey(`grade-${submissionId}`),
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
  req: ApiOrPage,
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
 * Navigate to a route and wait for the page to settle. Mirrors admin-web's
 * `goTo` helper.
 */
export async function goTo(page: E2EPage, path: string): Promise<void> {
  await page.goto(path);
  await page.waitForLoadState('networkidle');
}

/** Wait for an AntD table row containing the given text to appear. */
export async function waitForTableRow(
  page: E2EPage,
  text: string,
  timeoutMs = 15_000,
): Promise<void> {
  await page
    .locator(SELECTORS.tableRow, { hasText: text })
    .first()
    .waitFor({ state: 'visible', timeout: timeoutMs });
}

/** Click a button inside an AntD table row matching `rowText`. */
export async function clickRowAction(
  page: E2EPage,
  rowText: string,
  buttonText: string,
): Promise<void> {
  const row = page.locator(SELECTORS.tableRow, { hasText: rowText }).first();
  await row.waitFor({ state: 'visible' });
  await row.getByRole('button', { name: buttonText }).first().click();
}

// ---------------------------------------------------------------------------
// Mock response helpers — for specs that must run without a live backend.
// ---------------------------------------------------------------------------

/** Build a mock course list response (portal-web / student-h5 shape). */
export function mockCourseList(count = 4) {
  const items = Array.from({ length: count }, (_, i) => ({
    id: `mock-${i + 1}`,
    title: `Mock Course ${i + 1}`,
    description: `Description for mock course ${i + 1}`,
    coverImage: `https://placehold.co/600x400/1677ff/ffffff?text=Course${i + 1}`,
    status: 'published',
    teacherId: `t-${i + 1}`,
    teacherName: `Teacher ${i + 1}`,
    price: i * 50,
    enrollmentCount: 100 * (i + 1),
    rating: 4.5 + i * 0.1,
    tags: ['Mock', `Tag${i + 1}`],
    chapters: [],
    createdAt: '2025-01-01T00:00:00Z',
    updatedAt: '2025-06-01T00:00:00Z',
  }));
  return { items, total: count, page: 1, pageSize: count };
}

/** Build a mock login response (bff-java shape). */
export function mockLoginResponse(username = 'e2e-user') {
  return {
    accessToken: 'mock-access-token',
    refreshToken: 'mock-refresh-token',
    user: {
      userId: 1,
      username,
      nickname: 'E2E User',
      role: 'student',
      avatar: null,
    },
  };
}
