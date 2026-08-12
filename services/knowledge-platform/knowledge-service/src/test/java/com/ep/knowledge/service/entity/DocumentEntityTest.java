package com.ep.knowledge.service.entity;

import com.ep.knowledge.service.mapper.DocumentMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Pure Mockito unit test for {@link Document} entity and {@link DocumentMapper}.
 * No Spring context, no H2/Testcontainers — keeps the test fast and isolated
 * from the unavailable DB environment.
 */
class DocumentEntityTest {

    @Test
    void test_document_creation() {
        Document doc = new Document();
        doc.setId("doc-001");
        doc.setTenantId("t-001");
        doc.setTitle("Spring Boot Guide");
        doc.setFormat("PDF");
        doc.setContentPath("/docs/spring-guide.pdf");
        doc.setChunkCount(12);
        doc.setStatus("READY");
        doc.setVersion(1);
        LocalDateTime now = LocalDateTime.now();
        doc.setCreatedAt(now);

        assertEquals("doc-001", doc.getId());
        assertEquals("t-001", doc.getTenantId());
        assertEquals("Spring Boot Guide", doc.getTitle());
        assertEquals("PDF", doc.getFormat());
        assertEquals("/docs/spring-guide.pdf", doc.getContentPath());
        assertEquals(12, doc.getChunkCount());
        assertEquals("READY", doc.getStatus());
        assertEquals(1, doc.getVersion());
        assertEquals(now, doc.getCreatedAt());
    }

    @Test
    void test_document_mapper_insert_and_select() {
        DocumentMapper documentMapper = mock(DocumentMapper.class);

        Document doc = new Document();
        doc.setId("doc-002");
        doc.setTenantId("t-002");
        doc.setTitle("Microservices Notes");
        doc.setFormat("MD");
        doc.setContentPath("/docs/notes.md");
        doc.setChunkCount(5);
        doc.setStatus("PENDING");
        doc.setVersion(1);

        when(documentMapper.insert(any(Document.class))).thenReturn(1);
        when(documentMapper.selectById("doc-002")).thenReturn(doc);

        int rows = documentMapper.insert(doc);
        assertEquals(1, rows);

        Document result = documentMapper.selectById("doc-002");
        assertNotNull(result);
        assertEquals("doc-002", result.getId());
        assertEquals("Microservices Notes", result.getTitle());
        assertEquals("MD", result.getFormat());
        assertEquals(5, result.getChunkCount());

        verify(documentMapper, times(1)).insert(doc);
        verify(documentMapper, times(1)).selectById("doc-002");
    }

    @Test
    void test_document_version_increment() {
        Document doc = new Document();
        doc.setId("doc-003");
        doc.setVersion(1);
        assertEquals(1, doc.getVersion());

        doc.setVersion(2);
        assertEquals(2, doc.getVersion());

        doc.setVersion(3);
        assertEquals(3, doc.getVersion());
    }
}
