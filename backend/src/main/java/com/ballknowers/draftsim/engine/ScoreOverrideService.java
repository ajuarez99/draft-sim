package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.store.LeagueMatchupRepository;
import com.ballknowers.draftsim.store.LeagueMemberRepository;
import com.ballknowers.draftsim.store.LeagueMembership;
import com.ballknowers.draftsim.store.LeagueRepository;
import com.ballknowers.draftsim.store.RosterFptsSnapshotRepository;
import com.ballknowers.draftsim.store.RosterSeasonRepository;
import com.ballknowers.draftsim.store.RosterWeekPointsRepository;
import com.ballknowers.draftsim.store.WeekBound;
import com.ballknowers.draftsim.store.WeekScoreOverrideRepository;
import com.ballknowers.draftsim.store.WeekScoreResolver;
import com.ballknowers.draftsim.store.ManagerRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The commissioner's score entry for one week (spec 057): what is in use now and where it came from, and the
 * two exact checks against Sleeper's own stored numbers (FR-013). Saving and clearing are whole-week.
 *
 * <p>The checks WARN and never block: they compare typed numbers with Sleeper's official ones, and a mismatch
 * is information for the commissioner, not a rule. Both go through the resolved reads, so they answer "do the
 * scores the app is using agree with Sleeper's own totals".
 */
@Service
public class ScoreOverrideService {

    public static final String MATCH = "MATCH";
    public static final String MISMATCH = "MISMATCH";
    public static final String UNAVAILABLE = "UNAVAILABLE";

    public record Team(int rosterId, String teamName, BigDecimal feedPoints, BigDecimal inUsePoints,
                       BigDecimal overridePoints) {}

    public record TotalsRow(int rosterId, String teamName, BigDecimal inUseTotal, BigDecimal sleeperTotal) {}

    public record Totals(String status, Integer throughWeek, List<Integer> feedWeeks, List<TotalsRow> rows) {}

    public record RecordRow(int rosterId, String teamName, String computed, String sleeper) {}

    public record Records(String status, Integer throughWeek, List<Integer> feedWeeks, List<RecordRow> rows) {}

    public record Checks(Totals totals, Records record) {}

    /** {@code enteredAt} is an ISO-8601 string, null when nothing is entered for the week. */
    public record View(int week, boolean isFinal, boolean canEdit, boolean commissionerKnown, String source,
                       String enteredAt, List<Team> teams, Checks checks) {}

    private final WeekScoreResolver resolver;
    private final WeekScoreOverrideRepository overrides;
    private final RosterWeekPointsRepository weekPoints;
    private final LeagueMatchupRepository matchups;
    private final RosterSeasonRepository rosterSeasons;
    private final RosterFptsSnapshotRepository fptsSnapshots;
    private final LeagueMemberRepository leagueMembers;
    private final LeagueMembership membership;
    private final LeagueRepository leagues;
    private final ScoredWeeks scoredWeeks;
    private final ManagerRepository managers;

    public ScoreOverrideService(WeekScoreResolver resolver, WeekScoreOverrideRepository overrides,
                                RosterWeekPointsRepository weekPoints, LeagueMatchupRepository matchups,
                                RosterSeasonRepository rosterSeasons, RosterFptsSnapshotRepository fptsSnapshots,
                                LeagueMemberRepository leagueMembers, LeagueMembership membership,
                                LeagueRepository leagues, ScoredWeeks scoredWeeks, ManagerRepository managers) {
        this.resolver = resolver;
        this.overrides = overrides;
        this.weekPoints = weekPoints;
        this.matchups = matchups;
        this.rosterSeasons = rosterSeasons;
        this.fptsSnapshots = fptsSnapshots;
        this.leagueMembers = leagueMembers;
        this.membership = membership;
        this.leagues = leagues;
        this.scoredWeeks = scoredWeeks;
        this.managers = managers;
    }

    public boolean isFinal(long leagueId, int week) {
        return scoredWeeks.of(leagueId).isFinal(week);
    }

