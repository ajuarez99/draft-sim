package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.engine.AdvancedStats.WindowKind;
import com.ballknowers.draftsim.engine.DraftGradesService.DraftGrades;
import com.ballknowers.draftsim.engine.PlayerStatsService.LeaderboardRow;
import com.ballknowers.draftsim.engine.PlayerStatsService.PlayerStatsPage;
import com.ballknowers.draftsim.engine.PlayerStatsService.StatLeaderboard;
import com.ballknowers.draftsim.store.DraftRepository;
import com.ballknowers.draftsim.store.LeagueRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 022 T051 (SC-009, I5, I6) over the real 2025 data: the leaderboard's draft column against
 * {@code draft_pick}, its ADP state, its values against the player page's, and the row count against a direct
 * SQL recount. SKIPS (with a message) when the local Postgres is unreachable or the leagues are not in it, so
 * the caller must read the skip count. Prints {@code REPORT} lines for the measured numbers.
 */
@SpringBootTest
class PlayerStatsLeaderboardReadIT {

    private static final String JDBC_URL = "jdbc:postgresql://localhost:5433/draftsim";
    private static final String NBA_2025 = "1229352720222134272";
    private static final String NBA_2026 = "1339351318115946496";
    private static final String MEMBER = "1122386008709910528";

    @BeforeAll
    static void requiresLocalPostgres() {
        boolean reachable;
        try (Connection c = DriverManager.getConnection(JDBC_URL, "draftsim", "draftsim")) {
            reachable = true;
        } catch (SQLException e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable, "no local Postgres reachable at " + JDBC_URL + " -- skipping");
    }

    @Autowired private PlayerStatsService service;
    @Autowired private DraftGradesService gradesService;
    @Autowired private DraftAndAdpJoin join;
    @Autowired private LeagueRepository leagues;
    @Autowired private DraftRepository drafts;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private com.fasterxml.jackson.databind.ObjectMapper mapper;

    private LeagueRepository.LeagueRow league(String sleeperId) {
        Optional<LeagueRepository.LeagueRow> l = leagues.bySleeperId(sleeperId);
        Assumptions.assumeTrue(l.isPresent(), "league " + sleeperId + " is not in the local database");
        return l.get();
    }

    /** Pick number to {round, sleeper id} straight from draft_pick. */
    private Map<Integer, String[]> storedPicks(long draftId) {
        Map<Integer, String[]> out = new HashMap<>();
        jdbc.query("""
                select dp.pick_no, dp.round, p.sleeper_id from draft_pick dp
                join player p on p.id = dp.player_id where dp.draft_id = ?""",
                rs -> {
                    out.put(rs.getInt(1), new String[] {String.valueOf(rs.getInt(2)), rs.getString(3)});
                }, draftId);
        return out;
    }

    @Test
    void all168PicksOfThe2025DraftMatchDraftPickAndDraftValueIsDraftGradesValueOverSlot() {
        var l = league(NBA_2025);
        DraftRepository.DraftRow draft = drafts.forLeague(l.id()).orElseThrow();
        Map<Integer, String[]> stored = storedPicks(draft.id());
        assertEquals(168, stored.size(), "the 2025 NBA draft has 168 stored picks with a player");

        // The join itself, over every pick (a drafted player with no 2025 game has no leaderboard row).
        var token = new com.ballknowers.draftsim.store.PlayerGameRepository.SeasonToken(0, null);
        DraftAndAdpJoin.Joined joined = join.join(l, token);
        assertEquals("COMPLETE", joined.draft().state());
        assertEquals(draft.sleeperDraftId(), joined.draft().draftId());
        assertEquals(168, joined.picks().size());
        for (Map.Entry<Integer, String[]> e : stored.entrySet()) {
            var p = joined.picks().get(e.getValue()[1]);
            assertNotNull(p, "pick " + e.getKey());
            assertEquals(e.getKey(), p.pickNo());
            assertEquals(Integer.parseInt(e.getValue()[0]), p.round(), "round of pick " + e.getKey());
            assertNotNull(p.managerName(), "manager of pick " + e.getKey());
        }

        DraftGrades dg = gradesService.read(draft);
        assertEquals(dg.available(), joined.draftGrades().available());
        assertEquals(dg.reason(), joined.draftGrades().reason());
        assertEquals(dg.gradesEarly(), joined.draftGrades().gradesEarly());
        assertEquals(dg.weeksCounted(), joined.draftGrades().weeksCounted());
        // The manager is Draft Grades' own: slot to team manager.
        Map<Integer, String> teamBySlot = dg.teams().stream().filter(t -> t.manager() != null)
                .collect(Collectors.toMap(DraftGradesService.TeamGrade::slot, DraftGradesService.TeamGrade::manager));
        int checkedValues = 0;
        for (DraftGradesService.PickGrade pg : dg.picks()) {
            var p = joined.picks().get(pg.sleeperPlayerId());
            if (p == null) continue;
            assertEquals(pg.pickNo(), p.pickNo());
            assertEquals(teamBySlot.get(pg.slot()), p.managerName(), "manager name of pick " + pg.pickNo());
            if (pg.valueOverSlot() == null) {
                assertNull(joined.draftValue().get(pg.sleeperPlayerId()));
            } else {
                assertEquals(pg.valueOverSlot(), joined.draftValue().get(pg.sleeperPlayerId()));
                checkedValues++;
            }
        }
        assertTrue(checkedValues > 0);
        System.out.println("REPORT draft picks matched=168, draft values checked=" + checkedValues
                + ", draftGrades=" + joined.draftGrades());
    }

