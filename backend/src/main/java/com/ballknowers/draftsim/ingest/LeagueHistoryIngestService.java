package com.ballknowers.draftsim.ingest;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.store.JsonUtil;
import com.ballknowers.draftsim.store.LeagueMemberRepository;
import com.ballknowers.draftsim.store.LeagueRepository;
import com.ballknowers.draftsim.store.ManagerRepository;
import com.ballknowers.draftsim.store.RosterSeasonRepository;
import com.ballknowers.draftsim.store.RosterWeekPointsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * claude/league-suite.md Phase A: standings + weekly points for a league
 * chain, read-only against Sleeper, no auth. Deliberately does not touch
 * {@code draft}, {@code draft_pick} or {@code manager_profile} -- same wall as
 * the mock tables ({@code MockDraftContaminationIT}'s pattern), pinned here by
 * {@code LeagueHistoryContaminationIT}.
 *
 * Champion is read off {@code league.metadata.latest_league_winner_roster_id}
 * rather than parsed out of {@code winners_bracket} -- the plan's own
 * shortcut, since full placement (2nd, 3rd, ...) is not required by Phase A's
 * acceptance criteria and reconstructing it needs the whole bracket tree.
 */
@Service
public class LeagueHistoryIngestService {

    private static final Logger log = LoggerFactory.getLogger(LeagueHistoryIngestService.class);

    private final SleeperClient sleeper;
    private final LeagueRepository leagues;
    private final ManagerRepository managers;
    private final LeagueMemberRepository leagueMembers;
    private final RosterSeasonRepository rosterSeasons;
    private final RosterWeekPointsRepository weekPoints;

    public LeagueHistoryIngestService(SleeperClient sleeper, LeagueRepository leagues,
                                      ManagerRepository managers, LeagueMemberRepository leagueMembers,
                                      RosterSeasonRepository rosterSeasons, RosterWeekPointsRepository weekPoints) {
        this.sleeper = sleeper;
        this.leagues = leagues;
        this.managers = managers;
        this.leagueMembers = leagueMembers;
        this.rosterSeasons = rosterSeasons;
        this.weekPoints = weekPoints;
    }

    public record Result(int seasons, int rostersUpserted, int weeksIngested) {}

    public Result ingestChain(Sport sport, String currentLeagueId) {
        int seasons = 0, rosterCount = 0, weekCount = 0;

        for (Map<String, Object> league : sleeper.leagueChain(currentLeagueId)) {
            seasons++;
            long leagueId = LeagueMapper.upsert(leagues, sport, league);
            String sleeperLeagueId = String.valueOf(league.get("league_id"));
            int season = Integer.parseInt(String.valueOf(league.get("season")));

            Map<String, Long> managerByUserId = upsertManagers(leagueId, sleeperLeagueId);
            rosterCount += ingestStandings(leagueId, sleeperLeagueId, league, managerByUserId);
            weekCount += ingestWeeklyPoints(leagueId, season, sleeperLeagueId, league);
        }
        log.info("league history: {} seasons, {} roster-seasons, {} roster-weeks ingested",
                seasons, rosterCount, weekCount);
        return new Result(seasons, rosterCount, weekCount);
    }

    /**
     * claude/power-rankings-ballots.md: the same {@code leagueUsers()} walk
     * this method already ran before the ballots feature existed now also
     * upserts {@code league_member} -- see LeagueIngestService's own copy of
     * this method for why it is not a third read of the payload.
     */
    private Map<String, Long> upsertManagers(long leagueId, String sleeperLeagueId) {
        Map<String, Long> out = new HashMap<>();
        for (Map<String, Object> u : sleeper.leagueUsers(sleeperLeagueId)) {
            String userId = String.valueOf(u.get("user_id"));
            String display = u.get("display_name") == null ? null : String.valueOf(u.get("display_name"));
            long managerId = managers.upsert(userId, display);
            out.put(userId, managerId);

            boolean isCommissioner = Boolean.TRUE.equals(u.get("is_owner"));
            leagueMembers.upsert(leagueId, managerId, isCommissioner, LeagueMapper.teamName(u, display));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private int ingestStandings(long leagueId, String sleeperLeagueId, Map<String, Object> league,
                                Map<String, Long> managerByUserId) {
        Map<String, Object> metadata = asMap(league.get("metadata"));
        Object championRosterIdRaw = metadata.get("latest_league_winner_roster_id");
        Integer championRosterId = championRosterIdRaw == null ? null : LeagueMapper.asInt(championRosterIdRaw, -1);
        if (championRosterId != null && championRosterId < 0) championRosterId = null;

        List<RosterSeasonRepository.Upsert> rows = new ArrayList<>();
        for (Map<String, Object> roster : sleeper.rosters(sleeperLeagueId)) {
            int rosterId = LeagueMapper.asInt(roster.get("roster_id"), -1);
            if (rosterId < 0) continue;
            Object ownerId = roster.get("owner_id");
            Long managerId = ownerId == null ? null : managerByUserId.get(String.valueOf(ownerId));

            Map<String, Object> settings = asMap(roster.get("settings"));
            rows.add(new RosterSeasonRepository.Upsert(
                    leagueId, managerId, rosterId,
                    (Integer) asIntOrNull(settings.get("wins")),
                    (Integer) asIntOrNull(settings.get("losses")),
                    (Integer) asIntOrNull(settings.get("ties")),
                    points(settings, "fpts", "fpts_decimal"),
                    points(settings, "fpts_against", "fpts_against_decimal"),
                    points(settings, "ppts", "ppts_decimal"),
                    championRosterId != null && championRosterId == rosterId ? 1 : null));
        }
        rosterSeasons.upsertAll(rows);
        return rows.size();
    }

    /**
     * Bounded by {@code settings.last_scored_leg} -- never looped until an
     * empty response, which claude/plan-review-league-suite.md's finding 2
     * measured returning real (but never-played) data past that point on Ball
     * Knowers 2025 week 18. The most recently scored week is always re-fetched
     * even if already cached, since it may have been ingested while Sleeper
     * was still finalizing that week's scores; every earlier stored week is
     * settled and skipped (finding 5).
     */
    private int ingestWeeklyPoints(long leagueId, int season, String sleeperLeagueId, Map<String, Object> league) {
        Map<String, Object> settings = asMap(league.get("settings"));
        int lastScoredLeg = LeagueMapper.asInt(settings.get("last_scored_leg"), 0);
        if (lastScoredLeg < 1) return 0;

        Set<Integer> stored = weekPoints.storedWeeks(leagueId);
        int count = 0;
        for (int week = 1; week <= lastScoredLeg; week++) {
            if (stored.contains(week) && week != lastScoredLeg) continue;
            List<Map<String, Object>> matchups = sleeper.matchups(sleeperLeagueId, week);
            if (matchups == null) continue;
            for (Map<String, Object> m : matchups) {
                int rosterId = LeagueMapper.asInt(m.get("roster_id"), -1);
                if (rosterId < 0) continue;
                // "points" is the roster's actual scored total for the week -- verified
                // live against Ball Knowers 2025 week 1 to equal sum(starters_points).
                // starters_points itself is a per-slot ARRAY ([26.02, 13.9, ...]), not a
                // roster total; reading it as a scalar silently produced 0.0 for every
                // roster every week until this was caught by actually running it.
                double startersPoints = asDouble(m.get("points"), 0.0);
                String playersPointsJson = JsonUtil.write(m.getOrDefault("players_points", Map.of()));
                weekPoints.upsert(new RosterWeekPointsRepository.Row(
                        leagueId, season, week, rosterId, startersPoints, playersPointsJson));
                count++;
            }
        }
        return count;
    }

    /** fpts/fpts_against/ppts are split whole+decimal by Sleeper: 1500 + 42 -> 1500.42. */
    private static Double points(Map<String, Object> settings, String wholeKey, String decimalKey) {
        Object whole = settings.get(wholeKey);
        if (whole == null) return null;
        double w = asDouble(whole, 0.0);
        double d = asDouble(settings.get(decimalKey), 0.0);
        return w + d / 100.0;
    }

    private static Integer asIntOrNull(Object o) {
        return o == null ? null : LeagueMapper.asInt(o, 0);
    }

    private static double asDouble(Object o, double fallback) {
        if (o == null) return fallback;
        if (o instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(o.toString());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }
}
