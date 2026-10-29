/**
 * D-244: SLA dashboard page tests — verdict rendering for all three
 * verdict shapes (ok / breach / insufficient-data) and the API-error path.
 * `../api/client` is mocked at module level (the browser fetcher needs a
 * real URL environment that jsdom doesn't provide) — the page contract under
 * test is `apiClient.get<SlaReport>('/api/v1/sla')` → render.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../api/client', () => ({
  apiClient: { get: vi.fn() },
}));

import { render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { apiClient } from '../api/client';
import SlaDashboard from '../pages/SlaDashboard';
import type { SlaReport } from '../types/sla';

const mockGet = vi.mocked(apiClient.get);

function makeReport(overrides: Partial<SlaReport> = {}): SlaReport {
  return {
    status: 'live',
    classes: {
      read: { verdict: 'ok', count: 500, p95Ms: 100, targetP95Ms: 500, errorRate: 0.004 },
      write: { verdict: 'breach', count: 300, p95Ms: 1500, targetP95Ms: 1000, errorRate: 0.03 },
      payment: { verdict: 'insufficient-data', count: 20, sample: 'low' },
    },
    overall: 'degraded',
    ...overrides,
  };
}

function renderWithProviders(ui: React.ReactElement) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={client}>{ui}</QueryClientProvider>);
}

beforeEach(() => {
  mockGet.mockReset();
});

describe('SlaDashboard (D-244)', () => {
  it('calls GET /api/v1/sla and renders all three class verdicts', async () => {
    mockGet.mockResolvedValueOnce(makeReport());
    renderWithProviders(<SlaDashboard />);

    await waitFor(() => {
      expect(mockGet).toHaveBeenCalledWith('/api/v1/sla');
      expect(screen.getByTestId('sla-class-read')).toBeDefined();
      expect(screen.getByTestId('sla-class-write')).toBeDefined();
      expect(screen.getByTestId('sla-class-payment')).toBeDefined();
    });
    expect(screen.getByText('达标')).toBeDefined();
    expect(screen.getByText('超标')).toBeDefined();
    expect(screen.getByText('样本不足')).toBeDefined();
    expect(screen.getByText('整体 SLA 降级')).toBeDefined();
  });

  it('shows the healthy banner when overall is ok', async () => {
    mockGet.mockResolvedValueOnce(makeReport({ overall: 'ok' }));
    renderWithProviders(<SlaDashboard />);
    await waitFor(() => expect(screen.getByText('整体 SLA 达标')).toBeDefined());
  });

  it('renders insufficient-data copy instead of statistics for low-sample classes', async () => {
    mockGet.mockResolvedValueOnce(makeReport());
    renderWithProviders(<SlaDashboard />);
    await waitFor(() => {
      expect(screen.getByText(/样本数不足/)).toBeDefined();
    });
  });

  it('renders an error alert when the API call fails', async () => {
    mockGet.mockRejectedValueOnce(new Error('connection refused'));
    renderWithProviders(<SlaDashboard />);
    await waitFor(() => expect(screen.getByText('SLA 数据不可用')).toBeDefined());
    expect(screen.getByText(/connection refused/)).toBeDefined();
  });
});
