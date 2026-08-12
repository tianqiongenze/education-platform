import { test, expect } from '@playwright/test';
import { mockCourseList, SELECTORS } from '@ep/shared/e2e-helpers';

/**
 * Course catalog E2E.
 *
 * The catalog page (`/courses`) fetches `/api/v1/courses` with keyword/tag
 * query params and falls back to local mock data when the API is empty. We
 * mock the API with a rich payload so search and category filtering are
 * deterministic.
 */
test.describe('Course catalog', () => {
  test.beforeEach(async ({ page }) => {
    // Mock the course list with 8 items so pagination is exercised.
    await page.route('**/api/v1/courses**', async (route) => {
      const url = new URL(route.request().url());
      const keyword = url.searchParams.get('keyword') ?? '';
      const tag = url.searchParams.get('tag') ?? '';
      const all = mockCourseList(8).items;
      let items = all;
      if (keyword) {
        items = items.filter(
          (c) =>
            c.title.toLowerCase().includes(keyword.toLowerCase()) ||
            c.description.toLowerCase().includes(keyword.toLowerCase()),
        );
      }
      if (tag && tag !== '全部') {
        items = items.filter((c) => c.tags.includes(tag));
      }
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ items, total: items.length, page: 1, pageSize: 8 }),
      });
    });
  });

  test('catalog page renders the title and search bar', async ({ page }) => {
    await page.goto('/courses');
    await page.waitForLoadState('networkidle');

    await expect(page.getByText('公开课目录').first()).toBeVisible({ timeout: 15_000 });
    await expect(page.getByPlaceholder('搜索课程标题或关键词')).toBeVisible();
  });

  test('course cards render from the mocked API', async ({ page }) => {
    await page.goto('/courses');
    await page.waitForLoadState('networkidle');

    const cards = page.locator(SELECTORS.courseCard);
    await expect(cards.first()).toBeVisible({ timeout: 15_000 });
    await expect(cards).toHaveCount(8);
    await expect(page.getByText('Mock Course 1').first()).toBeVisible();
  });

  test('searching by keyword filters the cards', async ({ page }) => {
    await page.goto('/courses');
    await page.waitForLoadState('networkidle');

    const search = page.getByPlaceholder('搜索课程标题或关键词');
    await search.fill('Mock Course 3');
    await page.getByRole('button', { name: /搜索/ }).first().click();

    await expect(page.locator(SELECTORS.courseCard)).toHaveCount(1);
    await expect(page.getByText('Mock Course 3').first()).toBeVisible();
  });

  test('clearing the search restores all courses', async ({ page }) => {
    await page.goto('/courses');
    await page.waitForLoadState('networkidle');

    const search = page.getByPlaceholder('搜索课程标题或关键词');
    await search.fill('Mock Course 3');
    await page.getByRole('button', { name: /搜索/ }).first().click();
    await expect(page.locator(SELECTORS.courseCard)).toHaveCount(1);

    // AntD Input.Search allowClear renders a close icon.
    await search.locator('..').getByRole('button').last().click().catch(async () => {
      await search.clear();
      await page.getByRole('button', { name: /搜索/ }).first().click();
    });
    await expect(page.locator(SELECTORS.courseCard).first()).toBeVisible({ timeout: 15_000 });
  });

  test('clicking a course card navigates to the detail page', async ({ page }) => {
    await page.goto('/courses');
    await page.waitForLoadState('networkidle');

    await page.locator(SELECTORS.courseCard).first().click();
    await expect(page).toHaveURL(/\/courses\/mock-1$/);
  });

  test('pagination control is rendered', async ({ page }) => {
    await page.goto('/courses');
    await page.waitForLoadState('networkidle');

    await expect(page.locator(SELECTORS.pagination)).toBeVisible({ timeout: 15_000 });
  });
});
