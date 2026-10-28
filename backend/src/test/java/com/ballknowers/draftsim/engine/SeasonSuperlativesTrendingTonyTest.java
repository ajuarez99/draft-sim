package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.domain.Player;
import com.ballknowers.draftsim.domain.Position;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.engine.SeasonSuperlativesService.Kind;
import com.ballknowers.draftsim.engine.SeasonSuperlativesService.PlayerContribution;
import com.ballknowers.draftsim.engine.SeasonSuperlativesService.Standing;
import com.ballknowers.draftsim.engine.SeasonSuperlativesService.Superlative;
import com.ballknowers.draftsim.engine.SeasonSuperlativesService.TrendingPickupDetail;
import com.ballknowers.draftsim.engine.TrendingPickups.Judged;
import com.ballknowers.draftsim.engine.TrendingPickups.Verdict;
import com.ballknowers.draftsim.engine.WaiverPickupAttribution.CompletedAdd;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TRENDING_TONY's result states (specs/051-trending-tony-award T015/T020/T025), driven through the pure
 * shaping half with hand-built {@link Judged} rows -- no Postgres, as with the Jabari tests.
 */
class SeasonSuperlativesTrendingTonyTest {

    private static final OffsetDateTime SNAP = OffsetDateTime.of(2026, 10, 28, 11, 0, 4, 0, ZoneOffset.UTC);
    private static final Set<Integer> ROSTERS = Set.of(1, 2, 3);

    private static Judged trending(int roster, String player, int week, int rank, String type) {
        return new Judged(new CompletedAdd(week, SNAP.toInstant(), type, player, roster, null), Verdict.TRENDING,
                rank, 5000 - rank, SNAP);
    }

    private static Judged notTrending(int roster, String player, int week) {
        return new Judged(new CompletedAdd(week, SNAP.toInstant(), "WAIVER", player, roster, null),
                Verdict.NOT_TRENDING, null, null, SNAP);
    }

    private static Judged notJudged(int roster, String player, int week) {
        return new Judged(new CompletedAdd(week, null, "WAIVER", player, roster, null), Verdict.NOT_JUDGED,
                null, null, null);
    }

    private static Superlative shape(List<Judged> judged, Optional<OffsetDateTime> earliest, Instant lastPickup) {
        return shape(judged, earliest, lastPickup, true);
    }

    private static Superlative shape(List<Judged> judged, Optional<OffsetDateTime> earliest, Instant lastPickup,
                                     boolean complete) {
        Map<Integer, String> names = new HashMap<>();
        for (int id : ROSTERS) names.put(id, "Team " + id);
        return SeasonSuperlativesService.trendingTonyFromJudged(judged, earliest, lastPickup, complete, Map.of(), ROSTERS,
                names, Map.of(), Map.of());
    }

    private static Superlative shape(List<Judged> judged) {
        return shape(judged, Optional.of(SNAP), SNAP.toInstant());
    }

    @Test
    void aWinnerWithFourBeatsAManagerWithTwo() {
        Superlative s = shape(List.of(
                trending(1, "a", 1, 3, "WAIVER"), trending(1, "b", 2, 5, "WAIVER"), trending(1, "c", 3, 1, "FREE_AGENT"),
                trending(1, "d", 4, 9, "WAIVER"), trending(2, "e", 1, 2, "WAIVER"), trending(2, "f", 2, 4, "WAIVER"),
                notTrending(2, "g", 3)));

        assertTrue(s.available());
        assertEquals(Kind.TRENDING_TONY, s.kind());
        assertEquals("PICKUPS", s.unit());
        assertEquals(4.0, s.value());
        assertEquals(List.of(1), s.holders().stream().map(h -> h.rosterId()).toList());
        assertEquals(3, s.detail().size(), "detail lists the holder's 3 best trending pickups only");
        assertNull(s.emptyReason());
        var d = (TrendingPickupDetail) s.detail().get(0);
        assertEquals("c", d.playerId(), "best (lowest) rank first");
        assertEquals("Unknown player", d.playerName());
        assertNull(d.position());
        assertEquals(SNAP, d.snapshotAt());
    }

    @Test
    void aTwoWayTieNamesBothManagers() {
        Superlative s = shape(List.of(
                trending(1, "a", 1, 3, "WAIVER"), trending(1, "b", 2, 5, "WAIVER"),
                trending(2, "c", 1, 2, "WAIVER"), trending(2, "d", 2, 4, "WAIVER"), notTrending(3, "e", 1)));

        assertEquals(List.of(1, 2), s.holders().stream().map(h -> h.rosterId()).toList());
        assertEquals(4, s.detail().size(), "two holders, two rows each");
    }

