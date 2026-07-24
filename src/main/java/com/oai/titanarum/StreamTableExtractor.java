package com.oai.titanarum;

import org.apache.pdfbox.text.TextPosition;
import java.util.*;

/**
 * Borderless ("stream") table extraction: real glyph positions, no rulings, no tags.
 * Detect-then-extract with a hard gridness gate. Precision over recall; every stage is
 * work-budgeted and aborts (throwing {@link TableExtractor.RulingOverflowException}) rather
 * than degrade. See docs/superpowers/specs/2026-07-24-borderless-stream-table-extraction-design.md.
 */
final class StreamTableExtractor {

    static final int MAX_STREAM_GLYPHS = 300_000;
    static final int MAX_STREAM_WORDS  = 60_000;
    static final int MAX_STREAM_LINES  = 8_000;

    private StreamTableExtractor() {}

    static final class Word {
        float x0, y0, x1, y1;   // top-left-origin points
        String text;
        boolean numeric;
        float width()  { return x1 - x0; }
        float height() { return y1 - y0; }
        float cx()     { return (x0 + x1) * 0.5f; }
    }

    static final class Line {
        float yTop, yBot;
        final List<Word> words = new ArrayList<>();
    }

    static void enforceGlyphCap(int n) {
        if (n > MAX_STREAM_GLYPHS) throw new TableExtractor.RulingOverflowException();
    }

    static float medianFontSize(List<Word> words) {
        if (words.isEmpty()) return 12f;
        float[] h = new float[words.size()];
        for (int i = 0; i < h.length; i++) h[i] = Math.max(1f, words.get(i).height());
        Arrays.sort(h);
        return h[h.length / 2];
    }

    static List<Word> buildWords(List<TextPosition> glyphs) {
        enforceGlyphCap(glyphs.size());
        List<Word> out = new ArrayList<>();
        Word cur = null;
        float prevX1 = 0, prevBaseline = 0;
        for (TextPosition tp : glyphs) {
            String u = tp.getUnicode();
            if (u == null || u.isEmpty()) continue;
            float gx0 = tp.getXDirAdj();
            float gy0 = tp.getYDirAdj();                 // top of glyph, top-left origin
            float gh  = Math.max(1f, tp.getHeightDir());
            float gw  = tp.getWidthDirAdj();
            float fs  = Math.max(1f, tp.getFontSizeInPt() * tp.getTextMatrix().getScalingFactorY());
            float space = Math.max(tp.getWidthOfSpace(), 0.25f * fs);
            boolean whitespace = u.trim().isEmpty();
            boolean newLine = cur != null && Math.abs(gy0 - prevBaseline) > 0.5f * fs;
            boolean gap     = cur != null && (gx0 - prevX1) > 0.30f * space;
            if (cur == null || whitespace || newLine || gap) {
                if (cur != null) finishWord(cur, out);
                if (whitespace) { cur = null; continue; }
                cur = new Word();
                cur.x0 = gx0; cur.y0 = gy0; cur.x1 = gx0 + gw; cur.y1 = gy0 + gh;
                cur.text = u;
            } else {
                cur.x1 = gx0 + gw;
                cur.y0 = Math.min(cur.y0, gy0);
                cur.y1 = Math.max(cur.y1, gy0 + gh);
                cur.text += u;
            }
            prevX1 = gx0 + gw; prevBaseline = gy0;
            if (out.size() > MAX_STREAM_WORDS) throw new TableExtractor.RulingOverflowException();
        }
        if (cur != null) finishWord(cur, out);
        return out;
    }

    private static void finishWord(Word w, List<Word> out) {
        w.text = w.text.trim();
        if (w.text.isEmpty()) return;
        w.numeric = w.text.matches("[-+(]?[\\d.,%$)]+") && w.text.chars().anyMatch(Character::isDigit);
        out.add(w);
    }

    static List<Line> buildLines(List<Word> words, float medianFontSize) {
        List<Word> sorted = new ArrayList<>(words);
        sorted.sort(Comparator.comparingDouble(a -> a.y0));
        List<Line> lines = new ArrayList<>();
        for (Word w : sorted) {
            Line target = null;
            for (int i = lines.size() - 1; i >= 0 && i >= lines.size() - 3; i--) { // recent lines only
                Line ln = lines.get(i);
                float ov = Math.min(w.y1, ln.yBot) - Math.max(w.y0, ln.yTop);
                float minH = Math.min(w.height(), ln.yBot - ln.yTop);
                if (ov > 0.4f * Math.max(1f, minH)) { target = ln; break; }
            }
            if (target == null) {
                target = new Line(); target.yTop = w.y0; target.yBot = w.y1;
                lines.add(target);
                if (lines.size() > MAX_STREAM_LINES) throw new TableExtractor.RulingOverflowException();
            } else {
                target.yTop = Math.min(target.yTop, w.y0);
                target.yBot = Math.max(target.yBot, w.y1);
            }
            target.words.add(w);
        }
        lines.sort(Comparator.comparingDouble(l -> l.yTop));
        for (Line l : lines) l.words.sort(Comparator.comparingDouble(a -> a.x0));
        return lines;
    }
}
