package com.ballknowers.draftsim.historical;

import com.ballknowers.draftsim.historical.HistoricalDataRepository.GameLine;
import com.ballknowers.draftsim.historical.HistoricalResult.FinalPlace;
import com.ballknowers.draftsim.historical.HistoricalResult.LineupWeek;
import com.ballknowers.draftsim.historical.HistoricalResult.Matchup;
import com.ballknowers.draftsim.historical.HistoricalResult.PlayoffMatchup;
import com.ballknowers.draftsim.historical.HistoricalResult.Round;
import com.ballknowers.draftsim.historical.HistoricalResult.Week;
import com.ballknowers.draftsim.historical.SeasonReplay.Pick;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.ballknowers.draftsim.historical.HistoricalTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 063 T006: the playoffs the replay adds. Twelve teams, one player each, whose weekly score is hand-set, so the
 * standings and every game of the bracket are known.
 *
 * <p>The regular season's pairings are a seeded shuffle, so which team finishes where is read from a first run (a
 * run does not depend on playoff-week scores). The second run then sets playoff-week scores BY SEED: by default the
 * better seed outscores the worse one, so the bracket plays out chalk unless a test overrides a game. Overrides are
 * keyed {@code "seed@week"}.
 */
class SeasonReplayPlayoffsTest {

    private static final int TEAMS = 12;
    private static final int REGULAR = 19;     // the configured regular season; playoffs are weeks 20, 21, 22
    private static final int SEASON = 25;      // weeks 23-25 are unused
    private static final long SEED = 31L;

    // ---- the scenario ----------------------------------------------------------------------------------

    /** A run: the result, and {@code slotOfSeed[seed]} (index 0 unused). */
    private record Run(HistoricalResult r, int[] slotOfSeed, int userSlot, int[] seedOfSlot) {}

    private static Run run(int seasonWeeks, int userSeed, Map<String, Integer> overridesBySeed) {
        Run first = play(seasonWeeks, 1, null, Map.of());
        int userSlot = first.slotOfSeed[userSeed];
        return play(seasonWeeks, userSlot, first.slotOfSeed, overridesBySeed);
    }

    private static int points(int slot, int week, int[] seedOfSlot, int regular, Map<String, Integer> overrides) {
        if (seedOfSlot != null && week > regular && week <= regular + 3) {
            int seed = seedOfSlot[slot];
            Integer o = overrides.get(seed + "@" + week);
            return o != null ? o : 200 - 10 * seed;       // playoff weeks: the better seed outscores the worse
        }
        return 200 - 10 * slot;                            // regular and unused weeks: by slot
    }

    private static Run play(int seasonWeeks, int userSlot, int[] slotOfSeed, Map<String, Integer> overrides) {
        int regular = SeasonFormat.of(seasonWeeks, REGULAR, TEAMS).regularSeasonWeeks();
        int[] seedOfSlot = null;
        if (slotOfSeed != null) {
            seedOfSlot = new int[TEAMS + 1];
            for (int seed = 1; seed <= TEAMS; seed++) seedOfSlot[slotOfSeed[seed]] = seed;
        }
        List<Pick> picks = new ArrayList<>();
        Map<Long, HistoricalPlayerCard> cards = new LinkedHashMap<>();
        List<GameLine> games = new ArrayList<>();
        for (int s = 1; s <= TEAMS; s++) {
            picks.add(new Pick(s, s, s));
            cards.put((long) s, card(s, "PG"));
            for (int w = 1; w <= seasonWeeks; w++) {
                games.add(game(s, s * 1000L + w, w, 0, points(s, w, seedOfSlot, regular, overrides), null));
            }
        }
        Map<Integer, LocalDate> starts = new LinkedHashMap<>();
        for (int w = 1; w <= seasonWeeks; w++) starts.put(w, DAY.plusDays(7L * (w - 1)));
        Map<String, Double> scoring = new LinkedHashMap<>();
        scoring.put("pts", 1.0);
        HistoricalResult r = REPLAY.replay(new SeasonReplay.Input(settings(TEAMS), rules(), scoring, seasonWeeks,
                REGULAR, SEED, userSlot, picks, cards, games, starts));
        int[] bySeed = new int[TEAMS + 1];
        int[] seedBySlot = new int[TEAMS + 1];
        for (var st : r.standings()) {
            bySeed[st.place()] = st.slot();
            seedBySlot[st.slot()] = st.place();
        }
        return new Run(r, bySeed, userSlot, seedBySlot);
    }

