package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.engine.PlayerOwnership.Facts;
import com.ballknowers.draftsim.engine.PlayerOwnership.Ownership;
import com.ballknowers.draftsim.engine.RosterOwners.RosterOwner;
import com.ballknowers.draftsim.store.LeagueMemberRepository;
import com.ballknowers.draftsim.store.RosterSeasonRepository;
import com.ballknowers.draftsim.store.RosterSeasonRepository.Rostered;
import com.ballknowers.draftsim.store.RosterWeekPointsRepository.WeekBreakdown;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Spec 022 T020: data-model "Ownership" table (F2, F5, F6, N6), and the naming rule it reuses. */
class PlayerOwnershipTest {

    private static final OffsetDateTime FETCHED = OffsetDateTime.parse("2026-10-08T10:00:00Z");

    private static final Map<Integer, RosterOwner> OWNERS = Map.of(
            1, new RosterOwner("Dunk Tank", "av1", false),
            2, new RosterOwner("Bob", null, true));

    private static Rostered v28(Map<String, Integer> by) {
        return new Rostered(by, FETCHED);
    }

    private static WeekBreakdown wk(int week, int roster, String playersPointsJson) {
        return new WeekBreakdown(week, roster, 0.0, playersPointsJson, null);
    }

    private static Map<Integer, Map<Integer, Set<String>>> weeks(WeekBreakdown... w) {
        return PlayerOwnership.weekRosters(List.of(w));
    }

    private static Facts facts(String status, Integer playoffStart, Integer leg, Integer lastWeek, Rostered cur,
                               Map<Integer, Map<Integer, Set<String>>> weekRosters) {
        return new Facts(status, playoffStart, leg, lastWeek, cur, weekRosters, OWNERS);
    }

    // ------------------------------------------------------------------ season view

    @Test
    void currentSeasonReadsV28AndSaysCurrent() {
        Facts f = facts("in_season", 19, 3, null, v28(Map.of("A", 1, "B", 2)), Map.of());
        Ownership a = PlayerOwnership.forSeasonView("A", f);
        assertEquals("ROSTERED", a.state());
        assertEquals(1, a.rosterId());
        assertEquals("Dunk Tank", a.ownerName());
        assertEquals("av1", a.avatarId());
        assertFalse(a.isMe());
        assertEquals("CURRENT", a.asOf().kind());
        assertEquals(FETCHED, a.asOf().fetchedAt());
        assertNull(a.asOf().week());

        Ownership b = PlayerOwnership.forSeasonView("B", f);
        assertTrue(b.isMe());
        assertNull(b.avatarId());

        Ownership fa = PlayerOwnership.forSeasonView("Z", f);
        assertEquals("FREE_AGENT", fa.state());
        assertNull(fa.rosterId());
        assertNull(fa.ownerName());
        assertEquals("CURRENT", fa.asOf().kind());
    }

    @Test
    void preDraftOrDraftingIsNotDraftedEvenWithRosterRows() {
        for (String status : List.of("pre_draft", "drafting")) {
            Ownership o = PlayerOwnership.forSeasonView("A", facts(status, 19, 1, null, v28(Map.of("A", 1)), Map.of()));
            assertEquals("NOT_DRAFTED", o.state(), status);
        }
        // an unknown status with nobody rostered also reads as not drafted (Trends B7), never as everyone free
        Ownership unknown = PlayerOwnership.forSeasonView("A", facts(null, 19, 1, null, v28(Map.of()), Map.of()));
        assertEquals("NOT_DRAFTED", unknown.state());
    }

    @Test
    void currentSeasonWithRostersNeverFetchedIsUnavailable() {
        Ownership o = PlayerOwnership.forSeasonView("A", facts("in_season", 19, 3, null, null, Map.of()));
        assertEquals("UNAVAILABLE", o.state());
    }

    @Test
    void completedSeasonReadsTheWeekBeforeThePlayoffsNotTheLastStoredWeek() {
        // 2025: playoffs from week 19, so week 18. Week 21 (a playoff week) holds a different roster and must not be read.
        Facts f = facts("complete", 19, 21, 21, null, weeks(
                wk(18, 1, "{\"A\": 10.0, \"X\": 1.0}"), wk(18, 2, "{\"B\": 5.0}"),
                wk(21, 1, "{\"B\": 5.0}"), wk(21, 2, "{\"A\": 10.0}")));
        Ownership a = PlayerOwnership.forSeasonView("A", f);
        assertEquals("ROSTERED", a.state());
        assertEquals(1, a.rosterId());
        assertEquals("WEEK", a.asOf().kind());
        assertEquals(18, a.asOf().week());
        assertNull(a.asOf().fetchedAt());

        // 2024: playoffs from week 22, so week 21
        Facts g = facts("complete", 22, 24, 24, null, weeks(wk(21, 1, "{\"A\": 1.0}"), wk(21, 2, "{\"B\": 1.0}")));
        assertEquals(21, PlayerOwnership.forSeasonView("B", g).asOf().week());
        assertEquals("FREE_AGENT", PlayerOwnership.forSeasonView("Q", g).state());
    }

    @Test
    void pastSeasonNeverBorrowsCurrentRosters() {
        // I8: V28 says A is on roster 1, but the season is over and week 18 is not stored
        Facts f = facts("complete", 19, 21, 21, v28(Map.of("A", 1)), Map.of());
        Ownership o = PlayerOwnership.forSeasonView("A", f);
        assertEquals("UNAVAILABLE", o.state());
        assertEquals(18, o.asOf().week());
        assertNull(o.rosterId());
    }

