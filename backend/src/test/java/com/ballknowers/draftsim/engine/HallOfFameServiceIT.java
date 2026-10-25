package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.TestAdmin;
import com.ballknowers.draftsim.engine.HallOfFameService.ManagerRef;
import com.ballknowers.draftsim.engine.HallOfFameService.TrophyCase;
import com.ballknowers.draftsim.engine.HallOfFameService.TrophySeason;
import com.ballknowers.draftsim.store.LeagueBracketRepository;
import com.ballknowers.draftsim.store.LeagueRepository;
import com.ballknowers.draftsim.store.LeagueRepository.LeagueRow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * specs/047 T012 / T026: the trophy case and the rivals against local Postgres. Two halves:
 *
 * <ul>
 *   <li>the REAL leagues in this database (skipped, with an Assumption, when they aren't there): SC-001 (the
 *       trophy champion is the trophy row of {@code /history}), the runner-ups research R1 measured, the points
 *       leaders, and plan A10 (every manager's career {@code titles} equals the trophy-case champion rows over
 *       counted seasons);
 *   <li>a synthetic chain for the cases no real league has: the bracket and the season result naming different
 *       champions, an in-progress season carrying a stale placement 1, and an orphan roster as points leader.
 * </ul>
 *
 * SKIPS when Postgres isn't reachable at the URL the app itself would use ({@code DB_URL}), so read the skip
 * count. Assertions about brackets are written so they hold both before and after the bracket backfill ran.
 */
@SpringBootTest
@AutoConfigureMockMvc
class HallOfFameServiceIT {

    // Real Sleeper league ids in the developer database (research R1/R2).
    static final String FBK_2026 = "1346366555759341568", FBK_2025 = "1254190892974084096";
    static final String NBA_2026 = "1339351318115946496", NBA_2025 = "1229352720222134272", NBA_2024 = "1141438340626231296";
    static final String WC_2026 = "1389361939561332736", WC_2025 = "1262506916429430784";

    static final String S25 = "it-hof-s25", S26 = "it-hof-s26", P26 = "it-hof-p26";
    private static final List<String> SYNTHETIC = List.of(S25, S26, P26);

