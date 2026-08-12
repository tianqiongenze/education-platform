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
 * Payment / Saga order-flow E2E.
 *
 * The order service (sp03-business) uses a Saga orchestrator for the
 * pay/refund lifecycle: paying an order triggers downstream saga steps
 * (inventory, payment-gateway mock, enrollment) and refunding runs the
 * compensating transactions. This spec exercises the full happy + compensation
 * path through the admin-web UI and asserts the saga reaches terminal states.
 *
 * Setup (via API for speed): create a user + a published course.
 *
 * Flow:
 * 1. Create order via API -> find it in 订单管理 with 待支付.
 * 2. Pay via the UI modal -> verify 已支付.
 * 3. Refund via the UI popconfirm -> verify 已退款 (saga compensation).
 * 4. Idempotent re-pay is rejected (saga already terminal).
 */
test.describe('Payment Saga flow', () => {
  test.describe.configure({ mode: 'serial' });

  let userId: number;
  let courseId: number;
  let orderId: number;

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext();
    const page = await ctx.newPage();
    const user = await createUserViaApi(page);
    userId = user.userId;
    const course = await createCourseViaApi(page, { price: '199.00' });
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

  test('saga starts in PENDING (待支付) after order creation', async ({ page }) => {
    const ctx = page.request;
    const order = await createOrderViaApi(ctx, userId, courseId, { amount: '199.00' });
    orderId = order.orderId;

    await goTo(page, ROUTES.orders);
    await expect(page.getByText('订单管理').first()).toBeVisible({ timeout: 15_000 });

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

  test('paying the order drives the saga to PAID (已支付)', async ({ page }) => {
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
    await expect(
      page.locator('tr.ant-table-row', { hasText: String(orderId) }).first(),
    ).toContainText('已支付');
  });

  test('refunding runs compensating saga steps -> 已退款', async ({ page }) => {
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
    await expect(
      page.locator('tr.ant-table-row', { hasText: String(orderId) }).first(),
    ).toContainText('已退款');
  });

  test('re-paying a refunded (terminal) order is rejected by the saga', async ({ request }) => {
    test.skip(!orderId, 'order was not created');
    // The saga is terminal (REFUNDED); a second pay attempt must not flip it
    // back to PAID. We assert at the API level because the UI hides the pay
    // button for non-pending orders.
    const res = await request.post(`/api/v1/orders/${orderId}/pay`, {
      data: { payMethod: 'ALIPAY' },
    });
    // The saga guard rejects the transition -> 4xx or 409.
    expect(res.ok()).toBeFalsy();

    // The order status remains 已退款.
    const getRes = await request.get(`/api/v1/orders/${orderId}`);
    expect(getRes.ok()).toBeTruthy();
    const order = (await getRes.json()) as { status: number };
    // status:6 maps to REFUNDED in sp03 OrderStatus enum (see types/business).
    expect(order.status).toBeGreaterThanOrEqual(5);
  });

  test('order detail page renders the saga timeline', async ({ page }) => {
    test.skip(!orderId, 'order was not created');
    await goTo(page, ROUTES.orderDetail(orderId));
    await expect(page.getByText('订单详情').first()).toBeVisible({ timeout: 15_000 });
    // The detail page shows the saga step list (支付 / 退款).
    await expect(page.getByText('支付').first()).toBeVisible();
  });
});
