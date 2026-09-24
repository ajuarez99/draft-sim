package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.store.PlayerGameRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JOEL_EMBIID wiring-layer pure helpers (coordinator follow-up 2026-09-23,
 * bug-hunting review of specs/008-season-superlatives). Pure and in-memory --
 * no Postgres.
 */
class SeasonSuperlativesAbsenceWiringTest {

    // ------------------------------------------------------- item 3: per-league availability

    @Test
    void everyRosteredPlayerNeverWalkedIsReportedAsSuchAndOnlyThose() {
        Set<String> rostered = Set.of("A", "B", "C");
        Set<String> gamePlayers = Set.of("A"); // A has a player_game row
        Set<String> absencePlayers = Set.of("B"); // B has a player_absence row
        // C has neither -- never walked.

        Set<String> neverWalked = SeasonSuperlativesService.neverWalked(rostered, gamePlayers, absencePlayers);

        assertEquals(Set.of("C"), neverWalked);
    }

    /**
     * The exact bug: a second same-sport-season league's own backfill must
     * not make THIS league's players look walked. Modelled here by simply
     * never including that other league's rostered players in {@code
     * rostered} at all -- {@code neverWalked} only ever looks at the ids it's
     * handed, which is the fix's whole point (the caller passes THIS
     * league's own {@code allPlayerIds}, not a sport-season-wide set).
     */
    @Test
    void aPlayerRosteredOnlyByThisLeagueIsNeverWalkedEvenIfGamesTableHasRowsForOtherPlayers() {
        Set<String> rostered = Set.of("OnlyOnThisLeague");
        // The sport-season's player_game/player_absence tables have rows --
        // just none for this league's own rostered player.
        Set<String> gamePlayers = Set.of("SomeoneElsesPlayer");
        Set<String> absencePlayers = Set.of("AnotherElsewherePlayer");

        Set<String> neverWalked = SeasonSuperlativesService.neverWalked(rostered, gamePlayers, absencePlayers);

        assertEquals(Set.of("OnlyOnThisLeague"), neverWalked,
                "a same-sport-season league's own backfill must not make this league's own player look walked");
    }

    @Test
    void everyoneWalkedIsAnEmptySet() {
        Set<String> rostered = Set.of("A", "B");
        Set<String> neverWalked = SeasonSuperlativesService.neverWalked(rostered, Set.of("A", "B"), Set.of());
        assertTrue(neverWalked.isEmpty());
    }

    // ------------------------------------------------------- item 4/1: bounded player_game rows

    private static PlayerGameRepository.Row gameRow(int week, String playerId, double pts) {
        return new PlayerGameRepository.Row(Sport.NFL, 2025, week, playerId, "g" + week + "-" + playerId,
                LocalDate.of(2025, 9, 1), null, null, "{\"rec_yd\":" + (pts * 10) + "}");
    }

    @Test
    void playoffAndOffWindowWeeksAreExcludedFromTheMeanAndFromPlayedWeeks() {
        List<PlayerGameRepository.Row> rows = List.of(
                gameRow(1, "P1", 10.0),   // regular season, counts
                gameRow(2, "P1", 20.0),   // regular season, counts
                gameRow(15, "P1", 100.0)); // playoff week, must NOT count
        Set<Integer> scoredWeeks = Set.of(1, 2); // throughWeek = 2, playoffWeekStart = 15
        Map<String, Double> scoring = Map.of("rec_yd", 0.1);
        GameScoringService gameScoring = new GameScoringService();

        SeasonSuperlativesService.BoundedPlayerGames bounded =
                SeasonSuperlativesService.boundedPlayerGames(rows, scoredWeeks, scoring, gameScoring);

        assertEquals(Set.of(1, 2), bounded.playedWeeksByPlayer().get("P1"),
                "the playoff week must not appear in playedWeeksByPlayer");
        // Mean of only weeks 1 and 2's scored points: (10.0 + 20.0) / 2 = 15.0
        assertEquals(15.0, bounded.pointsPerGame().get("P1"), 1e-9,
                "the playoff week's 100-point game must not drag the mean up");
    }

    @Test
    void aPlayerWithNoRowsInTheScoredWindowIsAbsentFromBothMaps() {
        List<PlayerGameRepository.Row> rows = List.of(gameRow(15, "P1", 100.0)); // only a playoff-week row
        Set<Integer> scoredWeeks = Set.of(1, 2);
        Map<String, Double> scoring = Map.of("rec_yd", 0.1);

        SeasonSuperlativesService.BoundedPlayerGames bounded =
                SeasonSuperlativesService.boundedPlayerGames(rows, scoredWeeks, scoring, new GameScoringService());

        assertFalse(bounded.playedWeeksByPlayer().containsKey("P1"));
        assertFalse(bounded.pointsPerGame().containsKey("P1"));
    }

    // ------------------------------------------------------- coverage merge

    @Test
    void mergeCoverageCombinesLeagueLevelAndPerHolderReasons() {
        SeasonSuperlativesService.Coverage unclassified =
                new SeasonSuperlativesService.Coverage(14, 0, List.of("roster 4: 2 weeks couldn't be classified as a bye or a missed game"));

        SeasonSuperlativesService.Coverage merged = SeasonSuperlativesService.mergeCoverage(
                List.of("3 rostered players have no per-game records — run POST /api/ingest/player-games/X"),
                unclassified, 14);

        assertEquals(2, merged.reasons().size());
        assertTrue(merged.reasons().get(0).startsWith("3 rostered players"));
        assertTrue(merged.reasons().get(1).startsWith("roster 4:"));
    }

    @Test
    void mergeCoverageIsNullWhenBothSourcesAreEmpty() {
        assertNull(SeasonSuperlativesService.mergeCoverage(List.of(), null, 14));
    }
}
