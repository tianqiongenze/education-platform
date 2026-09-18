package com.ep.knowledge.service.impl;

import com.ep.cache.CacheService;
import com.ep.knowledge.service.entity.Document;
import com.ep.knowledge.service.mapper.DocumentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Pure-Mockito unit tests for {@link DocumentServiceImpl} covering the file
 * upload sanitization (path-traversal fix), UUID-based content paths and the
 * extension allowlist. No Spring context is required.
 * <p>
 * D-182: also verifies {@link CacheService} getOrLoad/evict integration.
 * P0 (租户未进 key): get/evict now go through the tenant-scoped overloads.
 */
class DocumentServiceImplTest {

    private DocumentMapper documentMapper;
    private CacheService cacheService;
    private DocumentServiceImpl service;

    /**
     * D-182: Create a CacheService mock that simulates a cache miss by invoking
     * the supplied loader. This keeps existing logic intact while allowing
     * cache-hit tests to override the stub.
     * <p>
     * P0: the service uses the tenant-scoped 6-arg overload (loader at index 3).
     */
    private static CacheService cacheMiss() {
        CacheService cs = mock(CacheService.class);
        // lenient: not every test exercises getOrLoad (upload/delete/parseAndChunk only evict).
        lenient().when(cs.getOrLoad(anyString(), anyString(), anyString(), any(Supplier.class), anyLong(), anyBoolean()))
                .thenAnswer(inv -> {
                    Supplier<?> loader = inv.getArgument(3);
                    return loader.get();
                });
        return cs;
    }

    @BeforeEach
    void setUp() {
        documentMapper = mock(DocumentMapper.class);
        when(documentMapper.insert(any(Document.class))).thenReturn(1);
        cacheService = cacheMiss();
        service = new DocumentServiceImpl(documentMapper, cacheService);
    }

    // ---- sanitizeFileName -------------------------------------------------

    @Test
    void sanitizeFileName_strips_path_traversal_dotdotslash() {
        assertEquals("passwd", DocumentServiceImpl.sanitizeFileName("../../etc/passwd"));
    }

    @Test
    void sanitizeFileName_strips_leading_slash() {
        assertEquals("shadow", DocumentServiceImpl.sanitizeFileName("/etc/shadow"));
    }

    @Test
    void sanitizeFileName_handles_encoded_traversal_input() {
        // The servlet layer normally decodes %2F to '/', but defense in depth:
        // any ".." sequences are removed regardless. The literal "%2F" chars
        // (not decoded here) survive, but the dangerous ".." is gone.
        assertEquals("%2F%2Fevil", DocumentServiceImpl.sanitizeFileName("..%2F..%2Fevil"));
    }

    @Test
    void sanitizeFileName_preserves_normal_filename() {
        assertEquals("report.pdf", DocumentServiceImpl.sanitizeFileName("report.pdf"));
    }

    @Test
    void sanitizeFileName_returns_unnamed_for_null() {
        assertEquals("unnamed", DocumentServiceImpl.sanitizeFileName(null));
    }

    @Test
    void sanitizeFileName_returns_unnamed_for_empty_after_sanitization() {
        assertEquals("unnamed", DocumentServiceImpl.sanitizeFileName("../.."));
        assertEquals("unnamed", DocumentServiceImpl.sanitizeFileName("/"));
    }

    @Test
    void sanitizeFileName_strips_null_bytes_and_control_chars() {
        String input = "evil\u0000.txt";
        String result = DocumentServiceImpl.sanitizeFileName(input);
        assertFalse(result.contains("\u0000"));
        assertEquals("evil.txt", result);
    }

    // ---- upload: UUID content path + sanitized title ----------------------

