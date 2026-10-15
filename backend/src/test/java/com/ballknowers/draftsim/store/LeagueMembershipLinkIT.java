package com.ballknowers.draftsim.store;

import com.ballknowers.draftsim.ingest.espn.EspnIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Spec 035 T038: a claimed ESPN manager makes the ESPN league the caller's, and only theirs. */
class LeagueMembershipLinkIT extends EspnIT {

    @Autowired LeagueMembership membership;
    @Autowired ManagerRepository managers;
    @Autowired LeagueRepository leagues;

    @Test
    void aLinkedManagerSeesTheLeagueAndIsTheCallerInIt() throws Exception {
        add(ALLAN, 1, false).andExpect(status().isOk());
        long league = leagues.bySleeperId(LEAGUE_KEY).orElseThrow().id();
        long espnAlpha = managers.idsBySleeperUserId().get("espn:{IT035-A}");

        assertTrue(membership.canSee(ALLAN, league));
        assertEquals(Optional.of(espnAlpha), membership.callerManagerIn(league, ALLAN));
        assertFalse(membership.canSee(STRANGER, league));
        assertEquals(Optional.empty(), membership.callerManagerIn(league, STRANGER));
    }

    @Test
    void inASleeperLeagueTheHeadersOwnManagerStaysTheCaller() throws Exception {
        add(ALLAN, 1, false).andExpect(status().isOk());
        long allan = managers.idsBySleeperUserId().get(ALLAN);
        jdbc.update("insert into league (sport, season, sleeper_id, name, total_rosters) values ('nba', 2099, 'it035-sleeper-league', 'x', 2)");
        long sleeperLeague = jdbc.queryForObject("select id from league where sleeper_id = 'it035-sleeper-league'", Long.class);
        try {
            jdbc.update("insert into roster_season (league_id, manager_id, roster_id) values (?, ?, 1)", sleeperLeague, allan);
            assertEquals(Optional.of(allan), membership.callerManagerIn(sleeperLeague, ALLAN), "the header's manager, not the linked ESPN one");
            assertEquals(allan, ManagerRepository.caller(managers, sleeperLeague, ALLAN));
        } finally {
            jdbc.update("delete from league where sleeper_id = 'it035-sleeper-league'");
        }
    }

    @Test
    void withNoLinkThereIsNothingToResolve() throws Exception {
        add(ALLAN, 1, false).andExpect(status().isOk());
        long league = leagues.bySleeperId(LEAGUE_KEY).orElseThrow().id();
        jdbc.update("delete from manager_link where manager_id = (select id from manager where sleeper_user_id = ?)", ALLAN);
        assertEquals(Optional.empty(), membership.callerManagerIn(league, ALLAN));
        assertFalse(membership.canSee(ALLAN, league));
    }

    @Test
    void aSleeperOnlyUsersLeagueListIsUnchangedByAnUnrelatedEspnLeagueAndLink() throws Exception {
        // A user in no ESPN league: the set of leagues they see before and after is identical.
        jdbc.update("insert into manager (sleeper_user_id, display_name) values ('it035-bystander', 'b')");
        Set<Long> before = membership.leagueIdsFor("it035-bystander");
        add(ALLAN, 1, false).andExpect(status().isOk());
        assertEquals(before, membership.leagueIdsFor("it035-bystander"));
        assertEquals(Set.of(), membership.leagueIdsFor("it035-bystander"));
    }
}
