import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';

import { Input } from '../Input';

describe('Input', () => {
  it('renders a placeholder', () => {
    render(<Input placeholder="Type here" />);
    expect(screen.getByPlaceholderText('Type here')).toBeDefined();
  });

  it('defaults to default status and md size classes', () => {
    render(<Input placeholder="x" />);
    const wrapper = screen.getByPlaceholderText('x').closest('.ep-input');
    expect(wrapper?.className).toContain('ep-input--default');
  });

  it('applies error status class', () => {
    render(<Input status="error" placeholder="x" />);
    const wrapper = screen.getByPlaceholderText('x').closest('.ep-input');
    expect(wrapper?.className).toContain('ep-input--error');
  });

  it('applies disabled class and attribute', () => {
    render(<Input disabled placeholder="x" />);
    const wrapper = screen.getByPlaceholderText('x').closest('.ep-input');
    expect(wrapper?.className).toContain('ep-input--disabled');
    expect((screen.getByPlaceholderText('x') as HTMLInputElement).disabled).toBe(true);
  });

  it('renders prefix and suffix nodes', () => {
    render(
      <Input prefix={<span>pre</span>} suffix={<span>suf</span>} placeholder="x" />,
    );
    expect(screen.getByText('pre')).toBeDefined();
    expect(screen.getByText('suf')).toBeDefined();
    const wrapper = screen.getByPlaceholderText('x').closest('.ep-input');
    expect(wrapper?.className).toContain('ep-input--with-prefix');
    expect(wrapper?.className).toContain('ep-input--with-suffix');
  });

  it('calls onChange when the value changes', () => {
    const onChange = vi.fn();
    render(<Input placeholder="x" onChange={onChange} />);
    fireEvent.change(screen.getByPlaceholderText('x'), { target: { value: 'hi' } });
    expect(onChange).toHaveBeenCalledTimes(1);
  });

  it('toggles focused class on focus/blur', () => {
    render(<Input placeholder="x" />);
    const input = screen.getByPlaceholderText('x');
    const wrapper = input.closest('.ep-input');
    fireEvent.focus(input);
    expect(wrapper?.className).toContain('ep-input--focused');
    fireEvent.blur(input);
    expect(wrapper?.className).not.toContain('ep-input--focused');
  });

  it('forwards className', () => {
    render(<Input className="my-input" placeholder="x" />);
    const wrapper = screen.getByPlaceholderText('x').closest('.ep-input');
    expect(wrapper?.className).toContain('my-input');
  });
});
