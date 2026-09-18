import type { ReactNode } from 'react';

export type ButtonVariant = 'primary' | 'secondary' | 'ghost' | 'danger' | 'link';
export type ButtonSize = 'xs' | 'sm' | 'md' | 'lg';

export interface ButtonProps {
  variant?: ButtonVariant;
  size?: ButtonSize;
  loading?: boolean;
  disabled?: boolean;
  block?: boolean;
  children: ReactNode;
  onClick?: () => void;
  className?: string;
  /**
   * Accessible name for icon-only buttons (no visible text). Passed through
   * as `aria-label`; required by the ui-kit a11y convention for any button
   * whose only content is an icon.
   */
  'aria-label'?: string;
  /** Toggle-button state (e.g. ThemeToggle). */
  'aria-pressed'?: boolean;
  /** Native tooltip / accessible description supplement. */
  title?: string;
}
