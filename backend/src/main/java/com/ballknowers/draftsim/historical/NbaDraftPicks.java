package com.ballknowers.draftsim.historical;

import com.ballknowers.draftsim.historical.HistoricalBoardBuilder.Candidate;
import com.ballknowers.draftsim.historical.HistoricalBoardBuilder.RookiePick;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The real NBA draft order, for placing first-season players on a basketball Time Machine board (Allan
 * 2026-11-15: rookies were shuffled at random behind every veteran). Read from the bundled
 * {@code historical/nba-draft-picks.csv}: picks 1 to 60 of every draft from 1966 to 2026.
 *
 * <p>The basketball tables carry no draft data and no shared player key with this file, so a player is matched
 * to his draft BY NAME. That is the weak point, and these are the guards:
 * <ul>
 *   <li>Only a player with no season line before the drafted season is looked up, so a veteran can never be
 *       mistaken for a same-named draftee.</li>
 *   <li>Only drafts from the last few years count ({@code maxSeasonsSinceDraft}): a player drafted in 2014 who
 *       first played in 2016 is found, a namesake drafted in 1985 is not.</li>
 *   <li>A name that appears twice in one draft, or on two first-season players of one board, matches nobody.</li>
 * </ul>
 * A rookie this does not find (undrafted, drafted after pick 60, or spelled differently in the two sources)
 * stays where he was before: in the random tail of the board.
 *
 * <p>Measured 2026-11-15 against the names in the Kaggle season file (the import's identity authority), NOT
 * against an imported database: of the top 14 picks of each draft, the rule finds 95% for 1970-84, 99% for
 * 1985-99, 97% for 2000-10 and 99% for 2011-21. Most of the rest never played, or share a name with an
 * earlier player (a son; that measurement could not tell the two apart, the app's curated ids may). The
 * Sleeper-sourced seasons (2022 on) were not measured at all.
 *
 * <p>Pure: no Spring, no database.
 */
public final class NbaDraftPicks {

    static final String RESOURCE = "/historical/nba-draft-picks.csv";

    /** Draft year to (name key to pick). A name drafted twice in one year is absent from that year's map. */
    private final Map<Integer, Map<String, RookiePick>> byYear;

    private NbaDraftPicks(Map<Integer, Map<String, RookiePick>> byYear) {
        this.byYear = byYear;
    }

    public static NbaDraftPicks fromClasspath() {
        try (InputStream in = NbaDraftPicks.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("missing classpath resource " + RESOURCE);
            return parse(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** {@code season,pick,round,player}; the name is the last column and may hold commas. '#' lines are comments. */
    static NbaDraftPicks parse(Reader reader) throws IOException {
        Map<Integer, Map<String, RookiePick>> byYear = new HashMap<>();
        Map<Integer, Set<String>> twice = new HashMap<>();
        BufferedReader br = new BufferedReader(reader);
        boolean header = true;
        for (String line; (line = br.readLine()) != null; ) {
            if (line.isBlank() || line.startsWith("#")) continue;
            if (header) {
                if (!line.trim().equals("season,pick,round,player")) {
                    throw new IllegalStateException(RESOURCE + " has an unexpected header: " + line);
                }
                header = false;
                continue;
            }
            String[] cells = line.split(",", 4);
            if (cells.length < 4) throw new IllegalStateException(RESOURCE + " has a short row: " + line);
            int year = Integer.parseInt(cells[0].trim());
            RookiePick pick = new RookiePick(Integer.parseInt(cells[2].trim()), Integer.parseInt(cells[1].trim()));
            String name = cells[3].trim();
            if (name.length() >= 2 && name.startsWith("\"") && name.endsWith("\"")) {
                name = name.substring(1, name.length() - 1).replace("\"\"", "\"");
            }
            String key = key(name);
            if (key.isEmpty()) continue;
            if (byYear.computeIfAbsent(year, y -> new HashMap<>()).put(key, pick) != null) {
                twice.computeIfAbsent(year, y -> new HashSet<>()).add(key);
            }
        }
        twice.forEach((year, keys) -> keys.forEach(byYear.get(year)::remove));
        return new NbaDraftPicks(byYear);
    }

    /**
     * The name both sources are compared on: {@link KaggleIdentity#normalize} (accents, punctuation, case), with
     * a trailing Jr/Sr/II/III/IV dropped, because one source writes "Jaren Jackson Jr." and another may not.
     */
    static String key(String name) {
        return KaggleIdentity.normalize(name).replaceAll(" (jr|sr|ii|iii|iv)$", "");
    }

    /**
     * The first-season players of one board who can be found in a recent draft, by player id.
     *
     * @param season               the drafted season's start year; a rookie's draft is normally that same year
     * @param candidates           everyone on the board
     * @param veterans             ids with a season line before {@code season}; never matched
     * @param maxSeasonsSinceDraft how far back a draft may be (0 = this year's draft only); the newest match wins
     */
    public Map<Long, RookiePick> rookiesOf(int season, List<Candidate> candidates, Set<Long> veterans,
                                           int maxSeasonsSinceDraft) {
        // With no veteran on the board the data cannot tell a rookie from anyone else (HistoricalRookies).
        if (candidates.stream().noneMatch(c -> veterans.contains(c.id()))) return Map.of();

        Map<String, Long> firstSeason = new HashMap<>();
        Set<String> shared = new HashSet<>();
        for (Candidate c : candidates) {
            if (veterans.contains(c.id())) continue;
            if (firstSeason.put(key(c.name()), c.id()) != null) shared.add(key(c.name()));
        }
        Map<Long, RookiePick> out = new LinkedHashMap<>();
        for (Candidate c : candidates) {
            if (veterans.contains(c.id())) continue;
            String key = key(c.name());
            if (shared.contains(key)) continue;
            for (int year = season; year >= season - maxSeasonsSinceDraft; year--) {
                RookiePick pick = byYear.getOrDefault(year, Map.of()).get(key);
                if (pick != null) {
                    out.put(c.id(), pick);
                    break;
                }
            }
        }
        return out;
    }
}