    /** The rosters that have a stored (feed) score for the week: the set a save must name exactly. */
    public Set<Integer> storedRosters(long leagueId, int week) {
        return weekPoints.feedForWeek(leagueId, week).keySet();
    }

    public void save(LeagueRepository.LeagueRow row, int week, Map<Integer, BigDecimal> points, String sleeperUserId) {
        overrides.replaceWeek(row.id(), week, points, ManagerRepository.caller(managers, row.id(), sleeperUserId));
    }

    public void clear(LeagueRepository.LeagueRow row, int week) {
        overrides.clearWeek(row.id(), week);
    }

    public View view(LeagueRepository.LeagueRow row, int week, String sleeperUserId) {
        long id = row.id();
        WeekScoreResolver.Resolution resolution = resolver.resolve(List.of(id));
        Map<Integer, BigDecimal> feed = weekPoints.feedForWeek(id, week);
        List<WeekScoreOverrideRepository.Row> entered = overrides.forWeek(id, week);
        Map<Integer, BigDecimal> enteredByRoster = new HashMap<>();
        String enteredAt = null;
        for (WeekScoreOverrideRepository.Row r : entered) {
            enteredByRoster.put(r.rosterId(), r.points());
            if (r.enteredAt() != null && (enteredAt == null || r.enteredAt().toString().compareTo(enteredAt) > 0)) {
                enteredAt = r.enteredAt().toString();
            }
        }

        List<RosterSeasonRepository.StandingRow> standings = rosterSeasons.forLeague(id);
        Long callerManagerId = ManagerRepository.caller(managers, id, sleeperUserId);
        Map<Integer, RosterOwners.RosterOwner> owners =
                RosterOwners.ownerNames(standings, leagueMembers.forLeague(id), callerManagerId);

        List<Team> teams = new ArrayList<>();
        for (Map.Entry<Integer, BigDecimal> e : feed.entrySet()) {
            int rosterId = e.getKey();
            teams.add(new Team(rosterId, nameOf(owners, rosterId), e.getValue(),
                    resolution.points(id, week, rosterId, e.getValue()), enteredByRoster.get(rosterId)));
        }

        return new View(week, scoredWeeks.of(id).isFinal(week), membership.canCommission(id, sleeperUserId),
                leagueMembers.anyCommissioner(id), resolution.source(id, week).name(), enteredAt, teams,
                checks(row, standings, owners, resolution));
    }

    private static String nameOf(Map<Integer, RosterOwners.RosterOwner> owners, int rosterId) {
        RosterOwners.RosterOwner o = owners.get(rosterId);
        return o != null ? o.name() : "Roster " + rosterId;
    }

    // ------------------------------------------------------------- FR-013 checks

    private Checks checks(LeagueRepository.LeagueRow row, List<RosterSeasonRepository.StandingRow> standings,
                          Map<Integer, RosterOwners.RosterOwner> owners,
                          WeekScoreResolver.Resolution resolution) {
        long id = row.id();
        Map<Integer, Map<Integer, BigDecimal>> snapshots = fptsSnapshots.byWeek(id);
        if (snapshots.isEmpty()) {
            return new Checks(new Totals(UNAVAILABLE, null, List.of(), List.of()), new Records(UNAVAILABLE, null, List.of(), List.of()));
        }
        int through = snapshots.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
        // Weeks 1..N still on the public feed: a mismatch there is expected, and the panel says so.
        List<Integer> feedWeeks = new ArrayList<>();
        for (int w = 1; w <= through; w++) {
            if (resolution.source(id, w) == WeekScoreResolver.Source.FEED) feedWeeks.add(w);
        }
        return new Checks(totals(id, through, feedWeeks, snapshots.get(through), owners),
                records(row, through, feedWeeks, standings, owners));
    }

