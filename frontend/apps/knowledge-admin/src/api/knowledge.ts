/**
 * SP-07 知识中台 API client functions.
 *
 * All endpoints are served by sp07-bff under `/api/v1/knowledge/...`.
 */
import { apiClient } from './client';
import { ApiError } from '@ep/api-client';
import type {
  KnowledgeBase,
  KnowledgeDocument,
  UploadDocumentInput,
  SearchResult,
  RagChatResponse,
  Agent,
  CreateAgentInput,
  AgentChatResponse,
  Tenant,
  UpdateQuotaInput,
  LearningPath,
  CommunityPost,
} from '../types/knowledge';

export { ApiError };

const BASE = '/api/v1/knowledge';

// ---------------------------------------------------------------------------
// Knowledge Bases
// ---------------------------------------------------------------------------

export async function listKnowledgeBases(params?: {
  keyword?: string;
  page?: number;
  pageSize?: number;
}): Promise<{ items: KnowledgeBase[]; total: number }> {
  return apiClient.get<{ items: KnowledgeBase[]; total: number }>(
    `${BASE}/bases`,
    params as Record<string, unknown> | undefined,
  );
}

// ---------------------------------------------------------------------------
// Documents
// ---------------------------------------------------------------------------

export async function listDocuments(params?: {
  knowledgeBaseId?: string;
  keyword?: string;
  page?: number;
  pageSize?: number;
}): Promise<{ items: KnowledgeDocument[]; total: number }> {
  return apiClient.get<{ items: KnowledgeDocument[]; total: number }>(
    `${BASE}/documents`,
    params as Record<string, unknown> | undefined,
  );
}

export async function uploadDocument(input: UploadDocumentInput): Promise<KnowledgeDocument> {
  const formData = new FormData();
  formData.append('file', input.file);
  formData.append('knowledgeBaseId', input.knowledgeBaseId);
  formData.append('title', input.title);
  formData.append('format', input.format);
  formData.append('category', input.category);
  formData.append('tags', JSON.stringify(input.tags));
  // Delegate to fetch; the shared client is JSON-oriented.
  const url = `${import.meta.env.VITE_API_BASE_URL || '/api'}${BASE}/documents`;
  const res = await fetch(url, {
    method: 'POST',
    body: formData,
    headers: {
      Authorization: `Bearer ${localStorage.getItem('ep_access_token') ?? ''}`,
    },
  });
  if (!res.ok) {
    throw new ApiError(res.status, `upload failed: ${res.status}`);
  }
  return (await res.json()) as KnowledgeDocument;
}

// ---------------------------------------------------------------------------
// Retrieval & RAG
// ---------------------------------------------------------------------------

export async function search(params: {
  query: string;
  knowledgeBaseIds?: string[];
  topK?: number;
}): Promise<SearchResult> {
  return apiClient.post<SearchResult>(`${BASE}/search`, params);
}

export async function ragChat(params: {
  query: string;
  knowledgeBaseIds?: string[];
  sessionId?: string;
}): Promise<RagChatResponse> {
  return apiClient.post<RagChatResponse>(`${BASE}/rag`, params);
}

// ---------------------------------------------------------------------------
// Agents
// ---------------------------------------------------------------------------

export async function listAgents(params?: {
  keyword?: string;
  page?: number;
  pageSize?: number;
}): Promise<{ items: Agent[]; total: number }> {
  return apiClient.get<{ items: Agent[]; total: number }>(
    `${BASE}/agents`,
    params as Record<string, unknown> | undefined,
  );
}

export async function createAgent(input: CreateAgentInput): Promise<Agent> {
  return apiClient.post<Agent>(`${BASE}/agents`, input);
}

export async function agentChat(params: {
  agentId: string;
  message: string;
  sessionId?: string;
}): Promise<AgentChatResponse> {
  return apiClient.post<AgentChatResponse>(`${BASE}/agents/chat`, params);
}

// ---------------------------------------------------------------------------
// Community
// ---------------------------------------------------------------------------

export async function listCommunityPosts(params?: {
  status?: string;
  page?: number;
  pageSize?: number;
}): Promise<{ items: CommunityPost[]; total: number }> {
  return apiClient.get<{ items: CommunityPost[]; total: number }>(
    `${BASE}/community/posts`,
    params as Record<string, unknown> | undefined,
  );
}

export async function reviewCommunityPost(params: {
  postId: string;
  action: 'approve' | 'reject';
}): Promise<CommunityPost> {
  return apiClient.post<CommunityPost>(`${BASE}/community/posts/review`, params);
}

// ---------------------------------------------------------------------------
// Tenants
// ---------------------------------------------------------------------------

export async function listTenants(params?: {
  keyword?: string;
  page?: number;
  pageSize?: number;
}): Promise<{ items: Tenant[]; total: number }> {
  return apiClient.get<{ items: Tenant[]; total: number }>(
    `${BASE}/tenants`,
    params as Record<string, unknown> | undefined,
  );
}

export async function updateQuota(
  tenantId: string,
  input: UpdateQuotaInput,
): Promise<Tenant> {
  return apiClient.put<Tenant>(`${BASE}/tenants/${tenantId}/quota`, input);
}

// ---------------------------------------------------------------------------
// Learning Paths
// ---------------------------------------------------------------------------

export async function listLearningPaths(params?: {
  keyword?: string;
  page?: number;
  pageSize?: number;
}): Promise<{ items: LearningPath[]; total: number }> {
  return apiClient.get<{ items: LearningPath[]; total: number }>(
    `${BASE}/learning-paths`,
    params as Record<string, unknown> | undefined,
  );
}
