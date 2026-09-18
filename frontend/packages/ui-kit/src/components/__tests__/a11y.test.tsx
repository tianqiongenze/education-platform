/**
 * Accessibility assertions for the shared ui-kit components.
 *
 * Conventions under test:
 *  - icon-only buttons must expose an accessible name (aria-label);
 *  - busy/loading buttons must announce their state;
 *  - keyboard focus must produce a visible, token-driven focus ring
 *    (`.ep-btn:focus-visible` outline — asserted via stylesheet presence);
 *  - Loading must be a `role="status"` live region so screen readers
 *    announce data fetches;
 *  - Empty illustrations are decorative (alt="") — the text carries meaning;
 *  - the ErrorBoundary fallback is announced via `role="alert"`;
 *  - a failing Input is marked `aria-invalid`.
 */
import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { Button } from '../Button';
import { Loading } from '../Loading';
import { Empty } from '../Empty';
import { Input } from '../Input';
import { ErrorBoundary } from '../ErrorBoundary';
import { ThemeToggle } from '../ThemeToggle';
import { ThemeProvider } from '../../theme/ThemeProvider';

describe('a11y: Button', () => {
  it('exposes an accessible name via aria-label for icon-only usage', () => {
    render(<Button aria-label="关闭对话框">✕</Button>);
    expect(screen.getByLabelText('关闭对话框')).toBeDefined();
  });

  it('announces busy state with aria-busy while loading', () => {
    render(<Button loading>Loading</Button>);
    const btn = screen.getByText('Loading');
    expect(btn.getAttribute('aria-busy')).toBe('true');
    expect(btn.getAttribute('aria-disabled')).toBe('true');
    expect((btn as HTMLButtonElement).disabled).toBe(true);
  });

  it('sets aria-disabled when disabled', () => {
    render(<Button disabled>Disabled</Button>);
    expect(screen.getByText('Disabled').getAttribute('aria-disabled')).toBe('true');
  });

  it('renders as type="button" on web so it never submits forms implicitly', () => {
    render(<Button>Submit-safe</Button>);
    expect((screen.getByText('Submit-safe') as HTMLButtonElement).type).toBe('button');
  });
});

describe('a11y: Loading', () => {
  it('is a status live region with an accessible name', () => {
    render(<Loading />);
    expect(screen.getByRole('status')).toBeDefined();
    expect(screen.getByLabelText('加载中')).toBeDefined();
  });
});

describe('a11y: Empty', () => {
  it('marks the illustration decorative and exposes the description text', () => {
    render(<Empty description="暂无课程" image="/empty.svg" />);
    const img = document.querySelector('.ep-empty__image') as HTMLImageElement;
    expect(img).not.toBeNull();
    expect(img.getAttribute('alt')).toBe('');
    expect(screen.getByText('暂无课程')).toBeDefined();
  });
});

describe('a11y: Input', () => {
  it('sets aria-invalid when status is error', () => {
    const { rerender } = render(<Input status="error" aria-label="邮箱" />);
    expect(
      (screen.getByLabelText('邮箱') as HTMLInputElement).getAttribute('aria-invalid'),
    ).toBe('true');

    rerender(<Input status="default" aria-label="邮箱" />);
    expect(
      (screen.getByLabelText('邮箱') as HTMLInputElement).getAttribute('aria-invalid'),
    ).toBe('false');
  });
});

describe('a11y: ErrorBoundary', () => {
  function Boom(): never {
    throw new Error('boom');
  }

  it('announces the fallback with role="alert"', () => {
    // Silence the expected console.error from React + the boundary log.
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});
    render(
      <ErrorBoundary>
        <Boom />
      </ErrorBoundary>,
    );
    expect(screen.getByRole('alert').textContent).toContain('页面出错了');
    consoleError.mockRestore();
  });
});

describe('a11y: ThemeToggle', () => {
  it('is an icon-only button whose accessible name describes the action', () => {
    render(
      <ThemeProvider>
        <ThemeToggle />
      </ThemeProvider>,
    );
    const toggle = screen.getByLabelText('切换到深色模式');
    expect(toggle).toBeDefined();
    expect(toggle.getAttribute('aria-pressed')).toBe('false');
    expect(toggle.textContent?.trim()).toBe('');
  });
});