    /** Per roster: the in-use scores of weeks 1..N summed vs Sleeper's season total through N, to the cent. */
    private Totals totals(long id, int through, List<Integer> feedWeeks, Map<Integer, BigDecimal> sleeper,
                          Map<Integer, RosterOwners.RosterOwner> owners) {
        List<RosterWeekPointsRepository.ScoreRow> scored =
                weekPoints.allScores(List.of(id), WeekBound.through(through));
        Map<Integer, Set<Integer>> weeksByRoster = new HashMap<>();
        Map<Integer, BigDecimal> sums = new TreeMap<>();
        for (RosterWeekPointsRepository.ScoreRow r : scored) {
            sums.merge(r.rosterId(), r.points(), BigDecimal::add);
            weeksByRoster.computeIfAbsent(r.rosterId(), k -> new java.util.HashSet<>()).add(r.week());
        }
        // Every roster Sleeper totals must have all of weeks 1..N stored, or the sum is not comparable.
        for (int rosterId : sleeper.keySet()) {
            Set<Integer> have = weeksByRoster.getOrDefault(rosterId, Set.of());
            for (int w = 1; w <= through; w++) {
                if (!have.contains(w)) return new Totals(UNAVAILABLE, through, feedWeeks, List.of());
            }
        }
        boolean allMatch = true;
        List<TotalsRow> rows = new ArrayList<>();
        for (Map.Entry<Integer, BigDecimal> e : new TreeMap<>(sleeper).entrySet()) {
            BigDecimal mine = sums.getOrDefault(e.getKey(), BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
            BigDecimal theirs = e.getValue().setScale(2, RoundingMode.HALF_UP);
            if (mine.compareTo(theirs) != 0) allMatch = false;
            rows.add(new TotalsRow(e.getKey(), nameOf(owners, e.getKey()), mine, theirs));
        }
        return new Totals(allMatch ? MATCH : MISMATCH, through, feedWeeks, rows);
    }

    /**
     * Per roster: W-L(-T) recomputed from the in-use paired games through N (head-to-head plus the median game
     * when the league plays one, {@link WeeklyReportService#recordsThrough} -- the report's own rule, reused)
     * vs {@code roster_season}'s wins/losses/ties. Only when the stored final weeks reach N.
     */
    private Records records(LeagueRepository.LeagueRow row, int through, List<Integer> feedWeeks,
                            List<RosterSeasonRepository.StandingRow> standings,
                            Map<Integer, RosterOwners.RosterOwner> owners) {
        ScoredWeeks.Snapshot scored = scoredWeeks.of(row.id());
        if (scored.latestFinal() < through) return new Records(UNAVAILABLE, through, feedWeeks, List.of());
        for (RosterSeasonRepository.StandingRow s : standings) {
            if (s.wins() == null || s.losses() == null) return new Records(UNAVAILABLE, through, feedWeeks, List.of());
        }
        if (standings.isEmpty()) return new Records(UNAVAILABLE, through, feedWeeks, List.of());

        List<LeagueMatchupRepository.PairedGame> paired =
                matchups.pairedWithScores(List.of(row.id()), WeekBound.ALL_WEEKS);
        Map<Integer, String> computed = WeeklyReportService.recordsThrough(paired, row.season(), through,
                leagues.playoffFormat(row.id()));
        boolean allMatch = true;
        List<RecordRow> rows = new ArrayList<>();
        List<RosterSeasonRepository.StandingRow> sorted = new ArrayList<>(standings);
        sorted.sort(java.util.Comparator.comparingInt(RosterSeasonRepository.StandingRow::rosterId));
        for (RosterSeasonRepository.StandingRow s : sorted) {
            int ties = s.ties() == null ? 0 : s.ties();
            String theirs = ties > 0 ? s.wins() + "-" + s.losses() + "-" + ties : s.wins() + "-" + s.losses();
            String mine = computed.getOrDefault(s.rosterId(), "0-0");
            if (!mine.equals(theirs)) allMatch = false;
            rows.add(new RecordRow(s.rosterId(), nameOf(owners, s.rosterId()), mine, theirs));
        }
        return new Records(allMatch ? MATCH : MISMATCH, through, feedWeeks, rows);
    }
}
