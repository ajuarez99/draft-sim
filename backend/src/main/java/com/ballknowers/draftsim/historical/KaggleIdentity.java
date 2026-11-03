package com.ballknowers.draftsim.historical;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Who is who in the Kaggle era (spec 053 FR-014, research R5, plan-review F2/F5/F17). Pure: no Spring, no
 * database, so every rule here is a unit test on small fixtures.
 *
 * <p>The season file (Basketball-Reference shaped) is the identity authority. Its rows are keyed to a
 * <i>person key</i> (the normalised name, or a curated key for same-name people), the box-score rows are matched
 * to those, and the checks that make the import fail loudly (an uncurated same-name collision, an age that jumps
 * between seasons, an empty position set) all read the keyed rows.
 */
public final class KaggleIdentity {

    private KaggleIdentity() {}

    public static final String TOT = "TOT";

    /** Match problem: the season file has this name, but never on the box row's team. Rows are dropped, not merged. */
    public static final String TEAM_MISMATCH = "TEAM_MISMATCH";

    /** Match problem: curated same-name people on one team in one season, indistinguishable in box scores. */
    public static final String AMBIGUOUS_SAME_TEAM = "AMBIGUOUS_SAME_TEAM";

    // ---- names, positions, derived flags -------------------------------------------------------------

    /** NFKD, accents and punctuation stripped, lower-case, whitespace collapsed (research R5 step 1). */
    public static String normalize(String name) {
        if (name == null) return "";
        String s = Normalizer.normalize(name, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9 ]", "")
                .replaceAll("\\s+", " ")
                .trim();
        return s;
    }

