package com.ballknowers.draftsim.api;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * specs/009-auto-data-refresh T030 (research R10): a user-visible message must
 * not tell the reader to run an endpoint. Data now refreshes itself, so
 * "run POST /api/ingest/..." is wrong advice and internal detail.
 *
 * <p>Scans every {@code .java} under {@code api/} and {@code engine/}, strips
 * line and block comments, and fails if a string literal contains
 * {@code /api/ingest} or {@code POST /api/}. Grepping source from a test is
 * ugly (see NoSportNameInSuperlativesTest); nothing else would notice a new hint.
 *
 * <p>Exemption: a line that is a Spring route-mapping annotation
 * ({@code @RequestMapping("/api/ingest")}) declares the route rather than
 * describing it to a user, so it is not a message.
 */
class NoIngestHintsInMessagesTest {

    private static final String[] ROOTS = {
            "src/main/java/com/ballknowers/draftsim/api",
            "src/main/java/com/ballknowers/draftsim/engine",
            "src/main/java/com/ballknowers/draftsim/mock",
    };

    /** Returns the file's source with comments replaced by spaces (newlines kept, strings intact). */
    static String stripComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int n = src.length();
        int i = 0;
        while (i < n) {
            char c = src.charAt(i);
            char d = i + 1 < n ? src.charAt(i + 1) : '\0';
            if (c == '"' && src.startsWith("\"\"\"", i)) {
                int end = src.indexOf("\"\"\"", i + 3);
                end = end < 0 ? n : end + 3;
                out.append(src, i, end);
                i = end;
            } else if (c == '"' || c == '\'') {
                out.append(c);
                i++;
                while (i < n && src.charAt(i) != c && src.charAt(i) != '\n') {
                    if (src.charAt(i) == '\\' && i + 1 < n) { out.append(src.charAt(i)); i++; }
                    out.append(src.charAt(i));
                    i++;
                }
                if (i < n && src.charAt(i) == c) { out.append(c); i++; }
            } else if (c == '/' && d == '/') {
                while (i < n && src.charAt(i) != '\n') i++;
            } else if (c == '/' && d == '*') {
                int end = src.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
                for (int k = i; k < end; k++) if (src.charAt(k) == '\n') out.append('\n');
                i = end;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** A double-quoted Java string literal, escapes included. */
    private static final java.util.regex.Pattern LITERAL =
            java.util.regex.Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");

    private static boolean hasHint(String line) {
        if (line.contains("/api/ingest") || line.contains("POST /api/")) return true;
        // Added in live verification (2026-09-28): "no transactions ingested for this
        // league yet" names no endpoint but is still developer jargon on a page. Any
        // sentence-like literal (it has a space) using the word "ingest" fails. A bare
        // JSON key such as "ingested" has no space and stays allowed. Log statements
        // aren't shown to a league manager, so they are exempt.
        if (line.contains("log.")) return false;
        java.util.regex.Matcher m = LITERAL.matcher(line);
        while (m.find()) {
            String lit = m.group(1);
            if (lit.contains(" ") && lit.toLowerCase(java.util.Locale.ROOT).contains("ingest")) return true;
        }
        return false;
    }

    private static List<String> offenders() throws Exception {
        List<String> bad = new ArrayList<>();
        for (String root : ROOTS) {
            Path dir = Path.of(root);
            assertTrue(Files.isDirectory(dir), "expected to find " + dir.toAbsolutePath());
            List<Path> files;
            try (Stream<Path> s = Files.walk(dir)) {
                files = s.filter(p -> p.toString().endsWith(".java")).sorted().toList();
            }
            for (Path f : files) {
                String[] lines = stripComments(Files.readString(f)).split("\n", -1);
                for (int i = 0; i < lines.length; i++) {
                    String t = lines[i].trim();
                    if (t.startsWith("@") && t.contains("Mapping(")) continue;
                    if (hasHint(lines[i])) bad.add(f.getFileName() + ":" + (i + 1) + ": " + t);
                }
            }
        }
        return bad;
    }

    @Test
    void noMessageNamesAnIngestEndpoint() throws Exception {
        List<String> bad = offenders();
        assertTrue(bad.isEmpty(),
                "Messages must state what is missing in plain words, never an endpoint to call. Offending lines:\n"
                        + String.join("\n", bad));
    }

    @Test
    void theScannerStripsCommentsButKeepsStrings() {
        String src = "// POST /api/x\n/* POST /api/y */ String a = \"ok\"; /** run /api/ingest */\nString b = \"POST /api/z\";\n";
        String stripped = stripComments(src);
        assertTrue(!stripped.contains("/api/x") && !stripped.contains("/api/y") && !stripped.contains("/api/ingest"));
        assertTrue(stripped.contains("\"POST /api/z\""));
    }
}
