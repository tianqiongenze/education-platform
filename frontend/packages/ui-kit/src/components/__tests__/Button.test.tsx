import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';

import { Button } from '../Button';

describe('Button', () => {
  it('renders children', () => {
    render(<Button>Click me</Button>);
    expect(screen.getByText('Click me')).toBeDefined();
  });

  it('applies variant and size classes', () => {
    render(
      <Button variant="danger" size="lg">
        Delete
      </Button>,
    );
    const btn = screen.getByText('Delete');
    expect(btn.className).toContain('ep-btn--danger');
    expect(btn.className).toContain('ep-btn--lg');
    expect(btn.className).toContain('ep-btn');
  });

  it('applies block class', () => {
    render(<Button block>Full</Button>);
    expect(screen.getByText('Full').className).toContain('ep-btn--block');
  });

  it('does not call onClick when disabled', () => {
    const onClick = vi.fn();
    render(
      <Button disabled onClick={onClick}>
        Disabled
      </Button>,
    );
    fireEvent.click(screen.getByText('Disabled'));
    expect(onClick).not.toHaveBeenCalled();
  });

  it('does not call onClick when loading', () => {
    const onClick = vi.fn();
    render(
      <Button loading onClick={onClick}>
        Loading
      </Button>,
    );
    fireEvent.click(screen.getByText('Loading'));
    expect(onClick).not.toHaveBeenCalled();
  });

  it('calls onClick when enabled', () => {
    const onClick = vi.fn();
    render(<Button onClick={onClick}>Active</Button>);
    fireEvent.click(screen.getByText('Active'));
    expect(onClick).toHaveBeenCalledTimes(1);
  });

  it('defaults to primary variant and md size', () => {
    render(<Button>Default</Button>);
    const btn = screen.getByText('Default');
    expect(btn.className).toContain('ep-btn--primary');
    expect(btn.className).toContain('ep-btn--md');
  });
});
