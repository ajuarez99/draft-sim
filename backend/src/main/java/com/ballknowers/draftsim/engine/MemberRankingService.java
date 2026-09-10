package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.ingest.RosterOwnerMapper;
import com.ballknowers.draftsim.ingest.SleeperClient;
import com.ballknowers.draftsim.store.LeagueMemberRepository;
import com.ballknowers.draftsim.store.ManagerRepository;
import com.ballknowers.draftsim.store.RankingBallotRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * claude/power-rankings-ballots.md mode 2's aggregation -- computed on read
 * from {@code ranking_ballot}, never stored (see V9's migration comment for
 * why: a ballot's inputs are already frozen the moment the week ends, so a
 * stored average would just be a second copy of the truth that can disagree
 * with the first).
 *
 * <p>Roster ownership ({@code roster_id -> manager.id}) is read live off
 * {@code sleeper.rosters()} rather than {@code roster_season}, the same
 * choice {@link PowerRankingService#saveCommissionerRanking} makes after
 * plan-review finding 16 -- this feature's whole point is working on a
 * league with no ingested history at all (AC1, AC11, AC14).
 */
@Service
public class MemberRankingService {

    private final RankingBallotRepository ballots;
    private final SleeperClient sleeper;
    private final ManagerRepository managers;
    private final LeagueMemberRepository leagueMembers;

    public MemberRankingService(RankingBallotRepository ballots, SleeperClient sleeper,
                                ManagerRepository managers, LeagueMemberRepository leagueMembers) {
        this.ballots = ballots;
        this.sleeper = sleeper;
        this.managers = managers;
        this.leagueMembers = leagueMembers;
    }

    /**
     * One roster's room aggregate for one week -- claude/power-rankings-ballots.md's
     * "Aggregation" table plus the "Thin coverage has rules" section.
     *
     * @param rank          competition rank on {@code avgRank}, ascending --
     *                      see {@link #forWeek} for why a thin-coverage
     *                      roster's rank is pushed past every qualified one
     *                      rather than competing with them for rank 1.
     * @param stdev         population sigma of the individual ballot ranks
     *                      this roster received, suppressed (null) below
     *                      three ballots -- population sigma at n=1 is
     *                      exactly 0.0, which would otherwise render as
     *                      "perfect consensus" from a single vote.
     * @param ballotCount   ballots that ranked THIS roster specifically, not
     *                      the week's total -- a roster absent from a ballot
     *                      is skipped, never imputed as last.
     * @param thinCoverage  true when {@code ballotCount} is under half the
     *                      week's total ballots.
     * @param selfRankBias  {@code myRankOfMyTeam - avgRank}, where "my" is
     *                      this roster's own owner -- null when that owner
     *                      has no roster mapping, or submitted no ballot this
     *                      week. Negative means the owner rates their own
     *                      team higher than the room does.
     */
    public record Entry(int rosterId, Long managerId, int rank, double avgRank, int bestRank, int worstRank,
                        Double stdev, int ballotCount, boolean thinCoverage, Integer selfRankBias, String note) {}

    public record WeekRankings(int week, int totalBallots, List<Entry> entries) {}

    public List<Integer> weeksWithBallots(long leagueId) {
        return ballots.weeksWithBallots(leagueId);
    }

    /**
     * Empty when nobody has submitted a ballot this week -- "zero ballots,
     * the week does not exist for mode 2" (no carry-forward, ever).
     */
    public Optional<WeekRankings> forWeek(long leagueId, String sleeperLeagueId, int week) {
        List<RankingBallotRepository.Ballot> weekBallots = ballots.forWeek(leagueId, week);
        if (weekBallots.isEmpty()) return Optional.empty();

        int totalBallots = weekBallots.size();

        // roster_id -> every rank it received, and roster_id -> (voter's
        // manager_id -> the rank THAT voter gave it), the latter existing
        // only to answer selfRankBias without exposing anyone's individual
        // ballot on the wire.
        Map<Integer, List<Integer>> ranksByRoster = new LinkedHashMap<>();
        Map<Integer, Map<Long, Integer>> rankByRosterAndVoter = new HashMap<>();
        for (RankingBallotRepository.Ballot ballot : weekBallots) {
            for (RankingBallotRepository.BallotEntry e : ballot.entries()) {
                ranksByRoster.computeIfAbsent(e.rosterId(), k -> new ArrayList<>()).add(e.rank());
                rankByRosterAndVoter.computeIfAbsent(e.rosterId(), k -> new HashMap<>())
                        .put(ballot.managerId(), e.rank());
            }
        }

        Map<Integer, Long> managerByRoster = RosterOwnerMapper.rosterToManager(
                sleeper.rosters(sleeperLeagueId), managers.idsBySleeperUserId());

        // A roster ranked by very few ballots would otherwise win the week
        // outright on one enthusiastic vote (avgRank 1.00 beats an honest
        // 1.4 across seven ballots) -- claude/power-rankings-ballots.md's
        // "Thin coverage has rules". Split first, rank each group with the
        // SAME ranker, then push the thin group's ranks past every qualified
        // roster rather than letting the two compete.
        double threshold = totalBallots / 2.0;

        List<Agg> qualified = new ArrayList<>();
        List<Agg> thin = new ArrayList<>();
        for (var e : ranksByRoster.entrySet()) {
            Agg agg = aggregate(e.getKey(), e.getValue(), threshold);
            (agg.thinCoverage() ? thin : qualified).add(agg);
        }

        List<Entry> out = new ArrayList<>();
        List<Ranker.Ranked<Agg>> rankedQualified =
                Ranker.rank(qualified, Comparator.comparingDouble(Agg::avgRank), Agg::avgRank);
        for (var r : rankedQualified) {
            out.add(toEntry(r.item(), r.rank(), managerByRoster, rankByRosterAndVoter));
        }

        int offset = qualified.size();
        List<Ranker.Ranked<Agg>> rankedThin =
                Ranker.rank(thin, Comparator.comparingDouble(Agg::avgRank), Agg::avgRank);
        for (var r : rankedThin) {
            out.add(toEntry(r.item(), r.rank() + offset, managerByRoster, rankByRosterAndVoter));
        }

        return Optional.of(new WeekRankings(week, totalBallots, out));
    }

    private record Agg(int rosterId, double avgRank, int bestRank, int worstRank, Double stdev,
                       int ballotCount, boolean thinCoverage) {}

    private static Agg aggregate(int rosterId, List<Integer> ranks, double threshold) {
        int n = ranks.size();
        double avg = ranks.stream().mapToInt(Integer::intValue).average().orElse(0.0);
        int best = ranks.stream().mapToInt(Integer::intValue).min().orElse(0);
        int worst = ranks.stream().mapToInt(Integer::intValue).max().orElse(0);
        // Population sigma, suppressed below 3 ballots -- at n=1 it is
        // exactly 0.0, which renders as "perfect consensus" from one vote,
        // the single most misleading number this page could show.
        Double stdev = n < 3 ? null : populationStdev(ranks, avg);
        boolean thinCoverage = n < threshold;
        return new Agg(rosterId, avg, best, worst, stdev, n, thinCoverage);
    }

    private static double populationStdev(List<Integer> values, double mean) {
        double sumSq = 0;
        for (int v : values) sumSq += (v - mean) * (v - mean);
        return Math.sqrt(sumSq / values.size());
    }

    private static Entry toEntry(Agg a, int rank, Map<Integer, Long> managerByRoster,
                                 Map<Integer, Map<Long, Integer>> rankByRosterAndVoter) {
        Long managerId = managerByRoster.get(a.rosterId());
        Integer selfRankBias = null;
        if (managerId != null) {
            Integer myRankOfMyTeam = rankByRosterAndVoter.getOrDefault(a.rosterId(), Map.of()).get(managerId);
            if (myRankOfMyTeam != null) {
                selfRankBias = (int) Math.round(myRankOfMyTeam - a.avgRank());
            }
        }
        double avgRank = round2(a.avgRank());
        Double stdev = a.stdev() == null ? null : round1(a.stdev());
        String note = "avg " + String.format("%.2f", avgRank) + " · " + a.bestRank() + "–" + a.worstRank()
                + " · σ " + (stdev == null ? "—" : String.format("%.1f", stdev))
                + " · " + a.ballotCount() + " ballot(s)"
                + (a.thinCoverage() ? " (thin coverage)" : "");
        return new Entry(a.rosterId(), managerId, rank, avgRank, a.bestRank(), a.worstRank(), stdev,
                a.ballotCount(), a.thinCoverage(), selfRankBias, note);
    }

    private static double round2(double d) {
        return Math.round(d * 100.0) / 100.0;
    }

    private static double round1(double d) {
        return Math.round(d * 10.0) / 10.0;
    }

    /**
     * The ballot board's roster/member list, source of {@code members[]} on
     * {@code GET /ballot} -- one row per roster Sleeper currently has, joined
     * to {@link LeagueMemberRepository} for the display name and team name
     * this league already knows. Live off Sleeper, not stored history, for
     * the same no-ingest-required reason as {@link #forWeek}.
     */
    public record BallotMember(int rosterId, Long managerId, String manager, String teamName, boolean isMe) {}

    public List<BallotMember> members(long leagueId, String sleeperLeagueId, String callerSleeperUserId) {
        Map<String, Long> managerBySleeperUserId = managers.idsBySleeperUserId();
        Long callerManagerId = callerSleeperUserId == null || callerSleeperUserId.isBlank()
                ? null : managerBySleeperUserId.get(callerSleeperUserId);

        Map<Long, LeagueMemberRepository.MemberRow> byManager = new HashMap<>();
        for (LeagueMemberRepository.MemberRow m : leagueMembers.forLeague(leagueId)) {
            byManager.put(m.managerId(), m);
        }

        List<BallotMember> out = new ArrayList<>();
        for (Map<String, Object> roster : sleeper.rosters(sleeperLeagueId)) {
            int rosterId = asInt(roster.get("roster_id"), -1);
            if (rosterId < 0) continue;
            Object ownerId = roster.get("owner_id");
            Long managerId = ownerId == null ? null : managerBySleeperUserId.get(String.valueOf(ownerId));
            LeagueMemberRepository.MemberRow row = managerId == null ? null : byManager.get(managerId);

            String manager = row != null ? row.managerName() : null;
            String teamName = row != null ? row.teamName() : null;
            if (teamName == null) teamName = manager != null ? manager : "roster " + rosterId;

            out.add(new BallotMember(rosterId, managerId, manager, teamName,
                    managerId != null && managerId.equals(callerManagerId)));
        }
        return out;
    }

    private static int asInt(Object o, int fallback) {
        if (o == null) return fallback;
        if (o instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(o.toString());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
