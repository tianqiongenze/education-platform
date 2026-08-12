package com.ep.knowledge.api.retrieval;

/**
 * Dubbo RPC service for retrieval (vector + keyword hybrid search and RAG).
 */
public interface RetrievalRpcService {

    SearchResult search(QueryCmd cmd);

    RagResult ragChat(RagCmd cmd);
}
