package com.ep.knowledge.service.impl;

import com.ep.knowledge.service.entity.Document;
import com.ep.knowledge.service.mapper.DocumentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Pure-Mockito unit tests for {@link DocumentServiceImpl} covering the file
 * upload sanitization (path-traversal fix), UUID-based content paths and the
 * extension allowlist. No Spring context is required.
 */
class DocumentServiceImplTest {

    private DocumentMapper documentMapper;
    private DocumentServiceImpl service;

    @BeforeEach
    void setUp() {
        documentMapper = mock(DocumentMapper.class);
        when(documentMapper.insert(any(Document.class))).thenReturn(1);
        service = new DocumentServiceImpl(documentMapper);
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
    }

    @Test
    void upload_rejects_missing_extension() {
        MultipartFile file = new MockMultipartFile(
                "file", "noextension", "application/octet-stream", new byte[]{1});

        assertThrows(IllegalArgumentException.class,
                () -> service.upload("t1", file, "c1"));
        verify(documentMapper, never()).insert(any(Document.class));
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
}
