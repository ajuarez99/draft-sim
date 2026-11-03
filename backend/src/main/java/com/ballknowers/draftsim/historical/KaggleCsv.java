package com.ballknowers.draftsim.historical;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A streaming RFC-4180 reader for the Kaggle NBA box-score file (spec 053 T009), plus the few decoding rules
 * that file needs. No new dependency: quoted fields, commas and doubled quotes inside quotes, line breaks
 * inside quotes, and the empty first header the file ships with are all handled here.
 *
 * <p>The file is 158 MB and prod runs at {@code -Xmx1024m}, so {@link #next()} yields one record at a time and
 * nothing is accumulated.
 *
 * <p><b>An empty cell is {@code null}, never zero.</b> "Unrecorded" and "zero" are different facts in this
 * data (research R2) and the era rule depends on telling them apart. The header row is returned verbatim.
 */
public final class KaggleCsv implements AutoCloseable {

    /** Box-score CSV column -> the scoring/stat key it feeds (plan F17). */
    public static final Map<String, String> COLUMN_MAP = Map.ofEntries(
            Map.entry("REB", "reb"), Map.entry("AST", "ast"), Map.entry("STL", "stl"),
            Map.entry("BLK", "blk"), Map.entry("TOV", "to"), Map.entry("FG3M", "tpm"),
            Map.entry("FGM", "fgm"), Map.entry("FGA", "fga"), Map.entry("FTM", "ftm"),
            Map.entry("FTA", "fta"), Map.entry("PTS", "pts"), Map.entry("MIN", "min"));

    /** "NOV 19, 1946": the file upper-cases the month, so matching must ignore case (plan F17). */
    private static final DateTimeFormatter DATE = new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern("MMM d, yyyy")
            .toFormatter(Locale.US);

    private final BufferedReader in;
    private final List<String> header;
    private final Map<String, Integer> columnIndex = new HashMap<>();

    public KaggleCsv(Reader reader) {
        this.in = reader instanceof BufferedReader b ? b : new BufferedReader(reader, 1 << 16);
        String[] first = readRecord(false);
        if (first == null) throw new IllegalArgumentException("empty CSV: no header row");
        this.header = List.of(first);
        for (int i = 0; i < first.length; i++) columnIndex.putIfAbsent(first[i], i);
    }

    /** The header cells exactly as written (the first is empty in this file). */
    public List<String> header() {
        return header;
    }

    /** Position of a header column, or -1. */
    public int indexOf(String column) {
        return columnIndex.getOrDefault(column, -1);
    }

    /** The next record with empty cells as {@code null}, or {@code null} at end of input. */
    public String[] next() {
        return readRecord(true);
    }

    @Override
    public void close() {
        try {
            in.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Parse one record. A quoted empty string ({@code ""}) and an unquoted empty cell are both empty here;
     * the file does not use the distinction.
     */
    private String[] readRecord(boolean emptyToNull) {
        try {
            List<String> cells = new ArrayList<>();
            StringBuilder cell = new StringBuilder();
            boolean quoted = false;
            boolean sawAnything = false;
            int c;
            while ((c = in.read()) != -1) {
                sawAnything = true;
                if (quoted) {
                    if (c == '"') {
                        in.mark(1);
                        int peek = in.read();
                        if (peek == '"') {
                            cell.append('"');
                        } else {
                            quoted = false;
                            if (peek != -1) in.reset();
                        }
                    } else {
                        cell.append((char) c);
                    }
                } else if (c == '"' && cell.isEmpty()) {
                    quoted = true;
                } else if (c == ',') {
                    cells.add(finish(cell, emptyToNull));
                    cell.setLength(0);
                } else if (c == '\n' || c == '\r') {
                    if (c == '\r') {
                        in.mark(1);
                        int peek = in.read();
                        if (peek != '\n' && peek != -1) in.reset();
                    }
                    if (cells.isEmpty() && cell.isEmpty()) {
                        sawAnything = false; // a blank line is not a record
                        continue;
                    }
                    cells.add(finish(cell, emptyToNull));
                    return cells.toArray(new String[0]);
                } else {
                    cell.append((char) c);
                }
            }
            if (!sawAnything || (cells.isEmpty() && cell.isEmpty())) return null;
            cells.add(finish(cell, emptyToNull));
            return cells.toArray(new String[0]);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String finish(StringBuilder cell, boolean emptyToNull) {
        String s = cell.toString();
        return emptyToNull && s.isEmpty() ? null : s;
    }

    /** Parse the file's date form, e.g. {@code "NOV 19, 1946"}. */
    public static LocalDate parseDate(String text) {
        return LocalDate.parse(text.trim(), DATE);
    }

    /**
     * The season START year encoded in a {@code Game_ID}: characters 4-5 (index 3..5) are the two-digit start
     * year, {@code >= 46} meaning 19xx and otherwise 20xx. {@code 0024600034} is 1946-47 and
     * {@code 0029800001} is 1998-99.
     *
     * <p>Never use the CSV's own {@code Season} column here: it is the CALENDAR year the game was played in,
     * so a January game would land in the wrong season (research R3).
     */
    public static int seasonStartOfGameId(String gameId) {
        String id = gameId.trim();
        if (id.length() < 10) id = "0".repeat(10 - id.length()) + id;
        int yy = Integer.parseInt(id.substring(3, 5));
        return yy >= 46 ? 1900 + yy : 2000 + yy;
    }
}
