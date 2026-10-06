package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.store.LeagueMemberRepository;
import com.ballknowers.draftsim.store.RosterSeasonRepository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The one implementation of "what is this roster called, and is it the caller's"
 * (specs/019-minutes-streaming, review F11). Extracted from {@link SpotlightOwnership#build}
 * so Spotlight and Trends cannot name one roster two ways.
 *
 * <p>The rule is {@code league_member.team_name}, else the roster's manager name, else
 * "Roster N". {@code isMe} is the owner rule (X-Sleeper-User resolved to a manager id by the
 * caller, never a username match). {@link WeeklyReportService#forWeek} still has its own copy;
 * moving it here is a named follow-up.
 */
public final class RosterOwners {

    private RosterOwners() {}

    /** {@code avatarId} is nullable. */
    public record RosterOwner(String name, String avatarId, boolean isMe) {}

    public static Map<Integer, RosterOwner> ownerNames(List<RosterSeasonRepository.StandingRow> standings,
                                                       List<LeagueMemberRepository.MemberRow> memberRows,
                                                       Long callerManagerId) {
        Map<Long, String> teamNameByManager = new HashMap<>();
        for (LeagueMemberRepository.MemberRow m : memberRows) {
            if (m.teamName() != null && !m.teamName().isBlank()) teamNameByManager.put(m.managerId(), m.teamName());
        }
        Map<Integer, RosterOwner> out = new HashMap<>();
        for (RosterSeasonRepository.StandingRow s : standings) {
            String name = s.managerId() == null ? null : teamNameByManager.get(s.managerId());
            if (name == null) name = s.managerName();
            if (name == null || name.isBlank()) name = "Roster " + s.rosterId();
            boolean mine = callerManagerId != null && callerManagerId.equals(s.managerId());
            out.put(s.rosterId(), new RosterOwner(name, s.avatarId(), mine));
        }
        return out;
    }
}
