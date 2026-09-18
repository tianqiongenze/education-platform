import type { ChangeEvent, FocusEvent, ReactNode } from 'react';

export type InputStatus = 'default' | 'error' | 'warning';

export interface InputProps {
  /** Controlled value. */
  value?: string;
  /** Initial value for an uncontrolled input. */
  defaultValue?: string;
  /** Placeholder text. */
  placeholder?: string;
  /** Disable the input. */
  disabled?: boolean;
  /** Visual status, drives border / focus color. */
  status?: InputStatus;
  /** Optional prefix node rendered inside the input wrapper. */
  prefix?: ReactNode;
  /** Optional suffix node rendered inside the input wrapper. */
  suffix?: ReactNode;
  /** Called when the value changes. */
  onChange?: (e: ChangeEvent<HTMLInputElement>) => void;
  /** Called when the input gains focus. */
  onFocus?: (e: FocusEvent<HTMLInputElement>) => void;
  /** Called when the input loses focus. */
  onBlur?: (e: FocusEvent<HTMLInputElement>) => void;
  /** Additional class names. */
  className?: string;
  /** Native input passthrough (type, name, maxLength, ...). */
  [key: string]: unknown;
}
