package com.ballknowers.draftsim.historical;

import com.ballknowers.draftsim.config.NflHistoricalProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 056 T022 and T027: a whole football game on {@link NflSyntheticSeason} -- create, the bots, auto FINISH, the
 * replay, reopen -- against Postgres, plus the provenance rules (data notes, attribution) and that an NBA game still
 * discloses exactly what 053 did. Everything is compared as totals and lineup sets, never JSON order (review F12).
 *
 * <p>SKIPS when Postgres is not reachable at {@code DB_URL}, so read the skip count.
 */
@SpringBootTest
class NflGameFlowIT {

    private static final String ALICE = "it-nflgame-alice";
    private static final String NOTE_GAMES = "60 of 64 regular-season games are in the data";
    private static final String NOTE_DROPPED = "3 source rows with no player id (a skill position or scored stats) were left out";
    private static final String NOTE_POSITIONS =
            "Positions are each player's current listing, not necessarily his position that season";

    @BeforeAll
    static void requiresPostgres() {
        String url = System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5433/draftsim");
        boolean reachable;
        try (Connection c = DriverManager.getConnection(url, "draftsim", "draftsim")) {
            reachable = true;
        } catch (SQLException e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable, "no Postgres reachable at " + url + " -- skipping NFL game flow IT");
    }