    private static Run chalk() {
        return run(SEASON, 1, Map.of());
    }

    private static PlayoffMatchup playoffGame(Run run, int round, int index) {
        return run.r.playoffs().rounds().get(round - 1).matchups().get(index);
    }

    // ---- bracket structure -----------------------------------------------------------------------------

    @Test
    void seedsAreStandingsPlacesOneToSix() {
        Run run = chalk();
        var seeds = run.r.playoffs().seeds();
        assertEquals(6, seeds.size());
        for (int i = 0; i < 6; i++) {
            assertEquals(i + 1, seeds.get(i).seed());
            assertEquals(run.r.standings().get(i).slot(), seeds.get(i).slot());
        }
    }

    @Test
    void roundOneIsThreeVsSixAndFourVsFiveWithByesForTheTopTwo() {
        Run run = chalk();
        Round one = run.r.playoffs().rounds().get(0);
        assertEquals("ROUND_ONE", one.name());
        assertEquals(1, one.round());
        assertEquals(REGULAR + 1, one.week());
        assertEquals(List.of(run.slotOfSeed[1], run.slotOfSeed[2]), one.byeSlots());
        assertEquals(2, one.matchups().size());
        PlayoffMatchup a = one.matchups().get(0), b = one.matchups().get(1);
        assertEquals(3, a.highSeed());
        assertEquals(6, a.lowSeed());
        assertEquals(run.slotOfSeed[3], a.highSlot());
        assertEquals(run.slotOfSeed[6], a.lowSlot());
        assertEquals(4, b.highSeed());
        assertEquals(5, b.lowSeed());
    }

    @Test
    void whenTheHighSeedsWinRoundOneSeedOnePlaysFourAndSeedTwoPlaysThree() {
        Run run = chalk();
        PlayoffMatchup x = playoffGame(run, 2, 0), y = playoffGame(run, 2, 1);
        assertEquals(1, x.highSeed());
        assertEquals(4, x.lowSeed(), "seed 1 plays the highest-numbered survivor");
        assertEquals(2, y.highSeed());
        assertEquals(3, y.lowSeed());
        assertEquals("SEMIFINAL", run.r.playoffs().rounds().get(1).name());
        assertEquals(List.of(), run.r.playoffs().rounds().get(1).byeSlots());
    }

    @Test
    void reseedingSixBeatingThreeSendsSeedOneToPlaySix() {
        Run run = run(SEASON, 1, Map.of("6@" + (REGULAR + 1), 500));
        assertEquals(run.slotOfSeed[6], playoffGame(run, 1, 0).winnerSlot(), "the 6 seed upset the 3 seed");
        PlayoffMatchup x = playoffGame(run, 2, 0), y = playoffGame(run, 2, 1);
        assertEquals(1, x.highSeed());
        assertEquals(6, x.lowSeed(), "seed 1 plays the highest-numbered survivor, now 6");
        assertEquals(2, y.highSeed());
        assertEquals(4, y.lowSeed(), "seed 2 plays the other survivor, the 4-5 winner");
    }

    @Test
    void whenSixAndFiveBothWinSeedOnePlaysSixAndSeedTwoPlaysFive() {
        Run run = run(SEASON, 1, Map.of("6@" + (REGULAR + 1), 500, "5@" + (REGULAR + 1), 500));
        assertEquals(6, playoffGame(run, 2, 0).lowSeed());
        assertEquals(5, playoffGame(run, 2, 1).lowSeed());
    }

