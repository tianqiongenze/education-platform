import { Button as TaroButton } from '@tarojs/components';
import type { ComponentProps } from 'react';
import { classNames } from '../../utils/classNames';
import type { ButtonProps } from './types';

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
    loading,
    disabled: isDisabled,
    ...(isDisabled ? {} : onClick ? { onClick } : {}),
    ...rest,
  };

  return <TaroButton {...taroProps}>{children}</TaroButton>;
}
