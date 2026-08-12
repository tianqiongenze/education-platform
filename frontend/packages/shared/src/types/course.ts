export type CourseStatus = 'draft' | 'published' | 'archived';
export type LessonType = 'video' | 'live' | 'coding' | 'quiz' | 'reading';

export interface Course {
  id: string;
  title: string;
  description: string;
  coverImage: string;
  status: CourseStatus;
  teacherId: string;
  teacherName: string;
  price: number;
  enrollmentCount: number;
  rating: number;
  tags: string[];
  chapters: CourseChapter[];
  createdAt: string;
  updatedAt: string;
}

export interface CourseChapter {
  id: string;
  courseId: string;
  title: string;
  order: number;
  lessons: Lesson[];
}

export interface Lesson {
  id: string;
  chapterId: string;
  title: string;
  type: LessonType;
  duration: number;
  order: number;
  isFree: boolean;
  resourceUrl: string | null;
}
