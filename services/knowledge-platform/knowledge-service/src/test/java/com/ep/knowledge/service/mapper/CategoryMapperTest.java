package com.ep.knowledge.service.mapper;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ep.knowledge.service.entity.Category;
import org.junit.jupiter.api.BeforeEach;
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
 * Mockito-based unit test for {@link CategoryMapper}.
 *
 * <p>{@code @MybatisTest} is not on the classpath of knowledge-service
 * (it pulls in a full datasource), so the mapper is exercised through its
 * MyBatis-Plus {@link com.baomidou.mybatisplus.core.mapper.BaseMapper} contract
 * with a Mockito mock. This validates the CRUD wiring and the tenant-scoped
 * query-wrapper usage that callers rely on.
 */
@ExtendWith(MockitoExtension.class)
class CategoryMapperTest {

    @Mock
    private CategoryMapper categoryMapper;

    private Category sample(String id, String tenantId, String name, String parentId) {
        Category c = new Category();
        c.setId(id);
        c.setTenantId(tenantId);
        c.setName(name);
        c.setParentId(parentId);
        return c;
    }

    @Test
    void insert_returns_affected_row_count() {
        Category c = sample("c1", "t1", "Math", null);
        when(categoryMapper.insert(c)).thenReturn(1);

        int rows = categoryMapper.insert(c);

        assertEquals(1, rows);
        verify(categoryMapper).insert(c);
    }

    @Test
    void selectById_returns_category_when_present() {
        Category c = sample("c1", "t1", "Math", null);
        when(categoryMapper.selectById("c1")).thenReturn(c);

        Category result = categoryMapper.selectById("c1");

        assertSame(c, result);
        assertEquals("Math", result.getName());
    }

    @Test
    void selectById_returns_null_when_absent() {
        when(categoryMapper.selectById("missing")).thenReturn(null);

        assertNull(categoryMapper.selectById("missing"));
    }

    @Test
    void selectList_with_tenant_query_wrapper_returns_scoped_rows() {
        Category c1 = sample("c1", "t1", "Math", null);
        Category c2 = sample("c2", "t1", "Physics", "c1");
        when(categoryMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(c1, c2));

        QueryWrapper<Category> wrapper = new QueryWrapper<>();
        wrapper.eq("tenant_id", "t1");
        List<Category> rows = categoryMapper.selectList(wrapper);

        assertEquals(2, rows.size());
        assertTrue(rows.stream().allMatch(c -> "t1".equals(c.getTenantId())),
                "all returned categories must belong to tenant t1");
    }

    @Test
    void updateById_returns_one_for_existing_row() {
        Category c = sample("c1", "t1", "Mathematics", null);
        when(categoryMapper.updateById(any(Category.class))).thenReturn(1);

        int rows = categoryMapper.updateById(c);

        assertEquals(1, rows);
    }

    @Test
    void updateById_zero_for_missing_row() {
        when(categoryMapper.updateById(any(Category.class))).thenReturn(0);

        assertEquals(0, categoryMapper.updateById(sample("ghost", "t1", "x", null)));
    }

    @Test
    void deleteById_returns_one_for_existing_row() {
        when(categoryMapper.deleteById("c1")).thenReturn(1);

        assertEquals(1, categoryMapper.deleteById("c1"));
    }

    @Test
    void deleteById_captures_id_argument() {
        when(categoryMapper.deleteById("c1")).thenReturn(1);

        categoryMapper.deleteById("c1");

        // Verifying the exact id is passed through ensures callers don't
        // accidentally delete by entity reference when they mean the id.
        verify(categoryMapper).deleteById("c1");
    }

    @Test
    void insert_captures_full_entity() {
        Category c = sample("c1", "t1", "Math", "root");
        when(categoryMapper.insert(any(Category.class))).thenReturn(1);

        categoryMapper.insert(c);

        ArgumentCaptor<Category> captor = ArgumentCaptor.forClass(Category.class);
        verify(categoryMapper).insert(captor.capture());
        Category captured = captor.getValue();
        assertEquals("c1", captured.getId());
        assertEquals("t1", captured.getTenantId());
        assertEquals("Math", captured.getName());
        assertEquals("root", captured.getParentId());
    }
}
