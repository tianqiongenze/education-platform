import { test, expect } from '@playwright/test';
import {
  loginAsAdmin,
  goTo,
  ROUTES,
  uniqueName,
  createCourseViaApi,
  createAssignmentViaApi,
  publishAssignmentViaApi,
  submitHomeworkViaApi,
  waitForGradingTerminal,
  archiveCourseViaApi,
  waitForTableRow,
} from './helpers';

/**
 * Assignment + grading pipeline E2E.
 *
 * Setup (via API): create a course + a published assignment + a submission.
 *
 * Flow:
 * 1. Open 作业管理 → search by courseId → verify the assignment row appears
 *    with 已发布 status.
 * 2. Open 提交管理 → search by assignmentId → verify the submission row
 *    appears with 已提交 status.
 * 3. Open the grading pipeline page for the submission → click 启动评分 →
 *    verify the three canonical stages (auto_check, plagiarism_check,
 *    manual_review) appear in the timeline → poll the API until the pipeline
 *    reaches a terminal status.
 */
test.describe('Assignment & grading pipeline', () => {
  test.describe.configure({ mode: 'serial' });

  const title = uniqueName('E2E 作业');
  let courseId: number;
  let assignmentId: number;
  let submissionId: number;
  let pipelineId: number | undefined;

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext();
    const page = await ctx.newPage();
    // sp05 assignments reference a courseId; create a throwaway course in
    // sp03 so the ID is valid if any cross-service lookup happens.
    const course = await createCourseViaApi(page);
    courseId = course.courseId;

    const assignment = await createAssignmentViaApi(page, {
      title,
      courseId,
      teacherId: 2001,
      maxScore: 100,
    });
    assignmentId = assignment.id;
    await publishAssignmentViaApi(page, assignmentId);

    const submission = await submitHomeworkViaApi(page, assignmentId, 1001, 'E2E submission');
    submissionId = submission.id;
    await ctx.close();
  });

  test.afterAll(async ({ browser }) => {
    const ctx = await browser.newContext();
    const page = await ctx.newPage();
    await archiveCourseViaApi(page, courseId);
    await ctx.close();
  });

  test.beforeEach(async ({ page }) => {
    await loginAsAdmin(page);
  });

  test('assignment is visible and published', async ({ page }) => {
    await goTo(page, ROUTES.assignments);
    await expect(page.getByText('作业管理').first()).toBeVisible({ timeout: 15_000 });

    await page.getByPlaceholder('按课程 ID 搜索').fill(String(courseId));
    const searchResp = page.waitForResponse(
      (r) => r.url().includes('/api/v1/assignments') && r.request().method() === 'GET',
      { timeout: 15_000 },
    );
    await page.getByRole('button', { name: '搜索' }).click();
    await searchResp;

    await waitForTableRow(page, title);
    const row = page.locator('tr.ant-table-row', { hasText: title }).first();
    await expect(row).toContainText('已发布');
  });

  test('submission is visible for the assignment', async ({ page }) => {
    await goTo(page, ROUTES.submissions);
    await expect(page.getByText('提交管理').first()).toBeVisible({ timeout: 15_000 });

    await page.getByPlaceholder('按作业 ID 筛选').fill(String(assignmentId));
    const searchResp = page.waitForResponse(
      (r) => r.url().includes('/api/v1/submissions') && r.request().method() === 'GET',
      { timeout: 15_000 },
    );
    await page.getByRole('button', { name: '搜索' }).click();
    await searchResp;

    await waitForTableRow(page, String(submissionId));
    const row = page.locator('tr.ant-table-row', { hasText: String(submissionId) }).first();
    // Initial status is SUBMITTED (已提交).
    await expect(row).toContainText('已提交');
  });

  test('start grading pipeline and verify stages', async ({ page }) => {
    await goTo(page, ROUTES.grading(submissionId));
    // The grading page shows a "评分流水线" card.
    await expect(page.getByText('评分流水线').first()).toBeVisible({ timeout: 15_000 });

    // If no pipeline exists yet, a "启动评分" button is shown in the card extra.
    const startBtn = page.getByRole('button', { name: '启动评分' });
    await expect(startBtn).toBeVisible({ timeout: 10_000 });

    const startResp = page.waitForResponse(
      (r) => r.url().includes('/api/v1/grading/start') && r.request().method() === 'POST',
      { timeout: 15_000 },
    );
    // Popconfirm wraps the start action.
    await startBtn.click();
    const popconfirm = page.locator('.ant-popconfirm').last();
    await expect(popconfirm).toBeVisible();
    await popconfirm.getByRole('button', { name: '启动' }).click();
    const resp = await startResp;
    expect(resp.ok()).toBeTruthy();
    const pipeline = await resp.json();
    pipelineId = pipeline.id;

    // The three canonical stages are always rendered in the timeline,
    // regardless of backend progress. Verify all three labels appear.
    await expect(page.getByText('自动检查').first()).toBeVisible();
    await expect(page.getByText('查重检测').first()).toBeVisible();
    await expect(page.getByText('人工复审').first()).toBeVisible();
  });

  test('grading pipeline reaches a terminal status', async ({ request }) => {
    test.skip(!pipelineId, 'pipeline was not started');
    // Poll the API directly rather than waiting for the 3s UI refetch, so the
    // test is deterministic and fast.
    const pipeline = await waitForGradingTerminal(request, pipelineId as number, 60_000);
    // We accept COMPLETED, FAILED, or CANCELLED — the contract here is that
    // the pipeline terminates. COMPLETED is the happy path.
    expect(['COMPLETED', 'FAILED', 'CANCELLED']).toContain(pipeline.status);
  });
});
