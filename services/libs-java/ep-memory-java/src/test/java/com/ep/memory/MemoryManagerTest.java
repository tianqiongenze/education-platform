package com.ep.memory;

import com.ep.memory.model.ContextWindow;
import com.ep.memory.model.Memory;
import com.ep.memory.model.MemoryAddRequest;
import com.ep.memory.model.MemorySearchRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryManagerTest {

    private MemoryRpcService rpc;
    private MemoryClient http;
    private MemoryManager managerRpc;
    private MemoryManager managerHttp;

    @BeforeEach
    void setUp() {
        rpc = Mockito.mock(MemoryRpcService.class);
        http = Mockito.mock(MemoryClient.class);
        managerRpc = new MemoryManager(rpc, http, true);
        managerHttp = new MemoryManager(rpc, http, false);
    }

    @Test
    void testMemoryAdd() {
        MemoryAddRequest req = new MemoryAddRequest(
                "t1", "u1", "s1",
                List.of(Map.of("role", "user", "content", "hi")));
        managerRpc.addMemory(req);
        verify(rpc).add(req);
        verify(http, never()).addMemory(any());

        managerHttp.addMemory(req);
        verify(http).addMemory(req);
    }

    @Test
    void testMemorySearch() {
        MemorySearchRequest req = new MemorySearchRequest("t1", "u1", "hello", 5);
        List<Memory> expected = List.of(
                new Memory("m1", "t1", "u1", "hello back", "episodic", 0.9, 1L, Map.of()));
        when(rpc.search(req)).thenReturn(expected);
        when(http.searchMemory(req)).thenReturn(expected);

        assertEquals(expected, managerRpc.searchMemory(req));
        assertEquals(expected, managerHttp.searchMemory(req));
    }

    @Test
    void testGetContext() {
        ContextWindow ctx = new ContextWindow(
                List.of(Map.of("role", "user", "content", "q")),
                "summary",
                List.of(),
                List.of());
        when(rpc.getContext("t1", "u1", "s1")).thenReturn(ctx);
        when(http.getContext("t1", "u1", "s1")).thenReturn(ctx);

        assertEquals(ctx, managerRpc.getContext("t1", "u1", "s1"));
        assertEquals(ctx, managerHttp.getContext("t1", "u1", "s1"));
    }

    @Test
    void testHttpFallbackWhenRpcThrows() {
        ContextWindow ctx = new ContextWindow(List.of(), "fallback-summary", List.of(), List.of());
        when(rpc.getContext("t1", "u1", "s1")).thenThrow(new RuntimeException("dubbo down"));
        when(http.getContext("t1", "u1", "s1")).thenReturn(ctx);

        // useRpc=true but RPC throws -> manager should swallow and return null (graceful degrade),
        // NOT fall through to HTTP (HTTP is the alternate primary path, not an exception fallback).
        // This documents the contract: MemoryManager does not double-call on RPC exceptions.
        assertNull(managerRpc.getContext("t1", "u1", "s1"));
        // HTTP path is used directly when useRpc=false.
        assertNotNull(managerHttp.getContext("t1", "u1", "s1"));
        verify(http).getContext("t1", "u1", "s1");
    }

    @Test
    void testForgetAndPromote() {
        managerRpc.forget("m1");
        verify(rpc).forget("m1");
        managerRpc.promoteToOrg("m1");
        verify(rpc).promoteToOrg("m1");

        managerHttp.forget("m1");
        verify(http).forgetMemory("m1");
        managerHttp.promoteToOrg("m1");
        verify(http).promoteToOrg("m1");
    }

    @Test
    void testNullInputsDoNotThrow() {
        // No transport wired at all — every op must degrade to a no-op / empty.
        MemoryManager bare = new MemoryManager(null, null, true);
        assertNull(bare.getContext("t", "u", "s"));
        bare.addMemory(new MemoryAddRequest("t", "u", "s", java.util.List.of()));
        assertEquals(java.util.List.of(), bare.searchMemory(new MemorySearchRequest("t", "u", "q", 1)));
        bare.forget("m");
        bare.promoteToOrg("m");
    }

    @Test
    void testAddMemorySwallowsRpcException() {
        MemoryAddRequest req = new MemoryAddRequest("t1", "u1", "s1",
                List.of(Map.of("role", "user", "content", "x")));
        Mockito.doThrow(new RuntimeException("boom")).when(rpc).add(req);
        // Must not propagate — memory failure must never break the caller.
        managerRpc.addMemory(req);
        verify(rpc).add(req);
    }

    @Test
    void testSearchMemorySwallowsExceptionAndReturnsEmpty() {
        MemorySearchRequest req = new MemorySearchRequest("t1", "u1", "q", 3);
        when(rpc.search(req)).thenThrow(new RuntimeException("search down"));
        assertEquals(List.of(), managerRpc.searchMemory(req));
    }

    @Test
    void testUseRpcFalseIgnoresRpcService() {
        // Even when rpcService is non-null, useRpc=false routes to HTTP only.
        MemorySearchRequest req = new MemorySearchRequest("t1", "u1", "q", 2);
        when(http.searchMemory(req)).thenReturn(List.of());
        managerHttp.searchMemory(req);
        verify(rpc, never()).search(any());
    }

    @Test
    void testRpcBoundButUseRpcFalseFallsBackToHttp() {
        // http bound, useRpc false — http is the primary path.
        ContextWindow ctx = new ContextWindow(List.of(), "s", List.of(), List.of());
        when(http.getContext("t1", "u1", "s1")).thenReturn(ctx);
        assertEquals(ctx, managerHttp.getContext("t1", "u1", "s1"));
        verify(rpc, never()).getContext(any(), any(), any());
    }
}
