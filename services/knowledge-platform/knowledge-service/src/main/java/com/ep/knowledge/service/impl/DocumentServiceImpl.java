package com.ep.knowledge.service.impl;

import com.ep.cache.CacheService;
import com.ep.knowledge.service.DocumentService;
import com.ep.knowledge.service.entity.Document;
import com.ep.knowledge.service.mapper.DocumentMapper;
import com.ep.knowledge.service.util.DocumentParser;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class DocumentServiceImpl implements DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentServiceImpl.class);

    // D-182: cache names and TTL for knowledge document metadata (600s).
    private static final String CACHE_DOCUMENT = "knowledge:document";
    private static final long DOCUMENT_TTL_SEC = 600;

    /**
     * Allowlist of file extensions permitted for upload. Any extension not in
     * this set is rejected with an {@link IllegalArgumentException}.
     */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "pdf", "docx", "doc", "txt", "md", "pptx", "xlsx", "html", "epub");

    private final DocumentMapper documentMapper;
    private final CacheService cacheService;

    public DocumentServiceImpl(DocumentMapper documentMapper, CacheService cacheService) {
        this.documentMapper = documentMapper;
        this.cacheService = cacheService;
    }

    @Override
    public Document upload(String tenantId, MultipartFile file, String categoryId) {
        String originalFilename = file.getOriginalFilename();
        String sanitized = sanitizeFileName(originalFilename);
        String extension = extensionOf(sanitized);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("Unsupported file format: " + extension);
        }

        // UUID-based stored name eliminates path traversal and collision risks;
        // the original (sanitized) name is preserved as the document title.
        String storedName = UUID.randomUUID().toString() + "_" + sanitized;

        Document doc = new Document();
        doc.setTenantId(tenantId);
        doc.setTitle(sanitized);
        doc.setFormat(extension);
        doc.setContentPath("minio://knowledge-docs/" + tenantId + "/" + storedName);
        doc.setChunkCount(0);
        doc.setStatus("UPLOADED");
        doc.setVersion(1);
        doc.setCreatedAt(LocalDateTime.now());
        documentMapper.insert(doc);
        // D-182: evict potentially cached null for this document id.
        cacheService.evict(CACHE_DOCUMENT, String.valueOf(doc.getId()));
        log.info("Uploaded document {} for tenant {}", doc.getId(), tenantId);
        return doc;
    }

    @Override
    public Document get(String tenantId, String id) {
        // D-182: cache read-only document metadata (600s, cacheEmpty=true).
        return cacheService.getOrLoad(CACHE_DOCUMENT, id,
                () -> getDirect(tenantId, id), DOCUMENT_TTL_SEC, true);
    }

    private Document getDirect(String tenantId, String id) {
        Document doc = documentMapper.selectById(id);
        if (doc == null || !tenantId.equals(doc.getTenantId())) {
            return null;
        }
        return doc;
    }

    @Override
    public void delete(String tenantId, String id) {
        Document doc = documentMapper.selectById(id);
        if (doc != null && tenantId.equals(doc.getTenantId())) {
            documentMapper.deleteById(id);
            // D-182: evict cache entry after delete.
            cacheService.evict(CACHE_DOCUMENT, id);
        }
    }

    @Override
    public List<Document> list(String tenantId, String categoryId) {
        return documentMapper.selectList(
                new LambdaQueryWrapper<Document>().eq(Document::getTenantId, tenantId));
    }

    @Override
    public int parseAndChunk(String documentId) {
        Document doc = documentMapper.selectById(documentId);
        if (doc == null) {
            log.warn("Parse-and-chunk: document {} not found", documentId);
            return 0;
        }

        // EP_DEV_ONLY guard: in dev/CI, return simulated single chunk.
        // Production path would fetch content from MinIO (doc.getContentPath()).
        String devOnly = System.getenv("EP_DEV_ONLY");
        boolean devMode = devOnly == null || devOnly.isBlank() || "true".equalsIgnoreCase(devOnly);

        if (devMode) {
            log.info("Parse-and-chunk (EP_DEV_ONLY): simulated 1 chunk for document {}", documentId);
            doc.setChunkCount(1);
            doc.setStatus("PARSED");
            doc.setUpdatedAt(LocalDateTime.now());
            documentMapper.updateById(doc);
            // D-182: evict cache entry after metadata change.
            cacheService.evict(CACHE_DOCUMENT, documentId);
            return 1;
        }

        // Production path: fetch content from MinIO and parse.
        // Content fetching is handled by a storage adapter (not yet wired in MVP).
        // When content is available, DocumentParser.parseAndChunk() produces the chunks.
        log.info("Parse-and-chunk invoked for document {} (format={})", documentId, doc.getFormat());
        // TODO: fetch content from MinIO via doc.getContentPath(), then:
        //   List<String> chunks = DocumentParser.parseAndChunk(content, doc.getFormat());
        //   doc.setChunkCount(chunks.size());
        //   persist chunks to vector store
        doc.setChunkCount(0);
        doc.setStatus("PARSED");
        doc.setUpdatedAt(LocalDateTime.now());
        documentMapper.updateById(doc);
        // D-182: evict cache entry after metadata change.
        cacheService.evict(CACHE_DOCUMENT, documentId);
        return 0;
    }

    /**
     * Sanitize a user-supplied filename to neutralize path-traversal and
     * injection vectors:
     * <ul>
     *   <li>strip any directory component (take the segment after the last {@code /})</li>
     *   <li>remove {@code ..} sequences</li>
     *   <li>remove null bytes and other control characters</li>
     *   <li>fall back to {@code "unnamed"} when the result is empty</li>
     * </ul>
     *
     * <p>This method is package-private so it can be unit-tested directly.
     *
     * @param filename the raw original filename from the upload (may be null)
     * @return a safe, non-empty basename suitable for storage
     */
    static String sanitizeFileName(String filename) {
        if (filename == null) {
            return "unnamed";
        }
        // Strip any directory component — handles both forward slashes and
        // any encoded variants that survived decoding upstream.
        int slash = filename.lastIndexOf('/');
        if (slash >= 0) {
            filename = filename.substring(slash + 1);
        }
        // Remove ".." sequences (after stripping the path component, a leading
        // ".." can still appear for inputs like "../../etc/passwd").
        filename = filename.replace("..", "");
        // Remove null bytes and other ASCII control characters (0x00-0x1F, 0x7F).
        filename = filename.replaceAll("[\\x00-\\x1F\\x7F]", "");
        if (filename.isEmpty()) {
            return "unnamed";
        }
        return filename;
    }

    /**
     * Extract the lowercase file extension (without the dot) from a filename.
     * Returns the empty string when no extension is present.
     */
    private static String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int idx = filename.lastIndexOf('.');
        if (idx < 0 || idx == filename.length() - 1) {
            return "";
        }
        return filename.substring(idx + 1).toLowerCase();
    }
}
