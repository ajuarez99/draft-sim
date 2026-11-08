package com.ballknowers.draftsim.historical;

import com.ballknowers.draftsim.historical.HistoricalBoardBuilder.RookiePick;
import com.ballknowers.draftsim.historical.HistoricalDataRepository.SeasonRow;
import com.ballknowers.draftsim.historical.NflHistoricalRepository.NflSeasonLine;
import com.ballknowers.draftsim.historical.NflHistoricalRepository.NflWeekLine;
import com.ballknowers.draftsim.historical.NflverseSource.DraftPickRow;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 056 T016 against Postgres: the real SQL and the real JDBC binds (AGENTS.md bug classes 2 and 3) of the football
 * tables -- {@code text[]} positions, {@code numeric} stats, batch inserts, the season row's new columns and the
 * sport-scoped offered query -- over {@link NflSyntheticSeason}.
 *
 * <p>SKIPS when Postgres is not reachable at {@code DB_URL}, so read the skip count.
 */
@SpringBootTest
class NflHistoricalRepositoryIT {

    @BeforeAll
    static void requiresPostgres() {
        String url = System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5433/draftsim");
        boolean reachable;
        try (Connection c = DriverManager.getConnection(url, "draftsim", "draftsim")) {
            reachable = true;
        } catch (SQLException e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable, "no Postgres reachable at " + url + " -- skipping NFL repository IT");
    }

    @Autowired private NflHistoricalRepository nfl;
    @Autowired private HistoricalDataRepository data;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate tx;

    @BeforeEach
    void seed() {
        NflSyntheticSeason.clean(jdbc);
        NflSyntheticSeason.seed(nfl, jdbc);
    }

    @AfterAll
    static void removeTheMadeUpSeason() {
        String url = System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5433/draftsim");
        try {
            NflSyntheticSeason.clean(new JdbcTemplate(
                    new org.springframework.jdbc.datasource.DriverManagerDataSource(url, "draftsim", "draftsim")));
        } catch (RuntimeException e) {
            // Postgres was unreachable, so nothing was seeded either.
        }
    }

    @Test
    void aSeasonRoundTripsThroughTheTextArrayAndNumericBinds() {
        int[] counts = nfl.rowCounts(NflSyntheticSeason.S);
        // 250 players x 4 weeks, minus one bye each: 250 * 3
        assertEquals(750, counts[0]);
        assertEquals(250, counts[1]);

        Map<Long, NflSeasonLine> lines = nfl.lines(NflSyntheticSeason.S1);
        assertEquals(NflSyntheticSeason.VETERANS, lines.size());
        long id0 = idOf(0);
        NflSeasonLine l = lines.get(id0);
        assertEquals(List.of("QB"), l.positions());
        assertEquals(16, l.games());
        assertEquals("T0", l.team());
        assertNull(l.age());
        double expectedPassYd = 0;
        for (int w = 1; w <= 16; w++) expectedPassYd += NflSyntheticSeason.weekStats(0, w)[0];
        assertEquals(expectedPassYd, l.stat("pass_yd"), 1e-9);
        assertEquals(13, l.totals().size());
        assertThrows(IllegalArgumentException.class, () -> l.stat("pts"), "a basketball key is not a football stat");
    }

    @Test
    void weekRowsComeBackOrderedWithByesAbsent() {
        long a = idOf(4), b = idOf(5);   // bye weeks (4 % 4)+1 = 1 and (5 % 4)+1 = 2
        List<NflWeekLine> rows = nfl.playedWeeks(NflSyntheticSeason.S, List.of(b, a));
        assertEquals(6, rows.size(), "two players, three games each");
        for (int i = 1; i < rows.size(); i++) {
            NflWeekLine p = rows.get(i - 1), c = rows.get(i);
            assertTrue(p.week() < c.week() || (p.week() == c.week() && p.playerId() < c.playerId()),
                    "ordered by (week, player)");
        }
        assertTrue(rows.stream().noneMatch(r -> r.playerId() == a && r.week() == 1), "player 4 has a bye in week 1");
        assertTrue(rows.stream().noneMatch(r -> r.playerId() == b && r.week() == 2), "player 5 has a bye in week 2");
        NflWeekLine first = rows.stream().filter(r -> r.playerId() == a && r.week() == 2).findFirst().orElseThrow();
        assertNull(first.gameDate());
        assertEquals(NflSyntheticSeason.weekStats(4, 2)[3], first.stat("rush_yd"), 1e-9);
        assertEquals("ZZZ", first.team());
        assertTrue(nfl.playedWeeks(NflSyntheticSeason.S, List.of()).isEmpty());
    }

