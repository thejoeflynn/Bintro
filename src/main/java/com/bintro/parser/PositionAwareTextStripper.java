package com.bintro.parser;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link PDFTextStripper} subclass that captures every emitted text chunk
 * together with its page index and position. Used by
 * {@link ScenePositionIndex} to map screenplay scene headings and dialogue
 * lines to their pixel-accurate location on the rendered page.
 *
 * <p>Text output to the wrapped {@code Writer} is suppressed — only the
 * position data is retained.
 */
public class PositionAwareTextStripper extends PDFTextStripper {

    /** One chunk of text as emitted by PDFBox during extraction. */
    public record TextChunk(int page, float y, float x, String text) {
    }

    private final List<TextChunk> chunks = new ArrayList<>();

    public PositionAwareTextStripper() throws IOException {
        super();
        setSortByPosition(true);
    }

    /**
     * Runs the stripper over the document with output suppressed and
     * returns the captured chunk list.
     */
    public List<TextChunk> extract(PDDocument doc) throws IOException {
        chunks.clear();
        writeText(doc, new NullWriter());
        return chunks;
    }

    @Override
    protected void writeString(String text, List<TextPosition> positions) {
        // Intentionally do not call super — we want positions, not text output.
        if (positions == null || positions.isEmpty() || text == null || text.isEmpty()) {
            return;
        }
        TextPosition first = positions.get(0);
        // getCurrentPageNo() is 1-based — convert to 0-based for our index.
        int pageIdx = getCurrentPageNo() - 1;
        chunks.add(new TextChunk(pageIdx, first.getYDirAdj(), first.getXDirAdj(), text));
    }

    /** Writer that silently discards all output. */
    private static final class NullWriter extends Writer {
        @Override public void write(char[] cbuf, int off, int len) { }
        @Override public void flush() { }
        @Override public void close() { }
    }
}
