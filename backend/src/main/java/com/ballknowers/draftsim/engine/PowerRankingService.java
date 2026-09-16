package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.domain.*;
import com.ballknowers.draftsim.ingest.BoardService;
import com.ballknowers.draftsim.ingest.RosterOwnerMapper;
import com.ballknowers.draftsim.ingest.SleeperClient;
import com.ballknowers.draftsim.sport.SportRules;
import com.ballknowers.draftsim.sport.SportRulesRegistry;
import com.ballknowers.draftsim.store.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

/**
 * claude/league-suite.md "Power rankings, three ways" -- COMMISSIONER (Allan's
 * own ordering) and COMPUTED_REALIZED. Needs no auth (Phase A); the third
 * mode, league-member ballots, is Phase B and not built here.
 *
 * COMPUTED_REALIZED no longer claims to answer "how good is this team right
 * now" for a played week -- see claude/league-suite.md's "honest caveat" and
 * Phase A acceptance criterion 3, a hot start survives an injury it
 * shouldn't. Week 0 is the exception: a one-time preseason baseline (this
 * app's board value on each roster's best starting lineup, at the moment
 * someone first asked), written once via {@link #computeWeek0IfMissing} and
 * never overwritten by a later compute the way weeks 1+ are -- see that
 * method's own doc for why. Both facts ride in each entry's {@code note}, not
 * just in this comment.
 */
@Service
public class PowerRankingService {

    private static final Logger log = LoggerFactory.getLogger(PowerRankingService.class);
    private static final Set<String> BENCHED_INJURY_STATUSES = Set.of("OUT", "DOUBTFUL");

    private final SleeperClient sleeper;
    private final BoardService boards;
    private final PlayerRepository players;
    private final LeagueRepository leagues;
    private final ManagerRepository managers;
    private final RosterSeasonRepository rosterSeasons;
    private final RosterWeekPointsRepository weekPoints;
    private final PowerRankingRepository rankings;
    private final SportRulesRegistry rulesRegistry;

    public PowerRankingService(SleeperClient sleeper, BoardService boards, PlayerRepository players,
                               LeagueRepository leagues, ManagerRepository managers,
                               RosterSeasonRepository rosterSeasons, RosterWeekPointsRepository weekPoints,
                               PowerRankingRepository rankings, SportRulesRegistry rulesRegistry) {
        this.sleeper = sleeper;
        this.boards = boards;
        this.players = players;
        this.leagues = leagues;
        this.managers = managers;
        this.rosterSeasons = rosterSeasons;
        this.weekPoints = weekPoints;
        this.rankings = rankings;
        this.rulesRegistry = rulesRegistry;
    }

    public record SportState(int week, String season, String seasonStartDate, boolean started) {}

    /**
     * claude/plan-review-league-suite.md finding 3: the only way to know what
     * week it is. Per-sport since claude/nba-power-rankings.md -- this used to
     * hardcode {@code "nfl"}, and three {@code Sport.NFL} gates elsewhere
     * existed purely because it did.
     *
     * <p><b>{@code week} is legitimately 0, and 0 is falsy in the language on
     * the other end of the wire.</b> Measured 2026-09-15: {@code /state/nba}
     * answers {@code week: 0, season_type: "off"} for the whole offseason,
     * where {@code /state/nfl} answers 2. Every caller comparing against this
     * must use {@code ==} or a null check, never truthiness -- football is
     * never at week 0 during a season, so a {@code if (!week)} written here
     * fails only for basketball and only silently.
     */
    public SportState sportState(Sport sport) {
        Map<String, Object> s = sleeper.state(sport.code());
        int week = ((Number) s.getOrDefault("week", 1)).intValue();
        String season = String.valueOf(s.get("season"));
        String startDate = String.valueOf(s.get("season_start_date"));
        boolean started;
        try {
            started = !LocalDate.now().isBefore(LocalDate.parse(startDate));
        } catch (Exception e) {
            started = false;
        }
        return new SportState(week, season, startDate, started);
    }

    private record Scored(int rosterId, Long managerId, double score, String note) {}