    @Autowired private HistoricalGameService service;
    @Autowired private NflHistoricalRepository nfl;
    @Autowired private HistoricalDataRepository data;
    @Autowired private NflHistoricalProperties props;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        cleanGames();
        NflSyntheticSeason.clean(jdbc);
        NflSyntheticSeason.seed(nfl, jdbc);
    }

    @AfterAll
    static void removeTheMadeUpWorlds() {
        String url = System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5433/draftsim");
        try {
            JdbcTemplate j = new JdbcTemplate(
                    new org.springframework.jdbc.datasource.DriverManagerDataSource(url, "draftsim", "draftsim"));
            j.update("delete from historical_game where sleeper_user_id like 'it-nflgame-%'");
            NflSyntheticSeason.clean(j);
            HistoricalSyntheticSeason.clean(j);
        } catch (RuntimeException e) {
            // Postgres was unreachable, so nothing was seeded either.
        }
    }

    private void cleanGames() {
        jdbc.update("delete from historical_game where sleeper_user_id like 'it-nflgame-%'");
    }

    private JsonNode create(int slot) {
        return json.valueToTree(service.create(ALICE, "NFL", slot, NflSyntheticSeason.S));
    }

    private static JsonNode cardNamed(JsonNode cards, String name) {
        for (JsonNode c : cards) if (c.get("name").asText().equals(name)) return c;
        throw new AssertionError("no card named " + name);
    }

    private int indexOf(long playerId) {
        String key = jdbc.queryForObject("select source_key from historical_player where id = ?", String.class, playerId);
        return Integer.parseInt(key.substring(NflSyntheticSeason.PREFIX.length()));
    }

    // ---- the draft room ------------------------------------------------------------------------------

    @Test
    void aFootballDraftingStateHasOnlySkillPositionsAndNothingFromTheDraftedSeason() {
        JsonNode s = create(5);
        assertEquals("NFL", s.get("sport").asText());
        assertEquals("DRAFTING", s.get("status").asText());
        assertEquals(12, s.get("teams").asInt());
        assertEquals(15, s.get("rounds").asInt());
        assertEquals(5, s.get("currentPickNo").asInt(), "bots made picks 1..4, then it is the user's turn");
        assertEquals("1895", s.get("season").get("label").asText());
        assertEquals("Hand-set 12-team, 15-round roster: QB, 2 RB, 2 WR, TE, FLEX + 8 bench · full PPR", s.get("season").get("settingsSource").asText());
        assertEquals("ranked by last season's points over replacement", s.get("season").get("rankingLabel").asText());
        assertEquals(json.valueToTree(NflHistoricalSport.COUNTING), s.get("season").get("countedStats"));
        assertTrue(s.get("season").get("excludedStats").isEmpty());
        assertTrue(s.get("season").get("statCoverage").isEmpty());

        Set<String> allowed = Set.of("QB", "RB", "WR", "TE");
        JsonNode available = s.get("available");
        assertEquals(250 - 4, available.size());
        for (JsonNode c : available) {
            for (JsonNode p : c.get("positions")) assertTrue(allowed.contains(p.asText()), "position " + p);
        }

        // FR-004: the drafted season's sentinel line (team ZZZ, 91 games, 7777 passing yards ...) is nowhere.
        String text = s.toString();
        assertFalse(text.contains("ZZZ"), "the drafted season's team leaked");
        assertFalse(text.contains("\"games\":91"), "the drafted season's games leaked");
        // SC-006: no ADP, anywhere
        assertFalse(java.util.regex.Pattern.compile("\\badp\\b", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(text).find(), "the word ADP appears in the state");

        // the board is rank-ascending and a veteran's card is last season's line
        int prev = 0;
        for (JsonNode c : available) {
            assertTrue(c.get("rank").asInt() > prev);
            prev = c.get("rank").asInt();
        }
        JsonNode top = available.get(0);
        assertEquals("1894", top.get("prior").get("seasonLabel").asText());
        assertEquals(16, top.get("prior").get("games").asInt());
        assertTrue(top.get("age").isNull(), "football cards have no age");
        assertTrue(top.get("rookiePick").isNull(), "a veteran has no rookie pick");
        assertTrue(top.get("prior").get("totals").isObject());
    }

    @Test
    void rookiesAreRankedByTheirNflPickAndLateOnesStayInTheTail() {
        // Every veteran is on the board at creation except those the first four bots took; slot 12 sees pick 12 next.
        JsonNode s = create(12);
        JsonNode cards = s.get("available");
        JsonNode r240 = cardNamed(cards, "Synthetic 240");   // RB, round 1 pick 5
        JsonNode r243 = cardNamed(cards, "Synthetic 243");   // TE, round 3 pick 80: not placed by his pick
        assertEquals(5, r240.get("rookiePick").asInt());
        assertEquals(80, r243.get("rookiePick").asInt());
        assertTrue(r240.get("prior").isNull(), "a rookie's card has no prior line");
        assertTrue(r240.get("team").isNull());

        int worstVetRank = 0, bestRbRank = Integer.MAX_VALUE, worstRbRank = 0;
        for (JsonNode c : cards) {
            if (!c.get("prior").isNull()) {
                worstVetRank = Math.max(worstVetRank, c.get("rank").asInt());
                if (c.get("positions").get(0).asText().equals("RB")) {
                    bestRbRank = Math.min(bestRbRank, c.get("rank").asInt());
                    worstRbRank = Math.max(worstRbRank, c.get("rank").asInt());
                }
            }
        }
        int rk = r240.get("rank").asInt();
        assertTrue(rk > bestRbRank && rk < worstRbRank, "the round-1 RB sits inside the veteran RBs, not first or last");
        assertTrue(r243.get("rank").asInt() > worstVetRank, "a round-3 rookie is in the tail behind every veteran");
    }

    @Test
    void twoPlayersWithOneDisplayNameAreTwoCards() {
        JsonNode s = create(3);
        Set<Long> ids = new HashSet<>();
        for (JsonNode c : s.get("available")) {
            if (c.get("name").asText().equals(NflSyntheticSeason.TWIN)) ids.add(c.get("id").asLong());
        }
        // players 10 and 11 might have been taken by the first two bots, so count the picks made so far as well
        for (JsonNode p : s.get("picks")) {
            if (p.get("player").get("name").asText().equals(NflSyntheticSeason.TWIN)) {
                ids.add(p.get("player").get("id").asLong());
            }
        }
        assertEquals(2, ids.size(), "SC-007: two ids, two cards, one name");
    }

    // ---- the whole game ------------------------------------------------------------------------------

    @Test
    void autoFinishReplaysTheSeasonAndOneTeamsWeeksMatchAHandCalculation() {
        long id = service.create(ALICE, "NFL", 7, NflSyntheticSeason.S).id();
        long t0 = System.nanoTime();
        JsonNode done = json.valueToTree(
                service.auto(id, HistoricalGameService.Scope.FINISH, ALICE).orElseThrow());
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertEquals("COMPLETE", done.get("status").asText());
        assertEquals(12 * 15, done.get("picks").size());
        assertEquals(0, done.get("available").size());
        JsonNode result = done.get("result");

        assertEquals(NflSyntheticSeason.WEEKS, result.get("weeks").size(), "weekCount weeks, from the season row");
        for (JsonNode w : result.get("weeks")) {
            assertEquals(6, w.get("matchups").size());
            assertTrue(w.get("startDate").isNull(), "football has no dates (F4)");
        }
        assertEquals(12, result.get("standings").size());
        Set<Integer> places = new HashSet<>();
        int wins = 0, losses = 0, ties = 0, games = 0;
        for (JsonNode st : result.get("standings")) {
            places.add(st.get("place").asInt());
            wins += st.get("wins").asInt();
            losses += st.get("losses").asInt();
            ties += st.get("ties").asInt();
            games += st.get("wins").asInt() + st.get("losses").asInt() + st.get("ties").asInt();
        }
        assertEquals(12, places.size());
        assertEquals(wins, losses, "every game has a winner and a loser");
        assertEquals(0, ties % 2);
        assertEquals(12 * NflSyntheticSeason.WEEKS, games, "every team played every week");
        int userFinish = result.get("userFinish").asInt();
        assertTrue(userFinish >= 1 && userFinish <= 12);
        assertEquals(15, result.get("hindsight").get("picks").size());
        assertEquals("greedy hindsight, bots unchanged", result.get("hindsight").get("label").asText());

        // ---- the hand calculation (SC-003 shape): the user's roster, week by week, from the fixture alone ----
        List<Integer> roster = new ArrayList<>();
        for (JsonNode p : done.get("picks")) {
            if (p.get("seatType").asText().equals("USER")) roster.add(indexOf(p.get("player").get("id").asLong()));
        }
        assertEquals(15, roster.size());
        JsonNode lineups = result.get("lineups").get("7");
        assertEquals(NflSyntheticSeason.WEEKS, lineups.size());
        boolean sawFlex = false;
        for (JsonNode lw : lineups) {
            int week = lw.get("week").asInt();
            Map<String, List<double[]>> byPos = new HashMap<>();   // position -> [points, index]
            for (int i : roster) {
                if (!NflSyntheticSeason.hasGame(i, week)) continue;   // a bye scores nothing and sits out
                double pts = NflSyntheticSeason.points(NflSyntheticSeason.weekStats(i, week), props.scoring());
                byPos.computeIfAbsent(NflSyntheticSeason.position(i), k -> new ArrayList<>()).add(new double[]{pts, i});
            }
            byPos.values().forEach(l -> l.sort(Comparator.comparingDouble((double[] a) -> a[0]).reversed()));
            List<Double> expected = new ArrayList<>();
            List<double[]> leftover = new ArrayList<>();
            int[] slots = {1, 2, 2, 1};   // QB, RB, WR, TE
            String[] order = {"QB", "RB", "WR", "TE"};
            for (int k = 0; k < order.length; k++) {
                List<double[]> l = byPos.getOrDefault(order[k], List.of());
                for (int j = 0; j < l.size(); j++) {
                    if (j < slots[k]) expected.add(l.get(j)[0]);
                    else if (!order[k].equals("QB")) leftover.add(l.get(j));   // a QB never flexes
                }
            }
            leftover.stream().max(Comparator.comparingDouble((double[] a) -> a[0]))
                    .ifPresent(a -> expected.add(a[0]));
            expected.sort(Comparator.reverseOrder());

            List<Double> actual = new ArrayList<>();
            for (JsonNode st : lw.get("starters")) {
                int i = indexOf(st.get("playerId").asLong());
                assertTrue(NflSyntheticSeason.hasGame(i, week), "player " + i + " has a bye in week " + week + " and cannot start");
                assertTrue(st.get("gameDate").isNull(), "F4: no date, and nothing threw");
                String slot = st.get("slot").asText();
                String pos = NflSyntheticSeason.position(i);
                if (slot.equals("FLEX")) {
                    sawFlex = true;
                    assertNotEquals("QB", pos, "a quarterback never takes the FLEX");
                } else {
                    assertEquals(pos, slot, "a TE stays a TE, a WR stays a WR: slot " + slot + " held a " + pos);
                }
                actual.add(st.get("points").asDouble());
            }
            actual.sort(Comparator.reverseOrder());
            assertEquals(expected.size(), actual.size(), "week " + week + " starter count");
            for (int k = 0; k < expected.size(); k++) {
                assertEquals(expected.get(k), actual.get(k), 0.011, "week " + week + " starter " + k);
            }
            double total = actual.stream().mapToDouble(Double::doubleValue).sum();
            double expectedTotal = expected.stream().mapToDouble(Double::doubleValue).sum();
            assertEquals(expectedTotal, total, 0.05, "week " + week + " total");
        }
        assertTrue(sawFlex, "a 15-player roster fills the FLEX in at least one week");

        // reopening returns the frozen result unchanged
        JsonNode reopened = json.valueToTree(service.get(id, ALICE).orElseThrow()).get("result");
        assertEquals(result, reopened);
        System.out.println("NflGameFlowIT: auto FINISH (rest of the draft + replay) took " + ms + " ms");
    }

    // ---- provenance (T027) ---------------------------------------------------------------------------

    @Test
    void theDataNotesAndAttributionAreOnEveryFootballStateAndFrozenAtCreation() {
        JsonNode s = create(2);
        long gameId = s.get("id").asLong();
        List<String> notes = new ArrayList<>();
        s.get("season").get("dataNotes").forEach(n -> notes.add(n.asText()));
        assertEquals(List.of(NOTE_GAMES, NOTE_DROPPED, NOTE_POSITIONS), notes);
        assertEquals("Stats: nflverse (nflverse-data), CC BY 4.0", s.get("season").get("attribution").asText());

        // a re-import fixes the counts after this game started: the game keeps what it disclosed ...
        jdbc.update("update historical_season set expected_game_count = game_count, dropped_rows = 0"
                + " where sport = 'NFL' and season_start = ?", NflSyntheticSeason.S);
        JsonNode reopened = json.valueToTree(service.get(gameId, ALICE).orElseThrow());
        assertEquals(s.get("season").get("dataNotes"), reopened.get("season").get("dataNotes"));
        // ... and the next game starts clean: only the always-on positions note remains
        JsonNode fresh = create(2);
        List<String> freshNotes = new ArrayList<>();
        fresh.get("season").get("dataNotes").forEach(n -> freshNotes.add(n.asText()));
        assertEquals(List.of(NOTE_POSITIONS), freshNotes);
        assertEquals("Stats: nflverse (nflverse-data), CC BY 4.0", fresh.get("season").get("attribution").asText());

        // attribution survives to the finished state too
        JsonNode done = json.valueToTree(service.auto(fresh.get("id").asLong(),
                HistoricalGameService.Scope.FINISH, ALICE).orElseThrow());
        assertEquals("COMPLETE", done.get("status").asText());
        assertEquals("Stats: nflverse (nflverse-data), CC BY 4.0", done.get("season").get("attribution").asText());
    }

    @Test
    void aBasketballStateHasNoAttributionNoDataNotesAndItsOwn053Disclosure() {
        HistoricalSyntheticSeason.clean(jdbc);
        HistoricalSyntheticSeason.seed(data);
        try {
            JsonNode nba = json.valueToTree(service.create(ALICE, "NBA", 4, HistoricalSyntheticSeason.S));
            assertEquals("NBA", nba.get("sport").asText());
            assertTrue(nba.get("season").get("attribution").isNull());
            assertTrue(nba.get("season").get("dataNotes").isArray() && nba.get("season").get("dataNotes").isEmpty());
            assertEquals(NbaHistoricalSport.SETTINGS_SOURCE, nba.get("season").get("settingsSource").asText());
            assertEquals("ranked by last season's points", nba.get("season").get("rankingLabel").asText());
            assertEquals("1895-96", nba.get("season").get("label").asText());
            assertTrue(nba.get("season").get("excludedStats").has("stl"), "its 053 era disclosure is intact");
        } finally {
            cleanGames();
            HistoricalSyntheticSeason.clean(jdbc);
        }
    }

    @Test
    void anUnknownSportIsRefusedBeforeAnythingIsWritten() {
        assertThrows(IllegalArgumentException.class, () -> service.create(ALICE, "MLB", 1, 1895));
        assertThrows(IllegalArgumentException.class, () -> service.create(ALICE, null, 1, 1895));
        assertThrows(IllegalArgumentException.class, () -> service.create(ALICE, "NFL", 13, NflSyntheticSeason.S),
                "slot 13 of a 12-team league");
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from historical_game where sleeper_user_id like 'it-nflgame-%'", Integer.class));
    }
}
