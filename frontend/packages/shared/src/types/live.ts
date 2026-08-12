export type LiveRoomStatus = 'scheduled' | 'live' | 'ended' | 'cancelled';

export interface LiveRoom {
  id: string;
  title: string;
  description: string;
  teacherId: string;
  teacherName: string;
  courseId: string | null;
  status: LiveRoomStatus;
  streamUrl: string;
  coverImage: string;
  scheduledStart: string;
  scheduledEnd: string;
  actualStart: string | null;
  actualEnd: string | null;
  participantCount: number;
  maxParticipants: number;
}

export interface LiveSession {
  id: string;
  roomId: string;
  userId: string;
  joinedAt: string;
  leftAt: string | null;
  duration: number;
}
