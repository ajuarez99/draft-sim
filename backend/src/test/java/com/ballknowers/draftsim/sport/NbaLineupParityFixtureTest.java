package com.ballknowers.draftsim.sport;

import com.ballknowers.draftsim.config.ScoringProperties;
import com.ballknowers.draftsim.domain.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 025 T016 (amended A4/A5). Freezes {@link BasketballRules}'s lineup behaviour -- who is
 * seated, in which slot, which slot kinds are filled, and which of the 32 eligibility masks
 * could still join the starting nine -- for a set of hardcoded NBA rosters, into
 * {@code web/src/__fixtures__/nba-lineup-parity.json}. The frontend's TypeScript port of the
 * lineup matcher replays the same rosters and must produce the same answers.
 *
 * <p>Everything is read through the PUBLIC API of {@code BasketballRules}; nothing under
 * {@code sport/} was changed to make this possible. {@code canJoinByMask[m]} is
 * {@code rosterNeed(probe(m), lineup) > benchFloor}: a probe whose value is positive but below
 * every real player's can only score above the bench floor if he joins the kept set outright
 * (an evicting candidate's delta is clamped to 0, which scores exactly the floor).
 *
 * <p>Regenerate deliberately with {@code gradlew test --tests *NbaLineupParityFixtureTest*
 * -DregenFixture=true}; never to make a red run green. Compared as parsed JSON, not bytes
 * (core.autocrlf=true checks the file out with CRLF).
 */
class NbaLineupParityFixtureTest {

    private static final List<String> TEMPLATE = List.of("PG", "SG", "G", "SF", "PF", "F", "C", "UTIL", "UTIL");
    private static final List<String> SLOTS = List.of(
            "PG", "SG", "G", "SF", "PF", "F", "C", "UTIL", "UTIL",
            "BN", "BN", "BN", "BN", "BN");
    private static final LeagueSettings SETTINGS = new LeagueSettings(Sport.NBA, 12, 14, SLOTS, 0.0);
    private static final double BENCH_FLOOR = 0.15;
    private static final double PROBE_ADP = 9999.0;
    private static final List<Position> NBA_POS = Position.forSport(Sport.NBA);   // PG,SG,SF,PF,C = bits 0..4

    private final BasketballRules rules = new BasketballRules(new ScoringProperties(
            null,
            new ScoringProperties.SportScoring(
                    new ScoringProperties.Weights(1.0, 0.35, 0.5, 0.25),
                    12.0, 3.0, 60.0, BENCH_FLOOR, 6, 0.85,
                    Map.of(), 1.0, 30)));

    // ---- case construction -------------------------------------------------------------

    private record Spec(String name, List<BoardEntry> players) {}

    private static List<Position> pos(String csv) {
        if (csv.isEmpty()) return List.of();   // a player with no recognized position (mask 0)
        return Arrays.stream(csv.split("/")).map(Position::valueOf).toList();
    }

    private static BoardEntry e(long id, String positions, double adp) {
        return new BoardEntry(new Player(id, Sport.NBA, "s" + id, "P" + id, pos(positions),
                null, "Active", null, null, null), adp, 1);
    }

    /** {@code rows}: "POS/POS@adp" strings, ids 1..n in drafted order. */
    private static List<BoardEntry> roster(String... rows) {
        List<BoardEntry> out = new ArrayList<>();
        long id = 1;
        for (String r : rows) {
            String[] parts = r.split("@");
            out.add(e(id++, parts[0], Double.parseDouble(parts[1])));
        }
        return out;
    }

    /** A top-108 shape as the star (adp 3) plus eight single-position fillers. */
    private static Spec shapeCase(String shape) {
        return new Spec("shape " + shape, roster(
                shape + "@3", "PG@10", "SG@18", "SF@26", "PF@34", "C@42", "PG@50", "SG@58", "C@66"));
    }

    private static List<Spec> cases() {
        List<Spec> out = new ArrayList<>();
        for (String shape : List.of("PG/SG", "PG", "C", "C/PF", "PF/SF", "PF/SF/SG", "SF/SG", "PF",
                "PF/PG/SF", "PG/SF/SG")) {
            out.add(shapeCase(shape));
        }
        out.add(new Spec("empty roster", List.of()));
        out.add(new Spec("counterexample: PG/SG first, then a pure PG", roster("PG/SG@5", "PG@20")));
        out.add(new Spec("counterexample reversed: pure PG first, then PG/SG", roster("PG@5", "PG/SG@20")));
        out.add(new Spec("five pure centres", roster("C@4", "C@12", "C@20", "C@36", "C@52")));
        out.add(new Spec("full 14-player roster", roster(
                "PG/SG@2", "C@9", "PF/SF@15", "SG@22", "SF/SG@29", "PG@36", "C/PF@43",
                "SF@50", "PF@57", "PG/SF/SG@64", "SG@71", "C@78", "PF/PG/SF@85", "PG@92")));
        out.add(new Spec("10th pick with nine starters filled", roster(
                "PG@3", "SG@10", "PG/SG@17", "SF@24", "PF@31", "SF/PF@38", "C@45", "SG/SF@52",
                "PF/C@59", "PG@66")));
        out.add(new Spec("no SG-eligible player", roster(
                "PG@4", "SF@11", "PF@18", "C@25", "SF/PF@32", "C/PF@39", "PG@46", "PF@53")));
        out.add(new Spec("no PG or SG at all (all bigs and wings)", roster(
                "SF@6", "PF@13", "C@20", "PF/SF@27", "C/PF@34", "SF@41", "PF@48", "C@55", "SF/PF@62")));
        out.add(new Spec("one player", roster("SF/SG@8")));
        out.add(new Spec("auto-draft-like: balanced guards and bigs", roster(
                "PG/SG@3", "C@10", "SF/PF@17", "PG@26", "PF/C@33", "SG/SF@40", "C@47", "PG@54",
                "SF@61", "SG@68", "PF@75", "PG/SG@82")));
        out.add(new Spec("auto-draft-like: guard heavy", roster(
                "PG@2", "PG/SG@8", "SG@14", "PG@20", "SG@26", "PG/SG@32", "SF@38", "PG@44", "C@50",
                "SG@56", "PF@62")));
        out.add(new Spec("auto-draft-like: big heavy", roster(
                "C@1", "PF/C@7", "C/PF@13", "PF@19", "C@25", "SF/PF@31", "PF@37", "C@43", "SG@49",
                "PG@55", "PF@61")));
        out.add(new Spec("auto-draft-like: wing heavy", roster(
                "SF/SG@5", "PF/SF/SG@11", "SF@17", "SG/SF@23", "PF/SF@29", "SF@35", "PG/SF/SG@41",
                "SG@47", "C@53", "PG@59", "SF/PF@65", "SF@71")));
        out.add(new Spec("three-way versatile players only", roster(
                "PF/PG/SF@3", "PG/SF/SG@9", "PF/SF/SG@15", "PF/PG/SF@21", "PG/SF/SG@27",
                "PF/SF/SG@33", "PF/PG/SF@39", "PG/SF/SG@45", "PF/SF/SG@51", "PG@57")));
        out.add(new Spec("value order differs from drafted order", roster(
                "C@90", "PG@2", "SG@80", "SF@4", "PF@70", "PG/SG@6", "C@60", "SF/PF@8", "PF/C@50")));
        out.add(new Spec("equal-shape C/PF duplicates", roster(
                "C/PF@5", "C/PF@15", "C/PF@25", "C/PF@35", "C/PF@45", "PG@55")));
        // Spec 025 review N6: ties and the no-position player, both sides must agree.
        out.add(new Spec("ADP tie at the 999 sentinel: PG/SG first, then a pure PG", roster(
                "PG/SG@999", "PG@999")));
        out.add(new Spec("mask-0 player is skipped (no position) among normal picks", roster(
                "PG@3", "@7", "C@12", "SG/SF@20")));
        out.add(new Spec("second C with C filled and a UTIL open", roster(
                "C@3", "C@12", "PG@20", "SG@28", "SF@36", "PF@44")));
        addRealAutoDraftedRosters(out);
        return out;
    }

    /**
     * The USER seat's picks (pick order) from four real auto-drafted mock sessions on the local dev
     * DB (3721..3724, read 2026-10-10; positions as stored, i.e. Sleeper-alphabetical; adp from the
     * latest NBA adp_snapshot). Hardcoded -- the test never queries the DB. Each 14-pick roster also
     * contributes its 9- and 12-pick prefixes, the states a draft room actually passes through.
     * Session 3724 holds only five picks (an unfinished mock).
     */
    private static void addRealAutoDraftedRosters(List<Spec> out) {
        List<String> s3721 = List.of("PG@3", "C/PF@10", "PG/SG@17", "C/PF@46", "PG@47", "C@41", "PF/SF/SG@108",
                "PG@84", "PF/SF@103", "PG/SG@132", "C@124", "PG/SG@120", "SF/SG@134", "PF/SF@148");
        List<String> s3722 = List.of("C@1", "PG@4", "PF/SF@13", "PG@32", "PF/SF@50", "PG/SG@49", "SF/SG@65",
                "PF/SF/SG@101", "SF/SG@98", "PG/SG@64", "PF/SF/SG@123", "PF/SF/SG@116", "C/PF@140", "SG@158");
        List<String> s3723 = List.of("C@1", "PG@21", "PF/SF@26", "C/PF@40", "PF/SF@11", "PF/SF@59", "C@85",
                "PG/SG@83", "PG/SG@126", "PF/SF/SG@109", "C@124", "PF/SF@159", "C/PF@140", "SF/SG@182");
        List<String> s3724 = List.of("C@1", "PG@4", "PG/SG@18", "PG@22", "C@48");
        out.add(realSpec("real auto-draft mock 3721", s3721, s3721.size()));
        out.add(realSpec("real auto-draft mock 3721, first 9 picks", s3721, 9));
        out.add(realSpec("real auto-draft mock 3721, first 12 picks", s3721, 12));
        out.add(realSpec("real auto-draft mock 3722", s3722, s3722.size()));
        out.add(realSpec("real auto-draft mock 3722, first 9 picks", s3722, 9));
        out.add(realSpec("real auto-draft mock 3722, first 12 picks", s3722, 12));
        out.add(realSpec("real auto-draft mock 3723", s3723, s3723.size()));
        out.add(realSpec("real auto-draft mock 3723, first 9 picks", s3723, 9));
        out.add(realSpec("real auto-draft mock 3723, first 12 picks", s3723, 12));
        out.add(realSpec("real auto-draft mock 3724 (5 picks, unfinished)", s3724, s3724.size()));
    }

    private static Spec realSpec(String name, List<String> rows, int n) {
        return new Spec(name, roster(rows.subList(0, n).toArray(new String[0])));
    }

    // ---- generation --------------------------------------------------------------------

    private Map<String, Object> generate(Spec spec) {
        RosterState roster = new RosterState();
        for (BoardEntry p : spec.players()) roster.add(p);

        List<SportRules.Assigned> assigned = rules.startingLineup(roster, SETTINGS, rules::value);

        // Seated ids in the order the greedy kept them (value descending), recovered from the
        // public lineup: sort the assigned entries by value descending, tie-break by drafted order
        // (prepareLineup's sort is stable over roster.picks()).
        List<BoardEntry> picks = new ArrayList<>(roster.picks());
        List<BoardEntry> seatedEntries = new ArrayList<>();
        for (SportRules.Assigned a : assigned) seatedEntries.add(a.entry());
        seatedEntries.sort((a, b) -> {
            int c = Double.compare(rules.value(b), rules.value(a));
            return c != 0 ? c : Integer.compare(picks.indexOf(a), picks.indexOf(b));
        });
        List<Long> seated = new ArrayList<>();
        for (BoardEntry s : seatedEntries) seated.add(s.player().id());

        // Per-slot seating in template order. startingLineup omits empty slots and emits in
        // template order, so a greedy walk consuming matching kinds recovers the slot index.
        List<Object> seating = new ArrayList<>();
        int next = 0;
        for (String kind : TEMPLATE) {
            if (next < assigned.size() && assigned.get(next).slot().equals(kind)) {
                seating.add(assigned.get(next).entry().player().id());
                next++;
            } else {
                seating.add(null);
            }
        }
        assertEquals(assigned.size(), next, spec.name() + ": every assigned slot must map into the template");

        Map<String, Integer> filled = new LinkedHashMap<>();
        for (String kind : List.of("PG", "SG", "G", "SF", "PF", "F", "C", "UTIL")) {
            int n = 0;
            for (SportRules.Assigned a : assigned) if (a.slot().equals(kind)) n++;
            if (n > 0) filled.put(kind, n);
        }

        Object lineup = rules.prepareLineup(roster, SETTINGS, rules::value);
        List<Boolean> canJoin = new ArrayList<>(32);
        for (int mask = 0; mask < 32; mask++) {
            List<Position> ps = new ArrayList<>();
            for (int b = 0; b < NBA_POS.size(); b++) if ((mask & (1 << b)) != 0) ps.add(NBA_POS.get(b));
            BoardEntry probe = new BoardEntry(new Player(900_000L + mask, Sport.NBA, "probe" + mask, "Probe",
                    ps, null, "Active", null, null, null), PROBE_ADP, 1);
            canJoin.add(rules.rosterNeed(probe, lineup) > BENCH_FLOOR);
        }

        List<Object> players = new ArrayList<>();
        for (BoardEntry p : spec.players()) {
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("id", p.player().id());
            pm.put("positions", p.player().positions().stream().map(Enum::name).toList());
            pm.put("adp", p.adp());
            players.add(pm);
        }

        Map<String, Object> c = new LinkedHashMap<>();
        c.put("name", spec.name());
        c.put("players", players);
        c.put("seated", seated);
        c.put("seating", seating);
        c.put("filledByKind", filled);
        c.put("canJoinByMask", canJoin);
        return c;
    }

    private Map<String, Object> generateAll() {
        List<Object> caseList = new ArrayList<>();
        for (Spec s : cases()) caseList.add(generate(s));
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("template", TEMPLATE);
        root.put("cases", caseList);
        return root;
    }

    private static Path fixturePath() {
        // gradle runs tests from backend/; tolerate the repo root too.
        Path fromBackend = Path.of("..", "web", "src", "__fixtures__", "nba-lineup-parity.json");
        Path fromRoot = Path.of("web", "src", "__fixtures__", "nba-lineup-parity.json");
        return Files.isDirectory(Path.of("backend")) ? fromRoot : fromBackend;
    }

    @Test
    void committedParityFixtureMatchesTheBasketballRules() throws IOException {
        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        String generated = mapper.writeValueAsString(generateAll());
        Path path = fixturePath();

        if ("true".equals(System.getProperty("regenFixture"))) {
            Files.createDirectories(path.getParent());
            Files.writeString(path, generated + "\n", StandardCharsets.UTF_8);
            return;
        }

        assertTrue(Files.exists(path), "nba-lineup-parity.json is missing: run gradlew test "
                + "--tests *NbaLineupParityFixtureTest* -DregenFixture=true");
        JsonNode committed = mapper.readTree(Files.readString(path, StandardCharsets.UTF_8));
        JsonNode fresh = mapper.readTree(generated);
        assertEquals(fresh, committed, "nba-lineup-parity.json is stale: run gradlew test "
                + "--tests *NbaLineupParityFixtureTest* -DregenFixture=true");
    }

    @Test
    void theFixtureCoversAtLeastTwentyCasesAndTheKeyShapes() {
        List<String> names = cases().stream().map(Spec::name).toList();
        assertTrue(names.size() >= 20, "need >= 20 cases, have " + names.size());
        assertEquals(names.size(), names.stream().distinct().count(), "case names must be unique");
    }
}