    /**
     * Season-file {@code Pos} to engine positions. Hyphenated: the union, the first listed first (so it is the
     * primary). {@code G} is PG+SG, {@code F} is SF+PF. An empty list means unknown (F5: that fails the season).
     */
    public static List<String> positions(String pos) {
        if (pos == null || pos.isBlank()) return List.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String token : pos.trim().toUpperCase(Locale.ROOT).split("-")) {
            switch (token.trim()) {
                case "PG", "SG", "SF", "PF", "C" -> out.add(token.trim());
                case "G" -> {
                    out.add("PG");
                    out.add("SG");
                }
                case "F" -> {
                    out.add("SF");
                    out.add("PF");
                }
                default -> {
                    return List.of(); // an unknown token is not guessed at
                }
            }
        }
        return List.copyOf(out);
    }

    /**
     * dd/td/bonus flags for one game. The bonuses are null when their own input stat is null (never guessed).
     * dd/td treat a missing stat as 0 (FR-007: a counted stat missing from a box score scores 0), so a 1983-84 row
     * with points and rebounds but no steals/blocks is still a double-double; they are null only when all five
     * inputs are null.
     */
    public record Derived(Integer dd, Integer td, Integer bonusPt40, Integer bonusPt50, Integer bonusReb20,
                          Integer bonusAst15) {}

    public static Derived derive(Integer pts, Integer reb, Integer ast, Integer stl, Integer blk) {
        Integer dd = null, td = null;
        if (pts != null || reb != null || ast != null || stl != null || blk != null) {
            int tens = 0;
            for (Integer v : new Integer[]{pts, reb, ast, stl, blk}) if (v != null && v >= 10) tens++;
            dd = tens >= 2 ? 1 : 0;
            td = tens >= 3 ? 1 : 0;
        }
        Integer b40 = pts == null ? null : (pts >= 40 ? 1 : 0);
        Integer b50 = pts == null ? null : (pts >= 50 ? 1 : 0);
        Integer r20 = reb == null ? null : (reb >= 20 ? 1 : 0);
        Integer a15 = ast == null ? null : (ast >= 15 ? 1 : 0);
        return new Derived(dd, td, b40, b50, r20, a15);
    }

    // ---- the season file -----------------------------------------------------------------------------

    /**
     * One row of {@code NBA Player Stats(1950 - 2022).csv}. {@code endYear} is that file's {@code Season}, the
     * END year of the season (research R3); the season start is {@code endYear - 1}. Stats are season totals as
     * recorded, null when the cell is empty.
     */
    public record SeasonFileRow(int id, int endYear, String name, String norm, String pos, Integer age, String team,
                                Integer games, Double pts, Double reb, Double ast, Double stl, Double blk,
                                Double to, Double tpm, Double fgm, Double fga, Double ftm, Double fta) {
        public int seasonStart() {
            return endYear - 1;
        }
    }

    /** Reads the season file, keeping end years {@code minEnd..maxEnd}. Ids are assigned in file order. */
    public static List<SeasonFileRow> readSeasonFile(Reader reader, int minEnd, int maxEnd) {
        List<SeasonFileRow> out = new ArrayList<>();
        try (KaggleCsv csv = new KaggleCsv(reader)) {
            int cSeason = req(csv, "Season"), cName = req(csv, "Player"), cPos = req(csv, "Pos"),
                    cAge = req(csv, "Age"), cTm = req(csv, "Tm"), cG = req(csv, "G"),
                    cPts = req(csv, "PTS"), cReb = req(csv, "TRB"), cAst = req(csv, "AST"),
                    cStl = req(csv, "STL"), cBlk = req(csv, "BLK"), cTov = req(csv, "TOV"),
                    cTpm = req(csv, "3P"), cFgm = req(csv, "FG"), cFga = req(csv, "FGA"),
                    cFtm = req(csv, "FT"), cFta = req(csv, "FTA");
            String[] r;
            int id = 0;
            while ((r = csv.next()) != null) {
                Double seasonD = dbl(r, cSeason);
                if (seasonD == null) continue;
                int end = seasonD.intValue();
                if (end < minEnd || end > maxEnd) continue;
                String name = r[cName];
                if (name == null) continue;
                Double age = dbl(r, cAge), g = dbl(r, cG);
                out.add(new SeasonFileRow(id++, end, name, normalize(name), r[cPos],
                        age == null ? null : age.intValue(), r[cTm], g == null ? null : g.intValue(),
                        dbl(r, cPts), dbl(r, cReb), dbl(r, cAst), dbl(r, cStl), dbl(r, cBlk), dbl(r, cTov),
                        dbl(r, cTpm), dbl(r, cFgm), dbl(r, cFga), dbl(r, cFtm), dbl(r, cFta)));
            }
        }
        return out;
    }

    private static int req(KaggleCsv csv, String column) {
        int i = csv.indexOf(column);
        if (i < 0) throw new IllegalArgumentException("season file has no column " + column + ": " + csv.header());
        return i;
    }

    private static Double dbl(String[] r, int i) {
        if (i >= r.length || r[i] == null || r[i].isBlank()) return null;
        try {
            return Double.parseDouble(r[i].trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---- curation files ------------------------------------------------------------------------------

    /**
     * The three checked-in files. Keys are built so a lookup is a single map get:
     * aliases {@code end|normBoxName -> seasonFileName}, same-name {@code end|team|normName -> personKey},
     * team codes {@code boxCode -> seasonCode}.
     */
    public record Curation(Map<String, String> aliases, Map<String, String> sameName, Set<String> curatedNames,
                           Map<String, String> teamCodes) {

        public static Curation empty() {
            return new Curation(Map.of(), Map.of(), Set.of(), Map.of());
        }

        public static Curation fromRows(List<String[]> aliasRows, List<String[]> sameNameRows,
                                        List<String[]> teamRows) {
            Map<String, String> aliases = new HashMap<>();
            for (String[] r : aliasRows) {
                aliases.put(Integer.parseInt(r[0].trim()) + "|" + normalize(r[1]), r[2].trim());
            }
            Map<String, String> same = new HashMap<>();
            Set<String> names = new HashSet<>();
            for (String[] r : sameNameRows) {
                String norm = normalize(r[2]);
                names.add(norm);
                same.put(Integer.parseInt(r[0].trim()) + "|" + r[1].trim() + "|" + norm, r[3].trim());
            }
            Map<String, String> teams = new HashMap<>();
            for (String[] r : teamRows) teams.put(r[0].trim(), r[1].trim());
            return new Curation(aliases, same, names, teams);
        }

        public static Curation load() {
            return fromRows(resource("historical-name-aliases.csv", 3), resource("historical-same-name.csv", 4),
                    resource("team-codes.csv", 2));
        }

        public String mapTeam(String boxCode) {
            return boxCode == null ? null : teamCodes.getOrDefault(boxCode, boxCode);
        }

        private static List<String[]> resource(String file, int columns) {
            try (InputStream in = KaggleIdentity.class.getResourceAsStream("/historical/" + file)) {
                if (in == null) throw new IllegalStateException("missing resource /historical/" + file);
                BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                List<String[]> rows = new ArrayList<>();
                String line;
                boolean header = true;
                while ((line = br.readLine()) != null) {
                    String t = line.trim();
                    if (t.isEmpty() || t.startsWith("#")) continue;
                    if (header) { // the first non-comment line is the column header
                        header = false;
                        continue;
                    }
                    String[] parts = t.split(",", -1);
                    if (parts.length != columns) {
                        throw new IllegalStateException(file + ": expected " + columns + " columns, got: " + line);
                    }
                    rows.add(parts);
                }
                return rows;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    // ---- keying, collisions, age continuity ----------------------------------------------------------

    /** A problem that fails a season: kind is UNKEYED, COLLISION or AGE_JUMP. */
    public record Issue(int seasonStart, String kind, String detail) {}

    /** One real person in one season: the card line comes from {@code chosen} (the TOT row when there is one). */
    public record PersonSeason(String personKey, String displayName, SeasonFileRow chosen, String lastTeam,
                               List<SeasonFileRow> rows) {}

    /**
     * Season-file rows with person keys applied and the identity checks run. {@link #issues()} is everything that
     * must be empty before a season may commit (grouped by season start with {@link #issuesFor}).
     */
    public static final class Index {
        private final List<SeasonFileRow> rows;
        private final String[] keyOf;
        private final Map<Integer, Map<String, List<SeasonFileRow>>> byEndNorm = new HashMap<>();
        private final Map<Integer, Map<String, List<SeasonFileRow>>> byEndKey = new TreeMap<>();
        private final List<Issue> issues = new ArrayList<>();
        private final Curation curation;

        public Index(List<SeasonFileRow> rows, Curation curation) {
            this.rows = rows;
            this.curation = curation;
            this.keyOf = new String[rows.size()];
            assignKeys();
            detectCollisions();
            detectAgeJumps();
        }

        public Curation curation() {
            return curation;
        }

        public String keyOf(SeasonFileRow r) {
            return keyOf[r.id()];
        }

        public List<Issue> issues() {
            return issues;
        }

        public List<Issue> issuesFor(int seasonStart) {
            return issues.stream().filter(i -> i.seasonStart() == seasonStart).toList();
        }

        public List<SeasonFileRow> rowsForEnd(int endYear) {
            List<SeasonFileRow> out = new ArrayList<>();
            Map<String, List<SeasonFileRow>> m = byEndKey.get(endYear);
            if (m != null) m.values().forEach(out::addAll);
            out.sort((a, b) -> Integer.compare(a.id(), b.id()));
            return out;
        }

        private void assignKeys() {
            for (SeasonFileRow r : rows) {
                // team:age first, for two people of one name on the same team in one season
                String k = curation.sameName().get(r.endYear() + "|" + r.team() + ":" + r.age() + "|" + r.norm());
                if (k == null) k = curation.sameName().get(r.endYear() + "|" + r.team() + "|" + r.norm());
                if (k == null) {
                    if (curation.curatedNames().contains(r.norm())) {
                        issues.add(new Issue(r.seasonStart(), "UNKEYED", r.name() + " " + r.endYear() + " "
                                + r.team() + " (age " + r.age() + ") is a curated same-name but this row has no key"));
                    }
                    k = r.norm();
                }
                keyOf[r.id()] = k;
                byEndNorm.computeIfAbsent(r.endYear(), x -> new HashMap<>())
                        .computeIfAbsent(r.norm(), x -> new ArrayList<>()).add(r);
                byEndKey.computeIfAbsent(r.endYear(), x -> new LinkedHashMap<>())
                        .computeIfAbsent(k, x -> new ArrayList<>()).add(r);
            }
        }

        /** A season with several non-TOT rows for one person key and no TOT row is two people (R5). */
        private void detectCollisions() {
            for (Map.Entry<Integer, Map<String, List<SeasonFileRow>>> e : byEndKey.entrySet()) {
                for (Map.Entry<String, List<SeasonFileRow>> p : e.getValue().entrySet()) {
                    List<SeasonFileRow> rs = p.getValue();
                    boolean hasTot = rs.stream().anyMatch(r -> TOT.equals(r.team()));
                    long teams = rs.stream().filter(r -> !TOT.equals(r.team())).count();
                    if (!hasTot && teams > 1) {
                        StringBuilder sb = new StringBuilder(rs.get(0).name() + " " + (e.getKey() - 1) + ": ");
                        for (SeasonFileRow r : rs) sb.append(r.team()).append("(age ").append(r.age()).append(") ");
                        issues.add(new Issue(e.getKey() - 1, "COLLISION", sb.toString().trim()));
                    }
                }
            }
        }

        /**
         * Same person key in seasons S and S+k must be about k years older (k-1..k+1), per plan-review F2. Ages are
         * compared on the chosen row per season.
         */
        private void detectAgeJumps() {
            Map<String, TreeMap<Integer, SeasonFileRow>> perKey = new HashMap<>();
            for (Map.Entry<Integer, Map<String, List<SeasonFileRow>>> e : byEndKey.entrySet()) {
                for (Map.Entry<String, List<SeasonFileRow>> p : e.getValue().entrySet()) {
                    perKey.computeIfAbsent(p.getKey(), x -> new TreeMap<>()).put(e.getKey(), choose(p.getValue()));
                }
            }
            for (Map.Entry<String, TreeMap<Integer, SeasonFileRow>> e : perKey.entrySet()) {
                SeasonFileRow prev = null;
                for (SeasonFileRow cur : e.getValue().values()) {
                    if (prev != null && prev.age() != null && cur.age() != null) {
                        int k = cur.endYear() - prev.endYear();
                        int diff = cur.age() - prev.age();
                        if (diff < k - 1 || diff > k + 1) {
                            issues.add(new Issue(cur.seasonStart(), "AGE_JUMP", cur.name() + " " + prev.seasonStart()
                                    + " " + prev.team() + " age " + prev.age() + " -> " + cur.seasonStart() + " "
                                    + cur.team() + " age " + cur.age()));
                        }
                    }
                    prev = cur;
                }
            }
        }

        /** The person-seasons of one season (end year), in file order of their first row. */
        public List<PersonSeason> personSeasons(int endYear) {
            List<PersonSeason> out = new ArrayList<>();
            Map<String, List<SeasonFileRow>> m = byEndKey.get(endYear);
            if (m == null) return out;
            for (Map.Entry<String, List<SeasonFileRow>> e : m.entrySet()) {
                List<SeasonFileRow> rs = e.getValue();
                SeasonFileRow chosen = choose(rs);
                String lastTeam = chosen.team();
                for (SeasonFileRow r : rs) if (!TOT.equals(r.team())) lastTeam = r.team();
                out.add(new PersonSeason(e.getKey(), chosen.name(), chosen, lastTeam, rs));
            }
            out.sort((a, b) -> Integer.compare(a.chosen().id(), b.chosen().id()));
            return out;
        }

        private static SeasonFileRow choose(List<SeasonFileRow> rs) {
            for (SeasonFileRow r : rs) if (TOT.equals(r.team())) return r;
            return rs.get(0);
        }

        // ---- matching box-score rows -----------------------------------------------------------------

        /**
         * A season-file team equals the box team either verbatim or through team-codes.csv. Both, because a
         * crosswalk entry is right for some seasons and wrong for others (box WAS is season-file WSB in the 1970s
         * and plain WAS from 1998).
         */
        private static boolean sameTeam(String seasonTeam, String boxTeam, String mapped) {
            return seasonTeam != null && (seasonTeam.equals(boxTeam) || seasonTeam.equals(mapped));
        }

        /** A box-score name+team resolved to a person key, or why not. */
        public record Match(String personKey, String problem, boolean teamMismatch, String seasonTeams) {}

        /**
         * (season, team, name) then (season, name) then the alias file (research R5). A name with several keys in
         * the season (curated same-name) is narrowed by team; if the team cannot choose, it is AMBIGUOUS and
         * unmatched. A name whose season rows never list the (mapped) box team is {@link #TEAM_MISMATCH} and is
         * also left unmatched; the report lists these so a missing crosswalk entry shows up there.
         */
        public Match match(int endYear, String boxName, String boxTeam) {
            String norm = normalize(boxName);
            Map<String, List<SeasonFileRow>> names = byEndNorm.get(endYear);
            List<SeasonFileRow> cands = names == null ? null : names.get(norm);
            if (cands == null) {
                String alias = curation.aliases().get(endYear + "|" + norm);
                if (alias != null && names != null) cands = names.get(normalize(alias));
            }
            if (cands == null) return new Match(null, "NO_SEASON_ROW", false, "");
            String mapped = curation.mapTeam(boxTeam);
            Set<String> keys = new LinkedHashSet<>();
            for (SeasonFileRow r : cands) keys.add(keyOf[r.id()]);
            boolean teamHit = cands.stream().anyMatch(r -> !TOT.equals(r.team()) && sameTeam(r.team(), boxTeam, mapped));
            String teams = String.join("/", cands.stream().map(SeasonFileRow::team).distinct().toList());
            if (keys.size() == 1) {
                // The name is unique that season but none of that person's team rows is the box team: the box
                // rows are another player of the same name (or a traded player the season file lists under only
                // one team). Merging them would double-count games (measured: Greg Smith 1973-74, 143 box rows
                // for a 67-game season line), so they are NOT attributed to him.
                if (!teamHit) return new Match(null, TEAM_MISMATCH, true, teams);
                return new Match(keys.iterator().next(), null, false, teams);
            }
            Set<String> byTeam = new LinkedHashSet<>();
            for (SeasonFileRow r : cands) if (sameTeam(r.team(), boxTeam, mapped)) byTeam.add(keyOf[r.id()]);
            if (byTeam.size() == 1) return new Match(byTeam.iterator().next(), null, false, teams);
            // several curated people share this name AND the box team cannot tell them apart (same team, same
            // season). Box rows carry no player id, so these rows are not forced onto either person.
            return new Match(null, AMBIGUOUS_SAME_TEAM, true, teams);
        }
    }
}
