package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.domain.*;
import com.ballknowers.draftsim.ingest.BoardService;
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
 * own ordering), COMPUTED_MARKET_VALUE and COMPUTED_REALIZED. Modes 1 and 3
 * need no auth (Phase A); the third mode, league-member ballots, is Phase B
 * and not built here.
 *
 * Neither computed mode claims to answer "how good is this team right now" --
 * see claude/league-suite.md's "honest caveat" and Phase A acceptance
 * criterion 3. MARKET_VALUE is a preseason-flavoured expectation that goes
 * stale the moment real games are played; REALIZED is backward-looking and a
 * hot start survives an injury it shouldn't. Both facts ride in each entry's
 * {@code note}, not just in this comment.
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

    public record NflState(int week, String season, String seasonStartDate, boolean started) {}

    /** claude/plan-review-league-suite.md finding 3: the only way to know what week it is. */
    public NflState nflState() {
        Map<String, Object> s = sleeper.state("nfl");
        int week = ((Number) s.getOrDefault("week", 1)).intValue();
        String season = String.valueOf(s.get("season"));
        String startDate = String.valueOf(s.get("season_start_date"));
        boolean started;
        try {
            started = !LocalDate.now().isBefore(LocalDate.parse(startDate));
        } catch (Exception e) {
            started = false;
        }
        return new NflState(week, season, startDate, started);
    }

    private record Scored(int rosterId, Long managerId, double score, String note) {}

    /**
     * Sum of {@link SportRules#startingLineupValue} over each roster's ENTIRE
     * player pool (not Sleeper's own {@code starters} snapshot) -- the engine
     * picks the best-value starting lineup itself, which is what actually
     * answers "not the whole roster" (a bench full of elite QBs still doesn't
     * count) without trusting a manager to have remembered to set his lineup.
     * A rostered player who is OUT/Doubtful, or who isn't on this app's board
     * at all, is excluded before scoring -- Phase A acceptance criterion 4.
     */
    public PowerRankingRepository.Entry[] computeMarketValue(long leagueId, String sleeperLeagueId,
                                                             int season, int week) {
        LeagueRepository.LeagueRow leagueRow = leagues.byId(leagueId)
                .orElseThrow(() -> new IllegalStateException("no league row for id " + leagueId));
        LeagueSettings settings = LeagueRepository.toSettings(leagueRow, leagueRow.rosterPositions().size());
        SportRules rules = rulesRegistry.get(settings.sport());

        Map<String, Long> playerIdBySleeperId = players.idsBySleeperId(settings.sport());
        Map<Long, BoardEntry> boardByPlayerId = new HashMap<>();
        boards.currentBoard(settings.sport()).forEach(be -> boardByPlayerId.put(be.player().id(), be));
        Map<String, Long> managerBySleeperUserId = managers.idsBySleeperUserId();

        List<Scored> scored = new ArrayList<>();
        for (Map<String, Object> roster : sleeper.rosters(sleeperLeagueId)) {
            int rosterId = asInt(roster.get("roster_id"), -1);
            if (rosterId < 0) continue;
            Object ownerId = roster.get("owner_id");
            Long managerId = ownerId == null ? null : managerBySleeperUserId.get(String.valueOf(ownerId));

            @SuppressWarnings("unchecked")
            List<String> rosterPlayers = (List<String>) roster.getOrDefault("players", List.of());

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

        var ranked = rankDescending(scored);
        // Same reasoning as computeRealized: nothing to rank is not a snapshot.
        // Reachable here when Sleeper returns no rosters for the league.
        if (!ranked.isEmpty()) rankings.save(leagueId, season, week, "COMPUTED_MARKET_VALUE", ranked);
        return ranked.toArray(new PowerRankingRepository.Entry[0]);
    }

    private static String note(int excludedInjured, int excludedOffBoard) {
        List<String> parts = new ArrayList<>();
        if (excludedInjured > 0) {
            parts.add(excludedInjured + " OUT/Doubtful starter(s) excluded");
        }
        if (excludedOffBoard > 0) {
            parts.add(excludedOffBoard + " rostered player(s) not on the board, excluded");
        }
        return parts.isEmpty() ? "market value: this app's board, current roster" : String.join("; ", parts);
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
     */
    public PowerRankingRepository.Entry[] saveCommissionerRanking(long leagueId, int season, int week,
                                                                  List<Integer> orderedRosterIds) {
        Map<Integer, Long> managerByRoster = new HashMap<>();
        rosterSeasons.forLeague(leagueId).forEach(r -> managerByRoster.put(r.rosterId(), r.managerId()));

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
     * draws the chart. Scores are rounded to the stored precision (4dp)
     * before comparing, so two doubles that are "equal" up to float noise but
     * would print identically don't get spuriously separate ranks.
     */
    private static List<PowerRankingRepository.Entry> rankDescending(List<Scored> scored) {
        List<Scored> sorted = new ArrayList<>(scored);
        sorted.sort((a, b) -> Double.compare(b.score(), a.score()));

        List<PowerRankingRepository.Entry> out = new ArrayList<>(sorted.size());
        int rank = 0, seen = 0;
        Double prevRounded = null;
        for (Scored s : sorted) {
            seen++;
            double rounded = Math.round(s.score() * 10000.0) / 10000.0;
            if (prevRounded == null || rounded < prevRounded) {
                rank = seen;
                prevRounded = rounded;
            }
            out.add(new PowerRankingRepository.Entry(s.rosterId(), s.managerId(), rank, rounded, s.note()));
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
