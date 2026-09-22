package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.domain.*;
import com.ballknowers.draftsim.store.*;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * What a league's roster moves were worth
 * (specs/004-ffwrapped-feature-parity, US6).
 *
 * <p>Counts by type per manager, plus post-move grading: a player's average
 * positional rank over the weeks PLAYED since he changed hands. Lower is
 * better, and the endpoint says so explicitly rather than leaving a reader to
 * infer it from a 4 next to a name (US6.3).
 *
 * <p>Positional rank is resolved through the sport's own positions, so a
 * basketball league ranks centres against centres and never against tight ends
 * (US6.5). There is no football-shaped position list in this file.
 */
@Service
public class TransactionAnalysisService {

    private final LeagueRepository leagues;
    private final LeagueTransactionRepository transactions;
    private final RosterWeekPointsRepository weekPoints;
    private final RosterSeasonRepository rosterSeasons;
    private final LeagueMemberRepository members;
    private final PlayerRepository players;

    public TransactionAnalysisService(LeagueRepository leagues,
                                      LeagueTransactionRepository transactions,
                                      RosterWeekPointsRepository weekPoints,
                                      RosterSeasonRepository rosterSeasons,
                                      LeagueMemberRepository members,
                                      PlayerRepository players) {
        this.leagues = leagues;
        this.transactions = transactions;
        this.weekPoints = weekPoints;
        this.rosterSeasons = rosterSeasons;
        this.members = members;
        this.players = players;
    }

    public record ManagerCounts(Long managerId, String teamName, Map<String, Integer> counts, int total) {}

    /**
     * @param postMoveRank average positional rank over weeks played since the
     *                     move, or null when no week has been played yet
     * @param weeksCounted how many weeks that average covers -- reported beside
     *                     every rank so a one-week sample is not read as a
     *                     season verdict
     */
    public record MovedPlayer(String playerId, String playerName, String position,
                              Double postMoveRank, int weeksCounted) {}

    public record Add(int week, String teamName, MovedPlayer added, MovedPlayer dropped,
                      Integer faabBid, String type, String status) {}

    public record TradeSide(String teamName, List<MovedPlayer> received) {}

    public record Trade(int week, List<TradeSide> sides) {}

    public record Result(boolean available, String reason, int season, Sport sport,
                         List<ManagerCounts> byManager, List<Trade> trades, List<Add> adds,
                         String rankDirection) {

        static Result unavailable(String reason, int season, Sport sport) {
            return new Result(false, reason, season, sport, List.of(), List.of(), List.of(),
                    RANK_DIRECTION);
        }
    }

    /** Stated on the wire because "4" meaning good is not self-evident. */
    static final String RANK_DIRECTION = "LOWER_IS_BETTER";

    // ------------------------------------------------------- US6 (T070-T072): career-grain waiver/FAAB

    /** One league-season this manager shared no FAAB bidding with, and why. */
    public record ExcludedSeason(int season, String leagueName, String reason) {}

    /**
     * All five figures from data-model.md's FaabTendency, every one of them a
     * fraction of a season's own {@code waiver_budget} -- never a dollar
     * amount, which this database's budgets (100 -> 10000) make meaningless
     * across one manager's own career (research R8).
     *
     * @param typicalBidPct    mean bid size, win or lose -- "what this manager
     *                         usually bids", not "what he usually wins".
     * @param largestBidPct    the single biggest bid attempted, win or lose.
     * @param spentPerSeasonPct total of WON bids only, per season counted --
     *                         money actually spent, unlike the two above.
     * @param claimsPerSeason  FAAB bids attempted (won or lost) per season
     *                         counted -- narrower than {@code movesPerSeason}
     *                         above it, which also counts free-agent adds and
     *                         priority-waiver claims that carried no bid.
     * @param bidSuccessRate   won bids / bids attempted, within FAAB seasons
     *                         only.
     */
    public record FaabTendency(double typicalBidPct, double largestBidPct, double spentPerSeasonPct,
                               double claimsPerSeason, double bidSuccessRate) {}