    @Test
    void aLevelPlayoffScoreSendsTheBetterSeedThrough() {
        Run run = run(SEASON, 1, Map.of("3@" + (REGULAR + 1), 150, "6@" + (REGULAR + 1), 150));
        PlayoffMatchup g = playoffGame(run, 1, 0);
        assertEquals(g.highPoints(), g.lowPoints());
        assertTrue(g.decidedBySeed());
        assertEquals(run.slotOfSeed[3], g.winnerSlot(), "the better seed (3) advances on a tie");
        assertFalse(playoffGame(run, 1, 1).decidedBySeed(), "the other game was won on points");
    }

    @Test
    void aLevelFinalGoesToTheBetterSeed() {
        Run run = run(SEASON, 1, Map.of("1@" + (REGULAR + 3), 170, "2@" + (REGULAR + 3), 170));
        PlayoffMatchup f = playoffGame(run, 3, 0);
        assertTrue(f.decidedBySeed());
        assertEquals(run.slotOfSeed[1], run.r.playoffs().championSlot());
    }

    // ---- final places and the user's outcome -----------------------------------------------------------

    @Test
    void finalPlacesFollowTheBracketThenTheStandings() {
        // 6 beats 3, 5 beats 4 (round one); 5 beats 2 (semifinal); 1 beats 6 (semifinal); 1 beats 5 (final).
        Run run = run(SEASON, 1, Map.of("6@20", 500, "5@20", 500, "5@21", 500));
        int[] expectedSeedAtPlace = {0, 1, 5, 2, 6, 3, 4, 7, 8, 9, 10, 11, 12};
        List<FinalPlace> fp = run.r.finalPlaces();
        assertEquals(TEAMS, fp.size());
        for (int place = 1; place <= TEAMS; place++) {
            assertEquals(place, fp.get(place - 1).place());
            assertEquals(run.slotOfSeed[expectedSeedAtPlace[place]], fp.get(place - 1).slot(),
                    "place " + place + " should be seed " + expectedSeedAtPlace[place]);
        }
        assertEquals(HistoricalResult.CHAMPION, fp.get(0).outcome());
        assertEquals(HistoricalResult.LOST_FINAL, fp.get(1).outcome());
        assertEquals(HistoricalResult.LOST_SEMIFINAL, fp.get(2).outcome());
        assertEquals(HistoricalResult.LOST_SEMIFINAL, fp.get(3).outcome(), "semifinal losers by seed: 2 then 6");
        assertEquals(HistoricalResult.LOST_ROUND_ONE, fp.get(4).outcome());
        assertEquals(HistoricalResult.LOST_ROUND_ONE, fp.get(5).outcome());
        assertEquals(HistoricalResult.MISSED_PLAYOFFS, fp.get(6).outcome());
        assertEquals(run.slotOfSeed[1], run.r.playoffs().championSlot());
    }

    @Test
    void userFinishAndOutcomeMatchForEachOfTheFiveOutcomes() {
        Object[][] cases = {
                {1, HistoricalResult.CHAMPION, 1},
                {2, HistoricalResult.LOST_FINAL, 2},
                {3, HistoricalResult.LOST_SEMIFINAL, 3},
                {5, HistoricalResult.LOST_ROUND_ONE, 5},
                {9, HistoricalResult.MISSED_PLAYOFFS, 9}};
        for (Object[] c : cases) {
            Run run = run(SEASON, (int) c[0], Map.of());
            assertEquals(c[1], run.r.userOutcome(), "seed " + c[0]);
            assertEquals((int) c[2], run.r.userFinish(), "seed " + c[0]);
            int place = run.r.finalPlaces().stream().filter(f -> f.slot() == run.userSlot).findFirst().orElseThrow()
                    .place();
            assertEquals(run.r.userFinish(), place, "userFinish is the user's final place");
        }
    }

    // ---- what counts as regular season -----------------------------------------------------------------