    @Test
    void reImportingASeasonReplacesRowsAndKeepsPlayerIds() {
        long before = idOf(7);
        NflSyntheticSeason.seed(nfl, jdbc);   // a second import of the same synthetic data
        assertEquals(before, idOf(7), "a re-import must keep his id: saved games point at it (053 F9)");
        int[] counts = nfl.rowCounts(NflSyntheticSeason.S);
        assertEquals(750, counts[0], "replaced, not doubled");
        assertEquals(250, counts[1]);
    }

    @Test
    void theSeasonRowCarriesTheFootballColumnsAndTheOfferedQueryIsSportScoped() {
        SeasonRow row = data.findSeason("NFL", NflSyntheticSeason.S).orElseThrow();
        assertEquals("NFL", row.sport());
        assertEquals("NFLVERSE", row.source());
        assertEquals("1895", row.label());
        assertNull(row.openingNight());
        assertNull(row.lastCountedDate());
        assertEquals(64, row.expectedGameCount());
        assertEquals(60, row.gameCount());
        assertEquals(3, row.droppedRows());
        assertEquals("synthetic-sha", row.sourceSha256());
        assertEquals(NflHistoricalSport.COUNTING, row.countedStats());
        assertEquals("{}", row.excludedStatsJson());

        // 1894 has 1893 lines behind it, 1895 has 1894 lines, 1893 has nothing behind it
        List<Integer> offered = data.offeredSeasons("NFL", 1893, 1895).stream().map(SeasonRow::seasonStart).toList();
        assertEquals(List.of(1894, 1895), offered);
        assertTrue(data.offeredSeasons("NFL", 1893, 1895).stream().allMatch(s -> s.sport().equals("NFL")));
        assertThrows(IllegalArgumentException.class, () -> data.offeredSeasons("NFL"),
                "football's range is configuration, not a repository constant");
    }

    @Test
    void aDuplicatePlayerWeekIsRefusedByThePrimaryKey() {
        long id = idOf(3);
        assertThrows(DuplicateKeyException.class, () -> jdbc.update(
                "insert into historical_nfl_player_week (player_id, season, week, position) values (?, ?, 2, 'RB')",
                id, NflSyntheticSeason.S));
    }

    @Test
    void draftPicksForTheDraftedSeasonComeBackBySlot() {
        Map<Long, RookiePick> picks = nfl.draftPicks(NflSyntheticSeason.S);
        assertEquals(10, picks.size());
        assertEquals(new RookiePick(1, 5), picks.get(idOf(240)));
        assertEquals(new RookiePick(3, 80), picks.get(idOf(243)));
        assertTrue(nfl.draftPicks(NflSyntheticSeason.S1).isEmpty());
    }

    /** {@code replaceDraftPicks} replaces the whole table, so it runs in a transaction that is rolled back. */
    @Test
    void replaceDraftPicksStoresOnlyKnownPlayersFirstDraftAndIsRolledBack() {
        List<DraftPickRow> in = List.of(
                new DraftPickRow(NflSyntheticSeason.PREFIX + "240", 1895, 2, 33, "RB"),
                new DraftPickRow(NflSyntheticSeason.PREFIX + "241", 1895, 1, 3, "WR"),
                new DraftPickRow("not-in-the-database", 1895, 1, 1, "QB"));
        Integer[] result = new Integer[3];
        tx.executeWithoutResult(status -> {
            result[0] = nfl.replaceDraftPicks(in);
            result[1] = jdbc.queryForObject("select count(*) from historical_nfl_draft_pick where season = ?",
                    Integer.class, NflSyntheticSeason.S);
            result[2] = jdbc.queryForObject("select pick from historical_nfl_draft_pick where player_id = ?",
                    Integer.class, idOf(240));
            status.setRollbackOnly();
        });
        assertEquals(2, result[0], "the player that is not in historical_player gets no row");
        assertEquals(2, result[1]);
        assertEquals(33, result[2]);
        assertEquals(10, nfl.draftPicks(NflSyntheticSeason.S).size(), "rolled back: the seeded picks are back");
    }

    @Test
    void candidatesAreEveryoneWithAGameInTheDraftedSeasonOrderedById() {
        var c = nfl.candidates(NflSyntheticSeason.S);
        assertEquals(250, c.size());
        Set<String> names = c.stream().map(HistoricalBoardBuilder.Candidate::name).collect(Collectors.toSet());
        assertTrue(names.contains(NflSyntheticSeason.TWIN));
        assertEquals(249, names.size(), "two players share one display name but are two candidates");
        for (int i = 1; i < c.size(); i++) assertTrue(c.get(i - 1).id() < c.get(i).id());
        assertTrue(nfl.candidates(NflSyntheticSeason.S1).isEmpty(), "S-1 has season lines but no week rows");
    }

    private long idOf(int i) {
        return jdbc.queryForObject("select id from historical_player where source = 'NFLVERSE' and source_key = ?",
                Long.class, NflSyntheticSeason.PREFIX + i);
    }
}
