package com.ballknowers.draftsim.recap;

import com.ballknowers.draftsim.engine.WeeklyAwards;
import com.ballknowers.draftsim.engine.WeeklyReportService;
import com.ballknowers.draftsim.engine.WeeklyReportService.Result;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Reduces a {@link Result} to the {@link RecapInput} the model sees, and owns everything hashed
 * from it: the canonical JSON, the cache key, the numbers-only hash and the prompt version.
 * Nothing here touches the network or the database.
 */
@Component
public class RecapInputBuilder {

    private static final String PROMPT_RESOURCE = "/recap/system-prompt.md";

    /** Sorted keys, no whitespace, BigDecimal as plain text so 88.90 stays 88.90 and never 8.89E+1. */
    private static final JsonMapper CANONICAL = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    private volatile String promptVersion;

    public RecapInput build(Result r) {
        List<Double> scoresDesc = new ArrayList<>();
        for (WeeklyReportService.Matchup m : r.matchups()) {
            scoresDesc.add(m.home().points());
            scoresDesc.add(m.away().points());
        }
        scoresDesc.sort(Comparator.reverseOrder());

        List<RecapInput.Matchup> matchups = new ArrayList<>();
        RecapInput.Extreme high = null;
        RecapInput.Extreme low = null;
        for (WeeklyReportService.Matchup m : r.matchups()) {
            RecapInput.Side home = side(m.home(), scoresDesc);
            RecapInput.Side away = side(m.away(), scoresDesc);
            int cmp = home.points().compareTo(away.points());
            matchups.add(new RecapInput.Matchup(home, away, home.points().subtract(away.points()).abs(),
                    cmp == 0 ? null : (cmp > 0 ? home.teamName() : away.teamName())));
            for (RecapInput.Side s : List.of(home, away)) {
                if (high == null || s.points().compareTo(high.points()) > 0) high = new RecapInput.Extreme(s.teamName(), s.points());
                if (low == null || s.points().compareTo(low.points()) < 0) low = new RecapInput.Extreme(s.teamName(), s.points());
            }
        }

        return new RecapInput(r.season(), r.week(), r.sport().code(), r.basis(), matchups, high, low,
                map(r.topPerformers(), p -> new RecapInput.Performer(p.playerName(), p.position(), p.teamName(),
                        dec(p.points()), p.opponent(), p.isAway())),
                map(r.bestNights(), n -> new RecapInput.Night(n.playerName(), n.position(), n.teamName(),
                        dec(n.points()), n.date().toString(), n.opponent(), n.isAway())),
                map(r.bestWeek(), w -> new RecapInput.WeekTotal(w.playerName(), w.position(), w.teamName(),
                        dec(w.totalPoints()), w.gamesPlayed())),
                map(r.awards(), a -> new RecapInput.Award(a.kind(), a.teamName(), a.detail())),
                map(r.awardsOmitted(), o -> new RecapInput.Omitted(o.kind(), o.reason())),
                map(r.sectionsUnavailable(), u -> new RecapInput.Unavailable(u.section(), u.reason())));
    }

    public String canonicalJson(RecapInput input) {
        try {
            return CANONICAL.writeValueAsString(input);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not serialize recap input", e);
        }
    }

    /** SHA-256 over the canonical input, the model id and the prompt version (F2). */
    public String cacheKey(String canonicalJson, String model, String promptVersion) {
        return sha256(canonicalJson + "\n" + model + "\n" + promptVersion);
    }

    /**
     * SHA-256 of the input's NUMERIC content only (R4): points, margins, records, score ranks, games
     * played, week high/low points and the numbers inside award details, with every name blanked
     * first (so "Team 12" renamed "Team 13" is not a number change). It is an unordered multiset, so
     * a reorder of the same figures is not a scoring correction. A moved hash is what labels a
     * regeneration NUMBERS_CHANGED; positions, opponents, award wording and list membership do not move it.
     */
    public String numbersHash(RecapInput input) {
        return numbersHash(canonicalJson(input));
    }

    public String numbersHash(String canonicalJson) {
        JsonNode tree = blanked(canonicalJson);
        List<String> parts = new ArrayList<>();
        project(tree, null, parts);
        java.util.Collections.sort(parts);
        return sha256(String.join("\n", parts));
    }

