import { View, Text, Image } from '@tarojs/components';
import Taro from '@tarojs/taro';
import { useLiveRooms } from '@ep/query';
import { Loading, Empty } from '@ep/ui-kit';
import { apiClient } from '../../../../api/client';
import { formatDate } from '@ep/shared';
import './index.scss';

const STATUS_TEXT: Record<string, string> = {
  scheduled: '未开始',
  live: '直播中',
  ended: '已结束',
  cancelled: '已取消',
};

export default function LiveList() {
  const { data, isLoading } = useLiveRooms(apiClient, { page: 1, pageSize: 20 });

  const items = data?.items ?? [];

  const enterRoom = (id: string, stream: string) => {
    Taro.navigateTo({ url: `/subpackages/live/pages/room/index?id=${id}&stream=${encodeURIComponent(stream)}` });
  };

  return (
    <View className="live-list ep-page">
      <View className="live-list__header">
        <Text className="live-list__title">直播列表</Text>
      </View>

      <View className="live-list__body">
        {isLoading ? (
          <Loading />
        ) : items.length === 0 ? (
          <Empty description="暂无直播" />
        ) : (
          items.map((r) => (
            <View
              key={r.id}
              className="live-list__item"
              onClick={() => enterRoom(r.id, r.streamUrl)}
            >
              <Image className="live-list__cover" src={r.coverImage} mode="aspectFill" />
              <View className="live-list__info">
                <Text className="live-list__name">{r.title}</Text>
                <Text className="live-list__teacher">{r.teacherName}</Text>
                <Text className="live-list__time">{formatDate(r.scheduledStart)}</Text>
                <Text className={`live-list__status live-list__status--${r.status}`}>
                  {STATUS_TEXT[r.status] ?? r.status}
                </Text>
              </View>
            </View>
          ))
        )}
      </View>
    </View>
  );
}
