export type AssignmentStatus = 'draft' | 'published' | 'closed';
export type SubmissionStatus = 'not_submitted' | 'submitted' | 'graded' | 'late';

export interface Assignment {
  id: string;
  courseId: string;
  title: string;
  description: string;
  maxScore: number;
  dueDate: string;
  status: AssignmentStatus;
  attachments: string[];
  createdAt: string;
  updatedAt: string;
}

export interface Submission {
  id: string;
  assignmentId: string;
  studentId: string;
  studentName: string;
  content: string;
  attachments: string[];
  status: SubmissionStatus;
  score: number | null;
  feedback: string | null;
  submittedAt: string;
  gradedAt: string | null;
}