    /**
     * Computes and saves the week-0 preseason baseline, but ONLY if one
     * doesn't already exist for this league+season -- unlike every other
     * week, week 0 is written once and then frozen. It exists to answer "what
     * did this roster look like before any games were played", and a manager
     * trading players in week 5 must not be able to silently rewrite that
     * answer just by someone clicking "Compute" again. Returns no entries and
     * no reason when week 0 is already set -- that is an ordinary no-op, not a
     * gap -- and no entries WITH a reason when the league has not drafted yet.
     */
    public Week0Result computeWeek0IfMissing(long leagueId, String sleeperLeagueId, int season) {
        if (rankings.exists(leagueId, season, 0, "COMPUTED_REALIZED")) {
            return new Week0Result(new PowerRankingRepository.Entry[0], null);
        }
        return computePreseasonBaseline(leagueId, sleeperLeagueId, season);
    }

    /**
     * @param skipped why nothing was written, or null when something was --
     *                including the ordinary "already set" no-op, which is not
     *                a gap and says nothing.
     */
    public record Week0Result(PowerRankingRepository.Entry[] entries, String skipped) {}

    /**
     * Sum of {@link SportRules#startingLineupValue} over each roster's ENTIRE
     * player pool (not Sleeper's own {@code starters} snapshot) -- the engine
     * picks the best-value starting lineup itself, which is what actually
     * answers "not the whole roster" (a bench full of elite QBs still doesn't
     * count) without trusting a manager to have remembered to set his lineup.
     * A rostered player who is OUT/Doubtful, or who isn't on this app's board
     * at all, is excluded before scoring -- Phase A acceptance criterion 4.
     *
     * Saves at week 0 under kind COMPUTED_REALIZED unless the league has no
     * rostered players at all (see the refusal below) -- callers wanting the
     * "write once" behaviour should go through {@link #computeWeek0IfMissing}
     * instead of calling this directly.
     */
    private Week0Result computePreseasonBaseline(long leagueId, String sleeperLeagueId,
                                                  int season) {
        LeagueRepository.LeagueRow leagueRow = leagues.byId(leagueId)
                .orElseThrow(() -> new IllegalStateException("no league row for id " + leagueId));
        LeagueSettings settings = LeagueRepository.toSettings(leagueRow, leagueRow.rosterPositions().size());
        SportRules rules = rulesRegistry.get(settings.sport());

        Map<String, Long> playerIdBySleeperId = players.idsBySleeperId(settings.sport());
        Map<Long, BoardEntry> boardByPlayerId = new HashMap<>();
        boards.currentBoard(settings.sport()).forEach(be -> boardByPlayerId.put(be.player().id(), be));
        Map<String, Long> managerBySleeperUserId = managers.idsBySleeperUserId();

        List<Scored> scored = new ArrayList<>();
        int rosteredAcrossLeague = 0;
        for (Map<String, Object> roster : sleeper.rosters(sleeperLeagueId)) {
            int rosterId = asInt(roster.get("roster_id"), -1);
            if (rosterId < 0) continue;
            Object ownerId = roster.get("owner_id");
            Long managerId = ownerId == null ? null : managerBySleeperUserId.get(String.valueOf(ownerId));

            @SuppressWarnings("unchecked")
            List<String> rosterPlayers = (List<String>) roster.getOrDefault("players", List.of());
            rosteredAcrossLeague += rosterPlayers.size();

            RosterState state = new RosterState();
            int excludedInjured = 0, excludedOffBoard = 0;
            for (String sleeperPlayerId : rosterPlayers) {
                Long playerId = playerIdBySleeperId.get(sleeperPlayerId);
                BoardEntry be = playerId == null ? null : boardByPlayerId.get(playerId);
                if (be == null) { excludedOffBoard++; continue; }
                String injury = be.player().injuryStatus();
                if (injury != null && BENCHED_INJURY_STATUSES.contains(injury.trim().toUpperCase())) {
                    excludedInjured++;
                    continue;
                }
                state.add(be);
            }

            double value = rules.startingLineupValue(state, settings);
            String note = note(excludedInjured, excludedOffBoard);
            scored.add(new Scored(rosterId, managerId, value, note));
        }

        // An undrafted league is NOT a league of equally bad teams, and this
        // snapshot is write-once -- so getting it wrong here is permanent.
        //
        // The count is of ROSTERED players, deliberately, not of players who
        // survived the board/injury filters above. "Nobody has drafted" and
        // "this app's board does not cover these players" are different
        // states with different fixes, and only the first one is this
        // refusal's business -- the second keeps its existing behaviour of
        // scoring what it can and saying in the note what it dropped.
        // Measured live 2026-09-15 on the pre_draft NBA league: every roster
        // comes back `players: []`, every startingLineupValue is 0.0, and
        // rankDescending happily returns a full twelve-way tie at rank 1 that
        // computeWeek0IfMissing would then refuse to ever overwrite. Same
        // discipline PlayoffOddsService applies to a league with no scored
        // games: the honest answer before there are rosters is no answer.
        //
        // Deliberately not basketball-specific. A football league reached
        // before its draft has exactly this shape; basketball is merely the
        // first to get here, because its season starts five weeks later.
        if (rosteredAcrossLeague == 0) {
            log.info("preseason baseline: league {} has no rostered players at all, nothing written", leagueId);
            return new Week0Result(new PowerRankingRepository.Entry[0],
                    "every roster is empty -- this league has not drafted yet, so there is no preseason baseline"
                            + " to take. Compute again once the draft is done.");
        }

        var ranked = rankDescending(scored);
        // Same reasoning as computeRealized: nothing to rank is not a snapshot.
        // Reachable here when Sleeper returns no rosters for the league.
        if (!ranked.isEmpty()) rankings.save(leagueId, season, 0, "COMPUTED_REALIZED", ranked);
        return new Week0Result(ranked.toArray(new PowerRankingRepository.Entry[0]), null);
    }