    /**
     * SHA-256 of the input with every team and player name blanked, wherever it appears (including
     * inside award prose). Equal blanked hashes on different canonical inputs mean only names
     * changed, which is how a regeneration is labelled NAMES_CHANGED (F7).
     */
    public String namesBlankedHash(String canonicalJson) {
        try {
            return sha256(CANONICAL.writeValueAsString(blanked(canonicalJson)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not hash recap input", e);
        }
    }

    private static JsonNode blanked(String canonicalJson) {
        try {
            JsonNode tree = CANONICAL.readTree(canonicalJson);
            TreeSet<String> names = new TreeSet<>(Comparator.comparingInt(String::length).reversed()
                    .thenComparing(Comparator.naturalOrder()));
            collectNames(tree, names);
            blank(tree, names);
            return tree;
        } catch (IOException e) {
            throw new IllegalStateException("could not hash recap input", e);
        }
    }

    private static final java.util.Set<String> NUMERIC_FIELDS =
            java.util.Set.of("points", "margin", "scoreRank", "totalPoints", "gamesPlayed");
    private static final java.util.regex.Pattern NUMBER_IN_TEXT = java.util.regex.Pattern.compile("-?[0-9]+(?:[.][0-9]+)?");

    private static void project(JsonNode n, String field, List<String> parts) {
        if (n instanceof ObjectNode o) {
            for (Iterator<Map.Entry<String, JsonNode>> it = o.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                JsonNode v = e.getValue();
                String k = e.getKey();
                if (v.isNumber() && NUMERIC_FIELDS.contains(k)) {
                    parts.add(k + ":" + v.decimalValue().setScale(2, RoundingMode.HALF_UP).toPlainString());
                } else if (v.isTextual() && k.equals("record")) {
                    parts.add("record:" + v.asText());
                } else if (v.isTextual() && k.equals("detail")) {
                    java.util.regex.Matcher m = NUMBER_IN_TEXT.matcher(v.asText());
                    while (m.find()) parts.add("detail:" + m.group());
                } else {
                    project(v, k, parts);
                }
            }
        } else if (n instanceof ArrayNode a) {
            for (JsonNode c : a) project(c, field, parts);
        }
    }

    /**
     * SHA-256 of the system prompt file's bytes plus the structured-output schema the SDK derives
     * from {@link RecapOutput}, so editing either one invalidates every stored key (F2). Computed once.
     */
    public String promptVersion() {
        String v = promptVersion;
        if (v == null) {
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                md.update(promptBytes());
                md.update(schemaJson().getBytes(StandardCharsets.UTF_8));
                v = HexFormat.of().formatHex(md.digest());
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("could not compute the prompt version", e);
            }
            promptVersion = v;
        }
        return v;
    }

    /** The system prompt text, as sent. */
    public String systemPrompt() {
        return new String(promptBytes(), StandardCharsets.UTF_8);
    }

    /**
     * The input in its delimited block. A team name is member-written text (N5), so a literal
     * {@code </league_data>} inside one is escaped ({@code <\/}, still valid JSON) and cannot close it.
     */
    public String userTurn(String canonicalJson) {
        return "<league_data>" + canonicalJson.replace("</", "<\\/") + "</league_data>";
    }

    // ---- internals --------------------------------------------------------------------------

    private static byte[] promptBytes() {
        try (InputStream in = RecapInputBuilder.class.getResourceAsStream(PROMPT_RESOURCE)) {
            if (in == null) throw new IllegalStateException("missing " + PROMPT_RESOURCE);
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + PROMPT_RESOURCE, e);
        }
    }

    /** The schema as the SDK derives it from {@link RecapOutput}: the format node of a throwaway request. */
    private static String schemaJson() {
        return AnthropicRecapClient.requestBody(AnthropicRecapClient.buildParams("schema", 1, "", "", false))
                .at("/output_config/format").toString();
    }

    private static RecapInput.Side side(WeeklyReportService.Side s, List<Double> scoresDesc) {
        return new RecapInput.Side(s.teamName(), dec(s.points()), s.record(),
                WeeklyAwards.competitionRank(scoresDesc, s.points()));
    }

    private static BigDecimal dec(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }

    private static <A, B> List<B> map(List<A> in, Function<A, B> f) {
        return in == null ? null : in.stream().map(f).toList();
    }

    private static void collectNames(JsonNode n, TreeSet<String> names) {
        if (n.isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> it = n.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                if ((e.getKey().equals("teamName") || e.getKey().equals("playerName")) && e.getValue().isTextual()) {
                    names.add(e.getValue().asText());
                } else {
                    collectNames(e.getValue(), names);
                }
            }
        } else if (n.isArray()) {
            for (JsonNode c : n) collectNames(c, names);
        }
    }

    private static void blank(JsonNode n, TreeSet<String> names) {
        if (n instanceof ObjectNode o) {
            for (Iterator<Map.Entry<String, JsonNode>> it = o.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                JsonNode v = e.getValue();
                if (v.isTextual()) {
                    String key = e.getKey();
                    // winner is a team name too, though its field is not called teamName.
                    boolean wholeName = key.equals("teamName") || key.equals("playerName") || key.equals("winner");
                    e.setValue(TextNode.valueOf(wholeName ? "" : scrub(v.asText(), names)));
                } else {
                    blank(v, names);
                }
            }
        } else if (n instanceof ArrayNode a) {
            for (JsonNode c : a) blank(c, names);
        }
    }

    /** Longest names first (the set's order), so a name that contains another is removed whole. */
    private static String scrub(String text, TreeSet<String> names) {
        String out = text;
        for (String name : names) {
            if (!name.isEmpty()) out = out.replace(name, "");
        }
        return out;
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
