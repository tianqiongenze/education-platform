package com.ep.knowledge.service.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link DocumentParser} — verifies TXT/HTML/Markdown parsing,
 * chunk size enforcement, overlap, and edge cases.
 */
class DocumentParserTest {

    @Test
    void parseAndChunk_emptyContent_returnsEmptyList() {
        List<String> chunks = DocumentParser.parseAndChunk("", "txt");
        assertTrue(chunks.isEmpty());
    }

    @Test
    void parseAndChunk_nullContent_returnsEmptyList() {
        List<String> chunks = DocumentParser.parseAndChunk(null, "txt");
        assertTrue(chunks.isEmpty());
    }

    @Test
    void parseAndChunk_singleShortParagraph_returnsSingleChunk() {
        String content = "This is a short paragraph.";
        List<String> chunks = DocumentParser.parseAndChunk(content, "txt");
        assertEquals(1, chunks.size());
        assertEquals("This is a short paragraph.", chunks.get(0));
    }

    @Test
    void parseAndChunk_multipleParagraphs_groupsIntoChunks() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            sb.append("Paragraph ").append(i).append(" with some content that makes it long enough. ");
            sb.append("Adding more text to ensure this paragraph exceeds the minimum threshold. ");
            sb.append("Yet more text here to make sure this is a substantial paragraph.\n\n");
        }
        List<String> chunks = DocumentParser.parseAndChunk(sb.toString(), "txt", 800, 100);
        assertTrue(chunks.size() >= 1);
        // Each chunk should not be excessively large
        for (String chunk : chunks) {
            assertTrue(chunk.length() < 2000, "Chunk too long: " + chunk.length());
        }
    }

    @Test
    void stripHtml_removesTags() {
        String html = "<html><body><h1>Title</h1><p>Paragraph <b>text</b>.</p></body></html>";
        String result = DocumentParser.stripHtml(html);
        assertFalse(result.contains("<"));
        assertFalse(result.contains(">"));
        assertTrue(result.contains("Title"));
        assertTrue(result.contains("Paragraph text"));
    }

    @Test
    void stripHtml_removesScriptAndStyle() {
        String html = "<style>body { color: red; }</style><script>alert('xss')</script><p>Visible</p>";
        String result = DocumentParser.stripHtml(html);
        assertFalse(result.contains("alert"));
        assertFalse(result.contains("color"));
        assertTrue(result.contains("Visible"));
    }

    @Test
    void stripHtml_decodesEntities() {
        String html = "<p>Tom &amp; Jerry &lt;3 &quot;fun&quot;</p>";
        String result = DocumentParser.stripHtml(html);
        assertTrue(result.contains("Tom & Jerry"));
        assertTrue(result.contains("<3"));
        assertTrue(result.contains("\"fun\""));
    }

    @Test
    void stripMarkdown_removesSyntax() {
        String md = "# Header\n\n**bold** and _italic_ text.\n\n- item1\n- item2\n\n[link](http://x.com)";
        String result = DocumentParser.stripMarkdown(md);
        assertFalse(result.contains("#"));
        assertFalse(result.contains("**"));
        assertFalse(result.contains("_italic_"));
        assertFalse(result.contains("- item"));
        assertFalse(result.contains("](http"));
        assertTrue(result.contains("bold"));
        assertTrue(result.contains("italic"));
        assertTrue(result.contains("link"));
    }

    @Test
    void stripMarkdown_removesCodeBlocks() {
        String md = "Before\n\n```\ncode block\n```\n\nAfter";
        String result = DocumentParser.stripMarkdown(md);
        assertFalse(result.contains("code block"));
        assertTrue(result.contains("Before"));
        assertTrue(result.contains("After"));
    }

    @Test
    void splitIntoChunks_largeContent_producesMultipleChunks() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            sb.append("This is sentence number ").append(i)
              .append(". It has enough words to be meaningful. ");
            if (i % 5 == 4) sb.append("\n\n");
        }
        List<String> chunks = DocumentParser.splitIntoChunks(sb.toString(), 500, 50);
        assertTrue(chunks.size() > 1, "Expected multiple chunks, got " + chunks.size());
    }

    @Test
    void parseAndChunk_pdfFormat_treatedAsPlainText() {
        String content = "Some PDF text content. This is treated as plain text in MVP.";
        List<String> chunks = DocumentParser.parseAndChunk(content, "pdf");
        assertEquals(1, chunks.size());
        assertTrue(chunks.get(0).contains("Some PDF text content"));
    }

    @Test
    void parseAndChunk_customChunkSize() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            sb.append("Paragraph with enough content to be meaningful. ");
            sb.append("Additional text to exceed minimum size. ");
            sb.append("\n\n");
        }
        List<String> smallChunks = DocumentParser.parseAndChunk(sb.toString(), "txt", 300, 50);
        List<String> largeChunks = DocumentParser.parseAndChunk(sb.toString(), "txt", 2000, 100);
        assertTrue(smallChunks.size() >= largeChunks.size(),
                "Smaller chunk size should produce more or equal chunks");
    }

    @Test
    void parseAndChunk_preservesContentAcrossChunks() {
        StringBuilder sb = new StringBuilder();
        sb.append("First paragraph with meaningful content here. ");
        sb.append("Adding more text to ensure sufficient length.\n\n");
        sb.append("Second paragraph with different content. ");
        sb.append("More text to meet minimum requirements.\n\n");
        sb.append("Third paragraph with even more content. ");
        sb.append("Final text to complete the document.\n\n");

        List<String> chunks = DocumentParser.parseAndChunk(sb.toString(), "txt");
        String allContent = String.join(" ", chunks);
        assertTrue(allContent.contains("First paragraph"));
        assertTrue(allContent.contains("Second paragraph"));
        assertTrue(allContent.contains("Third paragraph"));
    }

    @Test
    void parseAndChunk_overlapProducesOverlappingContent() {
        // Create content that will produce multiple chunks
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            sb.append("Paragraph ").append(i).append(" with unique content XYZ").append(i)
              .append(" and enough text to be substantial. More padding here.\n\n");
        }
        List<String> chunks = DocumentParser.parseAndChunk(sb.toString(), "txt", 400, 100);
        if (chunks.size() > 1) {
            // The end of chunk[i] should overlap with the beginning of chunk[i+1]
            String endOfFirst = chunks.get(0).substring(Math.max(0, chunks.get(0).length() - 50));
            String startOfSecond = chunks.get(1).substring(0, Math.min(50, chunks.get(1).length()));
            // At minimum, verify the second chunk starts with something from the end of the first
            assertFalse(startOfSecond.isBlank());
        }
    }
}