    private static String note(int excludedInjured, int excludedOffBoard) {
        List<String> parts = new ArrayList<>();
        if (excludedInjured > 0) {
            parts.add(excludedInjured + " OUT/Doubtful starter(s) excluded");
        }
        if (excludedOffBoard > 0) {
            parts.add(excludedOffBoard + " rostered player(s) not on the board, excluded");
        }
        return parts.isEmpty() ? "preseason baseline: this app's board, roster at first compute" : String.join("; ", parts);
    }

    /**
     * Cumulative points-per-game through {@code week}, from each week's own
     * actual starting lineup ({@code roster_week_points.starters_points}) --
     * backward-looking by construction, so a player who got hurt in week 9
     * still counts for weeks 1-8 exactly as he produced then.
     */
    public PowerRankingRepository.Entry[] computeRealized(long leagueId, int season, int week) {
        Map<Integer, Long> managerByRoster = new HashMap<>();
        rosterSeasons.forLeague(leagueId).forEach(r -> managerByRoster.put(r.rosterId(), r.managerId()));

        Map<Integer, List<Double>> byRoster = new LinkedHashMap<>();
        for (RosterWeekPointsRepository.WeekPoint wp : weekPoints.through(leagueId, week)) {
            byRoster.computeIfAbsent(wp.rosterId(), k -> new ArrayList<>()).add(wp.startersPoints());
        }

        List<Scored> scored = new ArrayList<>();
        for (var e : byRoster.entrySet()) {
            List<Double> weeks = e.getValue();
            double avg = weeks.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            String note = "avg " + String.format("%.1f", avg) + " starter pts/wk over " + weeks.size()
                    + " of " + week + " week(s)";
            scored.add(new Scored(e.getKey(), managerByRoster.get(e.getKey()), avg, note));
        }

        var ranked = rankDescending(scored);
        // Skipped rather than saved when empty -- see PowerRankingRepository.save,
        // which refuses it anyway. Calling it regardless would work, but the call
        // reads as though an empty ranking is a result; it isn't one.
        if (!ranked.isEmpty()) rankings.save(leagueId, season, week, "COMPUTED_REALIZED", ranked);
        return ranked.toArray(new PowerRankingRepository.Entry[0]);
    }

