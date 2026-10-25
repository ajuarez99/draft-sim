package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.engine.RivalryService.PairRecord;
import com.ballknowers.draftsim.engine.RivalryService.Response;
import com.ballknowers.draftsim.store.LeagueBracketRepository;
import com.ballknowers.draftsim.store.LeagueMemberRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * specs/034 T016 / SC-001 / SC-002: every matrix cell equals what the versus page
 * ({@link HeadToHeadService#compute}) reports for the same pair, restricted to the chain's own leagues,
 * on a synthetic fixture shaped like the real data -- plus the HTTP surface (T017's shape and scoping).
 *
 * <p>Two NFL chains share managers m1 and m2 (plan.md F13): {@code HeadToHeadService} buckets every shared
 * league of one sport together, so for that pair its totals span both chains and only a recount filtered to
 * {@code sleeperLeagueId in chain} can equal the matrix. Roster numbers differ between chains, so a join on
 * roster instead of manager would fail. An NBA chain proves sports never mix. SKIPS when Postgres isn't
 * reachable at the URL the app itself would use ({@code DB_URL}), so read the skip count.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RivalryParityIT {

    private static final String X25 = "it-riv-x25", X26 = "it-riv-x26", Y26 = "it-riv-y26",
            Z25 = "it-riv-z25", Z26 = "it-riv-z26";
    private static final List<String> LEAGUES = List.of(X25, X26, Y26, Z25, Z26);
    private static final String PLAYOFF_SETTINGS = "{\"playoff_week_start\":7,\"playoff_teams\":4,\"playoff_round_type\":0}";

    @BeforeAll
    static void requiresPostgres() {
        String url = System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5433/draftsim");
        boolean reachable;
        try (Connection c = DriverManager.getConnection(url, "draftsim", "draftsim")) {
            reachable = true;
        } catch (SQLException e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable, "no Postgres reachable at " + url + " -- skipping rivalry parity IT");
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private RivalryService rivalries;
    @Autowired private HeadToHeadService headToHead;
    @Autowired private LeagueBracketRepository brackets;
    @Autowired private LeagueMemberRepository leagueMembers;
    @Autowired private MockMvc mvc;

    private final long[] m = new long[7];   // m[1..6]
    private final long[] n = new long[5];   // n[1..4], the NBA managers
    private long x25Id;

    @BeforeEach
    void setUp() {
        cleanup();
        for (int i = 1; i <= 6; i++) m[i] = manager("it-riv-m" + i);
        for (int i = 1; i <= 4; i++) n[i] = manager("it-riv-n" + i);

        // Chain X (NFL, 2025 -> 2026): rosters 1..6 = m1..m6, playoffs from week 7 (4 teams, round type 0).
        x25Id = league(X25, "nfl", 2025, null, PLAYOFF_SETTINGS);
        long x26 = league(X26, "nfl", 2026, X25, PLAYOFF_SETTINGS);
        for (long lid : new long[]{x25Id, x26}) {
            for (int r = 1; r <= 6; r++) roster(lid, r, m[r]);
        }
        sixTeamSeason(x25Id, 2025, 11);
        sixTeamSeason(x26, 2026, 29);
        // 2025 winners bracket: r1 (1,2) w1 and (3,4) w3; r2 final (1,3) w1 p1, third place (2,4) w4 p3.
        brackets.replaceForLeague(x25Id, List.of(
                new LeagueBracketRepository.Match(x25Id, 1, 1, 1, 2, 1, 2, null),
                new LeagueBracketRepository.Match(x25Id, 2, 1, 3, 4, 3, 4, null),
                new LeagueBracketRepository.Match(x25Id, 3, 2, 1, 3, 1, 3, 1),
                new LeagueBracketRepository.Match(x25Id, 4, 2, 2, 4, 4, 2, 3)));

        // Chain Y (NFL, 2026 only): rosters 1..4 = m2, m3, m4, m1 -- different roster numbers, same managers.
        long y26 = league(Y26, "nfl", 2026, null, "{}");
        long[] yMgr = {0, m[2], m[3], m[4], m[1]};
        for (int r = 1; r <= 4; r++) roster(y26, r, yMgr[r]);
        for (int w = 1; w <= 6; w++) for (int[] p : roundRobin(4, w)) score(y26, 2026, w, p, 17);

        // Chain Z (NBA, 2025 -> 2026): different managers entirely.
        long z25 = league(Z25, "nba", 2025, null, "{}");
        long z26 = league(Z26, "nba", 2026, Z25, "{}");
        for (long lid : new long[]{z25, z26}) for (int r = 1; r <= 4; r++) roster(lid, r, n[r]);
        for (int w = 1; w <= 6; w++) {
            for (int[] p : roundRobin(4, w)) { score(z25, 2025, w, p, 3); score(z26, 2026, w, p, 5); }
        }
    }

    @AfterEach
    void cleanup() {
        for (String s : LEAGUES) jdbc.update("delete from league where sleeper_id = ?", s);
        jdbc.update("delete from manager where sleeper_user_id like 'it-riv-%'");
    }

    // ---------------------------------------------------------------- the parity check

    @Test
    void everyMatrixCellEqualsTheVersusPageRestrictedToTheChain() {
        Response r = rivalries.forLeague(X26, null).orElseThrow();
        Set<String> chain = Set.of(X25, X26);
        assertEquals(15, r.matrix().pairs().size());
        boolean sawSpanningPair = false;
        for (PairRecord p : r.matrix().pairs()) {
            var h2h = headToHead.compute(p.aManagerId(), p.bManagerId());
            var nfl = h2h.sports().stream().filter(s -> s.sport() == Sport.NFL).findFirst().orElseThrow();
            var inChain = nfl.meetings().stream().filter(x -> chain.contains(x.sleeperLeagueId()))
                    .sorted(java.util.Comparator.comparingInt(HeadToHeadService.Meeting::season)
                            .thenComparingInt(HeadToHeadService.Meeting::week)).toList();
            int a = 0, b = 0, t = 0;
            for (var mt : inChain) {
                switch (mt.winner()) {
                    case "A" -> a++;
                    case "B" -> b++;
                    default -> t++;
                }
            }
            String label = p.aManagerId() + "-" + p.bManagerId();
            assertEquals(inChain.size(), p.meetings(), label);
            assertEquals(a, p.aWins(), label);
            assertEquals(b, p.bWins(), label);
            assertEquals(t, p.ties(), label);
            assertEquals(inChain.size(), p.games().size(), label);
            for (int i = 0; i < inChain.size(); i++) {
                // The versus page lists newest season first; the matrix lists chronologically.
                var mt = inChain.get(i);
                var line = p.games().get(i);
                assertEquals(mt.season(), line.season(), label);
                assertEquals(mt.week(), line.week(), label);
                assertEquals(mt.aPoints(), line.aPoints(), 1e-9, label);
                assertEquals(mt.bPoints(), line.bPoints(), 1e-9, label);
                assertEquals(mt.winner(), line.winner(), label);
            }
            int byKindTotal = p.byKind().values().stream().mapToInt(k -> k.aWins() + k.bWins() + k.ties()).sum();
            assertEquals(p.meetings(), byKindTotal, "SC-002: the kinds add up to the total for " + label);
            if (nfl.meetings().size() > inChain.size()) sawSpanningPair = true;
        }
        assertTrue(sawSpanningPair, "the fixture must include a pair the versus page counts across both chains (F13)");
    }

    @Test
    void thePlayoffWeeksSplitAgainstTheStoredBracketAndTheFinalIsRecorded() {
        Response r = rivalries.forLeague(X26, null).orElseThrow();
        PairRecord p13 = r.matrix().pairs().stream()
                .filter(p -> p.aManagerId() == m[1] && p.bManagerId() == m[3]).findFirst().orElseThrow();
        assertEquals(1, p13.finals().size());
        assertEquals(2025, p13.finals().get(0).season());
        assertEquals("A", p13.finals().get(0).winner(), "roster 1 won the 2025 final (bracket w)");
        assertFalse(p13.finals().get(0).twoWeek());
        var playoff = p13.byKind().get("PLAYOFF");
        assertEquals(1, playoff.aWins() + playoff.bWins() + playoff.ties(),
                "exactly the 2025 final is a playoff game for 1 v 3 (2026 has no bracket, so it is unsplit)");
        PairRecord p24 = r.matrix().pairs().stream()
                .filter(p -> p.aManagerId() == m[2] && p.bManagerId() == m[4]).findFirst().orElseThrow();
        assertEquals(1, p24.byKind().get("PLACEMENT").aWins() + p24.byKind().get("PLACEMENT").bWins());
        // 2026's playoff weeks have no stored bracket in this fixture: unsplit, and named.
        assertEquals(1, r.matrix().splitNotes().size());
        assertEquals(2026, r.matrix().splitNotes().get(0).season());
        assertEquals("NO_BRACKET", r.matrix().splitNotes().get(0).reason());
        PairRecord p56 = r.matrix().pairs().stream()
                .filter(p -> p.aManagerId() == m[5] && p.bManagerId() == m[6]).findFirst().orElseThrow();
        int consolation = p56.byKind().get("CONSOLATION").aWins() + p56.byKind().get("CONSOLATION").bWins()
                + p56.byKind().get("CONSOLATION").ties();
        assertEquals(2, consolation, "5 v 6 only meet in the 2025 consolation weeks 7 and 8 of the playoff weeks");
    }

    /**
     * specs/047 T019 (SC-002/SC-003): every discovery entry IS its matrix cell, field for field, and the
     * claim lists never name a pair below N meetings. Run on one NFL and one NBA chain.
     */
    @Test
    void discoveryEntriesAreTheirMatrixCellsAndNeverBelowTheFloor() {
        for (String league : List.of(X26, Z26)) {
            Response r = rivalries.forLeague(league, null).orElseThrow();
            RivalryService.Discovery d = r.discovery();
            assertNotNull(d, league);
            assertEquals(3, d.minMeetings(), league + ": N is the hand-set weights.yml value");
            assertEquals("hand-set", d.minMeetingsLabel());
            Map<String, PairRecord> cells = new HashMap<>();
            for (PairRecord p : r.matrix().pairs()) cells.put(p.aManagerId() + "-" + p.bManagerId(), p);
            int entries = 0;
            for (var named : Map.of("mostPlayed", d.mostPlayed(), "closest", d.closest(),
                    "lopsided", d.lopsided(), "playoff", d.playoff()).entrySet()) {
                for (RivalryService.DiscoveredPair e : named.getValue()) {
                    entries++;
                    String label = league + " " + named.getKey() + " " + e.aManagerId() + "-" + e.bManagerId();
                    PairRecord cell = cells.get(e.aManagerId() + "-" + e.bManagerId());
                    assertNotNull(cell, label + " has a matrix cell");
                    assertEquals(cell.meetings(), e.meetings(), label);
                    assertEquals(cell.aWins(), e.aWins(), label);
                    assertEquals(cell.bWins(), e.bWins(), label);
                    assertEquals(cell.ties(), e.ties(), label);
                    assertEquals(cell.byKind(), e.byKind(), label);
                    assertEquals(cell.status(), e.status(), label);
                    assertEquals(cell.finals(), e.finals(), label);
                    assertEquals(cell.games(), e.games(), label);
                    // the playoff list is a record of fact and has no floor; the three claims do
                    if (!named.getKey().equals("playoff")) assertTrue(e.meetings() >= d.minMeetings(), label);
                }
            }
            assertTrue(entries > 0, league + ": the fixture must produce at least one entry to compare");
        }
    }

    @Test
    void sportsNeverMixAndTheNbaChainStandsAlone() {
        Response nba = rivalries.forLeague(Z26, null).orElseThrow();
        assertEquals("nba", nba.sport());
        assertEquals(6, nba.matrix().pairs().size());
        assertTrue(nba.matrix().pairs().stream().allMatch(p -> p.aManagerId() >= n[1] && p.bManagerId() <= n[4]));
        assertEquals("OK", nba.recommendation().status());
        assertEquals(2, nba.recommendation().pairings().size());
        assertNull(nba.recommendation().unpaired());
        Response nfl = rivalries.forLeague(X26, null).orElseThrow();
        assertTrue(nfl.matrix().rows().stream().noneMatch(row -> row.managerId() >= n[1] && row.managerId() <= n[4]));
        assertEquals(3, nfl.recommendation().pairings().size(), "six managers, three pairs, nobody out");
    }

    @Test
    void theNewestSeasonIsTheUrlsSeasonEvenWhenAnOlderOneHasMoreScores() {
        Response r = rivalries.forLeague(X25, null).orElseThrow();
        assertEquals(2025, r.season());
        assertEquals(List.of(2025), r.seasonsCovered(), "opening 2025 shows history through 2025 only");
        assertEquals("NO_HISTORY", r.recommendation().status());
    }

    // ---------------------------------------------------------------- HTTP surface (T017)

    @Test
    void theEndpointIsScopedLikeEveryLeagueRouteAndHasTheContractShape() throws Exception {
        long x26 = jdbc.queryForObject("select id from league where sleeper_id = ?", Long.class, X26);
        leagueMembers.upsert(x26, m[1], false, "Team One");

        mvc.perform(get("/api/leagues/" + X26 + "/rivalries")).andExpect(status().isNotFound());
        mvc.perform(get("/api/leagues/" + X26 + "/rivalries").header("X-Sleeper-User", "it-riv-stranger"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/leagues/no-such-league/rivalries").header("X-Sleeper-User", "it-riv-m1"))
                .andExpect(status().isNotFound());

        mvc.perform(get("/api/leagues/" + X26 + "/rivalries").header("X-Sleeper-User", "it-riv-m1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sport").value("nfl"))
                .andExpect(jsonPath("$.season").value(2026))
                .andExpect(jsonPath("$.seasonsCovered[0]").value(2026))
                .andExpect(jsonPath("$.matrix.rows.length()").value(6))
                .andExpect(jsonPath("$.matrix.rows[?(@.isMe == true)]", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$.matrix.pairs.length()").value(15))
                .andExpect(jsonPath("$.matrix.pairs[0].byKind.POSTSEASON_UNSPLIT").exists())
                .andExpect(jsonPath("$.seasonGrid.scheduleStatus").value("OK"))
                .andExpect(jsonPath("$.seasonGrid.weeks[0].isPlayoffWeek").value(false))
                .andExpect(jsonPath("$.seasonGrid.cells[0].state").exists())
                .andExpect(jsonPath("$.recommendation.status").value("OK"))
                .andExpect(jsonPath("$.recommendation.minMeetingsLabel").value("hand-set"))
                .andExpect(jsonPath("$.recommendation.pairings[0].reason.code").exists());
    }

    // ---------------------------------------------------------------- fixture helpers

    private long manager(String sleeperUserId) {
        return jdbc.queryForObject("insert into manager (sleeper_user_id, display_name) values (?, ?) returning id",
                Long.class, sleeperUserId, sleeperUserId);
    }

    private long league(String sleeperId, String sport, int season, String previous, String settingsJson) {
        return jdbc.queryForObject("""
                insert into league (sport, season, sleeper_id, previous_league_id, name, total_rosters, status,
                                    roster_positions, settings_json)
                values (?, ?, ?, ?, ?, 6, ?, '{QB}', ?::jsonb) returning id
                """, Long.class, sport, season, sleeperId, previous, "IT " + sleeperId,
                season < 2026 ? "complete" : "in_season", settingsJson);
    }

    private void roster(long leagueId, int rosterId, long managerId) {
        jdbc.update("""
                insert into roster_season (league_id, manager_id, roster_id, wins, losses, points_for)
                values (?, ?, ?, 0, 0, 0)
                """, leagueId, managerId, rosterId);
    }

    /** Weeks 1-6 round robin on 6 rosters, then playoff weeks 7 and 8 shaped like the bracket (+ consolation 5v6). */
    private void sixTeamSeason(long leagueId, int season, int seed) {
        for (int w = 1; w <= 6; w++) for (int[] p : roundRobin(6, w)) score(leagueId, season, w, p, seed);
        for (int[] p : new int[][]{{1, 2}, {3, 4}, {5, 6}}) score(leagueId, season, 7, p, seed);
        for (int[] p : new int[][]{{1, 3}, {2, 4}, {5, 6}}) score(leagueId, season, 8, p, seed);
    }

    /** Circle-method pairings of rosters 1..n for round {@code week} (1-based, wrapping). */
    private static List<int[]> roundRobin(int n, int week) {
        List<Integer> rest = new ArrayList<>();
        for (int i = 2; i <= n; i++) rest.add(i);
        int shift = (week - 1) % (n - 1);
        List<Integer> rot = new ArrayList<>();
        for (int i = 0; i < rest.size(); i++) rot.add(rest.get((i + shift) % rest.size()));
        List<int[]> out = new ArrayList<>();
        out.add(new int[]{1, rot.get(0)});
        for (int i = 1; i < n / 2; i++) out.add(new int[]{rot.get(i), rot.get(rot.size() - i)});
        return out;
    }

    private final Map<String, Integer> matchupIds = new HashMap<>();

    /** One paired, scored game: deterministic points with an occasional exact tie. */
    private void score(long leagueId, int season, int week, int[] pair, int seed) {
        int a = pair[0], b = pair[1];
        double pa = 80 + ((a * 7 + week * 13 + seed) % 37);
        double pb = 80 + ((b * 11 + week * 5 + seed) % 37);
        if ((a + b + week + seed) % 9 == 0) pb = pa;
        int mid = matchupIds.merge(leagueId + ":" + week, 1, Integer::sum);
        for (int[] side : new int[][]{{a, 0}, {b, 1}}) {
            jdbc.update("""
                    insert into roster_week_points (league_id, season, week, roster_id, starters_points, players_points)
                    values (?, ?, ?, ?, ?, '{}'::jsonb)
                    """, leagueId, season, week, side[0], side[1] == 0 ? pa : pb);
            jdbc.update("""
                    insert into league_matchup (league_id, season, week, roster_id, matchup_id)
                    values (?, ?, ?, ?, ?)
                    """, leagueId, season, week, side[0], mid);
        }
    }
}
