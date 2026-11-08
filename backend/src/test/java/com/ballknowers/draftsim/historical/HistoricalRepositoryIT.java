package com.ballknowers.draftsim.historical;

import com.ballknowers.draftsim.historical.HistoricalDataRepository.GameLine;
import com.ballknowers.draftsim.historical.HistoricalDataRepository.NamedSeasonLine;
import com.ballknowers.draftsim.historical.HistoricalDataRepository.SeasonLine;
import com.ballknowers.draftsim.historical.HistoricalDataRepository.SeasonRow;
import com.ballknowers.draftsim.historical.HistoricalGameRepository.GameRow;
import com.ballknowers.draftsim.historical.HistoricalGameRepository.PickRow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 053 T008 against Postgres: the two historical repositories. What only execution shows (bug classes 2
 * and 3): a {@code text[]} bind and a {@code timestamptz} bind actually round-trip, the reserved column
 * {@code "to"} reads back, an upsert keeps the player id across imports, and {@code select ... for update}
 * is valid SQL for the game row.
 *
 * <p>Uses seasons 1890-1892, which no real import produces, and removes everything it wrote. SKIPS when
 * Postgres isn't reachable at {@code DB_URL}, so read the skip count.
 */
@SpringBootTest
class HistoricalRepositoryIT {

    private static final int S0 = 1890, S1 = 1891, S2 = 1892;
    private static final String SRC_PREFIX = "it-hist-";
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-02T12:00:00Z");