    /**
     * One manager, one sport, their whole career's waiver behaviour
     * (specs/006-deeper-history-both-sports US6, data-model.md WaiverTendency).
     *
     * @param seasonsCounted the SAME divisor {@link ManagerCareerService}
     *                       already computed for the rest of this manager's
     *                       CareerProfile (T073) -- not re-derived here, so
     *                       "moves per season" and every other career average
     *                       on the same block mean the same seasons. This
     *                       repo has shipped the two-implementations-of-one-
     *                       count bug under four different names; this field
     *                       exists so this is not the fifth.
     * @param faab           null when no season this manager played ran FAAB
     *                       at all (data-model.md: "null when no season used
     *                       FAAB").
     */
    public record WaiverTendency(double movesPerSeason, int seasonsCounted,
                                 FaabTendency faab, List<ExcludedSeason> faabExcludedSeasons) {}

    /**
     * The career-grain sibling of {@link #forLeague}'s per-league-season
     * {@code ManagerCounts} (T070) -- same "what counts as a move" rule
     * (WAIVER + FREE_AGENT; a TRADE cannot be attributed to one manager,
     * every one of 25 stored trades has a null {@code manager_id}, research
     * R8), just summed across a manager's whole career instead of one
     * league-season. Nothing here re-derives that rule; it is stated once,
     * in the one status-blind count below, the same way {@code forLeague}'s
     * own {@code countsByRoster} counts every status.
     *
     * @param managerId     whose career this is.
     * @param leagueSeasons every league-season of ONE sport this manager has
     *                      a roster in -- {@link ManagerCareerService} passes
     *                      its own per-sport grouping, so this method never
     *                      has to decide what a "sport" is.
     * @param seasonsCounted the divisor -- see {@link WaiverTendency#seasonsCounted}.
     */
    public WaiverTendency careerWaiverTendency(long managerId, List<LeagueRepository.LeagueRow> leagueSeasons,
                                               int seasonsCounted) {
        if (leagueSeasons.isEmpty()) {
            return new WaiverTendency(0, seasonsCounted, null, List.of());
        }
        List<Long> leagueIds = leagueSeasons.stream().map(LeagueRepository.LeagueRow::id).toList();

        // ---- moves: WAIVER + FREE_AGENT, every league-season, no status
        // filter -- forLeague()'s own ManagerCounts above applies none
        // either; a failed waiver claim is still a move this manager
        // attempted, and this is the one place that rule is stated.
        int totalMoves = 0;
        for (LeagueTransactionRepository.ManagerTypeCount c : transactions.movesByManager(managerId, leagueIds)) {
            if ("WAIVER".equals(c.type()) || "FREE_AGENT".equals(c.type())) totalMoves += c.count();
        }
        double movesPerSeason = seasonsCounted > 0 ? round2((double) totalMoves / seasonsCounted) : 0;

        // ---- T072: name every season that did not run FAAB before touching
        // a single bid. waiver_type 0 (or anything but 2) is priority -- no
        // bidding at all, and that is the correct, measured format for those
        // seasons (research R8: NFL 2025 alone holds 321 such WAIVER rows,
        // every one with a null faab_bid), not something to "fix" here.
        Map<Long, LeagueRepository.WaiverFormat> formatByLeague = new HashMap<>();
        List<ExcludedSeason> excluded = new ArrayList<>();
        for (LeagueRepository.LeagueRow league : leagueSeasons) {
            Optional<LeagueRepository.WaiverFormat> format = leagues.waiverFormat(league.id());
            format.ifPresent(f -> formatByLeague.put(league.id(), f));
            if (format.isEmpty() || !format.get().usesFaab()) {
                excluded.add(new ExcludedSeason(league.season(), league.name(), "used waiver priority, not FAAB"));
            }
        }

        // ---- T071, verbatim from data-model.md: every bid normalised
        // against ITS OWN season's budget before anything is summed. Budgets
        // in this database range 100 -> 10000 across a single manager's own
        // seasons, so summing raw dollars first and dividing once at the end
        // would silently let the highest-budget season dominate the figure.
        List<Double> bidPcts = new ArrayList<>();
        double spentPctSum = 0;
        int claims = 0, successes = 0;
        for (LeagueTransactionRepository.FaabBidRow bid : transactions.faabBidsByManager(managerId, leagueIds)) {
            LeagueRepository.WaiverFormat format = formatByLeague.get(bid.leagueId());
            // A bid can only exist in a FAAB season by construction (R8), but
            // usesFaab()/waiverBudget()>0 is re-checked rather than trusted --
            // a 0 budget would make the percentage undefined, not 0.
            if (format == null || !format.usesFaab() || format.waiverBudget() <= 0) continue;
            double pct = bid.bid() / format.waiverBudget();
            bidPcts.add(pct);
            claims++;
            if (bid.successful()) {
                successes++;
                spentPctSum += pct;
            }
        }

        FaabTendency faab = null;
        if (!bidPcts.isEmpty()) {
            double typical = bidPcts.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double largest = bidPcts.stream().mapToDouble(Double::doubleValue).max().orElse(0);
            double spentPerSeasonPct = seasonsCounted > 0 ? spentPctSum / seasonsCounted : 0;
            double claimsPerSeason = seasonsCounted > 0 ? (double) claims / seasonsCounted : 0;
            double bidSuccessRate = claims > 0 ? (double) successes / claims : 0;
            faab = new FaabTendency(round4(typical), round4(largest), round4(spentPerSeasonPct),
                    round2(claimsPerSeason), round4(bidSuccessRate));
        }

        return new WaiverTendency(movesPerSeason, seasonsCounted, faab, excluded);
    }

