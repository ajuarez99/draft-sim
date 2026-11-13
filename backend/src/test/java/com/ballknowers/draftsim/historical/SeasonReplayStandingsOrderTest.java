package com.ballknowers.draftsim.historical;

import com.ballknowers.draftsim.historical.HistoricalDataRepository.GameLine;
import com.ballknowers.draftsim.historical.HistoricalResult.FinalPlace;
import com.ballknowers.draftsim.historical.HistoricalResult.Matchup;
import com.ballknowers.draftsim.historical.HistoricalResult.Seed;
import com.ballknowers.draftsim.historical.HistoricalResult.Standing;
import com.ballknowers.draftsim.historical.SeasonReplay.Pick;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.ballknowers.draftsim.historical.HistoricalTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 063 review CR-02: the standings order decides the seeds, the two byes and who misses the playoffs, so it is
 * pinned here with expectations worked out by hand, not read back from a run.
 *
 * <p>Two scenarios. (1) Twelve teams, an 11-week regular season (a 14-week season is cut to 11 + 3 playoff weeks):
 * a single round robin, so every pair meets exactly once whatever the seeded shuffle does, and with slot s scoring
 * {@code 200 - 10 s} every week, slot s beats exactly the slots numbered above it: 12 - s wins, s - 1 losses, in slot
 * order. (2) Four teams, three weeks: again one meeting per pair, but each pair's result is a hand-chosen table,
 * placed in the week the schedule has them meet (read from the result, not assumed), so a tie and a points-for
 * tie-break can be tested without depending on the shuffle.
 */
class SeasonReplayStandingsOrderTest {

    private static final long SEED = 31L;

    private static Map<Integer, LocalDate> starts(int weeks) {
        Map<Integer, LocalDate> m = new LinkedHashMap<>();
        for (int w = 1; w <= weeks; w++) m.put(w, DAY.plusDays(7L * (w - 1)));
        return m;
    }

    // ---- twelve teams, one round robin, then the bracket --------------------------------------------------

    private static HistoricalResult twelveTeams() {
        int teams = 12, weeks = 14;
        List<Pick> picks = new ArrayList<>();
        Map<Long, HistoricalPlayerCard> cards = new LinkedHashMap<>();
        List<GameLine> games = new ArrayList<>();
        for (int s = 1; s <= teams; s++) {
            picks.add(new Pick(s, s, s));
            cards.put((long) s, card(s, "PG"));
            for (int w = 1; w <= weeks; w++) games.add(game(s, s * 1000L + w, w, 0, 200 - 10 * s, null));
        }
        return REPLAY.replay(new SeasonReplay.Input(settings(teams), rules(), Map.of("pts", 1.0), weeks, 19, SEED, 1,
                picks, cards, games, starts(weeks)));
    }