    @Test
    void holderDetailIsCappedAtTheThreeBestByRankThenWeekButContributionsKeepEverything() {
        Superlative s = shape(List.of(
                trending(1, "a", 5, 4, "WAIVER"), trending(1, "b", 2, 4, "WAIVER"), trending(1, "c", 3, 1, "WAIVER"),
                trending(1, "d", 1, 9, "WAIVER"), trending(1, "e", 4, 2, "WAIVER")));

        assertEquals(List.of("c", "e", "b"), s.detail().stream().map(r -> ((TrendingPickupDetail) r).playerId()).toList());
        assertEquals(5, s.playerContributions().size());
    }

    @Test
    void judgingSinceIsTheEarliestJudgedPickupNotTheSnapshotTime() {
        Instant early = Instant.parse("2026-10-27T23:00:00Z");
        Instant late = Instant.parse("2026-10-29T09:00:00Z");
        Judged a = new Judged(new CompletedAdd(1, late, "WAIVER", "a", 1, null), Verdict.TRENDING, 3, 100, SNAP);
        Judged b = new Judged(new CompletedAdd(1, early, "WAIVER", "b", 2, null), Verdict.NOT_TRENDING, null, null, SNAP);
        Judged c = new Judged(new CompletedAdd(1, Instant.parse("2026-10-01T00:00:00Z"), "WAIVER", "c", 3, null),
                Verdict.NOT_JUDGED, null, null, null);

        Superlative s = shape(List.of(a, b, c));

        assertEquals(early.atOffset(ZoneOffset.UTC), s.trendingCoverage().judgingSinceAt());
    }

    @Test
    void theStandingsNoteIsSingularForOneJudgedPickup() {
        Superlative s = shape(List.of(notTrending(1, "a", 1), trending(2, "b", 1, 3, "WAIVER"),
                trending(3, "c", 2, 4, "WAIVER"), trending(3, "d", 2, 6, "WAIVER"), notTrending(3, "e", 3)));

        assertEquals("0 of 1 judged pickup", note(s, 1));
        assertEquals("1 of 1 judged pickup", note(s, 2));
        assertEquals("2 of 3 judged pickups", note(s, 3));
    }

    private static String note(Superlative s, int rosterId) {
        return s.standings().stream().filter(x -> x.team().rosterId() == rosterId).findFirst().orElseThrow().note();
    }

    @Test
    void aTopCountOfOneNamesNobodyButKeepsCoverageAndStandings() {
        Superlative s = shape(List.of(trending(1, "a", 1, 3, "WAIVER"), trending(2, "b", 1, 4, "WAIVER"),
                notTrending(3, "c", 1)));

        assertTrue(s.available());
        assertEquals("nobody has chased the trending list twice yet", s.emptyReason());
        assertTrue(s.holders().isEmpty());
        assertNull(s.value());
        assertNotNull(s.trendingCoverage());
        assertEquals(3, s.standings().size());
        assertEquals(2, s.playerContributions().size());
    }

    @Test
    void aPickupBelowTheCutDoesNotCount() {
        // A rank-22 player reaches shaping only as NOT_TRENDING (the judge enforces TOP_N); two of those name nobody.
        Superlative s = shape(List.of(notTrending(1, "a", 1), notTrending(1, "b", 2), trending(2, "c", 1, 1, "WAIVER")));

        assertEquals("nobody has chased the trending list twice yet", s.emptyReason());
        assertTrue(s.playerContributions().stream().noneMatch(c -> c.value() > TrendingPickups.TOP_N));
    }

    @Test
    void standingsListEveryRosterWithTheShareNoteAndAZeroRow() {
        Superlative s = shape(List.of(
                trending(1, "a", 1, 3, "WAIVER"), trending(1, "b", 2, 5, "WAIVER"), notTrending(1, "c", 3),
                trending(2, "d", 1, 2, "WAIVER"), notJudged(3, "e", 1)));

        assertEquals(3, s.standings().size());
        Standing first = s.standings().get(0);
        assertEquals(1, first.team().rosterId());
        assertEquals(1, first.rank());
        assertEquals(2.0, first.value());
        assertEquals("2 of 3 judged pickups", first.note());
        Standing second = s.standings().get(1);
        assertEquals(2, second.rank());
        assertEquals("1 of 1 judged pickup", second.note());
        Standing last = s.standings().get(2);
        assertEquals(3, last.team().rosterId());
        assertEquals(0.0, last.value(), "a roster with nothing judged is a measured 0, not a missing row");
        assertTrue(last.hasValue());
        assertEquals("no judged pickups", last.note());
    }

    @Test
    void contributionsAreOrderedByRankThenWeekThenRosterWithTheNoteAndCompetitionRanks() {
        Superlative s = shape(List.of(
                trending(2, "x", 4, 2, "FREE_AGENT"), trending(1, "y", 3, 2, "WAIVER"),
                trending(1, "z", 1, 1, "WAIVER"), trending(3, "w", 2, 7, "WAIVER")));

        List<PlayerContribution> c = s.playerContributions();
        assertEquals(List.of("z", "y", "x", "w"), c.stream().map(PlayerContribution::playerId).toList());
        assertEquals(List.of(1, 2, 2, 4), c.stream().map(PlayerContribution::rank).toList());
        assertEquals(List.of(1.0, 2.0, 2.0, 7.0), c.stream().map(PlayerContribution::value).toList());
        assertEquals("week 1 · waiver", c.get(0).note());
        assertEquals("week 4 · free agent", c.get(2).note());
    }

