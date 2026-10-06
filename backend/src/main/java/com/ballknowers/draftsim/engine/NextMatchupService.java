package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.store.LeagueMatchupRepository;
import com.ballknowers.draftsim.store.LeagueMemberRepository;
import com.ballknowers.draftsim.store.LeagueRepository;
import com.ballknowers.draftsim.store.LeagueRepository.LeagueRow;
import com.ballknowers.draftsim.store.LeagueRepository.PlayoffFormat;
import com.ballknowers.draftsim.store.ManagerRepository;
import com.ballknowers.draftsim.store.RosterSeasonRepository;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The caller's opponent for the league's current week ({@code settings.leg}), from stored pairings
 * (specs/017-nba-schedule-grid, contracts/api.md C2 and data-model's state table).
 *
 * <p><b>The league row is the URL's</b> ({@code leagues.bySleeperId}), never
 * {@link LeagueSeasonResolver} (review F1): the resolver would answer for the newest season with
 * scored weeks, so NBA 2026 would read 2025's "season over".
 *
 * <p>Names go out raw and nullable; the fallback (team name, then username, then "roster N") stays
 * client-side so the server doesn't grow a third copy of it.
 */
@Service
public class NextMatchupService {

    private final LeagueRepository leagues;
    private final LeagueMatchupRepository matchups;
    private final RosterSeasonRepository rosterSeasons;
    private final LeagueMemberRepository members;
    private final ManagerRepository managers;

    public NextMatchupService(LeagueRepository leagues, LeagueMatchupRepository matchups,
                              RosterSeasonRepository rosterSeasons, LeagueMemberRepository members,
                              ManagerRepository managers) {
        this.leagues = leagues;
        this.matchups = matchups;
        this.rosterSeasons = rosterSeasons;
        this.members = members;
        this.managers = managers;
    }

    public record Side(int rosterId, String teamName, String username, String avatarId) {}

    public record Result(String sport, int season, Integer week, boolean available, String reason,
                         Side me, Side opponent) {}

    public Optional<Result> forLeague(String sleeperLeagueId, String sleeperUserId) {
        Optional<LeagueRow> found = leagues.bySleeperId(sleeperLeagueId);
        if (found.isEmpty()) return Optional.empty();
        LeagueRow league = found.get();
        String sport = league.sport().code();

        OptionalInt leg = leagues.currentLeg(league.id());
        if (leg.isEmpty()) {
            return Optional.of(unavailable(league, sport, null,
                    "Sleeper hasn't said which week this league is in yet."));
        }
        int week = leg.getAsInt();
        int start = leagues.playoffFormat(league.id()).map(PlayoffFormat::playoffWeekStart).orElse(0);
        if (league.complete() || (start >= 2 && week >= start)) {
            return Optional.of(unavailable(league, sport, week, "The regular season is over."));
        }
        List<LeagueMatchupRepository.Fixture> fixtures = matchups.between(league.id(), league.season(), week, week);
        if (fixtures.isEmpty()) {
            return Optional.of(unavailable(league, sport, week, "Pairings for week " + week
                    + " aren't out yet. Sleeper publishes them shortly before the week starts."));
        }

        Long callerManagerId = sleeperUserId == null || sleeperUserId.isBlank() ? null
                : managers.idsBySleeperUserId().get(sleeperUserId);
        Map<Long, String> teamNameByManager = new HashMap<>();
        for (LeagueMemberRepository.MemberRow m : members.forLeague(league.id())) {
            teamNameByManager.put(m.managerId(), m.teamName());
        }
        Map<Integer, RosterSeasonRepository.StandingRow> byRoster = new HashMap<>();
        // The caller's roster: the lowest roster id when a manager holds two. A rule, not an accident
        // (data-model, review F10).
        Integer myRoster = null;
        for (RosterSeasonRepository.StandingRow s : rosterSeasons.forLeague(league.id())) {
            byRoster.put(s.rosterId(), s);
            if (callerManagerId != null && callerManagerId.equals(s.managerId())
                    && (myRoster == null || s.rosterId() < myRoster)) {
                myRoster = s.rosterId();
            }
        }
        if (myRoster == null) {
            return Optional.of(new Result(sport, league.season(), week, true, null, null, null));
        }
        Side me = side(myRoster, byRoster, teamNameByManager);

        Integer myMatchup = null;
        for (LeagueMatchupRepository.Fixture f : fixtures) {
            if (f.rosterId() == myRoster) myMatchup = f.matchupId();
        }
        Integer partner = null;
        if (myMatchup != null) {
            for (LeagueMatchupRepository.Fixture f : fixtures) {
                if (f.rosterId() != myRoster && myMatchup.equals(f.matchupId())
                        && (partner == null || f.rosterId() < partner)) {
                    partner = f.rosterId();
                }
            }
        }
        // Bye: absent from the fixtures (between() drops null matchup ids) or alone on its matchup id.
        Side opponent = partner == null ? null : side(partner, byRoster, teamNameByManager);
        return Optional.of(new Result(sport, league.season(), week, true, null, me, opponent));
    }

    private static Side side(int rosterId, Map<Integer, RosterSeasonRepository.StandingRow> byRoster,
                             Map<Long, String> teamNameByManager) {
        RosterSeasonRepository.StandingRow s = byRoster.get(rosterId);
        if (s == null) return new Side(rosterId, null, null, null);
        String team = s.managerId() == null ? null : teamNameByManager.get(s.managerId());
        return new Side(rosterId, team, s.managerName(), s.avatarId());
    }

    private static Result unavailable(LeagueRow league, String sport, Integer week, String reason) {
        return new Result(sport, league.season(), week, false, reason, null, null);
    }
}
