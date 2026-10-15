package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.ingest.SleeperClient;
import com.ballknowers.draftsim.store.LeagueMemberRepository;
import com.ballknowers.draftsim.store.LeagueRepository;
import com.ballknowers.draftsim.store.ManagerRepository;
import com.ballknowers.draftsim.store.RankingBallotRepository;
import com.ballknowers.draftsim.store.RosterSeasonRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Spec 035 code review S6: the ballot's "you" goes through the per-league caller resolver (FR-004). */
class MemberRankingServiceCallerTest {

    private static Map<String, Object> roster(int id, String owner) {
        Map<String, Object> m = new HashMap<>();
        m.put("roster_id", id);
        m.put("owner_id", owner);
        return m;
    }

    private MemberRankingService service(ManagerRepository managers, SleeperClient sleeper) {
        return new MemberRankingService(Mockito.mock(RankingBallotRepository.class), sleeper, managers,
                Mockito.mock(LeagueMemberRepository.class), Mockito.mock(LeagueRepository.class),
                Mockito.mock(RosterSeasonRepository.class));
    }

    @Test
    void aLinkedCallerIsMeOnTheRosterTheirLinkedMemberOwns() {
        ManagerRepository managers = Mockito.mock(ManagerRepository.class);
        SleeperClient sleeper = Mockito.mock(SleeperClient.class);
        Mockito.when(sleeper.rosters("L")).thenReturn(List.of(roster(1, "owner-a"), roster(2, "owner-b")));
        Mockito.when(managers.idsBySleeperUserId()).thenReturn(Map.of("owner-a", 11L, "owner-b", 12L));
        // The caller's header names nobody who owns a roster, but their claimed link does (member 12).
        Mockito.when(managers.callerManagerIn(List.of(5L), "claimant")).thenReturn(Optional.of(12L));

        var members = service(managers, sleeper).members(5L, "L", "claimant");

        assertEquals(List.of(2), members.stream().filter(MemberRankingService.BallotMember::isMe)
                .map(MemberRankingService.BallotMember::rosterId).toList());
    }

    @Test
    void anEspnShapedHeaderIsNobody() {
        ManagerRepository managers = Mockito.mock(ManagerRepository.class);
        SleeperClient sleeper = Mockito.mock(SleeperClient.class);
        Mockito.when(sleeper.rosters("L")).thenReturn(List.of(roster(1, "espn:{A}")));
        Mockito.when(managers.idsBySleeperUserId()).thenReturn(Map.of("espn:{A}", 11L));

        var members = service(managers, sleeper).members(5L, "L", "espn:{A}");

        assertTrue(members.stream().noneMatch(MemberRankingService.BallotMember::isMe));
    }
}
