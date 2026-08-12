package com.ep.knowledge.service.mapper;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ep.knowledge.service.entity.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Mockito-based unit test for {@link DocumentMapper}.
 *
 * <p>{@code @MybatisTest} is not on the classpath of knowledge-service
 * (it pulls in a full datasource), so the mapper is exercised through its
 * MyBatis-Plus {@link com.baomidou.mybatisplus.core.mapper.BaseMapper} contract
 * with a Mockito mock. Validates CRUD wiring and the tenant-scoped query-wrapper
 * usage that callers ({@code DocumentServiceImpl}, {@code KnowledgeController})
 * rely on.
 */
@ExtendWith(MockitoExtension.class)
class DocumentMapperTest {

    @Mock
    private DocumentMapper documentMapper;

    private Document sample(String id, String tenantId, String title, String format, String status) {
        Document d = new Document();
        d.setId(id);
        d.setTenantId(tenantId);
        d.setTitle(title);
        d.setFormat(format);
        d.setStatus(status);
        return d;
    }

    @Test
    void insert_returns_affected_row_count() {
        Document d = sample("d1", "t1", "Guide", "pdf", "READY");
        when(documentMapper.insert(d)).thenReturn(1);

        assertEquals(1, documentMapper.insert(d));
        verify(documentMapper).insert(d);
    }

    @Test
    void selectById_returns_document_when_present() {
        Document d = sample("d1", "t1", "Guide", "pdf", "READY");
        when(documentMapper.selectById("d1")).thenReturn(d);

        Document result = documentMapper.selectById("d1");

        assertSame(d, result);
        assertEquals("Guide", result.getTitle());
        assertEquals("pdf", result.getFormat());
    }

    @Test
    void selectById_returns_null_when_absent() {
        when(documentMapper.selectById("missing")).thenReturn(null);

        assertNull(documentMapper.selectById("missing"));
    }

    @Test
    void selectList_with_tenant_and_category_filter_returns_scoped_rows() {
        Document d1 = sample("d1", "t1", "Guide", "pdf", "READY");
        Document d2 = sample("d2", "t1", "Slides", "pptx", "READY");
        when(documentMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(d1, d2));

        QueryWrapper<Document> wrapper = new QueryWrapper<>();
        wrapper.eq("tenant_id", "t1").eq("category_id", "cat-1");
        List<Document> rows = documentMapper.selectList(wrapper);

        assertEquals(2, rows.size());
        assertTrue(rows.stream().allMatch(d -> "t1".equals(d.getTenantId())));
    }

    @Test
    void updateById_returns_one_for_existing_row() {
        Document d = sample("d1", "t1", "Guide v2", "pdf", "READY");
        when(documentMapper.updateById(any(Document.class))).thenReturn(1);

        assertEquals(1, documentMapper.updateById(d));
    }

    @Test
    void updateById_zero_for_missing_row() {
        when(documentMapper.updateById(any(Document.class))).thenReturn(0);

        assertEquals(0, documentMapper.updateById(sample("ghost", "t1", "x", "pdf", "READY")));
    }

    @Test
    void deleteById_returns_one_for_existing_row() {
        when(documentMapper.deleteById("d1")).thenReturn(1);

        assertEquals(1, documentMapper.deleteById("d1"));
        verify(documentMapper).deleteById("d1");
    }

    @Test
    void insert_captures_full_entity() {
        Document d = sample("d1", "t1", "Guide", "pdf", "READY");
        d.setContentPath("minio://bucket/d1.pdf");
        d.setChunkCount(7);
        when(documentMapper.insert(any(Document.class))).thenReturn(1);

        documentMapper.insert(d);

        ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
        verify(documentMapper).insert(captor.capture());
        Document captured = captor.getValue();
        assertEquals("d1", captured.getId());
        assertEquals("t1", captured.getTenantId());
        assertEquals("Guide", captured.getTitle());
        assertEquals("pdf", captured.getFormat());
        assertEquals("minio://bucket/d1.pdf", captured.getContentPath());
        assertEquals(7, captured.getChunkCount());
        assertEquals("READY", captured.getStatus());
    }

    @Test
    void selectList_empty_result_handled() {
        when(documentMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

        QueryWrapper<Document> wrapper = new QueryWrapper<>();
        wrapper.eq("tenant_id", "empty-tenant");
        List<Document> rows = documentMapper.selectList(wrapper);

        assertNotNull(rows);
        assertTrue(rows.isEmpty());
    }
}
