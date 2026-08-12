import { test, expect } from '@playwright/test';
import { mockCourseList, SELECTORS } from '@ep/shared/e2e-helpers';

/**
 * Portal homepage E2E.
 *
 * The homepage fetches `/api/v1/courses` via the BFF; when the BFF is down it
 * falls back to static `MOCK_COURSES`. We mock the API so the test is
 * deterministic and CI-safe, then assert the hero, stats, and featured course
 * cards render.
 */
test.describe('Portal homepage', () => {
  test.beforeEach(async ({ page }) => {
    // Mock the course list endpoint with a deterministic payload.
    await page.route('**/api/v1/courses**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(mockCourseList(4)),
      });
    });
  });

  test('hero section renders with title and CTAs', async ({ page }) => {
    await page.goto('/');
    await page.waitForLoadState('networkidle');

    await expect(page.locator(SELECTORS.heroSection)).toBeVisible({ timeout: 15_000 });
    await expect(page.getByText('智教云 — 全场景在线教育平台').first()).toBeVisible();
    await expect(page.locator(SELECTORS.heroCoursesBtn)).toBeVisible();
    await expect(page.locator(SELECTORS.heroLoginBtn)).toBeVisible();
  });

  test('stats section shows the four KPIs', async ({ page }) => {
    await page.goto('/');
    await page.waitForLoadState('networkidle');

    await expect(page.getByText('优质课程').first()).toBeVisible();
    await expect(page.getByText('注册学员').first()).toBeVisible();
    await expect(page.getByText('资深讲师').first()).toBeVisible();
    await expect(page.getByText('企业客户').first()).toBeVisible();
  });

  test('featured course cards render from the mocked API', async ({ page }) => {
    await page.goto('/');
    await page.waitForLoadState('networkidle');

    const cards = page.locator(SELECTORS.courseCard);
    await expect(cards.first()).toBeVisible({ timeout: 15_000 });
    // The mocked payload has 4 items.
    await expect(cards).toHaveCount(4);

    // Each card shows its title.
    await expect(page.getByText('Mock Course 1').first()).toBeVisible();
    await expect(page.getByText('Mock Course 4').first()).toBeVisible();
  });

  test('hero "浏览课程" button navigates to the catalog', async ({ page }) => {
    await page.goto('/');
    await page.waitForLoadState('networkidle');

    await page.locator(SELECTORS.heroCoursesBtn).click();
    await expect(page).toHaveURL(/\/courses$/);
    await expect(page.getByText('公开课目录').first()).toBeVisible({ timeout: 15_000 });
  });

  test('hero "立即登录" button navigates to the login page', async ({ page }) => {
    await page.goto('/');
    await page.waitForLoadState('networkidle');

    await page.locator(SELECTORS.heroLoginBtn).click();
    await expect(page).toHaveURL(/\/login$/);
    await expect(page.getByText('智教云统一登录').first()).toBeVisible({ timeout: 15_000 });
  });

  test('top nav header is present and sticky', async ({ page }) => {
    await page.goto('/');
    await page.waitForLoadState('networkidle');

    const header = page.locator('header').first();
    await expect(header).toBeVisible();
    await expect(page.getByRole('link', { name: '首页' }).first()).toBeVisible();
    await expect(page.getByRole('link', { name: '课程' }).first()).toBeVisible();
  });
});
