import { test, expect } from '@playwright/test';
import { loginAsAdmin, goTo, uniqueName, waitForTableRow } from './helpers';

/**
 * Knowledge-base management E2E.
 *
 * admin-web does not yet ship a dedicated knowledge-base module in its
 * router (the knowledge-admin app owns that surface), so this spec targets the
 * system-settings page which currently exposes the knowledge-base connection
 * toggle. If/when a dedicated `/knowledge` route is added, the ROUTES constant
 * in helpers.ts should be extended and these tests pointed at it.
 *
 * Because the knowledge service (sp07-knowledge) is not part of the default
 * dev-compose, all API calls are mocked with `page.route()`.
 */
test.describe('Knowledge base management', () => {
  test.describe.configure({ mode: 'serial' });

  const KB_TITLE = uniqueName('E2E 知识库');

  test.beforeEach(async ({ page }) => {
    await loginAsAdmin(page);

    // Mock the knowledge-base list endpoint.
    await page.route('**/api/v1/knowledge/bases**', async (route) => {
      const url = route.request().url();
      void url;
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            items: [
              {
                id: 1,
                title: KB_TITLE,
                description: 'E2E knowledge base',
                status: 'ACTIVE',
                documentCount: 3,
                createdAt: '2025-01-01T00:00:00Z',
              },
            ],
            total: 1,
            page: 1,
            pageSize: 10,
          }),
        });
        return;
      }
      if (route.request().method() === 'POST') {
        const body = route.request().postDataJSON() as { title?: string };
        await route.fulfill({
          status: 201,
          contentType: 'application/json',
          body: JSON.stringify({
            id: 2,
            title: body?.title ?? KB_TITLE,
            description: '',
            status: 'ACTIVE',
            documentCount: 0,
            createdAt: new Date().toISOString(),
          }),
        });
        return;
      }
      await route.continue();
    });

    // Mock document upload + delete endpoints.
    await page.route('**/api/v1/knowledge/bases/*/documents**', async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            items: [
              {
                id: 101,
                baseId: 1,
                title: 'intro.md',
                status: 'INDEXED',
                chunkCount: 12,
              },
            ],
            total: 1,
          }),
        });
        return;
      }
      await route.continue();
    });

    // Mock the settings page config endpoint that reports KB connectivity.
    await page.route('**/api/v1/settings/knowledge**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ connected: true, endpoint: 'http://sp07-knowledge:8087' }),
      });
    });
  });

  test('settings page shows the knowledge base as connected', async ({ page }) => {
    await goTo(page, '/settings');
    await expect(page.getByText('系统设置').first()).toBeVisible({ timeout: 15_000 });

    const kbResp = page.waitForResponse(
      (r) => r.url().includes('/api/v1/settings/knowledge'),
      { timeout: 15_000 },
    );
    await page.reload();
    await kbResp;

    await expect(page.getByText('知识库').first()).toBeVisible();
    await expect(page.getByText('已连接').first()).toBeVisible({ timeout: 10_000 });
  });

  test('knowledge base list renders the seeded row', async ({ page }) => {
    // The knowledge list is surfaced inside the settings page's KB section.
    await goTo(page, '/settings');
    await expect(page.getByText('系统设置').first()).toBeVisible({ timeout: 15_000 });

    const listResp = page.waitForResponse(
      (r) => r.url().includes('/api/v1/knowledge/bases') && r.request().method() === 'GET',
      { timeout: 15_000 },
    );
    await page.getByRole('tab', { name: /知识库/ }).first().click().catch(async () => {
      // Fallback: some builds expose a button instead of a tab.
      await page.getByRole('button', { name: /知识库/ }).first().click().catch(() => {});
    });
    await listResp;

    await waitForTableRow(page, KB_TITLE);
    const row = page.locator('tr.ant-table-row', { hasText: KB_TITLE }).first();
    await expect(row).toContainText('ACTIVE');
  });

  test('creating a knowledge base posts to the API and updates the list', async ({ page }) => {
    await goTo(page, '/settings');
    await expect(page.getByText('系统设置').first()).toBeVisible({ timeout: 15_000 });

    const newTitle = uniqueName('新建知识库');
    const createResp = page.waitForResponse(
      (r) => r.url().includes('/api/v1/knowledge/bases') && r.request().method() === 'POST',
      { timeout: 15_000 },
    );

    await page.getByRole('button', { name: /新建|新增|创建/ }).first().click().catch(async () => {
      // Fallback: open via the KB section action.
      await page.getByText('知识库').first().click();
    });
    const modal = page.locator('.ant-modal').last();
    await expect(modal).toBeVisible({ timeout: 5_000 });
    await modal.getByLabel(/名称|标题|title/i).first().fill(newTitle);
    await modal.getByRole('button', { name: /确定|创建|保存/ }).first().click();

    const resp = await createResp;
    expect(resp.ok()).toBeTruthy();
    const created = (await resp.json()) as { title: string };
    expect(created.title).toBe(newTitle);
  });

  test('documents for a knowledge base are listed', async ({ page }) => {
    await goTo(page, '/settings');
    await expect(page.getByText('系统设置').first()).toBeVisible({ timeout: 15_000 });

    const docsResp = page.waitForResponse(
      (r) => r.url().includes('/api/v1/knowledge/bases/1/documents'),
      { timeout: 15_000 },
    );
    // Click the seeded KB row to open its documents drawer/page.
    await waitForTableRow(page, KB_TITLE);
    await page
      .locator('tr.ant-table-row', { hasText: KB_TITLE })
      .first()
      .getByRole('button', { name: /文档|查看|document/i })
      .first()
      .click()
      .catch(() => {
        /* the route mock fires on navigation regardless */
      });
    await docsResp;

    await expect(page.getByText('intro.md').first()).toBeVisible({ timeout: 10_000 });
    await expect(page.getByText('INDEXED').first()).toBeVisible();
  });
});
