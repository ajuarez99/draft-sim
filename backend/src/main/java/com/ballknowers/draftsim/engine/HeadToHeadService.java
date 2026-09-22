package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.store.LeagueMatchupRepository;
import com.ballknowers.draftsim.store.RosterSeasonRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Two managers, the seasons they actually shared, and the games that pairing
 * produced -- specs/006-deeper-history-both-sports US4 (research R6). ffwrapped
 * leaves this at a permanent 0-0 (it derives no pairing at all); this service
 * is what fixes that, now that {@code league_matchup} is backfilled for every
 * played season in both sports rather than the final week only (002's own
 * measurement -- 8 paired rows per season -- was a symptom of an upsert gate
 * that has since been fixed).
 *
 * <p><b>A shared season is one {@code league_id} both managers own a roster
 * in</b> -- never a league NAME, and never a global manager id compared
 * directly. {@code roster_season.manager_id} is per league-season, which is
 * the only thing that lets popsharky be roster 1 in (Foot) Ball Knowers and
 * roster 7 in West Coast (task T049, contracts/head-to-head-api.md). Every
 * join here resolves through {@link LeagueMatchupRepository#pairedWithScores}
 * and {@link LeagueMatchupRepository#scheduledPairs}, both of which already
 * carry {@code manager_id} off {@code roster_season} rather than a bare
 * roster id -- so this class never has to reconcile roster ids itself.
 *
 * <p><b>A meeting counts only when both sides have a stored
 * {@code starters_points}.</b> {@link LeagueMatchupRepository#pairedWithScores}
 * already enforces this with an inner join (T050) -- the 2026 chains hold 168
 * and 196 scheduled-but-unscored rows that must never surface as a meeting.
 *
 * <p><b>Grouped per sport, never combined.</b> Two managers who share a
 * football chain and a basketball one get two {@code SportHeadToHead}
 * entries with separate records (T052, US4.2) -- the same rule
 * {@link ManagerCareerService} already applies one level up.
 */
@Service
public class HeadToHeadService {

    private final RosterSeasonRepository rosterSeasons;
    private final LeagueMatchupRepository fixtures;

    public HeadToHeadService(RosterSeasonRepository rosterSeasons, LeagueMatchupRepository fixtures) {
        this.rosterSeasons = rosterSeasons;
        this.fixtures = fixtures;
    }

    /** One scored, paired game. {@code winner} is {@code A}/{@code B}/{@code TIE}, by {@code starters_points}. */
    public record Meeting(int season, int week, String leagueName, String sleeperLeagueId,
                          double aPoints, double bPoints, String winner) {}

    /**
     * A season both managers shared that contributed zero meetings between
     * THEM specifically -- named with its own reason (T051, US4.4) rather
     * than left silently absent. See {@link LeagueMatchupRepository#scheduledPairs}
     * for why the reason can be any of three different facts.
     */
    public record SeasonExcluded(int season, String leagueName, String reason) {}

    /** One sport's worth of the two managers' shared history. Never spans sports (T052). */
    public record SportHeadToHead(Sport sport, int aWins, int bWins, int ties,
                                  List<Meeting> meetings, List<SeasonExcluded> seasonsExcluded) {}

    /**
     * @param sharedNothing true when the two managers have never owned a
     *                      roster in the same {@code league_id} at all --
     *                      distinct from a shared season that simply produced
     *                      no meeting (that case still gets a {@code sports[]}
     *                      entry, with {@code seasonsExcluded} explaining it).
     *                      T047 / US4.3: this is the flag that keeps "never
     *                      met" from reading as an honest {@code 0-0}.
     */
    public record Result(List<SportHeadToHead> sports, boolean sharedNothing) {}

    public Result compute(long managerAId, long managerBId) {
        Map<Long, RosterSeasonRepository.StandingRow> aByLeague = byLeague(rosterSeasons.forManager(managerAId));
        Map<Long, RosterSeasonRepository.StandingRow> bByLeague = byLeague(rosterSeasons.forManager(managerBId));

        // Every league_id both managers own a roster in, grouped by sport.
        // Two managers can share several league_ids of the SAME sport --
        // (Foot) Ball Knowers and West Coast are both NFL -- and each
        // contributes its own meetings/seasonsExcluded under the one sport
        // bucket, never a second sports[] row for it.
        Map<Sport, List<Long>> sharedBySport = new EnumMap<>(Sport.class);
        for (Map.Entry<Long, RosterSeasonRepository.StandingRow> e : aByLeague.entrySet()) {
            if (!bByLeague.containsKey(e.getKey())) continue;
            sharedBySport.computeIfAbsent(e.getValue().sport(), k -> new ArrayList<>()).add(e.getKey());
        }

        // T047 / US4.3: an empty sports[] alone is ambiguous -- it is also
        // what an older server with no seasonsExcluded logic would send for a
        // shared-but-silent season. sharedNothing is the explicit flag: never
        // owned a roster together at all, in any sport.
        if (sharedBySport.isEmpty()) return new Result(List.of(), true);

        List<SportHeadToHead> sports = new ArrayList<>();
        // Sport.values() order (NFL, NBA), the same order every other
        // per-sport list in this feature renders in (draftHistory, careers[]).
        for (Sport sport : Sport.values()) {
            List<Long> leagueIds = sharedBySport.get(sport);
            if (leagueIds == null) continue;
            sports.add(sportHeadToHead(sport, leagueIds, aByLeague, managerAId, managerBId));
        }
        return new Result(sports, false);
    }

    private static Map<Long, RosterSeasonRepository.StandingRow> byLeague(List<RosterSeasonRepository.StandingRow> rows) {
        Map<Long, RosterSeasonRepository.StandingRow> out = new HashMap<>();
        for (RosterSeasonRepository.StandingRow r : rows) out.put(r.leagueId(), r);
        return out;
    }

    private SportHeadToHead sportHeadToHead(Sport sport, List<Long> leagueIds,
                                            Map<Long, RosterSeasonRepository.StandingRow> aByLeague,
                                            long managerAId, long managerBId) {
        int aWins = 0, bWins = 0, ties = 0;
        List<Meeting> meetings = new ArrayList<>();
        List<SeasonExcluded> excluded = new ArrayList<>();

        // Newest season first, same order forManager() itself returns rows in.
        List<Long> ordered = leagueIds.stream()
                .sorted(Comparator.comparing((Long id) -> aByLeague.get(id).season()).reversed())
                .toList();

        for (long leagueId : ordered) {
            RosterSeasonRepository.StandingRow row = aByLeague.get(leagueId);

            // T050: pairedWithScores already requires a stored starters_points
            // on BOTH sides -- a scheduled-but-unscored fixture (the 2026
            // chains) never reaches this list. One call per league rather than
            // batching every shared league_id together: PairedGame carries no
            // league_id of its own, and two different league chains can share
            // the same (season, week) -- batching would risk matching a game
            // from the wrong league to this pair.
            List<LeagueMatchupRepository.PairedGame> ours = fixtures.pairedWithScores(List.of(leagueId)).stream()
                    .filter(g -> isThisPair(g.aManagerId(), g.bManagerId(), managerAId, managerBId))
                    .toList();

            if (ours.isEmpty()) {
                excluded.add(new SeasonExcluded(row.season(), row.leagueName(),
                        exclusionReason(leagueId, managerAId, managerBId)));
                continue;
            }

            for (LeagueMatchupRepository.PairedGame g : ours) {
                // Normalise to A/B: pairedWithScores orders its two sides by
                // roster_id (the self-join's own `b.roster_id > a.roster_id`),
                // which has nothing to do with which manager this caller
                // named "a".
                boolean gAisOurA = g.aManagerId() == managerAId;
                double aPoints = (gAisOurA ? g.aPoints() : g.bPoints()).doubleValue();
                double bPoints = (gAisOurA ? g.bPoints() : g.aPoints()).doubleValue();
                String winner;
                // T047: a tie is its own outcome, counted once, never folded
                // into either side's losses.
                if (aPoints > bPoints) {
                    winner = "A";
                    aWins++;
                } else if (bPoints > aPoints) {
                    winner = "B";
                    bWins++;
                } else {
                    winner = "TIE";
                    ties++;
                }
                meetings.add(new Meeting(row.season(), g.week(), row.leagueName(), row.sleeperLeagueId(),
                        round2(aPoints), round2(bPoints), winner));
            }
        }

        // Newest season first, chronological within a season -- the same
        // tiebreak style LeagueRecordService's own margin lists use.
        meetings.sort(Comparator.comparing(Meeting::season).reversed().thenComparing(Meeting::week));

        return new SportHeadToHead(sport, aWins, bWins, ties, meetings, excluded);
    }

    private static boolean isThisPair(Long gA, Long gB, long managerAId, long managerBId) {
        if (gA == null || gB == null) return false;
        return (gA == managerAId && gB == managerBId) || (gA == managerBId && gB == managerAId);
    }

    /**
     * Why a season both managers shared produced no meeting between THESE two
     * specific people -- three different facts, three different sentences
     * (US4.4), because only one of them resolves itself by waiting:
     *
     * <ul>
     *   <li>the schedule paired them, but no score is stored yet (the 2026
     *       chains) -- resolves once the week is played and re-ingested;
     *   <li>the schedule never paired them this season at all -- true even in
     *       a fully-scored season, since a 12-team league does not play a
     *       full round robin in 17 weeks, and never will for this season;
     *   <li>nothing has been loaded for this league's schedule at all.
     * </ul>
     */
    private String exclusionReason(long leagueId, long managerAId, long managerBId) {
        List<LeagueMatchupRepository.ScheduledPair> scheduled = fixtures.scheduledPairs(leagueId);
        boolean everScheduled = scheduled.stream()
                .anyMatch(p -> isThisPair(p.aManagerId(), p.bManagerId(), managerAId, managerBId));
        if (everScheduled) return "fixtures are scheduled but no week is scored yet";
        if (!scheduled.isEmpty()) return "the schedule never paired them this season";
        return "fixtures haven't been loaded for this league's season yet";
    }

    /** Points are a two-decimal quantity everywhere Sleeper reports them (mirrors ManagerCareerService). */
    private static double round2(double d) {
        return Math.round(d * 100.0) / 100.0;
    }
}
