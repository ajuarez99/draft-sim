package com.ballknowers.draftsim.historical;

import com.ballknowers.draftsim.historical.SleeperHistoricalSource.Meta;
import com.ballknowers.draftsim.historical.SleeperHistoricalSource.Parsed;
import com.ballknowers.draftsim.historical.SleeperHistoricalSource.SleeperGame;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 053 T012 with fixture JSON: what the weekly parser keeps and drops (F11), the 2019-20 cut (FR-010), game
 * id handling, and the Sleeper-side position and age rules (F5, F19). Shaped like the real
 * {@code /stats/nba/{season}/{week}} entries measured on 2026-11-02.
 */
class SleeperHistoricalSourceTest {

    /** BasketballRules.playedIn: stats non-null and non-empty. */
    private static final Predicate<Map<String, ?>> PLAYED = m -> m != null && !m.isEmpty();

    private static Map<String, Object> entry(String playerId, String gameId, String date, String team,
                                             Map<String, Object> stats) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("player_id", playerId);
        e.put("game_id", gameId);
        e.put("date", date);
        e.put("team", team);
        e.put("stats", stats);
        return e;
    }

    private static Map<String, Object> stats(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @Test
    void teamPseudoPlayersAndEmptyStatsAreDroppedAndCounted() {
        Parsed p = new Parsed();
        SleeperHistoricalSource.parseWeek(List.of(
                entry("1000", "20231120_CHI_MIA", "2023-11-20", "MIA", stats("pts", 20.0, "reb", 5.0)),
                entry("TEAM_CHI", "20231120_CHI_MIA", "2023-11-20", "CHI", stats("pts", 100.0)),
                entry("2000", "20231120_CHI_MIA", "2023-11-20", "CHI", stats()),           // a DNP: {}
                entry("3000", "20231120_CHI_MIA", "2023-11-20", "CHI", null)),               // no stats at all
                PLAYED, null, p);
        assertEquals(1, p.games.size());
        assertEquals("1000", p.games.get(0).playerId());
        assertEquals(1, p.teamEntries);
        assertEquals(2, p.emptyStats);
    }

    @Test
    void theShutdownCutKeepsTheCutoffDayAndDropsTheDayAfter() {
        LocalDate cut = SleeperHistoricalSource.LAST_COUNTED.get(2019);
        assertEquals(LocalDate.of(2020, 3, 11), cut);
        Parsed p = new Parsed();
        SleeperHistoricalSource.parseWeek(List.of(
                entry("1", "g1", "2020-03-10", "BOS", stats("pts", 10.0)),
                entry("1", "g2", "2020-03-11", "BOS", stats("pts", 11.0)),
                entry("1", "g3", "2020-03-12", "BOS", stats("pts", 12.0)),
                entry("1", "g4", "2020-07-30", "BOS", stats("pts", 13.0))), PLAYED, cut, p);
        assertEquals(2, p.games.size());
        assertEquals(2, p.afterCutoff);
        assertEquals(LocalDate.of(2020, 3, 11), p.games.get(1).date());
        assertNull(SleeperHistoricalSource.LAST_COUNTED.get(2021), "only 2019-20 is cut");
    }

    @Test
    void aMissingStatKeyIsZeroAndFlagsAreClampedToZeroOrOne() {
        Parsed p = new Parsed();
        SleeperHistoricalSource.parseWeek(List.of(entry("7", "9", "2023-11-20", "CHI", stats(
                "pts", 100.0, "reb", 36.0, "ast", 21.0, "stl", 2.0, "blk", 7.0, "to", 11.0, "tpm", 11.0,
                "dd", 1.0, "bonus_pt_40p", 1.0, "bonus_pt_50p", 1.0, "bonus_reb_20p", 1.0,
                "bonus_ast_15p", 1.0, "tf", 2.0, "plus_minus", 17.0))), PLAYED, null, p);
        SleeperGame g = p.games.get(0);
        int[] v = g.v();
        List<String> keys = SleeperHistoricalSource.STAT_KEYS;
        assertEquals(100, v[keys.indexOf("pts")]);
        assertEquals(0, v[keys.indexOf("fgm")], "an omitted stat is 0, not null (Sleeper omits zeros)");
        assertEquals(1, v[keys.indexOf("td")], "no td key was sent, but 100/36/21 is a triple-double: derived from the box");
        assertEquals(1, v[keys.indexOf("dd")]);
        assertEquals(1, v[keys.indexOf("bonus_pt_50p")]);
        assertEquals(2, v[keys.indexOf("tf")]);
        assertEquals(0, v[keys.indexOf("ff")]);
        assertEquals(9L, g.gameId(), "a numeric game id is used as it is");
        assertEquals(0, p.hashedIds);
    }

    @Test
    void aNonNumericGameIdBecomesAStableNonNegativeHashAndIsCounted() {
        Parsed a = new Parsed(), b = new Parsed();
        Map<String, Object> e = entry("1", "20231120_CHI_MIA", "2023-11-20", "MIA", stats("pts", 1.0));
        SleeperHistoricalSource.parseWeek(List.of(e), PLAYED, null, a);
        SleeperHistoricalSource.parseWeek(List.of(e), PLAYED, null, b);
        assertEquals(a.games.get(0).gameId(), b.games.get(0).gameId(), "stable across runs");
        assertTrue(a.games.get(0).gameId() >= 0);
        assertEquals(1, a.hashedIds, "the report says ids were hashed");
        assertNotEquals(SleeperHistoricalSource.stableHash("20231120_CHI_MIA"),
                SleeperHistoricalSource.stableHash("20231121_CHI_MIA"));
    }

    @Test
    void anEntryWithoutADateIsDroppedRatherThanGuessed() {
        Parsed p = new Parsed();
        SleeperHistoricalSource.parseWeek(List.of(entry("1", "g", null, "BOS", stats("pts", 1.0))), PLAYED, null, p);
        assertTrue(p.games.isEmpty());
        assertEquals(1, p.noDate);
    }

    @Test
    void anEmbeddedPlayerObjectIsKeptForNamesAndPositions() {
        Parsed p = new Parsed();
        Map<String, Object> e = entry("1", "g", "2023-11-20", "MIA", stats("pts", 1.0));
        Map<String, Object> player = new LinkedHashMap<>();
        player.put("first_name", "Jimmy");
        player.put("last_name", "Butler III");
        player.put("fantasy_positions", List.of("PF", "SF", "SG"));
        player.put("position", "SF");
        e.put("player", player);
        SleeperHistoricalSource.parseWeek(List.of(e), PLAYED, null, p);
        Meta m = p.embedded.get("1");
        assertEquals("Jimmy Butler III", m.name());
        assertEquals(List.of("SF", "PF", "SG"), m.positions(), "Sleeper's own position goes first");
    }

    @Test
    void positionsMapToTheFivePositionsAndAnEmptySetStaysEmpty() {
        assertEquals(List.of("PG"), SleeperHistoricalSource.positionsOf(List.of("PG"), "PG"));
        assertEquals(List.of("SG", "PG"), SleeperHistoricalSource.positionsOf(List.of("PG", "SG"), "SG"));
        assertEquals(List.of("PG", "SG"), SleeperHistoricalSource.positionsOf(List.of("G"), null));
        assertTrue(SleeperHistoricalSource.positionsOf(List.of("DEF"), "DEF").isEmpty(), "archive artifacts drop");
        assertTrue(SleeperHistoricalSource.positionsOf(List.of(), null).isEmpty());
        // a position that is not already eligible is never inserted
        assertEquals(List.of("PG", "SG"), SleeperHistoricalSource.positionsOf(List.of("PG", "SG"), "C"));
    }

    @Test
    void ageIsOnOctoberFirstOfTheSeasonStartYear() {
        assertEquals(29, SleeperHistoricalSource.ageOnOctFirst(LocalDate.of(1988, 3, 14), 2017));
        assertEquals(28, SleeperHistoricalSource.ageOnOctFirst(LocalDate.of(1988, 10, 2), 2017));
        assertEquals(29, SleeperHistoricalSource.ageOnOctFirst(LocalDate.of(1988, 10, 1), 2017));
        assertNull(SleeperHistoricalSource.ageOnOctFirst(null, 2017));
    }

    @Test
    void thePolicyNumbersAreTheTaskSpec() {
        assertEquals(2014, SleeperHistoricalSource.FIRST_TOTALS_SEASON, "cards for 2017 look back to 2014, in one source");
        assertEquals(2017, SleeperHistoricalSource.FIRST_WEEKLY_SEASON);
        assertEquals(2025, SleeperHistoricalSource.LAST_SEASON);
        assertEquals(30, SleeperHistoricalSource.MAX_WEEK);
        assertEquals(19, SleeperHistoricalSource.STAT_KEYS.size());
    }

    // ---- review fixes: exhibitions, Cup finals, derived bonuses ---------------------------------------

    @Test
    void anAllStarGameAndACupFinalAreDroppedAndAnOrdinaryGameAndATeamlessRowAreKept() {
        Parsed p = new Parsed();
        p.excludedIds = java.util.Set.of("20231209_LAL_IND");
        List<Map<String, Object>> entries = new java.util.ArrayList<>();
        // 30 ordinary games between BOS and MIA; the All-Star sides (EST, WST) appear in one game each.
        for (int i = 0; i < 30; i++) {
            String gid = "reg" + i, d = "2023-11-" + String.format("%02d", i + 1);
            entries.add(entry("1", gid, d, "BOS", stats("pts", 20.0)));
            entries.add(entry("2", gid, d, "MIA", stats("pts", 10.0)));
        }
        entries.add(entry("1", "20240218_EST_WST", "2024-02-18", "EST", stats("pts", 55.0)));
        entries.add(entry("2", "20240218_EST_WST", "2024-02-18", "WST", stats("pts", 30.0)));
        entries.add(entry("1", "20231209_LAL_IND", "2023-12-09", "IND", stats("pts", 20.0)));  // Cup final
        entries.add(entry("2", "20231209_LAL_IND", "2023-12-09", "LAL", stats("pts", 20.0)));
        entries.add(entry("3", "teamless1", "2023-11-05", null, stats("pts", 12.0)));         // no team code
        SleeperHistoricalSource.parseWeek(entries, PLAYED, null, p);
        assertEquals(2, p.excludedRows, "the Cup final is dropped on its RAW id, before hashing");
        SleeperHistoricalSource.dropExhibitions(p);
        assertEquals(1, p.exhibitionGames);
        assertEquals(2, p.exhibitionRows);
        assertEquals(61, p.games.size(), "60 ordinary rows plus the team-less one");
        assertTrue(p.games.stream().noneMatch(g -> "20240218_EST_WST".equals(g.rawGameId())));
        assertTrue(p.games.stream().anyMatch(g -> "teamless1".equals(g.rawGameId())), "a null team is never exhibition");
    }

    @Test
    void aSleeperLineWithoutBonusKeysGetsThemDerivedFromTheBoxStats() {
        Parsed p = new Parsed();
        SleeperHistoricalSource.parseWeek(List.of(entry("7", "9", "2018-11-20", "HOU",
                stats("pts", 41.0, "reb", 21.0, "ast", 15.0, "stl", 1.0))), PLAYED, null, p);
        int[] v = p.games.get(0).v();
        List<String> keys = SleeperHistoricalSource.STAT_KEYS;
        assertEquals(1, v[keys.indexOf("bonus_pt_40p")]);
        assertEquals(0, v[keys.indexOf("bonus_pt_50p")]);
        assertEquals(1, v[keys.indexOf("bonus_reb_20p")]);
        assertEquals(1, v[keys.indexOf("bonus_ast_15p")]);
        assertEquals(1, v[keys.indexOf("dd")]);
        assertEquals(1, v[keys.indexOf("td")]);
    }
}
