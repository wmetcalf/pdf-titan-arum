package com.oai.titanarum.bakeoff;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads expected ("ground truth") tables from the corpus fixtures so extraction
 * output can be scored against something real. Two source formats are supported:
 *
 * <ul>
 *   <li>Plain RFC4180 CSV files under {@code csv/*.csv}.</li>
 *   <li>ICDAR 2013 "structure" XML files ({@code *-str.xml}) which describe cells
 *       by (row, col) position rather than by literal grid text.</li>
 * </ul>
 *
 * This class does no extraction of its own -- it only loads expectations.
 */
public final class GroundTruth {

    private GroundTruth() {
    }

    /**
     * One ICDAR-2013 {@code <cell>} as declared in a {@code *-str.xml}: its logical span
     * ({@code startRow..endRow} x {@code startCol..endCol}, already shifted by its region's
     * row/col-increment so it lands where it does in {@link Table#rows()}), its content, the
     * 1-based PDF page its region declares, and its {@code <bounding-box>} rectangle.
     *
     * <p><b>Coordinate convention (important, and different from ours):</b> ICDAR bounding boxes
     * are PDF user-space points with the origin at the page's BOTTOM-left and y increasing UPWARD,
     * so {@code y1 < y2} means y1 is the box's BOTTOM and y2 its TOP. Everything on our side
     * ({@code TableExtractor.TableHit#bbox}, {@code TextPosition#getYDirAdj}) is TOP-left origin
     * with y increasing DOWNWARD. Converting one to the other therefore requires the page height
     * and flips the two y values: {@code ourTop = pageHeight - icdarY2}, {@code ourBottom =
     * pageHeight - icdarY1}. Callers must do that conversion; this record stores the raw ICDAR
     * numbers verbatim so the conversion happens in exactly one place downstream.
     *
     * <p>{@code hasBox} is false when a cell carried no {@code <bounding-box>} element at all (none
     * do in the shipped corpus -- all 14530 cells have one -- but a malformed file could omit it,
     * and a geometry-consuming caller must be able to tell "no box" from "box at the origin").
     */
    public record Cell(int startRow, int startCol, int endRow, int endCol,
                        String text, int page,
                        float x1, float y1, float x2, float y2, boolean hasBox) {
    }

    /**
     * A single expected table, as rows of cell text, plus (for ICDAR-sourced tables only) the
     * declared {@link Cell}s behind that text.
     *
     * <p>{@code rows} is the EXPANDED text grid: a cell spanning several rows/columns has its
     * content REPEATED into every position it covers (see {@link #scanCellsInto}). That expansion
     * is what the exact-cell metric has always scored against and is deliberately unchanged.
     * {@code cells} is the un-expanded truth -- one entry per declared cell, carrying its span --
     * and is what the official ICDAR adjacency definition needs, because that definition treats a
     * spanning cell as ONE cell (so it emits no relation from a spanning cell to itself, and emits
     * at most one relation per ordered pair of cells). {@code cells} is EMPTY for CSV-sourced
     * ground truth, which has no span information to recover; callers must fall back to deriving
     * 1x1 cells from {@code rows} in that case (see {@code TableScore#gridCellsFromRows}).
     */
    public record Table(List<List<String>> rows, List<Cell> cells) {

        /** CSV-sourced / hand-built table: text grid only, no declared cell geometry or spans. */
        public Table(List<List<String>> rows) {
            this(rows, List.of());
        }

        public int rowCount() {
            return rows.size();
        }

        public int colCount() {
            int max = 0;
            for (List<String> row : rows) {
                max = Math.max(max, row.size());
            }
            return max;
        }
    }

    // ---------------------------------------------------------------- CSV --

    /**
     * Parses a strict RFC4180 CSV file into a grid of rows x cols.
     * Handles double-quoted fields, {@code ""} as an escaped literal quote, and
     * quoted fields containing embedded newlines (a field is not "done" just
     * because a physical line ended -- only an unescaped closing quote or the
     * true end of a record ends it).
     */
    public static Table fromCsv(Path csv) throws IOException {
        String text = Files.readString(csv, StandardCharsets.UTF_8);
        List<List<String>> rows = new ArrayList<>();
        List<String> currentRow = new ArrayList<>();
        StringBuilder field = new StringBuilder();

        boolean inQuotes = false;
        boolean sawAnyFieldContentInRow = false;
        int i = 0;
        int n = text.length();

        while (i < n) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < n && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i += 2;
                        continue;
                    } else {
                        inQuotes = false;
                        i++;
                        continue;
                    }
                } else if (c == '\n' || c == '\r') {
                    // A literal newline embedded inside a quoted field is, in
                    // this corpus, a physical line-wrap artifact (a cell's
                    // text was wrapped to fit a fixed display width when the
                    // fixture was authored) rather than a semantic paragraph
                    // break -- see csv/AnimalSounds.csv, where a single
                    // scientific-name token is wrapped across two physical
                    // lines with no space at the break. We drop it rather
                    // than either keeping it or turning it into a space, so
                    // the reconstructed cell text matches the single
                    // contiguous word/value the fixture actually encodes.
                    i++;
                    continue;
                } else {
                    field.append(c);
                    i++;
                    continue;
                }
            } else {
                if (c == '"') {
                    inQuotes = true;
                    sawAnyFieldContentInRow = true;
                    i++;
                    continue;
                } else if (c == ',') {
                    currentRow.add(field.toString());
                    field.setLength(0);
                    sawAnyFieldContentInRow = true;
                    i++;
                    continue;
                } else if (c == '\r') {
                    // Swallow bare CR; CRLF is handled by the following \n case.
                    i++;
                    continue;
                } else if (c == '\n') {
                    currentRow.add(field.toString());
                    field.setLength(0);
                    rows.add(currentRow);
                    currentRow = new ArrayList<>();
                    sawAnyFieldContentInRow = false;
                    i++;
                    continue;
                } else {
                    field.append(c);
                    sawAnyFieldContentInRow = true;
                    i++;
                    continue;
                }
            }
        }
        // Flush a trailing field/row that wasn't newline-terminated (e.g. no
        // trailing newline at EOF, or the file ends mid-unquoted-field).
        if (field.length() > 0 || sawAnyFieldContentInRow || !currentRow.isEmpty()) {
            currentRow.add(field.toString());
            rows.add(currentRow);
        }
        return new Table(rows);
    }

    // --------------------------------------------------------- ICDAR XML --

    // Deliberately tolerant: some copies of the ICDAR 2013 structure XML in the
    // wild have unclosed <cell> elements, mix single/double attribute quoting,
    // and are not well-formed enough to trust to a strict DOM/SAX parser. We
    // scan for <table>/<region>/<cell> start tags and <content> bodies with
    // regexes instead of parsing the file as XML.
    private static final Pattern TABLE_START = Pattern.compile("<table\\b");
    private static final Pattern REGION_START = Pattern.compile(
            "<region\\b([^>]*)>", Pattern.DOTALL);
    private static final Pattern CELL_START = Pattern.compile(
            "<cell\\b([^>]*)>", Pattern.DOTALL);
    private static final Pattern CONTENT = Pattern.compile(
            "<content>(.*?)</content>", Pattern.DOTALL);
    private static final Pattern BOUNDING_BOX = Pattern.compile(
            "<bounding-box\\b([^>]*)>", Pattern.DOTALL);
    private static final Pattern ATTR = Pattern.compile(
            "(\\S+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')");

    /**
     * Parses an ICDAR-2013-style {@code *-str.xml} structure file. A file may
     * describe multiple tables ({@code <table>} elements); each becomes one
     * {@link Table} in the returned list. Within a table, cells may be split
     * across multiple {@code <region>} blocks (e.g. side-by-side column
     * groups); a region's {@code col-increment}/{@code row-increment} is
     * added to its cells' {@code start-col}/{@code start-row} so the cells
     * land in the right place in the merged grid.
     */
    public static List<Table> fromIcdarStructureXml(Path strXml) throws IOException {
        String text = Files.readString(strXml, StandardCharsets.UTF_8);
        List<Table> tables = new ArrayList<>();

        // Split the document into per-<table> chunks by locating each
        // <table ...> start tag; the chunk runs until the next one (or EOF).
        List<Integer> tableStarts = new ArrayList<>();
        Matcher tm = TABLE_START.matcher(text);
        while (tm.find()) {
            tableStarts.add(tm.start());
        }
        if (tableStarts.isEmpty()) {
            // No <table> wrapper at all -- treat the whole document as one table.
            tables.add(parseTableChunk(text));
            return tables;
        }
        for (int t = 0; t < tableStarts.size(); t++) {
            int start = tableStarts.get(t);
            int end = (t + 1 < tableStarts.size()) ? tableStarts.get(t + 1) : text.length();
            String chunk = text.substring(start, end);
            Table table = parseTableChunk(chunk);
            if (table.rowCount() > 0) {
                tables.add(table);
            }
        }
        return tables;
    }

    private static Table parseTableChunk(String chunk) {
        // Split the table chunk into per-<region> pieces so we can apply each
        // region's row/col increment. If there's no <region> wrapper, treat
        // the whole chunk as a single implicit region with zero increments.
        Matcher rm = REGION_START.matcher(chunk);
        List<Integer> regionStarts = new ArrayList<>();
        List<String> regionAttrs = new ArrayList<>();
        while (rm.find()) {
            regionStarts.add(rm.end());
            regionAttrs.add(rm.group(1));
        }

        Map<Long, String> grid = new LinkedHashMap<>();
        List<Cell> cells = new ArrayList<>();
        int maxRow = -1;
        int maxCol = -1;

        if (regionStarts.isEmpty()) {
            // No region wrapper: scan the whole chunk as one region, zero increment. No <region>
            // means no page attribute either -- page 0 is the "unknown page" sentinel here (real
            // ICDAR pages are 1-based), and a geometry-consuming caller must skip such cells.
            int[] mm = scanCellsInto(chunk, 0, 0, 0, grid, cells);
            maxRow = Math.max(maxRow, mm[0]);
            maxCol = Math.max(maxCol, mm[1]);
        } else {
            for (int r = 0; r < regionStarts.size(); r++) {
                int bodyStart = regionStarts.get(r);
                int bodyEnd = (r + 1 < regionStarts.size()) ? findRegionEnd(chunk, regionStarts, r) : chunk.length();
                String body = chunk.substring(bodyStart, bodyEnd);
                Map<String, String> attrs = parseAttrs(regionAttrs.get(r));
                int colInc = parseIntAttr(attrs, "col-increment", 0);
                int rowInc = parseIntAttr(attrs, "row-increment", 0);
                int page = parseIntAttr(attrs, "page", 0);
                int[] mm = scanCellsInto(body, rowInc, colInc, page, grid, cells);
                maxRow = Math.max(maxRow, mm[0]);
                maxCol = Math.max(maxCol, mm[1]);
            }
        }

        if (maxRow < 0 || maxCol < 0) {
            return new Table(List.of());
        }

        List<List<String>> rows = new ArrayList<>(maxRow + 1);
        for (int r = 0; r <= maxRow; r++) {
            List<String> row = new ArrayList<>(maxCol + 1);
            for (int c = 0; c <= maxCol; c++) {
                row.add(grid.getOrDefault(key(r, c), ""));
            }
            rows.add(row);
        }
        return new Table(rows, List.copyOf(cells));
    }

    /** Finds where the next region's body should stop -- just reuses the next start's tag start. */
    private static int findRegionEnd(String chunk, List<Integer> regionStarts, int r) {
        // regionStarts holds the END of the <region ...> tag (i.e. body start).
        // The next region's tag start is somewhere before its recorded body
        // start; walking back to the last '<region' before it is unnecessary
        // since our body just needs to stop before the next region's cells
        // begin -- using the next region's body-start position as the cutoff
        // is safe because that only trims a few bytes of the next tag's own
        // "<region ...>" text, which contains no <cell> matches anyway.
        return regionStarts.get(r + 1);
    }

    /**
     * Scans every {@code <cell ...>} start tag in {@code body} (regardless of
     * whether it is ever closed) and records its content at
     * (start-row+rowInc, start-col+colInc), expanding to any end-row/end-col
     * span by repeating the content across the spanned cells. Returns
     * {maxRowSeen, maxColSeen}.
     *
     * <p>Also appends one {@link Cell} per declared cell to {@code cellsOut} -- the UN-expanded
     * view (span kept as a span, not repeated), carrying the cell's {@code <bounding-box>} and its
     * region's declared 1-based {@code page}. The expanded {@code grid} is untouched in shape or
     * content by this addition; {@code cellsOut} is purely additive information that was
     * previously discarded.
     */
    private static int[] scanCellsInto(String body, int rowInc, int colInc, int page,
                                        Map<Long, String> grid, List<Cell> cellsOut) {
        int maxRow = -1;
        int maxCol = -1;
        Matcher cm = CELL_START.matcher(body);
        List<Integer> cellTagEnds = new ArrayList<>();
        List<String> cellAttrsList = new ArrayList<>();
        while (cm.find()) {
            cellTagEnds.add(cm.end());
            cellAttrsList.add(cm.group(1));
        }
        for (int i = 0; i < cellTagEnds.size(); i++) {
            int segStart = cellTagEnds.get(i);
            int segEnd = (i + 1 < cellTagEnds.size()) ? cellTagEnds.get(i + 1) : body.length();
            String segment = body.substring(segStart, segEnd);

            Map<String, String> attrs = parseAttrs(cellAttrsList.get(i));
            // A NEGATIVE declared start-row/start-col is LEGAL in this corpus and must not be
            // confused with a missing one. The region's own row-increment/col-increment rebases the
            // cell coordinates, and the official evaluator adds the increment BEFORE placing the
            // cell (dataset-tools Table.addCell: `startRow += rowIncrement; endRow += rowIncrement;`
            // and only then setCell(c, r, ...)), so a cell declared at start-row='-1' inside a
            // <region row-increment='1'> lands on row 0 and is a perfectly ordinary header cell.
            // Rejecting the raw value dropped two real cells of us-019's first table -- the
            // "Variable"/"Assumption" header row -- and with them 3 ground-truth relations, which was
            // the entire residual disagreement between this implementation, the independent Python
            // port and the official tool's own printed output (all three now agree at 25,320).
            // ABSENT is a sentinel no coordinate can take, so "attribute not present (or
            // unparseable)" and "attribute present and negative" stay distinguishable.
            final int absent = Integer.MIN_VALUE;
            int startRow = parseIntAttr(attrs, "start-row", absent);
            int startCol = parseIntAttr(attrs, "start-col", absent);
            if (startRow == absent || startCol == absent) {
                continue; // malformed/unusable cell, skip rather than corrupt the grid
            }
            int endRow = parseIntAttr(attrs, "end-row", startRow);
            int endCol = parseIntAttr(attrs, "end-col", startCol);

            String content = "";
            Matcher contentM = CONTENT.matcher(segment);
            if (contentM.find()) {
                content = decodeXmlEntities(contentM.group(1));
            }

            int r0 = startRow + rowInc;
            int c0 = startCol + colInc;
            int r1 = Math.max(endRow, startRow) + rowInc;
            int c1 = Math.max(endCol, startCol) + colInc;
            if (r0 < 0 || c0 < 0) {
                // Still outside the grid AFTER the region's rebase. No file in the shipped corpus
                // reaches this (the official tool would throw IndexOutOfBoundsException from
                // setCell's `cellMatrix.get(row)` if one did, i.e. it has no defined behaviour here),
                // so skipping is the conservative reading for a hostile or broken annotation.
                continue;
            }

            float bx1 = 0f, by1 = 0f, bx2 = 0f, by2 = 0f;
            boolean hasBox = false;
            Matcher bbM = BOUNDING_BOX.matcher(segment);
            if (bbM.find()) {
                Map<String, String> bb = parseAttrs(bbM.group(1));
                Float ax1 = parseFloatAttr(bb, "x1");
                Float ay1 = parseFloatAttr(bb, "y1");
                Float ax2 = parseFloatAttr(bb, "x2");
                Float ay2 = parseFloatAttr(bb, "y2");
                if (ax1 != null && ay1 != null && ax2 != null && ay2 != null) {
                    // Normalize so x1<=x2 and y1<=y2 regardless of how the file ordered them.
                    bx1 = Math.min(ax1, ax2);
                    bx2 = Math.max(ax1, ax2);
                    by1 = Math.min(ay1, ay2);
                    by2 = Math.max(ay1, ay2);
                    hasBox = true;
                }
            }
            cellsOut.add(new Cell(r0, c0, r1, c1, content, page, bx1, by1, bx2, by2, hasBox));

            for (int r = r0; r <= r1; r++) {
                for (int c = c0; c <= c1; c++) {
                    grid.put(key(r, c), content);
                    maxRow = Math.max(maxRow, r);
                    maxCol = Math.max(maxCol, c);
                }
            }
        }
        return new int[]{maxRow, maxCol};
    }

    /** Decodes the five predefined XML entities plus numeric character references. */
    private static String decodeXmlEntities(String s) {
        if (s.indexOf('&') < 0) {
            return s;
        }
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '&') {
                int semi = s.indexOf(';', i + 1);
                if (semi > i && semi - i <= 12) {
                    String entity = s.substring(i + 1, semi);
                    String decoded = switch (entity) {
                        case "amp" -> "&";
                        case "lt" -> "<";
                        case "gt" -> ">";
                        case "quot" -> "\"";
                        case "apos" -> "'";
                        default -> null;
                    };
                    if (decoded == null && entity.startsWith("#")) {
                        try {
                            int code = entity.startsWith("#x") || entity.startsWith("#X")
                                    ? Integer.parseInt(entity.substring(2), 16)
                                    : Integer.parseInt(entity.substring(1));
                            decoded = new String(Character.toChars(code));
                        } catch (IllegalArgumentException ignored) {
                            decoded = null;
                        }
                    }
                    if (decoded != null) {
                        out.append(decoded);
                        i = semi + 1;
                        continue;
                    }
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static long key(int row, int col) {
        return (((long) row) << 32) | (col & 0xFFFFFFFFL);
    }

    private static Map<String, String> parseAttrs(String attrText) {
        Map<String, String> map = new LinkedHashMap<>();
        if (attrText == null) {
            return map;
        }
        Matcher am = ATTR.matcher(attrText);
        while (am.find()) {
            String name = am.group(1);
            String value = am.group(2) != null ? am.group(2) : am.group(3);
            map.put(name, value);
        }
        return map;
    }

    /** Returns null (rather than a sentinel) when the attribute is absent or unparseable, so a
     *  partially-specified {@code <bounding-box>} is treated as "no box" rather than silently
     *  contributing a zero coordinate to a region union. */
    private static Float parseFloatAttr(Map<String, String> attrs, String name) {
        String v = attrs.get(name);
        if (v == null) {
            return null;
        }
        try {
            return Float.parseFloat(v.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int parseIntAttr(Map<String, String> attrs, String name, int fallback) {
        String v = attrs.get(name);
        if (v == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // --------------------------------------------------------- Normalize --

    /**
     * Removes all whitespace, then casefolds, so cell comparison is
     * whitespace-insensitive rather than merely whitespace-collapsing.
     *
     * <p>PDF line-wrapping produces two shapes for the same underlying
     * content and both are legitimate: a mid-token wrap ("Parastratio-" /
     * "sphecomyiastratiosphecomyioides", or a bare wrap with no hyphen)
     * should rejoin with no space, while a wrap at a genuine word boundary
     * ("Hello" / "World") should rejoin with a space. Which one applies is
     * not recoverable from the wrapped text alone -- a fixture (or an
     * extractor) has to pick one, and ground truth and a candidate
     * extraction can disagree on which without either being "wrong" about
     * the actual content.
     *
     * <p>Collapsing to a single space (the previous behavior) silently
     * picks the "word boundary" answer for every wrap, which systematically
     * penalizes correct extractions that instead reconstruct a mid-token
     * wrap without a space (or vice versa): the two sides would differ only
     * by an artifact of how the fixture happened to encode line-wrapping,
     * not by a real extraction error. Stripping whitespace entirely removes
     * the ambiguity from the metric altogether, since both shapes collapse
     * to the same normalized text.
     *
     * <p>Accepted downside: two cells whose real text differs only in word
     * boundaries (e.g. "notable" vs. "not able") would now compare equal.
     * This is intentionally accepted -- the alternative (collapsing to one
     * space) is a biased metric that systematically penalizes one of two
     * equally valid wrap reconstructions, which is worse than a blunt
     * metric that occasionally over-credits a coincidental word-boundary
     * collision.
     */
    public static String normalizeCell(String s) {
        if (s == null) {
            return "";
        }
        String stripped = s.replaceAll("\\s+", "");
        return stripped.toLowerCase(java.util.Locale.ROOT);
    }
}
