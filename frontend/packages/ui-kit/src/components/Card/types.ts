import type { ReactNode } from 'react';

export interface CardProps {
  /** Optional title rendered in the card header. */
  title?: ReactNode;
  /** Optional extra content (e.g. action link) rendered on the header right. */
  extra?: ReactNode;
  /** Show a border around the card. Defaults to true. */
  bordered?: boolean;
  /** Apply the default card padding (token `card.padding`). Defaults to true. */
  padding?: boolean;
  /** Card body content. */
  children: ReactNode;
  /** Additional class names. */
  className?: string;
}
