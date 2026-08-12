import { test, expect } from '@playwright/test';
import { mockLoginResponse, API } from '@ep/shared/e2e-helpers';

/**
 * Portal auth E2E — login + register flows.
 *
 * The portal login page posts to the BFF (`/api/v1/auth/login`) via the
 * shared `useLogin` mutation. We mock the BFF response so the tests are
 * CI-safe, then assert the success message and redirect.
 */
test.describe('Portal auth', () => {
  test.beforeEach(async ({ page }) => {
    // Mock the BFF login endpoint.
    await page.route('**/api/v1/auth/login', async (route) => {
      const body = route.request().postDataJSON() as { username?: string; password?: string };
      if (body?.username === 'baduser') {
        await route.fulfill({
          status: 401,
          contentType: 'application/json',
          body: JSON.stringify({ code: 'UNAUTHORIZED', message: '用户名或密码错误' }),
        });
        return;
      }
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(mockLoginResponse(body?.username ?? 'e2e-user')),
      });
    });

    // Mock the register endpoint.
    await page.route('**/api/v1/auth/register', async (route) => {
      await route.fulfill({
        status: 201,
        contentType: 'application/json',
        body: JSON.stringify({ userId: 999, success: true }),
      });
    });
  });

  test('login page renders the form', async ({ page }) => {
    await page.goto('/login');
    await page.waitForLoadState('networkidle');

    await expect(page.getByText('智教云统一登录').first()).toBeVisible({ timeout: 15_000 });
    await expect(page.getByLabel('用户名')).toBeVisible();
    await expect(page.getByLabel('密码')).toBeVisible();
    await expect(page.getByRole('button', { name: '登录' })).toBeVisible();
  });

  test('successful login shows a success message and redirects home', async ({ page }) => {
    await page.goto('/login');
    await page.waitForLoadState('networkidle');

    await page.getByLabel('用户名').fill('student01');
    await page.getByLabel('密码').fill('Passw0rd!');

    const loginResp = page.waitForResponse(
      (r) => r.url().includes(API.LOGIN) && r.request().method() === 'POST',
      { timeout: 15_000 },
    );
    await page.getByRole('button', { name: '登录' }).click();
    const resp = await loginResp;
    expect(resp.ok()).toBeTruthy();

    // AntD message toast on success.
    await expect(page.getByText('登录成功').first()).toBeVisible({ timeout: 10_000 });
    // Redirects to '/'.
    await expect(page).toHaveURL(/\/$/);
  });

  test('failed login shows an error message and stays on the page', async ({ page }) => {
    await page.goto('/login');
    await page.waitForLoadState('networkidle');

    await page.getByLabel('用户名').fill('baduser');
    await page.getByLabel('密码').fill('wrong');

    await page.getByRole('button', { name: '登录' }).click();

    await expect(page.getByText(/用户名或密码错误|登录失败/).first()).toBeVisible({
      timeout: 10_000,
    });
    await expect(page).toHaveURL(/\/login$/);
  });

  test('form validation blocks empty submissions', async ({ page }) => {
    await page.goto('/login');
    await page.waitForLoadState('networkidle');

    await page.getByRole('button', { name: '登录' }).click();

    await expect(page.getByText('请输入用户名').first()).toBeVisible({ timeout: 5_000 });
    await expect(page.getByText('请输入密码').first()).toBeVisible({ timeout: 5_000 });
  });

  test('"返回首页" link navigates back to the homepage', async ({ page }) => {
    await page.goto('/login');
    await page.waitForLoadState('networkidle');

    await page.getByRole('link', { name: '返回首页' }).click();
    await expect(page).toHaveURL(/\/$/);
  });
});
