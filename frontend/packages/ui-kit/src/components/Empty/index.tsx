import { View, Image, Text } from '@tarojs/components';

export function Empty({
  description = '暂无数据',
  image,
}: {
  description?: string;
  image?: string;
}) {
  return (
    <View className="ep-empty">
      {image ? (
        <Image className="ep-empty__image" src={image} mode="aspectFit" />
      ) : null}
      <Text className="ep-empty__description">{description}</Text>
    </View>
  );
}
