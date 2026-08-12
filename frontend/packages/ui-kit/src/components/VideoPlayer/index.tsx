import { View, Video, LivePlayer } from '@tarojs/components';
import type { VideoEventDetail } from './types';
import type { VideoPlayerProps } from './types';

export function VideoPlayer({
  src,
  poster,
  isLive = false,
  onTimeUpdate,
  onEnded,
}: VideoPlayerProps) {
  // 直播 + 微信小程序: 使用 live-player
  // H5 环境使用 video 标签
  const isWeapp = process.env.TARO_ENV === 'weapp';

  if (isLive && isWeapp) {
    return (
      <LivePlayer
        src={src}
        mode="live"
        autoplay
        muted={false}
        orientation="vertical"
        objectFit="contain"
      />
    );
  }

  // H5 或点播: 使用 video
  const videoProps = {
    src,
    controls: true as const,
    autoplay: false as const,
    showCenterPlayBtn: true as const,
    enableProgressGesture: true as const,
    ...(poster ? { poster } : {}),
    ...(onTimeUpdate
      ? { onTimeUpdate: (e: VideoEventDetail) => onTimeUpdate(e.detail.currentTime) }
      : {}),
    ...(onEnded ? { onEnded } : {}),
  };

  return (
    <View className="ep-video-player">
      <Video {...videoProps} />
    </View>
  );
}