    @Test
    void moreWinsRanksHigherAndTheBestRecordIsFirst() {
        HistoricalResult r = twelveTeams();
        assertEquals(11, r.format().regularSeasonWeeks());
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12), r.standings().stream().map(Standing::slot).toList());
        for (Standing st : r.standings()) {
            assertEquals(12 - st.slot(), st.wins(), "wins of slot " + st.slot());
            assertEquals(st.slot() - 1, st.losses(), "losses of slot " + st.slot());
            assertEquals(0, st.ties());
            assertEquals(st.slot(), st.place());
        }
    }

    @Test
    void theSixSeedsAreSlotsOneToSixInThatOrderAndTheRestMissOut() {
        HistoricalResult r = twelveTeams();
        var seeds = r.playoffs().seeds();
        assertEquals(List.of(1, 2, 3, 4, 5, 6), seeds.stream().map(Seed::slot).toList());
        assertEquals(List.of(1, 2, 3, 4, 5, 6), seeds.stream().map(Seed::seed).toList());
        // seeds 1 and 2 have the byes; round one is 3 v 6 and 4 v 5
        var one = r.playoffs().rounds().get(0);
        assertEquals(List.of(1, 2), one.byeSlots());
        assertEquals(3, one.matchups().get(0).highSlot());
        assertEquals(6, one.matchups().get(0).lowSlot());
        assertEquals(4, one.matchups().get(1).highSlot());
        assertEquals(5, one.matchups().get(1).lowSlot());
        // Chalk all the way: slot 1 is champion. Final places: 1 and 2 from the final, then the semifinal losers (3
        // then 4, by seed), the round-one losers (5 then 6, by seed), then the six who missed, in standings order.
        assertEquals(1, r.playoffs().championSlot());
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12), r.finalPlaces().stream().map(FinalPlace::slot).toList());
    }

    // ---- four teams, three weeks: ties and points for ---------------------------------------------------------

    /** Slots: A = 3, B = 1, C = 2, D = 4, so the right order (A, D, B, C = 3, 4, 1, 2) is not slot order. */
    private static final int A = 3, B = 1, C = 2, D = 4;

    /**
     * The hand-chosen result of each pair, as {first team's score, second team's score}: A beats B 100-90, A beats C
     * 100-50, A loses to D 10-200, B ties D 100-100, B beats C 80-20, C beats D 60-40.
     * Records: A 2-1-0 (2.0 wins), B 1-1-1 (1.5), C 1-2-0 (1.0), D 1-1-1 (1.5).
     * Points for: A 210, B 270, C 130, D 340.
     */
    private static final Map<String, int[]> PAIR = Map.of(
            key(A, B), new int[]{100, 90},
            key(A, C), new int[]{100, 50},
            key(A, D), new int[]{10, 200},
            key(B, D), new int[]{100, 100},
            key(B, C), new int[]{80, 20},
            key(C, D), new int[]{60, 40});

    private static String key(int x, int y) {
        return x + "-" + y;
    }

    /** The designed scores of {@code home} and {@code away}, whichever way round the schedule has them. */
    private static int[] designed(int home, int away) {
        int[] direct = PAIR.get(key(home, away));
        if (direct != null) return direct;
        int[] flipped = PAIR.get(key(away, home));
        assertNotNull(flipped, "no designed result for " + home + " v " + away);
        return new int[]{flipped[1], flipped[0]};
    }

    private static HistoricalResult fourTeams() {
        int teams = 4, weeks = 3;
        List<Pick> picks = new ArrayList<>();
        Map<Long, HistoricalPlayerCard> cards = new LinkedHashMap<>();
        for (int s = 1; s <= teams; s++) {
            picks.add(new Pick(s, s, s));
            cards.put((long) s, card(s, "PG"));
        }
        // First pass: who meets whom in which week. The schedule does not depend on the scores.
        List<GameLine> blank = new ArrayList<>();
        for (int s = 1; s <= teams; s++) for (int w = 1; w <= weeks; w++) blank.add(game(s, s * 1000L + w, w, 0, 0, null));
        HistoricalResult probe = REPLAY.replay(new SeasonReplay.Input(settings(teams), rules(), Map.of("pts", 1.0), weeks, 19, SEED, 1,
                picks, cards, blank, starts(weeks)));
        List<GameLine> games = new ArrayList<>();
        for (var week : probe.weeks()) {
            assertEquals(2, week.matchups().size());
            for (Matchup m : week.matchups()) {
                int[] pts = designed(m.homeSlot(), m.awaySlot());
                games.add(game(m.homeSlot(), m.homeSlot() * 1000L + week.week(), week.week(), 0, pts[0], null));
                games.add(game(m.awaySlot(), m.awaySlot() * 1000L + week.week(), week.week(), 0, pts[1], null));
            }
        }
        assertEquals(teams * weeks, games.size(), "every team has a score every week");
        return REPLAY.replay(new SeasonReplay.Input(settings(teams), rules(), Map.of("pts", 1.0), weeks, 19, SEED, 1,
                picks, cards, games, starts(weeks)));
    }

    private static Standing of(HistoricalResult r, int slot) {
        return r.standings().stream().filter(s -> s.slot() == slot).findFirst().orElseThrow();
    }

    private static String rec(Standing s) {
        return s.wins() + "-" + s.losses() + "-" + s.ties();
    }

    @Test
    void theDesignedRecordsAndPointsHold() {
        HistoricalResult r = fourTeams();
        assertNull(r.playoffs(), "four teams: no playoffs");
        assertEquals("2-1-0", rec(of(r, A)));
        assertEquals("1-1-1", rec(of(r, B)));
        assertEquals("1-2-0", rec(of(r, C)));
        assertEquals("1-1-1", rec(of(r, D)));
        assertEquals(210.0, of(r, A).pointsFor());
        assertEquals(270.0, of(r, B).pointsFor());
        assertEquals(130.0, of(r, C).pointsFor());
        assertEquals(340.0, of(r, D).pointsFor());
    }

    @Test
    void moreWinsOutranksMorePointsAndEqualRecordsAreSplitByPointsFor() {
        // A has the most wins and the FEWEST points of the three above C (210 against 270 and 340): wins come first.
        // B and D are level at one win and one tie; D has more points for (340 v 270), so D ranks above B.
        HistoricalResult r = fourTeams();
        assertEquals(List.of(A, D, B, C), r.standings().stream().map(Standing::slot).toList());
        assertEquals(List.of(1, 2, 3, 4), r.standings().stream().map(Standing::place).toList());
    }

    @Test
    void aTieIsWorthExactlyHalfAWin() {
        HistoricalResult r = fourTeams();
        // 1 win + 1 tie (1.5) is above 1-2-0 (1.0) and below 2-1-0 (2.0).
        assertTrue(of(r, D).place() > of(r, A).place(), "below the 2-1-0 team");
        assertTrue(of(r, B).place() > of(r, A).place(), "below the 2-1-0 team");
        assertTrue(of(r, D).place() < of(r, C).place(), "above the 1-2-0 team");
        assertTrue(of(r, B).place() < of(r, C).place(), "above the 1-2-0 team");
        // A tie counted as a whole win would make B and D 2.0, level with A, and their larger points for (270 and 340
        // against 210) would then put both above A. They must not be.
        assertEquals(1, of(r, A).place());
    }
}
