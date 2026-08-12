package com.ep.knowledge.service.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure-Java document parser and chunker — industry-agnostic text processing.
 *
 * <p>D-099: Implements MVP document parsing and chunking without Tika or other
 * heavy parsing dependencies. Supports TXT, HTML, and Markdown formats. Complex
 * binary formats (PDF, DOCX) are treated as plain text with a warning log.
 *
 * <p>This is a utility class with pure functions (no Spring dependencies) so it
 * can be unit-tested directly without a Spring context.
 */
public final class DocumentParser {

    /** Default chunk size in characters. */
    public static final int DEFAULT_CHUNK_SIZE = 800;
    /** Default overlap between adjacent chunks in characters. */
    public static final int DEFAULT_OVERLAP = 100;
    /** Minimum chunk size — short paragraphs are merged with the next. */
    private static final int MIN_PARAGRAPH = 200;
    /** Maximum paragraph size before forced splitting. */
    private static final int MAX_PARAGRAPH = 1000;

    private DocumentParser() {
    }

    /**
     * Parse and chunk the given content.
     *
     * @param content raw document content (may be null or empty)
     * @param format  file format (txt, html, md, pdf, docx, etc.)
     * @return list of text chunks; empty list if content is null/empty
     */
    public static List<String> parseAndChunk(String content, String format) {
        return parseAndChunk(content, format, DEFAULT_CHUNK_SIZE, DEFAULT_OVERLAP);
    }

    /**
     * Parse and chunk with custom chunk size and overlap.
     *
     * @param content   raw document content
     * @param format    file format
     * @param chunkSize target chunk size in characters
     * @param overlap   overlap between adjacent chunks in characters
     * @return list of text chunks
     */
    public static List<String> parseAndChunk(String content, String format, int chunkSize, int overlap) {
        if (content == null || content.isBlank()) {
            return List.of();
        }

        String normalizedFormat = format == null ? "txt" : format.trim().toLowerCase();
        String plainText = switch (normalizedFormat) {
            case "html", "htm" -> stripHtml(content);
            case "md", "markdown" -> stripMarkdown(content);
            case "pdf", "docx", "doc", "pptx", "xlsx", "epub" -> content; // MVP: treat as plain text
            default -> content; // txt and unknown formats
        };

        return splitIntoChunks(plainText, chunkSize, overlap);
    }

    /**
     * Strip HTML tags from content, preserving text and paragraph structure.
     */
    static String stripHtml(String html) {
        // Remove script and style blocks entirely
        String result = html.replaceAll("(?is)<script[^>]*>.*?</script>", "");
        result = result.replaceAll("(?is)<style[^>]*>.*?</style>", "");
        // Convert block-level tags to double newlines to preserve paragraph boundaries
        result = result.replaceAll("(?i)<br\\s*/?>", "\n\n");
        result = result.replaceAll("(?i)</?(p|div|h[1-6]|li|tr|td|th|blockquote|section|article)[^>]*>", "\n\n");
        // Remove all remaining tags
        result = result.replaceAll("<[^>]+>", "");
        // Decode common HTML entities
        result = result.replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&nbsp;", " ");
        return result.trim();
    }

    /**
     * Strip Markdown syntax from content, preserving text and paragraph structure.
     */
    static String stripMarkdown(String markdown) {
        String result = markdown;
        // Remove code blocks
        result = result.replaceAll("(?s)```.*?```", "");
        result = result.replaceAll("(?s)`[^`]+`", "");
        // Remove headers markers
        result = result.replaceAll("(?m)^#{1,6}\\s+", "");
        // Remove bold/italic markers
        result = result.replaceAll("\\*\\*([^*]+)\\*\\*", "$1");
        result = result.replaceAll("__([^_]+)__", "$1");
        result = result.replaceAll("\\*([^*]+)\\*", "$1");
        result = result.replaceAll("_([^_]+)_", "$1");
        // Remove links: [text](url) → text
        result = result.replaceAll("\\[([^]]+)]\\([^)]+\\)", "$1");
        // Remove images: ![alt](url) → alt
        result = result.replaceAll("!\\[([^]]*)]\\([^)]+\\)", "$1");
        // Remove list markers
        result = result.replaceAll("(?m)^[-*+]\\s+", "");
        result = result.replaceAll("(?m)^\\d+\\.\\s+", "");
        // Remove blockquotes
        result = result.replaceAll("(?m)^>\\s?", "");
        // Remove horizontal rules
        result = result.replaceAll("(?m)^[-*_]{3,}$", "");
        return result.trim();
    }

