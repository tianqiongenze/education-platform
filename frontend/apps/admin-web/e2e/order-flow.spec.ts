import { test, expect } from '@playwright/test';
import {
  loginAsAdmin,
  goTo,
  ROUTES,
  createUserViaApi,
  createCourseViaApi,
  createOrderViaApi,
  refundOrderViaApi,
  cleanupUser,
  archiveCourseViaApi,
  waitForTableRow,
} from './helpers';

/**
 * Order flow E2E.
 *
 * Setup (via API for speed): create a user + a published course. The order
 * list page filters by user ID, so we need a known user to search for the
 * order.
 *
 * Flow: create order via UI-free API → open 订单管理 → search by user ID →
 * verify order row appears with 待支付 status → pay via Modal → verify 已支付
 * → refund via Popconfirm → verify 已退款.
 */
test.describe('Order flow', () => {
  test.describe.configure({ mode: 'serial' });

  let userId: number;
  let courseId: number;
  let orderId: number;

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext();
    const page = await ctx.newPage();
    const user = await createUserViaApi(page);
    userId = user.userId;
    const course = await createCourseViaApi(page, { price: '99.00' });
    courseId = course.courseId;
    await ctx.close();
  });

  test.afterAll(async ({ browser }) => {
    const ctx = await browser.newContext();
    const page = await ctx.newPage();
    await refundOrderViaApi(page, orderId).catch(() => {});
    await cleanupUser(page, userId);
    await archiveCourseViaApi(page, courseId);
    await ctx.close();
  });

  test.beforeEach(async ({ page }) => {
    await loginAsAdmin(page);
  });

  test('create an order via API and find it in the list', async ({ page }) => {
    // Create the order via the API so we have a known orderId to work with.
    const ctx = page.request;
    const order = await createOrderViaApi(ctx, userId, courseId, { amount: '99.00' });
    orderId = order.orderId;

    await goTo(page, ROUTES.orders);
    await expect(page.getByText('订单管理').first()).toBeVisible({ timeout: 15_000 });

    // The orders list requires a user ID filter before it loads anything.
    await page.getByPlaceholder('按用户 ID 筛选').fill(String(userId));
    const searchResp = page.waitForResponse(
      (r) => r.url().includes('/api/v1/orders/user/') && r.request().method() === 'GET',
      { timeout: 15_000 },
    );
    await page.getByRole('button', { name: '搜索' }).click();
    await searchResp;

    await waitForTableRow(page, String(orderId));
    const row = page.locator('tr.ant-table-row', { hasText: String(orderId) }).first();
    await expect(row).toContainText('待支付');
  });

  test('pay the order and verify status', async ({ page }) => {
    test.skip(!orderId, 'order was not created');
    await goTo(page, ROUTES.orders);
    await page.getByPlaceholder('按用户 ID 筛选').fill(String(userId));
    await page.getByRole('button', { name: '搜索' }).click();
    await waitForTableRow(page, String(orderId));

    const row = page.locator('tr.ant-table-row', { hasText: String(orderId) }).first();
    await row.getByRole('button', { name: '支付' }).click();

    const modal = page.locator('.ant-modal').last();
    await expect(modal).toBeVisible();
    await modal.getByPlaceholder('如 ALIPAY / WECHAT').fill('ALIPAY');

    const payResp = page.waitForResponse(
      (r) => r.url().includes('/pay') && r.request().method() === 'POST',
      { timeout: 15_000 },
    );
    await modal.getByRole('button', { name: '确认支付' }).click();
    const resp = await payResp;
    expect(resp.ok()).toBeTruthy();

    await waitForTableRow(page, String(orderId));
    await expect(page.locator('tr.ant-table-row', { hasText: String(orderId) }).first()).toContainText(
      '已支付',
    );
  });

  test('refund the order and verify status', async ({ page }) => {
    test.skip(!orderId, 'order was not created');
    await goTo(page, ROUTES.orders);
    await page.getByPlaceholder('按用户 ID 筛选').fill(String(userId));
    await page.getByRole('button', { name: '搜索' }).click();
    await waitForTableRow(page, String(orderId));

    const row = page.locator('tr.ant-table-row', { hasText: String(orderId) }).first();
    await row.getByRole('button', { name: '退款' }).click();

    const popconfirm = page.locator('.ant-popconfirm').last();
    await expect(popconfirm).toBeVisible();
    const refundResp = page.waitForResponse(
      (r) => r.url().includes('/refund') && r.request().method() === 'POST',
      { timeout: 15_000 },
    );
    await popconfirm.getByRole('button', { name: '退款' }).click();
    const resp = await refundResp;
    expect(resp.ok()).toBeTruthy();

    await waitForTableRow(page, String(orderId));
    await expect(page.locator('tr.ant-table-row', { hasText: String(orderId) }).first()).toContainText(
      '已退款',
    );
  });
});
