package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.domain.Player;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.store.LeagueRepository;
import com.ballknowers.draftsim.store.RosterSeasonRepository;
import com.ballknowers.draftsim.store.RosterWeekPointsRepository;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * One {@link CareerProfile} per sport, built from a manager's own
 * roster-seasons (specs/006-deeper-history-both-sports, US3).
 *
 * <p><b>No new aggregate tables, no SQL rollup.</b> {@link RosterManagementService}
 * and {@link ExpectedWinsService} are both already keyed on one league-season
 * -- each resolves a single league through {@link LeagueSeasonResolver} -- so
 * a career figure is a loop over those two calls per roster-season (research
 * R4). A rollup could produce record/points/winRate straight out of SQL, but
 * not efficiency or wins-above-expected, and a profile where half its fields
 * come from one rule and half from another is exactly the defect class this
 * feature exists to close (R3).
 *
 * <p><b>No sport branch.</b> {@link #forManager} groups a manager's own
 * roster-seasons by {@code sport} and runs the identical loop for each group.
 * Nothing here reads {@code Sport.NFL} or {@code Sport.NBA} by name (SC-005).
 *
 * <p><b>One efficiency implementation, not two.</b> Every total and every
 * efficiency figure here traces to {@link RosterManagementService}'s
 * per-week optimal lineup -- never to Sleeper's own stored regular-season
 * potential on {@code roster_season}, which gives a different, more
 * flattering number for the same manager-season (research R3, baseline.md
 * T004). {@code OneEfficiencyImplementationTest} guards this file staying
 * that way (SC-004).
 */
@Service
public class ManagerCareerService {

    private final RosterSeasonRepository rosterSeasons;
    private final RosterWeekPointsRepository weekPoints;
    private final RosterManagementService rosterManagement;
    private final ExpectedWinsService expectedWins;
    private final LeagueRepository leagues;
    private final TransactionAnalysisService transactions;

    public ManagerCareerService(RosterSeasonRepository rosterSeasons, RosterWeekPointsRepository weekPoints,
                                RosterManagementService rosterManagement, ExpectedWinsService expectedWins,
                                LeagueRepository leagues, TransactionAnalysisService transactions) {
        this.rosterSeasons = rosterSeasons;
        this.weekPoints = weekPoints;
        this.rosterManagement = rosterManagement;
        this.expectedWins = expectedWins;
        this.leagues = leagues;
        this.transactions = transactions;
    }

    /**
     * A figure this app genuinely cannot answer, named rather than printed as
     * a zero (FR-011, T039). Present in every {@link CareerProfile} even when
     * empty, so "no answer" is distinguishable from an older server that never
     * asked the question.
     */
    public record Unavailable(String figure, String reason) {}

    /**
     * A ranked figure that always carries the population it was ranked
     * against and the one chain it was ranked within (research R5, FR-008) --
     * a bare {@code #2} is not a rank.
     */
    public record Rank(String figure, int position, int population, String leagueName, String sleeperLeagueId) {}

    /**
     * One roster-season plus the one fact {@link RosterSeasonRepository.StandingRow}
     * itself does not carry: whether it has at least one scored week.
     * Computed once, here, and carried alongside the row rather than
     * recomputed by a caller -- so the wire's per-season {@code counted} flag
     * and {@link CareerProfile#seasonsCounted()} always come from the same
     * source (T035). This repo has shipped a bug where a count and the label
     * next to it were computed two different ways.
     */
    public record SeasonEntry(RosterSeasonRepository.StandingRow row, boolean counted) {}

    /**
     * One manager, one sport. Never spans sports (FR-002).
     *
     * @param seasonsCounted the number of {@link SeasonEntry#counted()} rows
     *                       in {@code seasons}, and the divisor behind every
     *                       average below.
     * @param seasons        every roster-season, including uncounted ones --
     *                       an uncounted season is still listed, it just
     *                       contributes to nothing (US3.4).
     * @param winRate        {@code wins / (wins + losses + ties)}. Null, never
     *                       0, when no games have been played.
     * @param pointsPerSeason {@code pointsFor / seasonsCounted}. Null when
     *                       there are no counted seasons to divide by.
     * @param averageEfficiency the weeks-weighted mean of each counted
     *                       season's own {@link RosterManagementService.TeamRow#efficiency()}.
     *                       Null, never 1.0, when there is no potential to
     *                       divide by -- the same rule that record's own
     *                       javadoc states, carried up a level (T036).
     * @param weeksCounted   weeks actually behind {@code averageEfficiency}.
     * @param weeksExcluded  weeks dropped for want of a per-player breakdown,
     *                       carried through from {@code TeamRow} so an
     *                       exclusion is visible rather than silently
     *                       vanishing from the average (FR-006).
     * @param winsAboveExpected sum of each counted season's own
     *                       {@link ExpectedWinsService.TeamRow#winsAboveExpected()}.
     *                       Null only when not one counted season produced a
     *                       computable figure.
     * @param titles         counted seasons with {@code finalPlacement == 1}
     *                       AND the season complete (T037; Phase 4 already
     *                       fixed the stored side of this, this must not
     *                       reintroduce the bug by dropping the completeness
     *                       check).
     */
    public record CareerProfile(Sport sport, int seasonsCounted, List<SeasonEntry> seasons,
                                int wins, int losses, int ties, Double winRate,
                                double pointsFor, double pointsAgainst, Double pointsPerSeason,
                                Double averageEfficiency, int weeksCounted, int weeksExcluded,
                                Double winsAboveExpected, int titles,
                                List<Unavailable> unavailable, List<Rank> ranks,
                                TransactionAnalysisService.WaiverTendency waivers) {}

    /** T039. Identical for every profile -- these two gaps are structural, not per-manager. */
    private static final List<Unavailable> UNAVAILABLE = List.of(
            new Unavailable("playoffAppearances",
                    "only the champion's placement is stored; the bracket is not parsed"),
            new Unavailable("tradesPerSeason",
                    "trades are not attributed to a manager (a trade names several rosters)"));

    /**
     * One {@link CareerProfile} per sport this manager has a roster-season in.
     * Sports with none are simply absent from the list, the same rule
     * {@code draftHistory} in {@code LeagueHistoryController} already applies.
     */
    public List<CareerProfile> forManager(long managerId) {
        Map<Sport, List<RosterSeasonRepository.StandingRow>> bySport = new LinkedHashMap<>();
        for (RosterSeasonRepository.StandingRow s : rosterSeasons.forManager(managerId)) {
            bySport.computeIfAbsent(s.sport(), k -> new ArrayList<>()).add(s);
        }

        List<CareerProfile> out = new ArrayList<>();
        for (Map.Entry<Sport, List<RosterSeasonRepository.StandingRow>> e : bySport.entrySet()) {
            out.add(careerFor(managerId, e.getKey(), e.getValue()));
        }
        return out;
    }

    private CareerProfile careerFor(long managerId, Sport sport, List<RosterSeasonRepository.StandingRow> rows) {
        // T033/R4: one player map for every league-season of this sport this
        // call touches -- both here and inside computeRanks below -- rather
        // than one per forLeague() call.
        Map<String, Player> playersBySleeperId = rosterManagement.playersBySleeperId(sport);

        List<SeasonEntry> seasons = new ArrayList<>();
        int wins = 0, losses = 0, ties = 0, titles = 0, seasonsCounted = 0;
        double pointsFor = 0, pointsAgainst = 0;
        double efficiencyWeighted = 0;
        int weeksCounted = 0, weeksExcluded = 0;
        double winsAboveExpectedSum = 0;
        boolean anyWinsAboveExpected = false;
        // T073: waivers are computed off the SAME seasons every other figure
        // above is -- counted ones only. An uncounted season (no scored week)
        // contributes to nothing anywhere in this profile (T035), and that
        // rule must not quietly grow an exception for waiver activity just
        // because Sleeper allows preseason adds before a single game is
        // played.
        List<LeagueRepository.LeagueRow> countedLeagueRows = new ArrayList<>();

        for (RosterSeasonRepository.StandingRow s : rows) {
            // T035: the ONE definition of "counted" in this service -- at
            // least one scored week. Deliberately NOT delegated to
            // RosterManagementService.forLeague()'s own emptiness check:
            // that method's LeagueSeasonResolver silently substitutes an
            // EARLIER played season when the one asked for has none stored
            // (it is built for a season-scoped PAGE that wants exactly that
            // fallback -- see the resolver's own javadoc). A career loop
            // must not inherit that behaviour, or an uncounted season would
            // silently re-count an already-visited earlier season under its
            // own name -- exactly what the winsAboveExpected conservation
            // check (T031) exists to catch.
            boolean counted = !weekPoints.storedWeeks(s.leagueId()).isEmpty();
            seasons.add(new SeasonEntry(s, counted));
            if (!counted) continue;
            seasonsCounted++;
            // Guaranteed to resolve: this season is already known to exist
            // (it just produced a StandingRow off league_id), so its own
            // LeagueRow is always found by sleeperLeagueId here.
            leagues.bySleeperId(s.sleeperLeagueId()).ifPresent(countedLeagueRows::add);

            wins += nz(s.wins());
            losses += nz(s.losses());
            ties += nz(s.ties());
            pointsFor += nz(s.pointsFor());
            pointsAgainst += nz(s.pointsAgainst());
            // T037: titles requires BOTH a stored final_placement of 1 AND
            // the season being complete. Phase 4 already gates the STORED
            // value on completeness (LeagueHistoryIngestService), but a
            // pre-V21 row that has not been re-ingested can still hold a
            // stale placement=1 next to a null/incomplete status, so this
            // check stays here too rather than trusting the column alone.
            if (Boolean.TRUE.equals(s.complete()) && s.finalPlacement() != null && s.finalPlacement() == 1) {
                titles++;
            }

            // Guaranteed to resolve to THIS exact season: `counted` was just
            // proven true from storedWeeks() on this season's own league id,
            // and the resolver only ever falls back to an earlier season
            // when the requested one has none stored.
            Optional<RosterManagementService.Result> management =
                    rosterManagement.forLeague(s.sleeperLeagueId(), playersBySleeperId);
            if (management.isPresent() && management.get().available()) {
                for (RosterManagementService.TeamRow t : management.get().teams()) {
                    if (t.rosterId() != s.rosterId()) continue;
                    if (t.efficiency() != null) {
                        efficiencyWeighted += t.efficiency() * t.weeksCounted();
                    }
                    weeksCounted += t.weeksCounted();
                    weeksExcluded += t.weeksExcluded().size();
                    break;
                }
            }

            Optional<ExpectedWinsService.Result> exp = expectedWins.forLeague(s.sleeperLeagueId());
            if (exp.isPresent() && exp.get().available()) {
                for (ExpectedWinsService.TeamRow t : exp.get().teams()) {
                    if (t.rosterId() != s.rosterId()) continue;
                    winsAboveExpectedSum += t.winsAboveExpected();
                    anyWinsAboveExpected = true;
                    break;
                }
            }
        }

        // T036: never 1.0. weeksCounted is the true "was there any potential
        // to divide by" test -- not seasonsCounted, which only says a week
        // was SCORED, not that a per-player breakdown existed for it.
        Double winRate = (wins + losses + ties) > 0 ? round4((double) wins / (wins + losses + ties)) : null;
        Double pointsPerSeason = seasonsCounted > 0 ? round2(pointsFor / seasonsCounted) : null;
        Double averageEfficiency = weeksCounted > 0 ? round4(efficiencyWeighted / weeksCounted) : null;
        Double winsAboveExpected = anyWinsAboveExpected ? round2(winsAboveExpectedSum) : null;

        List<Rank> ranks = computeRanks(managerId, sport, seasons, playersBySleeperId);

        // T073: the SAME seasonsCounted computed above, not re-derived --
        // "moves per season" and "wins per season" must mean the same
        // seasons on the same block.
        TransactionAnalysisService.WaiverTendency waivers =
                transactions.careerWaiverTendency(managerId, countedLeagueRows, seasonsCounted);

        return new CareerProfile(sport, seasonsCounted, seasons, wins, losses, ties, winRate,
                round2(pointsFor), round2(pointsAgainst), pointsPerSeason,
                averageEfficiency, weeksCounted, weeksExcluded, winsAboveExpected, titles,
                UNAVAILABLE, ranks, waivers);
    }

    /**
     * T038 / research R5: a figure is ranked within ONE league chain and one
     * sport, never across the whole database -- a manager in two football
     * chains gets two entries per figure, because comparing two chains that
     * have never played each other, under different scoring and different
     * schedules, is not a comparison at all.
     *
     * <p>The chain a counted season belongs to is identified by
     * {@code leagueName}: this schema gives no FORWARD walk from an older
     * season to a newer one (only {@code previous_league_id}, backward), and
     * every chain in this database keeps one stable name across its seasons
     * -- the same assumption {@code standingRow}'s own {@code leagueName}
     * field already rests on. The newest of this manager's own seasons for
     * that name is used to reach the chain: {@link LeagueRepository#chainBySleeperId}
     * walks backward from there, picking up every earlier season this
     * manager's own figures already sum.
     */
    private List<Rank> computeRanks(long managerId, Sport sport, List<SeasonEntry> seasons,
                                    Map<String, Player> playersBySleeperId) {
        Map<String, List<SeasonEntry>> byChainName = new LinkedHashMap<>();
        for (SeasonEntry se : seasons) {
            if (!se.counted()) continue;
            byChainName.computeIfAbsent(se.row().leagueName(), k -> new ArrayList<>()).add(se);
        }

        List<Rank> ranks = new ArrayList<>();
        for (Map.Entry<String, List<SeasonEntry>> e : byChainName.entrySet()) {
            String leagueName = e.getKey();
            SeasonEntry newest = e.getValue().stream()
                    .max(Comparator.comparingInt(se -> se.row().season()))
                    .orElseThrow();
            String headSleeperLeagueId = newest.row().sleeperLeagueId();

            List<LeagueRepository.LeagueRow> chain = leagues.chainBySleeperId(headSleeperLeagueId);
            if (chain.isEmpty()) continue;

            ChainTotals totals = chainTotals(sport, chain, playersBySleeperId);
            addRank(ranks, "winRate", managerId, totals.winRate(), leagueName, headSleeperLeagueId);
            addRank(ranks, "pointsPerSeason", managerId, totals.pointsPerSeason(), leagueName, headSleeperLeagueId);
            addRank(ranks, "averageEfficiency", managerId, totals.averageEfficiency(), leagueName, headSleeperLeagueId);
        }
        return ranks;
    }

    private record ChainAgg(int wins, int losses, int ties, double pointsFor, int seasonsCounted,
                            int weeksCounted, double efficiencyWeighted) {

        static final ChainAgg ZERO = new ChainAgg(0, 0, 0, 0, 0, 0, 0);
    }

    private record ChainTotals(Map<Long, Double> winRate, Map<Long, Double> pointsPerSeason,
                               Map<Long, Double> averageEfficiency) {}

    /**
     * Every manager who owns a roster anywhere in {@code chain}, aggregated
     * the same way {@link #careerFor} aggregates one manager's own seasons --
     * so a Rank compares this manager's figure against exactly the same
     * computation applied to everyone else in the chain, not a cheaper proxy.
     */
    private ChainTotals chainTotals(Sport sport, List<LeagueRepository.LeagueRow> chain,
                                    Map<String, Player> playersBySleeperId) {
        Map<Long, ChainAgg> byManager = new HashMap<>();

        for (LeagueRepository.LeagueRow league : chain) {
            if (weekPoints.storedWeeks(league.id()).isEmpty()) continue;

            Map<Integer, Double> efficiencyWeightedByRoster = new HashMap<>();
            Map<Integer, Integer> weeksCountedByRoster = new HashMap<>();
            Optional<RosterManagementService.Result> management =
                    rosterManagement.forLeague(league.sleeperId(), playersBySleeperId);
            if (management.isPresent() && management.get().available()) {
                for (RosterManagementService.TeamRow t : management.get().teams()) {
                    if (t.efficiency() != null) {
                        efficiencyWeightedByRoster.put(t.rosterId(), t.efficiency() * t.weeksCounted());
                    }
                    weeksCountedByRoster.put(t.rosterId(), t.weeksCounted());
                }
            }

            for (RosterSeasonRepository.StandingRow s : rosterSeasons.forLeague(league.id())) {
                if (s.managerId() == null) continue;
                ChainAgg prior = byManager.getOrDefault(s.managerId(), ChainAgg.ZERO);
                byManager.put(s.managerId(), new ChainAgg(
                        prior.wins() + nz(s.wins()),
                        prior.losses() + nz(s.losses()),
                        prior.ties() + nz(s.ties()),
                        prior.pointsFor() + nz(s.pointsFor()),
                        prior.seasonsCounted() + 1,
                        prior.weeksCounted() + weeksCountedByRoster.getOrDefault(s.rosterId(), 0),
                        prior.efficiencyWeighted() + efficiencyWeightedByRoster.getOrDefault(s.rosterId(), 0.0)));
            }
        }

        Map<Long, Double> winRate = new HashMap<>();
        Map<Long, Double> pointsPerSeason = new HashMap<>();
        Map<Long, Double> averageEfficiency = new HashMap<>();
        for (Map.Entry<Long, ChainAgg> e : byManager.entrySet()) {
            ChainAgg a = e.getValue();
            int games = a.wins() + a.losses() + a.ties();
            if (games > 0) winRate.put(e.getKey(), (double) a.wins() / games);
            if (a.seasonsCounted() > 0) pointsPerSeason.put(e.getKey(), a.pointsFor() / a.seasonsCounted());
            if (a.weeksCounted() > 0) averageEfficiency.put(e.getKey(), a.efficiencyWeighted() / a.weeksCounted());
        }
        return new ChainTotals(winRate, pointsPerSeason, averageEfficiency);
    }

    /**
     * This manager gets a rank for {@code figure} only when their OWN
     * chain-scoped figure is non-null -- an absent figure has nothing to rank
     * (mirrors {@code averageEfficiency}/{@code winRate}'s own null rule, one
     * level up). Position counts every value strictly greater; ties share a
     * position, which is the ordinary meaning of "rank" under a tie.
     */
    private static void addRank(List<Rank> ranks, String figure, long managerId, Map<Long, Double> population,
                                String leagueName, String sleeperLeagueId) {
        Double mine = population.get(managerId);
        if (mine == null) return;
        int position = 1;
        for (double v : population.values()) {
            if (v > mine) position++;
        }
        ranks.add(new Rank(figure, position, population.size(), leagueName, sleeperLeagueId));
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private static double nz(Double v) {
        return v == null ? 0 : v;
    }

    /** Points are a two-decimal quantity everywhere Sleeper reports them. */
    private static double round2(double d) {
        return Math.round(d * 100.0) / 100.0;
    }

    /** Rates (winRate, averageEfficiency) keep more precision -- ffwrapped's 73.3% needs three digits. */
    private static double round4(double d) {
        return Math.round(d * 10000.0) / 10000.0;
    }
}