    /** Counts (moves per season) keep two decimals, the same scale {@code pointsPerSeason} uses. */
    private static double round2(double d) {
        return Math.round(d * 100.0) / 100.0;
    }

    /** Rates and percentages keep four decimals, the same scale {@code winRate}/{@code averageEfficiency} use. */
    private static double round4(double d) {
        return Math.round(d * 10000.0) / 10000.0;
    }

    public Optional<Result> forLeague(String sleeperLeagueId) {
        Optional<LeagueRepository.LeagueRow> found = leagues.bySleeperId(sleeperLeagueId);
        if (found.isEmpty()) return Optional.empty();
        LeagueRepository.LeagueRow league = found.get();
        Sport sport = league.sport();

        List<LeagueTransactionRepository.Row> rows =
                transactions.forSeason(league.id(), league.season());
        if (rows.isEmpty()) {
            return Optional.of(Result.unavailable(
                    "no transactions ingested for this league yet", league.season(), sport));
        }

        Map<String, Player> playersBySleeperId = new HashMap<>();
        for (Player p : players.findAll(sport)) {
            if (p.sleeperId() != null) playersBySleeperId.put(p.sleeperId(), p);
        }

        Map<Long, String> teamNameByManager = new HashMap<>();
        for (LeagueMemberRepository.MemberRow m : members.forLeague(league.id())) {
            if (m.teamName() != null && !m.teamName().isBlank()) {
                teamNameByManager.put(m.managerId(), m.teamName());
            }
        }
        Map<Integer, String> nameByRoster = new HashMap<>();
        Map<Integer, Long> managerByRoster = new HashMap<>();
        for (RosterSeasonRepository.StandingRow s : rosterSeasons.forLeague(league.id())) {
            String name = s.managerId() == null ? null : teamNameByManager.get(s.managerId());
            if (name == null) name = s.managerName();
            nameByRoster.put(s.rosterId(), name == null || name.isBlank() ? "Roster " + s.rosterId() : name);
            managerByRoster.put(s.rosterId(), s.managerId());
        }

        // Weekly positional ranks, computed once for the whole season.
        Map<Integer, Map<String, Integer>> rankByWeek =
                positionalRanksByWeek(weekPoints.breakdownsFor(league.id(), league.season()),
                        playersBySleeperId);

        // ---- counts by type
        Map<Integer, Map<String, Integer>> countsByRoster = new HashMap<>();
        for (LeagueTransactionRepository.Row r : rows) {
            if (r.rosterId() == null) {
                // A trade has no single actor; credit every participant.
                for (Integer participant : participants(r, playersBySleeperId)) {
                    countsByRoster.computeIfAbsent(participant, k -> new TreeMap<>())
                            .merge(r.type(), 1, Integer::sum);
                }
            } else {
                countsByRoster.computeIfAbsent(r.rosterId(), k -> new TreeMap<>())
                        .merge(r.type(), 1, Integer::sum);
            }
        }
        List<ManagerCounts> byManager = new ArrayList<>();
        countsByRoster.forEach((rosterId, counts) -> byManager.add(new ManagerCounts(
                managerByRoster.get(rosterId),
                nameByRoster.getOrDefault(rosterId, "Roster " + rosterId),
                counts,
                counts.values().stream().mapToInt(Integer::intValue).sum())));
        byManager.sort(Comparator.comparingInt(ManagerCounts::total).reversed()
                .thenComparing(ManagerCounts::teamName));

        // ---- adds and trades
        List<Add> adds = new ArrayList<>();
        List<Trade> trades = new ArrayList<>();
        for (LeagueTransactionRepository.Row r : rows) {
            Map<String, Object> addMap = readIdMap(r.addsJson());
            Map<String, Object> dropMap = readIdMap(r.dropsJson());

            if ("TRADE".equals(r.type())) {
                Map<Integer, List<MovedPlayer>> received = new LinkedHashMap<>();
                addMap.forEach((playerId, rosterObj) -> {
                    int toRoster = rosterObj instanceof Number n ? n.intValue() : -1;
                    if (toRoster < 0) return;
                    received.computeIfAbsent(toRoster, k -> new ArrayList<>())
                            .add(moved(playerId, playersBySleeperId, rankByWeek, r.week()));
                });
                if (!received.isEmpty()) {
                    List<TradeSide> sides = new ArrayList<>();
                    received.forEach((rosterId, got) -> sides.add(new TradeSide(
                            nameByRoster.getOrDefault(rosterId, "Roster " + rosterId), got)));
                    trades.add(new Trade(r.week(), sides));
                }
                continue;
            }

            // A waiver/free-agent move: one add, optionally one drop.
            String addedId = addMap.keySet().stream().findFirst().orElse(null);
            if (addedId == null) continue;
            String droppedId = dropMap.keySet().stream().findFirst().orElse(null);
            Integer roster = r.rosterId();
            adds.add(new Add(
                    r.week(),
                    roster == null ? "Unknown" : nameByRoster.getOrDefault(roster, "Roster " + roster),
                    moved(addedId, playersBySleeperId, rankByWeek, r.week()),
                    droppedId == null ? null : moved(droppedId, playersBySleeperId, rankByWeek, r.week()),
                    r.faabBid(), r.type(), r.status()));
        }

        // Best adds first: the lowest post-move positional rank, which is the
        // best performance. Adds with no week played yet sort last -- they are
        // ungraded, not bad.
        adds.sort(Comparator.comparing(
                (Add a) -> a.added() == null || a.added().postMoveRank() == null
                        ? Double.MAX_VALUE : a.added().postMoveRank())
                .thenComparingInt(Add::week));

        return Optional.of(new Result(true, null, league.season(), sport,
                byManager, trades, adds, RANK_DIRECTION));
    }

