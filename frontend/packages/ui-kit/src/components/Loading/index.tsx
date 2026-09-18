import { View } from '../../platform';

export function Loading({ size = 'md' }: { size?: 'sm' | 'md' | 'lg' }) {
  return (
    <View
      className={`ep-loading ep-loading--${size}`}
      role="status"
      aria-label="加载中"
    />
  );
}