    @Test
    void anEmptyRosterInTheWeekMakesTheWholeWeekUnavailable() {
        Facts f = facts("complete", 19, 21, 21, null, weeks(wk(18, 1, "{\"A\": 1.0}"), wk(18, 2, "{}")));
        // without N6 this would say A is rostered and C a free agent; with it, neither is known
        assertEquals("UNAVAILABLE", PlayerOwnership.forSeasonView("A", f).state());
        assertEquals("UNAVAILABLE", PlayerOwnership.forSeasonView("C", f).state());
    }

    @Test
    void noPlayoffWeekStartMeansNoEndOfRegularSeasonRoster() {
        Facts f = facts("complete", null, 21, 21, null, weeks(wk(18, 1, "{\"A\": 1.0}")));
        assertEquals("UNAVAILABLE", PlayerOwnership.forSeasonView("A", f).state());
    }

    // ------------------------------------------------------------------ currentOwnership (F6)

    @Test
    void currentOfIsTheRequestedSeasonsCurrentRostersAndIsDraftGated() {
        Facts requested = facts("in_season", 19, 1, null, v28(Map.of("A", 2)), Map.of());
        Ownership o = PlayerOwnership.currentOf("A", requested);
        assertEquals("ROSTERED", o.state());
        assertEquals("CURRENT", o.asOf().kind());
        assertEquals(2, o.rosterId());
        assertEquals("NOT_DRAFTED",
                PlayerOwnership.currentOf("A", facts("pre_draft", 20, 1, null, v28(Map.of()), Map.of())).state());
    }

    // ------------------------------------------------------------------ nights (US3)

    @Test
    void aNightInAScoredWeekReadsThatWeeksRoster() {
        Facts f = facts("in_season", 19, 4, 22, v28(Map.of("A", 2)), weeks(
                wk(2, 1, "{\"A\": 3.0}"), wk(2, 2, "{\"B\": 3.0}"),
                wk(3, 1, "{\"B\": 3.0}"), wk(3, 2, "{\"A\": 3.0}")));
        Ownership w2 = PlayerOwnership.forNight("A", f, 2);
        assertEquals(1, w2.rosterId());
        assertEquals("WEEK", w2.asOf().kind());
        assertEquals(2, w2.asOf().week());
        assertEquals(2, PlayerOwnership.forNight("A", f, 3).rosterId());
    }

    @Test
    void theCurrentUnscoredWeekReadsV28AsCurrent() {
        Facts f = facts("in_season", 19, 4, 22, v28(Map.of("A", 2)), weeks(wk(3, 1, "{\"B\": 3.0}"), wk(3, 2, "{\"C\": 1.0}")));
        Ownership o = PlayerOwnership.forNight("A", f, 4);
        assertEquals("ROSTERED", o.state());
        assertEquals("CURRENT", o.asOf().kind());
        // and draft-gated like the page view
        Facts pre = facts("pre_draft", 19, 1, 22, v28(Map.of("A", 2)), Map.of());
        assertEquals("NOT_DRAFTED", PlayerOwnership.forNight("A", pre, 1).state());
    }

    @Test
    void aNightAfterTheLeaguesLastWeekIsUnavailable() {
        Facts f = facts("complete", 19, 21, 21, null, weeks(wk(21, 1, "{\"A\": 1.0}"), wk(21, 2, "{\"B\": 1.0}")));
        assertEquals("UNAVAILABLE", PlayerOwnership.forNight("A", f, 22).state());
        assertEquals("UNAVAILABLE", PlayerOwnership.forNight("A", f, 25).state());
        assertEquals("ROSTERED", PlayerOwnership.forNight("A", f, 21).state());
    }

    @Test
    void aScoredWeekWithAnEmptyRosterIsUnavailableForNightsToo() {
        Facts f = facts("in_season", 19, 4, 22, null, weeks(wk(2, 1, "{\"A\": 1.0}"), wk(2, 2, "{}")));
        assertEquals("UNAVAILABLE", PlayerOwnership.forNight("A", f, 2).state());
    }

    // ------------------------------------------------------------------ naming

    @Test
    void ownerNamesComeFromRosterOwnersAndRosterIdIsCarried() {
        RosterSeasonRepository.StandingRow s = new RosterSeasonRepository.StandingRow(1L, 7, 11L, "alice", "avA",
                null, null, null, null, null, null, 2025, "lg", null, "League", true);
        Map<Integer, RosterOwner> owners = RosterOwners.ownerNames(List.of(s),
                List.of(new LeagueMemberRepository.MemberRow(11L, "m11", null, false, "Team Alice")), 11L);
        Facts f = new Facts("in_season", 19, 3, null, v28(Map.of("A", 7)), Map.of(), owners);
        Ownership o = PlayerOwnership.forSeasonView("A", f);
        assertEquals("Team Alice", o.ownerName());
        assertEquals(7, o.rosterId());
        assertEquals("avA", o.avatarId());
        assertTrue(o.isMe());
        // a roster with no owner row still gets a name rather than a null
        Facts orphan = new Facts("in_season", 19, 3, null, v28(Map.of("A", 9)), Map.of(), Map.of());
        assertEquals("Roster 9", PlayerOwnership.forSeasonView("A", orphan).ownerName());
    }
}