    @Test
    void standingsCountTheRegularSeasonOnlyAndIgnorePlayoffScores() {
        Run chalk = chalk();
        Run wild = run(SEASON, 1, Map.of("1@20", 1, "1@21", 1, "1@22", 1, "9@20", 900, "12@21", 900));
        assertEquals(chalk.r.standings(), wild.r.standings(), "playoff-week scores cannot move the standings");
        for (var st : chalk.r.standings()) {
            assertEquals(REGULAR, st.wins() + st.losses() + st.ties(), "every team played " + REGULAR + " games");
            assertEquals(REGULAR * (200.0 - 10 * st.slot()), st.pointsFor(), 1e-9,
                    "points for is the 19 regular weeks, not 22 or 25");
        }
    }

    @Test
    void weeksHoldTheRegularSeasonThenThreePlayoffWeeksAndNothingAfter() {
        Run run = chalk();
        List<Week> weeks = run.r.weeks();
        assertEquals(REGULAR + 3, weeks.size());
        for (int i = 0; i < weeks.size(); i++) {
            assertEquals(i + 1, weeks.get(i).week());
            assertEquals(i < REGULAR ? "REGULAR" : "PLAYOFF", weeks.get(i).phase());
        }
        assertEquals(2, weeks.get(REGULAR).matchups().size());
        assertEquals(2, weeks.get(REGULAR + 1).matchups().size());
        assertEquals(1, weeks.get(REGULAR + 2).matchups().size());
        assertEquals(TEAMS / 2, weeks.get(0).matchups().size());
    }

    @Test
    void playoffMatchupScoresAreTheTeamsBestBallWeekScoresAndAgreeWithTheWeeksList() {
        Map<String, Integer> overrides = Map.of("6@20", 500);
        Run run = run(SEASON, 1, overrides);
        for (Round round : run.r.playoffs().rounds()) {
            Week w = run.r.weeks().get(round.week() - 1);
            assertEquals("PLAYOFF", w.phase());
            assertEquals(round.matchups().size(), w.matchups().size());
            for (int i = 0; i < round.matchups().size(); i++) {
                PlayoffMatchup p = round.matchups().get(i);
                Matchup m = w.matchups().get(i);
                assertEquals(p.highSlot(), m.homeSlot(), "home is the better seed");
                assertEquals(p.lowSlot(), m.awaySlot());
                assertEquals(p.highPoints(), m.homePoints());
                assertEquals(p.lowPoints(), m.awayPoints());
                assertEquals(points(p.highSlot(), round.week(), run.seedOfSlot, REGULAR, overrides),
                        p.highPoints(), "the score is that team's own week score");
                assertEquals(points(p.lowSlot(), round.week(), run.seedOfSlot, REGULAR, overrides), p.lowPoints());
                boolean highWon = p.winnerSlot() == p.highSlot();
                assertTrue(highWon || p.winnerSlot() == p.lowSlot());
                if (!p.decidedBySeed()) assertEquals(highWon, p.highPoints() > p.lowPoints());
            }
        }
    }

    @Test
    void userLineupsCoverRegularWeeksAndOnlyThePlayoffWeeksTheUserPlayed() {
        List<Integer> regular = new ArrayList<>();
        for (int w = 1; w <= REGULAR; w++) regular.add(w);
        List<Integer> byeThenPlayed = new ArrayList<>(regular);
        byeThenPlayed.addAll(List.of(21, 22));
        List<Integer> lostRoundOne = new ArrayList<>(regular);
        lostRoundOne.add(20);
        assertEquals(byeThenPlayed, lineupWeeks(run(SEASON, 1, Map.of())),
                "seed 1 had a bye in round one and played the rest");
        assertEquals(lostRoundOne, lineupWeeks(run(SEASON, 5, Map.of())), "seed 5 lost in round one");
        assertEquals(regular, lineupWeeks(run(SEASON, 9, Map.of())), "seed 9 missed the playoffs");
    }