    /**
     * Per week, each player's rank WITHIN HIS OWN POSITION by points scored.
     *
     * <p>Ranked across the whole league that week, so "RB4" means fourth best
     * running back among all rostered running backs -- not fourth on his team.
     * Positions come from the player rows, which are the sport's own, so this
     * is the same code for basketball (US6.5).
     */
    static Map<Integer, Map<String, Integer>> positionalRanksByWeek(
            List<RosterWeekPointsRepository.WeekBreakdown> weeks,
            Map<String, Player> playersBySleeperId) {

        record Scored(String playerId, Position position, double points) {}
        Map<Integer, List<Scored>> byWeek = new TreeMap<>();
        for (RosterWeekPointsRepository.WeekBreakdown w : weeks) {
            if (w.playersPointsJson() == null || w.playersPointsJson().isBlank()) continue;
            Map<String, Object> pts = JsonUtil.readMap(w.playersPointsJson());
            for (Map.Entry<String, Object> e : pts.entrySet()) {
                Player p = playersBySleeperId.get(e.getKey());
                if (p == null || !(e.getValue() instanceof Number n)) continue;
                byWeek.computeIfAbsent(w.week(), k -> new ArrayList<>())
                        .add(new Scored(e.getKey(), p.primary(), n.doubleValue()));
            }
        }

        Map<Integer, Map<String, Integer>> out = new TreeMap<>();
        byWeek.forEach((week, scored) -> {
            Map<Position, List<Scored>> byPos = new EnumMap<>(Position.class);
            for (Scored s : scored) byPos.computeIfAbsent(s.position(), k -> new ArrayList<>()).add(s);
            Map<String, Integer> ranks = new HashMap<>();
            byPos.values().forEach(list -> {
                list.sort(Comparator.comparingDouble(Scored::points).reversed());
                for (int i = 0; i < list.size(); i++) ranks.put(list.get(i).playerId(), i + 1);
            });
            out.put(week, ranks);
        });
        return out;
    }

