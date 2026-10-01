package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.domain.Player;
import com.ballknowers.draftsim.domain.Position;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.engine.PlayerSpotlightService.Period;
import com.ballknowers.draftsim.engine.PlayerSpotlightService.PeriodRows;
import com.ballknowers.draftsim.engine.PlayerSpotlightService.TrendingEntry;
import com.ballknowers.draftsim.engine.PlayerSpotlightService.TrendingSection;
import com.ballknowers.draftsim.store.PlayerGameRepository;
import com.ballknowers.draftsim.store.SportTrendingRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Trending outcomes (specs/014 T032): a non-game is never a zero (FR-005). */
class PlayerSpotlightTrendingTest {

    static final LocalDate D = LocalDate.parse("2026-10-21");
    static final Period NIGHT = Period.night(D, 5);
    static final Map<String, Double> SCORING = Map.of("pts", 1.0);
    static final GameScoringService SCORER = new GameScoringService();
    static final Instant NOW = Instant.parse("2026-10-22T12:00:00Z");
    static final OffsetDateTime FRESH = NOW.minusSeconds(600).atOffset(ZoneOffset.UTC);

    static Player player(String id, String team) {
        return new Player(Math.abs(id.hashCode()), Sport.NBA, id, "Name " + id,
                List.of(Position.PG), team, "Active", null, 25, 3);
    }

    static PlayerGameRepository.Row game(String playerId, String gameId, String opponent, int pts) {
        return new PlayerGameRepository.Row(Sport.NBA, 2026, 1, playerId, gameId, D, opponent, true,
                "{\"pts\": " + pts + "}");
    }

    static Map<String, Player> byId(Player... ps) {
        Map<String, Player> m = new HashMap<>();
        for (Player p : ps) m.put(p.sleeperId(), p);
        return m;
    }

    static Optional<SportTrendingRepository.Snapshot> snap(OffsetDateTime fetchedAt, int hours, String... ids) {
        List<SportTrendingRepository.Entry> entries = new ArrayList<>();
        for (int i = 0; i < ids.length; i++) entries.add(new SportTrendingRepository.Entry(ids[i], 100 - i));
        return Optional.of(new SportTrendingRepository.Snapshot(fetchedAt, hours, 2026, null, null, null, entries));
    }

    static TrendingSection run(Optional<SportTrendingRepository.Snapshot> s, Period period,
                               PeriodRows rows, Map<String, Player> all) {
        return PlayerSpotlightService.trending(s, period, rows, Map.of(), all, NOW);
    }

    // The slate: AAA's player played OPP; the anchors make BBB an opponent. CCC is on no row's
    // opponent line, so a CCC player with no row is on a bye.
    final Map<String, Player> all = byId(player("played", "AAA"), player("dnp", "BBB"),
            player("bye", "CCC"), player("noteam", null), player("anchor", "DDD"), player("anchor2", "EEE"));

    PeriodRows slate() {
        List<PlayerGameRepository.Row> rows = List.of(
                game("played", "g1", "OPP", 22),
                game("anchor", "g2", "BBB", 5),
                game("anchor2", "g2", "DDD", 5));
        return new PeriodRows(rows, PlayerSpotlightService.scoreAll(rows, SCORING, SCORER, all));
    }

    @Test
    void outcomesPerTheContract() {
        TrendingSection t = run(snap(FRESH, 24, "played", "dnp", "bye", "noteam"), NIGHT, slate(), all);
        Map<String, TrendingEntry> by = new HashMap<>();
        for (TrendingEntry e : t.entries()) by.put(e.playerId(), e);

        assertEquals("PLAYED", by.get("played").outcome());
        assertEquals(22.0, by.get("played").points());
        assertEquals("OPP", by.get("played").opponent());
        assertEquals(Boolean.TRUE, by.get("played").isAway());
        assertEquals("DID_NOT_PLAY", by.get("dnp").outcome());
        assertEquals("NO_GAME", by.get("bye").outcome());
        assertEquals("DID_NOT_PLAY", by.get("noteam").outcome(), "a null team is never a bye");
    }

    @Test
    void noPeriodMeansNoPeriodForEveryEntry() {
        TrendingSection t = run(snap(FRESH, 24, "played", "dnp", "bye"), null,
                new PeriodRows(List.of(), List.of()), all);
        assertEquals(3, t.entries().size());
        for (TrendingEntry e : t.entries()) assertEquals("NO_PERIOD", e.outcome());
    }

    @Test
    void onlyPlayedEntriesCarryPointsOpponentOrAwayFlag() {
        TrendingSection t = run(snap(FRESH, 24, "played", "dnp", "bye", "noteam"), NIGHT, slate(), all);
        for (TrendingEntry e : t.entries()) {
            if (e.outcome().equals("PLAYED")) {
                assertNotNull(e.points());
            } else {
                assertNull(e.points(), e.outcome() + " must not carry points");
                assertNull(e.opponent());
                assertNull(e.isAway());
            }
        }
    }

    @Test
    void unknownPlayersAreDroppedAndCounted() {
        TrendingSection t = run(snap(FRESH, 24, "ghost1", "played", "ghost2"), NIGHT, slate(), all);
        assertEquals(List.of("played"), t.entries().stream().map(TrendingEntry::playerId).toList());
        assertEquals(2, t.omittedUnknownPlayers());
    }

