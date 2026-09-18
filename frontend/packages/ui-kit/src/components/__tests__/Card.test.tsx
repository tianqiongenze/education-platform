import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';

import { Card } from '../Card';

describe('Card', () => {
  it('renders children in the body', () => {
    render(<Card>Content</Card>);
    expect(screen.getByText('Content')).toBeDefined();
  });

  it('renders header when title is provided', () => {
    render(<Card title="My Title">Body</Card>);
    expect(screen.getByText('My Title')).toBeDefined();
  });

  it('renders extra node in the header', () => {
    render(<Card title="T" extra={<span>more</span>}>Body</Card>);
    expect(screen.getByText('more')).toBeDefined();
  });

  it('omits header when neither title nor extra provided', () => {
    const { container } = render(<Card>Body</Card>);
    expect(container.querySelector('.ep-card__header')).toBeNull();
  });

  it('applies bordered class by default', () => {
    render(<Card>Body</Card>);
    const card = screen.getByText('Body').closest('.ep-card');
    expect(card?.className).toContain('ep-card--bordered');
  });

  it('removes bordered class when bordered=false', () => {
    render(<Card bordered={false}>Body</Card>);
    const card = screen.getByText('Body').closest('.ep-card');
    expect(card?.className).not.toContain('ep-card--bordered');
  });

  it('applies no-padding class when padding=false', () => {
    render(<Card padding={false}>Body</Card>);
    const card = screen.getByText('Body').closest('.ep-card');
    expect(card?.className).toContain('ep-card--no-padding');
  });

  it('forwards className', () => {
    render(<Card className="my-card">Body</Card>);
    const card = screen.getByText('Body').closest('.ep-card');
    expect(card?.className).toContain('my-card');
  });
});