    /**
     * Why a REALIZED ranking for this league and week has nothing to rank, or
     * null when it does.
     *
     * REALIZED ranks on {@code roster_week_points}, which only exists once the
     * league-history ingest has run AND Sleeper has actually scored the week
     * ({@code settings.last_scored_leg} bounds that ingest). Before this, the
     * compute endpoint answered 200 with {@code "realized": 0} and no reason,
     * which is indistinguishable from a real result of nothing -- it read as a
     * persistence bug when it was a missing-ingest one, and cost an afternoon
     * of looking in the wrong place.
     */
    public String realizedGap(long leagueId, int week) {
        if (!weekPoints.through(leagueId, week).isEmpty()) return null;

        Set<Integer> stored = weekPoints.storedWeeks(leagueId);
        if (stored.isEmpty()) {
            return "no weekly scoring is stored for this league -- run"
                    + " POST /api/ingest/league-history/{sleeperLeagueId}; it ingests only weeks"
                    + " Sleeper has already scored, so a season that has not kicked off yields none";
        }
        return "weekly scoring is stored for week(s) " + stored.stream().sorted().toList()
                + ", none of them at or before week " + week;
    }

    /**
     * Allan's own ordering. No score behind it -- {@code orderedRosterIds[0]}
     * is 1st, and so on -- per claude/league-suite.md's y-axis argument: modes
     * 1 and 2 produce orderings only, so rank is the one axis all three modes
     * share.
     *
     * <p><b>claude/plan-review-power-rankings-ballots.md finding 16.</b>
     * {@code managerByRoster} used to come from {@code rosterSeasons.forLeague},
     * i.e. from league-history ingest -- on the very no-history league
     * claude/power-rankings-ballots.md's AC11 targets, that map is empty,
     * every entry gets {@code manager_id = null}, and the chart legend and
     * tooltip both render "roster 3". It is read live off
     * {@code sleeper.rosters(sleeperLeagueId)}'s own {@code owner_id}
     * instead -- the same join the ballot endpoint's {@code members[]} list
     * uses, and the same reason: it works before any history ingest has run.
     */
    public PowerRankingRepository.Entry[] saveCommissionerRanking(long leagueId, String sleeperLeagueId,
                                                                  int season, int week,
                                                                  List<Integer> orderedRosterIds) {
        Map<Integer, Long> managerByRoster = RosterOwnerMapper.rosterToManager(
                sleeper.rosters(sleeperLeagueId), managers.idsBySleeperUserId());

        List<PowerRankingRepository.Entry> entries = new ArrayList<>(orderedRosterIds.size());
        for (int i = 0; i < orderedRosterIds.size(); i++) {
            int rosterId = orderedRosterIds.get(i);
            entries.add(new PowerRankingRepository.Entry(rosterId, managerByRoster.get(rosterId), i + 1, null, null));
        }
        rankings.save(leagueId, season, week, "COMMISSIONER", entries);
        return entries.toArray(new PowerRankingRepository.Entry[0]);
    }

    public List<PowerRankingRepository.SnapshotRow> snapshots(long leagueId) {
        return rankings.forLeague(leagueId);
    }

    /**
     * Standard competition ranking (1, 2, 2, 4): equal scores share the lower
     * rank and the next rank is skipped -- claude/plan-review-league-suite.md
     * finding 4, decided once here rather than left to whichever renderer
     * draws the chart.
     *
     * <p>Delegates to {@link Ranker}, extracted for
     * claude/power-rankings-ballots.md's member-ballot aggregate, which needs
     * the identical tie rule in the opposite (ascending) direction -- see
     * {@link Ranker}'s own header for why that extraction is a
     * sign-inversion trap if the tie test is not equality-based.
     */
    private static List<PowerRankingRepository.Entry> rankDescending(List<Scored> scored) {
        List<Ranker.Ranked<Scored>> ranked = Ranker.rank(scored,
                Comparator.comparingDouble(Scored::score).reversed(), Scored::score);

        List<PowerRankingRepository.Entry> out = new ArrayList<>(ranked.size());
        for (Ranker.Ranked<Scored> r : ranked) {
            out.add(new PowerRankingRepository.Entry(
                    r.item().rosterId(), r.item().managerId(), r.rank(), r.score(), r.item().note()));
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
