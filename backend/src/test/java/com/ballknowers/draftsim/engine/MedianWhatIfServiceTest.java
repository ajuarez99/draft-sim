package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.engine.ExpectedWinsService.Game;
import com.ballknowers.draftsim.engine.ExpectedWinsService.WinLossTie;
import com.ballknowers.draftsim.engine.MedianWhatIfService.SeasonMedian;
import com.ballknowers.draftsim.engine.MedianWhatIfService.SeasonMedianTeam;
import com.ballknowers.draftsim.store.LeagueRepository.PlayoffFormat;
import com.ballknowers.draftsim.store.RosterSeasonRepository.StandingRow;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 054: the median what-if is one pure function over games, the stored standings and the playoff
 * format, so every rule is pinned here with no Spring and no database. Median and all-play themselves
 * are {@code ExpectedWinsService}'s and are tested there; this file only checks they are what is used.
 */
class MedianWhatIfServiceTest {

    /**
     * Four rosters, three regular-season weeks (pairings 1v2 3v4 / 1v3 2v4 / 1v4 2v3). Hand-worked:
     * <pre>
     *   week   r1   r2   r3   r4    median  real result
     *   1     200  100   90   80      95    1>2, 3>4
     *   2      50  110   60   40      55    3>1, 2>4
     *   3      70   95  100   80    87.5    4>1, 3>2
     * </pre>
     * Real: r1 1-2 (PF 320), r2 1-2 (305), r3 3-0 (250), r4 1-2 (200). Median: r1 1-2, r2 3-0, r3 2-1, r4 0-3.
     * Combined: r1 2-4, r2 4-2, r3 5-1, r4 1-5. Actual order r3, r1, r2, r4 (r1 and r2 tie on record, r1 has the
     * points); what-if order r3, r2, r1, r4.
     */
    private static List<Game> strictGames() {
        return List.of(
                new Game(1, 1, 200, 2, 100), new Game(1, 3, 90, 4, 80),
                new Game(2, 1, 50, 3, 60), new Game(2, 2, 110, 4, 40),
                new Game(3, 1, 70, 4, 80), new Game(3, 2, 95, 3, 100));
    }

    private static StandingRow row(int rosterId, Integer w, Integer l, Integer t, Double pf, Integer placement) {
        return new StandingRow(1L, rosterId, (long) rosterId, "m" + rosterId, null, w, l, t, pf, 0.0,
                placement, 2025, "x", Sport.NFL, "L", null);
    }

    private static List<StandingRow> strictOfficial() {
        return List.of(row(1, 1, 2, 0, 320.0, null), row(2, 1, 2, 0, 305.0, null),
                row(3, 3, 0, 0, 250.0, null), row(4, 1, 2, 0, 200.0, null));
    }

    /** No divisions, plain seed type, 2 playoff teams, regular season = weeks 1-3. */
    private static PlayoffFormat format(int playoffTeams) {
        return new PlayoffFormat(playoffTeams, 4, 0, false, false, 0);
    }

    /** No stored bracket. */
    private static SeasonMedian wi(List<Game> games, List<StandingRow> official, Optional<PlayoffFormat> format,
                                   boolean complete) {
        return MedianWhatIfService.whatIf(games, official, format, complete, Optional.empty());
    }

    private static SeasonMedian wi(List<Game> games, List<StandingRow> official, Optional<PlayoffFormat> format,
                                   boolean complete, Set<Integer> bracketField) {
        return MedianWhatIfService.whatIf(games, official, format, complete, Optional.of(bracketField));
    }

    private static Map<Integer, SeasonMedianTeam> byRoster(SeasonMedian m) {
        return m.teams().stream().collect(Collectors.toMap(SeasonMedianTeam::rosterId, t -> t));
    }

    private static SeasonMedian strict(int playoffTeams) {
        return wi(strictGames(), strictOfficial(), Optional.of(format(playoffTeams)), false);
    }

    // ---------------------------------------------------------------- invariants

