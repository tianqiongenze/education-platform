package com.ep.knowledge.service;

import com.ep.knowledge.service.entity.Document;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface DocumentService {

    Document upload(String tenantId, MultipartFile file, String categoryId);

    Document get(String tenantId, String id);

    void delete(String tenantId, String id);

    List<Document> list(String tenantId, String categoryId);

    /**
     * Parse uploaded document via Tika, persist chunks. Skeleton implementation.
     */
    int parseAndChunk(String documentId);
}
