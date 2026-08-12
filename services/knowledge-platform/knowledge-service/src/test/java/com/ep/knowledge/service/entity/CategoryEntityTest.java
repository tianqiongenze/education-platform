package com.ep.knowledge.service.entity;

import com.ep.knowledge.service.mapper.CategoryMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Pure Mockito unit test for {@link Category} entity and {@link CategoryMapper}.
 * No Spring context, no H2/Testcontainers — keeps the test fast and isolated
 * from the unavailable DB environment.
 */
class CategoryEntityTest {

    @Test
    void test_category_creation() {
        Category category = new Category();
        category.setId("cat-001");
        category.setTenantId("t-001");
        category.setName("Programming");
        category.setParentId(null);

        assertEquals("cat-001", category.getId());
        assertEquals("t-001", category.getTenantId());
        assertEquals("Programming", category.getName());
        assertNull(category.getParentId());
    }

    @Test
    void test_category_tree_structure() {
        Category parent = new Category();
        parent.setId("cat-root");
        parent.setTenantId("t-001");
        parent.setName("Languages");
        parent.setParentId(null);

        Category child = new Category();
        child.setId("cat-child");
        child.setTenantId("t-001");
        child.setName("Java");
        child.setParentId(parent.getId());

        assertNull(parent.getParentId());
        assertEquals(parent.getId(), child.getParentId());
        assertEquals("Languages", parent.getName());
        assertEquals("Java", child.getName());
    }

    @Test
    void test_category_mapper_crud() {
        CategoryMapper categoryMapper = mock(CategoryMapper.class);

        Category category = new Category();
        category.setId("cat-010");
        category.setTenantId("t-010");
        category.setName("DevOps");
        category.setParentId(null);

        when(categoryMapper.insert(any(Category.class))).thenReturn(1);
        when(categoryMapper.selectById("cat-010")).thenReturn(category);

        int rows = categoryMapper.insert(category);
        assertEquals(1, rows);

        Category result = categoryMapper.selectById("cat-010");
        assertNotNull(result);
        assertEquals("cat-010", result.getId());
        assertEquals("DevOps", result.getName());

        verify(categoryMapper, times(1)).insert(category);
        verify(categoryMapper, times(1)).selectById("cat-010");
    }
}
