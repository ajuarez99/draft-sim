package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.store.JsonUtil;
import com.ballknowers.draftsim.store.LeagueMemberRepository;
import com.ballknowers.draftsim.store.LeagueRepository;
import com.ballknowers.draftsim.store.ManagerRepository;
import com.ballknowers.draftsim.store.RosterSeasonRepository;
import com.ballknowers.draftsim.store.RosterWeekPointsRepository;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Who rosters a player in this league, without a live Sleeper call
 * (specs/014-home-player-spotlight, research R10).
 *
 * <p>Current rosters are not stored, so ownership is read from the <b>latest stored week's</b>
 * {@code players_points} keys. The refresh refetches the in-progress week hourly, so this is
 * current to within the refresh window; a pickup since the last refresh reads as unrostered until
 * the next one. The label the page puts on it is "rostered by", never "scored for". A pre-draft
 * league has no stored week, and everyone is then truthfully unrostered (empty map).
 *
 * <p>Owner names follow {@link WeeklyReportService#forWeek} exactly -- {@code league_member.team_name},
 * else the roster's manager name, else "Roster N" -- and {@code isMe} uses the same owner rule
 * (X-Sleeper-User to manager id, never a username match). Two namings of one roster on one page
 * would be a bug of the kind this repo has shipped before.
 */
@Component
public class SpotlightOwnership {

    /** {@code teamName} is nullable by contract, so every map built from this is a mutable one. */
    public record Ownership(boolean rostered, String teamName, boolean isMe, String avatarId) {
        public static final Ownership UNROSTERED = new Ownership(false, null, false, null);
    }

    private final RosterWeekPointsRepository weekPoints;
    private final RosterSeasonRepository rosterSeasons;
    private final LeagueMemberRepository members;
    private final ManagerRepository managers;

    public SpotlightOwnership(RosterWeekPointsRepository weekPoints, RosterSeasonRepository rosterSeasons,
                              LeagueMemberRepository members, ManagerRepository managers) {
        this.weekPoints = weekPoints;
        this.rosterSeasons = rosterSeasons;
        this.members = members;
        this.managers = managers;
    }

    /** Sleeper player id to ownership, for the latest stored week; empty when no week is stored. */
    public Map<String, Ownership> forLeague(LeagueRepository.LeagueRow league, String sleeperUserId) {
        Long callerManagerId = sleeperUserId == null || sleeperUserId.isBlank() ? null
                : managers.idsBySleeperUserId().get(sleeperUserId);
        return build(weekPoints.breakdownsFor(league.id(), league.season()),
                rosterSeasons.forLeague(league.id()), members.forLeague(league.id()), callerManagerId);
    }

    /** The pure half, so the naming and latest-week rules are testable without a database. */
    static Map<String, Ownership> build(List<RosterWeekPointsRepository.WeekBreakdown> all,
                                        List<RosterSeasonRepository.StandingRow> standings,
                                        List<LeagueMemberRepository.MemberRow> memberRows,
                                        Long callerManagerId) {
        Map<String, Ownership> out = new HashMap<>();
        int latest = all.stream().mapToInt(RosterWeekPointsRepository.WeekBreakdown::week).max().orElse(0);
        if (latest == 0) return out;

        Map<Long, String> teamNameByManager = new HashMap<>();
        for (LeagueMemberRepository.MemberRow m : memberRows) {
            if (m.teamName() != null && !m.teamName().isBlank()) teamNameByManager.put(m.managerId(), m.teamName());
        }
        Map<Integer, String> nameByRoster = new HashMap<>();
        Map<Integer, Boolean> mineByRoster = new HashMap<>();
        Map<Integer, String> avatarByRoster = new HashMap<>();
        for (RosterSeasonRepository.StandingRow s : standings) {
            String name = s.managerId() == null ? null : teamNameByManager.get(s.managerId());
            if (name == null) name = s.managerName();
            nameByRoster.put(s.rosterId(), name == null || name.isBlank() ? "Roster " + s.rosterId() : name);
            if (s.avatarId() != null) avatarByRoster.put(s.rosterId(), s.avatarId());
            mineByRoster.put(s.rosterId(), callerManagerId != null && callerManagerId.equals(s.managerId()));
        }

        for (RosterWeekPointsRepository.WeekBreakdown w : all) {
            if (w.week() != latest) continue;
            if (w.playersPointsJson() == null || w.playersPointsJson().isBlank()) continue;
            String owner = nameByRoster.getOrDefault(w.rosterId(), "Roster " + w.rosterId());
            boolean mine = mineByRoster.getOrDefault(w.rosterId(), false);
            String avatar = avatarByRoster.get(w.rosterId());
            for (String playerId : JsonUtil.readMap(w.playersPointsJson()).keySet()) {
                out.put(playerId, new Ownership(true, owner, mine, avatar));
            }
        }
        return out;
    }
}