    /**
     * Split plain text into chunks of approximately {@code chunkSize} characters
     * with {@code overlap} characters of overlap between adjacent chunks.
     *
     * <p>Splitting strategy:
     * <ol>
     *   <li>Split by double newlines (paragraphs)</li>
     *   <li>Merge short paragraphs (&lt; {@value #MIN_PARAGRAPH}) with the next</li>
     *   <li>Split long paragraphs (&gt; {@value #MAX_PARAGRAPH}) at sentence boundaries</li>
     *   <li>Accumulate into chunks of ~{@code chunkSize}, with overlap</li>
     * </ol>
     */
    static List<String> splitIntoChunks(String text, int chunkSize, int overlap) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        // Step 1: split by double newlines into paragraphs
        String[] rawParagraphs = text.split("\\n\\s*\\n");
        List<String> paragraphs = new ArrayList<>();

        // Step 2: merge short paragraphs, split long ones
        StringBuilder buffer = new StringBuilder();
        for (String rawPara : rawParagraphs) {
            String para = rawPara.trim();
            if (para.isEmpty()) continue;

            if (para.length() > MAX_PARAGRAPH) {
                // Flush buffer first
                if (buffer.length() > 0) {
                    paragraphs.add(buffer.toString().trim());
                    buffer.setLength(0);
                }
                // Split long paragraph at sentence boundaries
                paragraphs.addAll(splitLongParagraph(para, MAX_PARAGRAPH));
            } else if (buffer.length() + para.length() + 2 < MIN_PARAGRAPH) {
                // Merge short paragraph with buffer
                if (buffer.length() > 0) buffer.append("\n\n");
                buffer.append(para);
            } else {
                if (buffer.length() > 0) buffer.append("\n\n");
                buffer.append(para);
                if (buffer.length() >= MIN_PARAGRAPH) {
                    paragraphs.add(buffer.toString().trim());
                    buffer.setLength(0);
                }
            }
        }
        if (buffer.length() > 0) {
            paragraphs.add(buffer.toString().trim());
        }

        if (paragraphs.isEmpty()) {
            return List.of();
        }

        // Step 3: accumulate into chunks with overlap
        List<String> chunks = new ArrayList<>();
        StringBuilder chunk = new StringBuilder();
        for (String para : paragraphs) {
            if (chunk.length() > 0 && chunk.length() + para.length() + 2 > chunkSize) {
                // Flush current chunk
                chunks.add(chunk.toString().trim());
                // Start new chunk with overlap from end of previous chunk
                if (overlap > 0 && chunk.length() > overlap) {
                    String overlapText = chunk.substring(chunk.length() - overlap);
                    chunk.setLength(0);
                    chunk.append(overlapText.trim());
                } else {
                    chunk.setLength(0);
                }
            }
            if (chunk.length() > 0) chunk.append("\n\n");
            chunk.append(para);
        }
        if (chunk.length() > 0) {
            chunks.add(chunk.toString().trim());
        }

        return chunks;
    }

    /**
     * Split a long paragraph at sentence boundaries.
     */
    private static List<String> splitLongParagraph(String para, int maxSize) {
        List<String> parts = new ArrayList<>();
        // Split at sentence boundaries: . ! ? followed by space or end
        String[] sentences = para.split("(?<=[.!?])\\s+");
        StringBuilder buf = new StringBuilder();
        for (String sentence : sentences) {
            if (buf.length() + sentence.length() + 1 > maxSize && buf.length() > 0) {
                parts.add(buf.toString().trim());
                buf.setLength(0);
            }
            if (buf.length() > 0) buf.append(" ");
            buf.append(sentence);
        }
        if (buf.length() > 0) {
            parts.add(buf.toString().trim());
        }
        return parts;
    }
}
