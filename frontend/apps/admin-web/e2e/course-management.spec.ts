import { test, expect } from '@playwright/test';
import {
  loginAsAdmin,
  goTo,
  ROUTES,
  uniqueName,
  archiveCourseViaApi,
  waitForTableRow,
} from './helpers';

/**
 * Course management E2E.
 *
 * Flow: create course via UI form → verify row appears in list → publish via
 * Popconfirm → verify status changes to 已发布 → search by keyword → archive
 * via Popconfirm → verify status changes to 已归档.
 */
test.describe('Course management', () => {
  test.describe.configure({ mode: 'serial' });

  const title = uniqueName('E2E 课程');
  let courseId: number | undefined;

  test.beforeEach(async ({ page }) => {
    await loginAsAdmin(page);
  });

  test('create a course via the form', async ({ page }) => {
    await goTo(page, ROUTES.courses);
    await expect(page.getByText('课程管理').first()).toBeVisible({ timeout: 15_000 });

    await page.getByRole('button', { name: '创建课程' }).click();
    await page.waitForURL(`**${ROUTES.courseCreate}`);

    await page.getByLabel('课程标题').fill(title);
    await page.getByLabel('课程描述').fill('E2E 自动化创建的课程');
    await page.getByLabel('教师 ID').fill('2001');
    await page.getByLabel('价格（元）').fill('99.00');

    const respPromise = page.waitForResponse(
      (r) => r.url().includes('/api/v1/courses') && r.request().method() === 'POST',
      { timeout: 15_000 },
    );
    await page.getByRole('button', { name: '创建课程' }).click();
    const resp = await respPromise;
    expect(resp.ok()).toBeTruthy();
    courseId = (await resp.json()).courseId;

    await page.waitForURL(`**${ROUTES.courses}`);
    await waitForTableRow(page, title);
    // New course is in DRAFT (草稿).
    await expect(page.locator('tr.ant-table-row', { hasText: title }).first()).toContainText(
      '草稿',
    );
  });

  test('publish the course and verify status', async ({ page }) => {
    test.skip(!courseId, 'create test did not produce a courseId');
    await goTo(page, ROUTES.courses);
    await waitForTableRow(page, title);

    const row = page.locator('tr.ant-table-row', { hasText: title }).first();
    await row.getByRole('button', { name: '发布' }).click();

    // AntD Popconfirm — confirm.
    const popconfirm = page.locator('.ant-popconfirm').last();
    await expect(popconfirm).toBeVisible();
    const publishResp = page.waitForResponse(
      (r) => r.url().includes('/publish') && r.request().method() === 'POST',
      { timeout: 15_000 },
    );
    await popconfirm.getByRole('button', { name: '发布' }).click();
    const resp = await publishResp;
    expect(resp.ok()).toBeTruthy();

    await waitForTableRow(page, title);
    await expect(page.locator('tr.ant-table-row', { hasText: title }).first()).toContainText(
      '已发布',
    );
  });

  test('search by keyword returns the course', async ({ page }) => {
    test.skip(!courseId, 'create test did not produce a courseId');
    await goTo(page, ROUTES.courses);

    // Use a slice of the title as the keyword to exercise the search endpoint.
    const keyword = title.slice(-12);
    const searchInput = page.getByPlaceholder('按关键词搜索课程');
    await searchInput.fill(keyword);

    const searchResp = page.waitForResponse(
      (r) => r.url().includes('/api/v1/courses/search') && r.request().method() === 'GET',
      { timeout: 15_000 },
    );
    await page.getByRole('button', { name: '搜索' }).click();
    const resp = await searchResp;
    expect(resp.ok()).toBeTruthy();

    await waitForTableRow(page, title);
    await expect(page.locator('tr.ant-table-row', { hasText: title }).first()).toContainText(
      '已发布',
    );
  });

  test('archive the course and verify status', async ({ page }) => {
    test.skip(!courseId, 'create test did not produce a courseId');
    await goTo(page, ROUTES.courses);
    await waitForTableRow(page, title);

    const row = page.locator('tr.ant-table-row', { hasText: title }).first();
    await row.getByRole('button', { name: '归档' }).click();

    const popconfirm = page.locator('.ant-popconfirm').last();
    await expect(popconfirm).toBeVisible();
    const archiveResp = page.waitForResponse(
      (r) => r.url().includes('/archive') && r.request().method() === 'POST',
      { timeout: 15_000 },
    );
    await popconfirm.getByRole('button', { name: '归档' }).click();
    const resp = await archiveResp;
    expect(resp.ok()).toBeTruthy();

    await waitForTableRow(page, title);
    await expect(page.locator('tr.ant-table-row', { hasText: title }).first()).toContainText(
      '已归档',
    );
  });

  test.afterAll(async ({ browser }) => {
    if (courseId === undefined) return;
    const ctx = await browser.newContext();
    const page = await ctx.newPage();
    await archiveCourseViaApi(page, courseId);
    await ctx.close();
  });
});