    @Test
    void medianRecordCoversExactlyTheGamesCountedForEveryTeam() {
        SeasonMedian m = strict(2);
        assertEquals("WHAT_IF", m.status());
        assertEquals(3, m.weeksCounted());
        assertEquals(4, m.teams().size());
        for (SeasonMedianTeam t : m.teams()) {
            assertEquals(3, t.gamesCounted());
            assertEquals(t.gamesCounted(), t.median().wins() + t.median().losses() + t.median().ties());
        }
        // The figures themselves, worked by hand in the fixture javadoc.
        Map<Integer, SeasonMedianTeam> t = byRoster(m);
        assertEquals(new WinLossTie(1, 2, 0), t.get(1).median());
        assertEquals(new WinLossTie(3, 0, 0), t.get(2).median());
        assertEquals(new WinLossTie(2, 1, 0), t.get(3).median());
        assertEquals(new WinLossTie(0, 3, 0), t.get(4).median());
    }

    @Test
    void usesTheSameMedianAndAllPlayAsTheLuckPage() {
        SeasonMedian m = strict(2);
        Map<Integer, WinLossTie> median = ExpectedWinsService.median(strictGames());
        Map<Integer, WinLossTie> allPlay = ExpectedWinsService.allPlay(strictGames());
        for (SeasonMedianTeam t : m.teams()) {
            assertEquals(median.get(t.rosterId()), t.median());
            assertEquals(allPlay.get(t.rosterId()), t.allPlay());
        }
    }

    @Test
    void withMedianIsRealPlusMedianFieldByFieldAndWinsGainedIsMedianWins() {
        SeasonMedian m = strict(2);
        for (SeasonMedianTeam t : m.teams()) {
            assertEquals(t.real().wins() + t.median().wins(), t.withMedian().wins());
            assertEquals(t.real().losses() + t.median().losses(), t.withMedian().losses());
            assertEquals(t.real().ties() + t.median().ties(), t.withMedian().ties());
            assertEquals(t.median().wins(), t.winsGained());
        }
        Map<Integer, SeasonMedianTeam> t = byRoster(m);
        assertEquals(new WinLossTie(2, 4, 0), t.get(1).withMedian());
        assertEquals(new WinLossTie(4, 2, 0), t.get(2).withMedian());
        assertEquals(new WinLossTie(5, 1, 0), t.get(3).withMedian());
        assertEquals(new WinLossTie(1, 5, 0), t.get(4).withMedian());
    }

    @Test
    void placesAreEachAPermutationOfOneToN() {
        SeasonMedian m = strict(2);
        List<Integer> expected = IntStream.rangeClosed(1, m.teams().size()).boxed().toList();
        assertEquals(expected, m.teams().stream().map(SeasonMedianTeam::actualPlace).sorted().toList());
        assertEquals(expected, m.teams().stream().map(SeasonMedianTeam::whatIfPlace).sorted().toList());
    }

    // ------------------------------------------------- preference ordering (lessons bug class 1)

    /**
     * Equal real records (r1 and r2 both 1-2), r1 has more points, r2 has more median wins. Adding the
     * median must lift r2 above r1 and drop r1 below r2, never the reverse. A sign inversion keeps every
     * structural invariant above green; only this fails.
     */
    @Test
    void moreMedianWinsAtAnEqualRealRecordBeatsMorePointsOnlyAfterTheMedianIsAdded() {
        Map<Integer, SeasonMedianTeam> t = byRoster(strict(2));
        SeasonMedianTeam a = t.get(1);
        SeasonMedianTeam b = t.get(2);
        assertEquals(a.real(), b.real());
        assertTrue(a.median().wins() < b.median().wins());

        assertTrue(a.actualPlace() < b.actualPlace(), "equal record: the higher points-for ranks first");
        assertTrue(b.whatIfPlace() < a.whatIfPlace(), "median wins now outweigh the points");
        assertEquals(2, a.actualPlace());
        assertEquals(3, a.whatIfPlace());
        assertEquals(3, b.actualPlace());
        assertEquals(2, b.whatIfPlace());
        // Opposite movement signs.
        assertTrue(a.whatIfPlace() - a.actualPlace() > 0);
        assertTrue(b.whatIfPlace() - b.actualPlace() < 0);
    }

