package com.ep.knowledge.service.controller;

import com.ep.web.common.ForbiddenException;
import com.ep.knowledge.service.entity.Document;
import com.ep.knowledge.service.mapper.CategoryMapper;
import com.ep.knowledge.service.DocumentService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Pure unit test for {@link KnowledgeController} role authorization.
 * Verifies that document upload requires teacher/admin role and that the
 * anonymous role is denied.
 */
class KnowledgeControllerAuthzTest {

    private DocumentService documentService;
    private CategoryMapper categoryMapper;
    private KnowledgeController controller;

    @BeforeEach
    void setUp() throws Exception {
        documentService = mock(DocumentService.class);
        categoryMapper = mock(CategoryMapper.class);
        controller = new KnowledgeController(documentService, categoryMapper);
        // Pure unit test — Spring @Value is not resolved, so inject the
        // role arrays manually to match the production defaults.
        setField(controller, "readRoles", "student,teacher,admin,platform_admin".split(","));
        setField(controller, "writeRoles", "teacher,admin,platform_admin".split(","));
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }

    private HttpServletRequest requestWithRole(String role) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("X-Role")).thenReturn(role);
        return req;
    }

    @Test
    void upload_allowedForTeacher() {
        HttpServletRequest req = requestWithRole("teacher");
        MultipartFile file = mock(MultipartFile.class);
        Document doc = new Document();
        doc.setId("d1");
        when(documentService.upload("t1", file, null)).thenReturn(doc);

        ResponseEntity<Document> result = controller.upload("t1", file, null, req);

        assertNotNull(result.getBody());
        assertEquals("d1", result.getBody().getId());
        verify(documentService).upload("t1", file, null);
    }

    @Test
    void upload_allowedForAdmin() {
        HttpServletRequest req = requestWithRole("admin");
        MultipartFile file = mock(MultipartFile.class);
        Document doc = new Document();
        when(documentService.upload("t1", file, null)).thenReturn(doc);

        ResponseEntity<Document> result = controller.upload("t1", file, null, req);

        assertNotNull(result.getBody());
        verify(documentService).upload("t1", file, null);
    }

    @Test
    void upload_deniedForStudent() {
        HttpServletRequest req = requestWithRole("student");
        MultipartFile file = mock(MultipartFile.class);
        assertThrows(ForbiddenException.class,
                () -> controller.upload("t1", file, null, req));
        verify(documentService, never()).upload(any(), any(), any());
    }

    @Test
    void upload_deniedForAnonymous() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("X-Role")).thenReturn(null);
        MultipartFile file = mock(MultipartFile.class);
        assertThrows(ForbiddenException.class,
                () -> controller.upload("t1", file, null, req));
        verify(documentService, never()).upload(any(), any(), any());
    }

    @Test
    void delete_allowedForTeacher() {
        HttpServletRequest req = requestWithRole("teacher");
        ResponseEntity<Void> result = controller.delete("t1", "d1", req);
        assertEquals(204, result.getStatusCode().value());
        verify(documentService).delete("t1", "d1");
    }

    @Test
    void delete_deniedForAnonymous() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("X-Role")).thenReturn(null);
        assertThrows(ForbiddenException.class,
                () -> controller.delete("t1", "d1", req));
        verify(documentService, never()).delete(any(), any());
    }

    @Test
    void get_allowedForStudent() {
        HttpServletRequest req = requestWithRole("student");
        Document doc = new Document();
        doc.setId("d1");
        when(documentService.get("t1", "d1")).thenReturn(doc);

        ResponseEntity<Document> result = controller.get("t1", "d1", req);

        assertNotNull(result.getBody());
        verify(documentService).get("t1", "d1");
    }

    @Test
    void get_deniedForAnonymous() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("X-Role")).thenReturn(null);
        assertThrows(ForbiddenException.class,
                () -> controller.get("t1", "d1", req));
        verify(documentService, never()).get(any(), any());
    }
}
