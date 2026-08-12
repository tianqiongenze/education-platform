import { View } from '@tarojs/components';

export function Loading({ size = 'md' }: { size?: 'sm' | 'md' | 'lg' }) {
  return <View className={`ep-loading ep-loading--${size}`} />;
}
