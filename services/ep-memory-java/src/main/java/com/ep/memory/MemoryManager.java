package com.ep.memory;

import com.ep.memory.model.ContextWindow;
import com.ep.memory.model.Memory;
import com.ep.memory.model.MemoryAddRequest;
import com.ep.memory.model.MemorySearchRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * D-045 Core memory manager for SP-07/SP-02/SP-03.
 *
 * <p>Chooses between Dubbo RPC ({@link MemoryRpcService}) and HTTP fallback
 * ({@link MemoryClient}) based on {@code ep.memory.use-rpc}. All operations
 * degrade gracefully: a memory failure must never break the agent turn.</p>
 *
 * <p>Asynchronous persistence is encouraged via virtual threads
 * (D-039): {@code Thread.startVirtualThread(() -> manager.addMemory(...))}.</p>
 */
public class MemoryManager {

    private static final Logger log = LoggerFactory.getLogger(MemoryManager.class);

    private final MemoryRpcService rpcService;   // may be null in HTTP-only mode
    private final MemoryClient httpClient;       // may be null in RPC-only mode
    private final boolean useRpc;

    public MemoryManager(MemoryRpcService rpcService, MemoryClient httpClient, boolean useRpc) {
        this.rpcService = rpcService;
        this.httpClient = httpClient;
        this.useRpc = useRpc;
    }

    public ContextWindow getContext(String tenantId, String userId, String sessionId) {
        try {
            if (useRpc && rpcService != null) {
                return rpcService.getContext(tenantId, userId, sessionId);
            }
            if (httpClient != null) {
                return httpClient.getContext(tenantId, userId, sessionId);
            }
        } catch (Exception e) {
            log.warn("getContext failed (rpc={}, tenant={}): {}", useRpc, tenantId, e.toString());
        }
        return null;
    }

    public void addMemory(MemoryAddRequest request) {
        try {
            if (useRpc && rpcService != null) {
                rpcService.add(request);
                return;
            }
            if (httpClient != null) {
                httpClient.addMemory(request);
            }
        } catch (Exception e) {
            log.warn("addMemory failed (rpc={}, tenant={}): {}", useRpc, request.tenantId(), e.toString());
        }
    }

    public List<Memory> searchMemory(MemorySearchRequest request) {
        try {
            if (useRpc && rpcService != null) {
                return rpcService.search(request);
            }
            if (httpClient != null) {
                return httpClient.searchMemory(request);
            }
        } catch (Exception e) {
            log.warn("searchMemory failed (rpc={}, tenant={}): {}", useRpc, request.tenantId(), e.toString());
        }
        return List.of();
    }

    public void forget(String memoryId) {
        try {
            if (useRpc && rpcService != null) {
                rpcService.forget(memoryId);
                return;
            }
            if (httpClient != null) {
                httpClient.forgetMemory(memoryId);
            }
        } catch (Exception e) {
            log.warn("forget failed (memoryId={}): {}", memoryId, e.toString());
        }
    }

    public void promoteToOrg(String memoryId) {
        try {
            if (useRpc && rpcService != null) {
                rpcService.promoteToOrg(memoryId);
                return;
            }
            if (httpClient != null) {
                httpClient.promoteToOrg(memoryId);
            }
        } catch (Exception e) {
            log.warn("promoteToOrg failed (memoryId={}): {}", memoryId, e.toString());
        }
    }
}
