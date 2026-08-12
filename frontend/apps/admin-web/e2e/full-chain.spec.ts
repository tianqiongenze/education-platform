import { test, expect, type Page } from '@playwright/test';
import {
  loginAsAdmin,
  goTo,
  ROUTES,
  uniqueName,
  uniquePhone,
  uniqueEmail,
  createCourseViaApi,
  publishCourseViaApi,
  createOrderViaApi,
  payOrderViaApi,
  createAssignmentViaApi,
  publishAssignmentViaApi,
  submitHomeworkViaApi,
  startGradingViaApi,
  waitForGradingTerminal,
  cleanupUser,
  archiveCourseViaApi,
  refundOrderViaApi,
  waitForTableRow,
} from './helpers';

/**
 * Marquee full-chain E2E — exercises the entire platform business flow in one
 * sequential story:
 *
 *   register user (UI)
 *     → create course (API) → publish (API)
 *     → create order (API) → pay (API)
 *     → create assignment (API) → publish (API)
 *     → submit homework (API)
 *     → start grading pipeline (API) → poll to terminal
 *     → verify each entity is visible in its respective admin list (UI)
 *
 * The "register user" step uses the UI to prove the create-user form works
 * end-to-end. Subsequent steps use API helpers for setup speed, with UI
 * assertions at each list page to prove the data flows through to the admin
 * views. The grading step is verified via API polling because the 3-stage
 * pipeline is asynchronous.
 */
test.describe('Full chain: user → course → order → assignment → grading', () => {
  test.describe.configure({ mode: 'serial' });

  const username = uniqueName('chain-user');
  const phone = uniquePhone();
  const email = uniqueEmail();
  const password = 'Passw0rd!';

  let userId: number;
  let courseId: number;
  let orderId: number;
  let assignmentId: number;
  let submissionId: number;
  let pipelineId: number;

  test.beforeEach(async ({ page }) => {
    await loginAsAdmin(page);
  });

  test('1. register a user via the UI form', async ({ page }) => {
    await goTo(page, ROUTES.userCreate);
    await expect(page.getByText('创建用户').first()).toBeVisible({ timeout: 15_000 });

    await page.getByLabel('用户名').fill(username);
    await page.getByLabel('昵称').fill(username);
    await page.getByLabel('手机号').fill(phone);
    await page.getByLabel('邮箱').fill(email);
    await page.getByLabel('密码').first().fill(password);

    const respPromise = page.waitForResponse(
      (r) => r.url().includes('/api/v1/users') && r.request().method() === 'POST',
      { timeout: 15_000 },
    );
    await page.getByRole('button', { name: '创建用户' }).click();
    const resp = await respPromise;
    expect(resp.ok()).toBeTruthy();
    userId = (await resp.json()).userId;

    await page.waitForURL(`**${ROUTES.users}`);
    await waitForTableRow(page, phone);
    await expect(page.locator('tr.ant-table-row', { hasText: phone }).first()).toContainText(
      '正常',
    );
  });

  test('2. create + publish a course via API', async ({ request }) => {
    test.skip(!userId, 'user registration failed');
    const course = await createCourseViaApi(request, {
      title: uniqueName('Chain 课程'),
      teacherId: 2001,
      price: '199.00',
    });
    courseId = course.courseId;
    await publishCourseViaApi(request, courseId);

    // Verify in the course list UI.
    const page = test.info().page as Page;
    await goTo(page, ROUTES.courses);
    await waitForTableRow(page, String(courseId));
    await expect(
      page.locator('tr.ant-table-row', { hasText: String(courseId) }).first(),
    ).toContainText('已发布');
  });

  test('3. create + pay an order via API', async ({ request, page }) => {
    test.skip(!userId || !courseId, 'prerequisites missing');
    const order = await createOrderViaApi(request, userId, courseId, { amount: '199.00' });
    orderId = order.orderId;
    await payOrderViaApi(request, orderId, 'ALIPAY');

    await goTo(page, ROUTES.orders);
    await page.getByPlaceholder('按用户 ID 筛选').fill(String(userId));
    await page.getByRole('button', { name: '搜索' }).click();
    await waitForTableRow(page, String(orderId));
    await expect(
      page.locator('tr.ant-table-row', { hasText: String(orderId) }).first(),
    ).toContainText('已支付');
  });

  test('4. create + publish an assignment via API', async ({ request, page }) => {
    test.skip(!courseId, 'prerequisites missing');
    const assignment = await createAssignmentViaApi(request, {
      title: uniqueName('Chain 作业'),
      courseId,
      teacherId: 2001,
      maxScore: 100,
    });
    assignmentId = assignment.id;
    await publishAssignmentViaApi(request, assignmentId);

    await goTo(page, ROUTES.assignments);
    await page.getByPlaceholder('按课程 ID 搜索').fill(String(courseId));
    await page.getByRole('button', { name: '搜索' }).click();
    await waitForTableRow(page, String(assignmentId));
    await expect(
      page.locator('tr.ant-table-row', { hasText: String(assignmentId) }).first(),
    ).toContainText('已发布');
  });

  test('5. submit homework via API', async ({ request, page }) => {
    test.skip(!assignmentId || !userId, 'prerequisites missing');
    const submission = await submitHomeworkViaApi(
      request,
      assignmentId,
      userId,
      'Full-chain E2E submission',
    );
    submissionId = submission.id;

    await goTo(page, ROUTES.submissions);
    await page.getByPlaceholder('按作业 ID 筛选').fill(String(assignmentId));
    await page.getByRole('button', { name: '搜索' }).click();
    await waitForTableRow(page, String(submissionId));
    await expect(
      page.locator('tr.ant-table-row', { hasText: String(submissionId) }).first(),
    ).toContainText('已提交');
  });

  test('6. start grading pipeline and wait for terminal status', async ({ request, page }) => {
    test.skip(!submissionId, 'prerequisites missing');
    const pipeline = await startGradingViaApi(request, submissionId, 2001);
    pipelineId = pipeline.id;

    // Open the grading page in the UI and verify the three stages render.
    await goTo(page, ROUTES.grading(submissionId));
    await expect(page.getByText('评分流水线').first()).toBeVisible({ timeout: 15_000 });
    await expect(page.getByText('自动检查').first()).toBeVisible();
    await expect(page.getByText('查重检测').first()).toBeVisible();
    await expect(page.getByText('人工复审').first()).toBeVisible();

    // Poll the API until the pipeline terminates.
    const terminal = await waitForGradingTerminal(request, pipelineId, 60_000);
    expect(['COMPLETED', 'FAILED', 'CANCELLED']).toContain(terminal.status);
  });

  test.afterAll(async ({ browser }) => {
    const ctx = await browser.newContext();
    const page = await ctx.newPage();
    if (pipelineId) {
      await waitForGradingTerminal(page, pipelineId, 30_000).catch(() => {});
    }
    if (orderId) {
      await refundOrderViaApi(page, orderId).catch(() => {});
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