    @Test
    void the2025SeasonBoardIsTheDraftColumnAdpNoAdpStoredAndEqualsThePlayerPage() {
        var l = league(NBA_2025);
        long t0 = System.nanoTime();
        StatLeaderboard cold = service.readLeaderboard(l, WindowKind.SEASON, MEMBER);
        long coldMs = (System.nanoTime() - t0) / 1_000_000;
        long t1 = System.nanoTime();
        StatLeaderboard board = service.readLeaderboard(l, WindowKind.SEASON, MEMBER);
        long warmMs = (System.nanoTime() - t1) / 1_000_000;
        System.out.println("REPORT leaderboard SEASON 2025 cold=" + coldMs + "ms warm=" + warmMs + "ms rows="
                + board.rows().size());
        assertTrue(warmMs < 10_000, "warm leaderboard read took " + warmMs + "ms");
        assertEquals(cold.rows().size(), board.rows().size());

        assertTrue(board.available());
        assertEquals(2025, board.season());
        assertNull(board.requestedSeason());
        assertEquals(WindowKind.SEASON, board.window());
        assertEquals("COMPLETE", board.draft().state());
        assertEquals(2025, board.draft().draftSeason());
        assertEquals("NO_ADP_STORED", board.adp().reason());
        assertNull(board.adp().capturedOn());
        assertEquals("blend", board.adp().source());
        assertTrue(board.rows().stream().allMatch(r -> r.adp() == null));
        assertNotNull(board.ownershipAsOf());
        assertNotNull(board.qualification());

        // I5: no team rows. Row count: a direct recount of players with a counted game.
        assertTrue(board.rows().stream().noneMatch(r -> r.sleeperPlayerId().startsWith("TEAM_")));
        int sqlPlayers = jdbc.queryForObject("""
                with codes as (
                  select substr(sleeper_player_id, 6) c from player_game
                  where sport = 'nba' and season = 2025 and sleeper_player_id like 'TEAM\\_%' and length(sleeper_player_id) > 5
                  group by 1)
                select count(distinct sleeper_player_id) from player_game
                where sport = 'nba' and season = 2025 and sleeper_player_id not like 'TEAM\\_%'
                  and game_id <> '1305814461864501248'
                  and (stats->>'sp')::float > 0 and opponent in (select c from codes)""", Integer.class);
        System.out.println("REPORT SEASON 2025 rows=" + board.rows().size() + " sql recount=" + sqlPlayers);
        assertEquals(sqlPlayers, board.rows().size());
        assertEquals(board.rows().size(), board.rows().stream().map(LeaderboardRow::sleeperPlayerId).distinct().count());

        // SC-009 on the board: every drafted row carries the stored pick.
        DraftRepository.DraftRow draft = drafts.forLeague(l.id()).orElseThrow();
        Map<Integer, String[]> stored = storedPicks(draft.id());
        Map<String, Integer> pickBySleeper = new HashMap<>();
        stored.forEach((pickNo, v) -> pickBySleeper.put(v[1], pickNo));
        int drafted = 0;
        for (LeaderboardRow r : board.rows()) {
            Integer expectedPick = pickBySleeper.get(r.sleeperPlayerId());
            if (expectedPick == null) {
                assertNull(r.draft(), r.sleeperPlayerId() + " is undrafted");
                assertNull(r.draftValue());
            } else {
                assertNotNull(r.draft(), r.sleeperPlayerId());
                assertEquals(expectedPick, r.draft().pickNo());
                drafted++;
            }
        }
        System.out.println("REPORT rows with a draft pick=" + drafted + " of 168 picks");
        assertTrue(drafted > 100);

        // Ranks: qualified only, competition ranks, leader is rank 1; replacement and value over replacement.
        var qualified = board.rows().stream().filter(LeaderboardRow::qualified).toList();
        assertFalse(qualified.isEmpty());
        assertTrue(qualified.stream().allMatch(r -> r.leagueRank() != null && r.pointsRank() != null
                && r.rankMove() == r.pointsRank() - r.leagueRank()));
        assertEquals(1, qualified.stream().mapToInt(LeaderboardRow::leagueRank).min().orElseThrow());
        for (LeaderboardRow r : board.rows()) {
            if (r.qualified()) continue;
            assertNull(r.leagueRank());
            assertNull(r.positionRank());
            assertNull(r.pointsRank());
            assertNull(r.rankMove());
            assertNull(r.valueOverReplacement());
            assertNull(r.vorPosition());
            assertNotNull(r.reason());
            assertNotNull(r.fpPerGame(), "an unqualified player still has fantasy points per game");
        }
        var repl = board.replacement();
        assertEquals("GREEDY_SLOT_FILL", repl.rule());
        assertEquals(l.totalRosters(), repl.teams());
        assertEquals(List.of("PG", "SG", "SF", "PF", "C"), List.copyOf(repl.byPosition().keySet()));
        System.out.println("REPORT replacement 2025 SEASON " + repl.byPosition() + " slots=" + repl.slots()
                + " teams=" + repl.teams());
        for (LeaderboardRow r : qualified) {
            if (r.valueOverReplacement() == null) continue;
            double level = repl.byPosition().get(r.vorPosition());
            assertEquals(Math.round((r.fpPerGame() - level) * 100.0) / 100.0, r.valueOverReplacement(), 1e-9);
            assertTrue(r.positions().contains(r.vorPosition()));
        }
        assertTrue(qualified.stream().anyMatch(r -> r.valueOverReplacement() != null && r.valueOverReplacement() > 0));

        // I6: the board's values are the player page's for the same player and window.
        List<LeaderboardRow> sample = new java.util.ArrayList<>(qualified.stream()
                .sorted(java.util.Comparator.comparingInt(LeaderboardRow::leagueRank)).limit(4).toList());
        board.rows().stream().filter(r -> !r.qualified()).findFirst().ifPresent(sample::add);
        assertEquals(5, sample.size());
        for (LeaderboardRow r : sample) {
            PlayerStatsPage page = service.read(l, r.sleeperPlayerId(), MEMBER).orElseThrow();
            var pw = page.windows().get(WindowKind.SEASON);
            assertEquals(pw, r.stats(), r.sleeperPlayerId() + " window");
            assertEquals(pw.advanced().ts().value(), r.stats().advanced().ts().value());
            assertEquals(page.fantasy().fpPerGame().get(WindowKind.SEASON), r.fpPerGame(), r.sleeperPlayerId());
            var pr = page.fantasy().ranks();
            assertEquals(pr.leagueRank(), r.leagueRank());
            assertEquals(pr.positionRank(), r.positionRank());
            assertEquals(pr.pointsRank(), r.pointsRank());
            assertEquals(pr.rankMove(), r.rankMove());
            assertEquals(page.ownership(), r.ownership());
            assertEquals(page.qualification(), board.qualification());
        }
    }

