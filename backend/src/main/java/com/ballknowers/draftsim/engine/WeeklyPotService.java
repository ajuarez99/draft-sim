package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.store.LeagueMemberRepository;
import com.ballknowers.draftsim.store.LeagueMembership;
import com.ballknowers.draftsim.store.LeagueRepository;
import com.ballknowers.draftsim.store.LeagueWeeklyPotRepository;
import com.ballknowers.draftsim.store.ManagerRepository;
import com.ballknowers.draftsim.store.PlayerGameRepository;
import com.ballknowers.draftsim.store.RosterSeasonRepository;
import com.ballknowers.draftsim.store.RosterWeekPointsRepository;
import com.ballknowers.draftsim.store.WeekBound;
import com.ballknowers.draftsim.domain.Sport;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The weekly high-score pot, as the page reads it (specs/048-weekly-high-score-ledger, plan A1-A15).
 * Nothing here is stored except the commissioner's switch ({@link LeagueWeeklyPotRepository}); the ledger is
 * recomputed from stored weekly scores on every read, so a re-ingest that corrects a score corrects the pot.
 *
 * <p>One implementation per rule, reused rather than re-derived: which weeks are final
 * ({@link ScoredWeeks}), where the regular season ends ({@link ExpectedWinsService#regularSeasonBound}), who
 * may switch it on ({@link LeagueMembership#canCommission}), what a roster is called ({@link RosterOwners}),
 * whether the live week has started ({@link LiveWeekSource#hasStarted}).
 *
 * <p><b>Only DECIDED weeks move money.</b> A week is DECIDED when its scores are final and not all exactly
 * zero; NO_SCORES (final, every score exactly 0 -- negatives are not zero), NOT_FINAL (stored but still being
 * played) and MISSING (no rows) are listed but carry no winner and no money.
 */
@Service
public class WeeklyPotService {

    public static final String TIE_CENT_RULE = "When a stake doesn't split evenly, the extra cent goes to "
            + "co-winners in roster order. A house convention, not a league rule.";

    public enum Status { DECIDED, NO_SCORES, NOT_FINAL, MISSING }

    /** What the page gets: {@link Off} (no money anywhere in it) or {@link On}. */
    public sealed interface View permits Off, On {}

    /** The off state carries only the switch and who may flip it (SC-005: nothing to render as money). */
    public record Off(int season, Sport sport, boolean enabled, int stakeCents, boolean canEdit,
                      boolean commissionerKnown) implements View {}

    /** {@code winningPoints} and {@code runnerUpPoints} are null unless DECIDED (runner-up also when all tied). */
    public record Week(int week, Status status, List<Integer> winners, BigDecimal winningPoints,
                       BigDecimal runnerUpPoints, int payers, long potCents) {}

    /** {@code managerId} and {@code avatarId} are nullable; {@code noManager} is true for an unowned roster. */
    public record Balance(int rosterId, Long managerId, String name, String avatarId, boolean isMe,
                          boolean noManager, int weeksWon, long collectedCents, long paidCents, long netCents) {}

    public record Debt(int fromRosterId, int toRosterId, long cents) {}

    public record LiveLeader(int week, List<Integer> rosterIds, double points) {}

    public record On(int season, Sport sport, boolean enabled, int stakeCents, boolean canEdit,
                     boolean commissionerKnown, boolean regularSeasonKnown, Integer regularSeasonThroughWeek,
                     int throughWeek, int latestDecidedWeek, boolean seasonFinal, String tieCentRule, List<Week> weeks,
                     List<Balance> balances, List<Debt> pairwise, List<Debt> shortcut, LiveLeader liveLeader,
                     boolean liveUnavailable) implements View {}

    private final LeagueWeeklyPotRepository settings;
    private final LeagueMembership membership;
    private final LeagueMemberRepository leagueMembers;
    private final ExpectedWinsService expectedWins;
    private final ScoredWeeks scoredWeeks;
    private final RosterWeekPointsRepository weekPoints;
    private final RosterSeasonRepository rosterSeasons;
    private final LiveWeekSource liveWeeks;
    private final PlayerGameRepository playerGames;
    private final ManagerRepository managers;

    public WeeklyPotService(LeagueWeeklyPotRepository settings, LeagueMembership membership,
                            LeagueMemberRepository leagueMembers, ExpectedWinsService expectedWins,
                            ScoredWeeks scoredWeeks, RosterWeekPointsRepository weekPoints,
                            RosterSeasonRepository rosterSeasons, LiveWeekSource liveWeeks,
                            PlayerGameRepository playerGames, ManagerRepository managers) {
        this.settings = settings;
        this.membership = membership;
        this.leagueMembers = leagueMembers;
        this.expectedWins = expectedWins;
        this.scoredWeeks = scoredWeeks;
        this.weekPoints = weekPoints;
        this.rosterSeasons = rosterSeasons;
        this.liveWeeks = liveWeeks;
        this.playerGames = playerGames;
        this.managers = managers;
    }

    public View view(LeagueRepository.LeagueRow row, String sleeperUserId) {
        LeagueWeeklyPotRepository.Setting setting = settings.find(row.id())
                .orElse(new LeagueWeeklyPotRepository.Setting(false, LeagueWeeklyPotRepository.DEFAULT_STAKE_CENTS));
        boolean canEdit = membership.canCommission(row.id(), sleeperUserId);
        boolean commissionerKnown = leagueMembers.anyCommissioner(row.id());
        if (!setting.enabled()) {
            return new Off(row.season(), row.sport(), false, setting.stakeCents(), canEdit, commissionerKnown);
        }

        WeekBound bound = expectedWins.regularSeasonBound(row);
        Integer boundWeek = bound.throughWeek();
        boolean regularSeasonKnown = boundWeek != null;

        ScoredWeeks.Snapshot snapshot = scoredWeeks.of(row.id());
        Map<Integer, List<RosterWeekPointsRepository.ScoreRow>> rowsByWeek = new TreeMap<>();
        for (RosterWeekPointsRepository.ScoreRow r : weekPoints.allScores(List.of(row.id()), bound)) {
            if (r.points() == null) continue;
            rowsByWeek.computeIfAbsent(r.week(), w -> new ArrayList<>()).add(r);
        }

        // The live line moves no money, so it reads the cache only (peek) and never holds the page up.
        boolean liveUnavailable = false;
        LiveLeader liveLeader = null;
        Optional<LiveWeekSource.Live> peeked = liveWeeks.peek(row);
        if (peeked.isPresent()) {
            LiveWeekSource.Live live = peeked.get();
            if (live.status() == LiveWeekSource.Status.FAILED) {
                liveUnavailable = true;
            } else {
                liveLeader = liveLeader(row, live, boundWeek, snapshot);
            }
        }

        // The week walk reads stored weeks only; the live week is represented solely by the live line (N1).
        int lastWeek = regularSeasonKnown
                ? Math.min(boundWeek, snapshot.latestStored())
                : snapshot.latestStored();

        List<WeeklyPotLedger.Score> decided = new ArrayList<>();
        Map<Integer, Status> statusByWeek = new TreeMap<>();
        for (int w = 1; w <= lastWeek; w++) {
            List<RosterWeekPointsRepository.ScoreRow> rows = rowsByWeek.get(w);
            Status status;
            if (rows == null || rows.isEmpty()) {
                status = Status.MISSING;
            } else if (!snapshot.isFinal(w)) {
                status = Status.NOT_FINAL;
            } else if (rows.stream().allMatch(r -> r.points().compareTo(BigDecimal.ZERO) == 0)) {
                status = Status.NO_SCORES;
            } else {
                status = Status.DECIDED;
                for (RosterWeekPointsRepository.ScoreRow r : rows) {
                    decided.add(new WeeklyPotLedger.Score(w, r.rosterId(), r.points()));
                }
            }
            statusByWeek.put(w, status);
        }

        List<RosterSeasonRepository.StandingRow> standings = rosterSeasons.forLeague(row.id());
        List<Integer> allRosterIds = standings.stream().map(RosterSeasonRepository.StandingRow::rosterId).toList();
        WeeklyPotLedger.Ledger ledger = WeeklyPotLedger.compute(decided, allRosterIds, setting.stakeCents());

        Map<Integer, WeeklyPotLedger.WeekResult> resultByWeek = new HashMap<>();
        for (WeeklyPotLedger.WeekResult r : ledger.weeks()) resultByWeek.put(r.week(), r);
        List<Week> weeks = new ArrayList<>();
        statusByWeek.forEach((w, status) -> {
            WeeklyPotLedger.WeekResult r = resultByWeek.get(w);
            weeks.add(status == Status.DECIDED && r != null
                    ? new Week(w, status, r.winners(), r.winningPoints(), r.runnerUpPoints(), r.payers(), r.potCents())
                    : new Week(w, status, List.of(), null, null, 0, 0L));
        });

        int throughWeek = 0;
        while (throughWeek < lastWeek && moves(statusByWeek.get(throughWeek + 1))) throughWeek++;
        int latestDecidedWeek = 0;
        for (Map.Entry<Integer, Status> e : statusByWeek.entrySet()) {
            if (e.getValue() == Status.DECIDED) latestDecidedWeek = e.getKey();
        }
        boolean seasonFinal;
        if (regularSeasonKnown) {
            seasonFinal = true;
            for (int w = 1; w <= boundWeek; w++) {
                if (!moves(statusByWeek.get(w))) {
                    seasonFinal = false;
                    break;
                }
            }
        } else {
            seasonFinal = row.complete();
        }

        Long callerManagerId = ManagerRepository.caller(managers, row.id(), sleeperUserId);
        Map<Integer, RosterOwners.RosterOwner> owners =
                RosterOwners.ownerNames(standings, leagueMembers.forLeague(row.id()), callerManagerId);
        Map<Integer, Long> managerByRoster = new HashMap<>();
        for (RosterSeasonRepository.StandingRow s : standings) {
            if (s.managerId() != null) managerByRoster.put(s.rosterId(), s.managerId());
        }
        List<Balance> balances = new ArrayList<>();
        for (WeeklyPotLedger.Balance b : ledger.balances().values()) {
            RosterOwners.RosterOwner owner = owners.get(b.rosterId());
            Long managerId = managerByRoster.get(b.rosterId());
            balances.add(new Balance(b.rosterId(), managerId,
                    owner != null ? owner.name() : "Roster " + b.rosterId(),
                    owner != null ? owner.avatarId() : null,
                    owner != null && owner.isMe(), managerId == null,
                    b.weeksWon(), b.collectedCents(), b.paidCents(), b.netCents()));
        }
        balances.sort(Comparator.comparingLong(Balance::netCents).reversed()
                .thenComparing(Comparator.comparingInt(Balance::weeksWon).reversed())
                .thenComparingInt(Balance::rosterId));

        return new On(row.season(), row.sport(), true, setting.stakeCents(), canEdit, commissionerKnown,
                regularSeasonKnown, boundWeek, throughWeek, latestDecidedWeek, seasonFinal, TIE_CENT_RULE, weeks, balances,
                debts(ledger.pairwise()), debts(ledger.shortcut()), liveLeader, liveUnavailable);
    }

    private static boolean moves(Status s) {
        return s == Status.DECIDED || s == Status.NO_SCORES;
    }

    private static List<Debt> debts(List<WeeklyPotLedger.Debt> in) {
        return in.stream().map(d -> new Debt(d.fromRosterId(), d.toRosterId(), d.cents())).toList();
    }

    /**
     * The roster(s) on top of the live week, or null when there is no honest line to show: the week is outside
     * the regular season, is already final, or has not started (nobody above 0 and no stored player games -- a
     * 12-way 0.0 "tie" is not a leader).
     */
    private LiveLeader liveLeader(LeagueRepository.LeagueRow row, LiveWeekSource.Live live, Integer boundWeek,
                                  ScoredWeeks.Snapshot snapshot) {
        int week = live.week();
        if (boundWeek != null && week > boundWeek) return null;
        if (snapshot.isFinal(week)) return null;
        if (live.rows().isEmpty()) return null;
        if (!LiveWeekSource.hasStarted(live, () -> !playerGames.forWeek(row.sport(), row.season(), week).isEmpty())) {
            return null;
        }
        double max = live.rows().stream().mapToDouble(LiveWeekSource.Row::points).max().orElse(0.0);
        if (max <= 0) return null;
        List<Integer> leaders = live.rows().stream()
                .filter(r -> Double.compare(r.points(), max) == 0)
                .map(LiveWeekSource.Row::rosterId)
                .sorted()
                .toList();
        return new LiveLeader(week, leaders, max);
    }
}
