package com.ep.memory;

import com.ep.memory.model.ContextWindow;
import com.ep.memory.model.Memory;
import com.ep.memory.model.MemoryAddRequest;
import com.ep.memory.model.MemorySearchRequest;

import java.util.List;

/**
 * D-045 Dubbo RPC interface for the multi-level memory service.
 *
 * <p>Implemented by AI Gateway / a dedicated ep-memory provider and
 * consumed by knowledge-platform/data-platform/core-business via Dubbo registry (Nacos).</p>
 *
 * <p>All operations are scoped by {@code tenantId} to enforce D-044 SaaS
 * multi-tenant isolation.</p>
 */
public interface MemoryRpcService {

    /** Persist a new memory entry derived from the supplied messages. */
    void add(MemoryAddRequest request);

    /** Semantic search across the user's memory store. */
    List<Memory> search(MemorySearchRequest request);

    /** Assemble the four-level context window for an upcoming agent turn. */
    ContextWindow getContext(String tenantId, String userId, String sessionId);

    /** Remove a memory by id (GDPR / forget-me). */
    void forget(String memoryId);

    /** Promote a personal memory to the organization (tenant) scope. */
    void promoteToOrg(String memoryId);
}