    @Test
    void upload_uses_uuid_in_content_path_not_original_filename() {
        // Use a traversal payload that still carries an allowed extension so
        // the allowlist check passes and we can assert on the content path.
        MultipartFile file = new MockMultipartFile(
                "file", "../../etc/secret.pdf", "application/octet-stream", new byte[]{1, 2, 3});

        Document saved = service.upload("tenant-1", file, "cat-1");

        ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
        verify(documentMapper).insert(captor.capture());
        Document persisted = captor.getValue();

        // The sanitized basename should be used as the title.
        assertEquals("secret.pdf", persisted.getTitle());

        // The content path must NOT contain the traversal payload verbatim.
        String contentPath = persisted.getContentPath();
        assertFalse(contentPath.contains(".."));
        assertFalse(contentPath.endsWith("/secret.pdf"));

        // It must be under the tenant-scoped prefix and contain a UUID.
        assertTrue(contentPath.startsWith("minio://knowledge-docs/tenant-1/"));
        String storedName = contentPath.substring("minio://knowledge-docs/tenant-1/".length());
        // UUID (36 chars incl. dashes) + "_" + sanitized basename
        assertTrue(storedName.length() > 36, "stored name should include a UUID prefix");
        int firstUnderscore = storedName.indexOf('_');
        String uuidPart = storedName.substring(0, firstUnderscore);
        assertDoesNotThrow(() -> java.util.UUID.fromString(uuidPart),
                "content path should be prefixed with a valid UUID");
        assertTrue(storedName.endsWith("_secret.pdf"));

        // Returned instance is the same one persisted.
        assertSame(persisted, saved);
    }

    @Test
    void upload_sets_format_from_extension() {
        MultipartFile file = new MockMultipartFile(
                "file", "Guide.PDF", "application/pdf", new byte[]{1});

        Document saved = service.upload("t1", file, "c1");

        assertEquals("pdf", saved.getFormat());
    }

    // ---- upload: extension allowlist --------------------------------------

