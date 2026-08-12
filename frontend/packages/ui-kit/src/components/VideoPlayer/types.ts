export interface VideoEventDetail {
  detail: {
    currentTime: number;
    duration: number;
  };
}

export interface VideoPlayerProps {
  src: string;
  poster?: string;
  isLive?: boolean;
  onTimeUpdate?: (currentTime: number) => void;
  onEnded?: () => void;
}