    @Test
    void aWinShareTieBreaksOnPointsForThenRosterId() {
        // Two rosters, one game each week, identical results and identical points: roster id decides.
        List<Game> games = List.of(new Game(1, 1, 100, 2, 100), new Game(2, 1, 100, 2, 100));
        List<StandingRow> official = List.of(row(1, 0, 0, 2, 200.0, null), row(2, 0, 0, 2, 200.0, null));
        SeasonMedian m = wi(games, official, Optional.of(new PlayoffFormat(1, 3, 0, false, false, 0)), false);
        Map<Integer, SeasonMedianTeam> t = byRoster(m);
        assertEquals(1, t.get(1).actualPlace());
        assertEquals(2, t.get(2).actualPlace());
    }

    // -------------------------------------------------------------------- playoff line

    @Test
    void aTeamThatCrossesTheLineIsTaggedInEachDirectionAndOthersAreNot() {
        SeasonMedian m = strict(2);
        assertEquals(2, m.playoffTeams());
        assertNull(m.playoffLineWithheld());
        Map<Integer, SeasonMedianTeam> t = byRoster(m);
        assertEquals("MISSED", t.get(1).playoffCross());   // in at 2nd, out at 3rd
        assertEquals("MADE", t.get(2).playoffCross());     // out at 3rd, in at 2nd
        assertNull(t.get(3).playoffCross());
        assertNull(t.get(4).playoffCross());
    }

    @Test
    void noMarkerWhenTheLineIsWithheld() {
        SeasonMedian m = wi(strictGames(), strictOfficial(),
                Optional.of(new PlayoffFormat(2, 4, 0, true, false, 0)), false);
        assertEquals("DIVISIONS", m.playoffLineWithheld());
        assertNull(m.playoffTeams());
        assertTrue(m.teams().stream().allMatch(x -> x.playoffCross() == null));
        // Places are still given: only the line is withheld.
        assertNotNull(byRoster(m).get(1).whatIfPlace());
    }

    @Test
    void eachUnmodelableFormatHasItsOwnCode() {
        assertEquals("DIVISIONS", withheld(new PlayoffFormat(2, 4, 0, true, false, 0)));
        assertEquals("SEED_TYPE", withheld(new PlayoffFormat(2, 4, 1, false, false, 0)));
        assertEquals("NO_PLAYOFF_TEAMS", withheld(new PlayoffFormat(0, 4, 0, false, false, 0)));
    }

    private static String withheld(PlayoffFormat f) {
        return wi(strictGames(), strictOfficial(), Optional.of(f), false).playoffLineWithheld();
    }

    /** Real ingest shape: only the champion (r3) has a final_placement, everyone else null. */
    private static List<StandingRow> championOnlyOfficial() {
        return List.of(row(1, 1, 2, 0, 320.0, null), row(2, 1, 2, 0, 305.0, null),
                row(3, 3, 0, 0, 250.0, 1), row(4, 1, 2, 0, 200.0, null));
    }

    @Test
    void aCompleteSeasonWhoseStoredBracketAgreesWithThisAppsOrderShowsTheMarkers() {
        // This app's top two are r3 and r1; the winners bracket field is {3, 1}.
        SeasonMedian m = wi(strictGames(), championOnlyOfficial(), Optional.of(format(2)), true, Set.of(1, 3));
        assertNull(m.playoffLineWithheld());
        assertEquals(2, m.playoffTeams());
        assertEquals("MISSED", byRoster(m).get(1).playoffCross());
        assertEquals("MADE", byRoster(m).get(2).playoffCross());
    }

    @Test
    void aCompleteSeasonWhoseStoredBracketDisagreesWithholdsTheLineAsOrderDisagrees() {
        // The bracket says r3 and r2 made it; this app's top two are r3 and r1.
        SeasonMedian m = wi(strictGames(), championOnlyOfficial(), Optional.of(format(2)), true, Set.of(2, 3));
        assertEquals("ORDER_DISAGREES", m.playoffLineWithheld());
        assertNull(m.playoffTeams());
        assertTrue(m.teams().stream().allMatch(x -> x.playoffCross() == null));
    }