    @Test
    void orderIsSleepersRankNotPoints() {
        // "played" scored 22 but Sleeper ranks it second; "dnp" has no points and ranks first.
        TrendingSection t = run(snap(FRESH, 24, "dnp", "played", "bye"), NIGHT, slate(), all);
        assertEquals(List.of("dnp", "played", "bye"),
                t.entries().stream().map(TrendingEntry::playerId).toList());
        assertEquals(List.of(1, 2, 3), t.entries().stream().map(TrendingEntry::rank).toList());
    }

    @Test
    void neverFetchedIsUnavailable() {
        TrendingSection none = run(Optional.empty(), NIGHT, slate(), all);
        assertEquals("NEVER_FETCHED", none.unavailable());
        assertTrue(none.entries().isEmpty());

        // A failure row with no success: fetchedAt null, whatever lastFailure says.
        var failedFirst = Optional.of(new SportTrendingRepository.Snapshot(null, 24, null, null,
                FRESH, "boom", List.of()));
        assertEquals("NEVER_FETCHED", run(failedFirst, NIGHT, slate(), all).unavailable());
    }

    @Test
    void aStaleFailureBesideANewerSuccessIsNotUnavailable() {
        var s = Optional.of(new SportTrendingRepository.Snapshot(FRESH, 24, 2026, null,
                FRESH.minusDays(2), "old failure",
                List.of(new SportTrendingRepository.Entry("played", 9))));
        TrendingSection t = run(s, NIGHT, slate(), all);
        assertNull(t.unavailable());
        assertEquals(1, t.entries().size());
    }

    @Test
    void staleMeansOlderThanTheLookbackWindow() {
        assertFalse(run(snap(NOW.minusSeconds(24 * 3600 - 60).atOffset(ZoneOffset.UTC), 24, "played"),
                NIGHT, slate(), all).stale());
        TrendingSection old = run(snap(NOW.minusSeconds(24 * 3600 + 60).atOffset(ZoneOffset.UTC), 24, "played"),
                NIGHT, slate(), all);
        assertTrue(old.stale());
        assertEquals(24, old.lookbackHours());
    }

    @Test
    void limitIsTenResolvedEntries() {
        String[] ids = new String[14];
        Map<String, Player> many = new HashMap<>(all);
        for (int i = 0; i < 14; i++) {
            ids[i] = "m" + i;
            many.put(ids[i], player(ids[i], "CCC"));
        }
        TrendingSection t = run(snap(FRESH, 24, ids), NIGHT, slate(), many);
        assertEquals(10, t.entries().size());
        assertEquals("m0", t.entries().get(0).playerId());
    }

    @Test
    void anUnsettledWeekIsNeverNoGame() {
        // The "Bye in Week 1" bug: a week still in progress, his team's game not yet played.
        Period open = Period.week(1, false);
        TrendingSection t = run(snap(FRESH, 24, "bye", "dnp"), open, slate(), all);
        for (TrendingEntry e : t.entries()) assertEquals("DID_NOT_PLAY", e.outcome(), e.playerId());
    }

    @Test
    void aFinalWeekWithHisTeamAbsentIsNoGame() {
        TrendingSection t = run(snap(FRESH, 24, "bye", "dnp"), Period.week(4, true), slate(), all);
        Map<String, TrendingEntry> by = new HashMap<>();
        for (TrendingEntry e : t.entries()) by.put(e.playerId(), e);
        assertEquals("NO_GAME", by.get("bye").outcome());
        assertEquals("DID_NOT_PLAY", by.get("dnp").outcome());
    }

    @Test
    void unknownIdsPastTheLimitAreNotCounted() {
        List<String> ids = new ArrayList<>();
        Map<String, Player> many = new HashMap<>(all);
        for (int i = 0; i < 10; i++) {
            ids.add("k" + i);
            many.put("k" + i, player("k" + i, "CCC"));
        }
        for (int i = 0; i < 15; i++) ids.add("ghost" + i);
        TrendingSection t = run(snap(FRESH, 24, ids.toArray(new String[0])), NIGHT, slate(), many);
        assertEquals(10, t.entries().size());
        assertEquals(0, t.omittedUnknownPlayers());
    }

    @Test
    void unknownIdsBeforeTheLimitAreCounted() {
        List<String> ids = new ArrayList<>();
        Map<String, Player> many = new HashMap<>(all);
        int known = 0;
        for (int i = 0; i < 13; i++) {
            if (i == 2 || i == 5 || i == 7) {
                ids.add("ghost" + i);
            } else {
                ids.add("k" + i);
                many.put("k" + i, player("k" + i, "CCC"));
                known++;
            }
        }
        assertEquals(10, known);
        for (int i = 0; i < 5; i++) ids.add("late" + i);
        TrendingSection t = run(snap(FRESH, 24, ids.toArray(new String[0])), NIGHT, slate(), many);
        assertEquals(10, t.entries().size());
        assertEquals(3, t.omittedUnknownPlayers());
    }
}
