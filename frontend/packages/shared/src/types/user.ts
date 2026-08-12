export type UserRole = 'student' | 'teacher' | 'admin' | 'super_admin';

export interface User {
  id: string;
  username: string;
  nickname: string;
  avatar: string;
  role: UserRole;
  cellId: string;
  email: string | null;
  phone: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface UserProfile extends User {
  bio: string | null;
  enrolledCourseCount: number;
  completedLessonCount: number;
  certificateCount: number;
}
