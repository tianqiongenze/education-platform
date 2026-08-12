/**
 * SP-07 知识中台 shared TypeScript types.
 */

export type KnowledgeBaseCategory = 'technical' | 'product' | 'service';

export type DocumentStatus = 'pending' | 'indexed' | 'failed';

export type DocumentFormat = 'pdf' | 'docx' | 'markdown' | 'html' | 'txt';

export interface KnowledgeBase {
  id: string;
  name: string;
  category: KnowledgeBaseCategory;
  description: string;
  documentCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface KnowledgeDocument {
  id: string;
  knowledgeBaseId: string;
  title: string;
  format: DocumentFormat;
  status: DocumentStatus;
  size: number;
  tags: string[];
  createdAt: string;
  updatedAt: string;
}

export interface UploadDocumentInput {
  knowledgeBaseId: string;
  title: string;
  format: DocumentFormat;
  category: KnowledgeBaseCategory;
  tags: string[];
  file: File;
}

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

export interface RagChatMessage {
  role: 'user' | 'assistant';
  content: string;
  citations: SearchHit[];
}

export interface RagChatResponse {
  answer: string;
  citations: SearchHit[];
  sessionId: string;
}

export interface Agent {
  id: string;
  name: string;
  description: string;
  knowledgeBaseIds: string[];
  model: string;
  temperature: number;
  status: 'enabled' | 'disabled';
  createdAt: string;
  updatedAt: string;
}

export interface CreateAgentInput {
  name: string;
  description: string;
  knowledgeBaseIds: string[];
  model: string;
  temperature: number;
}

export interface AgentChatMessage {
  role: 'user' | 'assistant';
  content: string;
}

export interface AgentChatResponse {
  answer: string;
  sessionId: string;
}

export interface CommunityPost {
  id: string;
  title: string;
  content: string;
  authorId: string;
  authorName: string;
  status: 'pending' | 'approved' | 'rejected';
  upvotes: number;
  answers: number;
  createdAt: string;
  updatedAt: string;
}

export interface Tenant {
  id: string;
  name: string;
  code: string;
  quota: TenantQuota;
  status: 'active' | 'suspended';
  createdAt: string;
  updatedAt: string;
}

export interface TenantQuota {
  documents: number;
  queries: number;
  storageBytes: number;
}

export interface UpdateQuotaInput {
  documents?: number;
  queries?: number;
  storageBytes?: number;
}

export interface LearningPath {
  id: string;
  title: string;
  description: string;
  documentIds: string[];
  estimatedMinutes: number;
  createdAt: string;
  updatedAt: string;
}
