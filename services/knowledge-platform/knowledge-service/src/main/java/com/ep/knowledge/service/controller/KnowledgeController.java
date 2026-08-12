package com.ep.knowledge.service.controller;

import com.ep.web.common.RoleChecker;
import com.ep.knowledge.service.entity.Category;
import com.ep.knowledge.service.entity.Document;
import com.ep.knowledge.service.mapper.CategoryMapper;
import com.ep.knowledge.service.DocumentService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/v1/kb")
@Tag(name = "知识库管理", description = "文档上传、查询、删除、分类管理")
public class KnowledgeController {

    /** Configurable role arrays — defaults preserve education industry behavior. */
    @Value("${ep.knowledge.roles.read:student,teacher,admin,platform_admin}")
    private String[] readRoles;

    @Value("${ep.knowledge.roles.write:teacher,admin,platform_admin}")
    private String[] writeRoles;

    private final DocumentService documentService;
    private final CategoryMapper categoryMapper;

    public KnowledgeController(DocumentService documentService, CategoryMapper categoryMapper) {
        this.documentService = documentService;
        this.categoryMapper = categoryMapper;
    }

    @PostMapping("/documents")
    public ResponseEntity<Document> upload(@RequestHeader("X-Tenant-Id") String tenantId,
                                           @RequestParam("file") MultipartFile file,
                                           @RequestParam(value = "categoryId", required = false) String categoryId,
                                           HttpServletRequest request) {
        RoleChecker.requireRole(request, writeRoles);
        return ResponseEntity.ok(documentService.upload(tenantId, file, categoryId));
    }

    @GetMapping("/documents/{id}")
    public ResponseEntity<Document> get(@RequestHeader("X-Tenant-Id") String tenantId,
                                        @PathVariable String id,
                                        HttpServletRequest request) {
        RoleChecker.requireRole(request, readRoles);
        Document doc = documentService.get(tenantId, id);
        return doc == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(doc);
    }

    @DeleteMapping("/documents/{id}")
    public ResponseEntity<Void> delete(@RequestHeader("X-Tenant-Id") String tenantId,
                                       @PathVariable String id,
                                       HttpServletRequest request) {
        RoleChecker.requireRole(request, writeRoles);
        documentService.delete(tenantId, id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/categories")
    public ResponseEntity<List<Category>> categories(@RequestHeader("X-Tenant-Id") String tenantId,
                                                     HttpServletRequest request) {
        RoleChecker.requireRole(request, readRoles);
        return ResponseEntity.ok(categoryMapper.selectList(
                new LambdaQueryWrapper<Category>().eq(Category::getTenantId, tenantId)));
    }
}
