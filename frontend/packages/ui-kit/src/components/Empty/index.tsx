import { View, Image, Text } from '../../platform';

export function Empty({
  description = '暂无数据',
  image,
}: {
  description?: string;
  image?: string;
}) {
  return (
    <View className="ep-empty" role="status">
      {image ? (
        // The illustration is decorative — the description text below carries
        // the meaning, so expose an empty alt to avoid double announcement.
        <Image className="ep-empty__image" src={image} mode="aspectFit" alt="" />
      ) : null}
      <Text className="ep-empty__description">{description}</Text>
    </View>
  );
}
