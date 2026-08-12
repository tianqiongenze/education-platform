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
  startGradingViaApi,
  waitForGradingTerminal,
  archiveCourseViaApi,
  waitForTableRow,
} from './helpers';

/**
 * Assignment grading E2E — covers the manual-grade path that complements
 * `assignment-grading.spec.ts` (which covers the auto pipeline start). Here we
 * focus on the GradeSubmissionPage (`/it-backend/grading/:id/grade`): teacher
 * enters a score + comment and the submission transitions to GRADED.
 *
 * Setup (via API): create a course + published assignment + a submission, and
 * start the auto pipeline so it reaches a terminal state before manual review.
 */
test.describe('Assignment grading (manual review)', () => {
  test.describe.configure({ mode: 'serial' });

  const title = uniqueName('E2E 评分');
  let courseId: number;
  let assignmentId: number;
  let submissionId: number;

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext();
    const page = await ctx.newPage();
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

    const submission = await submitHomeworkViaApi(page, assignmentId, 1001, 'My homework');
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

  test('submission is listed under 已提交', async ({ page }) => {
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
    await expect(row).toContainText('已提交');
  });

  test('auto pipeline terminates before manual review', async ({ request }) => {
    // Start the pipeline and wait for a terminal status so manual grading
    // operates on a settled submission.
    const pipeline = await startGradingViaApi(request, submissionId);
    const terminal = await waitForGradingTerminal(request, pipeline.id, 60_000);
    expect(['COMPLETED', 'FAILED', 'CANCELLED']).toContain(terminal.status);
  });

  test('teacher can submit a manual grade via the grade page', async ({ page }) => {
    await goTo(page, ROUTES.gradingGrade(submissionId));
    await expect(page.getByText('评分').first()).toBeVisible({ timeout: 15_000 });

    // The grade form has a score input and a comment textarea.
    const scoreInput = page.locator('input[type="number"]').first();
    await expect(scoreInput).toBeVisible();
    await scoreInput.fill('88');

    const commentInput = page.getByPlaceholder(/评语|评语|comment/i).first();
    if (await commentInput.isVisible({ timeout: 3_000 }).catch(() => false)) {
      await commentInput.fill('Good work — minor issues in section 2.');
    }

    const gradeResp = page.waitForResponse(
      (r) => r.url().includes('/grade') && r.request().method() === 'POST',
      { timeout: 15_000 },
    );
    await page.getByRole('button', { name: /提交|保存|确认/ }).first().click();
    const resp = await gradeResp;
    expect(resp.ok()).toBeTruthy();

    // After grading, the submissions list shows 已评分 for this row.
    await goTo(page, ROUTES.submissions);
    await page.getByPlaceholder('按作业 ID 筛选').fill(String(assignmentId));
    await page.getByRole('button', { name: '搜索' }).click();
    await waitForTableRow(page, String(submissionId));
    const row = page.locator('tr.ant-table-row', { hasText: String(submissionId) }).first();
    await expect(row).toContainText(/已评分|GRADED/);
  });

  test('graded submission cannot be re-graded with an out-of-range score', async ({ request }) => {
    // Sanity: the maxScore was 100; submitting 150 must be rejected.
    const res = await request.post(`/api/v1/submissions/${submissionId}/grade`, {
      data: { score: 150, comment: 'over limit' },
    });
    expect(res.ok()).toBeFalsy();
  });
});
