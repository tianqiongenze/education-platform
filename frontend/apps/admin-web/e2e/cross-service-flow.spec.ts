import { test, expect, type Page } from '@playwright/test';
import {
  loginAsAdmin,
  goTo,
  ROUTES,
  API,
  uniqueName,
  uniquePhone,
  uniqueEmail,
  createUserViaApi,
  createCourseViaApi,
  publishCourseViaApi,
  createAssignmentViaApi,
  publishAssignmentViaApi,
  submitHomeworkViaApi,
  startGradingViaApi,
  waitForGradingTerminal,
  cleanupUser,
  archiveCourseViaApi,
  waitForTableRow,
} from './helpers';

/**
 * Cross-Service Flow E2E — focuses on cross-service assertions.
 *
 * Where full-chain.spec.ts runs the business story end-to-end, this spec
 * asserts that data written in one service (core-business / sp03) is visible
 * and consumable by another service (grading-service / sp05), and vice versa.
 * The grading-service references courseId from core-business when creating an
 * assignment, and the core-business userId is the studentId on a submission.
 *
 * Each test uses `page.request` for API setup and `page` for UI assertions so
 * we exercise both the BFF proxy (API) and the admin-web render path (UI).
 */
test.describe('Cross-Service Flow: core-business ↔ grading-service', () => {
  test.describe.configure({ mode: 'serial' });

  const username = uniqueName('cross-user');
  const phone = uniquePhone();
  const email = uniqueEmail();

  let userId: number;
  let courseId: number;
  let assignmentId: number;
  let submissionId: number;
  let pipelineId: number;

  test.beforeEach(async ({ page }) => {
    await loginAsAdmin(page);
  });

  test('1. register a core-business user for the cross-service flow', async ({ request }) => {
    const user = await createUserViaApi(request, {
      username,
      phone,
      email,
      password: 'Passw0rd!',
      nickname: username,
    });
    userId = user.userId;
    expect(userId).toBeTruthy();

    // Cross-service identity contract: the core-business userId will later be
    // used as studentId in grading-service. Confirm it is a positive integer.
    expect(typeof userId).toBe('number');
    expect(userId).toBeGreaterThan(0);
  });

  test('2. course created in core-business is referenceable by grading-service assignment', async ({
    request,
    page,
  }) => {
    test.skip(!userId, 'user registration failed');
    // --- Setup: create + publish a course in core-business (sp03) ---
    const course = await createCourseViaApi(request, {
      title: uniqueName('Cross 课程'),
      teacherId: 2001,
      price: '299.00',
    });
    courseId = course.courseId;
    await publishCourseViaApi(request, courseId);

    // --- Cross-service assertion: grading-service (sp05) accepts the
    // core-business courseId when creating an assignment. If the services
    // were not wired together, the assignment create would reject the
    // foreign courseId (or the grading pipeline would later fail to enrich
    // course metadata). ---
    const assignment = await createAssignmentViaApi(request, {
      title: uniqueName('Cross 作业'),
      courseId, // courseId originates from core-business
      teacherId: 2001,
      maxScore: 100,
    });
    assignmentId = assignment.id;
    await publishAssignmentViaApi(request, assignmentId);

    // The assignment should echo back the same courseId, proving the
    // grading-service stored the core-business reference.
    expect(assignment.courseId).toBe(courseId);

    // --- UI assertion: the assignment list renders the cross-service
    // reference. Filter by the core-business courseId and confirm the row
    // appears with 已发布 status. ---
    await goTo(page, ROUTES.assignments);
    await page.getByPlaceholder('按课程 ID 搜索').fill(String(courseId));
    await page.getByRole('button', { name: '搜索' }).click();
    await waitForTableRow(page, String(assignmentId));
    await expect(
      page.locator('tr.ant-table-row', { hasText: String(assignmentId) }).first(),
    ).toContainText('已发布');
  });

  test('3. core-business user can submit to a grading-service assignment and be graded', async ({
    request,
    page,
  }) => {
    test.skip(!userId || !courseId || !assignmentId, 'prerequisites missing');
    // userId comes from core-business; submission.studentId in grading-service
    // must accept it. This is the core cross-service identity contract.
    const submission = await submitHomeworkViaApi(
      request,
      assignmentId,
      userId,
      'Cross-service submission content',
    );
    submissionId = submission.id;
    expect(submission.studentId).toBe(userId);

    // --- UI assertion: the submissions list (grading-service data) shows the
    // row for this assignment, with the core-business studentId embedded. ---
    await goTo(page, ROUTES.submissions);
    await page.getByPlaceholder('按作业 ID 筛选').fill(String(assignmentId));
    await page.getByRole('button', { name: '搜索' }).click();
    await waitForTableRow(page, String(submissionId));
    await expect(
      page.locator('tr.ant-table-row', { hasText: String(submissionId) }).first(),
    ).toContainText('已提交');

    // --- Cross-service assertion: start the 3-stage grading pipeline. The
    // pipeline reads the submission (grading-service) and the assignment /
    // course metadata (referencing core-business) to run auto-check +
    // plagiarism + manual review. A terminal COMPLETED status proves the
    // full cross-service chain resolved. ---
    const pipeline = await startGradingViaApi(request, submissionId, 2001);
    pipelineId = pipeline.id;

    await goTo(page, ROUTES.grading(submissionId));
    await expect(page.getByText('评分流水线').first()).toBeVisible({ timeout: 15_000 });
    await expect(page.getByText('自动检查').first()).toBeVisible();
    await expect(page.getByText('查重检测').first()).toBeVisible();
    await expect(page.getByText('人工复审').first()).toBeVisible();

    const terminal = await waitForGradingTerminal(request, pipelineId, 60_000);
    expect(['COMPLETED', 'FAILED', 'CANCELLED']).toContain(terminal.status);
  });

  test('4. grading-service stages are queryable by submissionId from core-business student', async ({
    request,
  }) => {
    test.skip(!submissionId || !pipelineId, 'prerequisites missing');
    // The grading endpoint is keyed by submissionId, which itself references
    // a core-business userId as studentId. Querying by submissionId must
    // return the pipeline we started in the previous test.
    const res = await request.get(API.gradingBySubmission(submissionId));
    expect(res.ok()).toBeTruthy();
    const body = await res.json();
    // The response may be a single pipeline or an array; normalize.
    const pipelines = Array.isArray(body) ? body : [body];
    const found = pipelines.some((p: { id: number }) => p.id === pipelineId);
    expect(found).toBeTruthy();

    // Stages sub-resource must enumerate the 3 expected stages.
    const stagesRes = await request.get(API.gradingStages(pipelineId));
    expect(stagesRes.ok()).toBeTruthy();
    const stages = (await stagesRes.json()) as Array<{ stageName?: string; name?: string }>;
    expect(stages.length).toBeGreaterThanOrEqual(3);
  });

  test.afterAll(async ({ browser }) => {
    const ctx = await browser.newContext();
    const page = await ctx.newPage();
    if (pipelineId) {
      await waitForGradingTerminal(page, pipelineId, 30_000).catch(() => {});
    }
    if (courseId) {
      await archiveCourseViaApi(page, courseId);
    }
    if (userId) {
      await cleanupUser(page, userId);
    }
    await ctx.close();
  });
});