    @Test
    void anAvailableCardAlwaysHasCoverageThatAddsUp() {
        Superlative s = shape(List.of(trending(1, "a", 1, 3, "WAIVER"), notTrending(1, "b", 2), notJudged(2, "c", 1),
                notJudged(2, "d", 2)));

        var cov = s.trendingCoverage();
        assertNotNull(cov);
        assertEquals(SNAP, cov.judgingSinceAt());
        assertEquals(2, cov.judged());
        assertEquals(2, cov.notJudged());
        assertEquals(4, cov.totalPickups());
        assertEquals(cov.totalPickups(), cov.judged() + cov.notJudged());
    }

    private static final String NOT_RECORDED = "Trending history wasn't being recorded yet for this season.";

    private static String sinceReason(String date) {
        return "Nothing could be judged yet: trending history began on " + date
                + " (UTC), and no pickup since has a list within a day of it.";
    }

    @Test
    void nothingJudgedIsNotRecordedWhenNoSnapshotExistsAtAll() {
        for (boolean complete : new boolean[] {true, false}) {
            Superlative s = shape(List.of(notJudged(1, "a", 1)), Optional.empty(), Instant.parse("2026-09-20T12:00:00Z"),
                    complete);
            assertFalse(s.available());
            assertEquals(NOT_RECORDED, s.reason());
            assertNull(s.trendingCoverage());
            assertTrue(s.holders().isEmpty());
        }
    }

    @Test
    void aCompleteSeasonWithHistoryThatBeganAfterEveryPickupIsNotRecorded() {
        Instant lastPickup = Instant.parse("2026-09-20T12:00:00Z");
        List<Judged> j = List.of(notJudged(1, "a", 1), notJudged(2, "b", 2));

        assertEquals(NOT_RECORDED, shape(j, Optional.of(OffsetDateTime.parse("2026-09-21T12:00:01Z")), lastPickup, true).reason());
        // exactly last + 24h is not "after" it
        assertEquals(sinceReason("2026-09-21"),
                shape(j, Optional.of(OffsetDateTime.parse("2026-09-21T12:00:00Z")), lastPickup, true).reason());
    }

    @Test
    void aLiveSeasonWithHistoryThatBeganAfterEveryPickupGetsTheNothingYetReason() {
        Superlative s = shape(List.of(notJudged(1, "a", 1)), Optional.of(OffsetDateTime.parse("2026-10-27T15:00:00Z")),
                Instant.parse("2026-10-26T12:00:00Z"), false);

        assertFalse(s.available());
        assertEquals(sinceReason("2026-10-27"), s.reason());
    }

    @Test
    void aFetchGapAfterHistoryBeganIsTheNothingYetReasonEvenWhenComplete() {
        Superlative s = shape(List.of(notJudged(1, "a", 1)), Optional.of(OffsetDateTime.parse("2026-10-28T11:00:04Z")),
                Instant.parse("2026-10-30T12:00:00Z"), true);

        assertEquals(sinceReason("2026-10-28"), s.reason());
        assertNull(s.trendingCoverage());
    }

    @Test
    void undatedPickupsWithHistoryOnFileGetTheNothingYetReason() {
        for (boolean complete : new boolean[] {true, false}) {
            assertEquals(sinceReason("2026-10-28"), shape(List.of(notJudged(1, "a", 1)),
                    Optional.of(OffsetDateTime.parse("2026-10-28T11:00:04Z")), null, complete).reason());
        }
    }

    @Test
    void noCountedPickupsAtAllIsTheNothingYetReasonWithHistoryAndNotRecordedWithout() {
        assertEquals(sinceReason("2026-10-28"), shape(List.of(), Optional.of(SNAP), null).reason());
        assertEquals(NOT_RECORDED, shape(List.of(), Optional.empty(), null).reason());
    }

    @Test
    void playersKnownToTheMapGetTheirNameAndPosition() {
        Player p = new Player(1L, Sport.NFL, "p9", "Some Guy", List.of(Position.WR), "BUF", null, null, null, null);
        Superlative s = SeasonSuperlativesService.trendingTonyFromJudged(
                List.of(trending(1, "p9", 1, 2, "WAIVER"), trending(1, "p9", 2, 3, "WAIVER")),
                Optional.of(SNAP), SNAP.toInstant(), true, Map.of("p9", p), ROSTERS, Map.of(), Map.of(), Map.of());

        var d = (TrendingPickupDetail) s.detail().get(0);
        assertEquals("Some Guy", d.playerName());
        assertEquals("WR", d.position());
    }
}
