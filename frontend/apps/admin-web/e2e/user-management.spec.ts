import { test, expect } from '@playwright/test';
import {
  loginAsAdmin,
  goTo,
  ROUTES,
  uniqueName,
  uniquePhone,
  uniqueEmail,
  cleanupUser,
  waitForTableRow,
} from './helpers';

/**
 * User management E2E.
 *
 * Flow: open 用户管理 → create user via UI form → verify row appears in
 * list → freeze user via Modal → verify status tag changes to 已冻结.
 *
 * The sp03 backend has no DELETE endpoint, so teardown is a best-effort
 * freeze to keep the user from interfering with later runs.
 */
test.describe('User management', () => {
  test.describe.configure({ mode: 'serial' });

  const username = uniqueName('e2euser');
  const phone = uniquePhone();
  const email = uniqueEmail();
  let userId: number | undefined;

  test.beforeEach(async ({ page }) => {
    await loginAsAdmin(page);
  });

  test('create a user via the form', async ({ page }) => {
    // Start from the user list so we can verify the row appears after creation.
    await goTo(page, ROUTES.users);
    await expect(page.getByText('用户管理').first()).toBeVisible({ timeout: 15_000 });

    // Click "创建用户" to navigate to the create page.
    await page.getByRole('button', { name: '创建用户' }).click();
    await page.waitForURL(`**${ROUTES.userCreate}`);

    // Fill the AntD form. Fields are labeled in Chinese.
    await page.getByLabel('用户名').fill(username);
    await page.getByLabel('昵称').fill(username);
    await page.getByLabel('手机号').fill(phone);
    await page.getByLabel('邮箱').fill(email);
    await page.getByLabel('密码').first().fill('Passw0rd!');

    // Submit and wait for navigation back to the list.
    const respPromise = page.waitForResponse(
      (r) => r.url().includes('/api/v1/users') && r.request().method() === 'POST',
      { timeout: 15_000 },
    );
    await page.getByRole('button', { name: '创建用户' }).click();
    const resp = await respPromise;
    expect(resp.ok()).toBeTruthy();
    userId = (await resp.json()).userId;

    // Should navigate back to the list and the new user should appear.
    await page.waitForURL(`**${ROUTES.users}`);
    await waitForTableRow(page, phone);
    await expect(page.locator('tr.ant-table-row', { hasText: phone })).toContainText(username);
  });

  test('created user is visible in the list', async ({ page }) => {
    await goTo(page, ROUTES.users);
    await waitForTableRow(page, phone);
    const row = page.locator('tr.ant-table-row', { hasText: phone }).first();
    await expect(row).toContainText(username);
    // Status for a fresh user should be "正常" (ACTIVE = 1).
    await expect(row).toContainText('正常');
  });

  test('freeze the user and verify status changes', async ({ page }) => {
    test.skip(!userId, 'previous create test did not produce a userId');
    await goTo(page, ROUTES.users);
    await waitForTableRow(page, phone);

    const row = page.locator('tr.ant-table-row', { hasText: phone }).first();
    // Open the freeze Modal.
    await row.getByRole('button', { name: '冻结' }).click();

    // The freeze Modal has a TextArea for the reason and a "确认冻结" button.
    const modal = page.locator('.ant-modal').last();
    await expect(modal).toBeVisible();
    await modal.getByPlaceholder('请输入冻结原因').fill('E2E freeze reason');

    const freezeResp = page.waitForResponse(
      (r) => r.url().includes('/freeze') && r.request().method() === 'POST',
      { timeout: 15_000 },
    );
    await modal.getByRole('button', { name: '确认冻结' }).click();
    const resp = await freezeResp;
    expect(resp.ok()).toBeTruthy();

    // The list refetches; the row should now show 已冻结.
    await waitForTableRow(page, phone);
    await expect(page.locator('tr.ant-table-row', { hasText: phone }).first()).toContainText(
      '已冻结',
    );
  });

  test.afterAll(async ({ browser }) => {
    if (userId === undefined) return;
    const ctx = await browser.newContext();
    const page = await ctx.newPage();
    await cleanupUser(page, userId);
    await ctx.close();
  });
});