    @Test
    void upload_rejects_disallowed_extension() {
        MultipartFile file = new MockMultipartFile(
                "file", "malware.exe", "application/octet-stream", new byte[]{1});

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.upload("t1", file, "c1"));
        assertTrue(ex.getMessage().contains("exe"));
        verify(documentMapper, never()).insert(any(Document.class));
        verify(cacheService, never()).evict(anyString(), anyString(), anyString());
    }

    @Test
    void upload_rejects_missing_extension() {
        MultipartFile file = new MockMultipartFile(
                "file", "noextension", "application/octet-stream", new byte[]{1});

        assertThrows(IllegalArgumentException.class,
                () -> service.upload("t1", file, "c1"));
        verify(documentMapper, never()).insert(any(Document.class));
        verify(cacheService, never()).evict(anyString(), anyString(), anyString());
    }

    @Test
    void upload_accepts_all_allowed_extensions() {
        String[] allowed = {"pdf", "docx", "doc", "txt", "md", "pptx", "xlsx", "html", "epub"};
        for (String ext : allowed) {
            MultipartFile file = new MockMultipartFile(
                    "file", "doc." + ext, "application/octet-stream", new byte[]{1});
            assertDoesNotThrow(() -> service.upload("t1", file, "c1"),
                    "extension should be allowed: " + ext);
        }
        verify(documentMapper, times(allowed.length)).insert(any(Document.class));
    }

    // ---- D-182: CacheService integration ---------------------------------

    @Test
    void get_cacheMiss_invokesMapper_andCachesResult() {
        Document doc = new Document();
        doc.setId("doc-1");
        doc.setTenantId("t1");
        doc.setTitle("hello.pdf");
        when(documentMapper.selectById("doc-1")).thenReturn(doc);

        Document fetched = service.get("t1", "doc-1");

        assertNotNull(fetched);
        assertEquals("doc-1", fetched.getId());
        assertEquals("hello.pdf", fetched.getTitle());
        // D-182: get must go through cache getOrLoad (TTL 600s, cacheEmpty=true).
        // P0: the tenant is part of the cache key.
        verify(cacheService).getOrLoad(eq("knowledge:document"), eq("t1"), eq("doc-1"),
                any(Supplier.class), eq(600L), eq(true));
    }

    @Test
    void get_tenantMismatch_returnsNull() {
        Document doc = new Document();
        doc.setId("doc-2");
        doc.setTenantId("other-tenant");
        when(documentMapper.selectById("doc-2")).thenReturn(doc);

        Document fetched = service.get("t1", "doc-2");

        assertNull(fetched, "tenant mismatch must return null");
        verify(cacheService).getOrLoad(eq("knowledge:document"), eq("t1"), eq("doc-2"),
                any(Supplier.class), eq(600L), eq(true));
    }

    @Test
    void get_cacheHit_skipsMapper() {
        // D-182: when the cache returns a value, the mapper is never touched.
        Document cached = new Document();
        cached.setId("doc-ch");
        cached.setTenantId("t1");
        when(cacheService.getOrLoad(eq("knowledge:document"), eq("t1"), eq("doc-ch"),
                any(Supplier.class), eq(600L), eq(true)))
                .thenReturn(cached);

        Document fetched = service.get("t1", "doc-ch");

        assertSame(cached, fetched);
        verify(documentMapper, never()).selectById(any());
    }

    @Test
    void get_sameIdForTwoTenants_usesDistinctCacheKeys() {
        // P0 (租户串号): the same document id requested by two tenants must
        // produce two tenant-scoped getOrLoad calls — never a shared entry.
        Document doc = new Document();
        doc.setId("doc-shared");
        doc.setTenantId("t1");
        when(documentMapper.selectById("doc-shared")).thenReturn(doc);

        service.get("t1", "doc-shared");
        service.get("t2", "doc-shared");

        verify(cacheService).getOrLoad(eq("knowledge:document"), eq("t1"), eq("doc-shared"),
                any(Supplier.class), eq(600L), eq(true));
        verify(cacheService).getOrLoad(eq("knowledge:document"), eq("t2"), eq("doc-shared"),
                any(Supplier.class), eq(600L), eq(true));
    }

    @Test
    void upload_evictsCacheAfterInsert() {
        // D-182: upload should evict the potentially cached null entry for the
        // newly assigned document id (tenant-scoped) and the tenant's list cache.
        MultipartFile file = new MockMultipartFile(
                "file", "test.pdf", "application/octet-stream", new byte[]{1});

        service.upload("t1", file, "c1");

        verify(cacheService).evict(eq("knowledge:document"), eq("t1"), anyString());
        verify(cacheService).evictAll("knowledge:document:list");
    }

    @Test
    void delete_evictsCacheAfterDelete() {
        // D-182: delete should evict the cache entry (tenant-scoped).
        Document doc = new Document();
        doc.setId("doc-del");
        doc.setTenantId("t1");
        when(documentMapper.selectById("doc-del")).thenReturn(doc);

        service.delete("t1", "doc-del");

        verify(documentMapper).deleteById((java.io.Serializable) "doc-del");
        verify(cacheService).evict("knowledge:document", "t1", "doc-del");
        verify(cacheService).evictAll("knowledge:document:list");
    }

    @Test
    void delete_tenantMismatch_doesNotEvict() {
        // D-182: when the tenant doesn't match, nothing is deleted and no evict.
        Document doc = new Document();
        doc.setId("doc-del2");
        doc.setTenantId("other");
        when(documentMapper.selectById("doc-del2")).thenReturn(doc);

        service.delete("t1", "doc-del2");

        verify(documentMapper, never()).deleteById(any(java.io.Serializable.class));
        verify(cacheService, never()).evict(anyString(), anyString(), anyString());
    }

    // ---- list: tenant+category caching (60s) ------------------------------

    @Test
    void list_cachesByTenantAndCategory() {
        when(documentMapper.selectList(any())).thenReturn(List.of());

        service.list("t1", "cat-9");

        verify(cacheService).getOrLoad(eq("knowledge:document:list"), eq("t1"), eq("cat-9"),
                any(Supplier.class), eq(60L), eq(false));
    }

    @Test
    void list_nullCategoryNormalizedToAll() {
        when(documentMapper.selectList(any())).thenReturn(List.of());

        service.list("t1", null);

        verify(cacheService).getOrLoad(eq("knowledge:document:list"), eq("t1"), eq("all"),
                any(Supplier.class), eq(60L), eq(false));
    }

    @Test
    void list_sameCategoryForTwoTenants_usesDistinctCacheKeys() {
        when(documentMapper.selectList(any())).thenReturn(List.of());

        service.list("t1", "cat-1");
        service.list("t2", "cat-1");

        verify(cacheService).getOrLoad(eq("knowledge:document:list"), eq("t1"), eq("cat-1"),
                any(Supplier.class), eq(60L), eq(false));
        verify(cacheService).getOrLoad(eq("knowledge:document:list"), eq("t2"), eq("cat-1"),
                any(Supplier.class), eq(60L), eq(false));
    }

    @Test
    void list_returnsLoaderResult() {
        Document doc = new Document();
        doc.setId("d-l1");
        when(documentMapper.selectList(any())).thenReturn(List.of(doc));

        List<Document> result = service.list("t1", null);

        assertEquals(1, result.size());
        assertSame(doc, result.get(0));
    }
}