    @Test
    void aCompleteSeasonWithNoStoredBracketIsBracketUnknownNotADisagreement() {
        SeasonMedian m = wi(strictGames(), championOnlyOfficial(), Optional.of(format(2)), true);
        assertEquals("BRACKET_UNKNOWN", m.playoffLineWithheld());
        assertNull(m.playoffTeams());
        assertTrue(m.teams().stream().allMatch(x -> x.playoffCross() == null));
    }

    @Test
    void aSeasonInProgressShowsTheLineWithNoBracketAtAll() {
        SeasonMedian m = wi(strictGames(), strictOfficial(), Optional.of(format(2)), false);
        assertNull(m.playoffLineWithheld());
        assertEquals(2, m.playoffTeams());
        assertEquals("MADE", byRoster(m).get(2).playoffCross());
    }

    // ------------------------------------------------------------------ odd roster count

    /**
     * Five rosters scored in one week (r4 appears in two games to get an odd count; the repository never
     * produces this, but the median rule must still hold): the middle score IS the median, so that roster
     * ties and gains no wins.
     */
    @Test
    void anOddNumberOfScoredRostersMakesTheMiddleRosterTieAndGainOnlyWins() {
        List<Game> games = List.of(
                new Game(1, 1, 50, 2, 40),
                new Game(1, 3, 30, 4, 60),
                new Game(1, 5, 20, 4, 60));
        List<StandingRow> official = List.of(row(1, 1, 0, 0, 50.0, null), row(2, 0, 1, 0, 40.0, null),
                row(3, 0, 1, 0, 30.0, null), row(4, 2, 0, 0, 120.0, null), row(5, 0, 1, 0, 20.0, null));
        SeasonMedian m = wi(games, official, Optional.of(new PlayoffFormat(2, 2, 0, false, false, 0)), false);
        SeasonMedianTeam mid = byRoster(m).get(2);          // 20,30,40,50,60: the median score is 40
        assertEquals(new WinLossTie(0, 0, 1), mid.median());
        assertEquals(0, mid.winsGained());
        assertEquals(1, mid.gamesCounted());
        assertEquals(new WinLossTie(0, 1, 1), mid.withMedian());
    }

    // ----------------------------------------------------------------------- ALREADY_ON

    @Test
    void alreadyOnKeepsMedianAndAllPlayAndNullsEveryWhatIfField() {
        SeasonMedian m = wi(strictGames(), strictOfficial(),
                Optional.of(new PlayoffFormat(2, 4, 0, false, true, 0)), false);
        assertEquals("ALREADY_ON", m.status());
        assertNull(m.realSource());
        assertEquals(0, m.driftGames());
        assertEquals(4, m.teams().size());
        for (SeasonMedianTeam t : m.teams()) {
            assertNotNull(t.median());
            assertNotNull(t.allPlay());
            assertNull(t.real());
            assertNull(t.withMedian());
            assertNull(t.winsGained());
            assertNull(t.actualPlace());
            assertNull(t.whatIfPlace());
            assertNull(t.playoffCross());
        }
    }

    // ----------------------------------------------------------------------- real source

    @Test
    void officialIsChosenWhenEveryRuleHolds() {
        SeasonMedian m = strict(2);
        assertEquals("OFFICIAL", m.realSource());
        assertEquals(0, m.driftGames());
        assertEquals(3, m.regularSeasonWeeks());
        assertEquals(new WinLossTie(3, 0, 0), byRoster(m).get(3).real());
    }

    @Test
    void aRosterMissingAGameFallsBackToCountedGamesWithoutThrowing() {
        List<Game> missing = new ArrayList<>(strictGames());
        missing.remove(new Game(3, 1, 70, 4, 80));            // r1 and r4 now have 2 games
        SeasonMedian m = wi(missing, strictOfficial(), Optional.of(format(2)), false);
        assertEquals("COUNTED_GAMES", m.realSource());
        assertEquals(2, byRoster(m).get(1).gamesCounted());
        assertEquals(3, byRoster(m).get(2).gamesCounted());
    }