    @Test
    void lastTenAndLastFiveAreSeparateRuleSetsOverTheSameRows() {
        var l = league(NBA_2025);
        StatLeaderboard season = service.readLeaderboard(l, WindowKind.SEASON, MEMBER);
        for (WindowKind k : List.of(WindowKind.LAST_10, WindowKind.LAST_5)) {
            StatLeaderboard b = service.readLeaderboard(l, k, MEMBER);
            assertEquals(k, b.window());
            assertEquals(season.rows().size(), b.rows().size());
            var q = b.rows().stream().filter(LeaderboardRow::qualified).toList();
            System.out.println("REPORT " + k + " rows=" + b.rows().size() + " qualified=" + q.size()
                    + " replacement=" + b.replacement().byPosition());
            for (LeaderboardRow r : b.rows()) {
                if (!r.qualified()) assertNull(r.leagueRank());
                assertTrue(r.stats().games() <= k.lastN());
            }
            // I6 for a last-N window: the page's windows map has the same figures.
            LeaderboardRow top = q.stream().min(java.util.Comparator.comparingInt(LeaderboardRow::leagueRank)).orElseThrow();
            PlayerStatsPage page = service.read(l, top.sleeperPlayerId(), MEMBER).orElseThrow();
            assertEquals(page.windows().get(k), top.stats());
            assertEquals(page.fantasy().fpPerGame().get(k), top.fpPerGame());
        }
    }