    /** A player plus his average positional rank in the weeks played since {@code sinceWeek}. */
    private static MovedPlayer moved(String playerId, Map<String, Player> players,
                                     Map<Integer, Map<String, Integer>> rankByWeek, int sinceWeek) {
        Player p = players.get(playerId);
        List<Integer> ranks = new ArrayList<>();
        for (Map.Entry<Integer, Map<String, Integer>> e : rankByWeek.entrySet()) {
            // From the move week INCLUSIVE. Sleeper's `leg` on a transaction is
            // the week it takes effect, and a waiver claim or free-agent add is
            // processed BEFORE that week's games -- so the player really does
            // score for his new roster that week, and his points are already in
            // that roster's players_points.
            //
            // An earlier version counted strictly later weeks, reasoning that
            // the move week was partly played elsewhere. That is a trade's
            // problem, not an add's, and it made every add in a one-week season
            // ungraded: measured against ffwrapped, which grades the week-1
            // waiver pickup Tyler Loop as K4 from week 1 itself.
            if (e.getKey() < sinceWeek) continue;
            Integer rank = e.getValue().get(playerId);
            if (rank != null) ranks.add(rank);
        }
        Double avg = ranks.isEmpty() ? null
                : Math.round(ranks.stream().mapToInt(Integer::intValue).average().orElse(0) * 10.0) / 10.0;
        return new MovedPlayer(playerId,
                p == null ? "Unknown player" : p.name(),
                p == null ? null : p.primary().name(),
                avg, ranks.size());
    }

    /** Roster ids touched by a transaction, via its adds and drops. */
    private static Set<Integer> participants(LeagueTransactionRepository.Row r,
                                             Map<String, Player> players) {
        Set<Integer> out = new LinkedHashSet<>();
        for (String json : List.of(r.addsJson(), r.dropsJson())) {
            readIdMap(json).values().forEach(v -> {
                if (v instanceof Number n) out.add(n.intValue());
            });
        }
        return out;
    }

    private static Map<String, Object> readIdMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return JsonUtil.readMap(json);
        } catch (RuntimeException e) {
            return Map.of();
        }
    }
}
