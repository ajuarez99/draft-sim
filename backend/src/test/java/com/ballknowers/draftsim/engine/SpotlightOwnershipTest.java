package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.engine.SpotlightOwnership.Ownership;
import com.ballknowers.draftsim.store.LeagueMemberRepository;
import com.ballknowers.draftsim.store.RosterSeasonRepository;
import com.ballknowers.draftsim.store.RosterWeekPointsRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ownership from the latest stored week's players_points keys
 * (specs/014-home-player-spotlight, T005, research R10). Pure: no database.
 */
class SpotlightOwnershipTest {

    private static RosterWeekPointsRepository.WeekBreakdown week(int week, int roster, String playersPoints) {
        return new RosterWeekPointsRepository.WeekBreakdown(week, roster, 0.0, playersPoints, null);
    }

    private static RosterSeasonRepository.StandingRow standing(int roster, Long managerId, String managerName) {
        return new RosterSeasonRepository.StandingRow(1L, roster, managerId, managerName, null,
                null, null, null, null, null, null, 2026, "lg", Sport.NFL, "League", false);
    }

    private static RosterSeasonRepository.StandingRow standingWithAvatar(int roster, Long managerId,
                                                                         String managerName, String avatarId) {
        return new RosterSeasonRepository.StandingRow(1L, roster, managerId, managerName, avatarId,
                null, null, null, null, null, null, 2026, "lg", Sport.NFL, "League", false);
    }

    private static LeagueMemberRepository.MemberRow member(long managerId, String teamName) {
        return new LeagueMemberRepository.MemberRow(managerId, "m" + managerId, null, false, teamName);
    }

    @Test
    void aPlayerOnTheLatestWeekMapsToThatRostersTeamName() {
        Map<String, Ownership> m = SpotlightOwnership.build(
                List.of(week(2, 1, "{\"100\": 10.0}"), week(2, 2, "{\"200\": 5.0}")),
                List.of(standing(1, 11L, "alice"), standing(2, 12L, "bob")),
                List.of(member(11L, "Dunk Tank"), member(12L, "  ")), null);

        assertEquals("Dunk Tank", m.get("100").teamName());
        assertTrue(m.get("100").rostered());
        // a blank team name falls through to the manager name, as WeeklyReportService does
        assertEquals("bob", m.get("200").teamName());
    }

    @Test
    void aPlayerOnlyOnAnEarlierWeekIsUnrostered() {
        Map<String, Ownership> m = SpotlightOwnership.build(
                List.of(week(1, 1, "{\"100\": 10.0, \"999\": 1.0}"), week(2, 1, "{\"100\": 7.0}")),
                List.of(standing(1, 11L, "alice")), List.of(), null);

        assertTrue(m.containsKey("100"));
        assertFalse(m.containsKey("999"), "dropped before the latest week, so no longer on a roster");
    }

    @Test
    void isMeIsTrueOnlyForTheCallersRoster() {
        Map<String, Ownership> m = SpotlightOwnership.build(
                List.of(week(1, 1, "{\"100\": 1.0}"), week(1, 2, "{\"200\": 1.0}")),
                List.of(standing(1, 11L, "alice"), standing(2, 12L, "bob")), List.of(), 12L);

        assertFalse(m.get("100").isMe());
        assertTrue(m.get("200").isMe());

        Map<String, Ownership> signedOut = SpotlightOwnership.build(
                List.of(week(1, 1, "{\"100\": 1.0}")),
                List.of(standing(1, 11L, "alice")), List.of(), null);
        assertFalse(signedOut.get("100").isMe());
    }

    @Test
    void ownershipCarriesTheOwningRostersManagerAvatarAndNullWhenThereIsNone() {
        Map<String, Ownership> m = SpotlightOwnership.build(
                List.of(week(1, 1, "{\"100\": 1.0}"), week(1, 2, "{\"200\": 1.0}")),
                List.of(standingWithAvatar(1, 11L, "alice", "av-alice"), standing(2, 12L, "bob")),
                List.of(), null);

        assertEquals("av-alice", m.get("100").avatarId());
        assertNull(m.get("200").avatarId(), "no manager avatar is null, never an empty string");
        assertNull(Ownership.UNROSTERED.avatarId());
    }

    @Test
    void noStoredWeeksGivesAnEmptyMap() {
        assertTrue(SpotlightOwnership.build(List.of(), List.of(standing(1, 11L, "alice")),
                List.of(), 11L).isEmpty());
    }

    @Test
    void aRosterWithNoMemberRowAndNoManagerNameIsRosterN() {
        // roster 3 has a standing row with no manager at all; roster 4 has no standing row
        Map<String, Ownership> m = SpotlightOwnership.build(
                List.of(week(1, 3, "{\"300\": 1.0}"), week(1, 4, "{\"400\": 1.0}")),
                List.of(standing(3, null, null)), List.of(), 11L);

        assertEquals("Roster 3", m.get("300").teamName());
        assertEquals("Roster 4", m.get("400").teamName());
        assertFalse(m.get("300").isMe());
    }

    @Test
    void aBlankOrMissingPlayersPointsBlobIsSkippedNotAnError() {
        Map<String, Ownership> m = SpotlightOwnership.build(
                List.of(week(1, 1, null), week(1, 2, " "), week(1, 3, "{\"300\": 1.0}")),
                List.of(), List.of(), null);
        assertEquals(1, m.size());
    }
}