    private static List<Integer> lineupWeeks(Run run) {
        return run.r.lineups().get(String.valueOf(run.userSlot)).stream().map(LineupWeek::week).toList();
    }

    @Test
    void theHindsightComparisonStillCoversTheWholeSeasonNotTheRegularSeason() {
        // PR-13: "You" in the hindsight panel is the whole real season (25 weeks here), not the 19 weeks of
        // standings points. Passing the regular-season points for would silently shrink it.
        Run run = run(SEASON, 9, Map.of());
        double whole = 0, regularOnly = 0;
        for (int w = 1; w <= SEASON; w++) {
            double p = points(run.userSlot, w, run.seedOfSlot, REGULAR, Map.of());
            whole += p;
            if (w <= REGULAR) regularOnly += p;
        }
        var standing = run.r.standings().stream().filter(s -> s.slot() == run.userSlot).findFirst().orElseThrow();
        assertEquals(regularOnly, standing.pointsFor(), 1e-9);
        assertNotEquals(whole, regularOnly);
        assertEquals(whole, run.r.hindsight().userPointsFor(), 1e-9, "the whole season, weeks 1-" + SEASON);
    }

    // ---- determinism and the other arms ----------------------------------------------------------------

    @Test
    void twoRunsOfTheSameInputSerialiseToIdenticalBytes() throws Exception {
        ObjectMapper om = new ObjectMapper();
        assertEquals(om.writeValueAsString(run(SEASON, 3, Map.of("6@20", 500)).r),
                om.writeValueAsString(run(SEASON, 3, Map.of("6@20", 500)).r));
    }

    @Test
    void theRegularSeasonPairingsDoNotDependOnHowLongTheSeasonIs() {
        // No new random numbers are drawn: weeks 1..19 pair identically in a 22- and a 25-week season.
        Run a = run(22, 1, Map.of()), b = run(25, 1, Map.of());
        for (int w = 0; w < REGULAR; w++) {
            List<Matchup> ma = a.r.weeks().get(w).matchups(), mb = b.r.weeks().get(w).matchups();
            for (int i = 0; i < ma.size(); i++) {
                assertEquals(ma.get(i).homeSlot(), mb.get(i).homeSlot());
                assertEquals(ma.get(i).awaySlot(), mb.get(i).awaySlot());
            }
        }
    }

    @Test
    void aThirteenWeekSeasonHasNoPlayoffsAndTheFinishIsTheStandingsRank() {
        Run run = run(13, 4, Map.of());
        HistoricalResult r = run.r;
        assertEquals(2, r.format().version());
        assertEquals(List.of(), r.format().playoffWeeks());
        assertNull(r.playoffs());
        assertEquals(13, r.weeks().size());
        assertEquals(HistoricalResult.NO_PLAYOFFS, r.userOutcome());
        assertEquals(4, r.userFinish(), "the standings rank");
        assertEquals(TEAMS, r.finalPlaces().size());
        for (int i = 0; i < TEAMS; i++) {
            assertEquals(r.standings().get(i).slot(), r.finalPlaces().get(i).slot());
            assertEquals(i + 1, r.finalPlaces().get(i).place());
            assertEquals(HistoricalResult.NO_PLAYOFFS, r.finalPlaces().get(i).outcome());
        }
        for (Week w : r.weeks()) assertEquals("REGULAR", w.phase());
    }

    @Test
    void aShortenedSeasonPlaysItsLastThreeWeeksAsPlayoffs() {
        HistoricalResult r = run(21, 1, Map.of()).r;
        assertTrue(r.format().shortened());
        assertEquals(18, r.format().regularSeasonWeeks());
        assertEquals(List.of(19, 20, 21), r.format().playoffWeeks());
        assertEquals("REGULAR", r.weeks().get(17).phase());
        assertEquals("PLAYOFF", r.weeks().get(18).phase());
        assertEquals(21, r.weeks().size());
        for (var st : r.standings()) assertEquals(18, st.wins() + st.losses() + st.ties());
    }
}
