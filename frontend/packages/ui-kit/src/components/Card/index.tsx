import { View } from '../../platform';
import { classNames } from '../../utils/classNames';
import type { CardProps } from './types';

// Side-effect: ships the Card's token-driven styles.
import './Card.scss';

export function Card({
  title,
  extra,
  bordered = true,
  padding = true,
  children,
  className,
}: CardProps) {
  const cls = classNames(
    'ep-card',
    {
      'ep-card--bordered': bordered,
      'ep-card--no-padding': !padding,
    },
    className,
  );

  return (
    <View className={cls}>
      {title || extra ? (
        <View className="ep-card__header">
          {title ? <View className="ep-card__title">{title}</View> : null}
          {extra ? <View className="ep-card__extra">{extra}</View> : null}
        </View>
      ) : null}
      <View className="ep-card__body">{children}</View>
    </View>
  );
}