    @BeforeAll
    static void requiresPostgres() {
        String url = System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5433/draftsim");
        boolean reachable;
        try (Connection c = DriverManager.getConnection(url, "draftsim", "draftsim")) {
            reachable = true;
        } catch (SQLException e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable, "no Postgres reachable at " + url + " -- skipping hall of fame IT");
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private HallOfFameService hallOfFame;
    @Autowired private LeagueRepository leagues;
    @Autowired private ManagerCareerService careers;
    @Autowired private LeagueBracketRepository brackets;
    @Autowired private RivalryService rivalries;
    @Autowired private com.ballknowers.draftsim.store.LeagueMembership membership;
    @Autowired private MockMvc mvc;

    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    @AfterEach
    void cleanup() {
        for (String s : SYNTHETIC) jdbc.update("delete from league where sleeper_id = ?", s);
        jdbc.update("delete from manager where sleeper_user_id like 'it-hof-%'");
    }

    // ---------------------------------------------------------------- the real leagues

    private void requireReal(String... sleeperIds) {
        for (String id : sleeperIds) {
            Assumptions.assumeTrue(leagues.bySleeperId(id).isPresent(), "real league " + id + " is not in this database");
        }
    }

    @Test
    void sc001_theTrophyChampionIsTheTrophyRowOfHistoryForEveryCompleteSeason() throws Exception {
        requireReal(FBK_2026, NBA_2026, WC_2026);
        int seasonsChecked = 0;
        for (String head : List.of(FBK_2026, NBA_2026, WC_2026)) {
            TrophyCase tc = hallOfFame.trophyCase(head).orElseThrow();
            JsonNode history = json.readTree(mvc.perform(get("/api/leagues/" + head + "/history")
                            .header("X-Admin-Token", TestAdmin.TOKEN))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            Map<String, Integer> championByLeague = new HashMap<>();
            for (JsonNode season : history.get("seasons")) {
                for (JsonNode row : season.get("standings")) {
                    if (row.get("champion").asBoolean()) {
                        assertNull(championByLeague.put(season.get("sleeperLeagueId").asText(), row.get("rosterId").asInt()),
                                "History marks exactly one champion per season");
                    }
                }
            }
            for (TrophySeason ts : tc.seasons()) {
                seasonsChecked++;
                assertNotNull(ts.champion(), head + " " + ts.season());
                assertEquals(championByLeague.get(ts.sleeperLeagueId()), ts.champion().rosterId(),
                        "SC-001 " + head + " " + ts.season());
            }
            // And the other way round: History's champions are exactly the seasons the trophy case lists.
            assertEquals(championByLeague.keySet(),
                    tc.seasons().stream().map(TrophySeason::sleeperLeagueId).collect(java.util.stream.Collectors.toSet()),
                    head);
        }
        assertTrue(seasonsChecked >= 1, "at least one complete real season was compared");
    }

    @Test
    void theRunnerUpsAreTheOnesTheBracketsStoreAndNeverSomeoneElse() {
        requireReal(NBA_2026, WC_2026, FBK_2026);
        assertEquals(6, runnerUp(NBA_2026, 2024));
        assertEquals(7, runnerUp(NBA_2026, 2025));
        assertEquals(3, runnerUp(WC_2026, 2025));
        // FBK 2025 has no stored bracket until the backfill runs; after it, the runner-up is a real roster
        // (not the champion, roster 1). Either way it is never a guess.
        TrophySeason fbk = season(FBK_2026, 2025);
        if (fbk.runnerUp() == null) {
            assertEquals(HallOfFameService.NO_BRACKET, fbk.runnerUpReason());
        } else {
            assertNull(fbk.runnerUpReason());
            assertNotEquals(fbk.champion().rosterId(), fbk.runnerUp().rosterId());
        }
    }

    @Test
    void theRunnerUpIsNeverTheChampionAndTheReasonMatchesTheCell() {
        requireReal(FBK_2026, NBA_2026, WC_2026);
        for (String head : List.of(FBK_2026, NBA_2026, WC_2026)) {
            for (TrophySeason ts : hallOfFame.trophyCase(head).orElseThrow().seasons()) {
                assertEquals(ts.runnerUp() == null, ts.runnerUpReason() != null, head + " " + ts.season());
                if (ts.runnerUp() != null) assertNotEquals(ts.champion().rosterId(), ts.runnerUp().rosterId());
                assertEquals(ts.lastPlace() == null, ts.lastPlaceReason() != null, head + " " + ts.season());
            }
        }
    }

    @Test
    void thePointsLeaderIsTheMaxPointsForIncludingAFormerManager() {
        requireReal(FBK_2026, NBA_2026, WC_2026);
        for (String head : List.of(FBK_2026, NBA_2026, WC_2026)) {
            for (TrophySeason ts : hallOfFame.trophyCase(head).orElseThrow().seasons()) {
                Double max = jdbc.queryForObject(
                        "select max(points_for) from roster_season rs join league l on l.id = rs.league_id where l.sleeper_id = ?",
                        Double.class, ts.sleeperLeagueId());
                assertFalse(ts.pointsLeaders().isEmpty());
                for (var p : ts.pointsLeaders()) assertEquals(max, p.pointsFor(), 1e-9, head + " " + ts.season());
                int atMax = jdbc.queryForObject("""
                        select count(*) from roster_season rs join league l on l.id = rs.league_id
                         where l.sleeper_id = ? and rs.points_for = ?
                        """, Integer.class, ts.sleeperLeagueId(), max);
                assertEquals(atMax, ts.pointsLeaders().size(), "every tied leader is listed");
            }
        }
        // West Coast 2025's leader is manager 23, who is no longer in the league.
        Optional<Long> m23 = jdbc.queryForList("select id from manager where id = 23", Long.class).stream().findFirst();
        if (m23.isPresent()) {
            TrophySeason wc = season(WC_2026, 2025);
            assertEquals(1, wc.pointsLeaders().size());
            assertEquals(23L, wc.pointsLeaders().get(0).managerId());
        }
    }

    @Test
    void anInProgressSeasonNeverGetsARow() {
        requireReal(FBK_2026, NBA_2026, WC_2026);
        for (String head : List.of(FBK_2026, NBA_2026, WC_2026)) {
            String headStatus = leagues.bySleeperId(head).orElseThrow().status();
            if ("complete".equals(headStatus)) continue;
            TrophyCase tc = hallOfFame.trophyCase(head).orElseThrow();
            assertTrue(tc.seasons().stream().noneMatch(s -> s.sleeperLeagueId().equals(head)), head);
        }
    }

    private int storedRows(String table, String sleeperId) {
        return jdbc.queryForObject("select count(*) from " + table + " t join league l on l.id = t.league_id where l.sleeper_id = ?",
                Integer.class, sleeperId);
    }

    /**
     * N5 (code review): once the winners bracket was ingested, the losers bracket must have been too -- the two
     * halves are written together -- so a regression where the ingest stops writing losers rows fails here rather
     * than hiding behind a NO_LOSERS_BRACKET refusal. Where the scratch DB genuinely lacks a season's brackets
     * the test stays tolerant for that season only. The expected outcomes are the four measured seasons
     * (research R2): FBK 2025 toilet bowl -> roster 2, West Coast 2025 consolation -> roster 10, NBA 2024/2025
     * refused. Pairing (S3) comes from the stored league_matchup rows, not from a stub.
     */
    @Test
    void nameLastPlaceOnlyWhenTheRuleCouldReadIt() {
        requireReal(FBK_2026, NBA_2026, WC_2026);
        for (String id : List.of(FBK_2025, WC_2025, NBA_2025, NBA_2024)) {
            if (leagues.bySleeperId(id).isEmpty()) continue;
            if (storedRows("league_bracket_match", id) > 0) {
                assertTrue(storedRows("league_losers_bracket_match", id) > 0,
                        "winners bracket stored for " + id + " but no losers bracket: the losers ingest stopped writing");
            }
        }
        for (TrophySeason ts : hallOfFame.trophyCase(NBA_2026).orElseThrow().seasons()) {
            assertNull(ts.lastPlace(), "NBA " + ts.season());
            if (storedRows("league_losers_bracket_match", ts.sleeperLeagueId()) > 0) {
                assertEquals(LastPlaceRule.MIXED_DIRECTION, ts.lastPlaceReason(), "NBA " + ts.season());
            } else {
                assertEquals(LastPlaceRule.NO_LOSERS_BRACKET, ts.lastPlaceReason(), "NBA " + ts.season());
            }
        }
        TrophySeason fbk = season(FBK_2026, 2025);
        if (storedRows("league_losers_bracket_match", FBK_2025) > 0) {
            assertNotNull(fbk.lastPlace(), "FBK 2025 reason: " + fbk.lastPlaceReason());
            assertEquals(2, fbk.lastPlace().rosterId());
            assertEquals(LastPlaceRule.TOILET_BOWL, fbk.lastPlaceBracket());
        } else {
            assertEquals(LastPlaceRule.NO_LOSERS_BRACKET, fbk.lastPlaceReason());
        }
        TrophySeason wc = season(WC_2026, 2025);
        if (storedRows("league_losers_bracket_match", WC_2025) > 0) {
            assertNotNull(wc.lastPlace(), "West Coast 2025 reason: " + wc.lastPlaceReason());
            assertEquals(10, wc.lastPlace().rosterId());
            assertEquals(LastPlaceRule.CONSOLATION, wc.lastPlaceBracket());
        } else {
            assertEquals(LastPlaceRule.NO_LOSERS_BRACKET, wc.lastPlaceReason());
        }
    }

    /** Plan A10: the career gate is a counting rule, not a second champion rule -- titles must still equal the trophy rows. */
    @Test
    void a10_everyManagersCareerTitlesEqualTheTrophyChampionRowsOverCountedSeasons() {
        requireReal(FBK_2026, NBA_2026, WC_2026);
        Map<String, Integer> championRosterBySeason = new HashMap<>();
        for (LeagueRow l : leagues.all()) {
            hallOfFame.trophyCase(l.sleeperId()).ifPresent(tc -> {
                for (TrophySeason ts : tc.seasons()) {
                    if (ts.champion() != null) championRosterBySeason.put(ts.sleeperLeagueId(), ts.champion().rosterId());
                }
            });
        }
        List<Long> managerIds = jdbc.queryForList("select distinct manager_id from roster_season where manager_id is not null", Long.class);
        int withTitles = 0;
        for (long managerId : managerIds) {
            for (var career : careers.forManager(managerId)) {
                int fromTrophy = 0;
                for (var entry : career.seasons()) {
                    var s = entry.row();
                    Integer champ = championRosterBySeason.get(s.sleeperLeagueId());
                    if (entry.counted() && champ != null && champ == s.rosterId()) fromTrophy++;
                }
                assertEquals(fromTrophy, career.titles(), "manager " + managerId + " " + career.sport());
                withTitles += career.titles();
            }
        }
        assertTrue(withTitles > 0, "at least one title exists in this database");
    }

    // ---------------------------------------------------------------- the synthetic chain

    @Test
    void whenTheBracketAndTheSeasonResultNameDifferentChampionsTheStoredChampionStaysAndTheRunnerUpIsNull() {
        long[] m = new long[7];
        for (int i = 1; i <= 6; i++) m[i] = manager("it-hof-m" + i);
        long l25 = league(S25, 2025, null, "complete");
        for (int r = 1; r <= 6; r++) roster(l25, r, m[r], r == 1 ? 1 : null, 100 + r);
        // The bracket says roster 3 won the final over roster 1; the season result says roster 1 is champion.
        brackets.replaceForLeague(l25, List.of(new LeagueBracketRepository.Match(l25, 1, 1, 1, 3, 3, 1, 1)));

        TrophyCase tc = hallOfFame.trophyCase(S25).orElseThrow();
        assertEquals("OK", tc.status());
        TrophySeason ts = tc.seasons().get(0);
        assertEquals(1, ts.champion().rosterId(), "the stored season result stays authoritative");
        assertNull(ts.runnerUp(), "never the bracket's winner promoted, never a guess");
        assertEquals(HallOfFameService.SOURCES_DISAGREE, ts.runnerUpReason());
        assertFalse(ts.finalDisputed());
    }

    @Test
    void anOrphanRosterCanLeadInPointsWithANullManagerIdAndTiesAreAllListed() {
        long a = manager("it-hof-a"), b = manager("it-hof-b");
        long l25 = league(S25, 2025, null, "complete");
        roster(l25, 1, a, 1, 500);
        roster(l25, 2, b, null, 900);
        roster(l25, 3, null, null, 900);   // an orphan roster tied for the lead

        TrophySeason ts = hallOfFame.trophyCase(S25).orElseThrow().seasons().get(0);
        assertEquals(2, ts.pointsLeaders().size());
        assertTrue(ts.pointsLeaders().stream().anyMatch(p -> p.managerId() == null && p.rosterId() == 3));
        assertTrue(ts.pointsLeaders().stream().anyMatch(p -> p.managerId() != null && p.managerId() == b));
        assertEquals(HallOfFameService.NO_BRACKET, ts.runnerUpReason());
    }

    @Test
    void aStalePlacementOneOnAnInProgressSeasonNeverBecomesARowAndAChainWithNoFinishedSeasonSaysSo() {
        long a = manager("it-hof-a"), b = manager("it-hof-b");
        long l26 = league(S26, 2026, null, "in_season");
        roster(l26, 1, a, 1, 500);   // a pre-V21 stale placement
        roster(l26, 2, b, null, 400);

        TrophyCase tc = hallOfFame.trophyCase(S26).orElseThrow();
        assertEquals("NO_COMPLETED_SEASON", tc.status());
        assertTrue(tc.seasons().isEmpty());
    }

    @Test
    void theChainRunsBackwardOnlyAndAnUnknownLeagueIsEmpty() {
        long a = manager("it-hof-a");
        long l25 = league(S25, 2025, null, "complete");
        roster(l25, 1, a, 1, 500);
        long l26 = league(S26, 2026, S25, "complete");
        roster(l26, 1, a, 1, 600);
        assertEquals(List.of(2026, 2025), hallOfFame.trophyCase(S26).orElseThrow().seasons().stream().map(TrophySeason::season).toList());
        assertEquals(List.of(2025), hallOfFame.trophyCase(S25).orElseThrow().seasons().stream().map(TrophySeason::season).toList(),
                "opening the older season never reaches forward (plan A5)");
        assertTrue(hallOfFame.trophyCase("it-hof-nope").isEmpty());
    }

    // ---------------------------------------------------------------- rivals (T026)

    private HallOfFameService.ChainRivals chainOf(HallOfFameService.ManagerRivals r, String headSleeperId) {
        return r.chains().stream().filter(c -> c.headSleeperLeagueId().equals(headSleeperId)).findFirst().orElseThrow();
    }

    private static double share(HallOfFameService.RivalEntry e) {
        return (e.wins() + e.ties() / 2.0) / e.meetings();
    }

    /** Every non-former rival record IS that pair's matrix cell (oriented to the manager), and the extremes are extremes. */
    @Test
    void everyRivalRecordEqualsThePairsMatrixCellAndNobodyQualifyingIsWorse() {
        requireReal(FBK_2026, NBA_2026, WC_2026);
        int compared = 0;
        for (String head : List.of(FBK_2026, NBA_2026, WC_2026)) {
            RivalryService.Response matrix = rivalries.forLeague(head, null).orElseThrow();
            int n = matrix.discovery().minMeetings();
            Map<String, RivalryService.PairRecord> cells = new HashMap<>();
            for (var p : matrix.matrix().pairs()) cells.put(p.aManagerId() + "-" + p.bManagerId(), p);
            for (var row : matrix.matrix().rows()) {
                var chains = hallOfFame.rivals(row.managerId(), null).orElseThrow();
                var chain = chains.chains().stream().filter(c -> c.headSleeperLeagueId().equals(head)).findFirst();
                if (chain.isEmpty()) continue;   // the manager's newest visible season in this chain is a different one
                var c = chain.get();
                for (var e : java.util.stream.Stream.concat(c.nemesis().stream(), c.victim().stream()).toList()) {
                    assertTrue(e.meetings() >= n);
                    long opp = e.opponent().managerId();
                    var cell = cells.get(Math.min(row.managerId(), opp) + "-" + Math.max(row.managerId(), opp));
                    if (e.former()) {
                        assertNull(cell, "a former opponent has no matrix cell");
                        continue;
                    }
                    assertNotNull(cell);
                    boolean mineIsA = row.managerId() < opp;
                    assertEquals(mineIsA ? cell.aWins() : cell.bWins(), e.wins(), row.managerId() + " v " + opp);
                    assertEquals(mineIsA ? cell.bWins() : cell.aWins(), e.losses(), row.managerId() + " v " + opp);
                    assertEquals(cell.ties(), e.ties());
                    assertEquals(cell.meetings(), e.meetings());
                    compared++;
                }
                // no current opponent with N+ meetings has a worse (nemesis) or better (victim) share than the one named
                if (!c.nemesis().isEmpty()) {
                    double worst = share(c.nemesis().get(0));
                    for (var p : matrix.matrix().pairs()) {
                        if (p.aManagerId() != row.managerId() && p.bManagerId() != row.managerId()) continue;
                        if (p.meetings() < n) continue;
                        boolean mineIsA = p.aManagerId() == row.managerId();
                        double w = mineIsA ? p.aWins() : p.bWins(), l = mineIsA ? p.bWins() : p.aWins();
                        if (l > w) assertTrue((w + p.ties() / 2.0) / p.meetings() >= worst - 1e-12, "a worse record than the nemesis");
                    }
                }
            }
        }
        assertTrue(compared > 0, "at least one rival record was compared with a matrix cell");
    }

    @Test
    void aFormerOpponentIsNamedAndTaggedFormer() {
        long m1 = manager("it-hof-m1"), m2 = manager("it-hof-m2"), gone = manager("it-hof-gone");
        long l25 = league(S25, 2025, null, "complete");
        roster(l25, 1, m1, 1, 500);
        roster(l25, 2, m2, null, 400);
        roster(l25, 3, gone, null, 300);
        long l26 = league(S26, 2026, S25, "in_season");
        roster(l26, 1, m1, null, 0);
        roster(l26, 2, m2, null, 0);   // `gone` has no roster in 2026
        // m1 loses to `gone` in weeks 1-3 (0-3), and beats m2 in the same weeks (3-0)
        for (int w = 1; w <= 3; w++) {
            game(l25, 2025, w, 1, 3, 50, 100);
            game(l25, 2025, w + 3, 1, 2, 100, 50);
        }

        var chain = chainOf(hallOfFame.rivals(m1, null).orElseThrow(), S26);
        assertEquals("OK", chain.status());
        assertEquals(1, chain.nemesis().size());
        var nemesis = chain.nemesis().get(0);
        assertEquals(gone, nemesis.opponent().managerId());
        assertTrue(nemesis.former(), "no roster in the head season");
        assertEquals(0, nemesis.wins());
        assertEquals(3, nemesis.losses());
        var victim = chain.victim().get(0);
        assertEquals(m2, victim.opponent().managerId());
        assertFalse(victim.former());
        assertEquals(3, victim.wins());
    }

    /** Plan A4: a caller sharing one league with a manager sees that one chain, never the manager's others. */
    @Test
    void aCallerWhoSharesOneChainSeesOnlyThatChain() throws Exception {
        long m = manager("it-hof-m"), other = manager("it-hof-other"), caller = manager("it-hof-caller");
        long a25 = league(S25, 2025, null, "complete");
        long a26 = league(S26, 2026, S25, "in_season");
        long p26 = league(P26, 2026, null, "in_season");
        for (long l : new long[]{a25, a26, p26}) roster(l, 1, m, null, 100);
        for (long l : new long[]{a25, a26, p26}) roster(l, 2, other, null, 90);
        roster(a26, 3, caller, null, 80);   // the caller only plays in chain S25 -> S26
        for (int w = 1; w <= 3; w++) {
            game(a25, 2025, w, 1, 2, 50, 100);
            game(p26, 2026, w, 1, 2, 50, 100);
        }

        var seen = hallOfFame.rivals(m, java.util.Set.copyOf(membership.leagueIdsFor("it-hof-caller"))).orElseThrow();
        assertEquals(1, seen.chains().size(), "only the chain the caller shares");
        assertEquals(S26, seen.chains().get(0).headSleeperLeagueId());
        assertEquals(2, hallOfFame.rivals(m, null).orElseThrow().chains().size(), "an operator sees both chains");

        // Over HTTP: the caller gets that chain only; a stranger and a missing identity get 404; an operator sees all.
        JsonNode body = json.readTree(mvc.perform(get("/api/managers/" + m + "/rivals").header("X-Sleeper-User", "it-hof-caller"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(1, body.get("chains").size());
        assertEquals(S26, body.get("chains").get(0).get("headSleeperLeagueId").asText());
        mvc.perform(get("/api/managers/" + m + "/rivals").header("X-Sleeper-User", "it-hof-stranger"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/managers/" + m + "/rivals")).andExpect(status().isNotFound());
        JsonNode admin = json.readTree(mvc.perform(get("/api/managers/" + m + "/rivals").header("X-Admin-Token", TestAdmin.TOKEN))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(2, admin.get("chains").size());
        mvc.perform(get("/api/managers/999999999/rivals").header("X-Admin-Token", TestAdmin.TOKEN))
                .andExpect(status().isNotFound());
    }

    /** The plan's real-data form of the same rule: someone who shares only "(Foot) Ball Knowers" with manager 7. */
    @Test
    void aRealCallerSharingOnlyFootBallKnowersWithManager7SeesExactlyOneChain() {
        requireReal(FBK_2026);
        Long m7 = jdbc.queryForList("select id from manager where id = 7", Long.class).stream().findFirst().orElse(null);
        Assumptions.assumeTrue(m7 != null, "manager 7 is not in this database");
        Set<Long> sevens = membership.leagueIdsForManager(7);
        Set<Long> fbk = Set.of(leagues.bySleeperId(FBK_2026).orElseThrow().id(), leagues.bySleeperId(FBK_2025).orElseThrow().id());
        String caller = null;
        for (String user : jdbc.queryForList("""
                select distinct m.sleeper_user_id from roster_season rs join manager m on m.id = rs.manager_id
                 where rs.league_id in (select id from league where sleeper_id in (?, ?)) and m.id <> 7
                """, String.class, FBK_2026, FBK_2025)) {
            Set<Long> shared = new java.util.HashSet<>(membership.leagueIdsFor(user));
            shared.retainAll(sevens);
            if (!shared.isEmpty() && fbk.containsAll(shared)) {
                caller = user;
                break;
            }
        }
        Assumptions.assumeTrue(caller != null, "no real manager shares only FBK with manager 7 in this database");
        var seen = hallOfFame.rivals(7, membership.leagueIdsFor(caller)).orElseThrow();
        assertEquals(1, seen.chains().size(), "FBK only: never West Coast or fantasy");
        assertEquals(FBK_2026, seen.chains().get(0).headSleeperLeagueId());
        assertTrue(hallOfFame.rivals(7, null).orElseThrow().chains().size() > 1, "while the operator sees every chain");
    }

    // ---------------------------------------------------------------- helpers

    private int matchupSeq = 0;

    /** One scored game between two rosters of a league (home = roster a). */
    private void game(long leagueId, int season, int week, int a, int b, double aPts, double bPts) {
        int mid = ++matchupSeq;
        for (Object[] side : new Object[][]{{a, aPts}, {b, bPts}}) {
            jdbc.update("""
                    insert into roster_week_points (league_id, season, week, roster_id, starters_points, players_points)
                    values (?, ?, ?, ?, ?, '{}'::jsonb)
                    """, leagueId, season, week, side[0], side[1]);
            jdbc.update("""
                    insert into league_matchup (league_id, season, week, roster_id, matchup_id)
                    values (?, ?, ?, ?, ?)
                    """, leagueId, season, week, side[0], mid);
        }
    }

    private TrophySeason season(String head, int season) {
        return hallOfFame.trophyCase(head).orElseThrow().seasons().stream()
                .filter(s -> s.season() == season).findFirst().orElseThrow();
    }

    private int runnerUp(String head, int season) {
        ManagerRef r = season(head, season).runnerUp();
        assertNotNull(r, head + " " + season + " runner-up (a stored bracket is expected locally)");
        return r.rosterId();
    }

    private long manager(String sleeperUserId) {
        return jdbc.queryForObject("insert into manager (sleeper_user_id, display_name) values (?, ?) returning id",
                Long.class, sleeperUserId, sleeperUserId);
    }

    private long league(String sleeperId, int season, String previous, String status) {
        return jdbc.queryForObject("""
                insert into league (sport, season, sleeper_id, previous_league_id, name, total_rosters, status,
                                    roster_positions, settings_json)
                values ('nfl', ?, ?, ?, ?, 6, ?, '{QB}', '{"playoff_week_start":7,"playoff_teams":4,"playoff_round_type":0}'::jsonb)
                returning id
                """, Long.class, season, sleeperId, previous, "IT " + sleeperId, status);
    }

    private void roster(long leagueId, int rosterId, Long managerId, Integer placement, double pointsFor) {
        jdbc.update("""
                insert into roster_season (league_id, manager_id, roster_id, wins, losses, points_for, final_placement)
                values (?, ?, ?, 0, 0, ?, ?)
                """, leagueId, managerId, rosterId, pointsFor, placement);
    }

}
