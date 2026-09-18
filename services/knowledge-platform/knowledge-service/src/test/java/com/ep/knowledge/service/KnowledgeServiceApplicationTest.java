package com.ep.knowledge.service;

import com.ep.cache.CacheService;
import com.ep.knowledge.service.entity.Document;
import com.ep.knowledge.service.mapper.DocumentMapper;
import com.ep.knowledge.service.DocumentService;
import com.ep.knowledge.service.impl.DocumentServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Pure unit test for {@link DocumentServiceImpl} using Mockito — same pattern as
 * {@code TenantServiceApplicationTest}. Avoids {@code @SpringBootTest} because
 * the service constructor-injects a MyBatis-Plus mapper that requires a real
 * {@code SqlSessionFactory} / datasource, which is unavailable in dev/CI.
 *
 * <p>See D-072: persistence-layer logic is covered by pure-Mockito entity tests
 * ({@link com.ep.knowledge.service.entity.DocumentEntityTest},
 * {@link com.ep.knowledge.service.entity.CategoryEntityTest}) and this service
 * test, without needing a Spring context.
 */
class KnowledgeServiceApplicationTest {

    private DocumentMapper documentMapper;
    private DocumentService documentService;

    @BeforeEach
    void setUp() {
        documentMapper = mock(DocumentMapper.class);
        // D-182: inject a cache-miss CacheService mock (invokes the loader directly).
        // P0 (租户未进 key): the service now uses the tenant-scoped 6-arg overload.
        CacheService cacheService = mock(CacheService.class);
        lenient().when(cacheService.getOrLoad(any(), any(), any(), any(Supplier.class), anyLong(), anyBoolean()))
                .thenAnswer(inv -> {
                    Supplier<?> loader = inv.getArgument(3);
                    return loader.get();
                });
        documentService = new DocumentServiceImpl(documentMapper, cacheService);
    }

    @Test
    void contextLoads() {
        assertNotNull(documentService);
    }

    @Test
    void uploadDocumentInsertsToMapper() {
        MultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", "content".getBytes());
        when(documentMapper.insert(any(Document.class))).thenReturn(1);

        Document uploaded = documentService.upload("tenant-1", file, null);

        assertNotNull(uploaded);
        assertEquals("tenant-1", uploaded.getTenantId());
        assertEquals("test.pdf", uploaded.getTitle());
        assertEquals("pdf", uploaded.getFormat());
        assertEquals("UPLOADED", uploaded.getStatus());
        assertEquals(1, uploaded.getVersion());
        verify(documentMapper, times(1)).insert(any(Document.class));
    }

    @Test
    void getDocumentReturnsFromMapper() {
        Document doc = new Document();
        doc.setId("d1");
        doc.setTenantId("tenant-1");
        doc.setTitle("Test");
        when(documentMapper.selectById("d1")).thenReturn(doc);

        Document result = documentService.get("tenant-1", "d1");

        assertNotNull(result);
        assertEquals("Test", result.getTitle());
    }

    @Test
    void getDocumentReturnsNullForWrongTenant() {
        Document doc = new Document();
        doc.setId("d1");
        doc.setTenantId("tenant-1");
        when(documentMapper.selectById("d1")).thenReturn(doc);

        Document result = documentService.get("tenant-2", "d1");

        assertNull(result);
    }

    @Test
    void deleteDocumentRemovesFromMapper() {
        Document doc = new Document();
        doc.setId("d1");
        doc.setTenantId("tenant-1");
        when(documentMapper.selectById("d1")).thenReturn(doc);

        documentService.delete("tenant-1", "d1");

        verify(documentMapper, times(1)).deleteById("d1");
    }
}
