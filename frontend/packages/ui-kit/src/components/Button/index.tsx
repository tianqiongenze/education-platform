import { Button as TaroButton, isTaro } from '../../platform';
import type { ComponentProps } from 'react';
import { classNames } from '../../utils/classNames';
import type { ButtonProps } from './types';

// Side-effect: ships the Button's token-driven styles.
import './Button.scss';

type TaroButtonProps = ComponentProps<typeof TaroButton>;

export function Button({
  variant = 'primary',
  size = 'md',
  loading = false,
  disabled = false,
  block = false,
  children,
  onClick,
  className,
  ...rest
}: ButtonProps) {
  const cls = classNames(
    'ep-btn',
    `ep-btn--${variant}`,
    `ep-btn--${size}`,
    {
      'ep-btn--block': block,
      'ep-btn--disabled': disabled,
    },
    className,
  );

  const isDisabled = disabled || loading;

  const taroProps: TaroButtonProps = {
    className: cls,
    // Native <button> defaults to type="submit"; never submit forms
    // implicitly. Only set on web/H5 (native button) — in mini-program builds
    // Taro reuses the `type` prop for its own visual style.
    ...(!isTaro ? { type: 'button' } : {}),
    // `loading` is a Taro Button prop (mini-program spinner). On web it would
    // leak onto the native <button> as an invalid DOM attribute — there the
    // busy state is conveyed via aria-busy + the disabled styles instead.
    ...(loading && isTaro ? { loading } : {}),
    disabled: isDisabled,
    // A11y: keep assistive tech informed when the control is disabled or in a
    // busy/loading state (native `disabled` alone loses state semantics in
    // some screen-reader browse modes and in mini-program runtimes).
    'aria-disabled': isDisabled,
    ...(loading ? { 'aria-busy': true } : {}),
    ...(isDisabled ? {} : onClick ? { onClick } : {}),
    ...rest,
  };

  return <TaroButton {...taroProps}>{children}</TaroButton>;
}
