package com.ballknowers.draftsim.historical;

import com.ballknowers.draftsim.historical.HistoricalDataRepository.GameLine;
import com.ballknowers.draftsim.historical.HistoricalDataRepository.SeasonLine;
import com.ballknowers.draftsim.historical.HistoricalDataRepository.SeasonRow;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A made-up three-season world for integration tests of the game flow (spec 053), at season starts no real
 * import produces (1893..1895), so it can sit beside the real reference data without touching it.
 *
 * <p>Season 1895 is the one drafted. Its own lines carry SENTINEL values (team ZZZ, age 99, 91 games, 7777
 * points, ...) so a test can prove none of them reaches the draft room (FR-003). Players are source-keyed with
 * {@link #PREFIX} so {@link #clean} removes exactly what {@link #seed} wrote.
 */
public final class HistoricalSyntheticSeason {

    public static final int S2 = 1893, S1 = 1894, S = 1895;
    public static final String PREFIX = "it-hgame-";
    public static final int PLAYERS = 200;
    public static final int WEEKS = 3;

    private HistoricalSyntheticSeason() {}

    public static void clean(JdbcTemplate jdbc) {
        jdbc.update("delete from historical_game where season_start between ? and ?", S2, S);
        jdbc.update("delete from historical_player_game where season_start between ? and ?", S2, S);
        jdbc.update("delete from historical_player_season where season_start between ? and ?", S2, S);
        jdbc.update("delete from historical_player where source_key like ?", PREFIX + "%");
        jdbc.update("delete from historical_season where season_start between ? and ?", S2, S);
    }

    /** Seeds the season row, {@link #PLAYERS} players, their S-2/S-1 lines, the sentinel S line and 3 weeks of games. */
    public static void seed(HistoricalDataRepository data) {
        data.upsertSeason(new SeasonRow(S, "1895-96", "KAGGLE", LocalDate.of(1895, 11, 6), LocalDate.of(1896, 4, 1),
                WEEKS, 100, List.of("pts", "reb", "ast"),
                "{\"stl\":\"not tracked by the league that season\"}", "{\"pts\":1.0,\"reb\":0.97,\"ast\":0.95}",
                "PRIOR_SEASON_POINTS", OffsetDateTime.parse("2026-11-02T12:00:00Z")));

        String[] pos = {"PG", "SG", "SF", "PF", "C"};
        List<SeasonLine> l2 = new ArrayList<>(), l1 = new ArrayList<>(), l0 = new ArrayList<>();
        List<GameLine> games = new ArrayList<>();
        for (int i = 0; i < PLAYERS; i++) {
            long id = data.upsertPlayer("KAGGLE", PREFIX + i, "Synthetic " + i);
            List<String> p = i % 7 == 0 ? List.of(pos[i % 5], pos[(i + 1) % 5]) : List.of(pos[i % 5]);
            if (i % 2 == 0) {
                l2.add(new SeasonLine(id, S2, "OLD", 30, 70, p, 70 * (6.0 + i % 11), 70.0 * (2 + i % 5),
                        70.0 * (1 + i % 4), null, null, null, null, null, null, null, null));
            }
            // distinct per-game points per player, so the ranking has a clear order
            l1.add(new SeasonLine(id, S1, "T" + (i % 30), 20 + i % 15, 60 + i % 20, p, (60 + i % 20) * (8.0 + i % 23),
                    (60 + i % 20) * (2.0 + i % 9), (60 + i % 20) * (1.0 + i % 6), null, null, null, null, null, null,
                    null, null));
            // the drafted season's own line: SENTINELS that must never reach the draft room
            l0.add(new SeasonLine(id, S, "ZZZ", 99, 91, p, 7777.0, 8888.0, 9999.0, null, null, null, null, null,
                    null, null, null));
            for (int w = 1; w <= WEEKS; w++) {
                games.add(new GameLine(id, w * 1000L + i, S, LocalDate.of(1895, 11, 6).plusDays(7L * (w - 1) + i % 5),
                        w, "ZZZ", 5 + (i * 7 + w * 3) % 30, w == 1 ? null : 3 + i % 6, 2 + i % 4, null, null, null,
                        null, null, null, null, null, null, null, null, null, null, null, null, null));
            }
        }
        data.replaceSeasonLines(S2, l2);
        data.replaceSeasonLines(S1, l1);
        data.replaceSeasonLines(S, l0);
        data.replaceSeasonGames(S, games);
    }
}