    @Test
    void officialRecordsThatDoNotAddUpToTheRegularSeasonFallBackToCountedGames() {
        // r3's stored record includes a playoff game: 4 results over a 3-week regular season.
        List<StandingRow> official = List.of(row(1, 1, 2, 0, 320.0, null), row(2, 1, 2, 0, 305.0, null),
                row(3, 4, 0, 0, 250.0, null), row(4, 1, 2, 0, 200.0, null));
        SeasonMedian m = wi(strictGames(), official, Optional.of(format(2)), false);
        assertEquals("COUNTED_GAMES", m.realSource());
        assertEquals(new WinLossTie(3, 0, 0), byRoster(m).get(3).real());
    }

    @Test
    void aNullOfficialFieldFallsBackToCountedGames() {
        List<StandingRow> official = List.of(row(1, 1, 2, 0, 320.0, null), row(2, 1, 2, null, 305.0, null),
                row(3, 3, 0, 0, 250.0, null), row(4, 1, 2, 0, 200.0, null));
        SeasonMedian m = wi(strictGames(), official, Optional.of(format(2)), false);
        assertEquals("COUNTED_GAMES", m.realSource());
    }

    @Test
    void aRosterInOneSetAndNotTheOtherFallsBackToCountedGames() {
        List<StandingRow> official = new ArrayList<>(strictOfficial());
        official.add(row(99, 0, 3, 0, 10.0, null));
        SeasonMedian m = wi(strictGames(), official, Optional.of(format(2)), false);
        assertEquals("COUNTED_GAMES", m.realSource());
        assertEquals(4, m.teams().size());          // places are over the counted-games rosters only
        assertTrue(byRoster(m).values().stream().allMatch(t -> t.actualPlace() <= 4));
    }

    @Test
    void driftGamesCountsRostersWhoseRecountDiffersFromTheOfficialRecord() {
        // Sleeper has r1 2-1 and r2 0-3: two rosters differ from their re-counted 1-2 and 1-2.
        List<StandingRow> official = List.of(row(1, 2, 1, 0, 320.0, null), row(2, 0, 3, 0, 305.0, null),
                row(3, 3, 0, 0, 250.0, null), row(4, 1, 2, 0, 200.0, null));
        SeasonMedian m = wi(strictGames(), official, Optional.of(format(2)), false);
        assertEquals("OFFICIAL", m.realSource());
        assertEquals(2, m.driftGames());
        assertEquals(new WinLossTie(2, 1, 0), byRoster(m).get(1).real());     // the official figure is what is shown
    }

    // ----------------------------------------------------------------- other statuses

    @Test
    void noRegularSeasonBoundMeansMedianAndAllPlayOnlyAndNoWhatIf() {
        SeasonMedian m = wi(strictGames(), strictOfficial(),
                Optional.of(new PlayoffFormat(2, 0, 0, false, false, 0)), false);
        assertEquals("NO_REGULAR_SEASON_BOUND", m.status());
        assertNull(m.regularSeasonWeeks());
        assertNull(m.realSource());
        assertEquals(4, m.teams().size());
        for (SeasonMedianTeam t : m.teams()) {
            assertNotNull(t.median());
            assertNotNull(t.allPlay());
            assertNull(t.real());
            assertNull(t.withMedian());
            assertNull(t.winsGained());
            assertNull(t.actualPlace());
            assertNull(t.whatIfPlace());
            assertNull(t.playoffCross());
        }
    }

    @Test
    void aMissingFormatIsTreatedAsNoRegularSeasonBound() {
        SeasonMedian m = wi(strictGames(), strictOfficial(), Optional.empty(), false);
        assertEquals("NO_REGULAR_SEASON_BOUND", m.status());
    }

    @Test
    void noGamesIsNoFinalWeeks() {
        SeasonMedian m = wi(List.of(), strictOfficial(), Optional.of(format(2)), false);
        assertEquals("NO_FINAL_WEEKS", m.status());
        assertEquals(0, m.weeksCounted());
        assertTrue(m.teams().isEmpty());
    }

    @Test
    void noFinalWeeksTakesPrecedenceOverAlreadyOn() {
        SeasonMedian m = wi(List.of(), List.of(),
                Optional.of(new PlayoffFormat(2, 4, 0, false, true, 0)), false);
        assertEquals("NO_FINAL_WEEKS", m.status());
    }
}
