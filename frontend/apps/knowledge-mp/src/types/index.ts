/**
 * SP-07 学员知识小程序端共享类型。
 */

export interface SearchHit {
  id: string;
  title: string;
  snippet: string;
  score: number;
  knowledgeBaseId: string;
  source: string;
}

export interface SearchResult {
  total: number;
  hits: SearchHit[];
  tookMs: number;
}

export interface RagMessage {
  role: 'user' | 'assistant';
  content: string;
  citations: SearchHit[];
}

export interface RagChatResponse {
  answer: string;
  citations: SearchHit[];
  sessionId: string;
}

export interface CommunityPost {
  id: string;
  title: string;
  content: string;
  authorName: string;
  upvotes: number;
  answers: number;
  createdAt: string;
}

export interface LearningPath {
  id: string;
  title: string;
  description: string;
  estimatedMinutes: number;
  progress: number;
}

export interface UserProfile {
  id: string;
  nickname: string;
  avatar: string;
  learningMinutes: number;
  contributionScore: number;
  favorites: number;
}
