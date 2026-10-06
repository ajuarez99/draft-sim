package com.ballknowers.draftsim.recap;

import com.ballknowers.draftsim.domain.Sport;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The runtime gate on a model's recap (research R5, as amended after review). Every number in a
 * section's title and body must occur in the items that section cites, every team or player named
 * must occur in them too (case-insensitively), and the headline is checked against the union of the
 * items all sections cite (review R1; it is no longer checked against the whole input).
 *
 * <p><b>What it proves:</b> these numbers and names occur in the data cited for this subject. It
 * does not prove the sentence is true, and small integers are near-vacuous (R5's stated limit);
 * the human read in quickstart V3 is the backstop.
 *
 * <p>Pure and static: no Spring, no I/O.
 */
public final class GroundingCheck {

    static final int MAX_CITES = 4;

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private static final Pattern ITEM_CITE =
            Pattern.compile("^/(matchups|awards|topPerformers|bestNights|bestWeek|awardsOmitted|sectionsUnavailable)/\\d+$");
    private static final Pattern EXTREME_CITE = Pattern.compile("^/(weekHigh|weekLow)$");

    private static final Pattern NBA_BASIS = Pattern.compile("\\b(counted|credited|scored for)\\b", Pattern.CASE_INSENSITIVE);

    // ---- tokenizing ---------------------------------------------------------------------------
    // Word boundaries are "not preceded/followed by a letter or digit", so 49ers and
    // FentMachines5 contribute nothing, and a record or an ISO date is ONE token, never bare integers.

    private static final String NO_WORD_BEFORE = "(?<![\\p{L}\\p{N}_])";
    private static final String NO_WORD_AFTER = "(?![\\p{L}\\p{N}_])";
    private static final String MONTH = "(?:Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|June?|July?|Aug(?:ust)?"
            + "|Sep(?:t(?:ember)?)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)";
    private static final List<String> CARDINAL_WORDS = List.of("zero", "one", "two", "three", "four", "five", "six",
            "seven", "eight", "nine", "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen",
            "seventeen", "eighteen", "nineteen", "twenty");
    private static final List<String> ORDINAL_WORDS = List.of("first", "second", "third", "fourth", "fifth", "sixth",
            "seventh", "eighth", "ninth", "tenth", "eleventh", "twelfth", "thirteenth", "fourteenth", "fifteenth",
            "sixteenth", "seventeenth", "eighteenth", "nineteenth", "twentieth");

    private static final Pattern TOKEN = Pattern.compile(
            NO_WORD_BEFORE + "(?<iso>\\d{4}-\\d{2}-\\d{2})" + NO_WORD_AFTER
            + "|" + NO_WORD_BEFORE + "(?<month>" + MONTH + ")\\.?\\s+(?<day>\\d{1,2})(?:st|nd|rd|th)?" + NO_WORD_AFTER
            + "|(?<![\\p{L}\\p{N}_.])(?<rec>\\d+-\\d+(?:-\\d+)?)" + NO_WORD_AFTER
            + "|(?<![\\p{L}\\p{N}_.])(?<neg>-)?(?<num>\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?|\\d+(?:\\.\\d+)?)"
            + "(?<suffix>%|st|nd|rd|th|pts?|points?|x)?"
            + NO_WORD_AFTER
            + "|" + NO_WORD_BEFORE + "(?<word>" + String.join("|", reversed(CARDINAL_WORDS)) + "|"
            + String.join("|", reversed(ORDINAL_WORDS)) + "|half|dozen)" + NO_WORD_AFTER,
            Pattern.CASE_INSENSITIVE);

    private static final Map<String, String> MONTH_NUMBER = Map.ofEntries(
            Map.entry("jan", "01"), Map.entry("feb", "02"), Map.entry("mar", "03"), Map.entry("apr", "04"),
            Map.entry("may", "05"), Map.entry("jun", "06"), Map.entry("jul", "07"), Map.entry("aug", "08"),
            Map.entry("sep", "09"), Map.entry("oct", "10"), Map.entry("nov", "11"), Map.entry("dec", "12"));

    /** One thing a body claims. {@code key} is typed (N: number, R: record, D: ISO date, MD: month-day). */
    private record Token(String raw, String key, boolean ordinal) {}

    /** What the cited items hold. Season and week match plain numbers only, never an ordinal. */
    private record Pool(Set<String> keys, Set<String> plainOnly, List<String> text) {}

    private GroundingCheck() {}

    public static GroundingResult check(RecapOutput out, String canonicalInputJson, Sport sport) {
        JsonNode root;
        try {
            root = JSON.readTree(canonicalInputJson);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("input is not JSON", e);
        }
        Set<String> names = new LinkedHashSet<>();
        collectNames(root, names);

        Set<String> unmatched = new LinkedHashSet<>();
        Set<String> badCites = new LinkedHashSet<>();
        Set<String> unbound = new LinkedHashSet<>();
        Set<String> basis = new LinkedHashSet<>();

        List<RecapOutput.Section> sections = out.sections() == null ? List.of() : out.sections();
        List<JsonNode> allItems = new ArrayList<>();
        for (int i = 0; i < sections.size(); i++) {
            RecapOutput.Section s = sections.get(i);
            List<String> cites = s.cites() == null ? List.of() : s.cites();

            List<JsonNode> items = new ArrayList<>();
            if (cites.isEmpty()) badCites.add("section " + i + ": no cites");
            if (cites.size() > MAX_CITES) {
                badCites.add("section " + i + ": " + cites.size() + " cites, at most " + MAX_CITES);
            }
            for (String cite : cites) {
                JsonNode item = resolve(root, cite);
                if (item == null) badCites.add(cite == null ? "null" : (cite.isEmpty() ? "\"\"" : cite));
                else items.add(item);
            }
            allItems.addAll(items);

            // The title is checked exactly like the body (R1): it is rendered as checked text too.
            Pool pool = pool(items, true, root);
            checkText(s.title(), pool, names, sport, unmatched, unbound, basis);
            checkText(s.body(), pool, names, sport, unmatched, unbound, basis);
        }

        // The headline's pool is what the sections cite, not the whole input (R1).
        if (out.headline() != null) {
            checkText(out.headline(), pool(allItems, true, root), names, sport, unmatched, unbound, basis);
        }

        boolean ok = unmatched.isEmpty() && badCites.isEmpty() && unbound.isEmpty() && basis.isEmpty();
        return new GroundingResult(ok, List.copyOf(unmatched), List.copyOf(badCites), List.copyOf(unbound),
                List.copyOf(basis));
    }

    private static void checkText(String raw, Pool pool, Set<String> names, Sport sport,
                                  Set<String> unmatched, Set<String> unbound, Set<String> basis) {
        String text = norm(raw == null ? "" : raw);
        for (Token t : tokens(withoutNames(text, names), true)) {
            if (!matches(t, pool)) unmatched.add(t.raw());
        }
        for (String name : names) {
            if (!name.isEmpty() && containsWhole(text, name) && pool.text().stream().noneMatch(v -> containsWhole(v, name))) {
                unbound.add(name);
            }
        }
        if (sport == Sport.NBA) {
            Matcher m = NBA_BASIS.matcher(text);
            while (m.find()) basis.add(m.group().toLowerCase(Locale.ROOT));
        }
    }

    /**
     * NFKC (fullwidth digits become ASCII, R11), then en/em dashes and the minus sign become a plain
     * hyphen (R3), so a record typeset "7-2" is one atomic token whichever dash was used.
     */
    private static String norm(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFKC).replaceAll("[‒–—―−]", "-");
    }

    // ---- cites ---------------------------------------------------------------------------------

    /** The item a cite points at, or null if it is not an item pointer or does not resolve. */
    private static JsonNode resolve(JsonNode root, String cite) {
        if (cite == null || !(ITEM_CITE.matcher(cite).matches() || EXTREME_CITE.matcher(cite).matches())) return null;
        JsonNode n = root.at(cite);
        return n.isMissingNode() || n.isNull() ? null : n;
    }

    // ---- the pool ------------------------------------------------------------------------------

    /**
     * Numeric leaves under the items, plus number tokens found inside their string leaves (award
     * prose holds 86% and 6th only as text). Records and ISO dates stay atomic; an ISO date also
     * offers its month-day, so "December 25" can match "2025-12-25".
     */
    private static Pool pool(List<JsonNode> items, boolean withSeasonAndWeek, JsonNode root) {
        Set<String> keys = new HashSet<>();
        List<String> text = new ArrayList<>();
        for (JsonNode item : items) walk(item, keys, text);
        Set<String> plain = new HashSet<>();
        if (withSeasonAndWeek) {
            for (String f : List.of("season", "week")) {
                if (root.has(f) && root.get(f).isNumber()) plain.add("N:" + normalize(root.get(f).decimalValue()));
            }
        }
        return new Pool(keys, plain, text);
    }

    private static void walk(JsonNode n, Set<String> keys, List<String> text) {
        if (n.isNumber()) {
            keys.add("N:" + normalize(n.decimalValue()));
        } else if (n.isTextual()) {
            String normalized = norm(n.asText());
            text.add(normalized);
            for (Token t : tokens(normalized, false)) {
                keys.add(t.key());
                if (t.key().startsWith("D:")) keys.add("MD:" + t.key().substring(7));
            }
        } else if (n.isContainerNode()) {
            for (JsonNode c : n) walk(c, keys, text);
        }
    }

    private static boolean matches(Token t, Pool pool) {
        if (pool.keys().contains(t.key())) return true;
        return t.key().startsWith("N:") && !t.ordinal() && pool.plainOnly().contains(t.key());
    }

    // ---- tokens --------------------------------------------------------------------------------

    private static List<Token> tokens(String text, boolean withWords) {
        List<Token> out = new ArrayList<>();
        Matcher m = TOKEN.matcher(text);
        while (m.find()) {
            if (m.group("iso") != null) {
                out.add(new Token(m.group(), "D:" + m.group("iso"), false));
            } else if (m.group("month") != null) {
                String month = MONTH_NUMBER.get(m.group("month").substring(0, 3).toLowerCase(Locale.ROOT));
                out.add(new Token(m.group(), "MD:" + month + "-" + String.format("%02d", Integer.parseInt(m.group("day"))), false));
            } else if (m.group("rec") != null) {
                out.add(new Token(m.group(), "R:" + m.group("rec"), false));
            } else if (m.group("num") != null) {
                String suffix = m.group("suffix");
                boolean ordinal = suffix != null && suffix.toLowerCase(Locale.ROOT).matches("st|nd|rd|th");
                BigDecimal v = new BigDecimal(m.group("num").replace(",", ""));
                if (m.group("neg") != null) v = v.negate(); // a negative must match a negative (R11)
                out.add(new Token(m.group(), "N:" + normalize(v), ordinal));
            } else if (withWords && m.group("word") != null) {
                String w = m.group("word").toLowerCase(Locale.ROOT);
                if (w.equals("half")) out.add(new Token(m.group(), "N:" + normalize(new BigDecimal("0.5")), false));
                else if (w.equals("dozen")) out.add(new Token(m.group(), "N:12", false));
                else if (CARDINAL_WORDS.contains(w)) out.add(new Token(m.group(), "N:" + CARDINAL_WORDS.indexOf(w), false));
                else out.add(new Token(m.group(), "N:" + (ORDINAL_WORDS.indexOf(w) + 1), true));
            }
        }
        return out;
    }

    /** 2 dp, HALF_UP, trailing zeros stripped, so 114.9 = 114.90 and 31% = 31. */
    private static String normalize(BigDecimal v) {
        BigDecimal r = v.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
        return (r.signum() == 0 ? BigDecimal.ZERO : r).toPlainString();
    }

    // ---- names ---------------------------------------------------------------------------------

    private static void collectNames(JsonNode n, Set<String> names) {
        if (n.isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> it = n.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                if ((e.getKey().equals("teamName") || e.getKey().equals("playerName")) && e.getValue().isTextual()) {
                    names.add(norm(e.getValue().asText()));
                } else {
                    collectNames(e.getValue(), names);
                }
            }
        } else if (n.isArray()) {
            for (JsonNode c : n) collectNames(c, names);
        }
    }

    /**
     * A name is a label, not a claim: "Three Amigos" or "Team 12" must not contribute a 3 or a 12
     * (the pool never reads number words, so such a name would otherwise always fail). Names are
     * blanked from the text before it is tokenized; they are still bound to the cited items.
     */
    private static String withoutNames(String text, Set<String> names) {
        String out = text;
        List<String> longestFirst = new ArrayList<>(names);
        longestFirst.sort((a, b) -> b.length() - a.length());
        for (String name : longestFirst) {
            if (!name.isEmpty()) {
                out = wholeName(name).matcher(out).replaceAll(" ");
            }
        }
        return out;
    }

    /** The whole name, not part of a longer word (a name field equals it; award prose contains it). */
    private static boolean containsWhole(String haystack, String name) {
        return wholeName(name).matcher(haystack).find();
    }

    private static Pattern wholeName(String name) {
        // Case-insensitive on purpose (R2): a lowercase handle capitalized at a sentence start is still that name.
        return Pattern.compile(NO_WORD_BEFORE + Pattern.quote(name) + NO_WORD_AFTER,
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    private static List<String> reversed(List<String> in) {
        List<String> out = new ArrayList<>(in);
        java.util.Collections.reverse(out);
        return out;
    }
}
