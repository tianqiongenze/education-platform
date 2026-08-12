export type Language = 'python' | 'java' | 'javascript' | 'cpp' | 'go' | 'rust';
export type JudgeStatus =
  | 'pending'
  | 'running'
  | 'accepted'
  | 'wrong_answer'
  | 'time_limit'
  | 'memory_limit'
  | 'runtime_error'
  | 'compile_error';

export interface CodeSubmission {
  id: string;
  problemId: string;
  userId: string;
  language: Language;
  code: string;
  status: JudgeStatus;
  executionTime: number | null;
  memoryUsed: number | null;
  errorMessage: string | null;
  submittedAt: string;
}

export interface JudgeResult {
  submissionId: string;
  status: JudgeStatus;
  executionTime: number;
  memoryUsed: number;
  passedTestCases: number;
  totalTestCases: number;
  errorMessage: string | null;
}