    @Test
    void aSeasonThatFellBackKeepsTheRequestedSeasonAndCarriesCurrentOwnershipPerRow() {
        var l = league(NBA_2026);
        StatLeaderboard b = service.readLeaderboard(l, WindowKind.SEASON, MEMBER);
        Assumptions.assumeTrue(b.requestedSeason() != null, "2026 has stored games now, so it did not fall back");
        assertEquals(2026, b.requestedSeason());
        assertEquals(2025, b.season());
        assertTrue(b.available());
        assertTrue(b.rows().stream().allMatch(r -> r.currentOwnership() != null));
        // 2025 answered: its ownership is the 2025 end-of-season week, never the 2026 league's.
        assertTrue(b.rows().stream().allMatch(r -> r.ownership().asOf() == null
                || "WEEK".equals(r.ownership().asOf().kind())));
        StatLeaderboard own = service.readLeaderboard(league(NBA_2025), WindowKind.SEASON, MEMBER);
        assertNull(own.rows().getFirst().currentOwnership());
    }

    /** V2 (amended 2026-10-08): the draft columns and ADP follow the REQUESTED season, not the answered one. */
    @Test
    void aSeasonThatFellBackReadsTheRequestedSeasonsDraftAndAdp() {
        var l = league(NBA_2026);
        StatLeaderboard b = service.readLeaderboard(l, WindowKind.SEASON, MEMBER);
        Assumptions.assumeTrue(b.requestedSeason() != null, "2026 has stored games now, so it did not fall back");
        Assumptions.assumeTrue("pre_draft".equals(drafts.forLeague(l.id()).orElseThrow().status()),
                "the 2026 draft has been held since this was written");
        assertEquals("NOT_HAPPENED", b.draft().state());
        assertEquals(2026, b.draft().draftSeason());
        assertNull(b.adp().reason());
        assertEquals(java.time.LocalDate.of(2026, 9, 28), b.adp().capturedOn());
        assertFalse(b.draftGrades().available());
        assertTrue(b.rows().stream().allMatch(r -> r.draft() == null && r.draftValue() == null));
        assertTrue(b.rows().stream().anyMatch(r -> r.adp() != null));
        System.out.println("REPORT 2026 fallback draft=" + b.draft() + " adp=" + b.adp() + " grades=" + b.draftGrades());
    }

    /** The wire shape: serialises with the app mapper (null levels and all) and carries the contract keys. */
    @Test
    void theBoardSerialisesToTheContractShape() throws Exception {
        var l = league(NBA_2025);
        StatLeaderboard b = service.readLeaderboard(l, WindowKind.SEASON, MEMBER);
        var json = mapper.readTree(mapper.writeValueAsString(b));
        for (String k : List.of("sport", "season", "requestedSeason", "available", "reason", "dataAsOf", "seasons", "window",
                "qualification", "ownershipAsOf", "draft", "adp", "draftGrades", "replacement", "rows")) {
            assertTrue(json.has(k), k);
        }
        assertEquals("SEASON", json.get("window").asText());
        assertEquals("COMPLETE", json.get("draft").get("state").asText());
        assertEquals("NO_ADP_STORED", json.get("adp").get("reason").asText());
        assertEquals("blend", json.get("adp").get("source").asText());
        assertTrue(json.get("replacement").get("byPosition").has("C"));
        var row = json.get("rows").get(0);
        for (String k : List.of("sleeperPlayerId", "name", "positions", "team", "ownership", "currentOwnership", "qualified",
                "reason", "stats", "fpPerGame", "leagueRank", "positionRank", "pointsRank", "rankMove",
                "valueOverReplacement", "vorPosition", "draft", "draftValue", "adp")) {
            assertTrue(row.has(k), k);
        }
        assertTrue(row.get("stats").has("advanced"));
    }
}
