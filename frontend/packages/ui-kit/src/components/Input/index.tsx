import { useState, type ChangeEvent, type ComponentProps, type ReactNode, type FocusEvent as ReactFocusEvent } from 'react';
import { Input as TaroInput, View, Text } from '../../platform';
import { classNames } from '../../utils/classNames';
import type { InputProps } from './types';

// Side-effect: ships the Input's token-driven styles.
import './Input.scss';

type TaroInputProps = ComponentProps<typeof TaroInput>;

/**
 * Text input backed by the platform adapter (native <input> on web,
 * @tarojs/components Input in mini-program builds).
 *
 * Styling is driven by @ep/design-tokens CSS custom properties (--ep-*),
 * with hardcoded fallbacks so the field remains usable without tokens.
 */
export function Input({
  value,
  defaultValue,
  placeholder,
  disabled = false,
  status = 'default',
  prefix,
  suffix,
  onChange,
  onFocus,
  onBlur,
  className,
  ...rest
}: InputProps) {
  const [focused, setFocused] = useState(false);

  const cls = classNames(
    'ep-input',
    `ep-input--${status}`,
    {
      'ep-input--disabled': disabled,
      'ep-input--focused': focused,
      'ep-input--with-prefix': Boolean(prefix),
      'ep-input--with-suffix': Boolean(suffix),
    },
    className,
  );

  const handleFocus = (e: unknown) => {
    setFocused(true);
    onFocus?.(e as ReactFocusEvent<HTMLInputElement>);
  };

  const handleBlur = (e: unknown) => {
    setFocused(false);
    onBlur?.(e as ReactFocusEvent<HTMLInputElement>);
  };

  const handleChange = (e: unknown) => {
    onChange?.(e as ChangeEvent<HTMLInputElement>);
  };

  const inputProps = {
    className: 'ep-input__field',
    value,
    defaultValue,
    placeholder,
    disabled,
    // A11y: announce validation failure to assistive technology.
    'aria-invalid': status === 'error',
    onFocus: handleFocus,
    onBlur: handleBlur,
    onChange: handleChange,
    ...rest,
  } as TaroInputProps;

  return (
    <View className={cls}>
      {prefix ? <View className="ep-input__prefix">{prefix}</View> : null}
      <TaroInput {...inputProps} />
      {suffix ? <View className="ep-input__suffix">{suffix}</View> : null}
    </View>
  );
}

// Re-export for consumers that want the standalone label.
export function InputLabel({ children }: { children: ReactNode }) {
  return <Text className="ep-input__label">{children}</Text>;
}
