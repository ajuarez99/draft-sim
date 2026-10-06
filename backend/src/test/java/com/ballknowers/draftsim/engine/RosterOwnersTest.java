package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.engine.RosterOwners.RosterOwner;
import com.ballknowers.draftsim.store.LeagueMemberRepository;
import com.ballknowers.draftsim.store.RosterSeasonRepository;
import com.ballknowers.draftsim.store.RosterWeekPointsRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** specs/019-minutes-streaming T002 (review F11): one owner-naming rule. */
class RosterOwnersTest {

    private static RosterSeasonRepository.StandingRow standing(int roster, Long managerId, String managerName, String avatar) {
        return new RosterSeasonRepository.StandingRow(1L, roster, managerId, managerName, avatar,
                null, null, null, null, null, null, 2026, "lg", Sport.NBA, "League", false);
    }

    private static LeagueMemberRepository.MemberRow member(long managerId, String teamName) {
        return new LeagueMemberRepository.MemberRow(managerId, "m" + managerId, null, false, teamName);
    }

    @Test
    void teamNameThenManagerNameThenRosterN() {
        Map<Integer, RosterOwner> m = RosterOwners.ownerNames(
                List.of(standing(1, 11L, "alice", "av1"), standing(2, 12L, "bob", null),
                        standing(3, 13L, null, null), standing(4, null, null, null)),
                List.of(member(11L, "Dunk Tank"), member(12L, "  ")), 12L);
        assertEquals("Dunk Tank", m.get(1).name());
        assertEquals("av1", m.get(1).avatarId());
        assertFalse(m.get(1).isMe());
        assertEquals("bob", m.get(2).name());
        assertTrue(m.get(2).isMe());
        assertEquals("Roster 3", m.get(3).name());
        assertEquals("Roster 4", m.get(4).name());
    }

    @Test
    void noCallerMeansNobodyIsMe() {
        Map<Integer, RosterOwner> m = RosterOwners.ownerNames(List.of(standing(1, null, "x", null)), List.of(), null);
        assertFalse(m.get(1).isMe());
    }

    @Test
    void spotlightAndOwnerNamesGiveTheSameNameForTheSameRoster() {
        List<RosterSeasonRepository.StandingRow> standings = List.of(
                standing(1, 11L, "alice", null), standing(2, 12L, "bob", null), standing(3, null, null, null));
        List<LeagueMemberRepository.MemberRow> members = List.of(member(11L, "Dunk Tank"));
        Map<Integer, RosterOwner> owners = RosterOwners.ownerNames(standings, members, 12L);
        List<RosterWeekPointsRepository.WeekBreakdown> weeks = List.of(
                new RosterWeekPointsRepository.WeekBreakdown(1, 1, 0.0, "{\"a\": 1.0}", null),
                new RosterWeekPointsRepository.WeekBreakdown(1, 2, 0.0, "{\"b\": 1.0}", null),
                new RosterWeekPointsRepository.WeekBreakdown(1, 3, 0.0, "{\"c\": 1.0}", null));
        Map<String, SpotlightOwnership.Ownership> spot = SpotlightOwnership.build(weeks, standings, members, 12L);
        assertEquals(owners.get(1).name(), spot.get("a").teamName());
        assertEquals(owners.get(2).name(), spot.get("b").teamName());
        assertEquals(owners.get(3).name(), spot.get("c").teamName());
        assertEquals(owners.get(2).isMe(), spot.get("b").isMe());
    }
}