    @BeforeAll
    static void requiresPostgres() {
        String url = System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5433/draftsim");
        boolean reachable;
        try (Connection c = DriverManager.getConnection(url, "draftsim", "draftsim")) {
            reachable = true;
        } catch (SQLException e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable, "no Postgres reachable at " + url + " -- skipping historical repository IT");
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private HistoricalDataRepository data;
    @Autowired private HistoricalGameRepository games;
    @Autowired private TransactionTemplate tx;

    @BeforeEach
    @AfterEach
    void clean() {
        jdbc.update("delete from historical_game where season_start between ? and ?", S0, S2);
        jdbc.update("delete from historical_player_game where season_start between ? and ?", S0, S2);
        jdbc.update("delete from historical_player_season where season_start between ? and ?", S0, S2);
        jdbc.update("delete from historical_player where source_key like ?", SRC_PREFIX + "%");
        jdbc.update("delete from historical_season where season_start between ? and ?", S0, S2);
    }

    private SeasonRow season(int start) {
        return new SeasonRow("NBA", start, start + "-" + (start + 1), "KAGGLE", LocalDate.of(start, 10, 22),
                LocalDate.of(start + 1, 4, 10), 25, 1230, List.of("pts", "reb", "ast"),
                "{\"stl\":\"not tracked by the league that season\"}", "{\"pts\":1.0,\"reb\":0.96}",
                "PRIOR_SEASON_POINTS", NOW, null, 0, null);
    }

    @Test
    void upsertKeepsThePlayerIdStableAndRefreshesTheName() {
        long a = data.upsertPlayer("KAGGLE", SRC_PREFIX + "a", "Old Name");
        long b = data.upsertPlayer("KAGGLE", SRC_PREFIX + "a", "New Name");
        assertEquals(a, b, "a second import must not mint a new id");
        assertEquals("New Name", data.playersByIds(List.of(a)).get(0).name());
        assertTrue(data.playersByIds(List.of()).isEmpty());
    }

    @Test
    void seasonLinesRoundTripTextArraysNullsAndTheReservedToColumn() {
        long p = data.upsertPlayer("KAGGLE", SRC_PREFIX + "p", "Player P");
        long q = data.upsertPlayer("KAGGLE", SRC_PREFIX + "q", "Player Q");
        data.replaceSeasonLines(S0, List.of(
                new SeasonLine(p, S0, "BOS", 24, 70, List.of("PG", "SG"), 1500.0, 400.0, 300.0, 80.0, 20.0,
                        150.0, 10.0, 600.0, 1300.0, 200.0, 260.0),
                new SeasonLine(q, S0, null, null, 12, List.of("C"), 100.0, null, null, null, null,
                        null, null, null, null, null, null)));

        List<NamedSeasonLine> read = data.seasonLines(S0);
        assertEquals(2, read.size());
        SeasonLine lp = read.get(0).line();
        assertEquals("Player P", read.get(0).name());
        assertEquals(List.of("PG", "SG"), lp.positions(), "text[] must round-trip");
        assertEquals(150.0, lp.to(), 1e-9, "\"to\" must read back");
        assertEquals(24, lp.age());
        SeasonLine lq = read.get(1).line();
        assertNull(lq.age());
        assertNull(lq.team());
        assertNull(lq.reb(), "unrecorded stays null, not zero");
        assertNull(lq.to());

        // A re-import replaces, it does not add.
        data.replaceSeasonLines(S0, List.of(lp));
        assertEquals(1, data.seasonLines(S0).size());
    }

    @Test
    void gamesRoundTripAndFilterByPlayer() {
        long p = data.upsertPlayer("KAGGLE", SRC_PREFIX + "gp", "Gamer P");
        long q = data.upsertPlayer("KAGGLE", SRC_PREFIX + "gq", "Gamer Q");
        List<GameLine> rows = new ArrayList<>();
        rows.add(new GameLine(p, 1L, S1, LocalDate.of(S1, 10, 22), 1, "BOS", 30, 10, 5, 2, 1, 3, 2, 12, 20, 4, 5,
                null, null, 1, 0, 0, 0, 0, 0));
        rows.add(new GameLine(p, 2L, S1, LocalDate.of(S1, 10, 29), 2, "BOS", 12, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null));
        rows.add(new GameLine(q, 1L, S1, LocalDate.of(S1, 10, 22), 1, "NYK", 8, 2, 2, 0, 0, 1, 0, 3, 9, 2, 2,
                null, null, 0, 0, 0, 0, 0, 0));
        for (int i = 0; i < 2500; i++) { // more than one 1000-row batch
            rows.add(new GameLine(q, 100L + i, S1, LocalDate.of(S1, 11, 5), 3, "NYK", 5, 1, 1, 0, 0, 0, 0, 2, 5, 1, 1,
                    null, null, 0, 0, 0, 0, 0, 0));
        }
        data.replaceSeasonGames(S1, rows);

        List<GameLine> onlyP = data.playedGames(S1, List.of(p));
        assertEquals(2, onlyP.size());
        assertEquals(3, onlyP.get(0).to(), "\"to\" must read back");
        assertNull(onlyP.get(1).reb(), "unrecorded stays null, not zero");
        assertNull(onlyP.get(1).dd());
        assertEquals(1, onlyP.get(0).dd());
        assertEquals(LocalDate.of(S1, 10, 22), onlyP.get(0).gameDate());
        assertEquals(2503, data.playedGames(S1, null).size());
        assertTrue(data.playedGames(S1, List.of()).isEmpty());

        data.replaceSeasonGames(S1, List.of(rows.get(0)));
        assertEquals(1, data.playedGames(S1, null).size(), "a re-import replaces the season");
    }

    @Test
    void aSeasonIsOfferedOnlyWhenItsPriorSeasonLinesExist() {
        long p = data.upsertPlayer("KAGGLE", SRC_PREFIX + "o", "Offered O");
        data.upsertSeason(season(S1));
        assertTrue(data.offeredSeasons("NBA", S0, S2).stream().noneMatch(s -> s.seasonStart() == S1),
                "no prior-season lines yet, so it must not be offered");

        data.replaceSeasonLines(S0, List.of(new SeasonLine(p, S0, "BOS", 25, 60, List.of("C"), 1000.0, 300.0, 100.0,
                50.0, 40.0, 90.0, 0.0, 400.0, 900.0, 100.0, 150.0)));
        assertTrue(data.offeredSeasons("NBA", S0, S2).stream().anyMatch(s -> s.seasonStart() == S1));

        SeasonRow read = data.findSeason("NBA", S1).orElseThrow();
        assertEquals(List.of("pts", "reb", "ast"), read.countedStats(), "text[] must round-trip");
        assertEquals(NOW, read.importedAt().withOffsetSameInstant(java.time.ZoneOffset.UTC));
        assertEquals(LocalDate.of(S1, 10, 22), read.openingNight());
        assertTrue(read.excludedStatsJson().contains("stl"));

        data.upsertSeason(season(S1)); // an upsert, not a duplicate-key failure
        assertTrue(data.findSeason("NBA", S2).isEmpty());
    }

    @Test
    void importJobsOpenAndClose() {
        long id = data.createJob("kaggle", NOW);
        assertEquals("RUNNING", data.findJob(id).orElseThrow().status());
        data.updateJobReport(id, "{\"season\":1971}");
        data.finishJob(id, "DONE", NOW.plusMinutes(3), "{\"ok\":true}");
        var done = data.findJob(id).orElseThrow();
        assertEquals("DONE", done.status());
        assertTrue(done.reportJson().contains("ok"));
        assertNotNull(done.finishedAt());
        jdbc.update("delete from historical_import_job where id = ?", id);
    }

    @Test
    void gameLifecycleCreateLockPicksAdvanceResult() {
        long p = data.upsertPlayer("KAGGLE", SRC_PREFIX + "x", "Pick X");
        long q = data.upsertPlayer("KAGGLE", SRC_PREFIX + "y", "Pick Y");
        data.upsertSeason(season(S1));

        long id = games.create("it-user", "NBA", S1, 12, 14, 0, List.of("PG", "SG", "BN"), "{\"pts\":0.5}", 4, 99L,
                "[{\"playerId\":" + p + ",\"adp\":1,\"positionalRank\":1}]", NOW);

        GameRow g = games.find(id).orElseThrow();
        assertEquals("DRAFTING", g.status());
        assertEquals(1, g.currentPickNo());
        assertEquals(List.of("PG", "SG", "BN"), g.rosterPositions());
        assertEquals(0, g.reversalRound());
        assertNull(g.resultJson());
        assertEquals(NOW, g.createdAt().withOffsetSameInstant(java.time.ZoneOffset.UTC));

        games.insertPicks(List.of(new PickRow(id, 1, 1, 1, "BOT", p), new PickRow(id, 2, 1, 2, "USER", q)));
        assertEquals(2, games.picks(id).size());
        assertThrows(Exception.class, () -> games.insertPicks(List.of(new PickRow(id, 3, 1, 3, "BOT", p))),
                "a player can be drafted once per game");

        // The lock: valid SQL, and it returns the row inside a transaction.
        GameRow locked = tx.execute(s -> games.lockForUpdate(id).orElseThrow());
        assertEquals(id, locked.id());

        games.advance(id, 3, "DRAFTING");
        assertEquals(3, games.find(id).orElseThrow().currentPickNo());

        games.writeResult(id, "{\"standings\":[]}", NOW.plusHours(1));
        GameRow done = games.find(id).orElseThrow();
        assertEquals("COMPLETE", done.status());
        assertTrue(done.resultJson().contains("standings"));
        assertNotNull(done.completedAt());

        assertEquals(1, games.listByUser("it-user").size());
        assertTrue(games.listByUser("someone-else").isEmpty());
        assertTrue(games.lockForUpdate(-1).isEmpty());
    }
}
